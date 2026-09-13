package com.signalscope.collect

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * PMTiles v3, enough of it to cut a region out of a 137 GB planet archive over HTTP range reads
 * and write a new valid archive — with no dependency beyond `java.*`.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 *
 * This is what `pmtiles extract` does, reimplemented because the app has to do it itself: parse
 * the 127-byte header, walk the root and leaf directories, work out which tile IDs fall inside a
 * bbox, range-fetch only those byte ranges, and reassemble them into a smaller archive with its
 * own directories. The source's tile bodies are copied **verbatim** — no re-encoding — so the
 * metadata (including the ODbL attribution string) and the vector schema come through unchanged.
 *
 * ## The part that is easy to get wrong
 *
 * Tile IDs are a **Hilbert curve**, not row-major. `(z,x,y) → id` and back are the two functions
 * everything else is built on, and a subtly wrong rotation gives you an archive full of tiles
 * from the wrong place, which renders — just somewhere else. The directory codec is four varint
 * arrays with a delta-encoded ID column and an offset column where **0 means "contiguous with the
 * previous entry"**; both were checked by round-tripping a real Protomaps archive's root
 * directory byte-for-byte before this was written.
 *
 * ## Memory
 *
 * A region body can be tens of MB, which is not a thing to hold in a `ByteArray` on a phone. The
 * output offsets are all known before a single tile byte is fetched (they follow from the entry
 * list), so the file is written header-first and tile bodies are seeked into place with
 * [RandomAccessFile] as each range arrives. Peak heap is one range buffer, capped at
 * [MAX_RANGE_BYTES].
 */
object RegionPmtiles {

    const val HEADER_BYTES = 127

    /** One request's worth of tile data. Bounds peak heap; also bounds what one retry costs. */
    const val MAX_RANGE_BYTES = 4L * 1024 * 1024

    /** Spec recommends header+root fit here, so one request usually gets both. */
    const val ROOT_PROBE_BYTES = 16384

    /**
     * Identify ourselves. Anyone whose bandwidth we are spending is entitled to know who is
     * spending it, and to be able to block us specifically rather than by netblock.
     */
    const val USER_AGENT = "SignalScope/0.1 (Android; +https://github.com/signalscope/signalscope)"

    // ---------------------------------------------------------------- header

    data class Header(
        val rootOffset: Long, val rootLength: Long,
        val metaOffset: Long, val metaLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Int, val internalCompression: Int, val tileCompression: Int,
        val tileType: Int, val minZoom: Int, val maxZoom: Int,
        val west: Double, val south: Double, val east: Double, val north: Double
    )

    fun parseHeader(b: ByteArray): Header {
        require(b.size >= HEADER_BYTES) { "short header" }
        require(
            b[0] == 'P'.code.toByte() && b[1] == 'M'.code.toByte() && b[2] == 'T'.code.toByte() &&
                b[3] == 'i'.code.toByte() && b[4] == 'l'.code.toByte() && b[5] == 'e'.code.toByte() &&
                b[6] == 's'.code.toByte()
        ) { "not a PMTiles archive" }
        require(b[7].toInt() == 3) { "PMTiles spec v${b[7].toInt()}, only v3 is supported" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            rootOffset = bb.getLong(8), rootLength = bb.getLong(16),
            metaOffset = bb.getLong(24), metaLength = bb.getLong(32),
            leafOffset = bb.getLong(40), leafLength = bb.getLong(48),
            dataOffset = bb.getLong(56), dataLength = bb.getLong(64),
            addressedTiles = bb.getLong(72), tileEntries = bb.getLong(80),
            tileContents = bb.getLong(88),
            clustered = b[96].toInt(), internalCompression = b[97].toInt(),
            tileCompression = b[98].toInt(), tileType = b[99].toInt(),
            minZoom = b[100].toInt() and 0xFF, maxZoom = b[101].toInt() and 0xFF,
            west = bb.getInt(102) / 1e7, south = bb.getInt(106) / 1e7,
            east = bb.getInt(110) / 1e7, north = bb.getInt(114) / 1e7
        )
    }

    // ---------------------------------------------------------------- directories

