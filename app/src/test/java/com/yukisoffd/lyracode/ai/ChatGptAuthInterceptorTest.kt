package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files

class ChatGptAuthInterceptorTest {
    private val profile = ApiProfile("chatgpt:oaiapp_fixture", "ChatGPT", "", ChatGptProtocol.RESOURCE,
        selectedModel = "fixture", savedModels = listOf("fixture"), chatGptAccountId = "oaiapp_fixture")

    @Test fun inferencePinsOAuthToPublicResponsesAndForcesPreviewRequirements() {
        val accounts = ChatGptAccountClient(chatGptFixtureStore())
        val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor { chain ->
            assertEquals("Bearer fixture-access", chain.request().header("Authorization"))
            val payload = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            assertTrue(payload.getBoolean("stream"))
            assertFalse(payload.getBoolean("store"))
            assertFalse(payload.has("temperature"))
            assertFalse(payload.has("max_output_tokens"))
            assertFalse(payload.has("previous_response_id"))
            assertEquals("user", payload.getJSONArray("input").getJSONObject(0).getString("role"))
            response(chain.request(), 200, "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n", "text/event-stream")
        }.build()
        val body = JSONObject().put("model", "fixture").put("input", "title").put("stream", false).put("store", true)
            .put("temperature", 0.2).put("max_output_tokens", 128).put("previous_response_id", "old")
        client.newCall(request(body)).execute().use {
            assertTrue(it.header("Content-Type")!!.contains("application/json"))
            assertEquals("completed", JSONObject(it.body!!.string()).getString("status"))
        }
    }

    @Test fun rejectsProxyAndUnsupportedRoutesBeforeAttachingCredentials() {
        val accounts = ChatGptAccountClient(chatGptFixtureStore())
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor { calls++; error("Must not send OAuth") }.build()
        for (url in listOf("https://proxy.test/v1/responses", "https://chatgpt.com/backend-api/codex/responses",
            "https://api.openai.com/v1/chat/completions", "http://api.openai.com/v1/responses", "https://api.openai.com/v1/responses?proxy=true")) {
            val req = request(JSONObject().put("input", "text")).newBuilder().url(url).build()
            assertThrows(IllegalArgumentException::class.java) { client.newCall(req).execute() }
        }
        assertEquals(0, calls)
    }

