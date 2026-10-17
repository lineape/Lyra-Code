package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderKeyInterceptorTest {
    private val profile = ApiProfile("p", "Provider", "main", "https://example.com/v1", selectedModel = "model",
        savedModels = listOf("model"), secondaryApiKeys = listOf("backup-1", "main", "", "backup-2"))

    @Test fun rejectedKeysFallBackInOrderWithTheSameRequestBody() {
        for (header in listOf("Authorization", "x-api-key", "x-goog-api-key")) {
          for (rejectedStatus in listOf(401, 402, 403, 429)) {
            val seen = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(ProviderKeyInterceptor()).addInterceptor { chain ->
                val value = chain.request().header(header)!!
                seen += value
                assertEquals("POST", chain.request().method)
                assertEquals("request-body", Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test")
                    .code(if (value.contains("backup-2")) 200 else rejectedStatus)
                    .body("response".toResponseBody()).build()
            }.build()
            val prefix = if (header == "Authorization") "Bearer " else ""
            val request = Request.Builder().url(profile.chatEndpoint).post("request-body".toRequestBody())
                .header(header, prefix + "main").build().withProviderKeys(profile)
            client.newCall(request).execute().use { assertEquals(200, it.code); assertEquals("response", it.body!!.string()) }
            assertEquals(listOf("${prefix}main", "${prefix}backup-1", "${prefix}backup-2"), seen)
          }
        }
    }

    @Test fun successAndInputOrServerErrorsDoNotRotateKeys() {
        for (status in listOf(200, 400, 404, 408, 500)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor(ProviderKeyInterceptor()).addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test")
                    .code(status).body("body".toResponseBody()).build()
            }.build()
            client.newCall(Request.Builder().url(profile.modelsEndpoint).header("Authorization", "Bearer main")
                .build().withProviderKeys(profile)).execute().close()
            assertEquals(1, calls)
        }
    }

    @Test fun manuallyOverriddenCredentialsArePreserved() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(ProviderKeyInterceptor()).addInterceptor { chain ->
            calls++
            assertEquals("Bearer manual-override", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test")
                .code(401).body("body".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url(profile.modelsEndpoint).header("Authorization", "Bearer manual-override")
            .build().withProviderKeys(profile)).execute().close()
        assertEquals(1, calls)
    }

    @Test fun templatedCustomHeadersFollowTheSelectedKey() {
        val customProfile = profile.copy(modelRequestOverrides = mapOf("model" to ModelRequestCustomization(headers = mapOf("x-custom" to "key={{apiKey}}"))))
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(ProviderKeyInterceptor()).addInterceptor { chain ->
            seen += chain.request().header("x-custom")!!
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test")
                .code(if (seen.size == 1) 402 else 200).body("body".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url(profile.modelsEndpoint)
            .header("x-custom", "key=main").build().withProviderKeys(customProfile)).execute().close()
        assertEquals(listOf("key=main", "key=backup-1"), seen)
    }
}
