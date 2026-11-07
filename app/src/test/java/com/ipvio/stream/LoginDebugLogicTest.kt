package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDebugLogicTest {
    private val url = "http://example.test:8080/get.php?username=demo&password=s%40cret%21&type=m3u_plus&output=ts"

    @Test fun secretsHaveEncodedAndDecoded() {
        assertEquals(setOf("s%40cret%21", "s@cret!"), LoginDebugLogic.secretsOf(url).toSet())
        assertTrue(LoginDebugLogic.secretsOf("http://h/get.php?username=u").isEmpty())
    }

    @Test fun urlPasswordMasked() {
        val m = LoginDebugLogic.mask(url, LoginDebugLogic.secretsOf(url))
        assertEquals("http://example.test:8080/get.php?username=demo&password=***&type=m3u_plus&output=ts", m)
    }

    @Test fun maskedWithoutSecretsToo() {
        assertEquals("http://h/player_api.php?username=u&password=***", LoginDebugLogic.mask("http://h/player_api.php?username=u&password=abc"))
        assertEquals("http://u:***@h/x", LoginDebugLogic.mask("http://u:abc@h/x"))
        assertEquals("http://h/live/u/***/12.ts", LoginDebugLogic.mask("http://h/live/u/abc/12.ts"))
    }

    @Test fun bodyEchoMasked() {
        val body = """{"user_info":{"username":"demo","password":"s@cret!","status":"Active"}}"""
        val s = LoginDebugLogic.snippet(body, LoginDebugLogic.secretsOf(url))
        assertFalse(s.contains("s@cret!"))
        assertTrue(s.contains("\"password\":\"***\""))
    }

    @Test fun rawPasswordInExceptionMasked() {
        val m = LoginDebugLogic.mask("java.io.IOException: failed for s@cret! at host", LoginDebugLogic.secretsOf(url))
        assertEquals("java.io.IOException: failed for *** at host", m)
    }

    @Test fun snippetIsOneLineAndCapped() {
        val s = LoginDebugLogic.snippet("#EXTM3U\n" + "x".repeat(1000))
        assertFalse(s.contains("\n"))
        assertEquals(LoginDebugLogic.SNIPPET_CHARS, s.length)
        assertTrue(s.startsWith("#EXTM3U\\n"))
    }
}
