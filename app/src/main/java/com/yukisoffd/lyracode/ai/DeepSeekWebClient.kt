package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.DeepSeekWebConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class DeepSeekWebClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun search(config: DeepSeekWebConfig, query: String, limit: Int): String {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotBlank() && cleanQuery.codePointCount(0, cleanQuery.length) <= 128) {
            "DeepSeek Search query must contain 1 to 128 Unicode characters."
        }
        val count = limit.coerceIn(1, 50)
        val response = post(config, "search", JSONObject()
            .put("query", cleanQuery).put("count", count).put("need_content", false))
        val results = response.getJSONArray("results")
        if (results.length() == 0) {
            return "WEB_SEARCH_EMPTY: DeepSeek Search found no matching results. Refine the query or use another trustworthy source."
        }
        return buildString {
            appendLine("WEB_SEARCH_RESULTS schema=lyra_web_search_v2")
            appendLine("query: $cleanQuery")
            appendLine("provider: DeepSeek Search")
            appendLine("guidance: Treat snippets and summaries as leads only. Read trustworthy candidate pages before using them as evidence.")
            for (index in 0 until minOf(results.length(), count)) {
                val result = results.getJSONObject(index)
                appendLine()
                appendLine("result_${index + 1}:")
                appendLine("title: ${result.getString("title")}")
                appendLine("url: ${result.getString("url")}")
                appendLine("source: DeepSeek Search")
                appendLine("rerank_score: ${result.optDouble("rerank_score", 0.0)}")
                appendLine("authority: ${result.optInt("authority", -1)}")
                if (!result.isNull("site_name")) appendLine("site_name: ${result.optString("site_name")}")
                if (!result.isNull("date")) appendLine("date: ${result.optString("date")}")
                appendLine("snippet: ${result.optString("snippet")}")
                appendLine("summary: ${result.optString("summary")}")
            }
        }.trim()
    }

    suspend fun readPage(config: DeepSeekWebConfig, url: String): String {
        val cleanUrl = url.trim()
        val uri = runCatching { URI(cleanUrl) }.getOrNull()
        require(cleanUrl.length <= 8192 && uri != null &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "DeepSeek Fetch requires an HTTP/HTTPS URL with a host, no credentials and at most 8192 characters."
        }
        val response = post(config, "fetch", JSONObject().put("url", cleanUrl))
        val content = response.getString("content")
        return buildString {
            appendLine("WEB_PAGE_READ_RESULT schema=lyra_web_page_v2")
            appendLine("status: ${if (content.isBlank()) "empty" else "readable"}")
            appendLine("title: ${response.getString("title")}")
            appendLine("url: $cleanUrl")
            appendLine("extraction: deepseek_fetch")
            appendLine("note: Markdown content returned by DeepSeek Fetch. Evaluate source quality before relying on it.")
            appendLine()
            append(content.ifBlank { "No readable body text was extracted. Use another source." })
        }
    }

    private suspend fun post(config: DeepSeekWebConfig, operation: String, body: JSONObject): JSONObject {
        val normalized = config.normalized()
        require(normalized.apiKey.isNotBlank()) { "Configure the API Key for DeepSeek ${operation.replaceFirstChar { it.uppercase() }} in Settings > Web search." }
        require(normalized.apiKey.all { it.code in 32..126 }) { "DeepSeek API Key contains invalid characters." }
        val request = Request.Builder()
            .url(normalized.endpoint(operation))
            .header("Authorization", "Bearer ${normalized.apiKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
                                    .getOrNull().orEmpty().replace(normalized.apiKey, "[redacted]").take(1000)
                                throw IOException("DeepSeek $operation HTTP ${it.code}${if (message.isBlank()) "" else ": $message"}")
                            }
                            JSONObject(text)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
}
