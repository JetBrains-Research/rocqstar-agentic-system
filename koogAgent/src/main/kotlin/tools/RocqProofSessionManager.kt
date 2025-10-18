package org.example.tools

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.IllegalArgumentException

/**
 * This class manages the abstraction of Coq-proof session.
 * Coq proof sessions exist on the server side to optimize the type-checking process.
 * When the agent starts proving a given theorem, a new session shall be started.
 * Session is started in the Coq-project server, whereas tool calls (most of them)
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
    private val logger: KLogger = KotlinLogging.logger {},
    val projectServerBaseUrl: String = "http://localhost:8000/rest/document",
    val client: HttpClient = HttpClient.newHttpClient(),
) : AutoCloseable {
    val proofSessionId: String
    var proofHash: String
        private set
    var currentGoals: List<String>?
        private set

    init {
        val startSessionResponse = initializeCoqProofSession()
        proofSessionId = startSessionResponse.sessionId
        proofHash = startSessionResponse.proofVersionHash

        val initialGoals = setGoalsToInitialState()
        // Actually, it is done in the call, but a kotlin type-checker cannot
        // infer it as it doesn't know the server invariants
        currentGoals = initialGoals.goals
    }

    /**
     * It is the wrapper around the request to the RocqProjectServer, which accepts a dynamic list
     * of parameters and a tool name. I kept it generic for those (most) cases, when we don't require
     * deserialization and post-processing of the response. In those cases, we operate with a raw JSON,
     * passing it to the model unchanged.
     */
    fun callTool(
        toolName: String, insideProofSession: Boolean, args: ServerCallParameters = emptyMap()
    ): String {
        // If llm has provided illegal arguments to the tool-call.
        // Basically checks for empty strings currently
        val validationRes = args.validateParams()
        if (validationRes.isFailure) {
            return validationRes.toString()
        }

        val finalArgs = args.withProofSession(insideProofSession, proofSessionId)

        return coqProjectRequest<RawJson>(toolName, finalArgs).value
    }

    /**
     * The following six requests are overloads with a specific response type for the cases, when
     * the post-processing of the response is required, e.g., we want to wrap the CheckProofs request
     * and manage its output to update session resources, etc.
     *
     * @return ID of the started session, and the initial proof hash
     */
    fun checkProof(proof: BodyParam.Str): ProofCheckResponse {
        logger.info { "Checking proof $proof" }

        if (proof.isEmpty()) {
            return ProofCheckResponse.fromErrorMsg("Please provide a non-empty proof")
        }

        val response = coqProjectRequest<ProofCheckResponse>(
            "check-proof", mapOf(
                "proof" to BodyParam.Str(proof.value),
                "coqSessionId" to BodyParam.Str(proofSessionId),
                "proofVersionHash" to BodyParam.Str(proofHash)
            )
        )

        // Check proof is the only request that returns the updated goals
        // Along with initializeSession; it is the only request that returns proofSessionHash
        // Sometimes, when an error during checking the proof occurs on the server side,
        // the returned proof hash could be null
        response.hash?.let { proofHash = it }
        // Also, currentGoals can be returned null
        response.goals?.let { currentGoals = it }

        return response
    }

    fun getPremises(
        goal: String, filePath: String, maxNumberOfPremises: Int = 20
    ) = coqProjectRequest<GetPremisesResponse>(
        "get-premises", mapOf(
            "goal" to BodyParam.Str(goal),
            "filePath" to BodyParam.Str(filePath),
            "maxNumberOfPremises" to BodyParam.Num(maxNumberOfPremises),
            "coqSessionId" to BodyParam.Str(proofSessionId)
        )
    )

    fun getTheorem(filePath: String, theoremName: String) = coqProjectRequest<TheoremResponse>(
        "theorem", mapOf(
            "filePath" to BodyParam.Str(filePath),
            "theoremName" to BodyParam.Str(theoremName),
            "coqSessionId" to BodyParam.Str(proofSessionId),
            "proofVersionHash" to BodyParam.Str(proofHash)
        )
    )

    fun searchPattern(pattern: String) = coqProjectRequest<SearchPatternResponse>(
        "search-pattern", mapOf(
            "pattern" to BodyParam.Str(pattern),
            "coqSessionId" to BodyParam.Str(proofSessionId),
        )
    ).trimLongResponse()

    /**
     * [getSessionTheorem] is moved as a separate method because it is not available to the agent
     */
    fun getSessionTheorem() = coqProjectRequest<SessionTheoremResponse>(
        "session-theorem", mapOf(
            "coqSessionId" to BodyParam.Str(proofSessionId), "proofVersionHash" to BodyParam.Str(proofHash)
        )
    )

    private fun initializeCoqProofSession(): StartSessionResponse = coqProjectRequest<StartSessionResponse>(
        "start-session", mapOf(
            "filePath" to BodyParam.Str(targetTheoremPath), "theoremName" to BodyParam.Str(theoremName)
        )
    )

    private fun finishCoqProofSession(sessionId: String) = coqProjectRequest<FinishSessionResponse>(
        "finish-session", mapOf("coqSessionId" to BodyParam.Str(sessionId))
    )

    fun setGoalsToInitialState(): ProofCheckResponse {
        // This is a workaround to retrieve the initial state of the theorem.
        val dummyProof = "Proof.\nQed."
        return checkProof(BodyParam.Str(dummyProof))
    }

    /**
     * A generic request to the Coq project server; supports cases with and without
     * deserialization of the response.
     */
    private inline fun <reified ResponseType> coqProjectRequest(
        path: String, args: ServerCallParameters = emptyMap()
    ): ResponseType {
        // Requests to the Coq project server are GET requests (for some reason).
        // Therefore, are packed with query-parameters
        val requestUrl = buildUriWithParams(projectServerBaseUrl, path, args)

        val req = HttpRequest.newBuilder().uri(requestUrl).header("Accept", "application/json")
            .header("Content-Type", "application/json").GET().build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        val body = resp.body()

        return try {
            // Deserialize to the requested type; if RawJson requested, return as is
            if (ResponseType::class == RawJson::class) {
                RawJson(body) as ResponseType
            } else {
                Json.decodeFromString<ResponseType>(body)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to parse response into ${ResponseType::class.simpleName}: $body", e
            )
        }
    }

    private fun buildUriWithParams(
        baseUrl: String, path: String, params: ServerCallParameters
    ): URI {
        val charset = StandardCharsets.UTF_8
        val query = params.entries.joinToString("&") { (k, v) ->
            val encodedKey = URLEncoder.encode(k, charset)
            val encodedValue = URLEncoder.encode(v.asString(), charset)
            "$encodedKey=$encodedValue"
        }

        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = path.trimStart('/')

        val fullUrl = if (query.isEmpty()) {
            "$normalizedBase/$normalizedPath"
        } else {
            "$normalizedBase/$normalizedPath?$query"
        }

        return URI.create(fullUrl)
    }

    /**
     * Method to insert [proofSessionId] to the tool-call parameters
     */
    private fun ServerCallParameters.withProofSession(
        enabled: Boolean, proofSession: String
    ): ServerCallParameters = if (enabled) this + ("coqSessionId" to BodyParam.Str(proofSession)) else this

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
}

@Serializable
data class StartSessionResponse(
    val sessionId: String, val proofVersionHash: String
)

@Serializable
data class FinishSessionResponse(
    val success: Boolean, val message: String
)

sealed class BodyParam {
    data class Str(val value: String) : BodyParam() {
        fun isEmpty(): Boolean = value.isEmpty() || value.isBlank() || value == EMPTY_JSON
    }

    data class Num(val value: Int) : BodyParam()

    fun asString(): String = when (this) {
        is Str -> value
        is Num -> value.toString()
    }
}

typealias ServerCallParameters = Map<String, BodyParam>

fun ServerCallParameters.validateParams(): Result<Unit> {
    forEach { (_, param) ->
        if (param is BodyParam.Str && param.isEmpty()) {
            return Result.failure(IllegalStateException("$param is invalid, it is an empty string."))
        }
    }

    return Result.success(Unit)
}

@JvmInline
value class RawJson(val value: String)

const val EMPTY_JSON = "{}"