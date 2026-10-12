package com.yukisoffd.lyracode.webaccess

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class WebAccessHttpTest {
    @Test fun mediaRangesSupportSeekAndRejectInvalidRequests() {
        assertEquals(0L..9L,WebAccessHttp.mediaRange(null,10))
        assertEquals(3L..9L,WebAccessHttp.mediaRange("bytes=3-",10))
        assertEquals(2L..4L,WebAccessHttp.mediaRange("bytes=2-4",10))
        assertEquals(7L..9L,WebAccessHttp.mediaRange("bytes=-3",10))
        for (value in listOf("bytes=10-","bytes=5-2","bytes=-0","bytes=0-1,4-5","bytes=999999999999999999999-")) {
            try { WebAccessHttp.mediaRange(value,10);fail(value) } catch(e:WebHttpError){assertEquals(416,e.status)}
        }
        val parsed=request("GET /api/media?id=folder%2Fimage%20one HTTP/1.1\r\n\r\n")
        assertEquals("/api/media",parsed.path);assertEquals("folder/image one",parsed.query["id"])
    }
    private fun request(raw: String) = WebAccessHttp.readRequest(raw.byteInputStream().buffered())
    private fun rejects(status: Int, raw: String) {
        try { request(raw); fail("Expected HTTP $status") } catch (e: WebHttpError) { assertEquals(status, e.status) }
    }
    @Test fun authorizationIsMandatoryAndExact() {
        assertFalse(request("GET /api/state HTTP/1.1\r\nHost: phone\r\n\r\n").authenticated("secret"))
        assertTrue(request("GET /api/state HTTP/1.1\r\nAuthorization: Bearer secret\r\n\r\n").authenticated("secret"))
        assertFalse(request("GET /api/state HTTP/1.1\r\nAuthorization: Bearer secret-extra\r\n\r\n").authenticated("secret"))
    }
    @Test fun rejectsAmbiguousFramingAndOversizedBodies() {
        rejects(400,"POST /api/upload HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 8\r\n\r\n")
        rejects(400,"POST /api/upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n")
        rejects(400,"POST /api/upload HTTP/1.1\r\nContent-Length: -1\r\n\r\n")
        rejects(400,"POST /api/upload HTTP/1.1\r\nContent-Length: 999999999999999\r\n\r\n")
        rejects(413,"POST /api/upload HTTP/1.1\r\nContent-Length: ${WebAccessHttp.MAX_UPLOAD+1}\r\n\r\n")
        rejects(431,"GET / HTTP/1.1\r\nX-Large: ${"a".repeat(17000)}\r\n\r\n")
        rejects(400,"GET http://other/api/state HTTP/1.1\r\n\r\n")
    }
    @Test fun headerReaderDoesNotConsumeUploadBytes() {
        val input = "POST /api/upload HTTP/1.1\r\nContent-Length: 4\r\n\r\nfile".byteInputStream().buffered()
        val header = WebAccessHttp.readRequest(input)
        assertEquals("file", WebAccessHttp.readBody(input,header.length).toString(Charsets.UTF_8))
        try { WebAccessHttp.readBody("a".byteInputStream(),2); fail() } catch (e: WebHttpError) { assertEquals(400,e.status) }
    }
    @Test fun responsesUseByteLengthsAndDisableCachingAndFraming() {
        val body = "手机附件".toByteArray()
        val output = ByteArrayOutputStream()
        WebAccessHttp.respond(output,200,"text/plain; charset=utf-8",body)
        val response = output.toByteArray().toString(Charsets.UTF_8)
        assertTrue(response.contains("Content-Length: ${body.size}\r\n"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("frame-ancestors 'none'"))
        assertTrue(response.contains("X-Content-Type-Options: nosniff"))
        assertTrue(response.endsWith("手机附件"))
    }
}