    @Test fun directRouteLimitPublishesNoticeAndNeverEntersAutomaticRetry() = runTest {
        val accounts = ChatGptAccountClient(chatGptFixtureStore())
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor { chain ->
            calls++
            response(chain.request(), 429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"fixture"}}""", "application/json")
        }.build()
        var error: Throwable? = null
        try { executeModelRequestWithRetry(retryDelayMillis = 0) { client.newCall(request(JSONObject().put("input", "text").put("stream", true))).execute() } }
        catch (caught: Throwable) { error = caught }
        assertTrue(error is ChatGptUsageLimitException)
        assertEquals(1, calls)
        assertNotNull(accounts.notice.value)
        assertTrue(accounts.accounts().single().isLoggedIn)
        // A per-app limit does not establish that either entire subscription window is empty.
        assertTrue(accounts.usage("oaiapp_fixture").isEmpty())
    }

    @Test fun streamCompletionRequiresCompletedAndCatchesLateQuotaErrors() {
        val accounts = ChatGptAccountClient(chatGptFixtureStore())
        val partial = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"saved partial content\"}\n\n"
        for (terminal in listOf("", "data: [DONE]\n\n", "data: {\"type\":\"response.incomplete\"}\n\n")) {
            assertThrows(ChatGptServiceException::class.java) {
                readChatGptCompletedResponse(StringReader(partial + terminal).buffered()) { accounts.fail("oaiapp_fixture", null, it.toString()) }
            }
        }
        val failed = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        assertThrows(ChatGptUsageLimitException::class.java) {
            readChatGptCompletedResponse(StringReader(partial + failed).buffered()) { accounts.fail("oaiapp_fixture", null, it.toString()) }
        }
        assertNotNull(accounts.notice.value)
    }

    @Test fun localToolsUseAdditionalToolsInputAndSystemBecomesDeveloper() {
        val normalized = chatGptResponsesBody(JSONObject().put("model", "fixture").put("input", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "instructions"))
            .put(JSONObject().put("role", "user").put("content", "hello")))
            .put("tools", JSONArray().put(JSONObject().put("type", "function").put("name", "local_tool").put("parameters", JSONObject())))
            .put("tool_choice", "auto"))
        assertFalse(normalized.has("tools"))
        val inputs = normalized.getJSONArray("input")
        assertEquals("additional_tools", inputs.getJSONObject(0).getString("type"))
        assertEquals("local_tool", inputs.getJSONObject(0).getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("developer", inputs.getJSONObject(1).getString("role"))
    }

    @Test fun oauthSkipsRequestOverridesPreviewsKeyRotationAndHttpAudit() {
        var records = 0
        val sink = object : HttpAuditSink {
            override fun start(title: String, detail: String): Long { records++; return 1 }
            override fun write(id: Long, section: String, text: String, append: Boolean) { records++ }
        }
        val directory = Files.createTempDirectory("chatgpt-audit-test").toFile()
        try {
            val audit = ModelHttpAudit(sink, directory)
            val customized = profile.copy(apiKey = "wrong-key", secondaryApiKeys = listOf("other-key"), modelRequestOverrides = mapOf(
                "fixture" to ModelRequestCustomization(headers = mapOf("Authorization" to "Bearer wrong-override"), body = """{"store":true}""")))
            val accounts = ChatGptAccountClient(chatGptFixtureStore())
            val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor(ProviderKeyInterceptor())
                .addInterceptor(audit.application).addInterceptor { chain ->
                    assertEquals("Bearer fixture-access", chain.request().header("Authorization"))
                    response(chain.request(), 200, "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n", "text/event-stream")
                }.build()
            client.newCall(Request.Builder().url(customized.responsesEndpoint).post("""{"input":"text","stream":true}""".toRequestBody())
                .build().customizedFor(customized, "fixture")).execute().close()
            assertEquals(0, records)
            assertTrue(customized.allApiKeys.isEmpty())
            assertTrue(customized.usesResponsesApi)
        } finally { directory.deleteRecursively() }
    }

    @Test fun switchingServicesPreservesContentAndToolCallsWithoutAccountSpecificReplayState() {
        val raw = JSONObject().put("role", "assistant").put("content", "completed work")
            .put("reasoning_content", "provider-specific thinking")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "finished-tool")))
            .put(RESPONSES_REPLAY_ITEMS_KEY, JSONArray().put(JSONObject().put("type", "web_search_call").put("id", "account-specific")))
        val otherService = profile.copy(id = "other-service", chatGptAccountId = "", useResponsesApi = true)
        val otherAccount = profile.copy(id = "chatgpt:oaiapp_other", chatGptAccountId = "oaiapp_other")
        for (target in listOf(otherService, otherAccount)) {
            val replay = chatGptHistoryForService(raw, profile.id, target)
            assertEquals("completed work", replay.getString("content"))
            assertEquals("finished-tool", replay.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertFalse(replay.has(RESPONSES_REPLAY_ITEMS_KEY))
            assertFalse(replay.has("reasoning_content"))
        }
        assertTrue(raw.has(RESPONSES_REPLAY_ITEMS_KEY)) // Stored history is unchanged.
        assertTrue(chatGptHistoryForService(raw, profile.id, profile).has(RESPONSES_REPLAY_ITEMS_KEY))
    }

    @Test fun statelessToolContinuationPreservesOpaqueReasoningForTheSameAccount() {
        val item = JSONObject().put("type", "reasoning").put("id", "rs_fixture").put("summary", JSONArray())
            .put("encrypted_content", "opaque-model-state")
        val replayItems = JSONArray()
        collectReplayableResponseItem(item, replayItems)
        val raw = JSONObject().put("role", "assistant").put("content", "saved visible text").put(RESPONSES_REPLAY_ITEMS_KEY, replayItems)
        val sameAccount = JSONArray()
        appendReplayableResponseItems(chatGptHistoryForService(raw, profile.id, profile), sameAccount)
        assertEquals("opaque-model-state", sameAccount.getJSONObject(0).getString("encrypted_content"))
        val differentAccount = JSONArray()
        appendReplayableResponseItems(chatGptHistoryForService(raw, profile.id, profile.copy(id = "chatgpt:other", chatGptAccountId = "other")), differentAccount)
        assertEquals(0, differentAccount.length())
        assertEquals("saved visible text", raw.getString("content"))
    }

    @Test fun codexInferenceRetries401OnceAndAdaptsMissingContentTypeWithoutChangingSiwcTools() {
        val codex = profile.copy(id = "chatgpt:$codexFixtureId", chatGptAccountId = codexFixtureId)
        var refreshes = 0
        val accounts = ChatGptAccountClient(codexFixtureStore(), chatGptHttp { request ->
            when (request.url.toString()) {
                ChatGptCodexProtocol.USAGE -> 200 to codexUsageFixture().toString()
                ChatGptCodexProtocol.TOKEN -> {
                    refreshes++
                    200 to """{"access_token":"rotated-access","expires_in":3600}"""
                }
                else -> error("Unexpected account route")
            }
        })
        var sends = 0
        val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor { chain ->
            val req = chain.request()
            requireChatGptRoute(req)
            assertEquals(CODEX_FIXTURE_ACCOUNT, req.header("ChatGPT-Account-ID"))
            assertEquals("codex_cli_rs", req.header("originator"))
            val body = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
            assertFalse(body.has("instructions"))
            assertEquals("developer", body.getJSONArray("input").getJSONObject(0).getString("role"))
            assertEquals("instructions", body.getJSONArray("input").getJSONObject(0).getString("content"))
            assertEquals("local_tool", body.getJSONArray("tools").getJSONObject(0).getString("name"))
            assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
            sends++
            if (sends == 1) response(req, 401, """{"error":{"code":"unauthorized"}}""", "application/json")
            else {
                assertEquals("Bearer rotated-access", req.header("Authorization"))
                Response.Builder().request(req).protocol(Protocol.HTTP_2).code(200).message("fixture")
                    .body("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n".toResponseBody(null)).build()
            }
        }.build()
        val body = JSONObject().put("input", "hello").put("instructions", "instructions").put("stream", false)
            .put("tools", JSONArray().put(JSONObject().put("type", "function").put("name", "local_tool")))
        val req = Request.Builder().url(codex.responsesEndpoint).post(body.toString().toRequestBody()).build().customizedFor(codex, "fixture")
        client.newCall(req).execute().use { assertEquals("completed", JSONObject(it.body!!.string()).getString("status")) }
        assertEquals(2, sends)
        assertEquals(1, refreshes)
    }

    @Test fun missingContentTypeIsOnlyAllowedForCodexAndConflictingTypesAlwaysFail() {
        for ((codex, declared) in listOf(false to null, true to "application/json", true to "text/html")) {
            val accountProfile = if (codex) profile.copy(chatGptAccountId = codexFixtureId) else profile
            val accounts = ChatGptAccountClient(if (codex) codexFixtureStore() else chatGptFixtureStore(), chatGptHttp { 200 to codexUsageFixture().toString() })
            val client = OkHttpClient.Builder().addInterceptor(ChatGptAuthInterceptor { accounts }).addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .apply { declared?.let { header("Content-Type", it) } }.body("data: {}\n\n".toResponseBody()).build()
            }.build()
            val req = Request.Builder().url(accountProfile.responsesEndpoint).post("""{"input":"text","stream":true}""".toRequestBody())
                .build().customizedFor(accountProfile, "fixture")
            assertThrows(ChatGptServiceException::class.java) { client.newCall(req).execute() }
        }
    }

    @Test fun compatibilityRouteGuardRejectsCrossAudienceAndRedirectedRequests() {
        val codex = profile.copy(chatGptAccountId = codexFixtureId)
        for (url in listOf(profile.responsesEndpoint, "https://chatgpt.com/backend-api/codex/responses?forward=true",
            "https://chatgpt.com/backend-api/conversation", "https://proxy.test/backend-api/codex/responses")) {
            val req = Request.Builder().url(url).post("{}".toRequestBody()).build().customizedFor(codex, "fixture")
            assertThrows(IllegalArgumentException::class.java) { requireChatGptRoute(req) }
        }
    }

    private fun request(body: JSONObject) = Request.Builder().url(profile.responsesEndpoint)
        .post(body.toString().toRequestBody("application/json".toMediaType())).build().customizedFor(profile, "fixture")

    private fun response(request: Request, status: Int, body: String, contentType: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type", contentType)
        .body(body.toResponseBody(contentType.toMediaType())).build()
}
