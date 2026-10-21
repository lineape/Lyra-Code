package com.yukisoffd.lyracode.data

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val CODEX_FIXTURE_ACCOUNT = "workspace-fixture"
internal val codexFixtureId: String get() = ChatGptCodexProtocol.storageId("fixture-user", CODEX_FIXTURE_ACCOUNT)
internal fun codexFixtureStore(): MemoryChatGptStore = chatGptFixtureStore().apply {
    val account = state.getJSONObject("accounts").getJSONObject("oaiapp_fixture")
    account.put("client_id", codexFixtureId).put("account_id", CODEX_FIXTURE_ACCOUNT).put("scope", ChatGptCodexProtocol.SCOPES)
    state.put("accounts", JSONObject().put(codexFixtureId, account))
}
internal fun codexUsageFixture(used: Double = 20.0, weekly: Double = 40.0, limited: Boolean = false): JSONObject = JSONObject()
    .put("plan_type", "plus").put("rate_limit", JSONObject().put("allowed", true).put("limit_reached", limited)
        .put("primary_window", codexWindow(18000, used)).put("secondary_window", codexWindow(604800, weekly)))
private fun codexWindow(seconds: Long, used: Double) = JSONObject().put("limit_window_seconds", seconds)
    .put("used_percent", used).put("reset_at", System.currentTimeMillis() / 1000 + 3600)

class ChatGptCodexTest {
    @Test fun compatibilityLoginUsesItsOwnAudiencePkceAndNeverSendsSiwcHints() {
        val attempt = ChatGptSignInAttempt("http://127.0.0.1:1455/auth/callback", codex = true)
        val url = ChatGptProtocol.authorizationUrl(attempt, "host", null)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals(ChatGptCodexProtocol.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(ChatGptCodexProtocol.SCOPES, url.queryParameter("scope"))
        assertEquals(ChatGptProtocol.challenge(attempt.verifier), url.queryParameter("code_challenge"))
        assertEquals(attempt.state, url.queryParameter("state"))
        assertNull(url.queryParameter("resource"))
        assertNull(url.queryParameter("id_token_hint"))
        assertNull(url.queryParameter("agent_name_hint"))
        val callback = attempt.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", attempt.state).addQueryParameter("code", "code").build()
        assertEquals(ChatGptCodexProtocol.CLIENT_ID to "code", ChatGptProtocol.callback(attempt, callback, System.currentTimeMillis()))
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptProtocol.callback(attempt, callback.newBuilder().addQueryParameter("client_id", "siwc-client").build(), System.currentTimeMillis())
        }
    }

    @Test fun fixedClientSeparatesAccountsAndWorkspacesAndKeepsSiwcRegistration() {
        val store = chatGptFixtureStore()
        var workspace = CODEX_FIXTURE_ACCOUNT
        var usageQueries = 0
        val client = ChatGptAccountClient(store, chatGptHttp { request ->
            if (request.url.toString() == ChatGptProtocol.JWKS) 200 to ChatGptTestIdentity.jwks.toString()
            else if (request.url.toString() == ChatGptCodexProtocol.USAGE) { usageQueries++; 200 to codexUsageFixture().toString() }
            else {
                assertEquals(ChatGptCodexProtocol.TOKEN, request.url.toString())
                val form = request.body as FormBody
                val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                assertEquals(ChatGptCodexProtocol.CLIENT_ID, values["client_id"])
                assertNull(values["resource"])
                val claims = ChatGptTestIdentity.claims(ChatGptCodexProtocol.CLIENT_ID).apply {
                    remove("nonce")
                    getJSONObject("https://api.openai.com/auth").put("chatgpt_account_id", workspace)
                }
                200 to tokens().put("id_token", ChatGptTestIdentity.token(claims)).toString()
            }
        })
        fun signIn(selected: ChatGptAccount? = null): ChatGptAccount {
            val attempt = ChatGptSignInAttempt("http://127.0.0.1:1455/auth/callback", codex = true)
            return client.completeSignIn(attempt, attempt.redirectUri.toHttpUrl().newBuilder()
                .addQueryParameter("state", attempt.state).addQueryParameter("code", "fixture").build(), selected)
        }
        // A failed background preflight before login must not throttle the new session's first query.
        client.refreshUsageIfStale(codexFixtureId)
        val first = signIn()
        client.refreshUsageIfStale(first.clientId)
        assertEquals(1, usageQueries)
        assertTrue(first.isCodex && first.planEnabled)
        assertEquals("Plus", first.plan)
        assertEquals(CODEX_FIXTURE_ACCOUNT, first.accountId)
        workspace = "other-workspace"
        assertThrows(IllegalArgumentException::class.java) { signIn(first) }
        val other = signIn()
        assertNotEquals(first.clientId, other.clientId)
        assertEquals(3, client.accounts().size)
        assertTrue(client.accounts().all { it.isLoggedIn })
    }

