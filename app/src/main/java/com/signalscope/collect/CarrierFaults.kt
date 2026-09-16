package com.signalscope.collect

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.PreciseDataConnectionState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Repeating carrier-side bearer failures, told in plain language.
 *
 * **The fault this was built for.** On a dual-SIM reference device the OS telephony log showed one
 * subscription's IMS bearer going CONNECTING -> DISCONNECTED every 20-60 seconds, continuously,
 * with `fail cause: IPV6_RS_RA_FAILED`. The SIM was roaming; its home operator's IMS profile asks
 * for IPv6 only while roaming, the visited network never answered the Router Solicitation, so no
 * address ever arrived and the modem retried forever. IMS is the bearer VoLTE rides on, so HD
 * calling on that SIM had never once worked — and nothing in the phone's UI said so. It was
 * visible only in `dumpsys`. That is the gap this closes.
 *
 * **Two tiers, and we say which one we are on.**
 *
 *  - **Tier 0** (always): [TelephonyCallback.DataConnectionStateListener] needs only
 *    READ_PHONE_STATE, which this app already holds. It reports *state transitions* per
 *    subscription and no cause at all, so what we detect is the *pattern*: teardowns that keep
 *    arriving without the bearer ever settling. Two further limits are worth being honest about:
 *    the public listener reports the subscription's default/internet data state, so an IMS-only
 *    retry loop like the one above may not appear here at all on many builds; and it says nothing
 *    about which APN or why. A pattern is still evidence — it is just weaker evidence.
 *  - **Tier 2** (optional, Shizuku): `dumpsys telephony.registry` names the subscription, the APN
 *    type and the fail cause, which upgrades "this SIM's data keeps failing" into "this SIM's
 *    calling service is failing because the network never gives it an address".
 *
 * READ_PRECISE_PHONE_STATE would give the cause directly at Tier 0 and is deliberately *not* the
 * Tier 0 path: it is signature|privileged and unavailable to this app. It is attempted anyway, in
 * a separate callback object (see [register]) so that its refusal cannot take the Tier 0
 * registration down with it, purely so the feature is better on a build that happens to allow it.
 *
 * Nothing here is allowed to throw. It is started from [CollectorService.onCreate], where an
 * escaping exception kills the process with the notification already posted — which has happened
 * before, from a null TelephonyManager on a device with no telephony at all.
 */
enum class FaultKind(val label: String) {
    /** Repeated setup failures, cause not established. */
    REPEATED_SETUP_FAILURE("connection keeps failing"),
    /** Setup fails at address configuration — the network never provides the address asked for. */
    IPV6_CONFIG_FAILURE("network never provides an address"),
    UNKNOWN("repeated failure, reason unknown")
}

data class Fault(
    val subId: Int,
    /** Descriptive only. Identity is [subId]: on an MEP device a slot carries several profiles. */
    val slot: Int,
    val carrier: String,
    /** e.g. "ims", "default". Null when only Tier 0 saw it — Tier 0 cannot tell which APN. */
    val apnType: String?,
    val kind: FaultKind,
    /** Verbatim from the OS log when Tier 2 read one, e.g. "IPV6_RS_RA_FAILED(0xfffffff7)". */
    val rawCause: String?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val count: Int,
    /** Which tier the *attribution* came from. [Tier.TIER0] means pattern only, no named cause. */
    val tier: Tier,
    val description: String,
    val suggestedAction: String,
    /** Set when the pattern stopped. The fault is kept, and says it stopped, rather than vanishing. */
    val clearedMillis: Long? = null,
    val clearedNote: String? = null
) {
    val active: Boolean get() = clearedMillis == null
}

object CarrierFaults {

    private val _faults = MutableStateFlow<List<Fault>>(emptyList())
    val faults: StateFlow<List<Fault>> = _faults

    /**
     * Why the Tier-2 upgrade is not in use, when it was tried and did not work. Not an error
     * state: Shizuku is optional and a parse miss is expected on vendor builds we have not seen.
     */
    val tier2Note = MutableStateFlow<String?>(null)

    // ------------------------------------------------------------------ thresholds
    //
    // These are the difference between a diagnostic and a nuisance. A single teardown is normal:
    // a handover, a lift, a tunnel, an idle timer all produce one, and one must never raise
    // anything.

    /** Rolling window the Tier 0 pattern is judged over. */
    private const val FAILURE_WINDOW_MS = 60 * 60_000L

    /**
     * Failures inside [FAILURE_WINDOW_MS] before anything is raised.
     *
     * The observed loop retries every 20-60 s, so a genuine one delivers 60-180 failures an hour
     * and crosses 6 within about three to six minutes. Normal use produces nothing like that
     * rate: a commute through a few dead spots costs a handful of teardowns an hour, and each of
     * those is followed by a bearer that settles, which is not counted here at all. Six is
     * therefore well clear of normal and still fast enough to catch a real loop the same morning.
     */
    private const val MIN_FAILURES = 6

