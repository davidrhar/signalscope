package com.signalscope.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Keeps the cellular bearer out of RRC dormancy while — and only while — dormancy is what would
 * hurt.
 *
 * `docs/excursion-findings.md` is the whole justification -- and it must be read with the dated
 * correction at its head, not the headline figures underneath it. What survived a larger sample is
 * this: a cold probe on a dormant bearer fails somewhat more often than one on a bearer already
 * carrying traffic, and takes substantially longer either way, the delay being the larger and
 * better-evidenced half of the effect. The original reading of that contrast was about twice the
 * size it should have been and is not what this file rests on. Signal quality is not the cause --
 * stretches of real use at a median SINR any textbook calls bad produced no failures at all. What
 * costs is the idle→connected transition.
 *
 * RRC state belongs to the device, not to an app, so a connection held up by our datagram is held
 * up for every app on the phone. That is the point: this is not a self-serving keepalive, it is a
 * device-wide one that happens to be cheap to drive from here.
 *
 * **This is not continuous, because the cost is battery.** Warmth runs only under a trigger, only
 * while cellular actually carries the default route, and never on a nearly flat battery. Every
 * gate is re-checked on every tick rather than once at the start, because all three of them —
 * transport, battery, what is playing — change underneath a long hold.
 *
 * What it costs and what it buys is not a question this file answers, and nothing here should be
 * read as a claim that it works. Its obligation is to be measurable: it records per-session
 * duration, trigger, and the fraction of the hold a datagram actually covered, and it persists a
 * rolling summary so a restart does not erase the evidence.
 */
data class WarmthSession(
    val trigger: String,
    val startWall: Long,
    val heldMs: Long,
    /**
     * Time actually covered by a datagram that went out. Held time is what we *intended*; this is
     * what we can defend, and the two diverge for two different reasons that both matter.
     *
     * One: `requestNetwork` may not have handed us a cellular Network yet, so the send had nothing
     * to bind to. Two: the collector holds no wakelock by deliberate design (19.6 mA measured, see
     * [CollectorService]), so with the screen off the tick timer is deferred until the CPU next
     * wakes and fewer datagrams go out than the hold's duration implies. Either way a hold with no
     * datagrams behind it warmed nothing, and this field is what says so.
     */
    val warmMs: Long,
    val sends: Int,
    val sendFailures: Int
) {
    /** Fraction of the hold a datagram actually covered. A hold is only as good as this. */
    val coverage: Double
        get() = if (heldMs <= 0L) 0.0 else (warmMs.toDouble() / heldMs).coerceAtMost(1.0)

    /**
     * Dormancy windows this hold spanned. Reported as a range rather than a number because the
     * truth is not observable from here: one continuous idle span costs the bearer exactly one
     * promotion no matter how long it is (the lower bound), but a bearer left alone may drop and
     * be re-woken once per inactivity timeout (the upper bound). See [BearerWarmth.DORMANCY_MS].
     */
    val gapsSpannedLower: Int get() = if (heldMs > BearerWarmth.DORMANCY_MS) 1 else 0
    val gapsSpannedUpper: Int get() = (heldMs / BearerWarmth.DORMANCY_MS).toInt()
}

object BearerWarmth {

