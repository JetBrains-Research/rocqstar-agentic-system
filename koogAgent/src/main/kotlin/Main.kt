package org.example

import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking
import org.example.generation.GrazieConfig
import org.example.generation.simpleGrazieExecutor
import org.example.tools.McpSessionManager
import org.example.utils.Backend
import org.example.utils.GRAZIE_STAGING_URI
import org.example.utils.loadAgentConfig
import java.net.http.HttpClient
import java.nio.file.Path

fun main() {
    runBlocking {
        val agentConfig = loadAgentConfig(Path.of("agent-config.yaml"))
        val executor = if (agentConfig.backend == Backend.Grazie) {
            val grazieConfig = GrazieConfig.fromAgentConfig(agentConfig)
            simpleGrazieExecutor(grazieConfig, GRAZIE_STAGING_URI)
        } else {
            // TODO: Support non-grazie backend with different LLM providers
            simpleOpenAIExecutor(agentConfig.apiTokens.openAiApiToken)
        }

        val theoremName = "loceq_same_tid"
        val targetPath = "src/basic/Events.v"

        val httpClient = HttpClient.newHttpClient()
        val mcpSessionManager = McpSessionManager(
            agentConfig.mcpServerBaseUrl,
            httpClient
        )

        val agent = RocqStarAgent(
            agentConfig,
            executor,
            mcpSessionManager,
            httpClient
        )

        val generationResult = agent.execute(theoremName, targetPath)
        if (generationResult.isSuccessful) {
            println(
                """
                    Generation of proof for theorem $theoremName has succeeded, the following proof was produced: 
                    ${generationResult.completeProof}
                """.trimIndent()
            )
        } else {
            println("Unfortunately, generation for theorem $theoremName failed")
        }

    }
}