    @Test fun rejectedTokenRefreshIsJsonSerializedAndRetainsOmittedRotatingFields() {
        val store = codexFixtureStore()
        val refreshes = AtomicInteger()
        val client = ChatGptAccountClient(store, chatGptHttp { request ->
            assertEquals(ChatGptCodexProtocol.TOKEN, request.url.toString())
            assertTrue(request.body!!.contentType().toString().contains("application/json"))
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(ChatGptCodexProtocol.CLIENT_ID, body.getString("client_id"))
            assertEquals("fixture-refresh", body.getString("refresh_token"))
            assertFalse(body.has("resource"))
            assertFalse(body.has("scope"))
            refreshes.incrementAndGet()
            200 to tokens().put("access_token", "new-access").apply { remove("refresh_token") }.toString()
        })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((0..7).map { Callable { client.accessToken(codexFixtureId, "fixture-access") } }).map { it.get() }
            assertTrue(results.all { it == "new-access" })
            assertEquals(1, refreshes.get())
            assertEquals("fixture-refresh", store.state.getJSONObject("accounts").getJSONObject(codexFixtureId).getString("refresh_token"))
            assertTrue(client.accounts().single().isLoggedIn)
        } finally { executor.shutdownNow() }
    }

    @Test fun modelCatalog401RetriesOnceWithFreshCredentialsAndWorkspaceHeaders() {
        for (rejectAgain in listOf(false, true)) {
            val store = codexFixtureStore()
            var requests = 0
            var refreshes = 0
            val client = ChatGptAccountClient(store, chatGptHttp { request ->
                if (request.url.toString() == ChatGptCodexProtocol.TOKEN) {
                    refreshes++
                    200 to tokens().put("access_token", "new-access").put("refresh_token", "rotated-refresh").toString()
                } else {
                    assertEquals(ChatGptCodexProtocol.MODELS, request.url.toString())
                    assertEquals(CODEX_FIXTURE_ACCOUNT, request.header("ChatGPT-Account-ID"))
                    assertEquals("codex_cli_rs", request.header("originator"))
                    requests++
                    if (requests == 1 || rejectAgain) 401 to """{"error":{"code":"unauthorized"}}"""
                    else {
                        assertEquals("Bearer new-access", request.header("Authorization"))
                        200 to """{"models":[{"slug":"z","display_name":"Visible","visibility":"list"},{"slug":"hidden","visibility":"hidden"}]}"""
                    }
                }
            })
            if (rejectAgain) assertThrows(ChatGptServiceException::class.java) { client.fetchModels(codexFixtureId) }
            else assertEquals(listOf("z"), client.fetchModels(codexFixtureId).map { it.slug })
            assertEquals(2, requests)
            assertEquals(1, refreshes)
        }
    }

    @Test fun allowanceQueryMapsActualWindowsPlanAndResetAndThrottlesPreflight() {
        val store = codexFixtureStore()
        var queries = 0
        val client = ChatGptAccountClient(store, chatGptHttp { request ->
            assertEquals(ChatGptCodexProtocol.USAGE, request.url.toString())
            assertEquals("Bearer fixture-access", request.header("Authorization"))
            queries++
            200 to codexUsageFixture(94.5, 98.0).put("plan_type", "promax").toString()
        })
        client.refreshUsageIfStale(codexFixtureId)
        repeat(4) { client.refreshUsageIfStale(codexFixtureId) }
        assertEquals(1, queries)
        assertEquals("Pro (Max)", client.accounts().single().plan)
        val windows = client.usage(codexFixtureId).associateBy { it.minutes }
        assertEquals(5.5, windows.getValue(300).remaining()!!, 0.001)
        assertEquals(2.0, windows.getValue(10080).remaining()!!, 0.001)
        assertTrue(windows.values.all { it.resetsAt!! > System.currentTimeMillis() })
        assertNull(client.notice.value)
    }

    @Test fun usageParsingHandlesReversedWindowsMissingValuesAndRelativeResets() {
        val now = System.currentTimeMillis()
        val root = codexUsageFixture().apply {
            getJSONObject("rate_limit").put("primary_window", codexWindow(604800, 95.0))
                .put("secondary_window", codexWindow(18000, 99.0).apply { remove("reset_at"); put("reset_after_seconds", 300) })
        }
        val windows = ChatGptCodexProtocol.usage(root, now).windows
        assertEquals(listOf(10080, 300), windows.map { it.minutes })
        assertEquals(now + 300_000, windows.last().resetsAt)
        root.getJSONObject("rate_limit").getJSONObject("primary_window").put("used_percent", "NaN")
        root.getJSONObject("rate_limit").getJSONObject("secondary_window").remove("limit_window_seconds")
        assertTrue(ChatGptCodexProtocol.usage(root, now).windows.isEmpty())
        root.getJSONObject("rate_limit").put("primary_window", codexWindow(7200, 95.0))
        assertEquals(120, ChatGptCodexProtocol.usage(root, now).windows.single().minutes)
        assertThrows(IllegalArgumentException::class.java) { ChatGptCodexProtocol.usage(JSONObject(), now) }
    }

    @Test fun hardLimitWithUnknownPercentBlocksAndFailedQueryKeepsTheObservation() {
        val store = codexFixtureStore()
        var status = 200
        val client = ChatGptAccountClient(store, chatGptHttp {
            status to if (status == 200) """{"rate_limit":{"allowed":false,"limit_reached":true}}""" else "unavailable"
        })
        client.refreshUsage(codexFixtureId)
        assertTrue(client.usage(codexFixtureId).isEmpty())
        assertNotNull(client.notice.value)
        client.dismissNotice()
        client.refreshUsage(codexFixtureId)
        assertNull(client.notice.value) // Do not repeat the same background warning every minute.
        assertThrows(ChatGptUsageLimitException::class.java) { client.checkUsage(codexFixtureId) }
        val observed = store.state.getJSONObject("accounts").getJSONObject(codexFixtureId).getLong("usage_checked_at")
        status = 503
        assertThrows(ChatGptServiceException::class.java) { client.refreshUsage(codexFixtureId) }
        assertEquals(observed, store.state.getJSONObject("accounts").getJSONObject(codexFixtureId).getLong("usage_checked_at"))
        assertTrue(client.accounts().single().isLoggedIn)
    }

    @Test fun exhaustionHasPriorityAndResetRefreshAllowsContinuation() {
        val store = codexFixtureStore()
        var payload = codexUsageFixture(100.0, 95.0, limited = true)
        val client = ChatGptAccountClient(store, chatGptHttp { 200 to payload.toString() })
        client.refreshUsage(codexFixtureId)
        assertNotNull(client.notice.value)
        assertEquals(300, client.notice.value!!.windowMinutes)
        assertThrows(ChatGptUsageLimitException::class.java) { client.checkUsage(codexFixtureId) }
        val saved = store.state.getJSONObject("accounts").getJSONObject(codexFixtureId)
        val resetNow = System.currentTimeMillis()
        saved.getJSONObject("usage").getJSONObject("300").put("observed", resetNow - 2000).put("reset", resetNow - 1000)
        payload = codexUsageFixture(2.0, 20.0)
        client.refreshUsageIfStale(codexFixtureId)
        client.checkUsage(codexFixtureId)
        assertEquals(98.0, client.usage(codexFixtureId).first { it.minutes == 300 }.remaining()!!, 0.001)
    }

    @Test fun lateUsageDoesNotWriteBackAfterSignOut() {
        val store = codexFixtureStore()
        val client = ChatGptAccountClient(store, chatGptHttp {
            store.transaction { root ->
                val account = root.getJSONObject("accounts").getJSONObject(codexFixtureId)
                listOf("access_token", "refresh_token", "id_token").forEach(account::remove)
            }
            200 to codexUsageFixture(100.0).toString()
        })
        client.refreshUsage(codexFixtureId)
        assertFalse(client.accounts().single().isLoggedIn)
        assertTrue(client.usage(codexFixtureId).isEmpty())
        assertNull(client.notice.value)
    }

    @Test fun permanentlyRejectedCodexGrantClearsStaleQuotaAndUpdatesAccountState() {
        val store = codexFixtureStore()
        val client = ChatGptAccountClient(store, chatGptHttp { 400 to """{"error":{"code":"refresh_token_reused"}}""" })
        val account = store.state.getJSONObject("accounts").getJSONObject(codexFixtureId)
        account.put("usage", JSONObject().put("300", ChatGptUsageWindow(300, 100.0, System.currentTimeMillis() + 3600_000, System.currentTimeMillis()).json()))
        assertThrows(ChatGptServiceException::class.java) { client.accessToken(codexFixtureId, "fixture-access") }
        assertFalse(client.accounts().single().isLoggedIn)
        assertTrue(client.usage(codexFixtureId).isEmpty())
        assertEquals(1, client.accountsRevision.value)
        client.checkUsage(codexFixtureId) // An expired grant must not look like an exhausted subscription.
    }

    @Test fun signOutDuringRotationCannotResurrectTheSession() {
        val store = codexFixtureStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = ChatGptAccountClient(store, chatGptHttp { request ->
            when (request.url.toString()) {
                ChatGptCodexProtocol.TOKEN -> {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                    200 to tokens().put("access_token", "rotated-access").put("refresh_token", "rotated-refresh").toString()
                }
                ChatGptProtocol.DISCOVERY -> 200 to JSONObject().put("issuer", ChatGptProtocol.ISSUER)
                    .put("revocation_endpoint", "${ChatGptProtocol.ISSUER}/api/accounts/oauth/revoke").toString()
                else -> {
                    assertEquals("rotated-refresh", (request.body as FormBody).value(0))
                    200 to ""
                }
            }
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rotation = executor.submit<String> { client.accessToken(codexFixtureId, "fixture-access") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val logout = executor.submit<Boolean> { client.signOut(codexFixtureId) }
            release.countDown()
            rotation.get(3, TimeUnit.SECONDS)
            assertTrue(logout.get(3, TimeUnit.SECONDS))
            assertFalse(client.accounts().single().isLoggedIn)
            assertThrows(ChatGptServiceException::class.java) { client.accessToken(codexFixtureId) }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun compatibilityProfilePinsRoutesAndExportsNoCredentials() {
        val profile = ApiProfile("chatgpt:$codexFixtureId", "ChatGPT", "secret", "https://proxy.test", selectedModel = "slug",
            savedModels = listOf("slug"), chatGptAccountId = codexFixtureId)
        val restored = ApiProfileJson.decode(ApiProfileJson.encode(profile), "https://fallback.test", "default")
        assertEquals(ChatGptCodexProtocol.BASE, restored.baseUrl)
        assertEquals(ChatGptCodexProtocol.MODELS, restored.modelsEndpoint)
        assertEquals("${ChatGptCodexProtocol.BASE}/responses", restored.responsesEndpoint)
        assertTrue(restored.apiKey.isEmpty())
        val client = ChatGptAccountClient(codexFixtureStore())
        assertThrows(IllegalArgumentException::class.java) { client.authorize(codexFixtureId, Request.Builder().url("${ChatGptProtocol.RESOURCE}/models").build()) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptAccountClient(chatGptFixtureStore()).authorize("oaiapp_fixture", Request.Builder().url(ChatGptCodexProtocol.USAGE).build()) }
    }

    private fun tokens() = JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
        .put("token_type", "Bearer").put("expires_in", 3600)
}
