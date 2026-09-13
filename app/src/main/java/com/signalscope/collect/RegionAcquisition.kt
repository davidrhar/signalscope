package com.signalscope.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.signalscope.store.RegionBoxes
import com.signalscope.store.RegionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor

/**
 * The basemap feeds itself.
 *
 * Before this existed the only way to get a `.pmtiles` archive onto the phone was `adb push`,
 * which is not a distribution path — so the map was blank for every real user on first run. This
 * closes that, in three layers, and the layering is the design:
 *
 * | Layer | Where it comes from | When | Cost |
 * |---|---|---|---|
 * | World z0–4 | **bundled in the APK** | first run, offline | 0 bytes of network, ever |
 * | Country z0–N | extracted over HTTP | country confirmed for 10 min | single-digit MB |
 * | Local z10–14 | extracted over HTTP | after dwelling in a 0.5° cell | tens of MB |
 *
 * ## Why the world tier is bundled rather than downloaded
 *
 * `map-stack.md` argues the case for offline better than this comment can: *"This app is used
 * precisely where the network does not work."* A first run in a dead spot is not a hypothetical
 * for a tool whose whole purpose is dead spots. So the floor — a recognisable map of the entire
 * planet — is in the APK and needs no network, no permission and no server. Everything the
 * network adds is detail on top of a map that already works.
 *
 * ## Where the detail tiers come from, and why not from the obvious place
 *
 * Protomaps' daily planet builds at `build.protomaps.com` are the obvious source and are the
 * wrong one. Their own documentation says: *"URLs may change and hotlinking to these downloads
 * are discouraged. Instead, you should copy the tileset to your own Cloud Storage."* The bucket
 * also keeps only a week of builds, so a URL compiled into an APK stops resolving within days,
 * and its responses carry `cf-cache-status: DYNAMIC` — every range read a shipped app made would
 * be uncached origin egress that Protomaps pay for. That is precisely the mistake `map-stack.md`
 * identifies with `tile.openstreetmap.org`, and it would be no better for being made politely.
 *
 * [PLANET_URL] therefore points at the **Source Cooperative mirror** that Protomaps themselves
 * link to: a public open-data distribution platform run by Radiant Earth, whose purpose is
 * serving open geospatial data to the public, at a stable undated URL. Reading 10 MB of byte
 * ranges out of a 134 GB archive is the exact access pattern PMTiles and object-store hosting
 * were designed for.
 *
 * It is still someone else's bandwidth, so: the archive is fetched **once per region and never
 * again**, the client identifies itself in `User-Agent`, the request count per region is bounded
 * and merged into as few ranges as possible, 429 and 5xx back off exponentially and then give up,
 * and nothing is fetched at all unless the phone is on unmetered Wi-Fi. At any real install base
 * the right answer is to mirror the planet ourselves and change one constant — that is the whole
 * change, and it is written down in `docs/region-acquisition.md`.
 *
 * ## Unattended, gated on conditions rather than on prompts
 *
 * `map-regions.md`: the user is *informed, never asked*. Nothing here prompts. A download that
 * cannot run is **queued, not failed**, and the Map tab says what it is waiting for. Gating on
 * unmetered transport is what makes the silence safe: the failure a prompt would guard against —
 * an unexpected 40 MB on a foreign cellular plan — is structurally excluded rather than delegated
 * to the user.
 */
object RegionAcquisition {

    /**
     * The Protomaps v4 planet, via the Source Cooperative mirror. See the class comment for why
     * this and not `build.protomaps.com`. One constant: point it at your own bucket at scale.
     */
    const val PLANET_URL = "https://data.source.coop/protomaps/openstreetmap/v4.pmtiles"

    /** `map-regions.md`: "a new country must be observed continuously for 10 minutes". */
    const val COUNTRY_HYSTERESIS_MS = 10 * 60 * 1000L

    /** Local detail follows real presence, not a single passing fix. */
    const val LOCAL_DWELL_MS = 5 * 60 * 1000L

    /** Local detail is cut on a fixed grid so overlapping visits never re-download anything. */
    const val LOCAL_CELL_DEG = 0.5

    /** Battery floor from `map-regions.md`; above this, or charging, is enough. */
    const val BATTERY_FLOOR = 20

