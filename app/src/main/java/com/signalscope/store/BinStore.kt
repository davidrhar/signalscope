package com.signalscope.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import org.json.JSONArray
import org.json.JSONObject

/**
 * One bin's accumulated evidence, kept across restarts.
 *
 * ## Why this exists
 *
 * Position stopped being stored when `map_fix` was dropped, and the fixes the map builds from now
 * live in memory and die with the process. That left the map unable to remember anything: days of
 * accumulated bins vanished on every restart, update and reboot.
 *
 * The answer is not to write positions back to disk. It is to keep the *result* instead of the
 * inputs -- counters, histograms and shares per bin, with no ordering between bins and no timestamp
 * finer than a day. A trace says where someone was and when. This says what the radio was like in a
 * place, and cannot be replayed into a journey.
 *
 * ## Why the numbers cannot drift
 *
 * Aggregation drains the buffer: [BinAggregator] builds bins from the fixes it consumes and then
 * removes exactly those fixes, so what is persisted and what is still live are disjoint by
 * construction. Nothing is counted twice because nothing is ever eligible to be counted twice --
 * which is a stronger guarantee than a watermark, and does not go wrong if a flush is interrupted.
 *
 * [Bin] was already built to merge -- every field is a sum, a union, a histogram or a min/max, and
 * never a derived average -- so a bin loaded from disk and merged with a fresh one is identical to
 * one pass over all the readings.
 */
@Entity(tableName = "bin_agg", primaryKeys = ["binId", "subId"])
data class BinAgg(
    val binId: Long,
    val subId: Int,
    /** The whole accumulator, as JSON. Columns would be forty of them, most read only together. */
    @ColumnInfo(name = "blob") val blob: String,
    /**
     * Whole days since the epoch, for retention only.
     *
     * Deliberately not milliseconds. A per-bin last-seen at millisecond precision, across enough
     * bins, is the ordering that was just removed -- it would say which bin someone was in last and
     * roughly when, which is the beginning of a journey again.
     */
    val lastSeenDay: Int
)

/**
 * [Bin] to JSON and back.
 *
 * Keys are short because this is written per bin on every flush and read on every map build; they
 * are not intended to be read by a person, and the field they map to is one line away.
 *
 * Every accumulator field is carried. Dropping one to save space would not lose a little accuracy,
 * it would silently produce a different answer after the first restart than before it -- the kind
 * of failure this project has already been caught by more than once.
 */
internal object BinCodec {

    fun encode(b: Bin): String = JSONObject().apply {
        put("id", b.id); put("res", b.res); put("sub", b.subId)
        put("oss", b.otherSubSamples); put("n", b.nObs)
        put("con", JSONArray(b.contributors.toList()))
        put("om", b.observedMs); put("oms", b.observedMsSq); put("vm", b.validatedMs)
        put("crs", b.cellRouteSamples); put("crm", b.cellRouteMs); put("cvm", b.cellValidatedMs)
        put("wrs", b.wifiRouteSamples); put("wrm", b.wifiRouteMs); put("sm", b.suspendedMs)
        put("pn", b.probeN); put("pf", b.probeFail); put("pi", b.probeInstrument)
        put("pnb", b.probeNoBearer)
        put("pol", JSONArray(b.probeOkLatencyMs))
        put("cpn", b.coldProbeN); put("cpf", b.coldProbeFail)
        put("pe", mapSI(b.probeErrors))
        put("cel", JSONArray(b.cells.map { c ->
            JSONObject().apply {
                put("rat", c.rat)
                c.ci?.let { put("ci", it) }; c.pci?.let { put("pci", it) }
                c.band?.let { put("b", it) }
                put("s", c.samples); put("ms", c.ms)
            }
        }))
        put("fl", b.flaps); put("cc", b.cellChanges)
        b.nrAnchor?.let { put("nra", it) }
        put("spm", b.speedMpsMs); put("sps", b.speedMs)
        put("rh", mapIL(b.rsrpHist)); put("sh", mapIL(b.sinrHist))
        put("rm", mapSL(b.ratMs)); put("bm", mapSL(b.bandMs)); put("pm", mapSL(b.plmnMs))
        put("cu", b.childUnits); put("lc", b.leafCount)
        put("fs", b.firstSeenMillis); put("ls", b.lastSeenMillis)
    }.toString()

