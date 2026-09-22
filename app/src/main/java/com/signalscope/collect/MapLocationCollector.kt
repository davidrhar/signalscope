package com.signalscope.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.signalscope.store.MapFix
import com.signalscope.store.Db
import com.signalscope.store.MapHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Position for the map, binned at write time.
 *
 * ## Power
 *
 * Location is the dominant power cost in this app: GNSS measured 38 mA against a collector
 * baseline of roughly 0.6 %/day. Three decisions follow, and none of them is negotiable:
 *
 *  - **Platform fused provider** (`LocationManager.FUSED_PROVIDER`, API 31+), at
 *    `QUALITY_BALANCED_POWER_ACCURACY`. That is the provider that answers from Wi-Fi and cell
 *    where it can and only reaches for GNSS when it must. It needs no Play Services dependency,
 *    which matters here because none is on the build file and none may be added.
 *  - **No GNSS duty-cycling.** Stopping and restarting the GPS engine pays the cold-start
 *    acquisition cost repeatedly and is worse than leaving a balanced request open.
 *  - **Gated, not foreground-only.** This used to run only while the Map tab was on screen, on
 *    the reasoning that a background location request is a power decision which should be made
 *    explicitly rather than inherited from a map screen. The reasoning was right; the conclusion
 *    was wrong, and the 2026-09-12 excursion showed what it cost. Twenty-five minutes of real
 *    off-Wi-Fi use recorded **zero fixes and zero map bins**, because the app was backgrounded
 *    the whole time -- so the map cannot grow as the phone travels, which is a stated goal, and
 *    movement cannot be separated from network churn, which makes the cell-change rate
 *    uninterpretable.
 *
 *    So the decision is now made explicitly, in [CollectorService], by a policy that asserts
 *    location only while it is worth paying for: when cellular holds the default route, or an
 *    excursion is open. Parked on Wi-Fi at home the position is static and a fix buys nothing,
 *    so nothing is requested. Note this needs no `ACCESS_BACKGROUND_LOCATION` grant and must not
 *    acquire one -- a service running as `foregroundServiceType="location"`, which this app's
 *    collector already is, is entitled to updates with the UI in the background.
 *
 *    [start] and [stop] are idempotent and synchronized, so the Map tab and the service can both
 *    drive them without coordinating.
 *
 * ## Privacy
 *
 * `data-model.md` §5 — no raw coordinate is written, ever. The fix is converted to a bin before
 * it touches storage, at the resolution its own accuracy justifies, and above the accuracy
 * ceiling nothing is stored at all. The `Location` object is not retained past this function.
 *
 * ## Time
 *
 * A fix is stamped with when it was *measured* -- `Location.getElapsedRealtimeNanos()` -- never
 * with when it reached us. Until 2026-09-13 every fix was stamped
 * `SystemClock.elapsedRealtimeNanos()` at delivery, and the cached `getLastKnownLocation` read at
 * start-up is delivered instantly however old it is: a position from hours earlier was recorded
 * as where the phone was at the moment the app started, and every radio sample near that moment
 * was painted onto it. The map joins samples to fixes by this timestamp, so a wrong stamp is a
 * wrong place. Rows written before the change are not restamped; the delivery time is all they
 * kept.
 */
object MapLocationCollector {

    private const val TAG = "MapLoc"

    /** Balanced, not high accuracy. ~20 s is ample for a 65-174 m bin at walking pace. */
    const val INTERVAL_MS = 20_000L

    /**
     * No distance filter. It was 15 m, which meant a phone standing still received no fixes at
     * all -- the map then had no evidence the phone was still anywhere, and samples taken while
     * stationary fell outside every match window and went unlocated. Stillness has to be recorded
     * to be used. The filter is applied by the platform at delivery, after the provider has
     * already computed the fix at [INTERVAL_MS], so removing it costs a callback and at most one
     * row a minute ([MIN_WRITE_GAP_MS]), not an extra location computation.
     */
    private const val MIN_DISTANCE_M = 0f

