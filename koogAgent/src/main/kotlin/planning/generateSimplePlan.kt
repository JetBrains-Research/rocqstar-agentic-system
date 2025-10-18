package org.example.planning

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import org.example.utils.ResolvedAgentConfig

private const val systemPrompt = """
You are a Coq expert assistant.
First review the list of available proof‐assistant tools, then outline a clear, stepwise proof strategy.
When you reference a tool in your plan, wrap its name in backticks, e.g. `check_proof`.
"""

suspend fun generateSimplePlan(
    theoremStatement: String,
    toolsetSummary: String,
    agentConfig: ResolvedAgentConfig,
    executor: PromptExecutor
): String {
    val agent = AIAgent(
        executor = executor,
        systemPrompt = systemPrompt,
        llmModel = agentConfig.planning.simplePlanning.profile
    )

    val userMessage =
        "**Theorem to prove:**\n$theoremStatement\n**Available tools:**\n$toolsetSummary\n\n\n" +
        "Please output a numbered plan of tactics and tool calls."

    val simplePlan = agent.run(userMessage)

    return simplePlan
}