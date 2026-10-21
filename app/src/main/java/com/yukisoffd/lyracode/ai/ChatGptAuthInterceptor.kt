package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ChatGptAccountClient
import com.yukisoffd.lyracode.data.ChatGptCodexProtocol
import com.yukisoffd.lyracode.data.ChatGptProtocol
import com.yukisoffd.lyracode.data.ChatGptServiceException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

internal class ChatGptAuthInterceptor(private val accounts: () -> ChatGptAccountClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val profile = original.tag(ApiProfile::class.java)?.takeIf { it.isChatGpt } ?: return chain.proceed(original)
        requireChatGptRoute(original)
        val account = accounts()
        val isInference = original.method == "POST"
        if (isInference) {
            account.refreshUsageIfStale(profile.chatGptAccountId)
            account.checkUsage(profile.chatGptAccountId)
        }
        var synchronousConsumer = false
        val builder = original.newBuilder()
        if (isInference) {
            val buffer = Buffer()
            original.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            synchronousConsumer = !payload.optBoolean("stream")
            builder.post(chatGptResponsesBody(payload, profile.isChatGptCodex).toString().toRequestBody("application/json".toMediaType()))
                .header("Accept", "text/event-stream")
        }
        val authorized = account.authorize(profile.chatGptAccountId, builder.build())
        var response = chain.proceed(authorized)
        // Codex may reject a still locally unexpired access token. No retry after streaming starts.
        if (profile.isChatGptCodex && response.code == 401) {
            response.close()
            val retry = account.authorize(profile.chatGptAccountId, builder.build(), authorized.header("Authorization")!!.removePrefix("Bearer "))
            response = chain.proceed(retry)
        }
        account.observeUsage(profile.chatGptAccountId, response.headers.toMultimap().mapValues { it.value.last() })
        val requestId = response.header("x-request-id") ?: response.header("openai-request-id").orEmpty()
        if (!response.isSuccessful) {
            val body = response.use { it.body?.string().orEmpty() }
            account.fail(profile.chatGptAccountId, response.code, body, requestId)
        }
        val contentType = response.header("Content-Type")
        if (isInference && !(contentType?.contains("text/event-stream", true) == true || (profile.isChatGptCodex && contentType == null))) {
            response.close()
            throw ChatGptServiceException("expected_responses_event_stream", response.code, requestId)
        }
        if (!synchronousConsumer) return response
        // Title, OCR and compression callers consume JSON. The wire request still streams to completion.
        val completed = response.use {
            readChatGptCompletedResponse(requireNotNull(it.body).charStream().buffered()) { error ->
                account.fail(profile.chatGptAccountId, null, error.toString(), requestId)
            }
        }
        return response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Encoding")
            .header("Content-Type", "application/json")
            .body(completed.toString().toResponseBody("application/json".toMediaType())).build()
    }
}

/** Recheck the final wire destination, including redirects, before any OAuth credential is sent. */
internal class ChatGptRouteGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().tag(ApiProfile::class.java)?.isChatGpt == true) requireChatGptRoute(chain.request())
        return chain.proceed(chain.request())
    }
}

internal fun requireChatGptRoute(request: Request) {
    val url = request.url
    val codex = request.tag(ApiProfile::class.java)?.isChatGptCodex == true
    val base = if (codex) ChatGptCodexProtocol.BASE else ChatGptProtocol.RESOURCE
    val models = if (codex) ChatGptCodexProtocol.MODELS else "$base/models"
    require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        ((request.method == "GET" && url.toString() == models) ||
            (request.method == "POST" && url.toString() == "$base/responses"))) {
        "ChatGPT credentials can only be used with this login mode's supported OpenAI endpoints."
    }
}

/** SIWC preview constraints apply to every caller, regardless of saved or imported settings. */
internal fun chatGptResponsesBody(source: JSONObject, codex: Boolean = false): JSONObject = JSONObject(source.toString()).apply {
    listOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt",
        "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id").forEach(::remove)
    put("store", false)
    put("stream", true)
    val input = opt("input").let { value ->
        if (value is JSONArray) value else JSONArray().put(JSONObject().put("role", "user").put("content", value?.toString().orEmpty()))
    }
    for (index in 0 until input.length()) {
        val item = input.optJSONObject(index) ?: continue
        if (item.optString("role") == "system") item.put("role", "developer")
    }
    if (codex) {
        val withInstructions = JSONArray()
        optString("instructions").takeIf(String::isNotBlank)?.let { withInstructions.put(JSONObject().put("role", "developer").put("content", it)) }
        remove("instructions")
        for (index in 0 until input.length()) withInstructions.put(input.get(index))
        put("input", withInstructions)
        put("include", JSONArray().put("reasoning.encrypted_content"))
        return@apply
    }
    val tools = optJSONArray("tools")
    if (tools != null) {
        val functions = JSONArray()
        val hosted = JSONArray()
        for (index in 0 until tools.length()) {
            val tool = tools.getJSONObject(index)
            when (tool.optString("type")) {
                "function", "custom" -> functions.put(tool)
                "namespace", "web_search", "web_search_preview" -> hosted.put(tool)
                else -> throw ChatGptServiceException("unsupported_chatgpt_tool")
            }
        }
        // additional_tools preserves the existing local function names and their replay format.
        val withTools = JSONArray()
        if (functions.length() > 0) withTools.put(JSONObject().put("type", "additional_tools").put("role", "developer").put("tools", functions))
        for (index in 0 until input.length()) withTools.put(input.get(index))
        put("input", withTools)
        if (hosted.length() == 0) { remove("tools"); remove("tool_choice") } else put("tools", hosted)
    } else put("input", input)
}

internal fun readChatGptCompletedResponse(reader: BufferedReader, onError: (JSONObject) -> Nothing): JSONObject {
    var eventType = ""
    val data = StringBuilder()
    fun consume(): JSONObject? {
        if (data.isEmpty()) return null
        val value = data.toString()
        data.clear()
        if (value == "[DONE]") return null
        val event = JSONObject(value)
        val type = event.optString("type").ifBlank { eventType }
        eventType = ""
        when (type) {
            "response.failed", "error" -> onError(event)
            "response.incomplete" -> throw ChatGptServiceException("response_incomplete")
            "response.completed" -> return event.getJSONObject("response").also {
                if (it.optString("status") != "completed") throw ChatGptServiceException("response_not_completed")
            }
        }
        return null
    }
    while (true) {
        val line = reader.readLine() ?: break
        when {
            line.isEmpty() -> consume()?.let { return it }
            line.startsWith("event:") -> eventType = line.substringAfter(':').trim()
            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').trimStart()) }
        }
    }
    consume()?.let { return it }
    throw ChatGptServiceException("stream_interrupted_before_response_completed")
}
