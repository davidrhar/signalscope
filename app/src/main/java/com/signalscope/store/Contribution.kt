package com.signalscope.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one phone offers to a shared map, and nothing more than that.
 *
 * ## The shape of it
 *
 * One record per (area, network, band). Signal is carried as **histograms, not percentiles**,
 * because percentiles cannot be merged: averaging one phone's median with another's is a median of
 * medians, which is not the median of the readings and drifts further the more contributors there
 * are. Histograms add exactly, so an area measured by ten people gives the same answer as one
 * phone that had been everywhere.
 *
 * ## What is deliberately absent
 *
 * **Cell identity.** `servingCi` against time is a movement trace -- the project's own security
 * review says so, and says it must never be uploaded. Mapping masts would be interesting and it is
 * not worth that, so a contribution says "band 40 on this network here" and never which cell.
 *
 * **Timestamps.** Only an ISO week. Fine timing across enough areas reconstructs a journey, which
 * is the thing removed when position stopped being stored.
 *
 * **Any identifier.** No device id, no account, no installation id, nothing that links two
 * contributions to one phone. That makes duplicate submissions possible; the alternative is a
 * pseudonym, and a stable pseudonym across areas *is* the linkage a trace needs. Duplicates are the
 * cheaper problem and the server's contributor threshold is where it is dealt with.
 *
 * ## Coarsening
 *
 * Bins are rolled up to [SHARE_RES] before they leave. This is not the privacy control -- coarsening
 * barely is one, since the uniqueness of a location history falls only as about the tenth power of
 * its resolution -- but the shared map is for comparing networks across a neighbourhood, and a
 * finer grid would offer detail the contributor count cannot support anyway.
 *
 * **The actual control is k-anonymity, and it lives on the server.** Nothing in this file can
 * enforce it: an app deciding what is safe to publish is an app trusting a client it does not
 * control. This produces the raw contribution honestly; the aggregator decides what is publishable.
 */
object Contribution {

    const val FORMAT = "signalscope-contribution"
    const val VERSION = 1

    /** ~460 m across. One step coarser than the finest the map draws for its owner. */
    const val SHARE_RES = 8

    /** Below this a record says almost nothing and is dropped rather than sent as noise. */
    const val MIN_SAMPLES = 5

    private const val DAY_MS = 86_400_000L

    data class Stats(
        var samples: Long = 0,
        var observedMs: Long = 0,
        val rsrp: HashMap<Int, Long> = HashMap(),
        val rsrq: HashMap<Int, Long> = HashMap(),
        val sinr: HashMap<Int, Long> = HashMap()
    )

