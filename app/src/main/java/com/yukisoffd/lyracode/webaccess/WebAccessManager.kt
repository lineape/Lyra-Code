package com.yukisoffd.lyracode.webaccess

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.yukisoffd.lyracode.Android17Compatibility
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue

internal data class WebAccessState(val running: Boolean = false, val addresses: List<String> = emptyList(), val error: String = "")

/** Shares the Activity's controller. It never creates a second agent or database session. */
internal class WebAccessManager(private val activity: Activity, private val settings: AppSettings, private val bridge: WebAccessBridge) {
    val state = mutableStateOf(WebAccessState())
    @Volatile private var server: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var streams: ThreadPoolExecutor? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val assets = mapOf("/" to "index.html", "/app.js" to "app.js", "/style.css" to "style.css",
        "/marked.js" to "marked.js", "/purify.js" to "purify.js", "/i18n.js" to "i18n.js", "/reasoning.js" to "reasoning.js",
        "/rich-content.js" to "rich-content.js", "/stream.js" to "stream.js",
        "/katex/katex.min.js" to "katex/katex.min.js", "/katex/katex.min.css" to "katex/katex.min.css",
        "/charts/mermaid.min.js" to "charts/mermaid.min.js", "/charts/echarts.min.js" to "charts/echarts.min.js",
        "/charts/circuit.js" to "charts/circuit.js", "/charts/circuit-symbols.js" to "charts/circuit-symbols.js") +
        activity.assets.list("katex/fonts").orEmpty().associate { "/katex/fonts/$it" to "katex/fonts/$it" }
    private val cachedAssets by lazy {
        assets.mapValues { (_, name) -> activity.assets.open(if (name.startsWith("katex/") || name.startsWith("charts/")) name else "web-access/$name").use { it.readBytes() } }
    }

    /** Uses the exact Compose theme, including wallpaper and custom background colors. */
    fun updateTheme(darkMode: Boolean, colors: ColorScheme) {
        val roles = mapOf(
            "background" to colors.background, "on-background" to colors.onBackground,
            "surface" to colors.surface, "surface-low" to colors.surfaceContainerLow,
            "surface-high" to colors.surfaceContainerHigh, "surface-highest" to colors.surfaceContainerHighest,
            "on-surface" to colors.onSurface, "on-surface-variant" to colors.onSurfaceVariant,
            "outline" to colors.outline, "outline-variant" to colors.outlineVariant,
            "primary" to colors.primary, "on-primary" to colors.onPrimary, "secondary" to colors.secondary,
            "error" to colors.error, "error-container" to colors.errorContainer, "on-error-container" to colors.onErrorContainer,
            "inverse-surface" to colors.inverseSurface, "inverse-on-surface" to colors.inverseOnSurface,
        )
        val json = JSONObject()
        roles.forEach { (role, color) -> json.put(role, "#%06x".format(color.toArgb() and 0xFFFFFF)) }
        bridge.theme = JSONObject().put("dark", darkMode).put("colors", json)
            .put("surfaceVariant", "#%06x".format(colors.surfaceVariant.toArgb() and 0xFFFFFF))
    }

