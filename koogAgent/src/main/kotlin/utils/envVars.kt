package org.example.utils

import io.github.cdimascio.dotenv.dotenv

internal val dotenv = dotenv {
    ignoreIfMissing = true
    systemProperties = true
}

internal fun getEnv(key: String): String {
    return dotenv[key]
        ?: error("$key is not set in the .env file")
}