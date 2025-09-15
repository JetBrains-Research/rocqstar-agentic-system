package org.example.planning

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentStrategy
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.ProvideStringSubgraphResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.utils.ResolvedAgentConfig
import org.example.utils.ResolvedModelConfig
import kotlin.collections.mutableListOf
import kotlin.collections.plus

private const val systemPromptDebaterA = "You are Debater A, a Coq expert. Use only natural language. Build on the tools above where helpful."
private const val userMessageDebaterA = "Outline your proof strategy this round, referencing tools if relevant."
private const val systemPromptDebaterB = "You are Debater B, a critical Coq theorist. Use natural language and tool references."
private const val userMessageDebaterB = "You will now see the last version of the plan by debater A. Critique and refine Debater A's approach, " +
    "suggesting tool-based improvements. Encounter critic fom Debater B, if present."
private const val systemPromptJudge = "You are the Judge: a neutral expert. Use natural language. RESPOND ONLY IN JSON FORMAT {{\\\"winner\\\": \\\"A\\\", \\\"plan\\\": \\\"...\\\"}}. " +
    "DO NOT SENT ANYTHING ELSE."
private const val userMessageJudge = "After reading all rounds, decide which plan is stronger ('A' or 'B') and provide a final consolidated proof plan.\n\n" +
    "Respond only as JSON: {{\"winner\": \"A\", \"plan\": \"...\"}}."

suspend fun generateMadPlan(
    theoremStatement: String,
    toolsetSummary: String,
    agentConfig: ResolvedAgentConfig,
    executor: PromptExecutor
): String {
    val toolRegistry = ToolRegistry {
        tool(ProvideStringSubgraphResult)
    }

    val initialSystemPrompt = "You are observing the debate on a Rocq theorem"
    val agent = AIAgent(
        executor = executor,
        systemPrompt = initialSystemPrompt,
        llmModel = agentConfig.planning.simplePlanning.profile,
        strategy = madDebateStrategy(
            agentConfig,
            toolsetSummary,
            theoremStatement,
            agentConfig.planning.madPlanning.madRoundsNumber
        ),
        toolRegistry = toolRegistry
    )

    val userMessage =
        "**Theorem to be proven:**\n$theoremStatement"

    val madPlan = agent.run(userMessage)

    return madPlan
}

/**
 * This custom node handles proper communication with the LLM,
 * it makes sure that LLM parameters, such as profile and temperature, are passed to the execution.
 * Additionally, it manages prompt-rewriting, because the default koog-way of building prompt between
 * LLM calls does not work in our scenario
 */
fun madDebateStrategy(
    agentConfig: ResolvedAgentConfig,
    toolsSummary: String,
    theoremStatement: String,
    rounds: Int
): AIAgentStrategy<String, String> {
    return strategy("mad-debate1") {
        // The state between the calls to different parts of the multi-agentic debate is stored
        // in the DebateState structure. It holds the current number and the messages
        // between debaters.
        val initStateNode by node<String, DebateState>("initialize-debate-state") { userInput ->
            DebateState (
                userInput,
                toolsSummary,
            )
        }

        val availableTools = "Available tools are: $toolsSummary"
        val theoremToProve = "**Theorem to be proven:**\n$theoremStatement"
        val totalRounds = rounds * 2

        val debaterABasePrompt = prompt("pro-llm-prompt") {
            system(systemPromptDebaterA)
            system(availableTools)
            user(theoremToProve)
        }
        val debaterA by makeDebaterRound(
            DebaterType.DebaterA,
            debaterABasePrompt,
            userMessageDebaterA,
            agentConfig.planning.madPlanning.proPlan
        )

        val debaterBBasePrompt = prompt("con-llm-prompt") {
            system(systemPromptDebaterB)
            system(availableTools)
            user(theoremToProve)
        }
        val debaterB by makeDebaterRound(
            DebaterType.DebaterB,
            debaterBBasePrompt,
            userMessageDebaterB,
            agentConfig.planning.madPlanning.conPlan
        )

        val judgeBasePrompt = prompt("judge-prompt") {
            system(systemPromptJudge)
            system(availableTools)
            user(theoremToProve)
        }

        val judge by node<DebateState, DebateResult>("judge-node") { st ->
            llm.writeSession {
                this.model = agentConfig.planning.madPlanning.judge.profile
                rewritePrompt {
                    val debateMessages = prompt("debate-messages") {
                        st.debateMessages.map { user(it) }
                        user(userMessageJudge)
                    }

                    prompt.copy(
                        messages = judgeBasePrompt.messages + debateMessages.messages,
                        params = LLMParams(temperature = agentConfig.planning.madPlanning.judge.temperature)
                    )
                }

                val llmResponse = requestLLMWithoutTools()
                Json.decodeFromString<DebateResult>(llmResponse.content)
            }
        }

        val extractDebateResult by node<DebateResult, String>("extract-winning-plan") { res -> res.plan }

        edge(nodeStart forwardTo initStateNode)
        edge(initStateNode forwardTo debaterA)

        // The strategy makes agents A and B communicate with each other
        // until we run out of rounds. On each round, the model sees
        // its default system prompt, the task description, and the collected
        // list of messages from both agents from before.
        edge(
            debaterA forwardTo debaterB
                    onCondition { st -> st.round < totalRounds }
        )
        edge(
            debaterB forwardTo debaterA
                    onCondition { st -> st.round < totalRounds }
        )

        edge(
            debaterA forwardTo judge
                    onCondition { st -> st.round >= totalRounds }
        )
        edge(
            debaterB forwardTo judge
                    onCondition { st -> st.round >= totalRounds }
        )

        // After we run out of rounds, the judge makes the decision on the final plan
        edge(judge forwardTo extractDebateResult)
        edge(extractDebateResult forwardTo nodeFinish)
    }
}

fun AIAgentSubgraphBuilderBase<*, *>.makeDebaterRound(
    debater: DebaterType,
    basePrompt: Prompt,
    userMessage: String,
    withProfile: ResolvedModelConfig,
): AIAgentNodeDelegate<DebateState, DebateState> =
    node(debater.name) { st ->
        llm.writeSession {
            this.model = withProfile.profile
            rewritePrompt {
                // Logic behind this prompt construction and role-interleave is that
                // debater A sees the history as if debater A = assistant, and debater B = user.
                // For debater B -- vice versa, as if debater A = user, and debater B = assistant.
                // Messages from previous rounds are inserted after the system instructions and
                // before the current assignment
                val debateMessages = prompt("debate-messages") {
                    st.debateMessages.mapIndexed { index, message ->
                        // If we are constructing history for the debaterA, assistant's messages
                        // are on even indices, otherwise -- on odd ones
                        val isAssistant = (debater == DebaterType.DebaterA) xor (index % 2 != 0)
                        if (isAssistant) {
                            assistant(message)
                        } else {
                            user(message)
                        }
                    }
                    user(userMessage)
                }

                prompt.copy(
                    messages = basePrompt.messages + debateMessages.messages,
                    params = LLMParams(temperature = withProfile.temperature)
                )
            }

            val llmResponse = requestLLMWithoutTools()

            // Update the debate state
            st.debateMessages.add("Debater ${debater.name} answered: ${llmResponse.content}")
            st.round += 1

            st
        }
    }

enum class DebaterType {
    DebaterA,
    DebaterB,
}

data class DebateState(
    val theoremStatement: String,
    val toolsSummary: String,
    var round: Int = 0,
    val debateMessages: MutableList<String> = mutableListOf()
)

@Serializable
data class DebateResult(
    val plan: String,
    val winner: String,
)