package org.example.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation

fun getToolSummary(toolset: ToolSet): String {
    return toolset::class.declaredFunctions
        .filter { it.findAnnotation<Tool>() != null }
        .joinToString("\n") { fn ->
            val desc = fn.findAnnotation<LLMDescription>()?.description ?: "No description"
            "- **${fn.name}**: $desc"
        }
}