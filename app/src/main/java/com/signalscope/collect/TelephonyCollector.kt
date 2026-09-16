package com.signalscope.collect

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.telephony.*
import com.signalscope.store.Bands
import com.signalscope.store.Db
import com.signalscope.store.NeighbourCell
import com.signalscope.store.Quality
import com.signalscope.store.RadioSample
import com.signalscope.store.RegistrationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Per-subscription telephony collection.
 *
 * Two rules from docs/data-model.md are load-bearing here:
 *  - every read goes through createForSubscriptionId(); a bare TelephonyManager
 *    silently serves the default subscription, which is wrong on a dual-SIM device.
 *  - no wakelock is taken. We wake on callback, write, and return.
 */
@SuppressLint("MissingPermission")
class TelephonyCollector(
    private val ctx: Context,
    private val scope: CoroutineScope
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val callbacks = mutableMapOf<Int, TelephonyCallback>()
    /** last written registration signature per subId|domain|transport */
    private val lastRegSig = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dao by lazy { Db.get(ctx).dao() }

    private val sm by lazy { ctx.getSystemService(SubscriptionManager::class.java) }
    private val baseTm by lazy { ctx.getSystemService(TelephonyManager::class.java) }

    /** Samsung and other OEMs append their own bar level to SignalStrength.toString(). */
    private val vendorLevelRe = Regex("""(?:lte|nr|gsm|wcdma)Level=(\d+)""", RegexOption.IGNORE_CASE)

    fun start() {
        val subs = try { sm?.activeSubscriptionInfoList.orEmpty() } catch (_: SecurityException) { emptyList() }
        if (subs.isEmpty()) {
            // No READ_PHONE_STATE yet, or no SIM. Register the default sub so we show something.
            registerFor(SubscriptionManager.getDefaultSubscriptionId(), -1, "default")
            return
        }
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        subs.forEach { info ->
            LiveState.updateSim(info.subscriptionId) {
                it.copy(
                    subId = info.subscriptionId,
                    slot = info.simSlotIndex,
                    carrier = info.carrierName?.toString() ?: "—",
                    // The card's network, kept as a label. The key -- plmn -- comes from the serving
                    // cell, because for a roaming SIM the two are different operators.
                    simPlmn = "${info.mccString ?: "?"}-${info.mncString ?: "?"}",
                    isDataSub = info.subscriptionId == dataSub
                )
            }
            registerFor(info.subscriptionId, info.simSlotIndex, info.carrierName?.toString() ?: "—")
        }
    }

    fun stop() {
        stopPolling()
        callbacks.forEach { (subId, cb) ->
            runCatching { tmFor(subId)?.unregisterTelephonyCallback(cb) }
        }
        callbacks.clear()
        runCatching { exec.shutdown() }
    }

    /**
     * Null on a device with no telephony subsystem at all, and on the sub-id variant when the
     * platform rejects the id. It used to be `baseTm!!`, which turned a Wi-Fi-only tablet into a
     * KotlinNullPointerException thrown out of `registerFor` -- i.e. out of `CollectorService`'s
     * `onCreate`, which kills the process on first launch.
     */
    private fun tmFor(subId: Int): TelephonyManager? = runCatching {
        val base = baseTm ?: return null
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base
        else base.createForSubscriptionId(subId)
    }.getOrNull()

    private fun registerFor(subId: Int, slot: Int, carrier: String) {
        val tm = tmFor(subId) ?: return
        val cb = SubCallback(subId)
        callbacks[subId] = cb
        runCatching { tm.registerTelephonyCallback(exec, cb) }
        LiveState.updateSim(subId) { it.copy(slot = slot, carrier = carrier) }
    }

    /** "00101" -> "001-01". Null for anything that is not a plausible MCC plus MNC. */
    private fun numericToPlmn(n: String?): String? =
        n?.takeIf { it.length in 5..6 && it.all(Char::isDigit) }?.let { "${it.take(3)}-${it.drop(3)}" }

    /**
     * Apply a cell-info report to a subscription's state, from either the passive callback or the
     * active poll -- one function, so the two paths cannot drift apart.
     *
     * Three rules, each fixing something that was measurably wrong:
     *
     *  - **No serving cell, no update to identity or freshness.** The passive path used to keep the
     *    previous PCI, CI and band when the report contained no registered cell -- correct enough on
     *    its own -- and then stamp cellMillis with the current time, so a carried-forward identity
     *    read as a fresh one and passed every staleness check built on it.
     *  - **Freshness is the READING's time, not ours.** requestCellInfoUpdate can return cached
     *    information, observed up to twenty minutes old. CellInfo.getTimestampMillis gives the
     *    modem's own time of measurement, on the elapsedRealtime clock, and that is converted to
     *    wall time rather than replaced with it.
     *  - **The network is the serving cell's.** Its MCC and MNC describe the network carrying the
     *    traffic; the SIM's describe the card.
     *  - **The band is the channel's.** `getBands()` may be empty and cannot tell B40 from n40, so
     *    the band is derived from the EARFCN / NR-ARFCN by [Bands] and the platform's answer kept
     *    beside it for comparison. `servingReported` records whether this report had a registered
     *    LTE/NR cell at all, so the writer can store null instead of the carried-forward identity.
     */
    private fun applyServing(s: SimState, cellInfo: List<CellInfo>): SimState {
        val base = s.copy(neighbourCountSeen = cellInfo.size, cellUpdates = s.cellUpdates + 1)
        val serving = cellInfo.firstOrNull { it.isRegistered }
            ?: return base.copy(servingReported = false)
        val ageMs = (SystemClock.elapsedRealtime() - serving.timestampMillis).coerceAtLeast(0L)
        val readingWall = System.currentTimeMillis() - ageMs
        return when (val id = serving.cellIdentity) {
            is CellIdentityLte -> {
                val plmn = mccMnc(id.mccString, id.mncString) ?: base.plmn
                val earfcn = nz(id.earfcn)
                val derived = runCatching { Bands.lte(earfcn, mccOf(plmn)) }.getOrNull()
                val reported = runCatching { id.bands.firstOrNull { it > 0 } }.getOrNull()
                base.copy(
                    pci = nz(id.pci), ci = nzL(id.ci.toLong()), tac = nz(id.tac),
                    arfcn = earfcn, band = derived?.band ?: reported,
                    bandReported = reported, bandDerived = derived?.band,
                    bandAmbiguous = derived?.ambiguous == true, cellRat = Bands.LTE,
                    plmn = plmn, cellMillis = readingWall, servingReported = true
                )
            }
            is CellIdentityNr -> {
                val plmn = mccMnc(id.mccString, id.mncString) ?: base.plmn
                val nrarfcn = nz(id.nrarfcn)
                val derived = runCatching { Bands.nr(nrarfcn, mccOf(plmn)) }.getOrNull()
                val reported = runCatching { id.bands.firstOrNull { it > 0 } }.getOrNull()
                base.copy(
                    pci = nz(id.pci), ci = nzL(id.nci), tac = nz(id.tac),
                    arfcn = nrarfcn, band = derived?.band ?: reported,
                    bandReported = reported, bandDerived = derived?.band,
                    bandAmbiguous = derived?.ambiguous == true, cellRat = Bands.NR,
                    plmn = plmn, cellMillis = readingWall, servingReported = true
                )
            }
            // Registered on GSM/WCDMA or something newer than this code: we hold no identity for
            // it, and the LTE/NR identity from before is precisely the carried-forward cell the
            // writer must not store.
            else -> base.copy(servingReported = false)
        }
    }

    /** "001-01" -> "001". Null for the "—" placeholder or anything that is not three digits. */
    private fun mccOf(plmn: String?): String? =
        plmn?.substringBefore('-')?.takeIf { it.length == 3 && it.all(Char::isDigit) }

    // ------------------------------------------------------------------------------------------
    // Neighbours
    // ------------------------------------------------------------------------------------------

    /** Per subscription, only touched from [exec]; concurrent maps anyway, for the poll's scope. */
    private val nbrStamp = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val nbrWriteElapsed = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val nbrServingCi = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * Store the strongest six non-serving cells of a cell-info report.
     *
     * Three gates keep this bounded, because a neighbour table written per signal row would
     * outgrow radio_sample several times over for no information:
     *
     *  - **A new report only.** requestCellInfoUpdate can hand back the same cached list again;
     *    if no cell in it is newer than the last one stored, nothing is written.
     *  - **At most one report a minute per subscription**, unless the serving cell changed. At rest
     *    the neighbour set barely moves; across a cell change it is exactly the evidence wanted.
     *  - **Six cells.** Reselection is decided among the strongest few; the tail is noise.
     *
     * Times are the modem's own measurement times, per `data-model.md` s2, not arrival time.
     */
    private fun recordNeighbours(subId: Int, cellInfo: List<CellInfo>) {
        runCatching {
            if (cellInfo.isEmpty()) return
            val newest = cellInfo.maxOf { it.timestampMillis }
            if (newest <= (nbrStamp[subId] ?: Long.MIN_VALUE)) return

            val nowElapsed = SystemClock.elapsedRealtime()
            val sim = LiveState.sims.value[subId]
            val servingCi = sim?.takeIf { it.servingReported == true }?.ci ?: NO_CELL
            val cellChanged = servingCi != (nbrServingCi[subId] ?: NO_CELL)
            val last = nbrWriteElapsed[subId]
            if (!cellChanged && last != null && nowElapsed - last < NEIGHBOUR_MIN_INTERVAL_MS) return

            nbrStamp[subId] = newest
            nbrWriteElapsed[subId] = nowElapsed
            nbrServingCi[subId] = servingCi

            val nowWall = System.currentTimeMillis()
            // Non-serving cells, plus one exception. Under non-standalone 5G the serving cell is
            // LTE and the NR carrier is a secondary leg; some modems report that leg as
            // `isRegistered`, which the plain filter would discard. Four days of collection
            // produced 14,794 LTE neighbour rows and zero NR ones, so whatever the cause, this
            // side of it costs nothing to rule out. Only while the serving cell is not itself NR:
            // on standalone 5G a registered NR cell IS the serving cell, and recording it here
            // would file the serving cell as its own neighbour.
            val servingIsNr = sim?.cellRat == Bands.NR
            val rows = cellInfo.asSequence()
                .filter { !it.isRegistered || (it is CellInfoNr && !servingIsNr) }
                .mapNotNull { runCatching { neighbourOf(subId, it, nowElapsed, nowWall) }.getOrNull() }
                .sortedByDescending { it.rsrp }
                .take(NEIGHBOURS_KEPT)
                .toList()
            if (rows.isEmpty()) return
            scope.launch { runCatching { dao.insertNeighbours(rows) } }
        }
    }

    /** Null for a cell with no RSRP -- it cannot be ranked -- and for non-LTE/NR cells. */
    private fun neighbourOf(subId: Int, c: CellInfo, nowElapsed: Long, nowWall: Long): NeighbourCell? {
        val ts = c.timestampMillis
        val elapsedNanos = ts * 1_000_000L
        val wall = nowWall - (nowElapsed - ts).coerceAtLeast(0L)
        return when (c) {
            is CellInfoLte -> {
                val sig = c.cellSignalStrength
                val rsrp = nz(sig.rsrp) ?: return null
                NeighbourCell(
                    elapsedNanos = elapsedNanos, wallMillis = wall, subId = subId, rat = Bands.LTE,
                    arfcn = nz(c.cellIdentity.earfcn), pci = nz(c.cellIdentity.pci),
                    rsrp = rsrp, rsrq = nz(sig.rsrq), rssnr = nz(sig.rssnr)
                )
            }
            is CellInfoNr -> {
                val id = c.cellIdentity as? CellIdentityNr ?: return null
                val sig = c.cellSignalStrength as? CellSignalStrengthNr ?: return null
                val rsrp = nz(sig.ssRsrp) ?: return null
                NeighbourCell(
                    elapsedNanos = elapsedNanos, wallMillis = wall, subId = subId, rat = Bands.NR,
                    arfcn = nz(id.nrarfcn), pci = nz(id.pci),
                    rsrp = rsrp, rsrq = nz(sig.ssRsrq), rssnr = nz(sig.ssSinr)
                )
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------
    // Signal
    // ------------------------------------------------------------------------------------------

    /**
     * One SignalStrength, split by technology.
     *
     * This used to be `rsrp = lte?.rsrp ?: nr?.ssRsrp`, one set of columns for both. Under NSA
     * both entries exist and the NR leg was silently dropped; with LTE momentarily absent the
     * same column switched to NR values mid-series, and a median over it mixed two different
     * measurements on two different reference signals. The LTE triple and the NR SS triple are
     * now separate, and [nrPresent] says whether NR had anything real to report.
     */
    private class Sig(
        val rsrp: Int?, val rsrq: Int?, val rssnr: Int?, val cqi: Int?, val ta: Int?,
        val ssRsrp: Int?, val ssRsrq: Int?, val ssSinr: Int?, val nrPresent: Boolean,
        val level: Int?, val vendor: Int?
    )

    private fun readSig(ss: SignalStrength): Sig {
        val lte = runCatching { ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull() }.getOrNull()
        val nr = runCatching { ss.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull() }.getOrNull()
        val vendor = runCatching { vendorLevelRe.find(ss.toString())?.groupValues?.get(1)?.toIntOrNull() }.getOrNull()
        val ssRsrp = nr?.let { runCatching { nz(it.ssRsrp) }.getOrNull() }
        val ssRsrq = nr?.let { runCatching { nz(it.ssRsrq) }.getOrNull() }
        val ssSinr = nr?.let { runCatching { nz(it.ssSinr) }.getOrNull() }
        // An NR entry full of UNAVAILABLE is how some modems say "no NR"; its mere existence is
        // not evidence of an NR leg. CSI values count too: in connected mode they can be the only
        // ones filled in.
        val nrPresent = nr != null && runCatching {
            listOf(nr.ssRsrp, nr.ssRsrq, nr.ssSinr, nr.csiRsrp, nr.csiRsrq, nr.csiSinr)
                .any { it != CellInfo.UNAVAILABLE }
        }.getOrDefault(false)
        return Sig(
            rsrp = lte?.let { nz(it.rsrp) }, rsrq = lte?.let { nz(it.rsrq) },
            rssnr = lte?.let { nz(it.rssnr) }, cqi = lte?.let { nz(it.cqi) },
            ta = lte?.let { nz(it.timingAdvance) },
            ssRsrp = ssRsrp, ssRsrq = ssRsrq, ssSinr = ssSinr, nrPresent = nrPresent,
            level = runCatching { ss.level }.getOrNull(), vendor = vendor
        )
    }

    /**
     * Apply a reading to live state. The dashboard's rsrp/rsrq/rssnr keep their old meaning --
     * LTE, falling back to NR SS -- because every live consumer was written against that and an SA
     * handset would otherwise show no signal at all. Only the STORED columns are split.
     */
    private fun applySig(s: SimState, sig: Sig, act: Int?): SimState = s.copy(
        rsrp = sig.rsrp ?: sig.ssRsrp, rsrq = sig.rsrq ?: sig.ssRsrq, rssnr = sig.rssnr ?: sig.ssSinr,
        cqi = sig.cqi, timingAdvance = sig.ta,
        ssRsrp = sig.ssRsrp, ssRsrq = sig.ssRsrq, ssSinr = sig.ssSinr, nrPresent = sig.nrPresent,
        level = sig.level, vendorLevel = sig.vendor, dataActivity = act,
        signalUpdates = s.signalUpdates + 1,
        signalMillis = System.currentTimeMillis()
    )

    private fun mccMnc(mcc: String?, mnc: String?): String? =
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else "$mcc-$mnc"

    private fun nz(v: Int): Int? = if (v == CellInfo.UNAVAILABLE) null else v
    private fun nzL(v: Long): Long? = if (v == CellInfo.UNAVAILABLE.toLong() || v == Long.MAX_VALUE) null else v

    private inner class SubCallback(private val subId: Int) :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.DataActivityListener,
        TelephonyCallback.CellInfoListener {

        override fun onSignalStrengthsChanged(ss: SignalStrength) {
            val sig = runCatching { readSig(ss) }.getOrNull() ?: return
            val act = runCatching { tmFor(subId)?.dataActivity }.getOrNull()
            LiveState.updateSim(subId) { applySig(it, sig, act) }
            write(subId, "event", sig, act)
        }

        override fun onServiceStateChanged(state: ServiceState) {
            // getNetworkRegistrationInfo(domain, transport) is @SystemApi; the list accessor
            // is the public one. Domain is a bitmask, so test with `and`.
            val regs = runCatching { state.networkRegistrationInfoList }.getOrDefault(emptyList())
            fun pick(domain: Int, transport: Int) = regs.firstOrNull {
                (it.domain and domain) != 0 && it.transportType == transport
            }
            val ps = pick(NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
            val cs = pick(NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
            val iwlan = pick(NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WLAN)

            val stateStr = when (state.state) {
                ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
                ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                ServiceState.STATE_POWER_OFF -> "POWER_OFF"
                else -> "UNKNOWN"
            }

            // Carrier aggregation at Tier 0. getCellBandwidths() is public and lists one entry per
            // configured carrier, so [20000, 20000] is two 20 MHz carriers aggregated -- no
            // Shizuku needed. It is often empty in RRC idle, where no carriers are configured; an
            // empty answer keeps the last non-empty one only while the serving cell is the one it
            // was reported on, and anything other than in-service clears it, so a bandwidth set
            // is never carried onto a different cell.
            val widths = runCatching { state.cellBandwidths }.getOrNull()
                ?.filter { it > 0 }?.takeIf { it.isNotEmpty() }?.joinToString(",")

            // Keyed on the channel number from THIS ServiceState, not on the cell id. The cell id
            // arrives from a separate callback, and when collection starts Android delivers the
            // current ServiceState before the first cell report -- so the widths were tagged with
            // a null cell, every later row had a real one, the match failed, and not one width was
            // ever written (0 of 102 rows in the first check after migration, while the platform
            // was reporting [20000, 20000]). The channel describes the carrier configuration the
            // widths belong to: moving between sectors of one carrier keeps them, moving to another
            // band invalidates them, which is the property that was wanted in the first place.
            val channel = runCatching { state.channelNumber }.getOrNull()
                ?.takeIf { it > 0 && it != Int.MAX_VALUE }
            LiveState.updateSim(subId) {
                val (bw, bwArfcn) = when {
                    state.state != ServiceState.STATE_IN_SERVICE -> null to null
                    widths != null -> widths to channel
                    it.bandwidthsArfcn != null && it.bandwidthsArfcn == channel ->
                        it.cellBandwidths to it.bandwidthsArfcn
                    else -> null to null
                }
                it.copy(
                    cellBandwidths = bw, bandwidthsCi = it.ci, bandwidthsArfcn = bwArfcn,
                    serviceState = stateStr,
                    roaming = state.roaming,
                    psRegistered = ps?.isRegistered,
                    csRegistered = cs?.isRegistered,
                    iwlanRegistered = iwlan?.isRegistered,
                    rejectCause = ps?.rejectCause,
                    // The registered operator. Used when no serving cell has reported an MCC yet;
                    // a serving cell's own identity overrides it when one arrives.
                    plmn = numericToPlmn(state.operatorNumeric) ?: it.plmn,
                    serviceMillis = System.currentTimeMillis()
                )
            }

            // Write one row PER registration, not just PS/WWAN. The earlier version dropped
            // the CS row and the WLAN row, which made cause 4's CS-up/PS-down half and cause 12
            // (VoWiFi/ePDG) unconfirmable from stored data even though we had both in hand.
            val rows = listOfNotNull(
                ps?.let { Triple("PS", "WWAN", it) },
                cs?.let { Triple("CS", "WWAN", it) },
                iwlan?.let { Triple("PS", "WLAN", it) }
            )
            val override = LiveState.sims.value[subId]?.overrideNetwork

            scope.launch {
                rows.forEach { (domain, transport, reg) ->
                    // ServiceState callbacks fire whether or not anything changed; without this
                    // the table fills with identical rows (91 rows, all IN_SERVICE, observed).
                    val sig = "$domain|$transport|$stateStr|${reg.rejectCause}|${state.roaming}|$override"
                    val key = "$subId|$domain|$transport"
                    if (lastRegSig[key] == sig) return@forEach
                    lastRegSig[key] = sig

                    runCatching {
                        dao.insertReg(
                            RegistrationEvent(
                                elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                                wallMillis = System.currentTimeMillis(),
                                subId = subId,
                                domain = domain,
                                transportType = transport,
                                accessNetworkTechnology = reg.accessNetworkTechnology.toString(),
                                regState = stateStr,
                                rejectCause = reg.rejectCause,
                                // getNrState() is hidden and blocked even reflectively on
                                // Android 16; keep the attempt so it works where it is allowed.
                                nrState = runCatching {
                                    reg.javaClass.getMethod("getNrState").invoke(reg).toString()
                                }.getOrNull(),
                                overrideNetworkType = override,
                                roaming = state.roaming,
                                dataState = null
                            )
                        )
                    }
                }
                runCatching { bumpCounters() }
            }
        }

        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            LiveState.updateSim(subId) {
                it.copy(
                    rat = ratName(info.networkType),
                    overrideNetwork = overrideName(info.overrideNetworkType)
                )
            }
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            LiveState.updateSim(subId) { it.copy(rat = ratName(networkType)) }
        }

        override fun onDataActivity(direction: Int) {
            LiveState.updateSim(subId) { it.copy(dataActivity = direction) }
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            runCatching { LiveState.updateSim(subId) { applyServing(it, cellInfo) } }
            recordNeighbours(subId, cellInfo)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Active polling
    // ------------------------------------------------------------------------------------------

    /**
     * Poll the radio directly, because the passive callbacks are not trustworthy when the screen
     * is off.
     *
     * Measured on 2026-09-12: with the screen on, `onSignalStrengthsChanged` delivers 15-37
     * samples a minute. The screen went off and the next sample arrived 39 minutes later, when it
     * came back on -- **39 minutes with not one reading**, while active probes over the same window
     * kept working fine (13 of them) and the event-driven service-state callbacks still fired.
     * So it is not the process being frozen: the platform simply stops notifying signal strength
     * to a background app, foreground service or not.
     *
     * That is fatal for unattended measurement, which is the whole point of the excursion
     * recorder and the A/B experiments -- a phone in a pocket has its screen off by definition.
     * `getSignalStrength()` and `requestCellInfoUpdate()` are pulls rather than pushes and are not
     * subject to that gating, so we pull on a timer and only write when the push stream has gone
     * quiet. When the screen is on this loop costs two reads a minute and writes nothing.
     *
     * ## Cadence is mobility-aware
     *
     * [intervalMs] is the *stationary* cadence, not the cadence. While [Mobility] says the phone
     * is moving faster than walking the loop tightens, because the thing being measured changes
     * on a different timescale: a cell that lasts minutes in traffic lasts tens of seconds on a
     * train, and a serving-cell change that is only noticed 20 s late cannot be correlated with
     * the service drop beside it. The evidence window for a suspected failed handover is 6 s
     * wide, so sampling every 10 s would make the distinction unmeasurable by construction.
     *
     * What this does *not* change is when a row is written: still only when the passive push
     * stream has been quiet for 8 s. Screen-on density is therefore unchanged, no duplicate rows
     * appear, and any rate derived from row density stays comparable with everything already
     * collected — including the 2.1 cell changes/min the excursion measured.
     */
    fun startPolling(intervalMs: Long = 10_000L) {
        if (polling != null) return
        polling = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                delay(cadenceMs(intervalMs))
            }
        }
    }

    fun stopPolling() { polling?.cancel(); polling = null }

    private var polling: Job? = null
    /** Cell info is a modem scan, so it is polled every other tick rather than every tick. */
    private var tick = 0L

    // ---- cadence ---------------------------------------------------------------------------

    /**
     * 5 s in a vehicle. A macro cell crossed in 45–135 s at motorway speed gives 9–27 samples,
     * which is enough to place a change and its evidence, and the cost is bounded: this only
     * applies while a journey is open, so it is tens of minutes a day rather than all day.
     */
    private val pollVehicleMs = 5_000L

    /**
     * 3 s above ~80 km/h. Deliberately close to the floor of what the platform will answer:
     * `requestCellInfoUpdate()` is rate-limited by the framework and the modem, so some of these
     * calls will return a cached list and `cellMillis` will not move. That is not a reason to
     * poll slower — it is the reason freshness is counted rather than assumed. Two reads per 3 s
     * against ten per minute when stationary, so roughly 4x the stationary read rate, paid only
     * while actually travelling fast.
     */
    private val pollFastMs = 3_000L

    /** Below 15 % and not charging, density loses to finishing the day. Re-read once a minute. */
    private var batteryCheckedElapsed = 0L
    private var batteryStarved = false

    private fun cadenceMs(stationaryMs: Long): Long {
        val m = runCatching { Mobility.state.value }.getOrNull() ?: return stationaryMs
        // UNKNOWN and STATIONARY both fall through to the existing cadence. UNKNOWN in
        // particular must not be treated as movement *or* as stillness -- it means the
        // classifier had nothing to go on, and guessing either way would be inventing data.
        if (!m.aboveWalking) return stationaryMs

        val now = SystemClock.elapsedRealtime()
        if (now - batteryCheckedElapsed > 60_000L) {
            batteryCheckedElapsed = now
            batteryStarved = runCatching {
                val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
                val pct = bm?.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                // -1 is "the device declined to answer" and must not be read as flat.
                pct in 0..14 && bm?.isCharging != true
            }.getOrDefault(false)
        }
        if (batteryStarved) return stationaryMs

        return if (m.cls == Mobility.Cls.FAST) pollFastMs else pollVehicleMs
    }

    /** True while the dense cadence is warranted; the cell-info scan follows it. */
    private fun movingFast(): Boolean =
        runCatching { Mobility.state.value.aboveWalking && !batteryStarved }.getOrDefault(false)

    private suspend fun pollOnce() {
        val subs = LiveState.sims.value.keys.ifEmpty { return }
        tick++
        for (subId in subs) {
            val tm = tmFor(subId) ?: continue
            // Only supplement. If the push stream is alive, adding polled rows would double-count
            // and distort every rate we derive from row density.
            val fresh = LiveState.sims.value[subId]?.signalStale(8_000) == false
            if (fresh) continue

            val ss = runCatching { tm.signalStrength }.getOrNull()
            val sig = ss?.let { runCatching { readSig(it) }.getOrNull() }
            if (sig != null) {
                val act = runCatching { tm.dataActivity }.getOrNull()
                LiveState.updateSim(subId) { applySig(it, sig, act) }
                write(subId, "poll", sig, act)
            }

            // Serving-cell identity is what the churn endpoint is built on, so it has to be
            // refreshed too -- a frozen CI reads as a perfectly stable cell.
            //
            // Every other tick when stationary, because this one is a modem scan and the more
            // expensive of the two reads. Every tick while moving faster than walking: the cell
            // is the measurement on a journey, and halving its sample rate would leave a 10 s
            // blind spot around each change at the 5 s cadence -- wider than the 6 s window the
            // handover classes are inferred over, which would make them meaningless.
            if (tick % 2 == 0L || movingFast()) runCatching {
                tm.requestCellInfoUpdate(exec, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        runCatching { LiveState.updateSim(subId) { applyServing(it, cellInfo) } }
                        recordNeighbours(subId, cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {}
                })
            }
        }
    }

    /**
     * One radio row.
     *
     * **Identity is written null when the latest cell report had no registered LTE/NR cell**,
     * rather than carrying the previous cell forward. What was checked before doing that: every
     * stored-data consumer that derives a cell change -- PingPong.readChanges, Compaction's fold,
     * IncidentEngine's churn scan -- skips a null identity WITHOUT updating its "previous cell",
     * so A, null, A reads as no change and A, null, B as exactly one. MapBins only counts a cell
     * share where ci or pci is non-null. Mobility and Excursion read live state, not these rows,
     * and live state still holds the last cell. So a null cannot fabricate a change; what it
     * removes is a fabricated *stable* cell.
     *
     * **cellAgeMs** is the age of the last registered-cell reading, measured by the modem, at the
     * moment of writing -- kept even when the identity is withheld, where it says how long it has
     * been since any registered cell was seen.
     */
    private fun write(subId: Int, mode: String, sig: Sig, act: Int?) {
        val s = LiveState.sims.value[subId]
        val nowWall = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        scope.launch {
            runCatching {
                val withheld = s?.servingReported == false
                val id = if (withheld) null else s
                val cellAge = s?.cellMillis?.takeIf { it > 0L }?.let { (nowWall - it).coerceAtLeast(0L) }
                // "—" is the placeholder for an unknown network and used to be split into mcc "—"
                // and mnc "—", which read as a network code. Unknown is null.
                val plmn = s?.plmn?.takeIf { plmnRe.matches(it) }

                var q = 0
                if (sig.rsrp == -140 || sig.rsrp == -43) q = q or Quality.RSRP_CLAMPED
                if (sig.rsrq == -34 || sig.rsrq == 3) q = q or Quality.RSRQ_CLAMPED
                if (sig.rssnr == -20 || sig.rssnr == 30) q = q or Quality.RSSNR_CLAMPED
                if (sig.ta != null && sig.ta !in 0..1282) q = q or Quality.TA_OUT_OF_RANGE
                if (sig.cqi != null && sig.cqi !in 0..15) q = q or Quality.CQI_OUT_OF_RANGE
                if (sig.ssRsrp == -140 || sig.ssRsrp == -44 || sig.ssRsrq == -43 || sig.ssRsrq == 20 ||
                    sig.ssSinr == -23 || sig.ssSinr == 40) q = q or Quality.NR_CLAMPED
                if (id?.ci != null || id?.pci != null) {
                    if (cellAge != null && cellAge > Quality.STALE_CELL_MS) q = q or Quality.CELL_STALE
                }
                if (id?.bandAmbiguous == true) q = q or Quality.BAND_AMBIGUOUS
                if (id?.bandReported != null && id.bandDerived != null && id.bandReported != id.bandDerived)
                    q = q or Quality.BAND_MISMATCH
                if (withheld) q = q or Quality.IDENTITY_WITHHELD

                dao.insertRadio(
                    RadioSample(
                        elapsedNanos = nowElapsedNanos,
                        wallMillis = nowWall,
                        subId = subId,
                        mode = mode,
                        rat = s?.rat ?: "—",
                        servingPci = id?.pci, servingCi = id?.ci, servingTac = id?.tac,
                        servingArfcn = id?.arfcn,
                        // Kept for existing readers: derived where the channel resolves, else reported.
                        bandNum = id?.band,
                        mcc = plmn?.substringBefore('-'), mnc = plmn?.substringAfter('-'),
                        rsrp = sig.rsrp, rsrq = sig.rsrq, rssnr = sig.rssnr, cqi = sig.cqi,
                        timingAdvance = sig.ta,
                        level = sig.level, vendorLevel = sig.vendor, dataActivity = act,
                        neighbourCount = s?.neighbourCountSeen,
                        bandReported = id?.bandReported, bandDerived = id?.bandDerived,
                        cellRat = id?.cellRat,
                        cellAgeMs = cellAge,
                        ssRsrp = sig.ssRsrp, ssRsrq = sig.ssRsrq, ssSinr = sig.ssSinr,
                        nrPresent = sig.nrPresent,
                        qualityFlags = q,
                        // Written only when the carrier they were reported for is still the serving
                        // one. A null key means ServiceState gave no channel: the widths are then
                        // unattributable and are withheld rather than guessed onto this row.
                        cellBandwidths = s?.cellBandwidths?.takeIf {
                            s.bandwidthsArfcn != null && s.bandwidthsArfcn == s.arfcn
                        },
                        // From satellite signal, and only while position is already being requested.
                        // Null is "not measured", never "outdoors": the classifier nulls itself when its
                        // inputs are stale or GNSS never came on.
                        indoorProb = runCatching {
                            EnvironmentContext.state.value.indoorProbability
                        }.getOrNull()
                    )
                )
                bumpCounters()
            }
        }
    }

    private val plmnRe = Regex("""\d{3}-\d{2,3}""")

    private suspend fun bumpCounters() {
        LiveState.counters.value = LiveState.counters.value.copy(
            radioRows = dao.radioCount(),
            regRows = dao.regCount(),
            linkRows = dao.linkCount()
        )
    }

    companion object {
        fun ratName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "WCDMA"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "GSM"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
            else -> "T$t"
        }

        fun overrideName(t: Int): String = when (t) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_PRO"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            // 5G+ / mmWave or carrier-defined advanced NR. It used to fall through to "T5", which
            // no consumer recognised as NR at all.
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            else -> "T$t"
        }

        /** Rank-by-RSRP cap for [recordNeighbours]. */
        private const val NEIGHBOURS_KEPT = 6
        /** At rest, one neighbour snapshot a minute per subscription. A cell change bypasses it. */
        private const val NEIGHBOUR_MIN_INTERVAL_MS = 60_000L
        /** Internal "no serving cell" marker for the neighbour gate only; never stored. */
        private const val NO_CELL = Long.MIN_VALUE
    }
}
