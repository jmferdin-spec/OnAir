package com.ipvio.stream

import android.util.Log

/**
 * OnAir: logcat output for the playlist / Xtream login request, debug builds only.
 * Filter with: adb logcat -s OnAirLogin
 * Every line is masked by [LoginDebugLogic] – the password is never written.
 */
object LoginDebug {
    const val TAG = "OnAirLogin"
    private val on get() = BuildConfig.DEBUG

    fun request(url: String) {
        if (on) Log.d(TAG, "GET " + LoginDebugLogic.mask(url, LoginDebugLogic.secretsOf(url)))
    }

    /** Plain status line; must never contain credentials. */
    fun info(msg: String) {
        if (on) Log.i(TAG, LoginDebugLogic.mask(msg))
    }

    fun response(url: String, code: Int, message: String, body: String) {
        if (!on) return
        val s = LoginDebugLogic.secretsOf(url)
        Log.d(TAG, "HTTP $code ${LoginDebugLogic.mask(message, s)}")
        Log.d(TAG, "Body[0..${LoginDebugLogic.SNIPPET_CHARS}]: " + LoginDebugLogic.snippet(body, s))
    }

    fun error(url: String, where: String, e: Throwable) {
        if (!on) return
        val s = LoginDebugLogic.secretsOf(url)
        // Own stack-trace string, masked, instead of Log.e(tag, msg, e): exception messages can contain the URL
        Log.e(TAG, "$where failed: " + LoginDebugLogic.mask(Log.getStackTraceString(e).ifEmpty { e.toString() }, s))
    }
}