    /**
     * Write a row on a bin change, or once a minute, whichever comes first.
     *
     * On a bin change the last unwritten fix in the OLD bin is written first. That makes "two
     * consecutive rows in the same bin" mean "every fix delivered between them was in that bin",
     * which is the rule `MapBinBuilder.Locator` uses to place samples between fixes without
     * needing a speed -- and most fused fixes carry none.
     */
    private const val MIN_WRITE_GAP_MS = 60_000L

    /**
     * Oldest a fix may be, at delivery, and still be stored.
     *
     * 60 s -- three request intervals. It exists for `getLastKnownLocation`, which answers from a
     * cache with no age limit: while updates flow the provider refreshes within [INTERVAL_MS], so
     * a cached fix older than a minute predates the provider tracking this phone at all, and at
     * 20 m/s it could be over a kilometre from here. Stamping it with its own time stops it lying
     * about *when*, but it would still be a row that places nothing taken now and shows the user a
     * "current" bin that may not be current. A fresh fix follows within one interval anyway. The
     * same limit applies to live deliveries, where it can only catch a stale batch.
     */
    const val MAX_FIX_AGE_MS = 60_000L

    /** A fix timestamped further than this in the future is a clock fault, not a position. */
    private const val MAX_FUTURE_SKEW_MS = 1_000L

    data class FixState(
        val running: Boolean = false,
        val binId: Long? = null,
        val resolution: Int? = null,
        val accuracyM: Float? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val fixCount: Int = 0,
        val rejectedForAccuracy: Int = 0,
        /** Fixes older than [MAX_FIX_AGE_MS] at delivery -- in practice, stale cached fixes. */
        val rejectedAsStale: Int = 0,
        /** Fixes the platform flagged as coming from a mock location provider. */
        val rejectedAsMock: Int = 0,
        val provider: String? = null,
        val note: String? = null
    )

    private val _state = MutableStateFlow(FixState())
    val state: StateFlow<FixState> = _state

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    /** Monotonic ms, on the fixes' own clock, of the last row written. */
    private var lastWriteMs = Long.MIN_VALUE / 2
    private var lastWrittenBin: Long? = null
    /** Newest fix accepted, so a re-served fix is never counted as new evidence. */
    private var lastFixNanos = Long.MIN_VALUE
    /** The newest accepted fix that was not written, held so a bin exit can write it. */
    private var pending: MapFix? = null

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Who currently wants position. The Map tab and [CollectorService] both drive this collector
     * and neither knows about the other, so a bare `stop()` from the tab would silently cancel
     * the service's request -- and the service's request is the one that makes the map grow while
     * you travel. Counting owners keeps the request alive until the last of them lets go, and
     * keeps the public `start`/`stop` pair working unchanged for the UI that already calls it.
     */
    private val owners = mutableSetOf<String>()

    /** The UI's claim: the Map tab is composed. */
    @Synchronized
    fun start(ctx: Context) = acquire(ctx, "ui")

    /** The UI's release. Does not stop collection if the service still wants it. */
    @Synchronized
    fun stop() = release("ui")

    /** The service's claim, made by its power gate rather than by anything on screen. */
    @Synchronized
    fun startForService(ctx: Context) = acquire(ctx, "service")

    @Synchronized
    fun stopForService() = release("service")

    private fun acquire(ctx: Context, owner: String) {
        owners += owner
        beginUpdates(ctx)
    }

    private fun release(owner: String) {
        owners -= owner
        if (owners.isEmpty()) endUpdates()
    }

