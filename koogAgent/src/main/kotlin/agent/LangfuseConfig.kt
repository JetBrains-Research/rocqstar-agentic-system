package org.example.agent

import io.github.oshai.kotlinlogging.KLogger
import org.example.utils.ResolvedAgentConfig

data class LangfuseConfig(
    val host: String,
    val publicKey: String,
    val secretKey: String
) {
    companion object {
        fun fromAgentConfig(
            agentConfig: ResolvedAgentConfig,
            logger: KLogger
        ): LangfuseConfig? {
            val publicKey = agentConfig.apiTokens.langfusePublicKey
            val secretKey = agentConfig.apiTokens.langfusePrivateKey

            return if (publicKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
                logger.warn { "LangFuse keys are missing or incomplete. LangFuseConfig will not be initialized." }
                null
            } else {
                LangfuseConfig(
                    host = agentConfig.langfuseHostUrl,
                    publicKey = publicKey,
                    secretKey = secretKey
                )
            }
        }
    }
}
