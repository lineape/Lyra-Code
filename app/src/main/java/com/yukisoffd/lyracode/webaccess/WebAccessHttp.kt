package com.yukisoffd.lyracode.webaccess

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

internal class WebHttpError(val status: Int, message: String) : Exception(message)

internal data class WebRequest(val method: String, val path: String, val headers: Map<String, String>, val length: Int, val query: Map<String, String> = emptyMap()) {
    fun authenticated(token: String): Boolean = MessageDigest.isEqual(
        headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray(),
    )
}

/** One bounded request per connection; no chunked encoding or ambiguous duplicate headers. */
internal object WebAccessHttp {
    const val MAX_UPLOAD = 8 * 1024 * 1024
    const val MAX_JSON = 1024 * 1024

    fun readRequest(input: BufferedInputStream): WebRequest {
        var total = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) throw WebHttpError(400, "Incomplete request")
                if (++total > 16_384) throw WebHttpError(431, "Headers too large")
                if (value == 10) break
                bytes.write(value)
            }
            return bytes.toString("ISO-8859-1").removeSuffix("\r")
        }
        val parts = line().split(' ')
        if (parts.size != 3 || parts[2] !in listOf("HTTP/1.1", "HTTP/1.0") || !parts[1].startsWith('/')) {
            throw WebHttpError(400, "Invalid request")
        }
        val headers = linkedMapOf<String, String>()
        while (true) {
            val row = line()
            if (row.isEmpty()) break
            val colon = row.indexOf(':')
            if (colon <= 0) throw WebHttpError(400, "Invalid header")
            val name = row.substring(0, colon).lowercase(Locale.ROOT)
            if (headers.put(name, row.substring(colon + 1).trim()) != null) throw WebHttpError(400, "Duplicate header")
        }
        if (headers.containsKey("transfer-encoding")) throw WebHttpError(400, "Use Content-Length")
        val length = headers["content-length"]?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw WebHttpError(400, "Invalid Content-Length")
        } ?: 0
        if (length > MAX_UPLOAD) throw WebHttpError(413, "File exceeds 8 MB")
        val query = parts[1].substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        return WebRequest(parts[0], parts[1].substringBefore('?'), headers, length, query)
    }

    fun readBody(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw WebHttpError(400, "Incomplete body")
            offset += count
        }
        return bytes
    }

    private const val SECURITY_HEADERS = "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n" +
        "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob: https: http:; media-src 'self' data: blob: https: http:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n"

    fun eventHeaders(output: OutputStream) {
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\nX-Accel-Buffering: no\r\n" + SECURITY_HEADERS + "\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun mediaRange(header: String?, size: Long): LongRange {
        if (header == null) return 0L until size
        val parts = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header)?.groupValues ?: throw WebHttpError(416,"Invalid range")
        val start = if (parts[1].isEmpty()) (size - (parts[2].toLongOrNull() ?: throw WebHttpError(416,"Invalid range"))).coerceAtLeast(0) else parts[1].toLongOrNull() ?: throw WebHttpError(416,"Invalid range")
        val end = if (parts[1].isEmpty() || parts[2].isEmpty()) size - 1 else (parts[2].toLongOrNull() ?: throw WebHttpError(416,"Invalid range")).coerceAtMost(size-1)
        if (size <= 0 || start >= size || end < start) throw WebHttpError(416,"Invalid range")
        return start..end
    }

    fun media(output: OutputStream, resource: WebAccessMedia.Resource, rangeHeader: String?, head: Boolean) {
        val range = mediaRange(rangeHeader,resource.length)
        val length = if (resource.length == 0L) 0L else range.last-range.first+1
        val status = if (rangeHeader == null) "200 OK" else "206 Partial Content"
        resource.input().use { input ->
            var skip = range.first
            while (skip > 0) { val n=input.skip(skip); if (n>0) skip-=n else { if(input.read()<0) error("Media changed"); skip-- } }
            val extra = if (rangeHeader == null) "" else "Content-Range: bytes ${range.first}-${range.last}/${resource.length}\r\n"
            val disposition = if(resource.mime=="application/octet-stream") "Content-Disposition: attachment; filename*=UTF-8''${java.net.URLEncoder.encode(resource.name,"UTF-8").replace("+","%20")}\r\n" else ""
            output.write(("HTTP/1.1 $status\r\nContent-Type: ${resource.mime}\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n$disposition${extra}Connection: close\r\n" + SECURITY_HEADERS + "\r\n").toByteArray(Charsets.US_ASCII))
            if (!head) { val buffer=ByteArray(32*1024); var remaining=length
                while(remaining>0) { val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(n<0) error("Media changed"); output.write(buffer,0,n);remaining-=n }
            }
            output.flush()
        }
    }

    fun respond(output: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> "Internal Server Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n" + SECURITY_HEADERS + "\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
