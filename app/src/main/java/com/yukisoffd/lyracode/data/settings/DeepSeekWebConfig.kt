package com.yukisoffd.lyracode.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class DeepSeekWebConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
) {
    val isConfigured: Boolean
        get() = apiKey.trim().let { key ->
            key.isNotEmpty() && key.all { it.code in 32..126 } &&
                runCatching { endpoint("search") }.isSuccess
        }

    fun normalized(): DeepSeekWebConfig = copy(
        baseUrl = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
        apiKey = apiKey.trim(),
    )

    fun endpoint(operation: String): String {
        require(operation == "search" || operation == "fetch")
        val base = normalized().baseUrl.toHttpUrlOrNull()
        require(base != null && base.host.isNotBlank() && base.username.isEmpty() &&
            base.password.isEmpty() && base.query == null && base.fragment == null) {
            "DeepSeek base URL must be an HTTP/HTTPS URL without credentials, query or fragment."
        }
        return base.toString().trimEnd('/').removeSuffix("/v1") + "/v1/tools/$operation"
    }

    fun toJson(includeSecrets: Boolean): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("baseUrl", baseUrl)
        .apply { if (includeSecrets) put("apiKey", apiKey) }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        fun fromJson(json: JSONObject, existingApiKey: String = ""): DeepSeekWebConfig = DeepSeekWebConfig(
            enabled = json.optBoolean("enabled", false),
            baseUrl = json.optString("baseUrl", DEFAULT_BASE_URL),
            apiKey = if (json.has("apiKey") && !json.isNull("apiKey")) json.getString("apiKey") else existingApiKey,
        ).normalized()
    }
}
