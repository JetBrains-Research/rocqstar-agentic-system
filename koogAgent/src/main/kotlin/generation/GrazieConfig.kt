package org.example.generation

import ai.grazie.model.cloud.AuthType
import org.example.utils.ResolvedAgentConfig

data class GrazieConfig(
    val apiToken: String,
    val agentName: String,
    val agentVersion: String,
    val authType: AuthType = AuthType.User
) {
    companion object {
        fun fromAgentConfig(agentConfig: ResolvedAgentConfig): GrazieConfig = GrazieConfig(
            apiToken = agentConfig.apiTokens.grazieApiToken,
            agentName = agentConfig.agentId,
            agentVersion = agentConfig.agentVersion,
            authType = agentConfig.grazie.clientAuthType
        )
    }
}