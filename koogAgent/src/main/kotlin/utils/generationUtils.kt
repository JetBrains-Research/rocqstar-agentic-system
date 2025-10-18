package org.example.utils

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

suspend fun generateWithPromptString(
    systemPrompt: String,
    userPrompt: String,
    params: ResolvedModelConfig,
    executor: PromptExecutor,
): String = AIAgent(
    executor = executor,
    systemPrompt = systemPrompt,
    llmModel = params.profile,
    temperature = params.temperature,
).run(userPrompt)

@OptIn(ExperimentalUuidApi::class)
suspend fun generateWithPrompt(
    prompt: Prompt,
    userPrompt: String,
    params: ResolvedModelConfig,
    executor: PromptExecutor,
): String = AIAgent(
    id = Uuid.random().toString(),
    promptExecutor = executor,
    strategy = singleRunStrategy(),
    agentConfig = AIAgentConfig(
        prompt = prompt.withUpdatedParams { temperature = params.temperature },
        model = params.profile,
        maxAgentIterations = 50,
    ),
    toolRegistry = ToolRegistry.EMPTY,
    installFeatures = {}
).run(userPrompt)