package com.ipvio.stream

import android.util.Log

/**
 * OnAir: logcat output for provider requests (login, catalog, guide, Events refresh). Filter with:
 *   adb logcat -s OnAirLogin
 * Every line is masked by [LoginDebugLogic] – the password is never written.
 *
 * - [error]: always (all builds), so failures can be diagnosed on the TV.
 * - [info]: short status lines ("guide import: … programmes", "events refresh: …"), debug builds only.
 * - [request] / [response] (URL, HTTP status, first 300 characters of the answer): the temporary login debugging from
 *   v1 development – off unless [VERBOSE] is switched on for a debug build.
 */
object LoginDebug {
    const val TAG = "OnAirLogin"
    /** Request / response tracing; set to true (debug builds only) when a login or catalog problem needs looking at. */
    private const val VERBOSE = false
    private val verbose get() = VERBOSE && BuildConfig.DEBUG

    fun request(url: String) {
        if (verbose) Log.d(TAG, "GET " + LoginDebugLogic.mask(url, LoginDebugLogic.secretsOf(url)))
    }

    /** Plain status line; must never contain credentials. */
    fun info(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, LoginDebugLogic.mask(msg))
    }

    /** [body] is only read (peeked) when tracing is on. */
    inline fun response(url: String, code: Int, message: String, body: () -> String) {
        if (tracing()) logResponse(url, code, message, body())
    }

    fun tracing(): Boolean = verbose

    fun logResponse(url: String, code: Int, message: String, body: String) {
        val s = LoginDebugLogic.secretsOf(url)
        Log.d(TAG, "HTTP $code ${LoginDebugLogic.mask(message, s)}")
        Log.d(TAG, "Body[0..${LoginDebugLogic.SNIPPET_CHARS}]: " + LoginDebugLogic.snippet(body, s))
    }

    fun error(url: String, where: String, e: Throwable) {
        val s = LoginDebugLogic.secretsOf(url)
        // Own stack-trace string, masked, instead of Log.e(tag, msg, e): exception messages can contain the URL
        Log.e(TAG, "$where failed: " + LoginDebugLogic.mask(Log.getStackTraceString(e).ifEmpty { e.toString() }, s))
    }
}