    fun decode(s: String): Bin? = runCatching {
        val o = JSONObject(s)
        Bin(
            id = o.getLong("id"), res = o.getInt("res"), subId = o.getInt("sub"),
            otherSubSamples = o.optInt("oss"), nObs = o.optInt("n"),
            contributors = arr(o, "con").toSet(),
            observedMs = o.optLong("om"), observedMsSq = o.optDouble("oms", 0.0),
            validatedMs = o.optLong("vm"),
            cellRouteSamples = o.optInt("crs"), cellRouteMs = o.optLong("crm"),
            cellValidatedMs = o.optLong("cvm"),
            wifiRouteSamples = o.optInt("wrs"), wifiRouteMs = o.optLong("wrm"),
            suspendedMs = o.optLong("sm"),
            probeN = o.optInt("pn"), probeFail = o.optInt("pf"),
            probeInstrument = o.optInt("pi"), probeNoBearer = o.optInt("pnb"),
            probeOkLatencyMs = ints(o, "pol"),
            coldProbeN = o.optInt("cpn"), coldProbeFail = o.optInt("cpf"),
            probeErrors = unmapSI(o.optJSONObject("pe")),
            cells = o.optJSONArray("cel").let { a ->
                (0 until (a?.length() ?: 0)).map { i ->
                    val c = a!!.getJSONObject(i)
                    MapProbeJoin.CellShare(
                        rat = c.optString("rat", "?"),
                        ci = if (c.has("ci")) c.getLong("ci") else null,
                        pci = if (c.has("pci")) c.getInt("pci") else null,
                        band = if (c.has("b")) c.getInt("b") else null,
                        samples = c.optInt("s"), ms = c.optLong("ms")
                    )
                }
            },
            flaps = o.optInt("fl"), cellChanges = o.optInt("cc"),
            nrAnchor = if (o.has("nra")) o.getDouble("nra") else null,
            speedMpsMs = o.optDouble("spm", 0.0), speedMs = o.optLong("sps"),
            rsrpHist = unmapIL(o.optJSONObject("rh")), sinrHist = unmapIL(o.optJSONObject("sh")),
            ratMs = unmapSL(o.optJSONObject("rm")), bandMs = unmapSL(o.optJSONObject("bm")),
            plmnMs = unmapSL(o.optJSONObject("pm")),
            // Recomputed by the builder from the fields above, never trusted from disk: the
            // thresholds that decide a class change as the project learns, and a stored class
            // would keep asserting whatever was true the day it was written.
            topCause = 0, causeShare = 0.0, cls = 0,
            childUnits = o.optLong("cu"), leafCount = o.optInt("lc"),
            mergeReason = "restored",
            firstSeenMillis = o.optLong("fs"), lastSeenMillis = o.optLong("ls")
        )
    }.getOrNull()

    private fun mapIL(m: Map<Int, Long>) = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }
    private fun mapSL(m: Map<String, Long>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }
    private fun mapSI(m: Map<String, Int>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }

    private fun unmapIL(o: JSONObject?): Map<Int, Long> {
        if (o == null) return emptyMap()
        val out = HashMap<Int, Long>(o.length())
        o.keys().forEach { k -> k.toIntOrNull()?.let { out[it] = o.optLong(k) } }
        return out
    }
    private fun unmapSL(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Long>(o.length())
        o.keys().forEach { k -> out[k] = o.optLong(k) }
        return out
    }
    private fun unmapSI(o: JSONObject?): Map<String, Int> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Int>(o.length())
        o.keys().forEach { k -> out[k] = o.optInt(k) }
        return out
    }
    private fun arr(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
    private fun ints(o: JSONObject, k: String): List<Int> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optInt(it) }
    }
}