    /** Planning estimate only, from measured extracts (~26 KB/tile at z10–14). */
    const val BYTES_PER_TILE = 30_000L

    private const val WORLD_ASSET = "region-world.pmtiles"
    private const val WORLD_FILE = "world.pmtiles"
    private const val CHANNEL = "regions"

    // ---------------------------------------------------------------- state

    data class Job(
        val id: String,
        val kind: RegionStore.Kind,
        val country: String?,
        val label: String,
        val file: String,
        val west: Double, val south: Double, val east: Double, val north: Double,
        val minZoom: Int, val maxZoom: Int,
        val estTiles: Long
    ) {
        val estBytes: Long get() = estTiles * BYTES_PER_TILE
    }

    data class Conditions(
        val unmetered: Boolean,
        val transport: String,
        val batteryPct: Int,
        val charging: Boolean,
        val freeBytes: Long
    ) {
        /** Null means go. Anything else is a reason to wait, never a reason to ask. */
        fun blocking(need: Long): String? = when {
            !unmetered -> "waiting for unmetered Wi-Fi — on $transport now"
            batteryPct in 0 until BATTERY_FLOOR && !charging ->
                "waiting for charge — battery $batteryPct%, floor $BATTERY_FLOOR%"
            freeBytes < RegionStore.RESERVE_BYTES + need ->
                "waiting for storage — ${freeBytes / 1_000_000} MB free, needs " +
                    "${(RegionStore.RESERVE_BYTES + need) / 1_000_000} MB"
            else -> null
        }
    }

    data class Ui(
        val running: Boolean = false,
        val source: String = PLANET_URL,
        val networkCountry: String? = null,
        val confirmedCountry: String? = null,
        val candidate: String? = null,
        val candidateHeldMs: Long = 0,
        val queue: List<Job> = emptyList(),
        val active: Job? = null,
        val progress: RegionPmtiles.Progress? = null,
        val blocked: String? = null,
        val conditions: Conditions? = null,
        val lastResult: String? = null,
        val lastError: String? = null,
        val worldSeeded: Boolean = false,
        /** Bumped whenever the set of archives changes, so the map knows to restyle. */
        val revision: Int = 0
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Exactly one [tick] at a time, and therefore exactly one extract at a time.
     *
     * This is not defensive tidiness; its absence was a process-killing bug. The 30-second loop and
     * the manual "add now" controls both call [tick], and an extract takes longer than 30 seconds —
     * so two of them ran concurrently on the same job, into the same work file. One deleted the
     * other's file mid-write and the survivor renamed a half-written archive into place. Its header
     * and directories were intact and `pmtiles show` reported it healthy; its tile bodies were
     * zero-filled, and MapLibre's native PMTiles thread aborted the whole process on the first one
     * it tried to gunzip. `tryLock` rather than `lock`: a tick that arrives during a download has
     * nothing to add, so it is dropped rather than queued behind it.
     */
    private val gate = Mutex()
    private var started = false

    /** Written from the UI thread, read on the download thread — so it must actually be seen. */
    @Volatile private var cancelActive = false

    private var candidateIso: String? = null
    private var candidateSince = 0L
    private var localCell: String? = null
    private var localSince = 0L
    private val queue = ArrayList<Job>()
    private val failed = HashMap<String, Long>()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Idempotent. Runs for the life of the process, which `CollectorService` keeps alive; it is
     * started from the Map tab because this change does not own `MainActivity`. One call from
     * `onCreate` would start it at app launch instead, and nothing here depends on which.
     */
    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        io.launch {
            seedWorld(app)
            while (true) {
                runCatching { tick(app) }
                delay(30_000)
            }
        }
    }

    /** Stop the current download. The partial archive is discarded; the job stays queued. */
    fun cancel() { cancelActive = true }

    fun requeue(ctx: Context) {
        io.launch {
            failed.clear()
            runCatching { tick(ctx.applicationContext) }
        }
    }

