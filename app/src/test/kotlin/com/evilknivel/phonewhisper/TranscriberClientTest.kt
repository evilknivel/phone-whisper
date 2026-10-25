package com.evilknivel.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `http error without json body reports the status`() {
        val r = TranscriberClient.parseResponse(504, "<html>Gateway Timeout</html>")
        assertNull(r.text)
        assertEquals("HTTP 504", r.error)
    }

    @Test fun `http error with api message reports the message`() {
        val r = TranscriberClient.parseResponse(400, """{"error":{"message":"Audio file is too short"}}""")
        assertEquals("Audio file is too short", r.error)
    }
}
