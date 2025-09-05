package org.example.tools

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

//class RocqProjectClient(
//    val proofSessionManager: RocqProofSessionManager,
//) {
//    fun getProjectRoot(): ProjectRootResponse =
//        proofSessionManager.coqProjectRequest("")
//
//    fun getAllCoqFiles(): AllCoqFilesResponse =
//        proofSessionManager.coqProjectRequest("all-coq-files")
//
//    fun getTheoremNames(filePath: String): TheoremNamesResponse =
//        proofSessionManager.coqProjectRequest(
//            "theorem-names",
//            mapOf(
//                "filePath" to BodyParam.Str(filePath),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId)
//            )
//        )
//
//    fun getSessionTheorem(): SessionTheoremResponse =
//        proofSessionManager.coqProjectRequest(
//            "session-theorem",
//            mapOf(
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId),
//                "proofVersionHash" to BodyParam.Str(proofSessionManager.proofHash)
//            )
//        )
//
//    fun getTheorem(filePath: String, theoremName: String): TheoremResponse =
//        proofSessionManager.coqProjectRequest(
//            "theorem",
//            mapOf(
//                "filePath" to BodyParam.Str(filePath),
//                "theoremName" to BodyParam.Str(theoremName),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId),
//                "proofVersionHash" to BodyParam.Str(proofSessionManager.proofHash)
//            )
//        )
//
//    fun checkProof(proof: String): ProofCheckResponse {
//        val response = proofSessionManager.coqProjectRequest<ProofCheckResponse>(
//            "check-proof",
//            mapOf(
//                "proof" to BodyParam.Str(proof),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId),
//                "proofVersionHash" to BodyParam.Str(proofSessionManager.proofHash)
//            )
//        )
//
//        // Update current goal list
//
//
//        return response
//    }
//
//    fun getObjects(): ObjectsResponse =
//        proofSessionManager.coqProjectRequest(
//            "get-objects",
//            mapOf("coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId))
//        )
//
//    fun searchPattern(pattern: String): SearchPatternResponse =
//        proofSessionManager.coqProjectRequest(
//            "search-pattern",
//            mapOf(
//                "pattern" to BodyParam.Str(pattern),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId)
//            )
//        )
//
//    fun printTerm(term: String): PrintTermResponse =
//        proofSessionManager.coqProjectRequest(
//            "print-term",
//            mapOf(
//                "term" to BodyParam.Str(term),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId)
//            )
//        )
//
//    fun checkTerm(term: String): CheckTermResponse =
//        proofSessionManager.coqProjectRequest(
//            "check-term",
//            mapOf(
//                "term" to BodyParam.Str(term),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId)
//            )
//        )
//
//    fun getPremises(goal: String, filePath: String, maxNumberOfPremises: Int = 20): GetPremisesResponse =
//        proofSessionManager.coqProjectRequest(
//            "get-premises",
//            mapOf(
//                "goal" to BodyParam.Str(goal),
//                "filePath" to BodyParam.Str(filePath),
//                "maxNumberOfPremises" to BodyParam.Num(maxNumberOfPremises),
//                "coqSessionId" to BodyParam.Str(proofSessionManager.proofSessionId)
//            )
//        )
//}

@Serializable
data class ProjectRootResponse(
    val message: String,
    val projectRoot: String
)

@Serializable
data class AllCoqFilesResponse(
    val coqFiles: List<String>
)

@Serializable
data class TheoremNamesResponse(
    val message: String,
    val theoremNames: List<String>,
    val isSessionBased: Boolean
)

@Serializable
data class SessionTheoremResponse(
    val theoremStatement: String,
    val theoremProof: String,
    val isIncomplete: Boolean,
    val sessionId: String
) {
    fun prettyPrint(): String {
        val status = if (isIncomplete) "incomplete" else "complete"
        return """
            (* Theorem [currently has status: $status]: *)
            $theoremStatement
            (* Proof: *) 
            $theoremProof
        """.trimIndent()
    }
}

@Serializable
data class TheoremResponse(
    val theoremStatement: String,
    val theoremProof: String? = null,
    val isIncomplete: Boolean? = null,
    val isFromOriginalFile: Boolean? = null
)

@Serializable
data class ProofCheckResponse(
    val success: Boolean,
    val message: String,
    val hash: String,
    val proof: String? = null,
    @Serializable(with = GoalsAsStringListSerializer::class)
    val goals: List<String>? = null,
    val error: ProofError? = null,
    val validPrefix: String? = null,
    val attemptedProof: String? = null
)

/**
 * To keep goals as JSON strings, because they are not used otherwise
 */
object GoalsAsStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        val elements = value.map { Json.parseToJsonElement(it) }
        encoder.encodeSerializableValue(ListSerializer(JsonElement.serializer()), elements)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val elements = decoder.decodeSerializableValue(ListSerializer(JsonElement.serializer()))
        return elements.map { it.toString() }
    }
}

@Serializable
data class ProofError(
    val message: String,
    val name: String? = null,
    val location: ProofErrorLocation? = null
)

@Serializable
data class ProofErrorLocation(
    val start: ProofErrorPosition,
    val end: ProofErrorPosition
)

@Serializable
data class ProofErrorPosition(
    val line: Int,
    val character: Int
)

@Serializable
data class ObjectsResponse(
    val message: String,
    val objects: List<String>? = null
)

@Serializable
data class SearchPatternResponse(
    val message: String,
    val result: List<String>? = null
)

@Serializable
data class PrintTermResponse(
    val message: String,
    val result: String? = null
)

@Serializable
data class CheckTermResponse(
    val message: String,
    val result: String? = null
)

@Serializable
data class GetPremisesResponse(
    val premises: List<String>
)
