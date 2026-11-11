package com.ipvio.stream

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * OnAir Phase 3: the TV guide in framework SQLite (no Room).
 *
 * One table `programme` (tvg_id, start, stop, title, sub_title, descr, categories, episode, title_norm) with a unique index on
 * (tvg_id, start) – also the per-channel lookup – and an index on (start, stop) for "on now". Channel names, logos, groups and
 * category ids stay in the playlist cache and are joined in memory by tvg-id.
 *
 * Imports ([replaceAll]) fill a staging table inside one transaction and swap it in at the end, so readers always see either
 * the old or the new guide, never half of one. WAL lets the screens keep reading while an import runs. Never call from the
 * main thread. Rules (window, episode numbers, signature) in [GuideLogic].
 */
object GuideDb {
    private const val NAME = "guide.db"
    private const val VERSION = 1
    private const val COLS = "tvg_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, " +
        "sub_title TEXT, descr TEXT, categories TEXT, episode TEXT, title_norm TEXT"

    private class Helper(c: Context) : SQLiteOpenHelper(c, NAME, null, VERSION) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE programme($COLS)")
            db.execSQL("CREATE UNIQUE INDEX ux_prog_0 ON programme(tvg_id, start)")
            db.execSQL("CREATE INDEX ix_prog_time_0 ON programme(start, stop)")
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS programme"); db.execSQL("DROP TABLE IF EXISTS programme_new"); db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    @Volatile private var helper: Helper? = null

    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(c.applicationContext).also { helper = it } }
        return h.writableDatabase
    }

    /** Receives the programmes of an import (see [replaceAll]). */
    fun interface Sink {
        fun add(tvgId: String, start: Long, stop: Long, title: String, subTitle: String?, desc: String?, categories: String?, episode: String?)
    }

    class Info(val importedAt: Long, val rows: Int, val sig: String?)

    fun meta(c: Context, key: String): String? =
        db(c).rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", arrayOf(key, value))
    }

    fun info(c: Context): Info = Info(
        meta(c, "imported_at")?.toLongOrNull() ?: 0L,
        meta(c, "rows")?.toIntOrNull() ?: 0,
        meta(c, "sig")
    )

    /**
     * Replaces the whole guide: [fill] gets a [Sink] and adds every programme to keep; duplicates of (tvg_id, start) are
     * ignored (first one wins, e.g. the provider's guide before a custom one). Everything happens in one transaction – an
     * exception or cancellation inside [fill] leaves the old guide untouched. Returns the number of rows stored.
     */
    fun replaceAll(c: Context, sig: String, fill: (Sink) -> Unit): Int {
        val db = db(c)
        db.beginTransaction()
        try {
            val gen = (meta(c, "gen")?.toIntOrNull() ?: 0) + 1
            db.execSQL("DROP TABLE IF EXISTS programme_new")
            db.execSQL("CREATE TABLE programme_new($COLS)")
            db.execSQL("CREATE UNIQUE INDEX ux_prog_$gen ON programme_new(tvg_id, start)")
            var rows = 0
            db.compileStatement(
                "INSERT OR IGNORE INTO programme_new(tvg_id, start, stop, title, sub_title, descr, categories, episode, title_norm) VALUES (?,?,?,?,?,?,?,?,?)"
            ).use { st ->
                fill(Sink { tvg, start, stop, title, sub, desc, cats, ep ->
                    st.clearBindings()
                    st.bindString(1, tvg); st.bindLong(2, start); st.bindLong(3, stop); st.bindString(4, title)
                    if (sub != null) st.bindString(5, sub) else st.bindNull(5)
                    if (desc != null) st.bindString(6, desc) else st.bindNull(6)
                    if (cats != null) st.bindString(7, cats) else st.bindNull(7)
                    if (ep != null) st.bindString(8, ep) else st.bindNull(8)
                    st.bindString(9, OnNowLogic.norm(title))
                    if (st.executeInsert() != -1L) rows++
                })
            }
            db.execSQL("CREATE INDEX ix_prog_time_$gen ON programme_new(start, stop)")
            db.execSQL("DROP TABLE IF EXISTS programme")
            db.execSQL("ALTER TABLE programme_new RENAME TO programme")
            putMeta(db, "gen", gen.toString())
            putMeta(db, "sig", sig)
            putMeta(db, "rows", rows.toString())
            putMeta(db, "imported_at", System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes programmes that ended before [beforeSec]; updates the stored row count. */
    fun prune(c: Context, beforeSec: Long): Int {
        val db = db(c)
        val n = db.delete("programme", "stop < ?", arrayOf(beforeSec.toString()))
        if (n > 0) putMeta(db, "rows", count(c).toString())
        return n
    }

    fun count(c: Context): Int = db(c).rawQuery("SELECT COUNT(*) FROM programme", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** How many programmes carry [categories, episode number, sub-title] – what the classification can rely on. */
    fun coverage(c: Context): IntArray =
        db(c).rawQuery("SELECT COUNT(categories), COUNT(episode), COUNT(sub_title) FROM programme", null).use {
            if (it.moveToFirst()) intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2)) else intArrayOf(0, 0, 0)
        }

    /** All tvg-ids that have programmes (lower case, as imported). */
    fun channelIds(c: Context): Set<String> =
        db(c).rawQuery("SELECT DISTINCT tvg_id FROM programme", null).use { cur ->
            val out = HashSet<String>(cur.count * 2)
            while (cur.moveToNext()) out.add(cur.getString(0))
            out
        }

    /** Programmes of one channel, by start time. */
    fun forChannel(c: Context, tvgId: String): List<EpgEntry> =
        db(c).rawQuery("SELECT title, descr, start, stop FROM programme WHERE tvg_id = ? ORDER BY start", arrayOf(tvgId)).use { cur ->
            val out = ArrayList<EpgEntry>(cur.count)
            while (cur.moveToNext()) out.add(EpgEntry(cur.getString(0), cur.getString(1) ?: "", cur.getLong(2), cur.getLong(3)))
            out
        }

    /** Everything on now (start ≤ now < stop), with categories and episode numbers, for [OnNowLogic]. */
    fun onNow(c: Context, nowSec: Long): List<OnNowLogic.Prog> =
        query(c, "start > ? AND start <= ? AND stop > ?", arrayOf((nowSec - GuideLogic.MAX_LEN_S).toString(), nowSec.toString(), nowSec.toString()))

    /** Programmes starting in [fromSec, toSec) – "starting soon" and "coming up" (Phase 4). */
    fun startingBetween(c: Context, fromSec: Long, toSec: Long): List<OnNowLogic.Prog> =
        query(c, "start >= ? AND start < ?", arrayOf(fromSec.toString(), toSec.toString()))

    private fun query(c: Context, where: String, args: Array<String>): List<OnNowLogic.Prog> =
        db(c).rawQuery("SELECT tvg_id, title, start, stop, categories, episode, sub_title, descr FROM programme WHERE $where", args).use { cur ->
            val out = ArrayList<OnNowLogic.Prog>(cur.count)
            while (cur.moveToNext()) out.add(
                OnNowLogic.Prog(
                    tvgId = cur.getString(0), title = cur.getString(1), start = cur.getLong(2), stop = cur.getLong(3),
                    categories = GuideLogic.splitCategories(cur.getString(4)), episode = cur.getString(5),
                    subTitle = cur.getString(6), desc = cur.getString(7)
                )
            )
            out
        }

    /** Everything gone (all playlists removed). */
    fun clear(c: Context) {
        synchronized(this) { runCatching { helper?.close() }; helper = null }
        runCatching { c.applicationContext.deleteDatabase(NAME) }
    }
}
