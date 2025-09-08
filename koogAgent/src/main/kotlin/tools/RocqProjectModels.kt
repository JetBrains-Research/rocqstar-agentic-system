package org.example.tools

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionTheoremResponse(
    val theoremStatement: String,
    val theoremProof: String,
    val isIncomplete: Boolean,
    val sessionId: String
)

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
data class GetPremisesResponse(
    val premises: List<String>
)