    /**
     * Build the bundle from what is already aggregated on disk.
     *
     * Reads `bin_agg` rather than the raw tables on purpose: those bins are the result of the
     * app's own accumulation, so a contribution can never contain a reading the owner's own map
     * did not also contain. What is shared is a coarser view of what they can see, never a
     * separate extraction.
     */
    suspend fun build(ctx: Context, nowMs: Long = System.currentTimeMillis()): String {
        val rows = runCatching { Db.get(ctx).dao().allBinAgg() }.getOrDefault(emptyList())
        val bins = rows.mapNotNull { BinCodec.decode(it.blob) }

        // (coarse area, network, band) -> merged stats.
        val grouped = LinkedHashMap<Triple<Long, String, String>, Stats>()

        for (b in bins) {
            val area = runCatching { MapHex.cellToParent(b.id, SHARE_RES) }.getOrNull() ?: continue
            val plmn = b.plmn ?: continue
            for ((rawBand, ms) in b.bandMs) {
                val band = shareBandLabel(rawBand)

                val rsrp = b.bandRsrpHist[band]
                val sinr = b.bandSinrHist[band]
                // A bin persisted before per-band histograms existed has none. It is skipped
                // rather than filled in from the bin's total: apportioning the whole bin's
                // distribution across its bands is what produced identically-shaped bands in the
                // first bundle this app ever generated.
                if (rsrp.isNullOrEmpty() && sinr.isNullOrEmpty()) continue

                val key = Triple(area, plmn, band)
                val st = grouped.getOrPut(key) { Stats() }
                st.observedMs += ms
                val bandTotal = b.bandMs.values.sum().takeIf { it > 0 } ?: continue
                st.samples += (b.nObs * (ms.toDouble() / bandTotal)).toLong()
                rsrp?.forEach { (v, w) -> st.rsrp[v] = (st.rsrp[v] ?: 0L) + w }
                sinr?.forEach { (v, w) -> st.sinr[v] = (st.sinr[v] ?: 0L) + w }
            }
        }

        val week = isoWeek(nowMs)
        val out = JSONArray()
        for ((key, st) in grouped) {
            if (st.samples < MIN_SAMPLES) continue
            val (area, plmn, band) = key
            out.put(JSONObject().apply {
                put("area", area)
                put("res", SHARE_RES)
                put("network", plmn)
                put("band", band)
                put("samples", st.samples)
                put("observedMs", st.observedMs)
                put("rsrp", hist(st.rsrp))
                put("sinr", hist(st.sinr))
                put("week", week)
            })
        }

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            // Day, not instant. A submission time to the second is a timestamp on the whole
            // bundle, and the point of the week buckets inside it is that there is not one.
            put("generated", dayStamp(nowMs))
            put("shareRes", SHARE_RES)
            put("records", out)
            put("note", "Aggregated per area, network and band. No cell identities, no " +
                "timestamps finer than a week, no device or account identifier.")
        }.toString()
    }

    /** How many records a bundle would contain, for showing someone before they send it. */
    suspend fun recordCount(ctx: Context): Int =
        runCatching { JSONObject(build(ctx)).getJSONArray("records").length() }.getOrDefault(0)


    /**
     * The band label a contribution carries.
     *
     * `bandLabel` emits "band 40" when the RAT did not say which numbering applies. 3GPP numbers
     * LTE and NR bands independently and several collide, so B40 and n40 are different spectrum.
     *
     * Three ways to handle that, and only one is honest:
     *
     *  - **Merge into "B40".** A guess, and a wrong one where it matters: the reference device's
     *    first real bundle contained "band 78", and 78 is an NR number with no LTE counterpart in
     *    use. Merging would have relabelled NR spectrum as LTE.
     *  - **Drop it.** What this did first. It discards a measured place -- an area whose only
     *    readings were RAT-less vanishes from the shared map entirely, which reads as "nobody has
     *    been there". On this device that was 3.3 % of observed time.
     *  - **Keep the number, flag the ambiguity.** "?40". It never merges with B40 or n40, because
     *    we do not know that it is either; and it never merges with "?78", because those are not
     *    the same spectrum whatever the numbering. Nothing is thrown away and nothing is claimed.
     *
     * The map shows these as "band 40, RAT not reported". A category a reader can see and discount
     * beats a silence they cannot.
     */
    fun shareBandLabel(raw: String): String =
        if (raw.startsWith("B") || raw.startsWith("n")) raw
        else "?" + raw.removePrefix("band ").trim()

    private fun hist(m: Map<Int, Long>): JSONObject =
        JSONObject().apply { m.toSortedMap().forEach { (v, ms) -> put(v.toString(), ms) } }

    private fun dayStamp(ms: Long): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = ms
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * ISO-8601 week, e.g. 2026-W38.
     *
     * java.time rather than Calendar: Calendar has no portable week-based year field on Android,
     * and taking the plain YEAR alongside WEEK_OF_YEAR mislabels the turn of the year -- the 29th
     * of December 2025 is 2026-W01, and the naive pairing calls it 2025-W01, silently merging two
     * different weeks a year apart into one bucket.
     */
    private fun isoWeek(ms: Long): String {
        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val wf = java.time.temporal.WeekFields.ISO
        return "%04d-W%02d".format(
            d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear())
        )
    }
}