    /** `runLength == 0` means this entry points at a leaf directory, not a tile. */
    data class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)

    private class Varints(val b: ByteArray) {
        var i = 0
        fun next(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val v = b[i++].toInt() and 0xFF
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v < 0x80) return result
                shift += 7
            }
        }
    }

    private fun putVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun parseDirectory(raw: ByteArray): List<Entry> {
        val v = Varints(raw)
        val n = v.next().toInt()
        val ids = LongArray(n); val runs = LongArray(n); val lens = LongArray(n); val offs = LongArray(n)
        var last = 0L
        for (k in 0 until n) { last += v.next(); ids[k] = last }
        for (k in 0 until n) runs[k] = v.next()
        for (k in 0 until n) lens[k] = v.next()
        for (k in 0 until n) {
            val raw0 = v.next()
            offs[k] = if (raw0 == 0L && k > 0) offs[k - 1] + lens[k - 1] else raw0 - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    fun serializeDirectory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream(entries.size * 6 + 8)
        putVarint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { putVarint(out, e.tileId - last); last = e.tileId }
        for (e in entries) putVarint(out, e.runLength)
        for (e in entries) putVarint(out, e.length)
        for ((k, e) in entries.withIndex()) {
            val prev = if (k > 0) entries[k - 1] else null
            if (prev != null && e.offset == prev.offset + prev.length) putVarint(out, 0L)
            else putVarint(out, e.offset + 1)
        }
        return out.toByteArray()
    }

    /** Compression byte: 1 = none, 2 = gzip. Anything else we do not claim to handle. */
    fun decompress(b: ByteArray, kind: Int): ByteArray =
        if (kind == 2) GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

    fun compress(b: ByteArray, kind: Int): ByteArray {
        if (kind != 2) return b
        val bos = ByteArrayOutputStream(b.size / 2 + 64)
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    /** Largest entry with `tileId <= id`, or null. Directories are sorted, so binary search. */
    fun seek(entries: List<Entry>, id: Long): Entry? {
        var lo = 0; var hi = entries.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= id) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val e = entries[found]
        if (e.runLength == 0L) return e                      // leaf pointer, always a candidate
        return if (id < e.tileId + e.runLength) e else null  // inside the run, or a genuine gap
    }

    // ---------------------------------------------------------------- Hilbert tile IDs

    /**
     * `(z,x,y) → tile id`, the spec's Hilbert ordering. The accumulator is the count of all tiles
     * at lower zooms, `(4^z − 1) / 3`.
     */
    fun tileId(z: Int, x: Long, y: Long): Long {
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        var xx = x; var yy = y
        var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((xx and s) > 0) 1L else 0L
            val ry = if ((yy and s) > 0) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                val t = xx; xx = yy; yy = t
            }
            s /= 2
        }
        return acc + d
    }

    fun lonToTileX(lon: Double, z: Int): Long {
        val n = 1L shl z
        return min(n - 1, max(0L, ((lon + 180.0) / 360.0 * n).toLong()))
    }

    fun latToTileY(lat: Double, z: Int): Long {
        val n = 1L shl z
        val clamped = max(-85.05112878, min(85.05112878, lat))
        val r = Math.toRadians(clamped)
        val merc = ln(tan(r) + 1.0 / kotlin.math.cos(r))
        return min(n - 1, max(0L, ((1.0 - merc / Math.PI) / 2.0 * n).toLong()))
    }

    /**
     * Every tile ID inside a bbox across a zoom range, ascending.
     *
     * `west > east` means the box crosses the antimeridian — which is not an edge case we can
     * skip, because it is the true bbox of the United States, Russia, New Zealand and Fiji. The
     * x range simply wraps modulo 2^z.
     */
    fun tileIdsIn(west: Double, south: Double, east: Double, north: Double, minZ: Int, maxZ: Int): LongArray {
        val out = ArrayList<Long>(4096)
        for (z in minZ..maxZ) {
            val n = 1L shl z
            val y0 = latToTileY(north, z)
            val y1 = latToTileY(south, z)
            val x0 = lonToTileX(west, z)
            val x1 = lonToTileX(east, z)
            val xs = ArrayList<Long>()
            if (west <= east) for (x in x0..x1) xs.add(x)
            else { for (x in x0 until n) xs.add(x); for (x in 0..x1) xs.add(x) }
            for (y in y0..y1) for (x in xs) out.add(tileId(z, x, y))
        }
        val arr = out.toLongArray()
        arr.sort()
        return arr
    }

    /** Tile count for a bbox at one zoom, without materialising anything. Used for budgeting. */
    fun tileCount(west: Double, south: Double, east: Double, north: Double, z: Int): Long {
        val n = 1L shl z
        val rows = latToTileY(south, z) - latToTileY(north, z) + 1
        val x0 = lonToTileX(west, z); val x1 = lonToTileX(east, z)
        val cols = if (west <= east) x1 - x0 + 1 else (n - x0) + (x1 + 1)
        return rows * cols
    }

    // ---------------------------------------------------------------- HTTP

    class Budget(val maxRequests: Int, val maxBytes: Long) {
        var requests = 0; private set
        var bytes = 0L; private set
        fun spend(n: Long) { requests++; bytes += n }
        fun exhausted() = requests >= maxRequests || bytes >= maxBytes
    }

    class Aborted(msg: String) : Exception(msg)

    /**
     * One range read, with backoff. 429 and 5xx are the host telling us to slow down, and the
     * only correct response is to actually slow down and then give up rather than hammer.
     */
    private fun range(url: String, offset: Long, length: Long, budget: Budget, cancelled: () -> Boolean): ByteArray {
        var attempt = 0
        while (true) {
            if (cancelled()) throw Aborted("cancelled")
            if (budget.exhausted()) throw Aborted("request budget exhausted")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Encoding", "identity")
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
                val code = conn.responseCode
                if (code == 206 || code == 200) {
                    val body = conn.inputStream.use { it.readBytes() }
                    budget.spend(body.size.toLong())
                    if (body.size.toLong() != length && code == 206) {
                        throw Exception("short range: got ${body.size} of $length")
                    }
                    return body
                }
                if (code == 429 || code in 500..599) {
                    if (++attempt > 4) throw Aborted("HTTP $code after $attempt attempts")
                    Thread.sleep(min(60_000L, 1_000L shl attempt))
                    continue
                }
                throw Aborted("HTTP $code")
            } catch (a: Aborted) {
                conn?.disconnect()
                throw a
            } catch (e: Exception) {
                // Only a failed connection is torn down. On success the connection is deliberately
                // NOT disconnected: disconnect() evicts the socket from Android's keep-alive pool,
                // and an extract is dozens of requests to one host. Measured on device, one TLS
                // handshake per range took a 16-request country extract to 70 s; reusing the
                // connection is the difference between "a moment" and "did it hang?".
                conn?.disconnect()
                if (++attempt > 4) throw Aborted("${e.javaClass.simpleName}: ${e.message}")
                Thread.sleep(min(30_000L, 1_000L shl attempt))
            }
        }
    }

    // ---------------------------------------------------------------- extract

    data class Cost(val requests: Int, val transferred: Long, val millis: Long, val bytesWritten: Long) {
        val overfetchPct: Int get() =
            if (bytesWritten <= 0) 0 else (((transferred - bytesWritten) * 100) / bytesWritten).toInt()
    }

    data class Progress(val phase: String, val done: Int, val total: Int, val transferred: Long)

    /**
     * Cut `[west,south,east,north] × [minZ,maxZ]` out of the archive at [url] and write a new
     * archive at [dest]. Blocking; call from an IO dispatcher.
     *
     * Fails soft: on any error the partial file is deleted and the exception propagates, so a
     * half-written archive can never be adopted as a region.
     */
    fun extract(
        url: String,
        dest: File,
        west: Double, south: Double, east: Double, north: Double,
        minZ: Int, maxZ: Int,
        maxRequests: Int = 400,
        maxBytes: Long = 96L * 1024 * 1024,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): Cost {
        val t0 = System.currentTimeMillis()
        val budget = Budget(maxRequests, maxBytes)
        // The work file lives in a subdirectory, not beside the archives. MapLibre's PMTiles
        // source is handed whatever the style names, and RegionStore lists `*.pmtiles` files in
        // the archives directory — so a half-written file must not be able to appear there under
        // any name or at any instant. A subdirectory is excluded from that listing structurally
        // rather than by a suffix convention someone could later change.
        val work = File(dest.parentFile, WORK_DIR).apply { mkdirs() }
        val tmp = File(work, dest.name + "." + System.nanoTime() + ".part")
        tmp.delete()

        try {
            onProgress(Progress("reading header", 0, 0, 0))
            val probe = range(url, 0, ROOT_PROBE_BYTES.toLong(), budget, cancelled)
            val h = parseHeader(probe)

            val rootRaw = if (h.rootOffset + h.rootLength <= probe.size)
                probe.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
            else range(url, h.rootOffset, h.rootLength, budget, cancelled)
            val root = parseDirectory(decompress(rootRaw, h.internalCompression))

            val metadata = if (h.metaOffset + h.metaLength <= probe.size)
                probe.copyOfRange(h.metaOffset.toInt(), (h.metaOffset + h.metaLength).toInt())
            else range(url, h.metaOffset, h.metaLength, budget, cancelled)

            val hiZ = min(maxZ, h.maxZoom)
            val wanted = tileIdsIn(west, south, east, north, minZ, hiZ)
            onProgress(Progress("resolving ${wanted.size} tiles", 0, wanted.size, budget.bytes))

            // Root -> leaves. Group by leaf so each leaf directory is fetched exactly once.
            val resolved = HashMap<Long, Entry>(wanted.size * 2)
            val byLeaf = LinkedHashMap<Entry, MutableList<Long>>()
            for (id in wanted) {
                val e = seek(root, id) ?: continue
                if (e.runLength == 0L) byLeaf.getOrPut(e) { ArrayList() }.add(id)
                else resolved[id] = e
            }
            // Leaf directories are contiguous in the leaf section, and a bbox that spans zooms
            // touches one leaf per zoom band — so they merge into a handful of requests exactly
            // the way tile data does. Fetching them one at a time is the difference between 16
            // requests and 5 for a small country, which is both slower and less polite.
            val leafPtrs = byLeaf.keys.sortedBy { it.offset }
            val leafRanges = mergeRanges(
                leafPtrs.map { it.offset }.toLongArray(),
                leafPtrs.associate { it.offset to it.length },
                64L * 1024
            )
            val leafBuf = HashMap<Long, ByteArray>(leafPtrs.size)
            for ((start, len) in leafRanges) {
                val buf = range(url, h.leafOffset + start, len, budget, cancelled)
                for (p in leafPtrs) {
                    if (p.offset >= start && p.offset + p.length <= start + len) {
                        leafBuf[p.offset] = buf.copyOfRange(
                            (p.offset - start).toInt(), (p.offset - start + p.length).toInt()
                        )
                    }
                }
                onProgress(Progress("directories", leafBuf.size, leafPtrs.size, budget.bytes))
            }
            for ((leafPtr, ids) in byLeaf) {
                val raw = leafBuf[leafPtr.offset] ?: continue
                val leaf = parseDirectory(decompress(raw, h.internalCompression))
                for (id in ids) {
                    val le = seek(leaf, id) ?: continue
                    if (le.runLength != 0L) resolved[id] = le
                }
            }
            onProgress(Progress("resolved", resolved.size, wanted.size, budget.bytes))
            if (resolved.isEmpty()) throw Aborted("no tiles in this area")

            // Output offsets are assigned in tile-ID order so the result is genuinely clustered.
            // Identical tiles (ocean, empty land) share one blob, exactly as the source does.
            val order = resolved.keys.toLongArray().also { it.sort() }
            val newOffset = LinkedHashMap<Long, Long>()   // source offset -> dest offset
            val blobLength = HashMap<Long, Long>()
            var bodyLength = 0L
            val entries = ArrayList<Entry>(order.size)
            for (id in order) {
                val src = resolved[id]!!
                val off = newOffset.getOrPut(src.offset) {
                    blobLength[src.offset] = src.length
                    val o = bodyLength; bodyLength += src.length; o
                }
                val prev = entries.lastOrNull()
                if (prev != null && prev.offset == off && prev.length == src.length &&
                    prev.tileId + prev.runLength == id
                ) {
                    entries[entries.size - 1] = prev.copy(runLength = prev.runLength + 1)
                } else {
                    entries.add(Entry(id, off, src.length, 1))
                }
            }

            val (rootBytes, leafBytes) = buildDirectories(entries, h.internalCompression)
            val rootOff = HEADER_BYTES.toLong()
            val metaOff = rootOff + rootBytes.size
            val leafOff = metaOff + metadata.size
            val dataOff = leafOff + leafBytes.size

            val header = ByteArray(HEADER_BYTES)
            "PMTiles".toByteArray().copyInto(header, 0)
            header[7] = 3
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(8, rootOff); putLong(16, rootBytes.size.toLong())
                putLong(24, metaOff); putLong(32, metadata.size.toLong())
                putLong(40, leafOff); putLong(48, leafBytes.size.toLong())
                putLong(56, dataOff); putLong(64, bodyLength)
                putLong(72, order.size.toLong())
                putLong(80, entries.size.toLong())
                putLong(88, newOffset.size.toLong())
                // The header bbox is a single non-wrapping rectangle by construction, and readers
                // use it to decide which tiles to even ask for. A wrapping region (the USA,
                // Russia, New Zealand, Fiji, Kiribati) cannot be expressed in it, and writing
                // west > east would have MapLibre cull almost everything. Widen to the full span
                // instead: it over-claims extent, which costs nothing, rather than under-claiming
                // it, which would render a blank map.
                val hw = if (west <= east) west else -180.0
                val he = if (west <= east) east else 180.0
                putInt(102, (hw * 1e7).toInt()); putInt(106, (south * 1e7).toInt())
                putInt(110, (he * 1e7).toInt()); putInt(114, (north * 1e7).toInt())
                putInt(119, ((hw + he) / 2 * 1e7).toInt())
                putInt(123, ((south + north) / 2 * 1e7).toInt())
            }
            header[96] = 1                                        // clustered
            header[97] = h.internalCompression.toByte()
            header[98] = h.tileCompression.toByte()
            header[99] = h.tileType.toByte()
            header[100] = minZ.toByte()
            header[101] = hiZ.toByte()
            header[118] = minZ.toByte()

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(dataOff + bodyLength)
                raf.seek(0); raf.write(header)
                raf.write(rootBytes); raf.write(metadata); raf.write(leafBytes)

                // Merge the blobs we need into as few ranges as the budget allows, then seek each
                // blob into its place. Gap tolerance grows until the request count fits — that is
                // the "5 % overfetch" knob, driven by politeness rather than by a constant.
                val blobs = newOffset.keys.toLongArray().also { it.sort() }
                var gap = 4096L
                var ranges = mergeRanges(blobs, blobLength, gap)
                while (ranges.size > maxRequests - budget.requests - 4 && gap < 8L * 1024 * 1024) {
                    gap *= 4
                    ranges = mergeRanges(blobs, blobLength, gap)
                }
                var done = 0
                for ((start, len) in ranges) {
                    val buf = range(url, h.dataOffset + start, len, budget, cancelled)
                    for (b in blobs) {
                        val bl = blobLength[b]!!
                        if (b >= start && b + bl <= start + len) {
                            raf.seek(dataOff + newOffset[b]!!)
                            raf.write(buf, (b - start).toInt(), bl.toInt())
                            done++
                        }
                    }
                    onProgress(Progress("tiles", done, blobs.size, budget.bytes))
                }
                // A short write means an archive full of zero-filled tiles, which MapLibre renders
                // as blank ground and `pmtiles show` reports as perfectly healthy — a silent
                // failure that looks exactly like "this area has no map". Refuse to produce one.
                if (done < blobs.size) {
                    throw Aborted("wrote $done of ${blobs.size} tiles; refusing a partial archive")
                }
                raf.fd.sync()
            }

            verify(tmp)
            if (!tmp.renameTo(dest)) throw Aborted("could not move archive into place")
            return Cost(budget.requests, budget.bytes, System.currentTimeMillis() - t0, bodyLength)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Prove an archive is readable **before** the renderer is allowed near it.
     *
     * This is not belt-and-braces; it is the only defence there is. MapLibre's `PMTilesFileSource`
     * parses archives on its own native thread and throws an **uncaught C++ exception** when it
     * meets something it cannot read — `std::runtime_error: unknown compression method` — which
     * calls `std::terminate` and aborts the whole process. There is no Kotlin exception to catch,
     * no try/catch that helps and no in-app recovery: one bad file crashes the Map tab on every
     * launch until it is deleted. Observed on device, from an archive whose tile bodies were
     * zero-filled by a writer race: the header, the directories and `pmtiles show` were all
     * perfectly happy, and the first tile MapLibre gunzipped killed the app.
     *
     * So every byte range the renderer can reach is checked here, on our thread, where an
     * exception is just an exception. Cost is one pass of 2-byte reads over the entry list.
     */
    fun verify(f: File) {
        val h = readLocalHeader(f) ?: throw Aborted("verify: not a readable PMTiles header")
        if (h.internalCompression !in 1..2) {
            throw Aborted("verify: internal compression ${h.internalCompression} is not none or gzip")
        }
        if (h.tileCompression !in 1..2) {
            throw Aborted("verify: tile compression ${h.tileCompression} is not none or gzip")
        }
        if (h.dataOffset + h.dataLength > f.length()) throw Aborted("verify: truncated file")

        RandomAccessFile(f, "r").use { raf ->
            fun read(at: Long, n: Int): ByteArray {
                val b = ByteArray(n)
                raf.seek(at)
                raf.readFully(b)
                return b
            }

            val root = runCatching {
                parseDirectory(decompress(read(h.rootOffset, h.rootLength.toInt()), h.internalCompression))
            }.getOrElse { throw Aborted("verify: root directory unreadable (${it.message})") }
            if (root.isEmpty()) throw Aborted("verify: empty root directory")

            val entries = ArrayList<Entry>(root.size)
            for (e in root) {
                if (e.runLength != 0L) { entries.add(e); continue }
                val leaf = runCatching {
                    parseDirectory(
                        decompress(read(h.leafOffset + e.offset, e.length.toInt()), h.internalCompression)
                    )
                }.getOrElse { throw Aborted("verify: leaf directory unreadable (${it.message})") }
                entries.addAll(leaf.filter { it.runLength != 0L })
            }
            if (entries.isEmpty()) throw Aborted("verify: no tile entries")

            // Every tile body must at least begin the way its declared compression says it does.
            // A zero-filled blob is exactly what this catches, and it is exactly what aborted the
            // process before this existed.
            for (e in entries) {
                if (e.length <= 0) throw Aborted("verify: zero-length tile at ${e.tileId}")
                if (h.dataOffset + e.offset + e.length > f.length()) {
                    throw Aborted("verify: tile ${e.tileId} points past the end of the file")
                }
                if (h.tileCompression == 2) {
                    val magic = read(h.dataOffset + e.offset, 2)
                    if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                        throw Aborted("verify: tile ${e.tileId} is not gzip as the header claims")
                    }
                }
            }

            // And one tile must decompress end to end, which is the thing the renderer will do.
            val probe = entries.first()
            runCatching {
                decompress(read(h.dataOffset + probe.offset, probe.length.toInt()), h.tileCompression)
            }.getOrElse { throw Aborted("verify: first tile does not decompress (${it.message})") }
        }
    }

    /** Work files live here, inside the archives directory but never listed as an archive. */
    const val WORK_DIR = ".work"

    /** Contiguous-ish source byte ranges, each at most [MAX_RANGE_BYTES]. */
    private fun mergeRanges(blobs: LongArray, lengths: Map<Long, Long>, gap: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for (b in blobs) {
            val len = lengths[b]!!
            val last = out.lastOrNull()
            if (last != null) {
                val end = last.first + last.second
                if (b - end <= gap && (b + len - last.first) <= MAX_RANGE_BYTES) {
                    out[out.size - 1] = last.first to (b + len - last.first)
                    continue
                }
            }
            out.add(b to len)
        }
        return out
    }

    /**
     * Root-only while it fits the spec's 16 KB recommendation, one level of leaves beyond that.
     * A country bbox at z9 can run to thousands of entries, so the leaf path is not theoretical.
     */
    private fun buildDirectories(entries: List<Entry>, compression: Int): Pair<ByteArray, ByteArray> {
        val flat = compress(serializeDirectory(entries), compression)
        if (flat.size <= ROOT_PROBE_BYTES) return flat to ByteArray(0)
        var per = 4096
        while (true) {
            val leaves = ByteArrayOutputStream()
            val rootEntries = ArrayList<Entry>()
            var at = 0L
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, min(i + per, entries.size))
                val c = compress(serializeDirectory(chunk), compression)
                rootEntries.add(Entry(chunk.first().tileId, at, c.size.toLong(), 0))
                leaves.write(c); at += c.size
                i += per
            }
            val root = compress(serializeDirectory(rootEntries), compression)
            if (root.size <= ROOT_PROBE_BYTES || per <= 64) return root to leaves.toByteArray()
            per /= 2
        }
    }

    /** Read just the header of a local archive — used to adopt files we did not download. */
    fun readLocalHeader(f: File): Header? = runCatching {
        f.inputStream().use { s ->
            val b = ByteArray(HEADER_BYTES)
            if (s.read(b) != HEADER_BYTES) return null
            parseHeader(b)
        }
    }.getOrNull()

    /** Extent, for the storage list. Handles the antimeridian case the same way the tiler does. */
    fun bboxLabel(west: Double, south: Double, east: Double, north: Double): String {
        val lon = if (west <= east) east - west else (180.0 - west) + (east + 180.0)
        return String.format(java.util.Locale.US, "%.1f° × %.1f°", lon, north - south)
    }
}
