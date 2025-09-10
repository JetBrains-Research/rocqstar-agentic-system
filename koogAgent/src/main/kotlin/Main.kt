package org.example

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking
import org.example.agent.RocqStarAgent
import org.example.generation.GrazieConfig
import org.example.generation.simpleGrazieExecutor
import org.example.tools.McpSessionManager
import org.example.utils.Backend
import org.example.utils.GRAZIE_STAGING_URI
import org.example.utils.ResolvedAgentConfig
import org.example.utils.loadAgentConfig
import java.net.http.HttpClient
import java.nio.file.Path
import java.util.logging.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.readText

fun main() {
    runBlocking {
        val agentConfig = loadAgentConfig(Path.of("agent-config.yaml"))
        val executor = if (agentConfig.backend == Backend.Grazie) {
            val grazieConfig = GrazieConfig.fromAgentConfig(agentConfig)
            simpleGrazieExecutor(grazieConfig, GRAZIE_STAGING_URI)
        } else {
            // TODO: Support non-grazie backend with different LLM providers
            simpleOpenAIExecutor(requireNotNull(agentConfig.apiTokens.grazieApiToken) {
                "OpenAI API token is missing in the .env"
            })
        }
        val logger: Logger = Logger.getLogger("main")

        val httpClient = HttpClient.newHttpClient()
        val mcpSessionManager = McpSessionManager(
            agentConfig.mcpServerBaseUrl, httpClient
        )

        val theoremsFile = Path.of(agentConfig.pathToTheorems)
        val theoremsJson = Json.parseToJsonElement(theoremsFile.readText()) as JsonObject

        var successCount = 0
        var totalCount = 0

        for ((filePath, theoremArray) in theoremsJson) {
            for (theorem in theoremArray.jsonArray) {
                val theoremName = theorem.jsonPrimitive.content
                totalCount++

                val success = runOnTheorem(
                    agentConfig, executor, mcpSessionManager, httpClient, filePath, theoremName, logger
                )

                if (success) successCount++
            }
        }

        logger.info("Finished: $successCount / $totalCount theorems proved successfully.")
    }
}

suspend fun runOnTheorem(
    agentConfig: ResolvedAgentConfig,
    executor: SingleLLMPromptExecutor,
    mcpSessionManager: McpSessionManager,
    httpClient: HttpClient,
    filePath: String,
    theoremName: String,
    logger: Logger
): Boolean {
    val agent = RocqStarAgent(agentConfig, executor, mcpSessionManager, httpClient)
    val result = agent.execute(theoremName, filePath)

    return if (result.isSuccessful) {
        logger.finest(
            """
            |Success: $theoremName in $filePath
            |Proof:
            |${result.completeProof}
            """.trimMargin()
        )
        true
    } else {
        logger.warning("Failed: $theoremName in $filePath")
        false
    }
}