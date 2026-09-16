package com.signalscope.collect

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Which carriers serve each subscription, and which of them is primary.
 *
 * Why this exists: the research behind docs/spectrum.md calls primary-versus-secondary role "the
 * missing link between SINR 0 dB and the connection being fine". A 0 dB SINR on a carrier that is
 * only an aggregated secondary costs some throughput; the same reading on the primary is where
 * signalling, RRC state and the uplink live, and is where connections actually fail. Every signal
 * figure this app stores is currently role-blind.
 *
 * ## Tiers, lowest first
 *
 *  - **Tier 0 -- `ServiceState.getCellBandwidths()`.** Public. One width per serving cell, but no
 *    roles and no bands, and its order is not documented, so no role is ever inferred from
 *    position. `getChannelNumber()` is documented as the PRIMARY cell's channel, so that one
 *    attribution is made, at the subscription level rather than on a guessed carrier.
 *  - **Tier 1 -- `PhysicalChannelConfigListener`.** Roles, bands, widths. Needs
 *    READ_PRECISE_PHONE_STATE, which this app does not hold, so it will normally fail to attach.
 *    It is attempted anyway, in its own callback object, because a future grant or a different
 *    build should light it up without a code change.
 *  - **Tier 2 -- `dumpsys telephony.registry` through [ShizukuBridge].** The registry's own
 *    last-known `PhysicalChannelConfig` list per phone, as text. Vendor formats vary, so the parser
 *    is written against lines seen on a real handset and treats anything else as "not recognised"
 *    and falls back to Tier 0 -- never as "no carriers".
 *
 * ## Verification by effect
 *
 * An earlier repair experiment once reported a run of successful trials that changed nothing on the
 * device, because an exit code was taken as evidence of effect. Here, Tier 2 counts as working only
 * when the output yielded at least one parsed physical-channel record. A command that exits 0 and
 * parses nothing is a format miss, and says so in [State.tier2Status].
 *
 * ## Reading the result
 *
 * Secondary carriers exist only while the bearer is RRC-connected. A subscription whose data is
 * idle -- or riding Wi-Fi -- will show its primary alone, and that means "not aggregating right
 * now", not "cannot aggregate". Readings are only comparable against the bearer state at the time.
 *
 * Nothing here is specific to a device, carrier, band or country: bands are whatever the platform
 * reports, labelled by RAT so LTE B40 and NR n40 can never be conflated.
 */
object CarrierAggregation {

    /** Registry snapshot interval. Spawns one shell process; frequent enough to catch CA changes. */
    private const val TIER2_INTERVAL_MS = 90_000L

    /**
     * A Tier 0 service-state change asks for an early Tier 2 read, so a CA transition is caught
     * when it happens rather than up to [TIER2_INTERVAL_MS] later -- but no more often than this,
     * because some modems emit service-state bursts of several per second.
     */
    private const val TIER2_MIN_GAP_MS = 20_000L

    /** Role-bearing evidence older than this is not preferred over current Tier 0 evidence. */
    private const val ROLE_EVIDENCE_STALE_MS = 3 * TIER2_INTERVAL_MS

    /** Only checks whether Tier 0 asked for an early read. No wakelock; idle while dozing. */
    private const val FAST_TICK_MS = 10_000L

    // ------------------------------------------------------------------ model

    enum class Role { PRIMARY, SECONDARY }

    enum class Tier(val level: Int, val label: String) {
        PUBLIC_BANDWIDTHS(0, "Tier 0: public cell bandwidths, no roles"),
        PRECISE_LISTENER(1, "Tier 1: physical channel config listener"),
        SHELL_REGISTRY(2, "Tier 2: telephony registry via Shizuku")
    }

