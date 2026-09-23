package com.signalscope.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everyone else's measurements, as one public aggregate.
 *
 * ## Why this needs no consent
 *
 * It is a read. Nothing about this phone leaves it: no bundle is uploaded, no identifier is sent,
 * and the document fetched is the same one anybody can fetch. Consent belongs to *contributing*,
 * which is a separate, deliberate act with its own screen. Asking permission to look at a public
 * map would teach the reader that the prompts here are noise.
 *
 * ## What arrives
 *
 * Cells that have cleared the server's thresholds -- three distinct contributors and thirty
 * readings between them. Anything below that was never published to anyone, so a thin area is
 * simply absent rather than drawn faintly. There is no client-side filtering to do, and doing any
 * would imply the app had been trusted to decide, which it deliberately has not been.
 *
 * ## Caching
 *
 * Written to disk on every successful fetch and read from there at startup, so the layer is
 * populated before the network answers and survives being offline. A stale copy says when it was
 * fetched; it never pretends to be current.
 */
object SharedMap {

    /**
     * Where the aggregate lives.
     *
     * Hard-coded rather than configurable because a settable endpoint on a measurement app is a
     * way to point someone's contributions somewhere they did not intend. Changing it is a build.
     */
    const val ENDPOINT = "https://signalscope-map.fly.dev/shared-map.json"

    private const val CACHE = "shared-map.json"
    private const val TIMEOUT_MS = 20_000

    data class Cell(
        val area: Long,
        val network: String,
        val band: String,
        val contributors: Int,
        val samples: Long,
        val rsrpP50: Int?,
        val sinrP50: Int?
    )

    data class Snapshot(
        val cells: List<Cell>,
        val generated: String,
        val bundles: Int,
        val minContributors: Int,
        val fetchedAtWall: Long,
        /** True when this came off disk rather than the network. */
        val fromCache: Boolean
    ) {
        val networks: List<String> get() = cells.map { it.network }.distinct().sorted()
    }

    /** The last copy written to disk, or null. Cheap: no network, safe on startup. */
    suspend fun cached(ctx: Context): Snapshot? = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, CACHE)
        if (!f.exists()) return@withContext null
        runCatching { parse(f.readText(), f.lastModified(), fromCache = true) }.getOrNull()
    }

    /**
     * @return the fetched snapshot, or null when the network failed.
     *
     * A failure is not an error state worth surfacing loudly -- the cached copy is still drawn and
     * still labelled with its age. A map that blanks itself because one request timed out is less
     * useful than one that says "as of yesterday".
     */
    suspend fun fetch(ctx: Context): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val body = c.inputStream.bufferedReader().use { it.readText() }
                // Written only after it parses. A truncated response overwriting a good cache
                // would turn one bad request into a permanently empty layer.
                val snap = parse(body, System.currentTimeMillis(), fromCache = false)
                File(ctx.filesDir, CACHE).writeText(body)
                snap
            } finally {
                runCatching { c.disconnect() }
            }
        }.getOrNull()
    }

    private fun parse(body: String, whenWall: Long, fromCache: Boolean): Snapshot {
        val o = JSONObject(body)
        require(o.optString("format") == "signalscope-shared-map") { "not a shared map" }
        val arr = o.optJSONArray("cells")
        val cells = ArrayList<Cell>(arr?.length() ?: 0)
        for (i in 0 until (arr?.length() ?: 0)) {
            val c = arr!!.getJSONObject(i)
            cells.add(
                Cell(
                    area = c.optLong("area"),
                    network = c.optString("network"),
                    band = c.optString("band"),
                    contributors = c.optInt("contributors"),
                    samples = c.optLong("samples"),
                    rsrpP50 = if (c.isNull("rsrpP50")) null else c.optInt("rsrpP50"),
                    sinrP50 = if (c.isNull("sinrP50")) null else c.optInt("sinrP50")
                )
            )
        }
        return Snapshot(
            cells = cells,
            generated = o.optString("generated", "?"),
            bundles = o.optInt("bundles"),
            minContributors = o.optInt("minContributors", 3),
            fetchedAtWall = whenWall,
            fromCache = fromCache
        )
    }

    /**
     * Polygons for the crowd layer.
     *
     * Colour comes from SINR because that is the measure the whole project turned on -- strength
     * looks fine nearly everywhere and says nothing. The ramp matches the local SINR layer's
     * legend exactly, so a shared cell and one of your own at the same quality are the same colour
     * and can be compared by eye without a key.
     */
    fun geoJson(cells: List<Cell>): String {
        val sb = StringBuilder(1024)
        sb.append("""{"type":"FeatureCollection","features":[""")
        cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{""")
            sb.append(""""area":"${c.area}","plmn":"${c.network}","band":"${c.band}",""")
            sb.append(""""k":${c.contributors},"colour":"${sinrColour(c.sinrP50)}"""")
            sb.append("""},"geometry":{"type":"Polygon","coordinates":[[""")
            MapHex.cellToBoundary(c.area).forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append('[')
                    .append(String.format(java.util.Locale.US, "%.6f", p[0])).append(',')
                    .append(String.format(java.util.Locale.US, "%.6f", p[1])).append(']')
            }
            sb.append("]]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Same cut-points as the local SINR legend. */
    private fun sinrColour(sinr: Int?): String = when {
        sinr == null -> "#2a3240"
        sinr >= 20 -> "#2f9e6b"
        sinr >= 13 -> "#46b07a"
        sinr >= 5 -> "#b8862f"
        sinr >= 0 -> "#a8642c"
        else -> "#b03a42"
    }
}
