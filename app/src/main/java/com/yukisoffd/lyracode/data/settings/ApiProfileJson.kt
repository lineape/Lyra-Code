package com.yukisoffd.lyracode.data

import org.json.JSONArray
import org.json.JSONObject

/** The same codec is used for encrypted storage and optional secret-bearing backups. */
internal object ApiProfileJson {
    fun encode(profile: ApiProfile, includeSecrets: Boolean = true): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("presetId", profile.presetId)
        .put("presetPlanId", profile.presetPlanId)
        .put("name", profile.name)
        .put("apiKey", if (includeSecrets) profile.apiKey else "")
        .put("secondaryApiKeys", JSONArray(if (includeSecrets) profile.allApiKeys.filter { it != profile.apiKey.trim() } else emptyList()))
        .put("baseUrl", profile.baseUrl)
        .put("chatPath", ApiProfile.normalizedChatPath(profile.apiFormat, profile.chatPath))
        .put("apiFormat", profile.apiFormat)
        .put("selectedModel", profile.selectedModel)
        .put("savedModels", JSONArray(profile.savedModels.distinct()))
        .put("enabledModels", JSONArray(profile.enabledModels.distinct()))
        .put("useResponsesApi", profile.apiFormat == ApiProfile.API_FORMAT_OPENAI && profile.useResponsesApi)
        .put("modelRequestOverrides", ModelRequestCustomization.encodeMap(profile.modelRequestOverrides))

    fun decode(item: JSONObject, defaultBaseUrl: String, defaultModel: String, defaultName: String = "API"): ApiProfile {
        fun strings(name: String): List<String> {
            val array = item.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank).distinct()
        }
        val format = item.optString("apiFormat").ifBlank { ApiProfile.API_FORMAT_OPENAI }
        val models = strings("savedModels")
        val keys = (listOf(item.optString("apiKey")) + strings("secondaryApiKeys"))
            .map(String::trim).filter(String::isNotBlank).distinct()
        val storedName = item.optString("name").ifBlank { defaultName }
        val displayName = if (item.optString("id").startsWith("newapi:") && storedName.startsWith("New API · ")) {
            storedName.replaceFirst("New API · ", "${NewApiAccountClient.PLATFORM_NAME} · ")
        } else storedName
        return ApiProfile(
            id = item.optString("id").ifBlank { AppSettings.newId() },
            name = displayName,
            apiKey = keys.firstOrNull().orEmpty(),
            secondaryApiKeys = keys.drop(1),
            baseUrl = item.optString("baseUrl").ifBlank { defaultBaseUrl },
            chatPath = ApiProfile.normalizedChatPath(format, item.optString("chatPath")),
            apiFormat = format,
            selectedModel = if (item.has("selectedModel")) item.optString("selectedModel") else defaultModel,
            savedModels = models,
            enabledModels = if (item.has("enabledModels")) strings("enabledModels") else models,
            useResponsesApi = format == ApiProfile.API_FORMAT_OPENAI && item.optBoolean("useResponsesApi"),
            presetId = item.optString("presetId"),
            presetPlanId = item.optString("presetPlanId"),
            modelRequestOverrides = ModelRequestCustomization.decodeMap(item.optJSONObject("modelRequestOverrides")),
        )
    }

    fun merge(current: ApiProfile, incoming: ApiProfile): ApiProfile {
        val keys = (incoming.allApiKeys + current.allApiKeys).distinct()
        return incoming.copy(
            id = current.id,
            apiKey = keys.firstOrNull().orEmpty(),
            secondaryApiKeys = keys.drop(1),
            savedModels = (incoming.savedModels + current.savedModels).filter(String::isNotBlank).distinct(),
            enabledModels = (incoming.enabledModels + current.enabledModels).filter(String::isNotBlank).distinct(),
        )
    }
}