    /**
     * @param inactiveReason why warmth is NOT running right now. Non-null exactly when
     *   [active] is false, because "not warming" with no reason attached is indistinguishable
     *   from a feature that quietly broke.
     */
    data class State(
        val active: Boolean = false,
        /** The trigger currently holding the bearer, or "—". */
        val trigger: String = "—",
        val inactiveReason: String? = "not started",
        /** Seconds held in the hold that is running now. Zero when nothing is held. */
        val heldSecondsThisSession: Long = 0,
        val sessions: Int = 0,
        val totalHeldMs: Long = 0,
        val totalWarmMs: Long = 0,
        val sends: Int = 0,
        val sendFailures: Int = 0,
        /** Lower and upper bound on promotions avoided — see [WarmthSession.gapsSpannedLower]. */
        val wakeupsAvoidedLower: Int = 0,
        val wakeupsAvoidedUpper: Int = 0,
        val lastSession: WarmthSession? = null,
        val recent: List<WarmthSession> = emptyList(),
        /** Last battery reading used for the gate, and whether it could be read at all. */
        val batteryPct: Int = -1,
        val batteryKnown: Boolean = false,
        /** Where the call/media signal came from on the last tick: the classifier or a direct read. */
        val triggerSource: String = "—"
    ) {
        /** Fraction of all held time that a datagram actually covered, across the install. */
        val coverage: Double
            get() = if (totalHeldMs <= 0L) 0.0 else (totalWarmMs.toDouble() / totalHeldMs).coerceAtMost(1.0)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    // ---------------------------------------------------------------- constants

    /**
     * Measured, not guessed: `excursion-findings.md` puts the dormancy fall at ~10 s of silence.
     * Everything downstream — the keepalive interval, the avoided-wake-up count — is derived from
     * this one number, so it is named once.
     */
    const val DORMANCY_MS = 10_000L

    /**
     * Shorter than a typical LTE RRC inactivity timer, so the connection is genuinely held rather
     * than re-promoted every interval — half [DORMANCY_MS], so a datagram lands well inside the
     * silence that would drop the bearer rather than racing it.
     */
    private const val KEEPALIVE_MS = 5_000L

    /** Gate re-evaluation cadence. Cheap: a few getters off already-published state. */
    private const val TICK_MS = 1_000L

    /**
     * `excursion-findings.md` §3 names the Wi-Fi→cellular handover the worst case — cold radio,
     * new IP, and every app demanding data at once — and puts the window at 60–120 s. Taking the
     * upper end: the cost of 60 s of extra warmth is small next to a failed reconnect, and this is
     * the one trigger where the failure is certain rather than probable.
     */
    private const val HANDOVER_WARM_MS = 120_000L

    /**
     * Fallback media window, used only when the classifier cannot tell us what is playing. The
     * YouTube pattern in the findings is buffer, 30–60 s of silence, refill; the refill is what
     * pays promotion. 60 s after the last burst covers that gap and then stops, so a single
     * browsing burst cannot turn into an open-ended hold.
     */
    private const val MEDIA_FALLBACK_MS = 60_000L

    /**
     * Below this, and not charging, nothing discretionary runs. Deliberately lower than
     * [RegionAcquisition.BATTERY_FLOOR] (20 %): a basemap download can always wait for a charger,
     * whereas the calls and handovers this protects happen when they happen, and a phone at 18 %
     * is exactly when a dropped reconnect is least welcome.
     */
    private const val BATTERY_FLOOR = 15

    /** The battery gate does not need per-second resolution, and the sticky read is not free. */
    private const val BATTERY_REFRESH_MS = 30_000L

    /** A one-shot hold is capped so a caller that forgets to bound it cannot hold the radio open. */
    private const val MANUAL_MAX_MS = 15 * 60_000L

    /**
     * Ceiling on a pre-emptive hold, and its own battery floor.
     *
     * 90 s because the prediction it acts on is "Wi-Fi is about to go", not "Wi-Fi will go at
     * some point today" -- a decaying access point resolves one way or the other inside a minute
     * or so, and if it does not, the prediction was wrong and the window should lapse rather than
     * quietly become a permanent hold. The floor is higher than [BATTERY_FLOOR] because a
     * speculative hold has weaker justification than a hold serving traffic that exists.
     */
    private const val PREEMPT_MAX_MS = 90_000L
    private const val PREEMPT_BATTERY_FLOOR = 25

    private const val PREFS = "bearer_warmth"
    private const val KEY_SUMMARY = "summary"
    private const val RECENT_KEEP = 40

    /**
     * 12 bytes to 1.1.1.1:53. A literal address rather than a name so no DNS is involved:
     * resolution on a non-default network is the part that blocks (see [CellProbe.probeAndRecord]),
     * and the point here is to touch the radio, not to reach a service. Nothing carrier- or
     * device-specific: any routable destination does.
     */
    private const val TARGET_HOST = "1.1.1.1"
    private const val TARGET_PORT = 53

    // ---------------------------------------------------------------- lifecycle

    private val lock = Any()
    private var job: Job? = null
    @Volatile private var manualUntilElapsed = 0L
    @Volatile private var manualReason = ""
    @Volatile private var preemptUntilElapsed = 0L
    @Volatile private var preemptReason = ""

    /** Cumulative, loaded from prefs on [start] so the counters survive a restart. */
    private var sessionCount = 0
    private var heldTotal = 0L
    private var warmTotal = 0L
    private var sendTotal = 0
    private var failTotal = 0
    private var avoidedLower = 0
    private var avoidedUpper = 0
    private var recent = listOf<WarmthSession>()

    fun start(ctx: Context, scope: CoroutineScope) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (job?.isActive == true) return
            runCatching { load(app) }
            // Idempotent (it returns early once its callback is registered) and the service
            // already calls it, but warmth must not depend on call order to have a Network.
            runCatching { CellProbe.start(app) }
            job = scope.launch { engine(app, manualOnly = false) }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
        manualUntilElapsed = 0L
        _state.value = _state.value.copy(
            active = false, trigger = "—", heldSecondsThisSession = 0,
            inactiveReason = "stopped"
        )
    }