    data class ComponentCarrier(
        /** Null at Tier 0, which reports widths without roles. Never guessed from list order. */
        val role: Role?,
        /** "LTE", "NR", ... as reported. Null when the tier does not say. */
        val rat: String?,
        /** Band number as reported. Meaningless without [rat]: LTE 40 and NR 40 are different bands. */
        val band: Int?,
        /** Downlink channel (EARFCN/NR-ARFCN). Null when unreported; some modems report 0. */
        val channel: Int?,
        val pci: Int?,
        val bandwidthKhz: Int?,
        val tier: Tier
    ) {
        /** "B40" for LTE, "n40" for NR, the bare number otherwise, null if no band. */
        val bandLabel: String?
            get() = band?.let {
                when {
                    rat == null -> "$it"
                    rat.startsWith("NR", ignoreCase = true) -> "n$it"
                    rat.startsWith("LTE", ignoreCase = true) -> "B$it"
                    else -> "$it"
                }
            }
    }

    data class SubCa(
        val subId: Int,
        /** SIM slot, which AOSP uses as the phone id in the registry dump. -1 if unknown. */
        val slot: Int,
        /** Best current evidence: the freshest role-bearing tier, else Tier 0. */
        val carriers: List<ComponentCarrier> = emptyList(),
        val tier: Tier? = null,
        val observedAtMillis: Long? = null,
        /**
         * True/false only when evidence supports it; null when unknown. Two carriers at any tier
         * is aggregation. One carrier from a role-bearing tier is "not aggregating now" (see the
         * class note on idle bearers). Tier 0 with one width is also false. No data is null.
         */
        val aggregated: Boolean? = null,
        /** Why [carriers] is empty or [aggregated] is null. */
        val reason: String? = null,

        /** Tier 0 raw: widths as the platform listed them, kHz. */
        val tier0WidthsKhz: List<Int>? = null,
        /** `ServiceState.getChannelNumber()`: documented as the primary serving cell's channel. */
        val primaryChannel: Int? = null,
        /**
         * Whether the platform's display override says LTE_CA. A carrier-config-driven label, not
         * a measurement -- recorded as corroboration, never as the source of [aggregated].
         */
        val displaySaysLteCa: Boolean? = null,
        /**
         * Tier 0 and a role-bearing tier count different numbers of carriers. Observed on the
         * reference handset (two widths, one physical channel), so it is surfaced rather than
         * resolved silently: one of the two platform views is stale, and which one is the finding.
         */
        val tiersDisagree: Boolean = false,

        /** Most recent per-tier evidence, so a consumer can see what each tier said. */
        val tier1Carriers: List<ComponentCarrier>? = null,
        val tier1AtMillis: Long? = null,
        val tier2Carriers: List<ComponentCarrier>? = null,
        val tier2AtMillis: Long? = null
    ) {
        val primary: ComponentCarrier? get() = carriers.firstOrNull { it.role == Role.PRIMARY }
        val secondaries: List<ComponentCarrier> get() = carriers.filter { it.role == Role.SECONDARY }

        /**
         * The role a band holds on this subscription right now, or null if it is not serving or
         * roles are unknown. Takes the RAT so "40" cannot match both LTE and NR.
         */
        fun roleOf(rat: String, band: Int): Role? =
            carriers.firstOrNull { it.band == band && it.rat?.startsWith(rat, true) == true }?.role
    }

