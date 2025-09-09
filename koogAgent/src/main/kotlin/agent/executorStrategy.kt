package org.example.agent

import ai.koog.agents.core.agent.entity.AIAgentStrategy
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.executeTool
import ai.koog.agents.core.environment.result
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.Message.Role
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.Json
import org.example.tools.ProofCheckResponse
import org.example.tools.explainCheckProofResponse
import org.example.utils.ResolvedAgentConfig
import org.example.utils.ResolvedModelConfig
import kotlinx.datetime.Clock
import org.example.tools.retrieveContextPremises
import java.util.logging.Logger
import kotlin.collections.plus

fun rocqStarExecutorStrategy(
    agentConfig: ResolvedAgentConfig,
    logger: Logger
): AIAgentStrategy<PlanExecutionState, PlanExecutionResult> {
    return strategy("executor-strategy") {
        val nodeCallExecutorModel by executorModelCall(
            "send-input-to-executor-model",
            agentConfig.generators.executor,
            logger,
        )
        val nodeExecuteTool by nodeExecuteTool(
            "execute-tool",
            logger,
        )
        val criticModelCall by executorModelCall(
            "send-input-to-critic-model",
            agentConfig.generators.proofProgressCritic,
            logger,
            canCallTools = false,
            buildPrompt = { state ->
                // Note that this message below is not appended to the execution history.
                // Moreover, by default, executorModelCall doesn't modify the current state
                // of the history other than described in the `applyResponse` block
                state.prompt.messages + userWithMeta(
                    "Critique the last actions under overall plan:" +
                    wrapPromptElement(state.currentPlan, "plan") +
                    "Highlight deviations and suggest improvements. " +
                    "Think about what context should be gathered to prove the theorem. " +
                    "Remember you have access to other files and theorems. " +
                    "If you propose to use specific tool write its name as `tool_name`. " +
                    "Propose to continue by applying tactic by tactic when you think it is useful. " +
                    "Here is the description of the tools:" +
                    wrapPromptElement(state.toolsSummary, "tools") +
                    "Do NOT CALL TOOLS."
                )
            }
        )
        val replanModelCall by executorModelCall(
            "send-input-to-replanning-model",
            agentConfig.generators.replanner,
            logger,
            canCallTools = false,
            buildPrompt = { state ->
                state.prompt.messages + userWithMeta(
            "Refine the proof plan:" +
                    wrapPromptElement(state.currentPlan, "plan") +
                    "using the critique above and similar-proof insights. " +
                    "Pay attention to what tools are proposed to be called. " +
                    "Here is the description of the tools:" +
                    wrapPromptElement(state.toolsSummary, "tools") +
                    "Output **only** the updated plan in clear natural language."
                )
            },
            applyResponse = { st, response ->
                prompt(st.prompt) {
                    user(
                        "I have refined the plan based on the current proof progress: ${response.content}\n" +
                                "Now continue with following this plan and calling tools"
                    )
                }
            }
        )

        val getSimilarProofs by executorModelCall(
            "force-similar-proofs-from-file",
            agentConfig.generators.similarTheoremsAnalyzer,
            logger,
            canCallTools = false,
            buildPrompt = { state ->
                val premises = retrieveContextPremises(
                    state.targetPath,
                    state.proofSessionManager,
                    agentConfig.maximumPremisesFromRanker,
                    logger
                )

                // This fully rewrites the prompt for the current LLM call into
                // a unique one, as similar proof analyzer doesn't look at the proof history
                listOf<Message>(
                    systemWithMeta("You are a proficient Rocq programmer"),
                    // TODO: Here state.theoremStatement is always the starting state of the theorem,
                    // while it should rather be the theorem constructed from the current state
                    userWithMeta("The current theorem statement is ${state.theoremStatement}\n" +
                            "List tactics, ideas, theorems and proof parts you can borrow to advance our proof. " +
                            "(Do not call any tools.) Here are some similar proofs to the goal of after valid proof prefix:" +
                            wrapPromptElement(premises.asString()) +
                            "Return some ideas on what of this can be helpful for proving the theorem."),
                )
            },
            applyResponse = { st, response ->
                prompt(st.prompt) { user(response.content) }
            }
        )

        val summarizerNode by executorModelCall(
            "summarizer-model-call",
            agentConfig.generators.summarizer,
            logger,
            canCallTools = false,
            buildPrompt = { state ->
                // TODO: Here we call the messagesToSummarize twice, which indeed
                // brings computational overhead, refactor that
                val (toSummarize, _) = messagesToSummarize(state)
                val jointToSummarize = toSummarize.joinToString("\n") { it.content }
                val summarizerUserMessage =
                    "Please produce a concise bullet-point summary of the proof progress so far (4-5) bullet points:\n\n" +
                            wrapPromptElement(jointToSummarize, "to summarize") +
                            "DO NOT CALL TOOLS."

                listOf<Message>(
                    systemWithMeta("You are a proficient Rocq programmer"),
                    userWithMeta(summarizerUserMessage),
                )
            },
            applyResponse = { state, response ->
                prompt("updated-prompt-after-summary") {
                    val (_, remainingMessages) = messagesToSummarize(state)
                    listOf(userWithMeta("Conversation summary so far:\n$response")) + remainingMessages
                }
            }
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

        // At first, we go to the executor model
        edge(nodeStart forwardTo nodeCallExecutorModel)
        // If the executor model returned a tool-call, we put the tool-call
        // into the state and proceed in the execute-tool node
        edge(
            nodeCallExecutorModel forwardTo nodeExecuteTool
                    onCondition { st -> st.lastToolCall != null }
        )

        // In case we exceeded the allowed tool-invocations or type-checked the valid proof,
        // we finish the execution
        edge(
            nodeExecuteTool forwardTo extractProofResult
                    onCondition { st ->
                        st.numberToolCalls >= agentConfig.totalAllowedToolCalls ||
                        st.finishedProof != null
                    }
        )
        // Extract the output and exit
        edge(extractProofResult forwardTo nodeFinish)

        // If we exceeded the number of allowed failed proof-checks in a row,
        // we proceed with a critic model to re-evaluate the plan
        edge(
            nodeExecuteTool forwardTo criticModelCall
                    onCondition { st ->
                        st.failedProofChecksInARow >= agentConfig.allowedFailedProofChecks
                    }
        )
        // Right after the critic, we fetch similar proofs to the current goal,
        // as we believe that the model is not progressing with the target proof.
        edge(criticModelCall forwardTo getSimilarProofs)
        // After seeing the similar proofs, we replan
        edge(getSimilarProofs forwardTo replanModelCall)
        // And proceed with the executor, with updated history
        edge(replanModelCall forwardTo nodeCallExecutorModel)

        // If the history is too long, summarize messages
        edge(nodeExecuteTool forwardTo summarizerNode
                onCondition { st -> st.prompt.messages.size > MAX_MESSAGES_BEFORE_SUMMARIZE }
        )
        edge(nodeCallExecutorModel forwardTo summarizerNode
                onCondition { st -> st.prompt.messages.size > MAX_MESSAGES_BEFORE_SUMMARIZE }
        )
        edge(summarizerNode forwardTo nodeCallExecutorModel)

        // I am not sure how is branch-priority implemented in koog,
        // therefore, currently it is like this in case conditions are not checked in order of declarations
        // TODO: fix
        edge(nodeExecuteTool forwardTo nodeCallExecutorModel
                onCondition { st ->
                    // Check that any other branch doesn't suit
                    st.prompt.messages.size <= MAX_MESSAGES_BEFORE_SUMMARIZE &&
                            st.failedProofChecksInARow < agentConfig.allowedFailedProofChecks &&
                            st.numberToolCalls < agentConfig.totalAllowedToolCalls &&
                            st.finishedProof == null
                }
        )
    }
}

/**
 * Default node to retrieve the answer from the model and put the answer to the history.
 * In comparison to the default koog nodeLLMRequest node, updates the prompt in a different way.
 * If a tool-call occurred, packs it into `lastToolCall` field of the state
 *
 * @param withProfile The configuration of the used model: includes
 * temperature, maximum used tokens, and the profile
 * @param buildPrompt Defines, how to build the prompt for the call, given the current
 * execution state. By default, takes the current execution history w/o modifications
 * @param applyResponse Given the current execution state and the model response, defines how to construct
 * the new state of the prompt. Default way to manage the response of the assistant: push it to the end of
 * the message history. When canCallTools = true and applyResponse redefines
 * the behavior, declining the tool-call, UB occurs; however, that doesn't make
 * sense semantically
 */
fun AIAgentSubgraphBuilderBase<*, *>.executorModelCall(
    name: String,
    withProfile: ResolvedModelConfig,
    logger: Logger,
    canCallTools: Boolean = true,
    buildPrompt: (PlanExecutionState) -> List<Message> = { it.prompt.messages },
    applyResponse: (PlanExecutionState, Message) -> Prompt = { st, response ->
        prompt(st.prompt) { message(response) }
    }
): AIAgentNodeDelegate<PlanExecutionState, PlanExecutionState> =
    node(name) { st ->
        llm.writeSession {
            this.model = withProfile.profile
            rewritePrompt {
                st.prompt.copy(
                    messages = buildPrompt(st),
                    params = LLMParams(temperature = withProfile.temperature)
                )
            }

            logger.info("Retrieving context with prompt: $prompt")

            val response = if (canCallTools) {
                requestLLM()
            } else {
                requestLLMWithoutTools()
            }
            // If the response from the assistant is a tool-call,
            // then we will successfully cast it and manage in the next node
            val toolAction = response as? Message.Tool.Call

            logger.info("Received response: $response, toolAction: $toolAction")

            st.copy(
                prompt = applyResponse(st, response),
                lastToolCall = toolAction,
            )
        }
    }

/**
 * Implements a default nodeExecuteTool node behavior. Differs from the koog analog in a way
 * it manages the prompt and the assistant's answer. Additionally, it listens to checkProof
 * tool and handles them differently, as our execution state updates on checkProof calls.
 */
fun AIAgentSubgraphBuilderBase<*, *>.nodeExecuteTool(
    name: String,
    logger: Logger,
): AIAgentNodeDelegate<PlanExecutionState, PlanExecutionState> =
    node(name) { st ->
        require(st.lastToolCall != null) {
            "The node execute tool-call was called, but the last message was not tool-call."
        }
        val toolCallResult: ReceivedToolResult = environment.executeTool(st.lastToolCall)

        // Additional checks in case the tool-call
        val (failedChecks, finishedProof, explanationMessage) =
            if (st.lastToolCall.tool == CHECK_PROOF_TOOL_NAME) {
                logger.info(
                    "checkProof tool-call resulted in content: -${toolCallResult.content}-, " +
                            "result: *${toolCallResult.result}*"
                )
                val checkProofResult = Json.decodeFromString(
                    ProofCheckResponse.serializer(),
                    toolCallResult.content
                )

                // We parse the result of the checkProof tool by ourselves to
                // overtake the responsibility for understanding the MCP-servers' response
                // from the assistant
                val explanation = explainCheckProofResponse(checkProofResult)

                // We allow only a given number of failed proof-checks in a row
                // (to adjust the plan in case of continuous failures) and update the counter here
                val newFailedChecks =
                    if (checkProofResult.success) 0 else st.failedProofChecksInARow + 1
                // In case a complete and a valid proof was sent for checking, automatically put it
                // into the result
                val proof = if (explanation.isProofComplete) checkProofResult.proof else null

                Triple(newFailedChecks, proof, explanation.explanationMessage)
            } else {
                Triple(st.failedProofChecksInARow, null, null)
            }

        logger.info(
            """
            |Name of the tool: ${st.lastToolCall.tool}
            |Current number of failed checks: $failedChecks
            |Proof is $finishedProof, explanation message: $explanationMessage
            """.trimMargin()
        )

        st.copy(
            prompt = prompt(st.prompt) {
                // For checkProof tool we substitute the response of the MCP to our explanation
                tool {
                    result(explanationMessage?.let { toolCallResult.copy(content = it) } ?: toolCallResult)
                }
            },
            lastToolCall = null,
            numberToolCalls = st.numberToolCalls + 1,
            failedProofChecksInARow = failedChecks,
            finishedProof = finishedProof,
        )
    }

fun userWithMeta(userMessage: String): Message.User {
    return Message.User(
        userMessage,
        metaInfo = RequestMetaInfo.create(Clock.System)
    )
}

fun systemWithMeta(systemMessage: String): Message.System {
    return Message.System(
        systemMessage,
        metaInfo = RequestMetaInfo.create(Clock.System)
    )
}

private fun messagesToSummarize(state: PlanExecutionState): Pair<List<Message>, List<Message>> {
    val rawMsgCount = state.prompt.messages.size
    val messagesToSummarize = mutableListOf<Message>()

    for (message in state.prompt.messages) {
        if (messagesToSummarize.size < rawMsgCount - KEEP_LAST_K_MESSAGES) {
            messagesToSummarize.add(message)
        } else {
            val lastAddedMessage = messagesToSummarize.last()
            if (lastAddedMessage.role == Role.Tool) {
                messagesToSummarize.add(message)
            }
            break
        }
    }

    val remainingMessages = state.prompt.messages.drop(messagesToSummarize.size)
    return messagesToSummarize to remainingMessages
}

const val CHECK_PROOF_TOOL_NAME = "checkProof"
const val MAX_MESSAGES_BEFORE_SUMMARIZE = 60
const val KEEP_LAST_K_MESSAGES = 20