    /**
     * The failures must also be spread over at least this long.
     *
     * Without it, six teardowns in ten seconds — one radio hiccup, or a handover storm crossing a
     * cell border — would read as a permanent fault. The fault we care about is *sustained*: it
     * was still going after hours. Requiring five minutes of spread costs nothing against a real
     * loop and removes the entire class of burst false positives.
     */
    private const val MIN_SUSTAINED_MS = 5 * 60_000L

    /**
     * Quiet time after the last failure before a raised fault is marked cleared.
     *
     * Fifteen minutes is fifteen times the longest retry gap observed (60 s), so silence this long
     * means the loop stopped rather than that we looked between two retries.
     */
    private const val CLEAR_AFTER_MS = 15 * 60_000L

    /**
     * How long a bearer must hold CONNECTED before a later teardown counts as normal life rather
     * than as churn. Longer than any RRC inactivity timer, short enough that a working connection
     * qualifies almost immediately.
     */
    private const val STABLE_CONNECTED_MS = 2 * 60_000L

    /** Cleared faults are kept this long, so "it cleared at 04:10" is still answerable. */
    private const val CLEARED_RETAIN_MS = 24 * 60 * 60_000L

    private const val EVAL_MS = 60_000L
    /** A dumpsys through Shizuku is not free, and the pattern is measured in minutes, not seconds. */
    private const val TIER2_POLL_MS = 5 * 60_000L
    /** Tier-2 attribution older than this is dropped: it describes a log window that has rolled. */
    private const val TIER2_TTL_MS = 20 * 60_000L
    /** Bound on retained event times per subscription, so a week-long loop cannot grow unbounded. */
    private const val MAX_EVENTS_PER_SUB = 512

    private const val PREFS = "carrier_faults"
    private const val KEY_FAULTS = "faults"
    private const val KEY_EVENTS = "events"

    // ------------------------------------------------------------------ state

    private class Track(val subId: Int, var slot: Int, var carrier: String) {
        /** Wall times of teardowns that arrived without the bearer ever settling. */
        val failures = ArrayDeque<Long>()
        var connectedSince = 0L
        var lastState = Int.MIN_VALUE
        /** Newest event already added to a fault's running total. */
        var reportedThroughMillis = 0L
    }

    private data class Tier2Evidence(
        val subId: Int,
        val apnType: String?,
        val cause: String?,
        val count: Int,
        val atMillis: Long,
        /**
         * True when this came from a Shizuku dumpsys, false when it came from the privileged
         * precise listener. Tracked because the fault must name the tier the evidence actually
         * came from: reporting a privileged-build reading as "Tier 2 · Shizuku" would credit a
         * mechanism that was never used.
         */
        val viaShizuku: Boolean
    )

    private val exec = Executors.newSingleThreadExecutor()
    private val tracks = ConcurrentHashMap<Int, Track>()
    private val registrations = ConcurrentHashMap<Int, TelephonyCallback>()
    private val preciseRegs = ConcurrentHashMap<Int, TelephonyCallback>()
    private val tier2 = ConcurrentHashMap<Int, Tier2Evidence>()
    private val current = ConcurrentHashMap<Int, Fault>()

    @Volatile private var started = false
    @Volatile private var lastSaveMillis = 0L
    private var loopJob: Job? = null
    private var tier2Job: Job? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Idempotent, and safe to call before any permission has been granted: with no readable
     * subscriptions there is simply nothing to register, and the next tick picks up a SIM when
     * one appears.
     */
    /**
     * Wall time this detector began watching, or 0 when it never has.
     *
     * Published so an empty result can say how long it has been empty. "No repeating faults
     * found" is a much weaker statement than "watched for forty minutes and found none", and
     * without this the UI can only condition on whether collection is running at all -- which
     * cannot distinguish a clean hour from a detector that started ten seconds ago.
     */
    @Volatile var watchingSinceMillis: Long = 0L
        private set

    fun start(ctx: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        watchingSinceMillis = System.currentTimeMillis()
        val app = ctx.applicationContext
        runCatching { load(app) }

        loopJob = scope.launch {
            while (isActive) {
                // Subscriptions are re-scanned every tick rather than watched with a listener:
                // it costs one cheap call a minute and handles a SIM swap, an eSIM profile being
                // enabled, and permission being granted after start, with no extra machinery.
                runCatching { syncRegistrations(app) }
                runCatching { evaluate(app) }
                delay(EVAL_MS)
            }
        }

        tier2Job = scope.launch {
            // Shizuku's binder arrives asynchronously after init, so the first sweep would
            // otherwise always report "not ready" and set a note that is merely early.
            delay(20_000)
            while (isActive) {
                runCatching { tier2Sweep(app) }
                delay(TIER2_POLL_MS)
            }
        }
    }

    fun stop() {
        watchingSinceMillis = 0L
        loopJob?.cancel(); loopJob = null
        tier2Job?.cancel(); tier2Job = null
        tracks.keys.toList().forEach { unregister(it) }
        started = false
    }

    // ------------------------------------------------------------------ Tier 0 registration

