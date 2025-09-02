package org.example

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class RocqProofSessionManager(
    private val theoremName: String,
    private val targetTheoremPath: String,
    private val mcpSessionManager: McpSessionManager,
    private val projectServerBaseUrl: String = "http://localhost:8000/rest/document",
    private val mcpServerBaseUrl: String = "http://localhost:3001/mcp",
    private val client: HttpClient = HttpClient.newHttpClient()
) {
    private val proofSessionId: String = initializeCoqProofSession()

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
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("mcp-session-id", mcpSessionManager.sessionId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.body()
    }

    fun dispose() {
        finishCoqProofSession(proofSessionId)
    }

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
        val fullUrl = if (query.isEmpty()) "$baseUrl/$path" else "$baseUrl$path?$query"
        return URI.create(fullUrl)
    }

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