    data class State(
        val subs: Map<Int, SubCa> = emptyMap(),
        val tier1Status: String = "not started",
        val tier2Status: String = "not started",
        /** Records parsed in the last Tier 2 read. Zero means Tier 2 did not work, whatever the exit code. */
        val tier2RecordsParsed: Int = 0,
        val tier2LastAttemptMillis: Long? = null,
        /** Last time Tier 2 was verified by effect (at least one record parsed). */
        val tier2VerifiedAtMillis: Long? = null,
        /** How registry phone ids were mapped to subscriptions: "registry log" or "slot index". */
        val tier2PhoneMapping: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // ------------------------------------------------------------------ lifecycle

    private var job: Job? = null
    private var appCtx: Context? = null
    private var executor: ExecutorService? = null
    private val tier0Regs = ConcurrentHashMap<Int, TelephonyCallback>()
    private val tier1Regs = ConcurrentHashMap<Int, TelephonyCallback>()
    @Volatile private var lastTier2Elapsed = 0L
    @Volatile private var tier2Wanted = false

    @Synchronized
    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        appCtx = ctx.applicationContext
        job = scope.launch {
            // Starts "due" so the first registration and registry read happen promptly.
            var sinceSlow = TIER2_INTERVAL_MS
            while (isActive) {
                val due = sinceSlow >= TIER2_INTERVAL_MS
                // The subscription list is a binder call and SIM changes are rare: slow tick only.
                if (due) runCatching { syncRegistrations() }
                if (due || tier2Wanted) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastTier2Elapsed >= TIER2_MIN_GAP_MS) {
                        lastTier2Elapsed = now
                        tier2Wanted = false
                        sinceSlow = 0
                        runCatching { readTier2() }.onFailure { e ->
                            update { it.copy(tier2Status = "failed: ${e.javaClass.simpleName}") }
                        }
                    }
                }
                // Re-merge so role evidence that has aged out stops being preferred.
                runCatching { rebuildAll() }
                delay(FAST_TICK_MS)
                sinceSlow += FAST_TICK_MS
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        val ctx = appCtx
        (tier0Regs.entries + tier1Regs.entries).forEach { (subId, cb) ->
            runCatching { ctx?.let { tmFor(it, subId) }?.unregisterTelephonyCallback(cb) }
        }
        tier0Regs.clear(); tier1Regs.clear()
        runCatching { executor?.shutdown() }
        executor = null
    }

    private fun tmFor(ctx: Context, subId: Int): TelephonyManager? = runCatching {
        // Per subscription, never the bare manager: that one silently serves the default sub.
        ctx.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
    }.getOrNull()

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is declared; a refusal is caught
    private fun activeSubs(ctx: Context): List<Pair<Int, Int>> = runCatching {
        ctx.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty()
            .map { it.subscriptionId to it.simSlotIndex }
    }.getOrDefault(emptyList())

    /** Follows SIM changes over a multi-day run: attach new subscriptions, drop removed ones. */
    @Synchronized
    private fun syncRegistrations() {
        val ctx = appCtx ?: return
        val exec = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        val subs = activeSubs(ctx)
        val live = subs.map { it.first }.toSet()

        (tier0Regs.keys + tier1Regs.keys).filter { it !in live }.toSet().forEach { gone ->
            val tm = tmFor(ctx, gone)
            tier0Regs.remove(gone)?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
            tier1Regs.remove(gone)?.let { cb -> runCatching { tm?.unregisterTelephonyCallback(cb) } }
            update { s -> s.copy(subs = s.subs - gone) }
        }

        for ((subId, slot) in subs) {
            if (tier0Regs.containsKey(subId)) continue
            val tm = tmFor(ctx, subId) ?: continue
            update { s -> s.copy(subs = s.subs + (subId to (s.subs[subId] ?: SubCa(subId, slot)).copy(slot = slot))) }

            val t0 = Tier0Callback(subId)
            if (runCatching { tm.registerTelephonyCallback(exec, t0) }.isSuccess) tier0Regs[subId] = t0
            // Seed Tier 0 immediately rather than waiting for the first change.
            runCatching { tm.serviceState }.getOrNull()?.let { onServiceState(subId, it) }

            // Tier 1 in its OWN callback object. registerTelephonyCallback checks permissions for
            // every listener the object implements and throws for the whole registration if one
            // is missing, so folding this into Tier0Callback would take Tier 0 down with it.
            val t1 = Tier1Callback(subId)
            runCatching { tm.registerTelephonyCallback(exec, t1) }
                .onSuccess {
                    tier1Regs[subId] = t1
                    update { it.copy(tier1Status = "attached") }
                }
                .onFailure { e ->
                    update {
                        it.copy(tier1Status = "not attached: ${e.javaClass.simpleName}" +
                            (if (e is SecurityException) " (READ_PRECISE_PHONE_STATE not held)" else ""))
                    }
                }
        }
    }

