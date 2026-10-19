package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.DeepSeekWebConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeepSeekWebClientTest {
    private val config = DeepSeekWebConfig(enabled = true, apiKey = "test-secret")
    private val page = "# Markdown title\n\nReadable content with [a link](https://example.com/next)."
    private val searchResponse = """{"results":[{"title":"Blocked candidate","url":"https://blocked.example/article","snippet":"Short lead","summary":"Long summary for the model","rerank_score":2.4,"authority":3,"site_name":null,"date":null}]}"""

    private fun client(status: Int = 200, body: String, inspect: (Request) -> Unit = {}): DeepSeekWebClient =
        DeepSeekWebClient(OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build())

    @Test fun searchSendsDocumentedRequestAndPreservesProviderRankingAndSummary() = runBlocking {
        val output = client(body = searchResponse) { request ->
            assertEquals("POST", request.method)
            assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
            assertEquals("Bearer test-secret", request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("news site:blocked.example", body.getString("query"))
            assertEquals(50, body.getInt("count"))
            assertFalse(body.getBoolean("need_content"))
        }.search(config, " news site:blocked.example ", 100)
        assertTrue(output.startsWith("WEB_SEARCH_RESULTS schema=lyra_web_search_v2"))
        assertTrue(output.contains("url: https://blocked.example/article"))
        assertTrue(output.contains("summary: Long summary for the model"))
        assertTrue(output.contains("rerank_score: 2.4"))
        assertTrue(output.contains("authority: 3"))
        assertFalse(output.contains("blocked_hosts:"))
        assertFalse(output.contains("site_name: null"))
        assertFalse(output.contains("date: null"))
    }

    @Test fun resultCountAndLimitAreRespected() = runBlocking {
        val one = JSONObject(searchResponse).getJSONArray("results").getJSONObject(0)
        val body = JSONObject().put("results", org.json.JSONArray().put(one).put(one)).toString()
        val output = client(body = body) { request ->
            assertEquals(1, JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getInt("count"))
        }.search(config, "query", 0)
        assertTrue(output.contains("result_1:"))
        assertFalse(output.contains("result_2:"))
    }

    @Test fun emptySearchIsNotAnError() = runBlocking {
        assertTrue(client(body = """{"results":[]}""").search(config, "query", 6).startsWith("WEB_SEARCH_EMPTY:"))
    }

    @Test fun unicodeQueryLimitCountsCodePointsAndDoesNotTruncateOperators() = runBlocking {
        var calls = 0
        val api = client(body = """{"results":[]}""") { calls++ }
        api.search(config, "😀".repeat(128), 1)
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.search(config, "😀".repeat(129), 1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.search(config, " \n ", 1) } }
        assertEquals(1, calls)
    }

    @Test fun fetchSendsOnlyUrlAndPreservesMarkdown() = runBlocking {
        val output = client(body = JSONObject().put("title", "Page title").put("content", page).toString()) { request ->
            assertEquals("POST", request.method)
            assertEquals("/v1/tools/fetch", request.url.encodedPath)
            assertEquals("Bearer test-secret", request.header("Authorization"))
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(1, body.length())
            assertEquals("https://blocked.example/article", body.getString("url"))
        }.readPage(config, " https://blocked.example/article ")
        assertTrue(output.startsWith("WEB_PAGE_READ_RESULT schema=lyra_web_page_v2"))
        assertTrue(output.contains("status: readable"))
        assertTrue(output.contains("extraction: deepseek_fetch"))
        assertTrue(output.endsWith(page))
    }

    @Test fun emptyFetchDoesNotClaimReadableContent() = runBlocking {
        val output = client(body = """{"title":"","content":""}""").readPage(config, "https://example.com")
        assertTrue(output.contains("status: empty"))
    }

    @Test fun invalidUrlsAndMissingKeysDoNotMakeRequests() {
        var calls = 0
        val api = client(body = "{}") { calls++ }
        for (url in listOf("file:///private", "ftp://example.com", "https:///", "https://user:pass@example.com", "https://example.com/" + "a".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.readPage(config, url) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.search(config.copy(apiKey = ""), "query", 6) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.readPage(config.copy(apiKey = ""), "https://example.com") } }
        assertEquals(0, calls)
    }

    @Test fun httpErrorsAreSurfacedOnceWithoutLeakingKeysOrFallingBack() {
        for (status in listOf(400, 401, 402, 403, 404, 408, 429, 500, 504)) {
            var calls = 0
            val api = client(status, """{"error":{"message":"Rejected test-secret","type":"invalid_request_error"}}""") { calls++ }
            val error = assertThrows(IOException::class.java) { runBlocking { api.search(config, "query", 6) } }
            assertTrue(error.message!!.contains("HTTP $status"))
            assertTrue(error.message!!.contains("[redacted]"))
            assertFalse(error.message!!.contains(config.apiKey))
            assertEquals(1, calls)
        }
    }

    @Test fun malformedSuccessResponseIsSurfaced() {
        for (body in listOf("<html>bad gateway</html>", "{}", "{\"results\":null}")) {
            assertThrows(Exception::class.java) { runBlocking { client(body = body).search(config, "query", 6) } }
        }
    }

    @Test fun cancellationCancelsTheUnderlyingHttpCall() = runBlocking {
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            while (!chain.call().isCanceled()) Thread.sleep(10)
            cancelled.countDown()
            throw IOException("cancelled")
        }.build()
        try {
            withTimeout(500) { DeepSeekWebClient(http).search(config, "query", 6) }
            fail("Expected cancellation")
        } catch (_: TimeoutCancellationException) {
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        }
    }
}
