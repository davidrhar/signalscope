package com.signalscope.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What this mast has actually been like, here, at about this time of day.
 *
 * ## Why time of day is not optional
 *
 * A flat "this is a bad mast" label would be wrong most of the time, and the device's own data
 * says so. One site on the reference phone put 94 % of its morning readings below usable SINR and
 * 2 % of its evening ones -- the same mast, the same phone, the same week. Every site measured was
 * flawless overnight. A warning that fires on the mast alone would be crying wolf two thirds of
 * the time, and the third time it mattered nobody would read it.
 *
 * So the verdict is per (site, time of day), over a window around the current hour.
 *
 * ## Why SINR below zero, and not an average
 *
 * Averages hide exactly the thing that breaks a call. A site averaging 4 dB while spending a
 * quarter of its time below zero is not "slightly worse" than one averaging 4 dB steadily -- the
 * first drops calls and the second does not. The measure is the share of readings below 0 dB,
 * where nothing real-time survives.
 *
 * ## Why this is local only
 *
 * Site identity is deliberately absent from anything this app shares: `servingCi` against time is
 * a movement trace, and the contribution format excludes cell identities for that reason. So there
 * is no crowd-sourced mast reputation and there should not be. This is one phone's memory of its
 * own experience, and it says nothing about anybody else's.
 */
object SiteHistory {

    /** Hours either side of now. Four hours of context, not a whole day flattened into one. */
    private const val WINDOW_H = 2

    /** Below this there is no verdict, only a handful of readings. */
    private const val MIN_SAMPLES = 80

    /** Share of readings below 0 dB at which this is worth interrupting someone about. */
    private const val BAD_FRACTION = 0.25

    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    data class Verdict(
        val site: Long,
        val samples: Int,
        /** Share of readings below 0 dB SINR in this window. */
        val badFraction: Double,
        val medianRsrq: Int?,
        /** The part of the day this site has been at its best, where one stands out. */
        val betterPart: String?,
        val betterFraction: Double?
    ) {
        val isBad: Boolean get() = badFraction >= BAD_FRACTION
    }

    /**
     * @param ci the serving Cell Identity, LTE only -- NR's NCI has a configurable gNB-ID length,
     *   so the site cannot be separated from the sector and is not guessed at.
     * @return null when there is not enough history to say anything, which is the common case on a
     *   new phone and must read as silence rather than as reassurance.
     */
    suspend fun forCell(
        ctx: Context, ci: Long?, rat: String?, plmn: String?
    ): Verdict? = withContext(Dispatchers.IO) {
        if (ci == null || rat == null || !rat.startsWith("LTE")) return@withContext null
        if (ci !in 0..0x0FFFFFFFL) return@withContext null
        if (plmn.isNullOrBlank() || plmn == "—") return@withContext null
        val site = ci shr 8

        runCatching {
            // Read the standing record, not the raw rows. The record outlives the 30-day sweep, so
            // a mast the phone has known for months keeps its history instead of silently
            // forgetting everything older than retention.
            val rows = Db.get(ctx).dao().siteStats(plmn, site)
            if (rows.isEmpty()) return@runCatching null

            val nowH = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val window = (-WINDOW_H..WINDOW_H).map { ((nowH + it) % 24 + 24) % 24 }.toSet()
            val here = rows.filter { it.hourBucket in window }
            val n = here.sumOf { it.samples }
            if (n < MIN_SAMPLES) return@runCatching null

            val bad = here.sumOf { it.belowZero }
            val hist = HashMap<Int, Long>()
            here.forEach { SiteAggregator.decodeHist(it.rsrqHist, hist) }

            val (betterPart, betterFrac) = bestPart(rows)
            Verdict(
                site = site,
                samples = n.toInt(),
                badFraction = bad.toDouble() / n,
                medianRsrq = median(hist),
                betterPart = betterPart,
                betterFraction = betterFrac
            )
        }.getOrNull()
    }

    /** Weighted median over a value -> count histogram. */
    private fun median(hist: Map<Int, Long>): Int? {
        if (hist.isEmpty()) return null
        val total = hist.values.sum()
        var seen = 0L
        for (k in hist.keys.sorted()) {
            seen += hist[k]!!
            if (seen >= total / 2) return k
        }
        return hist.keys.maxOrNull()
    }

    /**
     * When this mast is at its best, if any part of the day clearly is.
     *
     * Stated because it is the only actionable thing here. Nothing the user or the app can do
     * chooses a mast -- band and cell selection need a UICC-signed app -- so the useful sentence
     * is not "avoid this" but "this one is usually fine later", which is true and checkable.
     */
    private fun bestPart(rows: List<SiteStat>): Pair<String?, Double?> {
        val parts = listOf(
            "overnight" to (0..6), "in the morning" to (7..11),
            "in the afternoon" to (12..17), "in the evening" to (18..23)
        )
        val best = parts.mapNotNull { (name, hours) ->
            val part = rows.filter { it.hourBucket in hours }
            val n = part.sumOf { it.samples }
            if (n < MIN_SAMPLES) null
            else name to (part.sumOf { it.belowZero }.toDouble() / n)
        }.minByOrNull { it.second } ?: return null to null
        // Only worth saying when it is actually good, not merely least bad.
        return if (best.second < BAD_FRACTION / 2) best.first to best.second else null to null
    }
}
