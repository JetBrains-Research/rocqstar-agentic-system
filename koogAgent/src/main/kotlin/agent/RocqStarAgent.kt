package org.example.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import org.example.planning.generateMadPlan
import org.example.planning.generateSimplePlan
import org.example.tools.McpSessionManager
import org.example.tools.RocqMcpToolSet
import org.example.tools.RocqProofSessionManager
import org.example.utils.extractPropFromJsonString
import org.example.tools.getToolSummary
import org.example.tools.retrieveContextPremises
import org.example.utils.PlanningType
import org.example.utils.ResolvedAgentConfig
import org.example.utils.generateWithPrompt
import org.example.utils.generateWithPromptString
import java.net.http.HttpClient
import java.util.logging.Logger
import ai.koog.agents.features.opentelemetry.integration.langfuse.addLangfuseExporter
import org.example.agent.LangfuseConfig.Companion.fromAgentConfig
import kotlin.use

class RocqStarAgent(
    private val agentConfig: ResolvedAgentConfig,
    private val executor: SingleLLMPromptExecutor,
    private val mcpSessionManager: McpSessionManager,
    private val httpClient: HttpClient
) {
    suspend fun execute(
        theoremName: String,
        targetPath: String,
    ): GenerationResult {
        val proofSessionManager = RocqProofSessionManager(
            theoremName,
            targetPath,
            mcpSessionManager,
            logger,
            agentConfig.coqProjectServerBaseUrl,
            agentConfig.mcpServerBaseUrl,
            httpClient,
        )

        proofSessionManager.use { sessionManager ->
            val mcpTools = RocqMcpToolSet(
                sessionManager
            )

            val theoremStatement = sessionManager.getSessionTheorem().theoremStatement
            val toolsSummary = getToolSummary(mcpTools)

            val sortedPlans = generateSortedPlanCandidates(theoremStatement, toolsSummary)

            return iteratePlans(
                theoremStatement,
                targetPath,
                sortedPlans,
                toolsSummary,
                sessionManager,
                mcpTools
            )
        }
    }

    suspend fun iteratePlans(
        theoremStatement: String,
        targetPath: String,
        plans: List<String>,
        toolsSummary: String,
        sessionManager: RocqProofSessionManager,
        mcpTools: RocqMcpToolSet,
    ): GenerationResult {
        var executionHistorySummary: String? = null

        for ((index, plan) in plans.withIndex()) {
            if (index >= agentConfig.numBestPlansToExec) {
                return GenerationResult(false)
            }

            val execResult = executePlan(
                plan,
                executionHistorySummary,
                theoremStatement,
                targetPath,
                toolsSummary,
                sessionManager,
                mcpTools
            )
            if (execResult.isSuccessful) {
                return GenerationResult(true, execResult.completeProof)
            }
            executionHistorySummary = summarizePlanExecutionHistory(execResult.executionHistory)
        }

        return GenerationResult(false)
    }

    suspend fun executePlan(
        plan: String,
        summary: String?,
        theoremStatement: String,
        targetPath: String,
        toolsSummary: String,
        sessionManager: RocqProofSessionManager,
        mcpTools: RocqMcpToolSet,
    ): PlanExecutionResult {
        val similarProofs = retrieveContextPremises(
            targetPath,
            sessionManager,
            agentConfig.maximumPremisesFromRanker,
            logger
        )

        val executorBasePrompt = prompt(
            "executor-prompt",
            params = LLMParams(
                temperature = agentConfig.generators.executor.temperature,
            ),
        ) {
            system(executionSystemPrompt(theoremStatement, targetPath))
            user(executorUserMessage(theoremStatement, targetPath))
            if (summary != null) {
                system("Summary of the previous proof attempts:" +
                        wrapPromptElement(summary, "summary") +
                        "Continue theorem proving.")
            }
            user(
                "You should prove the theorem. Here is the plan you should follow. Plan:" +
                        wrapPromptElement(plan, "plan") +
                        "Here are the theorems whose proofs can be similar to the target proof:" +
                        wrapPromptElement(similarProofs.asString())
            )
        }

        val initialExecutionState = PlanExecutionState(
            theoremStatement,
            targetPath,
            executorBasePrompt,
            toolsSummary,
            plan,
            sessionManager
        )

        val agent = AIAgent(
            promptExecutor = executor,
            strategy = rocqStarExecutorStrategy(agentConfig, logger),
            toolRegistry = ToolRegistry {
                tools(mcpTools)
            },
            agentConfig = AIAgentConfig(
                prompt = executorBasePrompt,
                model = agentConfig.generators.executor.profile,
                // We manage iterations on our own, therefore here maxAgentIterations ~= INF
                maxAgentIterations = 1000,
            ),
            installFeatures = {
                val langFuseConfig = fromAgentConfig(agentConfig, logger)
                if (langFuseConfig != null) {
                    install(OpenTelemetry) {
                        setVerbose(true)
                        addLangfuseExporter(
                            langfuseUrl = langFuseConfig.host,
                            langfusePublicKey = langFuseConfig.publicKey,
                            langfuseSecretKey = langFuseConfig.secretKey
                        )
                    }
                }
            }
        )

        logger.info("Starting execution of an agent")
        return agent.run(initialExecutionState)
    }

    suspend fun summarizePlanExecutionHistory(history: Prompt): String {
        val summarizerUserRequest = "Summarize **why** the proof attempt failed, in 6-8 concise bullet points."
        // Map all systemMessages apart from the first one to user messages
        val refinedHistory = history.withMessages { messages ->
            messages.mapIndexed { index, m ->
                if (m.role == Message.Role.System && index > 0) {
                    Message.User(
                        "System message: ${m.content}",
                        m.metaInfo as RequestMetaInfo
                    )
                } else {
                    m
                }
            }
        }

        return generateWithPrompt(
            refinedHistory,
            summarizerUserRequest,
            agentConfig.generators.summarizer,
            executor
        )
    }

    suspend fun generateSortedPlanCandidates(
        theoremStatement: String,
        toolsSummary: String,
    ): List<String> {
        val plans = mutableListOf<String>()
        repeat(agentConfig.numPlansToGenerate) {
            val plan = if (agentConfig.planningType == PlanningType.Simple) {
                generateSimplePlan(
                    theoremStatement,
                    toolsSummary,
                    agentConfig,
                    executor
                )
            } else {
                generateMadPlan(
                    theoremStatement,
                    toolsSummary,
                    agentConfig,
                    executor
                )
            }

            plans.add(plan)
        }

        return sortPlans(
            theoremStatement,
            plans,
        )
    }

    // Sort plans according the score, given by the plan-ranker LLM
    suspend fun sortPlans(
        theoremStatement: String,
        plans: List<String>,
    ): List<String> {
        val systemPrompt = "You are a plan evaluator for Coq proof strategies. " +
                "You will get a theorem and one candidate plan. Rate its chance of success from 1 (low) to 10 (high). " +
                "Output **only** valid JSON: {{\"reason\":\"...\", \"score\":<integer>}}"
        val userPrompt: (String) -> String = { plan ->
            "Theorem: $theoremStatement\nPlan: ${wrapPromptElement(plan, "plan")}" +
                    "Respond with exactly: {{\"reason\":\"...\", \"score\":<1–10>}}"
        }

        val scorePlan: suspend (String) -> Int = { plan ->
            val response = generateWithPromptString(
                systemPrompt,
                userPrompt(plan),
                agentConfig.planning.planRanker,
                executor,
            )

            extractPropFromJsonString<Int>(response, "score")
        }

        return plans
            .map { plan -> plan to scorePlan(plan) }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    companion object {
        private val executionSystemPrompt: (String, String) -> String = { theoremStatement, filePath ->
            """
            |You are an expert Coq prover. Your mission is to produce a **correct**, **complete**, and **checkable** proof of the theorem  
            |`$theoremStatement` in file `$filePath`.
            |
            |• **Follow the agreed plan** step by step.  
            |• **Never** use `admit` or unsound shortcuts.  
            |• **Always** emit valid JSON when calling a tool.  
            |• After each proof step, invoke the `check_proof` tool and validate its JSON response.  
            |  – On error: parse the error message, adjust your call, and retry.  
            |• Avoid unnecessary goal-focusing; prefer high-level tactics first.  
            |• Keep your proof scripts concise, clear, and directly type-checkable by Coq.
            |
            |Begin now.
            """.trimMargin()
        }

        private val executorUserMessage: (String, String) -> String = { theoremStatement, filePath ->
            "Theorem to prove: $theoremStatement in file $filePath"
        }

        private val logger: Logger = Logger.getLogger(RocqStarAgent::class.java.name)
    }
}

typealias SimilarTheorems = List<Theorem>

fun SimilarTheorems.asString(): String =
    // TODO: Why do we have reversed here?
    asReversed()
        .joinToString("\n\n") { theorem ->
            "${theorem.theoremStatement}\n${theorem.proof ?: "(*Theorem is not proven in the source file.*)"}"
        }

data class Theorem(
    val theoremStatement: String,
    val proof: String?,
)

data class PlanExecutionState(
    val theoremStatement: String,
    val targetPath: String,
    val prompt: Prompt,
    val toolsSummary: String,
    val currentPlan: String,
    val proofSessionManager: RocqProofSessionManager,
    val lastToolCall: Message.Tool.Call? = null,
    val numberToolCalls: Int = 0,
    val failedProofChecksInARow: Int = 0,
    val finishedProof: String? = null,
)

data class PlanExecutionResult(
    val isSuccessful: Boolean,
    val executionHistory: Prompt,
    val completeProof: String?,
)

data class GenerationResult(
    val isSuccessful: Boolean,
    val completeProof: String? = null,
)