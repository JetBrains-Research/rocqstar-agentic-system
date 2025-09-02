package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val dotenv = dotenv()
        val apiKey = dotenv["OPENAI_API_KEY"]
            ?: error("OPENAI_API_KEY not set")

        val agent = AIAgent(
            executor = simpleOpenAIExecutor(apiKey),
            systemPrompt = "You are a helpful assistant. Answer user questions concisely.",
            llmModel = OpenAIModels.Chat.GPT4o
        )

        val result = agent.run("Tell me a joke")
        println(result)
    }
}