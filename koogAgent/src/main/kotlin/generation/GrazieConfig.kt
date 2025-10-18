package org.example.generation

import ai.grazie.model.auth.GrazieAgent
import ai.grazie.model.cloud.AuthType
import ai.jetbrains.code.prompt.executor.clients.grazie.koog.createGraziePromptExecutor
import ai.jetbrains.code.prompt.executor.clients.grazie.koog.model.GrazieEnvironment
import ai.koog.prompt.executor.model.PromptExecutor
import org.example.utils.ResolvedAgentConfig

data class GrazieConfig(
    val apiToken: String,
    val agentName: String,
    val agentVersion: String,
    val authType: AuthType = AuthType.User,
    val envType: GrazieEnvironment = GrazieEnvironment.Staging
) {
    companion object {
        fun fromAgentConfig(agentConfig: ResolvedAgentConfig): GrazieConfig = GrazieConfig(
            apiToken = requireNotNull(agentConfig.apiTokens.grazieApiToken) {
                "Grazie API token is missing in .env"
            },
            agentName = agentConfig.agentId,
            agentVersion = agentConfig.agentVersion,
            authType = agentConfig.grazie.clientAuthType,
            envType = agentConfig.grazie.grazieEnvironment
        )
    }
}

suspend fun graziePromptExecutorFromConfig(grazieConfig: GrazieConfig): PromptExecutor = createGraziePromptExecutor(
    grazieConfig.apiToken,
    grazieEnvironment = grazieConfig.envType,
    grazieAgent = GrazieAgent(grazieConfig.agentName, grazieConfig.agentVersion),
    authType = grazieConfig.authType,
)