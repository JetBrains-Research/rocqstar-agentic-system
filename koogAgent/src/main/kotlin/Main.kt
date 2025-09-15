package org.example

import kotlinx.coroutines.runBlocking
import org.example.utils.loadAgentConfig
import java.nio.file.Path
import org.example.evaluation.runSimpleEvaluation

fun main() {
    val agentConfig = loadAgentConfig(Path.of("agent-config.yaml"))
    val theoremsFilePath = Path.of(agentConfig.pathToTheorems)

    runBlocking {
        runSimpleEvaluation(agentConfig, theoremsFilePath)
    }
}