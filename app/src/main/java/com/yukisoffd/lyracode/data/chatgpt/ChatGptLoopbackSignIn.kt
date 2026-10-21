package com.yukisoffd.lyracode.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Bind before opening the system browser. The callback is local, bounded and consumed once. */
internal class ChatGptLoopbackSignIn(clientId: String = "", codex: Boolean = false) : Closeable {
    private val server = try {
        ServerSocket(if (codex) ChatGptCodexProtocol.CALLBACK_PORT else 0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    } catch (error: java.net.BindException) {
        throw ChatGptServiceException("callback_port_in_use_1455")
    }
    val attempt = ChatGptSignInAttempt("http://127.0.0.1:${server.localPort}/auth/callback", clientId, codex = codex)

    suspend fun awaitCallback(): HttpUrl {
        try {
            while (System.currentTimeMillis() < attempt.expiresAt) {
                currentCoroutineContext().ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    it.soTimeout = 3000
                    try {
                        val input = it.getInputStream()
                        val bytes = ArrayList<Byte>()
                        while (bytes.size < 16_384) {
                            val byte = input.read()
                            if (byte < 0) break
                            bytes.add(byte.toByte())
                            if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                        }
                        val request = String(bytes.toByteArray(), Charsets.US_ASCII)
                        val first = request.substringBefore("\r\n").split(' ')
                        val url = if (first.size == 3 && first[0] == "GET" && first[1].startsWith('/'))
                            ("http://127.0.0.1:${server.localPort}" + first[1]).toHttpUrlOrNull() else null
                        val host = request.lineSequence().firstOrNull { line -> line.startsWith("Host:", true) }
                            ?.substringAfter(':')?.trim()
                        val valid = url != null && url.encodedPath == "/auth/callback" && host == "127.0.0.1:${server.localPort}"
                        val html = if (valid) "<!doctype html><meta name=viewport content='width=device-width'><title>Lyra Code</title><p>ChatGPT authorization received. Return to Lyra Code to view the result.</p><p>已收到授权回调，请返回 Lyra Code 查看登录结果。</p>" else "Not found"
                        val body = html.toByteArray(Charsets.UTF_8)
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 ${if (valid) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n" +
                                "Referrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                        if (valid) {
                            ChatGptProtocol.callback(attempt, url!!, System.currentTimeMillis())
                            return url
                        }
                    } catch (_: SocketTimeoutException) { /* Ignore incomplete local requests until the attempt expires. */ }
                }
            }
            error("ChatGPT sign-in timed out. Please try again.")
        } finally { close() }
    }

    override fun close() { server.close() }
    override fun toString() = "ChatGptLoopbackSignIn([redacted])"
}
