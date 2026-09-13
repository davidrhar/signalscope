package com.signalscope.store

import android.content.Context
import android.os.StatFs
import com.signalscope.collect.RegionPmtiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What basemap the phone actually holds, and the promise that it keeps it.
 *
 * `map-regions.md`: *"Nothing is auto-evicted."* This class has no eviction path at all — the only
 * way a region leaves is [delete], which is reachable only from a button the user presses. The
 * asymmetry it states is worth repeating where the code lives: **a deleted map region can be
 * re-downloaded; a deleted measurement history is gone.** So storage pressure is resolved against
 * the basemap first, by asking, and the measurement store is never touched here.
 *
 * The registry is a small JSON sidecar next to the archives rather than a Room table, because a
 * Room table means a schema version bump and a schema version bump discards collected samples on
 * upgrade. The archives are the source of truth regardless: [scan] adopts any `.pmtiles` file it
 * finds by reading its 127-byte header, so a side-loaded archive (or one whose registry entry was
 * lost) still renders and still shows up in the storage list.
 */
object RegionStore {

    enum class Kind { WORLD, COUNTRY, LOCAL, ADOPTED }

    data class Region(
        val id: String,
        val kind: Kind,
        val country: String?,
        val label: String,
        val file: String,
        val west: Double, val south: Double, val east: Double, val north: Double,
        val minZoom: Int, val maxZoom: Int,
        val bytes: Long,
        val acquiredAt: Long,
        val requests: Int = 0,
        val transferred: Long = 0,
        val millis: Long = 0
    ) {
        val extent: String get() = RegionPmtiles.bboxLabel(west, south, east, north)

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < south || lat > north) return false
            return if (west <= east) lon in west..east else lon >= west || lon <= east
        }

        /** Draw order: coarse first, so finer archives paint over them where they exist. */
        val drawRank: Int get() = when (kind) {
            Kind.WORLD -> 0
            Kind.COUNTRY -> 1
            Kind.ADOPTED -> 2
            Kind.LOCAL -> 3
        }
    }

    fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir("maps") ?: File(ctx.filesDir, "maps")).apply { mkdirs() }

    private fun registry(ctx: Context) = File(dir(ctx), "regions.json")

    // ---------------------------------------------------------------- read

    /**
     * Every archive present, in draw order. Registry entries whose file has vanished are dropped;
     * archives with no registry entry are adopted from their own header.
     */
    fun scan(ctx: Context): List<Region> {
        val d = dir(ctx)
        val known = readRegistry(ctx).associateBy { it.file }
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") } ?: emptyArray()
        val out = ArrayList<Region>(files.size)
        for (f in files.sortedBy { it.name }) {
            val rec = known[f.name]
            if (rec != null) { out.add(rec.copy(bytes = f.length())); continue }
            val h = RegionPmtiles.readLocalHeader(f) ?: continue
            out.add(
                Region(
                    id = "adopted-${f.name}", kind = Kind.ADOPTED, country = null,
                    label = f.name.removeSuffix(".pmtiles"),
                    file = f.name,
                    west = h.west, south = h.south, east = h.east, north = h.north,
                    minZoom = h.minZoom, maxZoom = h.maxZoom,
                    bytes = f.length(), acquiredAt = f.lastModified()
                )
            )
        }
        return out.sortedWith(compareBy({ it.drawRank }, { it.minZoom }))
    }

    // ------------------------------------------------------------- renderable vs merely present

    /**
     * Verification results, keyed by name+length+mtime so a file that changes is re-checked and
     * nothing else is. An empty string means "checked, good"; anything else is the reason it is
     * not renderable.
     */
    private val verified = HashMap<String, String>()

    /** Why a present archive is not being rendered. Surfaced rather than silently skipped. */
    val rejections: Map<String, String> get() = synchronized(verified) {
        verified.entries.filter { it.value.isNotEmpty() }
            .associate { it.key.substringBefore(':') to it.value }
    }

    /**
     * The archives it is safe to hand to MapLibre.
     *
     * Not the same list as [scan]. MapLibre's PMTiles source aborts the **process** on an archive
     * it cannot parse — see [RegionPmtiles.verify] — so every file is proved readable on a thread
     * where a failure is survivable before the renderer is allowed to open it. That covers
     * archives this app wrote, archives a previous version wrote, and archives someone pushed
     * over adb, which is the case that used to be trusted implicitly.
     */
    fun renderable(ctx: Context): List<Region> = scan(ctx).filter { r ->
        val f = File(dir(ctx), r.file)
        val key = "${r.file}:${f.length()}:${f.lastModified()}"
        val cached = synchronized(verified) { verified[key] }
        if (cached != null) return@filter cached.isEmpty()
        val err = runCatching { RegionPmtiles.verify(f) }.exceptionOrNull()
            ?.let { it.message ?: it.javaClass.simpleName } ?: ""
        synchronized(verified) { verified[key] = err }
        err.isEmpty()
    }

    fun byId(ctx: Context, id: String): Region? = scan(ctx).firstOrNull { it.id == id }

    fun has(ctx: Context, id: String): Boolean = scan(ctx).any { it.id == id }

    fun totalBytes(ctx: Context): Long = scan(ctx).sumOf { it.bytes }

    /** Best zoom actually available at this position, or -1. Renderable only — what is on disk
     *  but cannot be shown is not detail the user has. */
    fun detailAt(ctx: Context, lat: Double, lon: Double): Int =
        renderable(ctx).filter { it.contains(lat, lon) }.maxOfOrNull { it.maxZoom } ?: -1

    // ---------------------------------------------------------------- write

    @Synchronized
    fun put(ctx: Context, r: Region) {
        val all = readRegistry(ctx).filterNot { it.id == r.id || it.file == r.file } + r
        writeRegistry(ctx, all)
    }

    /**
     * The only removal path in the app, and it is reached only from a user action. Returns the
     * bytes reclaimed.
     */
    @Synchronized
    fun delete(ctx: Context, id: String): Long {
        val r = scan(ctx).firstOrNull { it.id == id } ?: return 0
        val f = File(dir(ctx), r.file)
        val n = f.length()
        f.delete()
        writeRegistry(ctx, readRegistry(ctx).filterNot { it.id == id })
        return n
    }

    fun fileFor(ctx: Context, name: String) = File(dir(ctx), name)

    // ---------------------------------------------------------------- crash loop guard

    /**
     * The renderer can still kill us, and that must not be permanent.
     *
     * [RegionPmtiles.verify] runs before any archive is exposed, but it proves what *we* can read,
     * not what MapLibre can — a future schema quirk, a corrupt block past our sampling, a native
     * bug. If MapLibre aborts the process, the same archives are loaded on next launch and the
     * Map tab is dead forever. So the names of the files about to be handed to the renderer are
     * written down first and erased once a frame has actually rendered. Finding that note still
     * there at startup means the last attempt did not survive, and those files are moved aside.
     *
     * A false positive costs a re-download of a replaceable file. A false negative costs the user
     * their Map tab permanently. The asymmetry decides it.
     */
    private fun opening(ctx: Context) = File(dir(ctx), ".opening")

    fun markOpening(ctx: Context, files: List<String>) {
        runCatching { opening(ctx).writeText(files.joinToString("\n")) }
    }

    fun clearOpening(ctx: Context) { runCatching { opening(ctx).delete() } }

    /** Run once per process, before any style is built. Returns what it moved aside. */
    fun sweepCrash(ctx: Context): List<String> {
        val f = opening(ctx)
        if (!f.exists()) return emptyList()
        val names = runCatching { f.readText().lines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        f.delete()
        return names.mapNotNull { quarantine(ctx, it, "the app did not survive opening it") }
    }

    /** Quarantine directory: inside the archives directory, never listed as an archive. */
    private fun quarantineDir(ctx: Context) = File(dir(ctx), ".quarantine").apply { mkdirs() }

    /**
     * Move an archive out of reach and leave a note saying why. Never deletes: the file may be the
     * only copy of something, and the user is the one who decides what is thrown away.
     */
    fun quarantine(ctx: Context, name: String, why: String): String? {
        val src = File(dir(ctx), name)
        if (!src.exists()) return null
        val dst = File(quarantineDir(ctx), name)
        dst.delete()
        if (!src.renameTo(dst)) return null
        runCatching { File(quarantineDir(ctx), "$name.why").writeText(why) }
        writeRegistry(ctx, readRegistry(ctx).filterNot { it.file == name })
        // The bundled world archive is re-seeded from the APK whenever it is missing, so without
        // this it would come straight back and crash again. Blocking it costs the world tier and
        // keeps the app usable, which is the right way round.
        if (name == "world.pmtiles") {
            runCatching { File(dir(ctx), ".world-blocked").writeText(why) }
        }
        return name
    }

    fun worldBlocked(ctx: Context): String? =
        File(dir(ctx), ".world-blocked").takeIf { it.exists() }?.let {
            runCatching { it.readText() }.getOrDefault("blocked")
        }

    fun quarantined(ctx: Context): List<Pair<String, String>> =
        (quarantineDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") } ?: emptyArray())
            .map { f ->
                f.name to (runCatching {
                    File(f.parentFile, f.name + ".why").readText()
                }.getOrDefault("unknown"))
            }

    // ---------------------------------------------------------------- storage

    data class Space(val freeBytes: Long, val usedByMaps: Long) {
        val freeMb: Long get() = freeBytes / 1_000_000
        /** Headroom the app keeps clear so measurements never lose a write to a basemap. */
        val healthy: Boolean get() = freeBytes > RESERVE_BYTES
    }

    /**
     * Free space on the volume the archives live on. The reserve exists because of the asymmetry
     * above: the basemap must never be the reason a measurement cannot be written.
     */
    const val RESERVE_BYTES = 400L * 1024 * 1024

    fun space(ctx: Context): Space {
        val free = runCatching {
            val s = StatFs(dir(ctx).absolutePath)
            s.availableBlocksLong * s.blockSizeLong
        }.getOrDefault(0L)
        return Space(free, totalBytes(ctx))
    }

    /**
     * What we would suggest deleting if the user asked — never what we will delete. Oldest
     * non-world region first; the world archive is the floor and is never suggested.
     */
    fun suggestion(ctx: Context): Region? =
        scan(ctx).filter { it.kind != Kind.WORLD }.minByOrNull { it.acquiredAt }

    // ---------------------------------------------------------------- json

    private fun readRegistry(ctx: Context): List<Region> = runCatching {
        val f = registry(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Region(
                    id = o.getString("id"),
                    kind = Kind.valueOf(o.getString("kind")),
                    country = o.optString("country").takeIf { it.isNotEmpty() },
                    label = o.getString("label"),
                    file = o.getString("file"),
                    west = o.getDouble("w"), south = o.getDouble("s"),
                    east = o.getDouble("e"), north = o.getDouble("n"),
                    minZoom = o.getInt("minz"), maxZoom = o.getInt("maxz"),
                    bytes = o.optLong("bytes"), acquiredAt = o.optLong("at"),
                    requests = o.optInt("reqs"), transferred = o.optLong("tx"),
                    millis = o.optLong("ms")
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun writeRegistry(ctx: Context, all: List<Region>) {
        runCatching {
            val arr = JSONArray()
            for (r in all) arr.put(
                JSONObject().apply {
                    put("id", r.id); put("kind", r.kind.name)
                    put("country", r.country ?: ""); put("label", r.label); put("file", r.file)
                    put("w", r.west); put("s", r.south); put("e", r.east); put("n", r.north)
                    put("minz", r.minZoom); put("maxz", r.maxZoom)
                    put("bytes", r.bytes); put("at", r.acquiredAt)
                    put("reqs", r.requests); put("tx", r.transferred); put("ms", r.millis)
                }
            )
            registry(ctx).writeText(arr.toString())
        }
    }
}
