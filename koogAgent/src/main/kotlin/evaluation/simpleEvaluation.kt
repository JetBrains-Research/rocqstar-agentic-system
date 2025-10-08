package org.example.evaluation

import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.example.agent.RocqStarAgent
import org.example.generation.promptExecutorFromAgentConfig
import org.example.utils.ResolvedAgentConfig
import java.net.http.HttpClient
import java.nio.file.Path
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.io.path.readText

suspend fun runSimpleEvaluation(agentConfig: ResolvedAgentConfig, dataset: Path) {
    val executor = promptExecutorFromAgentConfig(agentConfig)
    val logger = KotlinLogging.logger {}

    val httpClient = HttpClient.newHttpClient()

    val theoremsJson = Json.parseToJsonElement(dataset.readText()) as JsonObject

    var successCount = 0
    var totalCount = 0

    for ((filePath, theoremArray) in theoremsJson) {
        for (theorem in theoremArray.jsonArray) {
            val theoremName = theorem.jsonPrimitive.content
            totalCount++

            var success = false

            try {
                success = runOnTheorem(
                    agentConfig, executor, httpClient, filePath, theoremName, logger
                )
            } catch (e: Exception) {
                logger.warn { "Error while executing theorem $theoremName: $e" }
            }

            if (success) successCount++
        }
    }

    logger.info { "Finished: $successCount / $totalCount theorems proved successfully." }
}

suspend fun runOnTheorem(
    agentConfig: ResolvedAgentConfig,
    executor: PromptExecutor,
    httpClient: HttpClient,
    filePath: String,
    theoremName: String,
    logger: KLogger
): Boolean {
    val agent = RocqStarAgent(agentConfig, executor, httpClient)
    val result = agent.execute(theoremName, filePath)

    return if (result.isSuccessful) {
        logger.info {
            """
            |Success: $theoremName in $filePath
            |Proof:
            |${result.completeProof}
            """.trimMargin()
        }
        true
    } else {
        logger.warn { "Failed: $theoremName in $filePath" }
        false
    }
}