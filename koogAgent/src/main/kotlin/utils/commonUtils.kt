package org.example.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

inline fun <reified T> extractPropFromJsonString(jsonString: String, property: String): T {
    val jsonElement = Json.parseToJsonElement(jsonString).jsonObject[property]
        ?: throw IllegalArgumentException("Property '$property' not found in JSON")

    return when (T::class) {
        String::class -> jsonElement.jsonPrimitive.content as T
        Int::class -> jsonElement.jsonPrimitive.int as T
        Double::class -> jsonElement.jsonPrimitive.double as T
        Boolean::class -> jsonElement.jsonPrimitive.boolean as T
        else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
    }
}