    fun start() {
        if (server != null) return
        try {
            require(Android17Compatibility.hasLocalNetworkAccess(activity)) { activity.getString(com.yukisoffd.lyracode.R.string.permission_local_network) }
            val host = settings.webAccessHost
            require(host in listOf("0.0.0.0", "127.0.0.1")) { "Invalid bind address" }
            val socket = ServerSocket().apply {
                reuseAddress = true
                try { bind(InetSocketAddress(InetAddress.getByName(host), settings.webAccessPort), 16) }
                catch (error: Exception) { close(); throw error }
            }
            server = socket
            val token = settings.webAccessToken
            val pool = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8)) { task ->
                Thread(task, "LyraWebClient").apply { isDaemon = true }
            }
            workers = pool
            streams = ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,SynchronousQueue()) { task -> Thread(task,"LyraWebStream").apply { isDaemon=true } }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WebAccessForegroundService.stopServer = { activity.runOnUiThread { stop() } }
            activity.startForegroundService(Intent(activity, WebAccessForegroundService::class.java))
            val addresses = if (host == "127.0.0.1") listOf("http://127.0.0.1:${socket.localPort}") else {
                NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { "http://${it.hostAddress}:${socket.localPort}" }.distinct()
                    .ifEmpty { listOf("http://127.0.0.1:${socket.localPort}") }
            }
            state.value = WebAccessState(running = true, addresses = addresses)
            Thread({
                try {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        clients += client
                        try { pool.execute { serve(client, token) } }
                        catch (_: Exception) { clients -= client; client.close() }
                    }
                } catch (_: SocketException) {
                    // Closing the listener is the normal stop path.
                } catch (error: Exception) {
                    activity.runOnUiThread { stop(); state.value = WebAccessState(error = error.message.orEmpty()) }
                }
            }, "LyraWebAccess").apply { isDaemon = true }.start()
        } catch (error: Exception) {
            stop()
            state.value = WebAccessState(error = error.message.orEmpty())
        }
    }

    fun stop() {
        server?.let { runCatching { it.close() } }; server = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers?.shutdownNow(); workers = null
        streams?.shutdownNow(); streams = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebAccessForegroundService.stopServer = null
        activity.stopService(Intent(activity, WebAccessForegroundService::class.java))
        state.value = WebAccessState()
    }

    private fun serve(socket: Socket, token: String) {
        var handedOff = false
            try {
                socket.soTimeout = 15_000
                val input = socket.getInputStream().buffered()
                val request = WebAccessHttp.readRequest(input)
                if (request.method == "GET" && request.path in assets) {
                    val type = when { request.path.endsWith(".js") -> "text/javascript; charset=utf-8"; request.path.endsWith(".css") -> "text/css; charset=utf-8"; request.path.endsWith(".woff2") -> "font/woff2"; request.path.endsWith(".woff") -> "font/woff"; request.path.endsWith(".ttf") -> "font/ttf"; else -> "text/html; charset=utf-8" }
                    WebAccessHttp.respond(socket.getOutputStream(), 200, type, cachedAssets.getValue(request.path))
                    return
                }
                // Only interface language is public so the connection page can follow the phone too.
                if (request.method == "GET" && request.path == "/api/locale") {
                    val locale = runBlocking { withContext(Dispatchers.Main) { bridge.locale() } }
                    WebAccessHttp.respond(socket.getOutputStream(), 200, "application/json; charset=utf-8", locale.toString().toByteArray())
                    return
                }
                if (request.method in listOf("GET","HEAD") && request.path == "/api/media") {
                    // The unguessable ID grants read access to one referenced file for five minutes.
                    WebAccessHttp.media(socket.getOutputStream(),bridge.media.open(request.query["id"].orEmpty()),request.headers["range"],request.method=="HEAD")
                    return
                }
                if (!request.authenticated(token)) throw WebHttpError(401, "请输入手机端显示的访问码")
                if (request.method == "GET" && request.path == "/api/events") {
                    val pool = streams ?: throw WebHttpError(503,"Server stopped")
                    try { pool.execute { serveEvents(socket) }; handedOff=true }
                    catch (_: java.util.concurrent.RejectedExecutionException) { throw WebHttpError(503,"Too many streaming clients") }
                    return
                }
                val response = when {
                    request.method == "GET" && request.path == "/api/state" -> runBlocking { withContext(Dispatchers.Main) { bridge.snapshot() } }
                    request.method == "POST" && request.path == "/api/action" -> {
                        if (request.length > WebAccessHttp.MAX_JSON) throw WebHttpError(413, "Request too large")
                        val args = JSONObject(WebAccessHttp.readBody(input, request.length).toString(Charsets.UTF_8))
                        runBlocking { bridge.action(args) }
                    }
                    request.method == "POST" && request.path == "/api/upload" -> {
                        val bytes = WebAccessHttp.readBody(input, request.length)
                        runBlocking { bridge.upload(request.headers, bytes) }
                    }
                    else -> throw WebHttpError(404, "Not found")
                }
                WebAccessHttp.respond(socket.getOutputStream(), 200, "application/json; charset=utf-8", response.toString().toByteArray())
            } catch (error: Exception) {
                val status = when (error) { is WebHttpError -> error.status; is IllegalArgumentException, is org.json.JSONException -> 400; else -> 500 }
                val body = JSONObject().put("error", error.message ?: "Request failed").toString().toByteArray()
                runCatching { WebAccessHttp.respond(socket.getOutputStream(), status, "application/json; charset=utf-8", body) }
            } finally { if (!handedOff) { clients -= socket; runCatching { socket.close() } } }
    }

    private fun serveEvents(socket: Socket) {
        socket.use {
            try {
                val output=socket.getOutputStream()
                WebAccessHttp.eventHeaders(output)
                runBlocking {
                    // Backpressure must not discard a message delta that later deltas depend on.
                    val updates=Channel<String>(Channel.RENDEZVOUS)
                    val producer=launch(Dispatchers.Main) {
                        var signature: Int? = null
                        var previous=emptyList<com.yukisoffd.lyracode.ai.ChatRecord>()
                        snapshotFlow {
                            val nextSignature=bridge.configurationSignature()
                            val snapshot=if(signature==nextSignature)bridge.liveSnapshot(previous) else bridge.snapshot()
                            signature=nextSignature;previous=bridge.currentMessages();snapshot.toString()
                        }.collect { updates.send(it) }
                    }
                    try {
                        while (isActive && server != null && !socket.isClosed) {
                            val next=withTimeoutOrNull(10_000) { updates.receive() }
                            val event=if(next==null) ": heartbeat\n\n" else "event: state\ndata: $next\n\n"
                            output.write(event.toByteArray(Charsets.UTF_8));output.flush()
                        }
                    } finally { producer.cancel(); updates.close() }
                }
            } catch (_: Exception) { /* Client disconnect or server shutdown. */ }
            finally { clients -= socket }
        }
    }
}
