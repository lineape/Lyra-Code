package com.yukisoffd.lyracode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class ChatGptLoopbackSignInTest {
    @Test fun loopbackIsAlreadyBoundIgnoresUnrelatedRequestsAndConsumesOneVerifiedCallback() = runBlocking {
        withTimeout(10_000) {
            ChatGptLoopbackSignIn().use { listener ->
                val redirect = listener.attempt.redirectUri.toHttpUrl()
                val waiting = async(Dispatchers.IO) { listener.awaitCallback() }
                suspend fun send(url: HttpUrl, host: String = "127.0.0.1:${url.port}"): String = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", url.port).use { socket ->
                        socket.soTimeout = 3000
                        val target = url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()
                        socket.getOutputStream().apply { write("GET $target HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()); flush() }
                        socket.getInputStream().bufferedReader().readText()
                    }
                }
                assertTrue(send(redirect.newBuilder().encodedPath("/favicon.ico").build()).startsWith("HTTP/1.1 404"))
                assertTrue(send(redirect, "attacker.test").startsWith("HTTP/1.1 404"))
                val callback = redirect.newBuilder().addQueryParameter("state", listener.attempt.state)
                    .addQueryParameter("code", "fixture-code").addQueryParameter("client_id", "oaiapp_fixture").build()
                val response = send(callback)
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("Cache-Control: no-store"))
                assertFalse(response.contains("fixture-code"))
                assertFalse(response.contains(listener.attempt.state))
                assertEquals(callback, waiting.await())
                assertEquals("oaiapp_fixture", listener.attempt.issuedClientId)
                assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", redirect.port).close() }
            }
        }
        Unit
    }
}