    // ------------------------------------------------------------------ Tier 0

    private class Tier0Callback(private val subId: Int) :
        TelephonyCallback(), TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener {
        override fun onServiceStateChanged(ss: ServiceState) {
            runCatching { onServiceState(subId, ss) }
        }

        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            runCatching {
                val ca = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
                updateSub(subId) { it.copy(displaySaysLteCa = ca) }
            }
        }
    }

    private val tier0 = ConcurrentHashMap<Int, Pair<Long, List<Int>>>()

    private fun onServiceState(subId: Int, ss: ServiceState) {
        val widths = runCatching { ss.cellBandwidths.toList() }.getOrNull()
            ?.filter { validInt(it) } // a width of 0 or MAX_VALUE is "unknown", not "narrow"
        val ch = runCatching { ss.channelNumber }.getOrNull()?.takeIf { validInt(it) }
        val prev = tier0[subId]?.second
        if (widths != null) tier0[subId] = System.currentTimeMillis() to widths
        updateSub(subId) { it.copy(tier0WidthsKhz = widths, primaryChannel = ch) }
        // A change in the serving set is exactly when roles may have moved; look sooner.
        if (widths != prev) tier2Wanted = true
        rebuild(subId)
    }

    // ------------------------------------------------------------------ Tier 1

    private val tier1 = ConcurrentHashMap<Int, Pair<Long, List<ComponentCarrier>>>()

    private class Tier1Callback(private val subId: Int) :
        TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener {
        override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
            runCatching {
                val cc = configs.mapNotNull { c -> runCatching { fromPlatform(c) }.getOrNull() }
                tier1[subId] = System.currentTimeMillis() to cc
                rebuild(subId)
            }
        }
    }

    private fun fromPlatform(c: PhysicalChannelConfig): ComponentCarrier {
        val role = when (runCatching { c.connectionStatus }.getOrNull()) {
            // CellInfo's constants: the PhysicalChannelConfig copies are deprecated aliases.
            android.telephony.CellInfo.CONNECTION_PRIMARY_SERVING -> Role.PRIMARY
            android.telephony.CellInfo.CONNECTION_SECONDARY_SERVING -> Role.SECONDARY
            else -> null
        }
        val nt = runCatching { c.networkType }.getOrNull()
        val rat = when (nt) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            null, TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            else -> "type$nt"
        }
        return ComponentCarrier(
            role = role,
            rat = rat,
            band = runCatching { c.band }.getOrNull()?.takeIf { validInt(it) },
            channel = runCatching { c.downlinkChannelNumber }.getOrNull()?.takeIf { validInt(it) },
            pci = runCatching { c.physicalCellId }.getOrNull()?.takeIf { it >= 0 && it != Int.MAX_VALUE },
            bandwidthKhz = runCatching { c.cellBandwidthDownlinkKhz }.getOrNull()?.takeIf { validInt(it) },
            tier = Tier.PRECISE_LISTENER
        )
    }

    // ------------------------------------------------------------------ Tier 2

    private val tier2 = ConcurrentHashMap<Int, Pair<Long, List<ComponentCarrier>>>()

    private suspend fun readTier2() {
        val now = System.currentTimeMillis()
        if (ShizukuBridge.state.value != ShizukuState.READY) {
            update {
                it.copy(tier2Status = "Shizuku ${ShizukuBridge.state.value.label}",
                    tier2RecordsParsed = 0, tier2LastAttemptMillis = now)
            }
            return
        }
        val r = ShizukuBridge.exec("dumpsys telephony.registry")
        val parsed = parseRegistry(r.out)
        val records = parsed.byPhone.values.sumOf { it.size }

        // Map registry phone ids to subscriptions. The registry's own log lines state the pairing
        // ("subId=6 phoneId=0"); prefer those, and fall back to slot index, which is what AOSP uses
        // as the phone id -- a convention rather than a contract, hence the recorded source.
        val ctx = appCtx
        val subs = ctx?.let { activeSubs(it) }.orEmpty()
        val liveSubs = subs.map { it.first }.toSet()
        val bySlot = subs.filter { it.second >= 0 }.associate { it.second to it.first }
        // A logged pairing only counts if it names a subscription that is active now; any phone
        // the log does not pair falls back to its slot.
        val fromLog = parsed.phoneToSub.filterValues { liveSubs.isEmpty() || it in liveSubs }
        val phoneToSub: Map<Int, Int> = bySlot + fromLog
        val mappingSource = when {
            fromLog.isEmpty() -> "slot index"
            fromLog.keys.containsAll(parsed.byPhone.keys) -> "registry log"
            else -> "registry log, slot index for the rest"
        }

        if (records > 0) {
            tier2.clear()
            for ((phone, cc) in parsed.byPhone) {
                val sub = phoneToSub[phone] ?: continue
                if (liveSubs.isNotEmpty() && sub !in liveSubs) continue
                // An explicitly empty list is a real reading of "no physical channel", but it
                // carries no role information, so it is stored empty and never preferred over
                // Tier 0 (see rebuild). Absent data must not read as "no carriers".
                tier2[sub] = now to cc
            }
        }

        update {
            it.copy(
                tier2RecordsParsed = records,
                tier2LastAttemptMillis = now,
                tier2VerifiedAtMillis = if (records > 0) now else it.tier2VerifiedAtMillis,
                tier2PhoneMapping = if (records > 0) mappingSource else it.tier2PhoneMapping,
                tier2Status = when {
                    records > 0 -> "verified: $records physical channel record(s) across " +
                        "${parsed.byPhone.size} phone(s)"
                    !r.ok && r.out.isBlank() -> "command failed (code ${r.code})"
                    parsed.listsSeen > 0 -> "ran; registry lists no physical channel on any phone " +
                        "(idle or unreported) -- not verified"
                    else -> "ran (code ${r.code}) but no physical channel line was recognised; " +
                        "format differs on this build -- falling back to Tier 0"
                }
            )
        }
    }

    internal data class RegistryParse(
        val byPhone: Map<Int, List<ComponentCarrier>>,
        val phoneToSub: Map<Int, Int>,
        /** `mPhysicalChannelConfigs=[...]` lines seen, including empty ones. */
        val listsSeen: Int
    )

    /*
     * Line format this was written against, from `dumpsys telephony.registry` on a real handset
     * (Android 16 build, 2026-09-13), inside the "last known state:" section:
     *
     *   Phone Id=1
     *     ...
     *     mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=20000,
     *       mCellBandwidthUplinkKhz=0,mNetworkType=LTE_CA,mFrequencyRange=LOW,mDownlinkChannelNumber=0,
     *       mUplinkChannelNumber=0,mContextIds=[],mPhysicalCellId=157,mBand=3,mDownlinkFrequency=0,
     *       mUplinkFrequency=0}, {mConnectionStatus=SecondaryServing,...}]
     *
     * (one line in the real output). The "local logs:" section after it carries lines such as
     * "notifyServiceStateForSubscriber: subId=2 phoneId=1", which give the phone-to-sub pairing.
     *
     * Everything is matched per field rather than by position, and every field is optional, so a
     * build that adds, drops or reorders fields degrades to fewer fields rather than to nothing.
     * Older AOSP spells status as a number (mCellConnectionStatus=1); both are accepted.
     */
    private val phoneHeaderRe = Regex("""^\s*Phone Id\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val pccListRe = Regex("""mPhysicalChannelConfigs\s*=\s*\[(.*)]\s*$""")
    private val recordRe = Regex("""\{([^{}]*)\}""")
    private val subPhoneRe = Regex("""subId=(\d+)\s*,?\s*phoneId=(\d+)""", RegexOption.IGNORE_CASE)
    private val phoneSubRe = Regex("""phoneId=(\d+)\s*,?\s*subId=(\d+)""", RegexOption.IGNORE_CASE)

    private val fieldRes = ConcurrentHashMap<String, Regex>()

    /** `name=value`, where name is not the tail of a longer identifier (mBand vs mBandwidth). */
    private fun field(rec: String, name: String): String? =
        fieldRes.getOrPut(name) { Regex("""(?<![A-Za-z])$name\s*=\s*([^,\s}]+)""") }
            .find(rec)?.groupValues?.get(1)

    internal fun parseRegistry(out: String): RegistryParse {
        val byPhone = LinkedHashMap<Int, List<ComponentCarrier>>()
        // phone -> (sub -> times paired). Counted rather than last-wins: on the reference handset
        // the tail of the dump lists listener registrations as "subId=2147483647 phoneId=0" --
        // DEFAULT_SUBSCRIPTION_ID, a placeholder, not a pairing -- and last-wins mapped phone 0 to
        // it. Invalid ids are dropped and the most frequent real pairing wins.
        val pairCounts = HashMap<Int, HashMap<Int, Int>>()
        var listsSeen = 0
        var phone: Int? = null
        var inLogs = false

        fun pair(p: String, s: String) {
            val pi = p.toIntOrNull() ?: return
            val si = s.toIntOrNull()?.takeIf { validInt(it) } ?: return
            val m = pairCounts.getOrPut(pi) { HashMap() }
            m[si] = (m[si] ?: 0) + 1
        }

        for (line in out.lineSequence()) {
            runCatching {
                if (line.trim().startsWith("local logs", ignoreCase = true)) { inLogs = true; phone = null }
                if (inLogs) {
                    subPhoneRe.find(line)?.let { m -> pair(m.groupValues[2], m.groupValues[1]) }
                        ?: phoneSubRe.find(line)?.let { m -> pair(m.groupValues[1], m.groupValues[2]) }
                    return@runCatching
                }
                phoneHeaderRe.find(line)?.let { phone = it.groupValues[1].toInt(); return@runCatching }
                val p = phone ?: return@runCatching
                val list = pccListRe.find(line)?.groupValues?.get(1) ?: return@runCatching
                listsSeen++
                val cc = recordRe.findAll(list).mapNotNull { m ->
                    runCatching { parseRecord(m.groupValues[1]) }.getOrNull()
                }.toList()
                byPhone[p] = cc
            }
        }
        val phoneToSub = pairCounts.mapNotNull { (p, subs) ->
            subs.maxByOrNull { it.value }?.let { p to it.key }
        }.toMap()
        return RegistryParse(byPhone, phoneToSub, listsSeen)
    }

    private fun parseRecord(rec: String): ComponentCarrier? {
        val statusRaw = field(rec, "mConnectionStatus") ?: field(rec, "mCellConnectionStatus")
        val role = statusRaw?.let {
            when {
                it.contains("secondary", true) || it == "2" -> Role.SECONDARY
                it.contains("primary", true) || it == "1" -> Role.PRIMARY
                else -> null
            }
        }
        val rat = field(rec, "mNetworkType")?.let { raw ->
            // "LTE_CA" names the same RAT as "LTE"; the CA part is a property of the set.
            when {
                raw.startsWith("NR", true) -> "NR"
                raw.startsWith("LTE", true) -> "LTE"
                raw.equals("UNKNOWN", true) -> null
                else -> raw
            }
        }
        val c = ComponentCarrier(
            role = role,
            rat = rat,
            band = field(rec, "mBand")?.toIntOrNull()?.takeIf { validInt(it) },
            channel = field(rec, "mDownlinkChannelNumber")?.toIntOrNull()?.takeIf { validInt(it) },
            pci = field(rec, "mPhysicalCellId")?.toIntOrNull()?.takeIf { it >= 0 && it != Int.MAX_VALUE },
            bandwidthKhz = field(rec, "mCellBandwidthDownlinkKhz")?.toIntOrNull()?.takeIf { validInt(it) },
            tier = Tier.SHELL_REGISTRY
        )
        // A record is real only if it says something: a role, a band or a width. Otherwise it is
        // an unrecognised shape, and counting it would be exactly the exit-code mistake.
        return if (c.role != null || c.band != null || c.bandwidthKhz != null) c else null
    }

    // ------------------------------------------------------------------ merge

    private fun rebuildAll() {
        _state.value.subs.keys.forEach { rebuild(it) }
    }

    private fun rebuild(subId: Int) {
        val now = System.currentTimeMillis()
        val t1 = tier1[subId]?.takeIf { now - it.first <= ROLE_EVIDENCE_STALE_MS && it.second.isNotEmpty() }
        val t2 = tier2[subId]?.takeIf { now - it.first <= ROLE_EVIDENCE_STALE_MS && it.second.isNotEmpty() }
        val t0 = tier0[subId]
        // Tier 1 is event-driven, so its age reflects when the set last changed, not when it was
        // last true; the freshest of the two role-bearing tiers wins rather than a fixed order.
        val roles = listOfNotNull(t1?.let { Triple(Tier.PRECISE_LISTENER, it.first, it.second) },
            t2?.let { Triple(Tier.SHELL_REGISTRY, it.first, it.second) }).maxByOrNull { it.second }

        updateSub(subId) { s ->
            val widths = s.tier0WidthsKhz
            val base = s.copy(
                tier1Carriers = tier1[subId]?.second, tier1AtMillis = tier1[subId]?.first,
                tier2Carriers = tier2[subId]?.second, tier2AtMillis = tier2[subId]?.first
            )
            when {
                roles != null -> base.copy(
                    carriers = roles.third,
                    tier = roles.first,
                    observedAtMillis = roles.second,
                    aggregated = roles.third.size > 1,
                    tiersDisagree = widths != null && widths.isNotEmpty() && widths.size != roles.third.size,
                    reason = if (roles.third.size == 1)
                        "one carrier reported; secondaries appear only while the bearer is connected"
                    else null
                )
                t0 != null && t0.second.isNotEmpty() -> base.copy(
                    carriers = t0.second.map { w ->
                        ComponentCarrier(null, null, null, null, null, w, Tier.PUBLIC_BANDWIDTHS)
                    },
                    tier = Tier.PUBLIC_BANDWIDTHS,
                    observedAtMillis = t0.first,
                    aggregated = t0.second.size > 1,
                    tiersDisagree = false,
                    reason = "roles and bands unknown: no role-bearing tier has current evidence"
                )
                else -> base.copy(
                    carriers = emptyList(), tier = null, observedAtMillis = null, aggregated = null,
                    tiersDisagree = false,
                    reason = if (t0 == null) "no service state received yet"
                    else "platform reports no serving cell bandwidths (out of service, or unreported)"
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Platform "unknown" sentinels: 0, negatives, and Integer.MAX_VALUE (CellInfo.UNAVAILABLE).
     *
     * 0 is a legal EARFCN in principle (the bottom of LTE Band 1), but the reference handset's
     * registry reports mDownlinkChannelNumber=0 beside mDownlinkFrequency=-1 for carriers whose
     * real channel is known to be elsewhere. Dropping a genuine 0 costs one channel number;
     * keeping a fake 0 would put the carrier in the wrong band. So 0 is unknown.
     */
    private fun validInt(v: Int) = v > 0 && v != Int.MAX_VALUE

    private inline fun update(f: (State) -> State) {
        synchronized(this) { _state.value = f(_state.value) }
    }

    private inline fun updateSub(subId: Int, f: (SubCa) -> SubCa) = update { s ->
        val cur = s.subs[subId] ?: SubCa(subId, runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1))
        s.copy(subs = s.subs + (subId to f(cur)))
    }
}
