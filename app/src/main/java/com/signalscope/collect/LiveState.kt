package com.signalscope.collect

import com.signalscope.store.DeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One subscription's live view, as the dashboard renders it. */
data class SimState(
    val subId: Int,
    val slot: Int,
    val carrier: String = "—",
    /**
     * The network actually SERVING this subscription, as MCC-MNC, read from the serving cell or
     * failing that from the registered operator. This is what every stored row and every map bin is
     * keyed on.
     *
     * It used to be the SIM card's own network, taken from SubscriptionInfo. At home the two are the
     * same, which is why that went unnoticed; on a roaming SIM it attributed every reading to a home
     * operator carrying none of that traffic, and enforced "bins never merge across networks"
     * against the wrong key. "—" until the serving network is known -- never guessed from the card.
     */
    val plmn: String = "—",
    /** The SIM card's home network. Notation only: shown beside [plmn], never used as a key. */
    val simPlmn: String = "—",
    val rat: String = "—",
    val overrideNetwork: String = "—",
    val roaming: Boolean = false,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val rssnr: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val level: Int? = null,
    val vendorLevel: Int? = null,
    val pci: Int? = null,
    val ci: Long? = null,
    val tac: Int? = null,
    val arfcn: Int? = null,
    val band: Int? = null,
    val psRegistered: Boolean? = null,
    val csRegistered: Boolean? = null,
    val iwlanRegistered: Boolean? = null,
    val rejectCause: Int? = null,
    val dataActivity: Int? = null,
    val serviceState: String = "—",
    val isDataSub: Boolean = false,
    val neighbourCountSeen: Int? = null,
    val signalUpdates: Int = 0,
    val cellUpdates: Int = 0,
    /**
     * Wall time of the last signal reading, and of the last serving-cell reading.
     *
     * These exist because a reading with no timestamp cannot be distinguished from a stale one,
     * and on 2026-09-12 that cost a whole experiment: the passive [TelephonyCallback] signal
     * stream stops when the screen goes off, so for 39 minutes the keepalive A/B run
     * read the same frozen -91 dBm / SINR 3 snapshot 600 times and recorded eight consecutive
     * blocks of "zero cell changes, zero variance" as if the network had gone quiet. It had not;
     * we had. Anything consuming these fields for measurement MUST check freshness first.
     */
    val signalMillis: Long = 0,
    val cellMillis: Long = 0,
    /**
     * Wall time of the last service-state reading.
     *
     * Separate from [signalMillis] because the two streams fail differently: signal pushes stop
     * when the screen goes off, while the service-state callbacks kept firing right through that
     * same window on 2026-09-12. [Mobility] infers failed handovers partly from service drops and
     * had to rely on that observation rather than verify it; this lets it check.
     */
    val serviceMillis: Long = 0,
    /**
     * [band] as the platform reported it, and as derived from [arfcn] by [com.signalscope.store.Bands].
     * [band] itself stays populated for existing readers: the derived band where the channel
     * resolves, the reported one otherwise.
     */
    val bandReported: Int? = null,
    val bandDerived: Int? = null,
    /** More than one band fitted the channel after regional resolution. */
    val bandAmbiguous: Boolean = false,
    /**
     * LTE or NR: the serving cell identity's own technology, i.e. which band table [band] and
     * which raster [arfcn] belong to. Not [rat], which is the display network type.
     */
    val cellRat: String? = null,
    /**
     * Whether the most recent cell-info report contained a registered LTE/NR cell. Null until the
     * first report. When false, [pci]/[ci]/[band] still hold the last known cell -- the dashboard
     * keeps showing it -- but stored rows write the identity as null rather than carry it forward.
     */
    val servingReported: Boolean? = null,
    /** NR SS signal, kept apart from the LTE triple above. Null when there is no NR entry. */
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    /** An NR signal entry with at least one real value exists. */
    val nrPresent: Boolean = false,
    /** Latest non-empty ServiceState.getCellBandwidths(), kHz, comma-joined. See the collector. */
    val cellBandwidths: String? = null,
    /** Serving CI when [cellBandwidths] was reported. Kept for display; not the validity key. */
    val bandwidthsCi: Long? = null,
    /**
     * The channel number ServiceState reported in the same callback as [cellBandwidths] -- the key
     * that decides whether those widths still describe the serving carrier. See TelephonyCollector.
     */
    val bandwidthsArfcn: Int? = null
) {
    /** True when the reading is older than [maxAgeMs] -- i.e. not safe to measure with. */
    fun signalStale(maxAgeMs: Long = 15_000): Boolean =
        signalMillis == 0L || System.currentTimeMillis() - signalMillis > maxAgeMs

    /** Service state is event-driven, so it goes quiet legitimately -- hence the wider default. */
    fun serviceStale(maxAgeMs: Long = 10 * 60_000): Boolean =
        serviceMillis == 0L || System.currentTimeMillis() - serviceMillis > maxAgeMs
}

data class NetState(
    val transport: String = "—",
    val validated: Boolean = false,
    val notSuspended: Boolean = true,
    val metered: Boolean = false,
    val v4: String? = null,
    val v6: String? = null,
    val mtu: Int? = null,
    val hasClat: Boolean = false,
    val ifname: String? = null,
    val addressChanges: Int = 0,
    /**
     * A VPN sits in the path. [transport] already reports the bearer UNDERNEATH it, because a
     * tunnel is an overlay and not a radio -- but the tunnel is still worth knowing about, since
     * it adds a hop to every latency this app measures and can route traffic somewhere the radio
     * did not choose.
     */
    val vpn: Boolean = false
)

data class Counters(
    val radioRows: Int = 0,
    val regRows: Int = 0,
    val linkRows: Int = 0,
    val startedElapsed: Long = 0
)

object LiveState {
    val sims = MutableStateFlow<Map<Int, SimState>>(emptyMap())
    val net = MutableStateFlow(NetState())
    val counters = MutableStateFlow(Counters())
    val running = MutableStateFlow(false)
    /** non-null when collection is degraded; shown in the UI rather than failing silently */
    val degraded = MutableStateFlow<String?>(null)
    /** discovered at first run; nothing device-specific is hardcoded anywhere else */
    val profile = MutableStateFlow<DeviceProfile?>(null)

    /**
     * Read-modify-write on a map shared by several threads, so it goes through [update], which
     * retries on a concurrent write rather than clobbering it.
     *
     * The plain `sims.value = sims.value.toMutableMap()...` this replaces was a lost-update race:
     * the telephony callbacks arrive on the collector's own single-thread executor, the
     * connectivity callbacks on a binder thread, and [ActionTrafficClassifier] posts to the main
     * looper. Two of those landing between one read of `.value` and its write silently dropped a
     * field -- an RSRP that never updated, a `cellUpdates` count that lost increments. It is a
     * data-correctness bug, not a crash, which is what made it worth fixing rather than noting.
     */
    fun updateSim(subId: Int, f: (SimState) -> SimState) {
        sims.update { cur ->
            cur + (subId to f(cur[subId] ?: SimState(subId = subId, slot = -1)))
        }
    }

    /**
     * Forget a subscription entirely.
     *
     * Used when the placeholder registered before the real subscription list was readable has to
     * be cleared. Leaving it would put a second, permanently blank SIM beside the real ones on the
     * Live screen, which reads as a hardware fault rather than as a startup artefact.
     */
    fun dropSim(subId: Int) {
        sims.update { cur -> cur - subId }
    }

    val simsFlow: StateFlow<Map<Int, SimState>> get() = sims
}
