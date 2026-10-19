package com.yukisoffd.lyracode.data

import org.json.JSONArray
import org.json.JSONObject

data class MessageTokenCosts(val prompt: Long, val output: Long)

internal fun ChatMessage.calculateTokenCosts(count: (String) -> Long): MessageTokenCosts {
    val body = count(content)
    val output = if (role.equals("assistant", ignoreCase = true)) {
        body + count(thinking) + count(toolCallsOutputText(rawJson))
    } else 0L
    val prompt = when (role.lowercase(java.util.Locale.ROOT)) {
        "assistant" -> output
        "user", "tool" -> body
        else -> body + count(thinking)
    }
    return MessageTokenCosts(8L + prompt, output)
}

internal fun toolCallsOutputText(rawJson: String?): String {
    val raw = rawJson?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ""
    val calls = raw.optJSONArray("tool_calls") ?: return ""
    return buildString {
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function")
            if (function != null) {
                append(function.optString("name")).append('\n').append(function.optString("arguments"))
            } else {
                append(call.optString("name")).append('\n')
                when (val input = call.opt("input")) {
                    is JSONObject, is JSONArray -> append(input.toString())
                    null -> append(call.toString())
                    else -> append(input.toString())
                }
            }
            append('\n')
        }
    }
}
