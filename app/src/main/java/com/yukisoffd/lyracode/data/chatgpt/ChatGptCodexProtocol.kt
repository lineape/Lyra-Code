package com.yukisoffd.lyracode.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest

/** Codex compatibility is a separate OAuth audience, never a fallback destination for SIWC tokens.
 * Protocol references: openai/codex and rikkahub/rikkahub commit 318cffef63dc4abd7636b06835c035e75066403a.
 * These private endpoints can change independently of the public SIWC integration. */
internal object ChatGptCodexProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN = "${ChatGptProtocol.ISSUER}/oauth/token"
    const val BASE = "https://chatgpt.com/backend-api/codex"
    const val USAGE = "https://chatgpt.com/backend-api/wham/usage"
    const val CLIENT_VERSION = "0.163.0"
    const val MODELS = "$BASE/models?client_version=$CLIENT_VERSION"
    const val SCOPES = "openid profile email offline_access"
    const val CALLBACK_PORT = 1455

    fun authorizationUrl(attempt: ChatGptSignInAttempt): HttpUrl =
        "${ChatGptProtocol.ISSUER}/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", ChatGptProtocol.challenge(attempt.verifier))
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("originator", "codex_cli_rs")
            .build()

    // A fixed OAuth client can contain several users/workspaces. Do not use it as the storage key.
    fun storageId(subject: String, accountId: String): String = "codex:" +
        ChatGptProtocol.encode(MessageDigest.getInstance("SHA-256").digest("$subject\u0000$accountId".toByteArray()))

    fun planName(value: String): String = when (value.lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "prolite" -> "Pro"
        "pro" -> "Pro (More)"
        "promax" -> "Pro (Max)"
        "team" -> "Team"
        "business" -> "Business"
        "ent26", "enterprise", "hc" -> "Enterprise"
        "edu", "education" -> "Edu"
        else -> value.split('_').filter(String::isNotBlank).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    fun usage(root: JSONObject, now: Long): ChatGptQuotaSnapshot {
        val rate = root.optJSONObject("rate_limit")
        val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
            val window = rate?.optJSONObject(key) ?: return@mapNotNull null
            val used = (window.opt("used_percent") as? Number)?.toDouble() ?: return@mapNotNull null
            val seconds = (window.opt("limit_window_seconds") as? Number)?.toLong() ?: return@mapNotNull null
            if (!used.isFinite() || used !in 0.0..100.0 || seconds !in 1..Int.MAX_VALUE.toLong() * 60) return@mapNotNull null
            val reset = (window.opt("reset_at") as? Number)?.toLong()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
                ?: (window.opt("reset_after_seconds") as? Number)?.toLong()?.takeIf { it in 1..31_536_000 }?.let { now + it * 1000 }
            ChatGptUsageWindow(((seconds + 59) / 60).toInt(), used, reset, now)
        }.distinctBy { it.minutes }
        require(rate != null) { "ChatGPT usage data is unavailable." }
        return ChatGptQuotaSnapshot(windows, rate.optBoolean("limit_reached") || (rate.has("allowed") && !rate.optBoolean("allowed")),
            root.optString("plan_type").takeUnless { it == "null" }.orEmpty())
    }
}

internal data class ChatGptQuotaSnapshot(val windows: List<ChatGptUsageWindow>, val limitReached: Boolean, val planType: String)
