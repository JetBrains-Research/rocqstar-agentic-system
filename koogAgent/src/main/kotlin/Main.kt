package org.example

import kotlinx.coroutines.runBlocking
import org.example.generation.GrazieConfig
import org.example.generation.simpleGrazieExecutor
import org.example.planning.generateMadPlan
import org.example.tools.McpSessionManager
import org.example.tools.RocqMcpToolSet
import org.example.tools.RocqProofSessionManager
import org.example.tools.getToolSummary
import org.example.utils.GRAZIE_STAGING_URI
import org.example.utils.loadAgentConfig
import java.net.http.HttpClient
import java.nio.file.Path
import kotlin.use

fun main() {
    runBlocking {
        val agentConfig = loadAgentConfig(Path.of("agent-config.yaml"))
        val grazieConfig = GrazieConfig.fromAgentConfig(agentConfig)
        val grazieExecutor = simpleGrazieExecutor(grazieConfig, GRAZIE_STAGING_URI)

        val theoremName = "loceq_same_tid"
        val targetPath = "src/basic/Events.v"

        val httpClient = HttpClient.newHttpClient()
        val mcpSessionManager = McpSessionManager(
            agentConfig.mcpServerBaseUrl,
            httpClient
        )
        val proofSessionManager = RocqProofSessionManager(
            theoremName,
            targetPath,
            mcpSessionManager,
            agentConfig.coqProjectServerBaseUrl,
            agentConfig.mcpServerBaseUrl,
            httpClient
        )

        proofSessionManager.use { sessionManager ->
            val mcpTools = RocqMcpToolSet(
                sessionManager
            )

            val plan = generateMadPlan(
                "Lemma loceq_same_tid (r: relation actid) (H: funeq tid r): r ⊆ r ∩ same_tid.",
                getToolSummary(mcpTools),
                agentConfig,
                grazieExecutor
            )

            println(plan)
        }
    }
}