package org.example

import ai.koog.agents.core.agent.entity.AIAgentStrategy
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.executeTool
import ai.koog.agents.core.environment.result
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.Message.Role
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.Json
import org.example.tools.ProofCheckResponse
import org.example.tools.explainCheckProofResponse
import org.example.utils.ResolvedAgentConfig
import org.example.utils.ResolvedModelConfig
import kotlin.collections.orEmpty

/**
 * Input to the strategy is swallowed, as we already have all context
 */
fun getExecutorSubgraphStrategy(
    agentConfig: ResolvedAgentConfig,
    executionState: PlanExecutionState
): AIAgentStrategy<String, PlanExecutionResult> {
    return strategy("executor-strategy") {
        val initStateNode by node<String, PlanExecutionState>("initialize-executor-state") {
            executionState
        }
        val nodeCallExecutorModel by executorModelCall(
            "send-input-to-executor-model",
            agentConfig.generators.executor
        )
        val nodeExecuteTool by nodeExecuteTool(
            "execute-tool",
        )
        // TODO: ban from calling tools
        val criticModelCall by executorModelCall(
            "send-input-to-critic-model",
            agentConfig.generators.proofProgressCritic,
            prompt("critic-user-prompt") {
                user(
                    "Critique the last actions under overall plan:\n" +
                            "${executionState.currentPlan}\n" +
                            "Highlight deviations and suggest improvements. " +
                            "Think about what context should be gathered to prove the theorem. " +
                            "Remember you have access to other files and theorems. " +
                            "If you propose to use specific tool write its name as `tool_name`. " +
                            "Propose to continue by applying tactic by tactic when you think it is useful. " +
                            "Here is the description of the tools:\n" +
                            "${executionState.toolsSummary}\n" +
                            "Do NOT CALL TOOLS."
                )
            }
        )
        val replanModelCall by executorModelCall(
            "send-input-to-replanning-model",
            agentConfig.generators.replanner,
            prompt("critic-user-prompt") {
                user(
                    "Refine the proof plan:\n" +
                            "${executionState.currentPlan}\n " +
                            "using the critique above and similar-proof insights. " +
                            "Pay attention to what tools are proposed to be called. " +
                            "Here is the description of the tools:\n" +
                            "${executionState.toolsSummary}\n" +
                            "Output **only** the updated plan in clear natural language."
                )
            },
            applyResponse = {
                user("I have refined the plan based on the current proof progress: $it\n" +
                        "Now continue with following this plan and calling tools")
            }
        )

        val summarizerNode by summarizerNode(
            agentConfig.generators.summarizer,
        )

        val extractProofResult by node<PlanExecutionState, PlanExecutionResult>(
            "extract-proof-result"
        ) { st ->
            PlanExecutionResult(
            st.finishedProof != null,
            st.prompt,
            st.finishedProof
            )
        }

        edge(nodeStart forwardTo initStateNode)
        edge(initStateNode forwardTo nodeCallExecutorModel)
        edge(
            nodeCallExecutorModel forwardTo nodeExecuteTool
                    onCondition { st -> st.lastToolCall != null }
        )

        edge(
            nodeExecuteTool forwardTo extractProofResult
                    onCondition { st -> st.lastToolCall != null }
        )
        edge(extractProofResult forwardTo nodeFinish)
        edge(
            nodeExecuteTool forwardTo criticModelCall
                    onCondition { st ->
                        st.lastToolCall != null &&
                                st.lastToolCall.tool == "checkProof" &&
                                st.failedProofChecksInARow >= 5
                    }
        )

        edge(replanModelCall forwardTo nodeCallExecutorModel)
        edge(summarizerNode forwardTo nodeCallExecutorModel)
    }
}

