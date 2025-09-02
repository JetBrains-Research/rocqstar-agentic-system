package org.example

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * This class manages the abstraction of Coq-proof session.
 * Coq proof sessions exist on the server side to optimize the type-checking process.
 * When the agent starts proving a given theorem, a new session shall be started.
 * Session is started in the Coq-project server, whereas MCP-tool calls (most of them)
 * expect the session-id as a parameter. To hide the abstraction of proof-sessions from
 * the agent and avoid hallucinations, this class manages sessions on its own, intercepting the tool-calls
 * and equipping them with session-ids. The life-time of this class is the process of proving a single theorem.
 */
class RocqProofSessionManager(
    private val theoremName: String,
    private val targetTheoremPath: String,
    private val mcpSessionManager: McpSessionManager,
    private val projectServerBaseUrl: String = "http://localhost:8000/rest/document",
    private val mcpServerBaseUrl: String = "http://localhost:3001/mcp",
    private val client: HttpClient = HttpClient.newHttpClient()
): AutoCloseable {
    private val proofSessionId: String = initializeCoqProofSession()

    /**
     * Quasi the only public method of [RocqProofSessionManager], it is the wrapper around the request to
     * the MCP server, which accepts a dynamic list of parameters and a tool name
     */
    fun callTool(toolName: String, insideProofSession: Boolean, args: ServerCallParameters = emptyMap()): String {
        val finalArgs = args.withProofSession(insideProofSession, proofSessionId)
        val argsJson = finalArgs.toJson()

        val body = """
        {
          "jsonrpc": "2.0",
          "method": "tools/call",
          "params": {
            "name": "$toolName",
            "arguments": $argsJson
          },
          "id": ${mcpSessionManager.nextToolId}
        }
        """.trimIndent()

        val req = HttpRequest.newBuilder()
            .uri(URI.create(mcpServerBaseUrl))
            // These headers are crucial!
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("mcp-session-id", mcpSessionManager.sessionId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.body()
    }

    /**
     * [RocqProofSessionManager] could be used inside a use-block
     * ```proofSessionManager.use { sessionManager -> { ... }}```,
     * that will automatically close the session, after the agent is done
     * proving the given theorem
     */
    override fun close() {
        finishCoqProofSession(proofSessionId)
    }

    /**
     * The requests to initialize and finish proof-sessions are sent
     * to the Coq Proof Server, which is a lower-level abstraction under the MCP.
     * [coqProjectRequest] by-passes the MCP and sends the request directly to the
     * Coq Proof server.
     */
    private fun initializeCoqProofSession(): String {
        val responseBody = coqProjectRequest(
            "start-session",
            mapOf(
                "filePath" to BodyParam.Str(targetTheoremPath),
                "theoremName" to BodyParam.Str(theoremName)
            )
        )

        val json = Json.parseToJsonElement(responseBody).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content
            ?: error("Rocq project server did not return proof session ID")

        return sessionId
    }

    private fun finishCoqProofSession(sessionId: String) {
        coqProjectRequest(
            "finish-session",
            mapOf("coqSessionId" to BodyParam.Str(sessionId))
        )
    }

    /**
     * In comparison to the MCP server, requests to the Coq project server are
     * GET requests (for some reason). Therefore, requests are packed with query-parameters
     */
    private fun coqProjectRequest(path: String, args: ServerCallParameters = emptyMap()): String {
        val requestUrl = buildUriWithParams(projectServerBaseUrl, path, args)

        val req = HttpRequest.newBuilder()
            .uri(requestUrl)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .GET()
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.body()
    }

    private fun buildUriWithParams(baseUrl: String, path: String, params: ServerCallParameters): URI {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${v.asString()}"
        }
        val fullUrl = if (query.isEmpty()) "$baseUrl/$path" else "$baseUrl/$path?$query"
        return URI.create(fullUrl)
    }

    /**
     * Method to insert [proofSessionId] to the tool-call parameters
     */
    private fun ServerCallParameters.withProofSession(
        enabled: Boolean,
        proofSession: String
    ): ServerCallParameters =
        if (enabled) this + ("coqSessionId" to BodyParam.Str(proofSession)) else this

    private fun ServerCallParameters.toJson(): String {
        return entries.joinToString(
            prefix = "{", postfix = "}"
        ) { (k, v) ->
            val value = when (v) {
                is BodyParam.Str -> "\"${v.value.replace("\"", "\\\"")}\""
                is BodyParam.Num -> v.value.toString()
            }
            "\"$k\": $value"
        }
    }
}

sealed class BodyParam {
    data class Str(val value: String) : BodyParam()
    data class Num(val value: Int) : BodyParam()

    fun asString(): String = when (this) {
        is Str -> value
        is Num -> value.toString()
    }
}

typealias ServerCallParameters = Map<String, BodyParam>