    private fun tmFor(ctx: Context, subId: Int): TelephonyManager? = runCatching {
        val base = ctx.getSystemService(TelephonyManager::class.java) ?: return null
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base
        else base.createForSubscriptionId(subId)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun syncRegistrations(ctx: Context) {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val active = runCatching { sm?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val wanted = active.associate { info ->
            info.subscriptionId to Pair(info.simSlotIndex, info.carrierName?.toString() ?: "—")
        }

        // A SIM that has gone away stops being watched, but its evidence and any raised fault are
        // kept: "the SIM you took out was failing" is still the answer to the user's question.
        registrations.keys.filter { it !in wanted.keys }.forEach { unregister(it) }

        wanted.forEach { (subId, meta) ->
            val t = tracks.getOrPut(subId) { Track(subId, meta.first, meta.second) }
            t.slot = meta.first
            t.carrier = meta.second
            if (!registrations.containsKey(subId)) register(ctx, subId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun register(ctx: Context, subId: Int) {
        // Per subscription, never the bare TelephonyManager: that one silently serves the default
        // subscription, so on a dual-SIM handset it would watch one SIM twice and the other never.
        val tm = tmFor(ctx, subId) ?: return

        val cb = StateCallback(subId)
        if (runCatching { tm.registerTelephonyCallback(exec, cb) }.isFailure) return
        registrations[subId] = cb

        // Registered as its OWN callback object on purpose. registerTelephonyCallback checks the
        // permissions for every listener interface the object implements and throws for the whole
        // registration if one is missing — so folding the precise listener into StateCallback
        // would mean READ_PRECISE_PHONE_STATE's absence silently killed the Tier 0 path we
        // actually depend on. Separate objects means the privileged one simply does not attach.
        runCatching {
            val p = PreciseCallback(subId)
            tm.registerTelephonyCallback(exec, p)
            preciseRegs[subId] = p
        }
    }

    private fun unregister(subId: Int) {
        val cb = registrations.remove(subId)
        val p = preciseRegs.remove(subId)
        val tm = lastCtx?.let { tmFor(it, subId) }
        cb?.let { runCatching { tm?.unregisterTelephonyCallback(it) } }
        p?.let { runCatching { tm?.unregisterTelephonyCallback(it) } }
    }

    /** Held only so [unregister] can reach a TelephonyManager; always the application context. */
    @Volatile private var lastCtx: Context? = null

    private class StateCallback(private val subId: Int) :
        TelephonyCallback(), TelephonyCallback.DataConnectionStateListener {
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            runCatching { onDataState(subId, state) }
        }
    }

    /**
     * Only attaches where READ_PRECISE_PHONE_STATE is somehow held. Everything it adds is a bonus
     * on top of a feature that is complete without it.
     *
     * The fail *cause* here is an int from a @SystemApi table with no public name mapping, so it
     * is reported as a number rather than dressed up as a name we cannot verify. The APN profile
     * is the more useful half anyway: an IMS APN whose roaming protocol is IPv6-only, failing
     * while the subscription is roaming, is the fault in the reference case stated as a
     * configuration fact rather than inferred from a cause string.
     */
    private class PreciseCallback(private val subId: Int) :
        TelephonyCallback(), TelephonyCallback.PreciseDataConnectionStateListener {
        override fun onPreciseDataConnectionStateChanged(s: PreciseDataConnectionState) {
            runCatching {
                if (s.state != TelephonyManager.DATA_DISCONNECTED) return@runCatching
                val cause = runCatching { s.lastCauseCode }.getOrNull() ?: return@runCatching
                if (cause == 0) return@runCatching  // 0 is "no failure": a normal teardown
                val apn: ApnSetting? = runCatching { s.apnSetting }.getOrNull()
                val type = apn?.let { apnTypeName(runCatching { it.apnTypeBitmask }.getOrDefault(0)) }
                // The profile fact behind the reference fault: IPv6-only while roaming. Stated
                // from the APN profile itself rather than guessed from a cause code we cannot name.
                val v6Only = runCatching {
                    apn != null && apn.roamingProtocol == ApnSetting.PROTOCOL_IPV6
                }.getOrDefault(false)
                noteEvidence(
                    Tier2Evidence(
                        subId = subId,
                        apnType = type,
                        cause = if (v6Only) "roaming profile is IPv6-only (platform cause $cause)"
                                else "platform cause $cause",
                        // One event, not a count: the running total stays Tier 0's, which is
                        // timed. This exists to name the APN and the cause, not to tally.
                        count = 0,
                        atMillis = System.currentTimeMillis(),
                        viaShizuku = false
                    )
                )
            }
        }
    }

    private fun onDataState(subId: Int, state: Int) {
        val t = tracks[subId] ?: return
        val now = System.currentTimeMillis()
        synchronized(t) {
            if (state == TelephonyManager.DATA_CONNECTED && t.connectedSince == 0L)
                t.connectedSince = now
            if (state == TelephonyManager.DATA_DISCONNECTED) {
                val held = if (t.connectedSince == 0L) 0L else now - t.connectedSince
                t.connectedSince = 0L
                // A bearer that held for a while and then dropped is ordinary life. Only a
                // teardown that arrives without the connection ever settling is churn.
                val settled = held >= STABLE_CONNECTED_MS
                // The registry re-notifies the same state on several builds; counting those would
                // inflate the rate by whatever the vendor's notify cadence happens to be.
                val duplicate = t.lastState == TelephonyManager.DATA_DISCONNECTED
                if (!settled && !duplicate && inService(subId)) {
                    t.failures.addLast(now)
                    while (t.failures.size > MAX_EVENTS_PER_SUB) t.failures.removeFirst()
                }
            }
            t.lastState = state
        }
    }

    /**
     * Out of coverage is not a carrier fault. A phone in a lift tears its bearer down and retries
     * exactly like a broken APN profile does, and the only thing separating the two is whether the
     * radio had service at the time — which [LiveState] already knows. Unknown is not treated as
     * out of service: absent evidence must not suppress a real detection.
     */
    private fun inService(subId: Int): Boolean {
        val s = LiveState.sims.value[subId]?.serviceState ?: return true
        return s == "IN_SERVICE" || s == "—" || s == "UNKNOWN"
    }

    private fun noteEvidence(e: Tier2Evidence) {
        val prev = tier2[e.subId]
        // Keep whichever count is larger: a dump that has rolled shows fewer lines than one taken
        // mid-loop, and the smaller number is an artefact of the log window, not an improvement.
        tier2[e.subId] = e.copy(count = maxOf(e.count, prev?.count ?: 0))
    }

    // ------------------------------------------------------------------ Tier 2

    private const val MARKER = "notifyDataConnectionForSubscriber"
    private val RE_SUBID = Regex("""subId\s*[=:]?\s*(\d+)""")
    private val RE_STATE = Regex("""state\s*[=:]\s*([A-Za-z_]+)""")
    private val RE_CAUSE =
        Regex("""fail\s*cause\s*[=:]\s*([A-Za-z0-9_]+)\s*(\(0x[0-9a-fA-F]+\))?""")

    /**
     * Reads `dumpsys telephony.registry` and parses the data-connection notifications out of it.
     *
     * **Verified by effect, not by exit code.** A Shizuku command that returns 0 may have produced
     * nothing usable — an earlier repair experiment trusted the exit status and so reported a run
     * of successful trials that changed nothing on the device. The only thing that counts as
     * success here is therefore having parsed at least one `notifyDataConnectionForSubscriber`
     * record out of the output. Anything else sets a note and leaves detection on Tier 0, which is
     * fully functional on its own.
     */
    private suspend fun tier2Sweep(ctx: Context) {
        if (ShizukuBridge.state.value != ShizukuState.READY) {
            tier2Note.value = null   // not an upgrade we failed at; it was never offered
            return
        }
        val r = ShizukuBridge.exec("dumpsys telephony.registry")
        val records = parseRegistry(r.out)
        if (records.isEmpty()) {
            tier2Note.value = if (r.out.contains(MARKER))
                "Read the telephony registry but could not parse its data-connection lines on " +
                        "this build; detection is using connection-state patterns instead."
            else
                "The telephony registry dump contained no data-connection notifications " +
                        "(${r.out.length} chars read); detection is using connection-state patterns."
            return
        }
        tier2Note.value = null

        // Only failures: a record whose cause is NONE is a successful teardown and says nothing.
        val failures = records.filter { it.cause != null && !it.cause.equals("NONE", true) }
        val now = System.currentTimeMillis()

        failures.groupBy { it.subId }.forEach { (subId, rows) ->
            // The most frequent (APN, cause) pair in the log is the loop; a stray single failure
            // from something else must not rename it.
            val dominant = rows.groupBy { Pair(it.apnType, it.cause) }
                .maxByOrNull { it.value.size } ?: return@forEach
            noteEvidence(
                Tier2Evidence(
                    subId = subId,
                    apnType = dominant.key.first,
                    cause = dominant.key.second,
                    count = dominant.value.size,
                    atMillis = now,
                    viaShizuku = true
                )
            )
        }
        // Attribution that has not been refreshed by a dump for a while describes a log window
        // that has since rolled over, so it is dropped rather than left to age quietly.
        tier2.entries.filter { now - it.value.atMillis > TIER2_TTL_MS }
            .forEach { tier2.remove(it.key) }
    }

    private data class Record(val subId: Int, val state: String?, val apnType: String?, val cause: String?)

    /**
     * Deliberately loose. Vendor builds reorder these fields, pad them differently, and split the
     * fail cause onto a continuation line under the APN — which is exactly how the reference
     * device printed it. So each record is the marker line plus the lines that follow it until the
     * next marker, and every field is searched for by name inside that block rather than taken by
     * position. A field we cannot find stays null and the description simply says less.
     */
    private fun parseRegistry(out: String): List<Record> {
        if (out.isBlank()) return emptyList()
        val lines = out.lines()
        val records = mutableListOf<Record>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].contains(MARKER)) { i++; continue }
            val head = lines[i]
            val block = StringBuilder(head)
            var j = i + 1
            // 12 lines is generous for one record's continuation and short enough that a missing
            // terminator cannot swallow the next record's cause.
            while (j < lines.size && j - i <= 12 && !lines[j].contains(MARKER)) {
                block.appendLine(); block.append(lines[j]); j++
            }
            val text = block.toString()
            val subId = RE_SUBID.find(head)?.groupValues?.get(1)?.toIntOrNull()
            if (subId != null) {
                val cause = RE_CAUSE.find(text)?.let { m ->
                    m.groupValues[1] + m.groupValues[2]   // NAME(0xhex) when the hex is present
                }
                records += Record(
                    subId = subId,
                    state = RE_STATE.find(head)?.groupValues?.get(1)?.uppercase(),
                    apnType = apnTypeFromDump(text),
                    cause = cause?.takeIf { it.isNotBlank() }
                )
            }
            i = j
        }
        return records
    }

    /**
     * The APN types the platform knows, paired with the token the dump prints. The list is the
     * platform's own constants, so it carries no carrier or device assumption; it is used both to
     * name a type from a bitmask and to recognise one in dumped text.
     */
    private val APN_TYPES: List<Pair<Int, String>> = listOf(
        ApnSetting.TYPE_DEFAULT to "default",
        ApnSetting.TYPE_MMS to "mms",
        ApnSetting.TYPE_SUPL to "supl",
        ApnSetting.TYPE_DUN to "dun",
        ApnSetting.TYPE_HIPRI to "hipri",
        ApnSetting.TYPE_FOTA to "fota",
        ApnSetting.TYPE_IMS to "ims",
        ApnSetting.TYPE_CBS to "cbs",
        ApnSetting.TYPE_IA to "ia",
        ApnSetting.TYPE_EMERGENCY to "emergency",
        ApnSetting.TYPE_MCX to "mcx",
        ApnSetting.TYPE_XCAP to "xcap",
        ApnSetting.TYPE_ENTERPRISE to "enterprise",
        ApnSetting.TYPE_RCS to "rcs"
    )

    private fun apnTypeName(bitmask: Int): String? {
        if (bitmask == 0) return null
        // An APN can carry several types; the narrowest single type is the informative one, and
        // "default" is reported last so a default+ims profile is described as ims.
        return APN_TYPES.sortedBy { if (it.second == "default") 1 else 0 }
            .firstOrNull { (bit, _) -> bitmask and bit != 0 }?.second
    }

    private fun apnTypeFromDump(text: String): String? {
        // Prefer an explicit field where the build prints one.
        Regex("""apnType[s]?\s*[=:]\s*([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase()
            ?.let { t -> APN_TYPES.firstOrNull { it.second == t }?.let { return it.second } }
        // Otherwise scan the ApnSetting line's tokens. Whole-token matching only: an apn *name*
        // containing "ims" as a substring must not be read as the ims type.
        val apnLine = text.lines().firstOrNull { it.contains("ApnSetting", true) } ?: return null
        val tokens = apnLine.lowercase().split(Regex("""[^a-z0-9_]+""")).filter { it.isNotEmpty() }
        // Same ordering rule as [apnTypeName]: a profile carrying several types is described by
        // the specific one, because "default" is true of half the profiles on the device.
        return APN_TYPES.map { it.second }
            .sortedBy { if (it == "default") 1 else 0 }
            .firstOrNull { it in tokens }
    }

    // ------------------------------------------------------------------ evaluation

    private data class Ctx(
        val labels: Map<Int, String>,
        val carriers: Map<Int, String>,
        val roaming: Map<Int, Boolean>,
        val dataSubId: Int,
        val multiSim: Boolean
    )

    @SuppressLint("MissingPermission")
    private fun contextOf(ctx: Context): Ctx {
        lastCtx = ctx
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val active = runCatching { sm?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val profile = LiveState.profile.value
        val labels = mutableMapOf<Int, String>()
        val carriers = mutableMapOf<Int, String>()
        val roaming = mutableMapOf<Int, Boolean>()

        val known = if (active.isNotEmpty()) active.map { Triple(it.subscriptionId, it.simSlotIndex,
            it.carrierName?.toString() ?: "—") }
        else tracks.values.map { Triple(it.subId, it.slot, it.carrier) }

        known.forEach { (subId, slot, carrier) ->
            // "SIM 2" is generated from the slot the platform reports, never assumed. With one SIM
            // a number is noise, so it is not used.
            labels[subId] = when {
                known.size <= 1 -> "your SIM"
                slot >= 0 -> "SIM ${slot + 1}"
                carrier != "—" -> carrier
                else -> "subscription $subId"
            }
            carriers[subId] = carrier
            roaming[subId] = LiveState.sims.value[subId]?.roaming
                ?: profile?.sub(subId)?.isRoaming
                ?: runCatching { tmFor(ctx, subId)?.isNetworkRoaming == true }.getOrDefault(false)
        }

        return Ctx(
            labels = labels,
            carriers = carriers,
            roaming = roaming,
            dataSubId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
                .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID),
            multiSim = known.size > 1
        )
    }

    private fun evaluate(ctx: Context) {
        val now = System.currentTimeMillis()
        val c = contextOf(ctx)
        val subjects = (tracks.keys + tier2.keys).toSet()

        subjects.forEach { subId ->
            val t = tracks[subId]
            var tier0Count = 0
            var firstEvent = 0L
            var lastEvent = 0L
            var fresh = 0
            if (t != null) synchronized(t) {
                while (t.failures.isNotEmpty() && now - t.failures.first() > FAILURE_WINDOW_MS)
                    t.failures.removeFirst()
                tier0Count = t.failures.size
                firstEvent = t.failures.firstOrNull() ?: 0L
                lastEvent = t.failures.lastOrNull() ?: 0L
                fresh = t.failures.count { it > t.reportedThroughMillis }
            }

            val ev = tier2[subId]?.takeIf { now - it.atMillis <= TIER2_TTL_MS }
            val tier0Fires = tier0Count >= MIN_FAILURES && (lastEvent - firstEvent) >= MIN_SUSTAINED_MS
            val tier2Fires = ev != null && ev.count >= MIN_FAILURES
            val existing = current[subId]

            if (!tier0Fires && !tier2Fires) {
                // Nothing supports a fault right now. An open one is not withdrawn immediately:
                // silence has to last the quiet window before we claim the pattern stopped.
                //
                // Clearing is prompt for a Tier 0 fault, because those events are timestamped as
                // they arrive. A Tier-2-only fault clears later: the dump is a log of history, so
                // its lines keep the fault alive until they roll out of that log. That is the
                // honest behaviour of the evidence, not a bug to paper over.
                if (existing != null && existing.active && now - existing.lastSeenMillis > CLEAR_AFTER_MS) {
                    current[subId] = existing.copy(
                        clearedMillis = now,
                        clearedNote = "Stopped. No further failures in the " +
                                "${CLEAR_AFTER_MS / 60_000} minutes since the last one, after " +
                                "${existing.count} failures " +
                                spanPhrase(existing.lastSeenMillis - existing.firstSeenMillis) + "."
                    )
                }
                return@forEach
            }

            val kind = classify(ev)
            val lastSeen = maxOf(lastEvent, ev?.atMillis ?: 0L, existing?.lastSeenMillis ?: 0L)
            val firstSeen = when {
                existing != null && existing.active -> existing.firstSeenMillis
                firstEvent != 0L -> firstEvent
                else -> ev?.atMillis ?: now
            }
            // Tier 0's total is timed and accumulates as events arrive; Tier 2's is whatever its
            // log window held. Neither is allowed to shrink a total we have already reported.
            val count = when {
                existing == null || !existing.active ->
                    maxOf(tier0Count, ev?.count ?: 0).coerceAtLeast(1)
                else -> maxOf(existing.count + fresh, ev?.count ?: 0)
            }
            val timed = tier0Fires
            val fault = Fault(
                subId = subId,
                slot = t?.slot ?: -1,
                carrier = c.carriers[subId] ?: "—",
                apnType = ev?.apnType ?: existing?.apnType,
                kind = kind,
                rawCause = ev?.cause ?: existing?.rawCause,
                firstSeenMillis = firstSeen,
                lastSeenMillis = if (lastSeen == 0L) now else lastSeen,
                count = count,
                tier = if (ev?.viaShizuku == true && ev.cause != null) Tier.TIER2 else Tier.TIER0,
                description = "",
                suggestedAction = ""
            )
            current[subId] = fault.copy(
                description = describe(fault, c, timed),
                suggestedAction = suggest(fault, c)
            )
            if (t != null) synchronized(t) {
                t.reportedThroughMillis = maxOf(t.reportedThroughMillis, lastEvent)
            }
        }

        // Drop cleared faults once they are old news, so the list stays about now.
        current.entries.filter { (_, f) ->
            f.clearedMillis != null && now - f.clearedMillis > CLEARED_RETAIN_MS
        }.forEach { current.remove(it.key) }

        // Write only when something actually moved. This loop ticks every minute for the life of
        // the install, and an unconditional commit would be a disk write a minute on a device that
        // is working perfectly — for a file that would be byte-identical each time.
        val changed = publish()
        val newEvents = tracks.values.any { t ->
            synchronized(t) { (t.failures.lastOrNull() ?: 0L) > lastSaveMillis }
        }
        if (changed || newEvents) {
            runCatching { save(ctx) }
            lastSaveMillis = now
        }
    }

    private fun classify(ev: Tier2Evidence?): FaultKind {
        val cause = ev?.cause ?: return FaultKind.REPEATED_SETUP_FAILURE
        val u = cause.uppercase()
        // Address configuration never completing is its own fault: the bearer is granted and then
        // dies because no address arrives. It reads completely differently to a refusal, and the
        // user's action is different too.
        val addressFailure = listOf("IPV6", "RS_RA", "SLAAC", "PDP_ADDRESS", "ADDRESS")
            .any { u.contains(it) }
        return when {
            addressFailure -> FaultKind.IPV6_CONFIG_FAILURE
            u.contains("UNSPECIFIED") || u.contains("UNKNOWN") -> FaultKind.UNKNOWN
            else -> FaultKind.REPEATED_SETUP_FAILURE
        }
    }

    // ------------------------------------------------------------------ plain language
    //
    // The reader has never heard of IMS, SLAAC or a PDN, and should not have to. Every sentence is
    // generated from the evidence in hand: nothing is written for a particular slot, carrier or
    // cause, and a clause whose precondition we cannot establish is simply not emitted rather than
    // hedged into meaninglessness.

    private fun spanPhrase(ms: Long): String = when {
        ms < 90_000 -> "in the last minute or so"
        ms < 90 * 60_000L -> "over the last ${maxOf(1, ms / 60_000)} minutes"
        ms < 36 * 3_600_000L -> "over the last ${maxOf(1, ms / 3_600_000)} hours"
        else -> "over the last ${ms / 86_400_000L} days"
    }

    /** What the failing APN means to someone who has never heard of an APN. */
    private fun serviceWords(apnType: String?): String = when (apnType) {
        null -> "data connection"
        // Parenthetical rather than a dash: this phrase is dropped into the middle of a sentence
        // ("SIM 2's <this> has failed to connect ..."), and a dash there does not survive it.
        "ims" -> "calling service (the feature Settings calls VoLTE, or HD calling)"
        "default" -> "mobile data"
        "mms" -> "picture-messaging service"
        "supl" -> "location-assistance service"
        "xcap" -> "call-settings service"
        "emergency" -> "emergency-call connection"
        "dun" -> "tethering connection"
        else -> "\"$apnType\" connection"
    }

    /** Short form for the action sentence, where the long explanation would be in the way. */
    private fun serviceShort(apnType: String?): String = when (apnType) {
        "ims" -> "HD calling (VoLTE)"
        "default" -> "mobile data"
        null -> "the failing connection"
        else -> "the $apnType connection"
    }

    private fun describe(f: Fault, c: Ctx, timed: Boolean): String {
        val me = c.labels[f.subId] ?: "this SIM"
        val other = c.labels.entries.firstOrNull { it.key != f.subId && it.key == c.dataSubId }?.value
        val carrier = f.carrier.takeIf { it.isNotBlank() && it != "—" }

        val whenPhrase =
            if (timed) spanPhrase(f.lastSeenMillis - f.firstSeenMillis)
            else "in the phone's own telephony log"

        return buildString {
            // 1. What was observed. A count and a period, nothing interpreted yet.
            append("$me's ${serviceWords(f.apnType)} has failed to connect ${f.count} times ")
            append("$whenPhrase, and keeps retrying.")

            // 2. What it probably means, in terms of the thing the network did or did not do.
            append(" ")
            when (f.kind) {
                FaultKind.IPV6_CONFIG_FAILURE -> append(
                    "It is asking this network for a type of internet address the network does " +
                    "not hand out, so the connection is dropped and tried again."
                )
                FaultKind.REPEATED_SETUP_FAILURE -> append(
                    if (f.rawCause != null)
                        "Each attempt is turned down by the network; the phone records the " +
                        "reason as ${f.rawCause}."
                    else
                        "Each attempt is set up and then torn down again before it settles."
                )
                FaultKind.UNKNOWN -> append(
                    "The phone does not record a reason, so what is stopping it is not visible " +
                    "from here — only that it keeps happening."
                )
            }

            // 3. Roaming, when it is true, because a home setting that fails abroad is the single
            //    most common shape of this fault and it tells the user why it started.
            if (c.roaming[f.subId] == true) {
                append(" This SIM is roaming")
                if (carrier != null) append(" (its home network is $carrier)")
                append(", and a setting that works on its home network can fail on a visited one.")
            }

            // 4. What it costs. Only claimed where the APN is known, and never as a certainty.
            if (f.apnType == "ims") append(
                " Calls on $me are probably still connecting, because they fall back to the " +
                "older method automatically — which is why this can go unnoticed for a long time."
            )

            // 5. Contention, offered as a possibility and only when another SIM is carrying data.
            //    Most dual-SIM phones share one radio between both SIMs; we cannot read which
            //    kind this is from a normal app, so it stays a "may".
            if (c.multiSim && other != null) append(
                " Most dual-SIM phones share one radio between both SIMs, so attempts this often " +
                "may be taking time away from $other's data."
            )

            // 6. How strong the evidence is. Tier 0 saw a pattern and no reason; say so rather
            //    than letting the confident sentences above imply more than we measured. A fault
            //    that does carry a cause has already stated it, so this does not apply.
            if (f.tier == Tier.TIER0 && f.rawCause == null) append(
                " This is based on the connection states the phone reports to any app: the " +
                "repetition is the evidence, and the exact reason needs privileges this app " +
                "does not have."
            )
        }
    }

    private fun suggest(f: Fault, c: Ctx): String {
        val me = c.labels[f.subId] ?: "this SIM"
        // A sibling SIM with no active fault of its own is the reason to be specific about which
        // SIM to change: turning the feature off everywhere would break the one that works.
        val healthyOther = c.labels.entries.firstOrNull {
            it.key != f.subId && current[it.key]?.active != true
        }?.value

        return buildString {
            when (f.apnType) {
                "ims" -> {
                    append("You can turn off ${serviceShort(f.apnType)} for $me in Settings, " +
                            "under mobile networks. Calls on $me will use the older method instead.")
                    if (healthyOther != null) append(
                        " Leave it switched on for $healthyOther, where it is working — turning " +
                        "it off there would push those calls onto the older method too."
                    )
                }
                "default" -> append(
                    "If you do not need mobile data on $me, switching its mobile data — or its " +
                    "data roaming — off stops the retrying. Its calls and texts are unaffected."
                )
                null -> append(
                    "Open $me's mobile network settings. If you do not need this SIM's data, " +
                    "turning its data roaming off stops the retrying; otherwise its network " +
                    "operator is the one who can fix the setting behind it."
                )
                else -> append(
                    "In $me's mobile network settings, switching off ${serviceShort(f.apnType)} " +
                    "stops the retrying. If you need it, its network operator is the one who can " +
                    "fix the setting behind it."
                )
            }
            if (c.roaming[f.subId] == true) append(
                " It should start working again by itself once this SIM is back on its home network."
            )
        }
    }

    /** Returns true when the published list actually changed. */
    private fun publish(): Boolean {
        val next = current.values.sortedWith(
            compareByDescending<Fault> { it.active }.thenByDescending { it.lastSeenMillis }
        )
        if (next == _faults.value) return false
        _faults.value = next
        return true
    }

    // ------------------------------------------------------------------ persistence
    //
    // SharedPreferences rather than Room, for the same reason as DeviceProfile: this is a handful
    // of records, not a time series, and adding a table would mean a schema migration that
    // discards collected samples. A fault found at 03:00 has to still be there at 08:00, including
    // across a service restart, so the event times are persisted too — otherwise a restart resets
    // the count and the pattern has to be re-earned from scratch.

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context) {
        val fa = JSONArray()
        current.values.forEach { f ->
            fa.put(JSONObject().apply {
                put("subId", f.subId); put("slot", f.slot); put("carrier", f.carrier)
                put("apnType", f.apnType ?: JSONObject.NULL)
                put("kind", f.kind.name)
                put("rawCause", f.rawCause ?: JSONObject.NULL)
                put("first", f.firstSeenMillis); put("last", f.lastSeenMillis)
                put("count", f.count); put("tier", f.tier.name)
                put("description", f.description); put("action", f.suggestedAction)
                put("cleared", f.clearedMillis ?: JSONObject.NULL)
                put("clearedNote", f.clearedNote ?: JSONObject.NULL)
            })
        }
        val ea = JSONObject()
        tracks.forEach { (subId, t) ->
            val arr = JSONArray()
            synchronized(t) { t.failures.forEach { arr.put(it) } }
            ea.put(subId.toString(), arr)
        }
        prefs(ctx).edit()
            .putString(KEY_FAULTS, fa.toString())
            .putString(KEY_EVENTS, ea.toString())
            .apply()
    }

    private fun load(ctx: Context) {
        lastCtx = ctx
        val p = prefs(ctx)
        val now = System.currentTimeMillis()

        p.getString(KEY_FAULTS, null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val cleared = if (o.isNull("cleared")) null else o.optLong("cleared")
                    if (cleared != null && now - cleared > CLEARED_RETAIN_MS) continue
                    val subId = o.optInt("subId")
                    current[subId] = Fault(
                        subId = subId,
                        slot = o.optInt("slot", -1),
                        carrier = o.optString("carrier", "—"),
                        apnType = if (o.isNull("apnType")) null else o.optString("apnType"),
                        kind = runCatching { FaultKind.valueOf(o.optString("kind")) }
                            .getOrDefault(FaultKind.UNKNOWN),
                        rawCause = if (o.isNull("rawCause")) null else o.optString("rawCause"),
                        firstSeenMillis = o.optLong("first"),
                        lastSeenMillis = o.optLong("last"),
                        count = o.optInt("count"),
                        tier = runCatching { Tier.valueOf(o.optString("tier")) }
                            .getOrDefault(Tier.TIER0),
                        description = o.optString("description"),
                        suggestedAction = o.optString("action"),
                        clearedMillis = cleared,
                        clearedNote = if (o.isNull("clearedNote")) null else o.optString("clearedNote")
                    )
                }
            }
        }

        p.getString(KEY_EVENTS, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.keys().forEach { k ->
                    val subId = k.toIntOrNull() ?: return@forEach
                    val arr = o.optJSONArray(k) ?: return@forEach
                    val t = tracks.getOrPut(subId) {
                        Track(subId, current[subId]?.slot ?: -1, current[subId]?.carrier ?: "—")
                    }
                    synchronized(t) {
                        for (i in 0 until arr.length()) {
                            val at = arr.optLong(i)
                            // Events older than the window are evidence about a period we no
                            // longer claim anything about.
                            if (at > 0 && now - at <= FAILURE_WINDOW_MS) t.failures.addLast(at)
                        }
                        t.reportedThroughMillis = t.failures.lastOrNull() ?: 0L
                    }
                }
            }
        }

        publish()
    }
}