fun AIAgentSubgraphBuilderBase<*, *>.summarizerNode(
    withProfile: ResolvedModelConfig,
): AIAgentNodeDelegate<PlanExecutionState, PlanExecutionState>  =
    node<PlanExecutionState, PlanExecutionState>("summarizer") { st ->
        val tailSize = 20
        val rawMsgCount = st.prompt.messages.size

        llm.writeSession {
            this.model = withProfile.profile

            val messagesToSummarize = mutableListOf<Message>()
            for (message in st.prompt.messages) {
                if (messagesToSummarize.size < rawMsgCount - tailSize) {
                    messagesToSummarize.add(message)
                } else {
                    val lastAddedMessage = messagesToSummarize.last()
                    if (lastAddedMessage.role == Role.Tool) {
                        messagesToSummarize.add(message)
                    }
                    break
                }
            }

            val remainingMessages = st.prompt.messages.drop(messagesToSummarize.size)

            val summarizerUserMessage =
                "Please produce a concise bullet-point summary of the proof progress so far (4-5) bullet points:\n\n" +
                        messagesToSummarize.joinToString("\n") { it.content } +
                        "DO NOT CALL TOOLS."

            val summarizerPrompt = prompt(
                "summarizer-prompt",
                params = LLMParams(temperature = withProfile.temperature)
            ) {
                system("You are a Rocq expert")
                user(summarizerUserMessage)
            }

            rewritePrompt { summarizerPrompt }

            val summarizedMessages = requestLLM()
            val basePrompt = prompt("updated-prompt-after-summary") {
                user("Conversation summary so far:\n$summarizedMessages")
            }

            st.copy(
                prompt = prompt("updated-prompt-after-summary") {
                    basePrompt.messages + remainingMessages
                }
            )
        }
    }

fun AIAgentSubgraphBuilderBase<*, *>.executorModelCall(
    name: String,
    withProfile: ResolvedModelConfig,
    extraMessages: Prompt? = null,
    applyResponse: PromptBuilder.(String) -> Unit = { assistant(it) }
): AIAgentNodeDelegate<PlanExecutionState, PlanExecutionState> =
    node(name) { st ->
        llm.writeSession {
            this.model = withProfile.profile
            rewritePrompt { _ ->
                st.prompt.copy(
                    messages = prompt.messages + (extraMessages?.messages.orEmpty()),
                    params = LLMParams(temperature = withProfile.temperature)
                )
            }

            val response = requestLLM()
            val toolAction = response as? Message.Tool.Call

            st.copy(
                prompt = prompt(st.prompt) {
                    applyResponse(response.content)
                },
                lastToolCall = toolAction,
            )
        }
    }

fun AIAgentSubgraphBuilderBase<*, *>.nodeExecuteTool(
    name: String? = null
): AIAgentNodeDelegate<PlanExecutionState, PlanExecutionState> =
    node(name) { st ->
        require(st.lastToolCall != null) {
            "The node execute tool-call was called, but the last message was not tool-call."
        }
        val toolCallResult: ReceivedToolResult = environment.executeTool(st.lastToolCall)

        val (failedChecks, finishedProof, explanationMessage) =
            if (st.lastToolCall.tool == "checkProof") {
                val checkProofResult = Json.decodeFromString(
                    ProofCheckResponse.serializer(),
                    st.lastToolCall.content
                )

                val explanation = explainCheckProofResponse(checkProofResult)

                val newFailedChecks =
                    if (checkProofResult.success) 0 else st.failedProofChecksInARow + 1
                val proof = if (explanation.isProofComplete) checkProofResult.proof else null

                Triple(newFailedChecks, proof, explanation.explanationMessage)
            } else {
                Triple(st.failedProofChecksInARow, null, null)
            }

        st.copy(
            prompt = prompt(st.prompt) {
                explanationMessage?.let { user(it) }
                    ?: tool { result(toolCallResult) }
            },
            lastToolCall = null,
            numberToolCalls = st.numberToolCalls + 1,
            failedProofChecksInARow = failedChecks,
            finishedProof = finishedProof,
        )
    }