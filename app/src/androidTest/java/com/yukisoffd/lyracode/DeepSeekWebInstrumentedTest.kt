package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.DeepSeekWebClient
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.DeepSeekWebConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DeepSeekWebInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var settings: AppSettings
    private lateinit var oldSearch: DeepSeekWebConfig
    private lateinit var oldFetch: DeepSeekWebConfig
    private lateinit var oldBlacklist: String

    @Before fun setUp() {
        settings = AppSettings(context)
        oldSearch = settings.deepSeekSearchConfig
        oldFetch = settings.deepSeekFetchConfig
        oldBlacklist = settings.webSearchBlacklistText
        settings.webSearchBlacklistText = "blocked.example\n*.blocked.example"
        settings.deepSeekSearchConfig = DeepSeekWebConfig(true, apiKey = "search-test-key")
        settings.deepSeekFetchConfig = DeepSeekWebConfig(true, apiKey = "fetch-test-key")
    }

    @After fun tearDown() {
        settings.deepSeekSearchConfig = oldSearch
        settings.deepSeekFetchConfig = oldFetch
        settings.webSearchBlacklistText = oldBlacklist
    }

    private fun agent(status: Int = 200, calls: MutableList<String>): WebViewWebAgent {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            calls += path
            val search = path.endsWith("/search")
            assertEquals("Bearer ${if (search) "search-test-key" else "fetch-test-key"}", chain.request().header("Authorization"))
            val body = when {
                status != 200 -> """{"error":{"message":"Rejected"}}"""
                search -> """{"results":[{"title":"Blocked site","url":"https://blocked.example/page","snippet":"Lead","summary":"Summary"}]}"""
                else -> """{"title":"Blocked site","content":"# Body\n\nMarkdown from a blocked website."}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
        return WebViewWebAgent(context, settings, DeepSeekWebClient(http))
    }

    @Test fun enabledBackendsBypassTheWebsiteBlacklist() = runBlocking {
        val calls = mutableListOf<String>()
        val agent = agent(calls = calls)
        val search = agent.search("query", 6)
        assertTrue(search.contains("url: https://blocked.example/page"))
        assertFalse(search.contains("blocked_hosts:"))
        val page = agent.readPage("https://blocked.example/page")
        assertTrue(page.contains("status: readable"))
        assertTrue(page.contains("# Body"))
        assertFalse(page.contains("blocked_by_user"))
        assertEquals(listOf("/v1/tools/search", "/v1/tools/fetch"), calls)
    }

    @Test fun switchesAreIndependentAndTakeEffectOnTheExistingAgent() = runBlocking {
        val calls = mutableListOf<String>()
        val agent = agent(calls = calls)
        settings.deepSeekFetchConfig = settings.deepSeekFetchConfig.copy(enabled = false)
        assertTrue(agent.search("query").contains("provider: DeepSeek Search"))
        assertTrue(agent.readPage("https://blocked.example/page").contains("blocked_by_user"))
        assertEquals(listOf("/v1/tools/search"), calls)
        settings.deepSeekSearchConfig = settings.deepSeekSearchConfig.copy(enabled = false)
        settings.deepSeekFetchConfig = settings.deepSeekFetchConfig.copy(enabled = true)
        assertTrue(agent.readPage("https://news.blocked.example/page").contains("extraction: deepseek_fetch"))
        assertFalse(settings.deepSeekSearchConfig.enabled)
        assertEquals(listOf("/v1/tools/search", "/v1/tools/fetch"), calls)
        settings.deepSeekFetchConfig = settings.deepSeekFetchConfig.copy(enabled = false)
        assertTrue(agent.readPage("https://news.blocked.example/page").contains("blocked_by_user"))
        assertEquals(2, calls.size)
    }

    @Test fun providerErrorsDoNotFallBackToBuiltInTools() {
        val calls = mutableListOf<String>()
        val agent = agent(status = 401, calls = calls)
        assertThrows(IOException::class.java) { runBlocking { agent.search("query") } }
        assertThrows(IOException::class.java) { runBlocking { agent.readPage("https://blocked.example/page") } }
        assertEquals(listOf("/v1/tools/search", "/v1/tools/fetch"), calls)
    }

    @Test fun settingsPersistAndExportsRespectIncludeSecrets() {
        val reloaded = AppSettings(context)
        assertEquals(settings.deepSeekSearchConfig, reloaded.deepSeekSearchConfig)
        assertEquals(settings.deepSeekFetchConfig, reloaded.deepSeekFetchConfig)
        val publicExport = settings.exportSettingsJson(false)
        assertFalse(publicExport.getJSONObject("deepSeekSearch").has("apiKey"))
        assertFalse(publicExport.getJSONObject("deepSeekFetch").has("apiKey"))
        val privateExport = settings.exportSettingsJson(true)
        assertEquals("search-test-key", privateExport.getJSONObject("deepSeekSearch").getString("apiKey"))
        settings.importSettingsJson(JSONObject()
            .put("deepSeekSearch", publicExport.getJSONObject("deepSeekSearch"))
            .put("deepSeekFetch", publicExport.getJSONObject("deepSeekFetch")), "replace")
        assertEquals("search-test-key", settings.deepSeekSearchConfig.apiKey)
        assertEquals("fetch-test-key", settings.deepSeekFetchConfig.apiKey)
        settings.deepSeekSearchConfig = DeepSeekWebConfig(enabled = true)
        settings.deepSeekFetchConfig = DeepSeekWebConfig(enabled = true, baseUrl = "invalid-url", apiKey = "test-key")
        assertFalse(AppSettings(context).deepSeekSearchConfig.enabled)
        assertFalse(AppSettings(context).deepSeekFetchConfig.enabled)
    }
}
