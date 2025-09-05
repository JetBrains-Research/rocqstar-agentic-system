package org.example.tools

import kotlinx.serialization.Serializable
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * This class manages the abstraction of Coq-proof session.
 * Coq proof sessions exist on the server side to optimize the type-checking process.
 * When the agent starts proving a given theorem, a new session shall be started.
 * Session is started in the Coq-project server, whereas MCP-tool calls (most of them)
 * expect the session-id as a parameter. To hide the abstraction of proof-sessions from
 * the agent and avoid hallucinations, this class manages sessions on its own, intercepting the tool-calls
 * and equipping them with session-ids. The life-time of this class is the process of proving a single theorem.
 *
 * Invariants:
 *  - holds the current session ID (is constant over the whole lifetime of the class)
 *  - holds the up-to-date proofVersionHash, and updates it every time it is returned in the Rocq server response
 *  - holds the up-to-date list of goals, which is updated
 */
class RocqProofSessionManager(
    private val theoremName: String,
    private val targetTheoremPath: String,
    private val mcpSessionManager: McpSessionManager,
    val projectServerBaseUrl: String = "http://localhost:8000/rest/document",
    private val mcpServerBaseUrl: String = "http://localhost:3001/mcp",
    val client: HttpClient = HttpClient.newHttpClient()
): AutoCloseable {
    val proofSessionId: String
    var proofHash: String
        private set
    var currentGoals: List<String>?
        private set

    init {
        val startSessionResponse = initializeCoqProofSession()
        proofSessionId = startSessionResponse.sessionId
        proofHash = startSessionResponse.proofVersionHash

        // This is a workaround to retrieve initial state of the theorem.
        val dummyProof = "Proof.\nQed."
        val checkProofResponse = checkProof(dummyProof)
        // Actually it is done in the call, but kotlin type-checker cannot
        // infer it
        currentGoals = checkProofResponse.goals
    }

    /**
     * It is the wrapper around the request to the MCP server, which accepts a dynamic list
     * of parameters and a tool name
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
        val finishSessionResult = finishCoqProofSession(proofSessionId)
        if (!finishSessionResult.success) {
            throw IllegalStateException("Unable to close proof session")
        }
    }

    /**
     * The requests to initialize and finish proof-sessions are sent
     * to the Coq Proof Server, which is a lower-level abstraction under the MCP.
     * [coqProjectRequest] by-passes the MCP and sends the request directly to the
     * Coq Proof server.
     *
     * @return ID of the started session, and the initial proof hash
     */
    private fun initializeCoqProofSession(): StartSessionResponse = coqProjectRequest<StartSessionResponse>(
        "start-session",
        mapOf(
            "filePath" to BodyParam.Str(targetTheoremPath),
            "theoremName" to BodyParam.Str(theoremName)
        )
    )

    private fun finishCoqProofSession(sessionId: String) = coqProjectRequest<FinishSessionResponse>(
        "finish-session",
        mapOf("coqSessionId" to BodyParam.Str(sessionId))
    )

    fun getSessionTheorem() = coqProjectRequest<SessionTheoremResponse>(
        "session-theorem",
        mapOf(
            "coqSessionId" to BodyParam.Str(proofSessionId),
            "proofVersionHash" to BodyParam.Str(proofHash)
        )
    )

    fun checkProof(proof: String): ProofCheckResponse {
        val response = coqProjectRequest<ProofCheckResponse>(
            "check-proof",
            mapOf(
                "proof" to BodyParam.Str(proof),
                "coqSessionId" to BodyParam.Str(proofSessionId),
                "proofVersionHash" to BodyParam.Str(proofHash)
            )
        )

        // Check proof is the only request that returns the updated goals
        // Along with initializeSession, it is the only request, that returns proofSessionHash
        proofHash = response.hash
        currentGoals = response.goals

        return response
    }

    fun getPremises(
        goal: String,
        filePath: String,
        maxNumberOfPremises: Int = 20
    ) = coqProjectRequest<GetPremisesResponse>(
        "get-premises",
        mapOf(
            "goal" to BodyParam.Str(goal),
            "filePath" to BodyParam.Str(filePath),
            "maxNumberOfPremises" to BodyParam.Num(maxNumberOfPremises),
            "coqSessionId" to BodyParam.Str(proofSessionId)
        )
    )

    fun getTheorem(filePath: String, theoremName: String) = coqProjectRequest<TheoremResponse>(
        "theorem",
        mapOf(
            "filePath" to BodyParam.Str(filePath),
            "theoremName" to BodyParam.Str(theoremName),
            "coqSessionId" to BodyParam.Str(proofSessionId),
            "proofVersionHash" to BodyParam.Str(proofHash)
        )
    )

    /**
     * In comparison to the MCP server, requests to the Coq project server are
     * GET requests (for some reason). Therefore, requests are packed with query-parameters
     */
    private inline fun <reified ResponseType> coqProjectRequest(
        path: String,
        args: ServerCallParameters = emptyMap()
    ): ResponseType {
        val requestUrl = buildUriWithParams(projectServerBaseUrl, path, args)

        val req = HttpRequest.newBuilder()
            .uri(requestUrl)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .GET()
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        val body = resp.body()

        return try {
            // Deserialize to requested type
            Json.decodeFromString<ResponseType>(body)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to parse response into ${ResponseType::class.simpleName}: $body",
                e
            )
        }
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

@Serializable
data class StartSessionResponse(
    val sessionId: String,
    val proofVersionHash: String
)

@Serializable
data class FinishSessionResponse(
    val success: Boolean,
    val message: String
)

sealed class BodyParam {
    data class Str(val value: String) : BodyParam()
    data class Num(val value: Int) : BodyParam()

    fun asString(): String = when (this) {
        is Str -> value
        is Num -> value.toString()
    }
}

typealias ServerCallParameters = Map<String, BodyParam>