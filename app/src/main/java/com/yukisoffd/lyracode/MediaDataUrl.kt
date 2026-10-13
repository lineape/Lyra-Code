package com.yukisoffd.lyracode

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

internal data class DecodedDataUrl(val mimeType: String, val bytes: ByteArray)

/** RFC 2397 payloads use URL escapes, not form encoding: a literal '+' stays '+'. */
internal fun decodeDataUrl(dataUrl: String): DecodedDataUrl? = runCatching {
    val source = dataUrl.trim()
    if (!source.startsWith("data:", ignoreCase = true)) return null
    val comma = source.indexOf(',')
    if (comma < 0) return null
    val metadata = source.substring(5, comma).split(';')
    val mimeType = metadata.first().ifBlank { "application/octet-stream" }.lowercase(Locale.ROOT)
    val payload = decodeDataUrlEscapes(source.substring(comma + 1))
    val bytes = if (metadata.drop(1).any { it.equals("base64", ignoreCase = true) }) {
        Base64.getDecoder().decode(payload.toString(Charsets.US_ASCII).filterNot { it.isWhitespace() })
    } else {
        payload
    }
    DecodedDataUrl(mimeType, bytes)
}.getOrNull()

private fun decodeDataUrlEscapes(payload: String): ByteArray {
    val output = ByteArrayOutputStream(payload.length)
    var cursor = 0
    while (cursor < payload.length) {
        if (payload[cursor] == '%') {
            require(cursor + 2 < payload.length)
            val high = payload[cursor + 1].digitToIntOrNull(16) ?: error("Invalid URL escape")
            val low = payload[cursor + 2].digitToIntOrNull(16) ?: error("Invalid URL escape")
            output.write(high * 16 + low)
            cursor += 3
        } else {
            val end = payload.indexOf('%', cursor).let { if (it < 0) payload.length else it }
            output.write(payload.substring(cursor, end).toByteArray(Charsets.UTF_8))
            cursor = end
        }
    }
    return output.toByteArray()
}
