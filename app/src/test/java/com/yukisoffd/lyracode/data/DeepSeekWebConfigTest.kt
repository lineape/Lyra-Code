package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekWebConfigTest {
    @Test fun defaultsKeepExistingUsersOnBuiltInBackends() {
        val config = DeepSeekWebConfig.fromJson(JSONObject())
        assertFalse(config.enabled)
        assertFalse(config.isConfigured)
        assertTrue(config.copy(apiKey = "test-key").isConfigured)
        assertFalse(config.copy(apiKey = "  ").isConfigured)
        assertFalse(config.copy(apiKey = "test\nkey").isConfigured)
        assertEquals("https://api.deepseek.com/v1/tools/search", config.endpoint("search"))
        assertEquals("", config.apiKey)
    }

    @Test fun acceptsBaseUrlsWithOrWithoutV1AndPreservesProxyPrefix() {
        for (base in listOf("https://api.deepseek.com", "https://api.deepseek.com/", " https://api.deepseek.com/v1/ ")) {
            val config = DeepSeekWebConfig(baseUrl = base)
            assertEquals("https://api.deepseek.com/v1/tools/search", config.endpoint("search"))
            assertEquals("https://api.deepseek.com/v1/tools/fetch", config.endpoint("fetch"))
        }
        assertEquals("https://proxy.example/deepseek/v1/tools/fetch", DeepSeekWebConfig(baseUrl = "https://proxy.example/deepseek/v1").endpoint("fetch"))
    }

    @Test fun rejectsInvalidBaseUrls() {
        for (base in listOf("bad-url", "ftp://example.com", "https://user:pass@example.com", "https://example.com/?key=x", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { DeepSeekWebConfig(baseUrl = base).endpoint("search") }
            assertFalse(DeepSeekWebConfig(baseUrl = base, apiKey = "test-key").isConfigured)
        }
    }

    @Test fun secretsAreExcludedFromPublicExportsAndPreservedWhenAbsentOnImport() {
        val original = DeepSeekWebConfig(true, "https://proxy.example/v1", "secret")
        val publicExport = original.toJson(includeSecrets = false)
        assertFalse(publicExport.toString().contains("secret"))
        assertFalse(publicExport.has("apiKey"))
        assertEquals(original, DeepSeekWebConfig.fromJson(publicExport, "secret"))
        assertEquals(original, DeepSeekWebConfig.fromJson(original.toJson(includeSecrets = true)))
        assertEquals("", DeepSeekWebConfig.fromJson(publicExport.put("apiKey", ""), "secret").apiKey)
    }
}
