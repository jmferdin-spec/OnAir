package com.ipvio.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Eine vom Handy gesendete Playlist: entweder ein M3U-Link oder Xtream-Zugangsdaten. */
class PhoneInput(val url: String, val host: String, val user: String, val pass: String) {
    val isXtream get() = url.isBlank()
}

/** Reine Hilfsfunktionen (testbar, ohne Android). */
object PhoneImportLogic {
    /** Zerlegt "a=1&b=x%20y" in eine Map. */
    fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    /** Prüft Eingaben; null = ungültig. */
    fun toInput(f: Map<String, String>): PhoneInput? {
        val url = f["url"].orEmpty().trim()
        if (url.isNotEmpty()) return if (url.startsWith("http://") || url.startsWith("https://")) PhoneInput(url, "", "", "") else null
        val host = f["host"].orEmpty().trim()
        val user = f["user"].orEmpty().trim()
        val pass = f["pass"].orEmpty().trim()
        return if (host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) PhoneInput("", host, user, pass) else null
    }

    fun newCode(rnd: java.util.Random): String = (1000 + rnd.nextInt(9000)).toString()

    /** Sprache der Handy-Seite aus „Accept-Language“: erste der App-Sprachen (de, en, tr, bs, hr, sr, es, fr, it), sonst Englisch. */
    fun langIndex(accept: String): Int {
        for (part in accept.split(',')) {
            val i = I18nCore.CODES.indexOf(part.substringBefore(';').trim().substringBefore('-').lowercase())
            if (i >= 0) return i
        }
        return 1
    }

    /** Deutscher Text, sonst Übersetzung aus den Wörterbüchern der App; fehlt sie, der englische Text. */
    private fun tr(li: Int, de: String, en: String): String = when (li) {
        0 -> de
        1 -> en
        else -> I18nCore.translate(de, li).takeIf { it != de } ?: en
    }

    fun page(de: Boolean): String = page(if (de) 0 else 1)
    fun message(de: Boolean, kind: String): String = message(if (de) 0 else 1, kind)

    fun page(li: Int): String {
        fun t(a: String, b: String) = tr(li, a, b)
        return """<!doctype html><html lang="${I18nCore.CODES.getOrElse(li) { "en" }}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OnAir</title><style>
body{font-family:system-ui,sans-serif;background:#0B0F19;color:#fff;margin:0;padding:20px;max-width:560px}
h1{font-size:22px}h2{font-size:16px;color:#9aa;margin-top:28px}
input,textarea{width:100%;box-sizing:border-box;padding:14px;margin:6px 0;border-radius:10px;border:1px solid #333;background:#171a26;color:#fff;font-size:16px}
button{width:100%;padding:14px;margin-top:10px;border:0;border-radius:10px;background:#D32F2F;color:#FFFFFF;font-size:17px;font-weight:700}
</style></head><body><h1>OnAir</h1>
<p>${t("Sende deine Playlist an den Fernseher. Den Code siehst du auf dem Bildschirm.", "Send your playlist to the TV. You can see the code on the screen.")}</p>
<form method="post" action="/send"><h2>${t("M3U-Link", "M3U link")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<textarea name="url" rows="3" placeholder="http://…/playlist.m3u"></textarea>
<button type="submit">${t("An den Fernseher senden", "Send to TV")}</button></form>
<form method="post" action="/send"><h2>${t("oder Xtream-Login", "or Xtream login")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<input name="host" placeholder="${t("Server (z. B. http://server.tld:8080)", "Server (e.g. http://server.tld:8080)")}">
<input name="user" placeholder="${t("Benutzername", "Username")}" autocapitalize="off">
<input name="pass" placeholder="${t("Passwort", "Password")}" autocapitalize="off">
<button type="submit">${t("An den Fernseher senden", "Send to TV")}</button></form></body></html>"""
    }

    fun message(li: Int, kind: String): String {
        val txt = when (kind) {
            "ok" -> tr(li, "Gesendet. Schau jetzt auf den Fernseher.", "Sent. Now look at the TV.")
            "code" -> tr(li, "Falscher Code. Bitte zurück und erneut versuchen.", "Wrong code. Go back and try again.")
            "bad" -> tr(li, "Eingabe unvollständig oder ungültig.", "Input incomplete or invalid.")
            else -> tr(li, "Zu viele Fehlversuche. Auf dem Fernseher neu starten.", "Too many attempts. Restart on the TV.")
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:system-ui,sans-serif;background:#0B0F19;color:#fff;padding:24px"><h2>$txt</h2></body></html>"""
    }
}

/**
 * Kleiner Server im lokalen Netz: Das Handy öffnet die angezeigte Adresse und sendet die Playlist,
 * damit auf dem Fernseher nichts getippt werden muss. Läuft nur, solange der Hinzufügen-Bildschirm offen ist.
 * Geschützt durch einen vierstelligen Code und eine Sperre nach 5 Fehlversuchen.
 */
class PhoneImport(private val onReceive: (PhoneInput) -> Unit) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var fails = 0
    val code: String = PhoneImportLogic.newCode(SecureRandom())
    @Volatile var port: Int = 0
        private set

    fun start(): Boolean {
        if (server != null) return true
        val ss = (8765..8775).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return false
        server = ss
        port = ss.localPort
        thread(isDaemon = true, name = "phone-import") {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
        return true
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun readRequest(ins: InputStream): Triple<String, String, String>? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 8192) {
            val b = ins.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val text = head.toString("UTF-8")
        val lines = text.split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val lang = lines.firstOrNull { it.startsWith("Accept-Language:", true) }?.substringAfter(':')?.trim().orEmpty()
        if (len > 8192) return null
        val body = ByteArray(len)
        var n = 0
        while (n < len) { val r = ins.read(body, n, len - n); if (r < 0) break; n += r }
        return Triple(first[0] + " " + first[1].substringBefore('?'), String(body, 0, n, Charsets.UTF_8), lang)
    }

    private fun handle(s: Socket) {
        s.soTimeout = 5000
        val (req, body, lang) = readRequest(s.getInputStream()) ?: return
        val de = PhoneImportLogic.langIndex(lang)
        val (status, html) = when (req) {
            "GET /" -> 200 to PhoneImportLogic.page(de)
            "POST /send" -> {
                val f = PhoneImportLogic.parseForm(body)
                when {
                    fails >= 5 -> 429 to PhoneImportLogic.message(de, "lock")
                    f["code"].orEmpty().trim() != code -> { fails++; 403 to PhoneImportLogic.message(de, "code") }
                    else -> {
                        val input = PhoneImportLogic.toInput(f)
                        if (input == null) 400 to PhoneImportLogic.message(de, "bad")
                        else { onReceive(input); 200 to PhoneImportLogic.message(de, "ok") }
                    }
                }
            }
            else -> 404 to PhoneImportLogic.message(de, "bad")
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = s.getOutputStream()
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        /** Lokale IPv4-Adresse des Geräts im WLAN/LAN (oder null). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

