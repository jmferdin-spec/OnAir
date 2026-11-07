package com.ipvio.stream

import java.net.URLDecoder

/**
 * OnAir: pure helpers for login debug logging. Everything that goes to logcat passes through [mask] first,
 * so the Xtream password never appears in the log (neither in the URL, nor echoed back in a response body,
 * nor inside an exception message).
 */
object LoginDebugLogic {
    const val MASK = "***"
    const val SNIPPET_CHARS = 300

    private val QUERY_PASS = Regex("""([?&](?:password|pass|pwd)=)[^&#\s]*""", RegexOption.IGNORE_CASE)
    private val JSON_PASS = Regex(""""(password|pass)"\s*:\s*"(?:[^"\\]|\\.)*"""", RegexOption.IGNORE_CASE)
    private val USERINFO = Regex("""(https?://[^/:@\s]+:)[^/@\s]+@""", RegexOption.IGNORE_CASE)
    // Xtream stream paths: /live|movie|series|timeshift/<user>/<pass>/...
    private val PATH_PASS = Regex("""(/(?:live|movie|series|timeshift)/[^/\s]+/)[^/\s]+/""", RegexOption.IGNORE_CASE)

    /** Raw and URL-encoded forms of the password found in [url] (empty if none). */
    fun secretsOf(url: String): List<String> {
        val m = Regex("""[?&]password=([^&#\s]*)""", RegexOption.IGNORE_CASE).find(url) ?: return emptyList()
        val enc = m.groupValues[1]
        if (enc.isEmpty()) return emptyList()
        val dec = try { URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { enc }
        return listOf(enc, dec).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    }

    /** [text] with every known form of the password replaced by [MASK]. */
    fun mask(text: String, secrets: List<String> = emptyList()): String {
        var s = text
        for (p in secrets) if (p.length >= 1) s = s.replace(p, MASK)
        s = QUERY_PASS.replace(s) { it.groupValues[1] + MASK }
        s = JSON_PASS.replace(s) { "\"" + it.groupValues[1] + "\":\"" + MASK + "\"" }
        s = USERINFO.replace(s) { it.groupValues[1] + MASK + "@" }
        s = PATH_PASS.replace(s) { it.groupValues[1] + MASK + "/" }
        return s
    }

    /** First [SNIPPET_CHARS] characters of a response body, on one line, masked. */
    fun snippet(body: String, secrets: List<String> = emptyList()): String {
        val one = body.take(SNIPPET_CHARS * 2).replace("\r", "\\r").replace("\n", "\\n")
        return mask(one, secrets).take(SNIPPET_CHARS)
    }
}
