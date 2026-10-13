package com.yukisoffd.lyracode

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MediaDataUrlTest {
    @Test fun `decodes the percent encoded SVG from the reported image`() {
        val url = "data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='160'%20height='90'%3E%3Crect%20width='160'%20height='90'%20rx='12'%20fill='%232563eb'/%3E%3Ccircle%20cx='52'%20cy='45'%20r='22'%20fill='%23facc15'/%3E%3Crect%20x='92'%20y='30'%20width='48'%20height='30'%20rx='6'%20fill='%23ffffff'/%3E%3C/svg%3E"
        val decoded = decodeDataUrl(url)!!
        assertEquals(SVG_MIME_TYPE, decoded.mimeType)
        assertTrue(decoded.bytes.toString(Charsets.UTF_8).contains("fill='#2563eb'"))
        assertTrue(decoded.bytes.toString(Charsets.UTF_8).endsWith("</svg>"))
    }

    @Test fun `decodes charset parameters utf8 and literal plus signs`() {
        val decoded = decodeDataUrl("DATA:IMAGE/SVG+XML;charset=utf-8,%3Csvg%3E%E4%B8%AD%E6%96%87+%26%3C/svg%3E")!!
        assertEquals(SVG_MIME_TYPE, decoded.mimeType)
        assertEquals("<svg>中文+&</svg>", decoded.bytes.toString(Charsets.UTF_8))
        assertEquals("<svg>中文+</svg>", decodeDataUrl("data:image/svg+xml,<svg>中文+</svg>")!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun `preserves base64 image and binary payloads with metadata and line breaks`() {
        val bytes = byteArrayOf(0, 127, -128, -1)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, decodeDataUrl("data:image/png;base64,$base64")!!.bytes)
        assertArrayEquals(bytes, decodeDataUrl("data:video/mp4;charset=utf-8;BASE64,\n$base64\n")!!.bytes)
        assertArrayEquals(bytes, decodeDataUrl("data:application/octet-stream,%00%7f%80%ff")!!.bytes)
    }

    @Test fun `invalid escapes and base64 fail without throwing`() {
        listOf("data:image/svg+xml", "data:image/svg+xml,%", "data:image/svg+xml,%2", "data:image/svg+xml,%GG", "data:image/png;base64,@@@", "https://example.com/a.svg")
            .forEach { assertNull(it, decodeDataUrl(it)) }
    }
}
