package com.ipvio.stream

/**
 * OnAir v2 (Phase 6): the "Update guide" button on the home screen – what the line next to it says (pure, tested in
 * GuideUpdateLogicTest). Texts are German source strings, translated when shown (DictD.kt). Times come in as Unix ms and
 * are formatted by the caller ([fmt]).
 *
 * The update downloads only the guide (xmltv.php) and re-imports it; the rules for when it may run are
 * [RefreshLogic.Trigger.GUIDE] (15 min after the last attempt, failure backoff, provider cooldown).
 */
object GuideUpdateLogic {
    /** What a running update is doing. */
    enum class Phase { DOWNLOADING, IMPORTING }

    /** The button was pressed too soon: why, until when, and when the guide was last updated (0 = never). */
    data class Blocked(val reason: RefreshLogic.Wait, val until: Long, val lastUpdated: Long)

    /**
     * The status line, most important first: running → too soon (right after a press) → last manual update failed →
     * "Guide updated 14:05" → nothing stored yet. [errorAt] / [storedAt] decide whether an error is newer than the guide.
     */
    fun line(phase: Phase?, blocked: Blocked?, lastError: String?, errorAt: Long, storedAt: Long, fmt: (Long) -> String): String = when {
        phase == Phase.DOWNLOADING -> "Programm wird heruntergeladen …"
        phase == Phase.IMPORTING -> "Programm wird eingelesen …"
        blocked != null -> blockedText(blocked, fmt)
        lastError != null && errorAt >= storedAt -> "Aktualisierung fehlgeschlagen: $lastError"
        storedAt > 0 -> "Programm aktualisiert ${fmt(storedAt)}"
        else -> "Noch kein Programm geladen"
    }

    /** Why the button can't run now, and from when it can. */
    fun blockedText(b: Blocked, fmt: (Long) -> String): String = when (b.reason) {
        RefreshLogic.Wait.PROVIDER -> "Der Anbieter bittet zu warten – neuer Versuch ab ${fmt(b.until)}"
        RefreshLogic.Wait.BACKOFF -> "Der letzte Versuch ist fehlgeschlagen – neuer Versuch ab ${fmt(b.until)}"
        else -> if (b.lastUpdated > 0) "Programm wurde ${fmt(b.lastUpdated)} aktualisiert – nächste Aktualisierung ab ${fmt(b.until)}"
                else "Nächste Aktualisierung ab ${fmt(b.until)}"
    }

    /**
     * The button over several guide addresses (provider + custom): it may run if at least one address may be fetched now.
     * [next] = the earliest allowed time per address (null = never by itself). Returns null when allowed, else the address
     * that is free soonest, as (its wait reason, until).
     */
    fun combine(now: Long, next: List<Pair<Long?, RefreshLogic.Wait>>): Pair<RefreshLogic.Wait, Long>? {
        if (next.isEmpty()) return null
        if (next.any { (t, _) -> t != null && t <= now }) return null
        val soonest = next.filter { it.first != null }.minByOrNull { it.first!! } ?: return null
        return soonest.second to soonest.first!!
    }
}