    private fun beginUpdates(ctx: Context) {
        if (listener != null) return
        if (!hasPermission(ctx)) {
            _state.value = _state.value.copy(running = false, note = "location permission not granted")
            return
        }
        val manager = ctx.getSystemService(LocationManager::class.java) ?: return

        val provider = when {
            manager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }

        val l = LocationListener { loc -> onFix(ctx, loc) }
        listener = l
        lm = manager

        val request = LocationRequest.Builder(INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .build()

        try {
            manager.requestLocationUpdates(provider, request, executor, l)
            // A cached fix costs nothing and makes the first frame of the map useful -- when it
            // is recent. onFix stamps it with its own measurement time and drops it past
            // MAX_FIX_AGE_MS, so a cache left over from hours ago is never stored as "now".
            manager.getLastKnownLocation(provider)?.let { onFix(ctx, it) }
            _state.value = _state.value.copy(running = true, provider = provider, note = null)
        } catch (e: SecurityException) {
            listener = null
            _state.value = _state.value.copy(running = false, note = "location denied: ${e.message}")
        } catch (e: IllegalArgumentException) {
            listener = null
            _state.value = _state.value.copy(running = false, note = "no provider: ${e.message}")
        }
    }

    private fun endUpdates() {
        val l = listener ?: return
        try { lm?.removeUpdates(l) } catch (_: SecurityException) { }
        listener = null
        _state.value = _state.value.copy(running = false)
    }

    /**
     * Synchronized because it is reached from two threads: the location executor for live fixes,
     * and whichever thread called [start] for the cached one. The write bookkeeping below is
     * read-modify-write and would otherwise race.
     */
    @Synchronized
    private fun onFix(ctx: Context, loc: Location) {
        // A mock provider is a developer tool or a spoofing app. Either way the position is not
        // where the radio was, and a map built on it attributes real measurements to a place the
        // phone never went.
        if (loc.isMock) {
            _state.value = _state.value.copy(
                rejectedAsMock = _state.value.rejectedAsMock + 1,
                note = "fix rejected: mock location provider"
            )
            return
        }

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val fixNanos = loc.elapsedRealtimeNanos
        val ageMs = (nowNanos - fixNanos) / 1_000_000L
        if (fixNanos <= 0L || ageMs > MAX_FIX_AGE_MS || ageMs < -MAX_FUTURE_SKEW_MS) {
            _state.value = _state.value.copy(
                rejectedAsStale = _state.value.rejectedAsStale + 1,
                note = if (fixNanos <= 0L) "fix rejected: no measurement time"
                    else "fix rejected: measured ${ageMs / 1000} s ago, older than " +
                        "${MAX_FIX_AGE_MS / 1000} s"
            )
            return
        }
        // The cached fix read at start-up is often the very fix the first live delivery repeats.
        if (fixNanos <= lastFixNanos) return

        val acc = if (loc.hasAccuracy()) loc.accuracy else 9999f
        val res = MapHex.resForAccuracy(acc, loc.latitude)
        if (res == null) {
            // "Above a configurable accuracy ceiling, store null rather than a bad bin."
            _state.value = _state.value.copy(
                rejectedForAccuracy = _state.value.rejectedForAccuracy + 1,
                accuracyM = acc,
                note = "fix rejected: ±${acc.toInt()} m is coarser than the ${MapHex.ACCURACY_CEILING_M.toInt()} m ceiling"
            )
            return
        }

        val bin = MapHex.latLngToCell(loc.latitude, loc.longitude, res)
        val centre = MapHex.cellToLatLng(bin)
        lastFixNanos = fixNanos

        _state.value = _state.value.copy(
            running = true,
            binId = bin,
            resolution = res,
            accuracyM = acc,
            // The bin CENTRE, not the fix. Nothing finer than the bin is held in memory either.
            lat = centre[0],
            lng = centre[1],
            fixCount = _state.value.fixCount + 1,
            note = null
        )

        val fixMs = fixNanos / 1_000_000L
        val row = MapFix(
            elapsedNanos = fixNanos,
            // The wall time of the same instant, not of delivery. The map tells boots apart by the
            // offset between these two clocks, so they must describe one moment.
            wallMillis = System.currentTimeMillis() - ageMs,
            binId = bin,
            resolution = res,
            accuracyM = acc,
            speedMps = if (loc.hasSpeed()) loc.speed else null
        )

        val newBin = bin != lastWrittenBin
        if (!newBin && fixMs - lastWriteMs < MIN_WRITE_GAP_MS) {
            pending = row
            return
        }
        // Leaving a bin: record the last moment the phone was seen in it before the new one.
        val exit = pending?.takeIf { newBin && it.binId == lastWrittenBin }
        pending = null
        lastWriteMs = fixMs
        lastWrittenBin = bin

        io.launch {
            try {
                val dao = Db.get(ctx).dao()
                exit?.let { dao.insertFix(it) }
                dao.insertFix(row)
            } catch (e: Throwable) { Log.w(TAG, "fix write failed", e) }
        }
    }
}