    /**
     * Hold the bearer now, for [durationMs], under the label [reason]. For a manual test.
     *
     * The hard gates still apply. A one-shot hold while Wi-Fi holds the default route would warm a
     * bearer nothing rides on, which is pure battery cost — so it is refused and says so in
     * [state], rather than silently spending power and reporting success.
     */
    /**
     * Warm the bearer *before* Wi-Fi goes, on evidence that it is about to.
     *
     * Separate from [holdNow] because it is the only path allowed to run while Wi-Fi still holds
     * the default route, and that permission should be legible in the call site rather than
     * hidden in a flag. Called by [HandoverPredictor]; bounded by [PREEMPT_MAX_MS] so a wrong
     * prediction costs a known amount of radio and no more.
     */
    fun preemptNow(ctx: Context, scope: CoroutineScope, reason: String, durationMs: Long) {
        val app = ctx.applicationContext
        preemptReason = reason.ifBlank { "wifi-decay" }
        val until = SystemClock.elapsedRealtime() + durationMs.coerceIn(0L, PREEMPT_MAX_MS)
        // Extend, never shorten: two predictors firing in sequence should not cut each other off.
        if (until > preemptUntilElapsed) preemptUntilElapsed = until
        synchronized(lock) {
            if (job?.isActive == true) return
            runCatching { load(app) }
            runCatching { CellProbe.start(app) }
            job = scope.launch { engine(app, manualOnly = true) }
        }
    }

    fun holdNow(ctx: Context, scope: CoroutineScope, reason: String, durationMs: Long) {
        val app = ctx.applicationContext
        manualReason = reason.ifBlank { "manual" }
        manualUntilElapsed = SystemClock.elapsedRealtime() + durationMs.coerceIn(0L, MANUAL_MAX_MS)
        synchronized(lock) {
            if (job?.isActive == true) return   // the running engine will pick the window up
            runCatching { load(app) }
            runCatching { CellProbe.start(app) }
            job = scope.launch { engine(app, manualOnly = true) }
        }
    }

    // ---------------------------------------------------------------- the engine

