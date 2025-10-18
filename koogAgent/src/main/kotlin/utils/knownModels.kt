package org.example.utils

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel


private fun createModelEntry(model: LLModel, customId: String): Pair<String, LLModel> {
    return customId to model.copy(id = customId)
}

internal val LLM_MODELS: Map<String, LLModel> = mapOf(
    createModelEntry(OpenAIModels.Chat.GPT4o, "openai-gpt-4o"),
    createModelEntry(OpenAIModels.Chat.GPT4_1, "openai-gpt4.1"),
    createModelEntry(OpenAIModels.CostOptimized.GPT4oMini, "openai-gpt-4o-mini"),
    createModelEntry(OpenAIModels.CostOptimized.GPT4_1Mini, "openai-gpt4.1-mini"),
    createModelEntry(OpenAIModels.CostOptimized.GPT4_1Nano, "openai-gpt4.1-nano"),

    createModelEntry(AnthropicModels.Sonnet_3_5, "anthropic-claude-3.5-sonnet"),
    createModelEntry(AnthropicModels.Sonnet_3_7, "anthropic-claude-3.7-sonnet"),
    createModelEntry(AnthropicModels.Sonnet_4, "anthropic-claude-4-sonnet"),

    createModelEntry(GoogleModels.Gemini2_0Flash, "google-chat-gemini-flash-2.0"),
    createModelEntry(GoogleModels.Gemini2_0FlashLite, "google-chat-gemini-flash-lite-2.0"),
    createModelEntry(GoogleModels.Gemini2_5Flash, "google-chat-gemini-flash-2.5"),
    createModelEntry(GoogleModels.Gemini2_5Pro, "google-chat-gemini-pro-2.5"),
)

internal fun listKnownProfiles(): String =
    LLM_MODELS.keys.sorted().joinToString("\n") { " - $it" }