    /**
     * Queue this country now, without waiting out the hysteresis.
     *
     * The 10-minute rule exists to damp *automatic* triggering at a border, where the serving
     * network flaps and the phone cannot tell a crossing from a wobble. An explicit tap carries
     * no such ambiguity, so it is not subject to it. The conditions gate still applies — this
     * queues a download, it does not start one on cellular.
     */
    fun forceCountry(ctx: Context) {
        val app = ctx.applicationContext
        io.launch {
            val iso = networkCountryIso(app) ?: return@launch
            candidateIso = iso
            candidateSince = System.currentTimeMillis() - COUNTRY_HYSTERESIS_MS
            failed.clear()
            runCatching { tick(app) }
        }
    }

    /** Same, for the 0.5° cell the phone is in right now. */
    fun forceLocal(ctx: Context) {
        val app = ctx.applicationContext
        io.launch {
            val fix = MapLocationCollector.state.value
            val lat = fix.lat ?: return@launch
            val lon = fix.lng ?: return@launch
            localCell = cellIdFor(lat, lon)
            localSince = System.currentTimeMillis() - LOCAL_DWELL_MS
            failed.clear()
            runCatching { tick(app) }
        }
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun tick(ctx: Context) {
        if (!gate.tryLock()) return
        try { tickLocked(ctx) } finally { gate.unlock() }
    }

    private suspend fun tickLocked(ctx: Context) {
        val iso = networkCountryIso(ctx)
        trackCountry(ctx, iso)
        trackLocal(ctx)

        val cond = conditions(ctx)
        val next = queue.firstOrNull { (failed[it.id] ?: 0) < System.currentTimeMillis() }
        val block = next?.let { cond.blocking(it.estBytes) }

        _state.value = _state.value.copy(
            running = true, networkCountry = iso,
            candidate = candidateIso,
            candidateHeldMs = if (candidateIso != null) System.currentTimeMillis() - candidateSince else 0,
            queue = queue.toList(), conditions = cond, blocked = block
        )

        if (next != null && block == null) acquire(ctx, next)
    }

    // ---------------------------------------------------------------- country detection

    /**
     * `getNetworkCountryIso()` on the **data** subscription.
     *
     * Not `getSimCountryIso()`, which reports the SIM's *home* country. `map-regions.md` records
     * why that is not a preference: the reference device's slot 1 holds a foreign SIM that has
     * never once been used in its home country, so the SIM country is permanently wrong and
     * keying the map off it would download a country the phone has never been in and never
     * download the one it is in. And not a bare `TelephonyManager`, which silently serves the
     * default subscription — `multi-sim.md` is explicit that the data sub is authoritative here.
     */
    fun networkCountryIso(ctx: Context): String? = runCatching {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return@runCatching null
        val base = ctx.getSystemService(TelephonyManager::class.java) ?: return@runCatching null
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val tm = if (dataSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base
        else base.createForSubscriptionId(dataSub)
        tm.networkCountryIso?.uppercase()?.takeIf { it.length == 2 }
    }.getOrNull()

    private fun trackCountry(ctx: Context, iso: String?) {
        val now = System.currentTimeMillis()
        if (iso == null) return
        if (iso != candidateIso) { candidateIso = iso; candidateSince = now; return }
        if (now - candidateSince < COUNTRY_HYSTERESIS_MS) return

        val confirmed = _state.value.confirmedCountry
        if (confirmed != iso) _state.value = _state.value.copy(confirmedCountry = iso)

        val box = RegionBoxes.of(iso) ?: return
        val id = "country-$iso"
        if (RegionStore.has(ctx, id) || queue.any { it.id == id }) return
        val z = box.zoomForBudget()
        queue.add(
            Job(
                id = id, kind = RegionStore.Kind.COUNTRY, country = iso,
                label = "${box.name} · context z0–$z",
                file = "country-${iso.lowercase()}.pmtiles",
                west = box.west, south = box.south, east = box.east, north = box.north,
                minZoom = 0, maxZoom = z, estTiles = box.tilesTo(z)
            )
        )
    }

    // ---------------------------------------------------------------- local detail

    /**
     * `map-regions.md`: *"detail arrives only where you have actually been"*. The trigger is the
     * map's own position fix — the same fix that produces measurement bins — so the detail tier is
     * exactly coextensive with the ground the app has data for.
     *
     * Cells are a fixed 0.5° grid rather than a box centred on the user, so walking 200 m never
     * re-downloads an overlapping region, and a cell can be named, listed and deleted.
     */
    private fun trackLocal(ctx: Context) {
        val fix = MapLocationCollector.state.value
        val lat = fix.lat ?: return
        val lon = fix.lng ?: return
        val now = System.currentTimeMillis()
        val cell = cellIdFor(lat, lon)
        if (cell != localCell) { localCell = cell; localSince = now; return }
        if (now - localSince < LOCAL_DWELL_MS) return

        val id = "local-$cell"
        if (RegionStore.has(ctx, id) || queue.any { it.id == id }) return
        val (w, s, e, n) = cellBox(lat, lon)
        val iso = _state.value.confirmedCountry
        queue.add(
            Job(
                id = id, kind = RegionStore.Kind.LOCAL, country = iso,
                label = "Local detail z10–14 · ${cellLabel(lat, lon)}",
                file = "$id.pmtiles",
                west = w, south = s, east = e, north = n,
                minZoom = 10, maxZoom = 14,
                estTiles = (10..14).sumOf { RegionPmtiles.tileCount(w, s, e, n, it) }
            )
        )
    }

    private fun cellIdFor(lat: Double, lon: Double): String {
        val la = floor(lat / LOCAL_CELL_DEG).toInt()
        val lo = floor(lon / LOCAL_CELL_DEG).toInt()
        return "${la}_$lo"
    }

    private data class Box4(val w: Double, val s: Double, val e: Double, val n: Double)

    private fun cellBox(lat: Double, lon: Double): Box4 {
        val s = floor(lat / LOCAL_CELL_DEG) * LOCAL_CELL_DEG
        val w = floor(lon / LOCAL_CELL_DEG) * LOCAL_CELL_DEG
        return Box4(w - 0.02, s - 0.02, w + LOCAL_CELL_DEG + 0.02, s + LOCAL_CELL_DEG + 0.02)
    }

    private fun cellLabel(lat: Double, lon: Double): String {
        val s = floor(lat / LOCAL_CELL_DEG) * LOCAL_CELL_DEG
        val w = floor(lon / LOCAL_CELL_DEG) * LOCAL_CELL_DEG
        return String.format(java.util.Locale.US, "%.1f, %.1f", s, w)
    }

    // ---------------------------------------------------------------- conditions

    fun conditions(ctx: Context): Conditions {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val transport = when {
            caps == null -> "no network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        // NOT_METERED is the property that matters; the transport check keeps a metered hotspot
        // that lies about itself from being treated as home Wi-Fi.
        val unmetered = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

        val batt = runCatching {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            pct to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
        }.getOrDefault(-1 to false)

        return Conditions(
            unmetered = unmetered, transport = transport,
            batteryPct = batt.first, charging = batt.second,
            freeBytes = RegionStore.space(ctx).freeBytes
        )
    }

    // ---------------------------------------------------------------- acquisition

    private suspend fun acquire(ctx: Context, job: Job) {
        cancelActive = false
        // Off the queue before the first byte, back on it if this fails. Leaving it queued while
        // it runs is what let a second caller start the same extract.
        queue.removeAll { it.id == job.id }
        _state.value = _state.value.copy(
            active = job, progress = null, lastError = null, queue = queue.toList()
        )
        val dest = RegionStore.fileFor(ctx, job.file)
        try {
            val cost = withContext(Dispatchers.IO) {
                RegionPmtiles.extract(
                    url = PLANET_URL, dest = dest,
                    west = job.west, south = job.south, east = job.east, north = job.north,
                    minZ = job.minZoom, maxZ = job.maxZoom,
                    cancelled = { cancelActive },
                    onProgress = { p -> _state.value = _state.value.copy(progress = p) }
                )
            }
            RegionStore.put(
                ctx,
                RegionStore.Region(
                    id = job.id, kind = job.kind, country = job.country, label = job.label,
                    file = job.file,
                    west = job.west, south = job.south, east = job.east, north = job.north,
                    minZoom = job.minZoom, maxZoom = job.maxZoom,
                    bytes = dest.length(), acquiredAt = System.currentTimeMillis(),
                    requests = cost.requests, transferred = cost.transferred, millis = cost.millis
                )
            )
            val mb = dest.length() / 1_000_000
            val summary = "${job.label} · $mb MB · ${cost.requests} requests · " +
                "${cost.transferred / 1_000_000} MB transferred · ${cost.millis / 1000}s · " +
                "${cost.overfetchPct}% overfetch"
            _state.value = _state.value.copy(
                active = null, progress = null, lastResult = summary,
                revision = _state.value.revision + 1
            )
            notifyOnce(ctx, job, mb)
        } catch (e: Throwable) {
            // Fail soft: requeue, back off, say what happened, change nothing else. Nothing
            // half-written is left behind — RegionPmtiles deletes its work file on any throw, and
            // it never had a name the renderer could reach in the first place.
            if (queue.none { it.id == job.id }) queue.add(job)
            failed[job.id] = System.currentTimeMillis() + 15 * 60 * 1000L
            _state.value = _state.value.copy(
                active = null, progress = null, queue = queue.toList(),
                lastError = "${job.label}: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    // ---------------------------------------------------------------- world seed

    /**
     * Copy the bundled world archive out of the APK on first run. No network, no permission, no
     * failure mode worth reporting beyond the one line in the UI — if this cannot run the map
     * degrades to bins on flat ground, which is the state that existed before.
     */
    private fun seedWorld(ctx: Context) {
        // If the world archive was ever quarantined, it is not re-seeded: re-copying the file that
        // killed the app would be a crash loop with extra steps. Losing the world tier costs
        // orientation outside downloaded regions; the alternative costs the Map tab entirely.
        RegionStore.worldBlocked(ctx)?.let { why ->
            _state.value = _state.value.copy(
                lastError = "bundled world map is quarantined: $why"
            )
            return
        }
        val dest = RegionStore.fileFor(ctx, WORLD_FILE)
        if (dest.exists() && dest.length() > 1024) {
            if (!RegionStore.has(ctx, "world")) registerWorld(ctx, dest)
            _state.value = _state.value.copy(worldSeeded = true)
            return
        }
        runCatching {
            // Same rule as a downloaded region: written somewhere the renderer cannot see, proved
            // readable, then moved into place. A truncated copy (storage full mid-write) would
            // otherwise be a bundled crash.
            val work = File(dest.parentFile, RegionPmtiles.WORK_DIR).apply { mkdirs() }
            val tmp = File(work, "$WORLD_FILE.part")
            ctx.assets.open(WORLD_ASSET).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            RegionPmtiles.verify(tmp)
            tmp.renameTo(dest)
            registerWorld(ctx, dest)
            _state.value = _state.value.copy(
                worldSeeded = true, revision = _state.value.revision + 1
            )
        }.onFailure {
            _state.value = _state.value.copy(
                lastError = "bundled world map could not be unpacked: ${it.message}"
            )
        }
    }

    private fun registerWorld(ctx: Context, dest: File) {
        val h = RegionPmtiles.readLocalHeader(dest)
        RegionStore.put(
            ctx,
            RegionStore.Region(
                id = "world", kind = RegionStore.Kind.WORLD, country = null,
                label = "World z${h?.minZoom ?: 0}–${h?.maxZoom ?: 4} · bundled",
                file = WORLD_FILE,
                west = h?.west ?: -180.0, south = h?.south ?: -85.05,
                east = h?.east ?: 180.0, north = h?.north ?: 85.05,
                minZoom = h?.minZoom ?: 0, maxZoom = h?.maxZoom ?: 4,
                bytes = dest.length(), acquiredAt = System.currentTimeMillis()
            )
        )
    }

    // ---------------------------------------------------------------- notification

    /** After the fact, once, never a question. `map-regions.md` is explicit about this. */
    private fun notifyOnce(ctx: Context, job: Job, mb: Long) {
        runCatching {
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Map regions", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Told after a map region has been added." }
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Map added: ${job.country?.let { RegionBoxes.name(it) } ?: job.label}")
                .setContentText("${job.label} · $mb MB · downloaded on Wi-Fi")
                .setAutoCancel(true)
                .build()
            nm.notify(job.id.hashCode(), n)
        }
    }
}
