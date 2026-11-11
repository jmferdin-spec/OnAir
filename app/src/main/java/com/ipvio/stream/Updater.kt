package com.ipvio.stream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Hintergrund-Aktualisierung der Playlist nach dem eingestellten Plan (täglich, wöchentlich, alle X Stunden). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        General.init(c)
        Config.init(c)
        val now = System.currentTimeMillis()
        if (!SettingsLogic.refreshDue(General.plan(), General.lastRefresh, now, General.zone())) return Result.success()
        val url = PlaylistStore.savedUrl(c) ?: return Result.success()
        // Gerade wird geschaut: nicht jetzt neu laden (große Listen kosten Speicher und Rechenzeit). Der Auftrag läuft stündlich
        // und holt es nach, sobald der Player zu ist – „zuletzt aktualisiert“ bleibt bis dahin unverändert.
        if (AppNav.playing) return Result.success()
        // App offen: wie bisher laden, damit die Oberfläche den neuen Stand zeigt.
        // App geschlossen: nur den Zwischenspeicher erneuern – nichts im Arbeitsspeicher.
        // OnAir: both paths go through the rate-limit gate (at most every 12 h, back-off after errors) and also renew the
        // stored TV guide; "last refreshed" is set there on success.
        if (PlaylistStore.hasData) PlaylistStore.load(c, url, false, ask = false, trigger = RefreshLogic.Trigger.SCHEDULED)
        else PlaylistStore.refreshCacheOnly(c, url)
        return Result.success()
    }
}

object Updater {
    private const val NAME = "playlist_update"

    /** Stellt den Hintergrundauftrag passend zur Einstellung ein. Bei "bei jedem Start" und "nur von Hand" gibt es keinen. */
    fun apply(c: Context) {
        try {
            val wm = WorkManager.getInstance(c.applicationContext)
            if (General.refreshMode in 1..3) {
                val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else wm.cancelUniqueWork(NAME)
        } catch (e: Exception) {
        }
    }
}
