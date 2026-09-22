package com.signalscope.store

import android.content.Context

/**
 * Delete `signalscope-map.db`, the separate database position used to live in.
 *
 * Its rows are an ordered, timestamped sequence of bins as fine as ~65 m: a movement history, and
 * the most sensitive thing this app ever wrote down. Once position stopped being stored, leaving
 * that file sitting in the app's data directory would mean the history survives the decision to
 * stop keeping it -- invisible to its owner, who cannot reach it and would not know to ask.
 *
 * So it goes, along with its write-ahead log and shared-memory sidecars, which hold recent rows the
 * main file has not absorbed yet and would otherwise leave fragments behind.
 *
 * Failure is ignored on purpose. A file that will not delete is worth exactly one attempt per
 * start; it is not worth taking the collector down for, and the attempt simply repeats next time.
 */
object LegacyPositionFile {

    private const val NAME = "signalscope-map.db"

    /** @return true when something was actually deleted. */
    fun remove(ctx: Context): Boolean {
        val app = ctx.applicationContext
        var removed = false
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = app.getDatabasePath(NAME + suffix)
            if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) removed = true
        }
        return removed
    }
}
