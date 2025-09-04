package org.example.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The life-time of this class is the whole process of communication
 * between the agent and the MCP. It handles resources that stay constant during the whole pipeline
 */
class McpSessionManager(
    mcpServerBaseUrl: String = "http://localhost:3001/mcp",
    client: HttpClient = HttpClient.newHttpClient()
) {
    /**
     * The ID of the session that is issued by the MCP server.
     * This ID remains constant for the whole cycle of communicating with
     * the MCP. Is somewhat redundant but required by the MCP-server library.
     */
    val sessionId: String

    /**
     * The counter for the tool-calls to the MCP server.
     */
    val nextToolId: Int
        get() = toolIdCounter++

    private var toolIdCounter = 1

    private val clientInfoPayload = """{
        "name": "rocqstar-agent",
        "version": "1.0.0"
    }
    """

    // This behavior is mostly defined by the MCP-protocol
    init {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(mcpServerBaseUrl))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString("""{
              "jsonrpc": "2.0",
              "method": "initialize",
              "params": { "clientInfo": $clientInfoPayload, "protocolVersion": "2025-03-26", "capabilities": {} },
              "id": $nextToolId
            }"""))
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        sessionId = resp.headers().firstValue("mcp-session-id").orElse(null)
            ?: error("MCP server did not return sessionId")

        println("MCP session initialized: $sessionId")
    }
}