    /**
     * One loop owns both the gating and the sending, so "am I allowed to hold" and "am I holding"
     * can never disagree. [manualOnly] exits once the one-shot window has closed, which is what
     * lets [holdNow] work on a process where [start] was never called.
     */
    private suspend fun engine(ctx: Context, manualOnly: Boolean) {
        var holding = false
        var sessionStartElapsed = 0L
        var sessionStartWall = 0L
        var sessionTrigger = ""
        var sessionSends = 0
        var sessionFails = 0
        var sessionOk = 0
        var lastSendElapsed = 0L

        // Wi-Fi→cellular edge. Detected here rather than from a callback because this loop is
        // already polling the published transport, and an edge missed while the process was dead
        // is not a handover we could have warmed anyway.
        var lastTransport = runCatching { LiveState.net.value.transport }.getOrDefault("—")
        var handoverAtElapsed = 0L

        // Fallback burst tracking, used only when the classifier is not running. There is no
        // per-field timestamp for dataActivity, so the signal timestamp is used as a conservative
        // proxy for freshness: a stale SimState is treated as no observation at all.
        var lastBurstElapsed = 0L

        var batteryPct = -1
        var batteryCharging = false
        var batteryKnown = false
        var batteryReadAtElapsed = 0L

        try {
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()

                val net = runCatching { LiveState.net.value }.getOrNull()
                val transport = net?.transport ?: "—"
                if (transport != lastTransport) {
                    // Only a *known* non-cellular predecessor counts. "—" or "LOST" means we do
                    // not know we came off Wi-Fi, and inventing a handover there would warm the
                    // radio every time the service starts.
                    if (transport == "CELLULAR" && (lastTransport == "WIFI" || lastTransport == "OTHER")) {
                        handoverAtElapsed = now
                    }
                    lastTransport = transport
                }

                if (now - batteryReadAtElapsed >= BATTERY_REFRESH_MS || batteryReadAtElapsed == 0L) {
                    val b = readBattery(ctx)
                    batteryPct = b.first; batteryCharging = b.second
                    batteryKnown = batteryPct >= 0
                    batteryReadAtElapsed = now
                }

                val sim = runCatching {
                    LiveState.sims.value.values.firstOrNull { it.isDataSub }
                }.getOrNull()
                val simFresh = sim != null && !sim.signalStale()
                if (simFresh && isBurst(sim.dataActivity)) lastBurstElapsed = now

                val gate = evaluate(
                    ctx = ctx,
                    now = now,
                    transport = transport,
                    batteryPct = batteryPct,
                    batteryCharging = batteryCharging,
                    handoverAtElapsed = handoverAtElapsed,
                    lastBurstElapsed = lastBurstElapsed,
                    simFresh = simFresh
                )
                val want = gate.trigger != null

                if (want && !holding) {
                    holding = true
                    sessionStartElapsed = now
                    sessionStartWall = System.currentTimeMillis()
                    sessionTrigger = gate.trigger!!
                    sessionSends = 0; sessionFails = 0; sessionOk = 0
                    lastSendElapsed = 0L
                } else if (want && holding && gate.trigger != sessionTrigger) {
                    // The trigger changed mid-hold (a call started during the handover window).
                    // Close the session and open a new one, so per-trigger duration stays honest.
                    closeSession(
                        ctx, sessionTrigger, sessionStartWall, now - sessionStartElapsed,
                        sessionOk, sessionSends, sessionFails
                    )
                    sessionStartElapsed = now
                    sessionStartWall = System.currentTimeMillis()
                    sessionTrigger = gate.trigger!!
                    sessionSends = 0; sessionFails = 0; sessionOk = 0
                    lastSendElapsed = 0L
                }

                // `want` is in the condition as well as `holding`: on the tick a gate closes we are
                // still nominally holding, and one more datagram there would be power spent after
                // the reason to spend it had gone.
                if (want && holding && (lastSendElapsed == 0L || now - lastSendElapsed >= KEEPALIVE_MS)) {
                    lastSendElapsed = now
                    sessionSends++
                    if (sendOne()) sessionOk++ else sessionFails++
                }

                if (!want && holding) {
                    holding = false
                    closeSession(
                        ctx, sessionTrigger, sessionStartWall, now - sessionStartElapsed,
                        sessionOk, sessionSends, sessionFails
                    )
                }

                publish(
                    holding = holding,
                    trigger = if (holding) sessionTrigger else "—",
                    heldMs = if (holding) now - sessionStartElapsed else 0L,
                    inactiveReason = if (holding) null else gate.reason,
                    batteryPct = batteryPct,
                    batteryKnown = batteryKnown,
                    source = gate.source
                )

                if (manualOnly && !holding && now >= manualUntilElapsed) return

                delay(TICK_MS)
            }
        } finally {
            if (holding) {
                // Cancellation must not lose the session that was in flight.
                runCatching {
                    closeSession(
                        ctx, sessionTrigger, sessionStartWall,
                        SystemClock.elapsedRealtime() - sessionStartElapsed,
                        sessionOk, sessionSends, sessionFails
                    )
                }
            }
            synchronized(lock) { if (job?.isActive != true) job = null }
        }
    }

    // ---------------------------------------------------------------- gating

    /** [trigger] non-null means hold; [reason] explains the refusal when it is null. */
    private data class Gate(val trigger: String?, val reason: String, val source: String)

    private fun evaluate(
        ctx: Context,
        now: Long,
        transport: String,
        batteryPct: Int,
        batteryCharging: Boolean,
        handoverAtElapsed: Long,
        lastBurstElapsed: Long,
        simFresh: Boolean
    ): Gate {
        // The single most important gate. While Wi-Fi holds the default route nothing rides on
        // cellular, so warming it is battery spent for no user-visible benefit whatsoever. This is
        // also why the dormant-bearer penalty in the findings was harmless in practice: it was
        // measured on a bearer nobody was using.
        if (transport != "CELLULAR") {
            // The one exception, and it is deliberately narrow. Warming while Wi-Fi still holds
            // the route is normally pure cost -- but the Wi-Fi-to-cellular handover is the worst
            // moment in the entire dataset: a cold radio, a new IP address that kills every open
            // socket, and every app demanding data at once. It is also the one failure that can
            // be seen coming, from Wi-Fi decaying or losing validation. Spending sixty seconds of
            // radio to arrive at that moment already connected is a good trade; spending it
            // speculatively forever is not, so the window is short, evidence-triggered by
            // [HandoverPredictor], and capped at [PREEMPT_MAX_MS].
            //
            // Wi-Fi only. An unknown or lost transport must not open this, or a reconnect storm
            // would hold the radio open on a guess.
            val preempting = now < preemptUntilElapsed && transport == "WIFI"
            if (!preempting) {
                return Gate(null, "cellular is not the default route (transport $transport)", "—")
            }
            // A stiffer battery floor than the ordinary one: this hold is a prediction, and a
            // prediction should not be the thing that flattens the phone.
            if (batteryPct in 0 until PREEMPT_BATTERY_FLOOR && !batteryCharging) {
                return Gate(null,
                    "pre-emptive hold declined: battery $batteryPct % is below the " +
                        "$PREEMPT_BATTERY_FLOOR % pre-emption floor", "—")
            }
            val left = (preemptUntilElapsed - now) / 1000
            return Gate("preempt:$preemptReason", "handover looks imminent, ${left}s left",
                "predictor")
        }

        // Read it, never assume it. An unreadable battery is reported as unreadable rather than
        // treated as full -- but it does not veto the feature either, because a handset that will
        // not report a level would otherwise lose warmth permanently. The coverage flag in
        // [State.batteryKnown] is how that shows up instead of being hidden.
        if (batteryPct in 0 until BATTERY_FLOOR && !batteryCharging) {
            return Gate(null, "battery $batteryPct % is below the $BATTERY_FLOOR % floor and not charging", "—")
        }

        if (now < manualUntilElapsed) {
            val left = (manualUntilElapsed - now) / 1000
            return Gate("manual:$manualReason", "manual hold, ${left}s left", "caller")
        }

        val traffic = readTraffic(ctx)

        if (traffic.inCall) {
            return Gate("call", "call active", traffic.source)
        }

        if (handoverAtElapsed > 0L && now - handoverAtElapsed < HANDOVER_WARM_MS) {
            val left = (HANDOVER_WARM_MS - (now - handoverAtElapsed)) / 1000
            return Gate("wifi-handover", "handover window, ${left}s left", traffic.source)
        }

        if (traffic.mediaBuffering) {
            return Gate("media", "media playing", traffic.source)
        }

        // Fallback only. Used when the classifier is not running, which in a background service is
        // the normal case -- ActionTraffic is started by the Actions screen, not by the collector.
        // A burst then silence is the observable half of the buffer-then-idle cycle, and the
        // window is bounded so it cannot become a continuous hold.
        if (!traffic.classifierLive && simFresh &&
            lastBurstElapsed > 0L && now - lastBurstElapsed < MEDIA_FALLBACK_MS
        ) {
            return Gate("media-burst-gap", "post-burst gap", "dataActivity")
        }

        return Gate(
            null,
            when {
                !traffic.classifierLive && !simFresh ->
                    "no trigger active — and neither the traffic classifier nor a fresh radio " +
                        "reading is available, so a media cycle could be missed"
                else -> "no trigger active (${traffic.basis})"
            },
            traffic.source
        )
    }

    private data class Traffic(
        val inCall: Boolean,
        val mediaBuffering: Boolean,
        val classifierLive: Boolean,
        val basis: String,
        val source: String
    )

    /**
     * Prefers [ActionTraffic], read-only, because it already classifies exactly this and it has
     * positive evidence (audio mode, playback configurations, call state) rather than an inference.
     * But it is only *started* by the Actions screen, so in a background service it usually has no
     * samples at all — and an unsampled classifier reports UNKNOWN, which must never be read as
     * "no call". So when it is not live, fall back to the two cheap platform reads it would have
     * made. Nothing here is started or mutated; a missing service or a revoked permission degrades
     * to "cannot tell", never to "nothing is happening".
     */
    private fun readTraffic(ctx: Context): Traffic {
        val cs = runCatching { ActionTraffic.state.value }.getOrNull()
        // "Live" means it has actually sampled and can read call state. samples == 0 is the
        // unsampled default, whose class is UNKNOWN -- and UNKNOWN is not evidence of no call.
        if (cs != null && cs.samples > 0 && cs.callStateReadable) {
            val call = cs.klass == TrafficClass.REALTIME_CALL || cs.klass == TrafficClass.CONFERENCING
            val media = cs.klass == TrafficClass.VIDEO || cs.klass == TrafficClass.MUSIC
            return Traffic(call, media, true, cs.basis, "ActionTraffic")
        }

        // AudioManager.getMode() needs no permission and covers the case the findings actually
        // named: a call, VoIP included. getCallState() needs READ_PHONE_STATE, which we declare,
        // but it throws rather than returning IDLE when the grant is missing -- hence runCatching
        // around it separately, so losing one signal does not lose the other.
        val am = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
        val mode = runCatching { am?.mode ?: -1 }.getOrDefault(-1)
        val musicActive = runCatching { am?.isMusicActive == true }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val callState = runCatching {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.callState
        }.getOrNull()

        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            callState == TelephonyManager.CALL_STATE_OFFHOOK ||
            callState == TelephonyManager.CALL_STATE_RINGING

        val basis = buildString {
            append("direct read: audio mode ")
            append(runCatching { ActionTraffic.modeName(mode) }.getOrDefault("mode $mode"))
            append(", call ")
            append(callState?.let { runCatching { ActionTraffic.callStateName(it) }.getOrDefault("$it") }
                ?: "unreadable")
        }
        return Traffic(inCall, musicActive, false, basis, "direct")
    }

    /**
     * Uplink or downlink activity on the data SIM. DORMANT (4) and NONE (0) are the silence we are
     * trying to span; anything else is the burst that precedes it.
     */
    private fun isBurst(dataActivity: Int?): Boolean = when (dataActivity) {
        TelephonyManager.DATA_ACTIVITY_IN,
        TelephonyManager.DATA_ACTIVITY_OUT,
        TelephonyManager.DATA_ACTIVITY_INOUT -> true
        else -> false
    }

    /** Percent and charging. -1 percent means the platform would not say, which is reported. */
    private fun readBattery(ctx: Context): Pair<Int, Boolean> = runCatching {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        pct to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(-1 to false)

    // ---------------------------------------------------------------- the datagram

    /**
     * One small datagram on a cellular-bound socket. Reuses [CellProbe.withCellular] rather than
     * requesting a network of its own: one `requestNetwork` for the whole app is the arrangement
     * the probe already established, and a second one would be a second reason for the modem to
     * stay up that nobody is accounting for.
     *
     * Returns false when there is no cellular Network to bind to. That is not a warm hold and must
     * not be counted as one.
     */
    private suspend fun sendOne(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            CellProbe.withCellular { net ->
                DatagramSocket().use { s ->
                    net.bindSocket(s)
                    val payload = ByteArray(12)
                    s.send(
                        DatagramPacket(
                            payload, payload.size,
                            InetAddress.getByName(TARGET_HOST), TARGET_PORT
                        )
                    )
                }
                true
            }
        }.getOrNull() == true
    }

    // ---------------------------------------------------------------- bookkeeping

    private fun closeSession(
        ctx: Context,
        trigger: String,
        startWall: Long,
        heldMs: Long,
        okSends: Int,
        sends: Int,
        fails: Int
    ) {
        // A hold shorter than one keepalive interval spanned no dormancy window and warmed
        // nothing; recording it would pad the session count with noise.
        if (heldMs < KEEPALIVE_MS) return

        val s = WarmthSession(
            trigger = trigger,
            startWall = startWall,
            heldMs = heldMs,
            warmMs = (okSends * KEEPALIVE_MS).coerceAtMost(heldMs),
            sends = sends,
            sendFailures = fails
        )
        sessionCount++
        heldTotal += s.heldMs
        warmTotal += s.warmMs
        sendTotal += s.sends
        failTotal += s.sendFailures
        avoidedLower += s.gapsSpannedLower
        avoidedUpper += s.gapsSpannedUpper
        recent = (recent + s).takeLast(RECENT_KEEP)

        _state.value = _state.value.copy(lastSession = s)
        runCatching { save(ctx) }
    }

    private fun publish(
        holding: Boolean,
        trigger: String,
        heldMs: Long,
        inactiveReason: String?,
        batteryPct: Int,
        batteryKnown: Boolean,
        source: String
    ) {
        _state.value = _state.value.copy(
            active = holding,
            trigger = trigger,
            inactiveReason = inactiveReason,
            heldSecondsThisSession = heldMs / 1000,
            sessions = sessionCount,
            totalHeldMs = heldTotal,
            totalWarmMs = warmTotal,
            sends = sendTotal,
            sendFailures = failTotal,
            wakeupsAvoidedLower = avoidedLower,
            wakeupsAvoidedUpper = avoidedUpper,
            recent = recent,
            batteryPct = batteryPct,
            batteryKnown = batteryKnown,
            triggerSource = source
        )
    }

    private fun save(ctx: Context) {
        val o = JSONObject().apply {
            put("sessions", sessionCount)
            put("heldMs", heldTotal); put("warmMs", warmTotal)
            put("sends", sendTotal); put("fails", failTotal)
            put("avoidedLo", avoidedLower); put("avoidedHi", avoidedUpper)
            put("recent", JSONArray().apply {
                recent.forEach {
                    put(JSONObject().apply {
                        put("trigger", it.trigger); put("startWall", it.startWall)
                        put("heldMs", it.heldMs); put("warmMs", it.warmMs)
                        put("sends", it.sends); put("fails", it.sendFailures)
                    })
                }
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUMMARY, o.toString()).apply()
    }

    private fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUMMARY, null) ?: return
        runCatching {
            val o = JSONObject(raw)
            sessionCount = o.optInt("sessions")
            heldTotal = o.optLong("heldMs"); warmTotal = o.optLong("warmMs")
            sendTotal = o.optInt("sends"); failTotal = o.optInt("fails")
            avoidedLower = o.optInt("avoidedLo"); avoidedUpper = o.optInt("avoidedHi")
            val a = o.optJSONArray("recent") ?: JSONArray()
            recent = (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                WarmthSession(
                    trigger = s.optString("trigger", "—"),
                    startWall = s.optLong("startWall"),
                    heldMs = s.optLong("heldMs"),
                    warmMs = s.optLong("warmMs"),
                    sends = s.optInt("sends"),
                    sendFailures = s.optInt("fails")
                )
            }
        }
        publish(
            holding = false, trigger = "—", heldMs = 0,
            inactiveReason = "not started", batteryPct = -1, batteryKnown = false, source = "—"
        )
    }

    /** One-line readback, with its own coverage attached. */
    fun summary(s: State = _state.value): String = buildString {
        if (s.active) append("holding · ${s.trigger} · ${s.heldSecondsThisSession}s\n")
        else append("not holding — ${s.inactiveReason ?: "no reason recorded"}\n")
        append("%d sessions, %.1f min held total, %.0f%% of it covered by a datagram\n"
            .format(s.sessions, s.totalHeldMs / 60_000.0, s.coverage * 100))
        if (s.sessions == 0) {
            append("no sessions yet — nothing measured")
        } else {
            append("promotions avoided: ${s.wakeupsAvoidedLower}–${s.wakeupsAvoidedUpper} " +
                "(one per hold, to one per ${DORMANCY_MS / 1000}s dormancy window)")
            if (s.sendFailures > 0) append("\n${s.sendFailures} of ${s.sends} datagrams did not go out")
        }
    }
}
