package org.example.generation

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.client.common.SuspendableClientWithBackoff
import ai.grazie.client.common.SuspendableHTTPClient
import ai.grazie.client.ktor.GrazieKtorHTTPClient
import ai.grazie.model.auth.GrazieAgent
import ai.grazie.model.auth.v5.AuthData
import ai.grazie.model.cloud.AuthType
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.jetbrains.code.prompt.executor.clients.grazie.koog.GrazieLLMClient
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.*

fun simpleGrazieExecutor(
    grazieConfig: GrazieConfig,
    serverUrl: String,
): SingleLLMPromptExecutor {
    val client = GrazieLLMClient(
        SuspendableAPIGatewayClient(
            serverUrl,
            SuspendableHTTPClient.WithV5(
                SuspendableClientWithBackoff(
                    GrazieKtorHTTPClient(HttpClient(OkHttp))
                ),
                AuthData(
                    grazieConfig.apiToken,
                    grazieAgent = GrazieAgent(grazieConfig.agentName, grazieConfig.agentVersion)
                )
            ),
            AuthType.User
        ),
        default = LLMParams()
    )
    return SingleLLMPromptExecutor(client)
}