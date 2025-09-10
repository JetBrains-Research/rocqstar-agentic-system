package org.example.agent

import org.example.utils.ResolvedAgentConfig
import java.util.logging.Logger

data class LangfuseConfig(
    val host: String,
    val publicKey: String,
    val secretKey: String
) {
    companion object {
        fun fromAgentConfig(
            agentConfig: ResolvedAgentConfig,
            logger: Logger
        ): LangfuseConfig? {
            val publicKey = agentConfig.apiTokens.langfusePublicKey
            val secretKey = agentConfig.apiTokens.langfusePrivateKey

            return if (publicKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
                logger.warning("LangFuse keys are missing or incomplete. LangFuseConfig will not be initialized.")
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
