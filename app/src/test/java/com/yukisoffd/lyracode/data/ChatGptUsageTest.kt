package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptUsageTest {
    @Test fun catalogRefreshSelectsAnEnabledModelWhenThePreviousDefaultIsRemoved() {
        val original = ApiProfile("chatgpt:fixture", "ChatGPT", "", ChatGptProtocol.RESOURCE, selectedModel = "retired",
            savedModels = listOf("retired", "enabled"), enabledModels = listOf("retired", "enabled"), chatGptAccountId = "fixture")
        val refreshed = original.withChatGptModels(listOf("first", "enabled"), mapOf("enabled" to "Enabled display name"))
        assertEquals(listOf("first", "enabled"), refreshed.savedModels)
        assertEquals(listOf("enabled"), refreshed.enabledModels)
        assertEquals("enabled", refreshed.selectedModel)
        assertEquals("Enabled display name", refreshed.modelDisplayName("enabled"))
        assertEquals("first", original.copy(enabledModels = listOf("retired")).withChatGptModels(listOf("first", "enabled"), emptyMap()).selectedModel)
        assertEquals("", original.withChatGptModels(emptyList(), emptyMap()).selectedModel)
    }

    @Test fun missingOrInvalidUsageIsUnknownAndNeverInventedFromTokenCounters() {
        for (headers in listOf(emptyMap(), mapOf("x-ratelimit-remaining-tokens" to "0"),
            headers("NaN"), headers("101"), headers("-1"), headers("99").plus("x-codex-primary-window-minutes" to "60"))) {
            assertTrue(chatGptUsageHeaders(headers, System.currentTimeMillis()).isEmpty())
        }
    }

    @Test fun lowAllowanceUpdatesInlineDataWithoutNoticesAndExhaustionStillStopsRequests() {
        val store = chatGptFixtureStore()
        val client = ChatGptAccountClient(store)
        client.observeUsage("oaiapp_fixture", headers("90"))
        assertNull(client.notice.value)
        client.observeUsage("oaiapp_fixture", headers("90.1"))
        assertNull(client.notice.value)
        assertEquals(9.9, client.usage("oaiapp_fixture").single().remaining()!!, 0.001)
        assertEquals(2, client.usageRevision.value)
        client.observeUsage("oaiapp_fixture", headers("95"))
        assertNull(client.notice.value)
        client.observeUsage("oaiapp_fixture", headers("100"))
        assertNotNull(client.notice.value)
        assertThrows(ChatGptUsageLimitException::class.java) { client.checkUsage("oaiapp_fixture") }
        assertTrue(client.accounts().single().isLoggedIn)
        assertEquals("fixture-access", client.accessToken("oaiapp_fixture"))

        client.dismissNotice()
        client.observeUsage("oaiapp_fixture", headers("93", slot = "secondary", minutes = 10080))
        assertNull(client.notice.value)
        client.observeUsage("oaiapp_fixture", headers("100", slot = "secondary", minutes = 10080))
        assertEquals(10080, client.notice.value!!.windowMinutes)
    }

    @Test fun composerShowsFiveHourAllowanceOrWeeklyWhenTheFiveHourWindowDoesNotApply() {
        val now = System.currentTimeMillis()
        val five = ChatGptUsageWindow(300, 94.5, now + 60_000, now)
        val weekly = ChatGptUsageWindow(10080, 20.0, now + 600_000, now)
        val account = ChatGptAccount(chatGptFixtureStore().state.getJSONObject("accounts").getJSONObject("oaiapp_fixture"))
        assertEquals(ChatGptComposerAllowance(300, 5.5), chatGptComposerAllowance(account, listOf(weekly, five), now))
        // A missing or expired percentage does not establish that the five-hour limit is absent.
        assertEquals(ChatGptComposerAllowance(300, null), chatGptComposerAllowance(account, listOf(weekly), now))
        assertEquals(ChatGptComposerAllowance(300, null), chatGptComposerAllowance(account, listOf(weekly, five.copy(observedAt = now - 300_001)), now))
        val pro = ChatGptAccount(account.json().put("plan", "Pro"))
        assertEquals(ChatGptComposerAllowance(10080, 80.0), chatGptComposerAllowance(pro, listOf(weekly), now))
        assertEquals(ChatGptComposerAllowance(300, 5.5), chatGptComposerAllowance(pro, listOf(weekly, five), now))
        val codex = ChatGptAccount(codexFixtureStore().state.getJSONObject("accounts").getJSONObject(codexFixtureId).put("usage_checked_at", now))
        assertEquals(ChatGptComposerAllowance(10080, 80.0), chatGptComposerAllowance(codex, listOf(weekly), now))
        assertEquals(ChatGptComposerAllowance(10080, null), chatGptComposerAllowance(pro, listOf(weekly.copy(resetsAt = now - 1)), now))
        assertEquals(ChatGptComposerAllowance(300, null), chatGptComposerAllowance(null, listOf(five), now))
        val signedOut = ChatGptAccount(pro.json().apply { remove("id_token") })
        assertEquals(ChatGptComposerAllowance(300, null), chatGptComposerAllowance(signedOut, listOf(five, weekly), now))
    }

    @Test fun staleOrResetDataBecomesUnknownAndDoesNotBlockNewRequests() {
        val now = System.currentTimeMillis()
        val stale = ChatGptUsageWindow(300, 100.0, now + 1_000_000, now - 300_001)
        val reset = ChatGptUsageWindow(300, 100.0, now - 1, now - 1000)
        assertNull(stale.remaining(now))
        assertNull(reset.remaining(now))
        val store = chatGptFixtureStore()
        store.state.getJSONObject("accounts").getJSONObject("oaiapp_fixture")
            .put("usage", JSONObject().put("300", stale.json()).put("10080", reset.json()))
        ChatGptAccountClient(store).checkUsage("oaiapp_fixture")
    }

    @Test fun profileBackupNeverContainsOAuthCredentialsAndNormalizesLegacyFlags() {
        val profile = ApiProfile("chatgpt:oaiapp_fixture", "ChatGPT", "must-not-export", "https://proxy.test/v1", selectedModel = "slug",
            savedModels = listOf("slug"), chatGptAccountId = "oaiapp_fixture", secondaryApiKeys = listOf("second-secret"),
            modelRequestOverrides = mapOf("slug" to ModelRequestCustomization(body = """{"store":true}""")), modelDisplayNames = mapOf("slug" to "Display name"))
        val encoded = ApiProfileJson.encode(profile, includeSecrets = true)
        assertFalse(encoded.toString().contains("must-not-export"))
        assertFalse(encoded.toString().contains("second-secret"))
        val restored = ApiProfileJson.decode(encoded, "https://fallback.test/v1", "fallback")
        assertTrue(restored.isChatGpt)
        assertTrue(restored.usesResponsesApi)
        assertTrue(restored.modelRequestOverrides.isEmpty())
        assertEquals(ChatGptProtocol.RESOURCE, restored.baseUrl)
        assertEquals("https://api.openai.com/v1/responses", restored.responsesEndpoint)
        assertEquals("Display name", restored.modelDisplayName("slug"))
    }

    private fun headers(used: String, slot: String = "primary", minutes: Int = 300) = mapOf(
        "x-codex-$slot-used-percent" to used,
        "x-codex-$slot-window-minutes" to minutes.toString(),
        "x-codex-$slot-reset-at" to (System.currentTimeMillis() / 1000 + 3600).toString(),
    )
}
