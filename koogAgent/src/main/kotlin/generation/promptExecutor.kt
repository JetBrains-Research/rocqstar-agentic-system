package org.example.generation

import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import org.example.utils.Backend
import org.example.utils.ResolvedAgentConfig

suspend fun promptExecutorFromAgentConfig(
    agentConfig: ResolvedAgentConfig
): PromptExecutor = when (agentConfig.backend) {
    Backend.Grazie -> {
        val grazieConfig = GrazieConfig.fromAgentConfig(agentConfig)
        graziePromptExecutorFromConfig(grazieConfig)
    }
    Backend.OpenAI -> simpleOpenAIExecutor(
        requireNotNull(agentConfig.apiTokens.grazieApiToken) {
            "OpenAI API token is missing in the .env"
        }
    )
    Backend.Anthropic -> simpleAnthropicExecutor(
        requireNotNull(agentConfig.apiTokens.anthropicApiToken) {
            "Anthropic API token is missing in the .env"
        }
    )
    Backend.OpenRouter -> simpleOpenRouterExecutor(
        requireNotNull(agentConfig.apiTokens.openRouterApiToken) {
            "OpenRouter API token is missing in the .env"
        }
    )
}