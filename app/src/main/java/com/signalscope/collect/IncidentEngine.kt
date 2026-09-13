package com.signalscope.collect

import com.signalscope.store.IncidentStore
import com.signalscope.store.IncidentStore.LinkRow
import com.signalscope.store.IncidentStore.RadioRow
import com.signalscope.store.IncidentStore.RegRow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Derives incidents from collected rows, then classifies each against the twelve-cause table.
 *
 * Three rules are load-bearing here and each one costs the engine some apparent certainty:
 *
 *  1. **Layer-1 resolution is bounded near 10 s on this platform** (docs/device-findings.md).
 *     Nothing here claims to time a radio event more precisely than the callbacks arrive, and a
 *     window shorter than that floor is damped rather than presented as a precise measurement.
 *  2. **`NET_CAPABILITY_VALIDATED` is a lagging indicator.** It bounds an incident; it does not
 *     time one. Detectors use it to say *that* a window was bad, never exactly when it started.
 *  3. **Incidents belong to one subscription.** Radio and registration evidence carries its own
 *     `subId`. Link events do not — they are attributed to the subscription carrying data, and
 *     that attribution is marked [Attribution.ASSUMED] because the role history the attribution
 *     really needs (`subscription_role_event`) is not collected yet.
 *
 * A predicate that cannot be evaluated counts *against* confidence. It is not neutral and it is
 * certainly not support: not knowing is a reason to be less sure, which is why nothing this
 * engine emits today reaches the mockup's 92 %.
 */
object IncidentEngine {

    // ---------------------------------------------------------------- model

    enum class Layer(val label: String) { RADIO("RADIO"), NETWORK("NETWORK"), TRANSPORT("TRANSPORT"), CONTEXT("CONTEXT") }

    enum class LayerVerdict { HEALTHY, NOMINAL, DEGRADED, FAILING, LOST, NOT_COLLECTED }

    /** Data demonstrably unusable, versus a named fault signature while data still worked. */
    enum class Kind { OUTAGE, DEGRADED }

    enum class Attribution { OBSERVED, ASSUMED }

    enum class Support { SUPPORTS, CONTRADICTS, UNOBSERVABLE }

    data class Cause(
        val num: Int, val name: String, val layer: Layer,
        val fix: String, val tier: String
    )

    data class Predicate(
        val text: String,
        val support: Support,
        val weight: Float = 1f,
        /** Required predicates rule the cause out when contradicted. */
        val required: Boolean = false,
        /** Why it could not be evaluated. Shown verbatim; no guessing. */
        val why: String? = null
    )

    data class Assessment(
        val cause: Cause,
        val predicates: List<Predicate>,
        val confidence: Float,
        val ruledOut: Boolean,
        val ruledOutReason: String?
    ) {
        val supported get() = predicates.count { it.support == Support.SUPPORTS }
        val evaluable get() = predicates.count { it.support != Support.UNOBSERVABLE }
        val unobservable get() = predicates.filter { it.support == Support.UNOBSERVABLE }
    }

    data class LayerCard(
        val layer: Layer,
        val verdict: LayerVerdict,
        val rows: List<Pair<String, String>>,
        val note: String? = null
    )

    data class Incident(
        val id: String,
        val subId: Int,
        val carrier: String,
        val attribution: Attribution,
        val kind: Kind,
        val detectors: List<String>,
        val startWall: Long,
        val endWall: Long,
        val durationMs: Long,
        val ongoing: Boolean,
        val headline: String,
        val evidenceLine: String,
        val top: Assessment?,
        val ranked: List<Assessment>,
        val ruledOut: List<Assessment>,
        val notExcluded: List<Assessment>,
        val layers: List<LayerCard>,
        val resolutionNote: String?,
        val raiseConfidence: String?,
        val prevalenceNote: String?
    ) {
        val confidence: Float get() = top?.confidence ?: 0f
        /** Below this, the engine names no cause. Displaying a 20 % guess as a verdict is lying. */
        val classified: Boolean get() = (top?.confidence ?: 0f) >= CLASSIFY_FLOOR
    }

    data class Result(
        val incidents: List<Incident>,
        val subIds: List<Int>,
        val rows: IncidentStore.Rows,
        /** cause number -> incidents, measured on this device. Ranking is never the table order. */
        val prevalence: Map<Int, Int>,
        val armed: List<String>
    )

    /** Confidence below which the engine refuses to name a cause. */
    const val CLASSIFY_FLOOR = 0.30f

    /** Measured platform floor for Layer-1 delivery. Nothing shorter is timed, only bounded. */
    const val LAYER1_FLOOR_MS = 10_000L

    // ---------------------------------------------------------------- causes

    val CAUSES: List<Cause> = listOf(
        Cause(1, "Radio coverage loss", Layer.RADIO,
            "Move, or fit an external antenna. Check the map tab — if this is a place rather than a time, that is a coverage hole worth reporting.", "TIER 0"),
        Cause(2, "5G NSA anchor thrash", Layer.RADIO,
            "Pin the radio to LTE so the NR leg cannot be re-added.", "TIER 2 · SHIZUKU"),
        Cause(3, "Cell reselection ping-pong", Layer.RADIO,
            "Pin to LTE or to a band. If it only happens while moving it is ordinary handover, not a fault.", "TIER 2 · SHIZUKU"),
        Cause(4, "PS attach / PDN failure", Layer.RADIO,
            "Check the APN and the SIM. A non-zero reject cause is the carrier refusing the packet-switched attach.", "TIER 0"),
        Cause(5, "Data suspended", Layer.NETWORK,
            "Enable VoLTE. Data suspends when voice falls back to a legacy RAT.", "TIER 0"),
        Cause(6, "Connected, path broken", Layer.NETWORK,
            "Change DNS, or turn off IPv6-only on this APN. The route is up; something above it is not.", "TIER 0"),
        Cause(7, "Wi-Fi ↔ cellular thrash", Layer.NETWORK,
            "Turn down adaptive Wi-Fi / 'switch to mobile data' assistance, or forget the marginal AP.", "TIER 0"),
        Cause(8, "Device-side interference", Layer.CONTEXT,
            "Exempt the collector from battery optimisation, check for a VPN, let the device cool.", "TIER 0"),
        Cause(9, "Carrier-side congestion", Layer.TRANSPORT,
            "Avoid the time of day, or complain with evidence attached. Export this incident.", "TIER 0"),
        Cause(10, "DSDS tune-away", Layer.CONTEXT,
            "Disable or relocate the second SIM. Run the A/B: one day with slot 1 off, and compare.", "TIER 0"),
        Cause(11, "Data-subscription switch", Layer.NETWORK,
            "Fix the data SIM and disable automatic data switching. Every open socket dies on the IP change.", "TIER 0"),
        Cause(12, "Roaming / VoWiFi handover", Layer.RADIO,
            "Check roaming and Wi-Fi-calling settings for this subscription.", "TIER 0")
    )

    private fun cause(n: Int) = CAUSES.first { it.num == n }

    /** What the engine is watching for. Shown in the empty state, so "nothing found" is legible. */
    val ARMED = listOf(
        "SERVICE_LOSS · ServiceState leaves IN_SERVICE",
        "PS_REJECT · NetworkRegistrationInfo reject cause ≠ 0",
        "VALIDATION_LOSS · default route loses NET_CAPABILITY_VALIDATED",
        "SUSPENDED · default route loses NOT_SUSPENDED",
        "ADDRESS_CHANGE · default route IP changes (kills every open socket)",
        "ROUTE_FLAP · default transport flips ≥3× in 120 s",
        "RESELECTION_CHURN · ≥4 serving-cell changes with a return, in 120 s",
        "QUALITY_FLOOR · SINR < 0 dB or RSRP ≤ −110 for ≥15 s"
    )

    // ---------------------------------------------------------------- entry point

    fun analyse(
        rows: IncidentStore.Rows,
        sims: Map<Int, SimState>,
        dataSubId: Int?
    ): Result {
        val subIds = (rows.radio.map { it.subId } + rows.reg.map { it.subId })
            .distinct().sortedBy { sims[it]?.slot ?: it }
        val attributedSub = dataSubId
            ?: sims.values.firstOrNull { it.isDataSub }?.subId
            ?: subIds.firstOrNull()

        val windows = ArrayList<Window>()
        subIds.forEach { sub ->
            windows += serviceLoss(rows.reg.filter { it.subId == sub }, sub)
            windows += psReject(rows.reg.filter { it.subId == sub }, sub)
            windows += reselectionChurn(rows.radio.filter { it.subId == sub }, sub)
            windows += qualityFloor(rows.radio.filter { it.subId == sub }, sub)
        }
        if (attributedSub != null) {
            val def = rows.link.filter { it.isDefault }
            windows += validationLoss(def, attributedSub)
            windows += suspended(def, attributedSub)
            windows += addressChange(def, attributedSub)
            windows += routeFlap(def, attributedSub)
        }

        val merged = merge(windows)
        val incidents = merged.map { w ->
            build(w, rows, sims, attributedSub)
        }.sortedByDescending { it.startWall }

        // Prevalence is measured, never assumed: the cause ranking on this handset comes out of
        // what this handset actually recorded. README is explicit that the table order is not it.
        val prevalence = incidents.filter { it.classified }
            .mapNotNull { it.top?.cause?.num }
            .groupingBy { it }.eachCount()

        val withPrevalence = incidents.map { inc ->
            val rankNote = inc.ranked.getOrNull(1)?.let { runner ->
                val top = inc.top ?: return@let null
                if (abs(top.confidence - runner.confidence) > 0.05f) null
                else "ranked above cause ${runner.cause.num} by measured prevalence on this " +
                        "device (${prevalence[top.cause.num] ?: 0} vs ${prevalence[runner.cause.num] ?: 0})"
            }
            inc.copy(prevalenceNote = rankNote)
        }

        return Result(withPrevalence, subIds, rows, prevalence, ARMED)
    }

    // ---------------------------------------------------------------- detection

    private data class Window(
        val subId: Int,
        val kind: Kind,
        val detector: String,
        val startElapsed: Long,
        val endElapsed: Long,
        val startWall: Long,
        val endWall: Long,
        val attribution: Attribution,
        val ongoing: Boolean = false
    )

    private fun serviceLoss(reg: List<RegRow>, sub: Int): List<Window> {
        val out = ArrayList<Window>()
        var open: RegRow? = null
        reg.forEach { r ->
            val inService = r.regState == "IN_SERVICE"
            if (!inService && open == null) open = r
            else if (inService && open != null) {
                out += Window(sub, Kind.OUTAGE, "SERVICE_LOSS",
                    open!!.elapsedNanos, r.elapsedNanos, open!!.wallMillis, r.wallMillis,
                    Attribution.OBSERVED)
                open = null
            }
        }
        open?.let { o ->
            val last = reg.last()
            out += Window(sub, Kind.OUTAGE, "SERVICE_LOSS",
                o.elapsedNanos, last.elapsedNanos, o.wallMillis, last.wallMillis,
                Attribution.OBSERVED, ongoing = true)
        }
        return out
    }

    private fun psReject(reg: List<RegRow>, sub: Int): List<Window> {
        val bad = reg.filter { (it.rejectCause ?: 0) != 0 }
        if (bad.isEmpty()) return emptyList()
        return cluster(bad.map { it.elapsedNanos to it.wallMillis }, 120_000L).map { (a, b) ->
            Window(sub, Kind.OUTAGE, "PS_REJECT", a.first, b.first, a.second, b.second,
                Attribution.OBSERVED)
        }
    }

    private fun validationLoss(link: List<LinkRow>, sub: Int): List<Window> {
        val out = ArrayList<Window>()
        var open: LinkRow? = null
        link.forEach { r ->
            if (!r.validated && open == null) open = r
            else if (r.validated && open != null) {
                out += Window(sub, Kind.OUTAGE, "VALIDATION_LOSS",
                    open!!.elapsedNanos, r.elapsedNanos, open!!.wallMillis, r.wallMillis,
                    Attribution.ASSUMED)
                open = null
            }
        }
        open?.let { o ->
            out += Window(sub, Kind.OUTAGE, "VALIDATION_LOSS",
                o.elapsedNanos, link.last().elapsedNanos, o.wallMillis, link.last().wallMillis,
                Attribution.ASSUMED, ongoing = true)
        }
        return out
    }

    private fun suspended(link: List<LinkRow>, sub: Int): List<Window> {
        val bad = link.filter { !it.notSuspended }
        if (bad.isEmpty()) return emptyList()
        return cluster(bad.map { it.elapsedNanos to it.wallMillis }, 60_000L).map { (a, b) ->
            Window(sub, Kind.OUTAGE, "SUSPENDED", a.first, b.first, a.second, b.second,
                Attribution.ASSUMED)
        }
    }

    private fun addressChange(link: List<LinkRow>, sub: Int): List<Window> =
        link.filter { it.addressChanged }.map {
            Window(sub, Kind.OUTAGE, "ADDRESS_CHANGE",
                it.elapsedNanos, it.elapsedNanos, it.wallMillis, it.wallMillis,
                Attribution.ASSUMED)
        }

    private fun routeFlap(link: List<LinkRow>, sub: Int): List<Window> {
        val flips = ArrayList<LinkRow>()
        var prev: String? = null
        link.forEach { r ->
            if (prev != null && r.transport != prev) flips += r
            prev = r.transport
        }
        if (flips.size < 3) return emptyList()
        return cluster(flips.map { it.elapsedNanos to it.wallMillis }, 120_000L)
            .filter { (a, b) -> b.first > a.first }
            .map { (a, b) ->
                Window(sub, Kind.OUTAGE, "ROUTE_FLAP", a.first, b.first, a.second, b.second,
                    Attribution.ASSUMED)
            }
    }

    private fun reselectionChurn(radio: List<RadioRow>, sub: Int): List<Window> {
        val changes = ArrayList<RadioRow>()
        var prev: Long? = null
        radio.forEach { r ->
            val c = r.ci ?: return@forEach
            if (prev != null && c != prev) changes += r
            prev = c
        }
        if (changes.size < 4) return emptyList()
        val out = ArrayList<Window>()
        var cur = ArrayList<RadioRow>()
        fun flush() {
            if (cur.size >= 4) {
                val cells = cur.mapNotNull { it.ci }
                // A ping-pong is a *return*, not merely movement between cells. Without that
                // test a walk down a street looks identical to a stationary fault.
                if (cells.size > cells.toSet().size) {
                    out += Window(sub, Kind.DEGRADED, "RESELECTION_CHURN",
                        cur.first().elapsedNanos, cur.last().elapsedNanos,
                        cur.first().wallMillis, cur.last().wallMillis, Attribution.OBSERVED)
                }
            }
            cur = ArrayList()
        }
        changes.forEach { ch ->
            if (cur.isNotEmpty() && ch.elapsedNanos - cur.last().elapsedNanos > 120_000L * 1_000_000L) flush()
            cur.add(ch)
        }
        flush()
        return out
    }

    private fun qualityFloor(radio: List<RadioRow>, sub: Int): List<Window> {
        val out = ArrayList<Window>()
        var run = ArrayList<RadioRow>()
        fun flush() {
            if (run.size >= 3) {
                val span = run.last().wallMillis - run.first().wallMillis
                if (span >= 15_000L) {
                    out += Window(sub, Kind.DEGRADED, "QUALITY_FLOOR",
                        run.first().elapsedNanos, run.last().elapsedNanos,
                        run.first().wallMillis, run.last().wallMillis, Attribution.OBSERVED)
                }
            }
            run = ArrayList()
        }
        radio.forEach { r ->
            val bad = (r.rssnr != null && r.rssnr < 0) || (r.rsrp != null && r.rsrp <= -110)
            if (bad) run.add(r) else flush()
        }
        flush()
        return out
    }

    /** Group timestamps into clusters separated by more than [gapMs]. */
    private fun cluster(
        pts: List<Pair<Long, Long>>, gapMs: Long
    ): List<Pair<Pair<Long, Long>, Pair<Long, Long>>> {
        if (pts.isEmpty()) return emptyList()
        val gapNanos = gapMs * 1_000_000L
        val out = ArrayList<Pair<Pair<Long, Long>, Pair<Long, Long>>>()
        var first = pts.first(); var last = pts.first()
        pts.drop(1).forEach { p ->
            if (p.first - last.first > gapNanos) { out += first to last; first = p }
            last = p
        }
        out += first to last
        return out
    }

    /**
     * Overlapping outages on one subscription are one incident, not several views of it.
     * A degradation that overlaps an outage is folded away entirely — it is evidence inside the
     * outage's layer cards, and listing it twice would inflate the count.
     */
    private fun merge(windows: List<Window>): List<Window> {
        val outages = windows.filter { it.kind == Kind.OUTAGE }
            .groupBy { it.subId }.values.flatMap { mergeOne(it) }
        val degraded = windows.filter { it.kind == Kind.DEGRADED }
            .groupBy { it.subId }.values.flatMap { mergeOne(it) }
            .filterNot { d -> outages.any { o -> o.subId == d.subId && overlaps(o, d) } }
        return (outages + degraded).sortedBy { it.startElapsed }
    }

    private fun mergeOne(ws: List<Window>): List<Window> {
        if (ws.isEmpty()) return emptyList()
        val sorted = ws.sortedBy { it.startElapsed }
        val out = ArrayList<Window>()
        var cur = sorted.first()
        var dets = linkedSetOf(cur.detector)
        sorted.drop(1).forEach { w ->
            // One Layer-1 delivery interval of slack. Below that the platform cannot tell us
            // whether two detector hits are two events or one event seen twice, and claiming
            // they are separate would inflate the incident count on nothing but jitter.
            if (w.startElapsed <= cur.endElapsed + LAYER1_FLOOR_MS * 1_000_000L) {
                cur = cur.copy(
                    endElapsed = max(cur.endElapsed, w.endElapsed),
                    endWall = max(cur.endWall, w.endWall),
                    ongoing = cur.ongoing || w.ongoing,
                    attribution = if (cur.attribution == Attribution.OBSERVED ||
                        w.attribution == Attribution.OBSERVED) Attribution.OBSERVED
                    else Attribution.ASSUMED
                )
                dets.add(w.detector)
            } else {
                out += cur.copy(detector = dets.joinToString("+"))
                cur = w; dets = linkedSetOf(w.detector)
            }
        }
        out += cur.copy(detector = dets.joinToString("+"))
        return out
    }

    private fun overlaps(a: Window, b: Window) =
        a.startElapsed <= b.endElapsed && b.startElapsed <= a.endElapsed

    // ---------------------------------------------------------------- evidence

    private data class Ev(
        val subId: Int,
        val kind: Kind,
        val durationMs: Long,
        val radio: List<RadioRow>,
        val reg: List<RegRow>,
        val link: List<LinkRow>,
        val otherReg: List<RegRow>,
        val otherSubPresent: Boolean,
        val nrEverSeen: Boolean,
        val cqiEverSeen: Boolean,
        val iwlanRowsCollected: Boolean,
        val overrideCollected: Boolean
    ) {
        val minRsrp = radio.mapNotNull { it.rsrp }.minOrNull()
        val maxRsrp = radio.mapNotNull { it.rsrp }.maxOrNull()
        val minSinr = radio.mapNotNull { it.rssnr }.minOrNull()
        val maxLevel = radio.mapNotNull { it.level }.maxOrNull()
        val rsrpSpread = if (minRsrp != null && maxRsrp != null) maxRsrp - minRsrp else null
        val cells = radio.mapNotNull { it.ci }
        val pcis = radio.mapNotNull { it.pci }
        val cellChanges = cells.zipWithNext().count { (a, b) -> a != b }
        val pingPong = cells.size > cells.toSet().size && cellChanges >= 2
        val cellStable = cells.isNotEmpty() && cells.toSet().size == 1
        val leftService: Boolean? =
            if (reg.isEmpty()) null else reg.any { it.regState != "IN_SERVICE" }
        val rejectNonZero: Boolean? =
            if (reg.isEmpty()) null else reg.any { (it.rejectCause ?: 0) != 0 }
        val roaming: Boolean? = reg.lastOrNull()?.roaming
        val defaults = link.filter { it.isDefault }
        val validatedLost: Boolean? =
            if (defaults.isEmpty()) null else defaults.any { !it.validated }
        val suspendedSeen: Boolean? =
            if (defaults.isEmpty()) null else defaults.any { !it.notSuspended }
        val addrChanged = defaults.any { it.addressChanged }
        val transports = defaults.map { it.transport }.toSet()
        val routeFlips = defaults.map { it.transport }.zipWithNext().count { (a, b) -> a != b }
        val vpnSeen = link.any { it.transport == "VPN" }
        val v6Only = defaults.any { it.v6 != null && it.v4 == null }
        val clat = defaults.any { it.hasClat }
        val dormant = radio.any { it.dataActivity == 4 }
        val otherRegEvents = otherReg.size
        val otherStates = otherReg.map { it.regState }.toSet()
    }

    private fun evidence(
        w: Window, rows: IncidentStore.Rows, sims: Map<Int, SimState>
    ): Ev {
        // A window shorter than the platform's ~10 s delivery interval can easily contain no
        // sample at all. Widening the *evidence* window to that interval is not fudging the
        // timing — it is admitting that 10 s is the finest grain the platform offers, so
        // anything inside one interval of the event is contemporaneous with it.
        val pad = if ((w.endElapsed - w.startElapsed) / 1_000_000L < LAYER1_FLOOR_MS)
            LAYER1_FLOOR_MS * 1_000_000L else 0L
        val s = w.startElapsed - pad; val e = w.endElapsed + pad
        fun <T> inWindow(list: List<T>, t: (T) -> Long) = list.filter { t(it) in s..e }

        val radio = inWindow(rows.radio.filter { it.subId == w.subId }) { it.elapsedNanos }
        val reg = inWindow(rows.reg.filter { it.subId == w.subId }) { it.elapsedNanos }
        // Link rows are sparse; fall back to the last known state so a short window is not blind.
        val link = inWindow(rows.link) { it.elapsedNanos }.ifEmpty {
            listOfNotNull(rows.link.lastOrNull { it.elapsedNanos <= s })
        }
        val otherReg = inWindow(rows.reg.filter { it.subId != w.subId }) { it.elapsedNanos }

        return Ev(
            subId = w.subId,
            kind = w.kind,
            durationMs = (e - s) / 1_000_000L,
            radio = radio, reg = reg, link = link, otherReg = otherReg,
            otherSubPresent = rows.reg.any { it.subId != w.subId } || sims.size > 1,
            // Whole-history questions, not window questions: "does this device have an NR leg
            // at all" is not answerable from 40 seconds of samples.
            nrEverSeen = rows.radio.any { it.subId == w.subId && it.rat.startsWith("NR") } ||
                    rows.reg.any { it.subId == w.subId && (it.nrState ?: "").contains("CONNECTED") } ||
                    rows.reg.any { (it.overrideNetworkType ?: "").contains("NR") },
            cqiEverSeen = rows.radio.any { it.subId == w.subId && it.cqi != null },
            iwlanRowsCollected = rows.reg.any { it.transportType != "WWAN" },
            overrideCollected = rows.reg.any { it.overrideNetworkType != null }
        )
    }

    // ---------------------------------------------------------------- classification

    private fun predicates(num: Int, ev: Ev): List<Predicate> = when (num) {

        // The required clause is deliberately the *power* signature, not SINR. A sub-zero SINR
        // at −95 dBm is interference or congestion, not a coverage hole, and letting SINR alone
        // satisfy the requirement made every noisy minute look like lost coverage.
        1 -> listOf(
            req("radio showed loss — OUT_OF_SERVICE or RSRP ≤ −110 dBm", 2f,
                when {
                    ev.leftService == true -> Support.SUPPORTS
                    ev.minRsrp != null && ev.minRsrp <= -110 -> Support.SUPPORTS
                    ev.reg.isEmpty() && ev.radio.isEmpty() -> Support.UNOBSERVABLE
                    else -> Support.CONTRADICTS
                },
                whyNot = if (ev.reg.isEmpty() && ev.radio.isEmpty())
                    "no Layer-1 sample inside this window — delivery is ~10 s" else null),
            p("RSRP fell to or below −110 dBm", 2f, tri(ev.minRsrp?.let { it <= -110 })),
            p("SINR fell below 0 dB", 1f, tri(ev.minSinr?.let { it < 0 })),
            p("ServiceState reported OUT_OF_SERVICE or EMERGENCY_ONLY", 2f, tri(ev.leftService))
        )

        2 -> listOf(
            req("an NR leg exists on this subscription", 2f,
                if (ev.nrEverSeen) Support.SUPPORTS else Support.CONTRADICTS),
            p("TelephonyDisplayInfo flapped NR_NSA ↔ LTE", 2f,
                if (ev.overrideCollected) tri(false) else Support.UNOBSERVABLE,
                why = "overrideNetworkType is not persisted by the collector")
        )

        3 -> listOf(
            req("the serving cell changed during the window", 2f,
                when {
                    ev.cellChanges >= 2 -> Support.SUPPORTS
                    ev.cells.isEmpty() -> Support.UNOBSERVABLE
                    else -> Support.CONTRADICTS
                },
                whyNot = if (ev.cells.isEmpty()) "no cell identity sampled in this window" else null),
            p("returned to a previously served cell (a ping-pong, not a walk)", 2f, tri(ev.pingPong)),
            p("≥4 reselections inside the window", 1f, tri(ev.cellChanges >= 4)),
            p("RSRP spread ≤ 12 dB — no large path-loss change", 1f,
                tri(ev.rsrpSpread?.let { it <= 12 })),
            p("device stationary", 2f, Support.UNOBSERVABLE,
                why = "motionState is not collected; reselection while moving is normal handover")
        )

        4 -> listOf(
            req("PS reject cause ≠ 0", 2f, tri(ev.rejectNonZero),
                whyNot = if (ev.rejectNonZero == null) "no registration event in this window" else null),
            p("CS registered while PS was not", 1f, Support.UNOBSERVABLE,
                why = "only the PS/WWAN registration row is persisted; the CS row is dropped")
        )

        5 -> listOf(
            req("NET_CAPABILITY_NOT_SUSPENDED was lost", 2f, tri(ev.suspendedSeen),
                whyNot = if (ev.suspendedSeen == null) "no default-route capability row in this window" else null),
            p("a voice call was active", 1f, Support.UNOBSERVABLE,
                why = "callState is not collected (context_sample does not exist yet)")
        )

        6 -> listOf(
            req("VALIDATED lost while the radio still had service", 2f,
                when {
                    ev.validatedLost == true && ev.leftService != true -> Support.SUPPORTS
                    ev.validatedLost == false -> Support.CONTRADICTS
                    else -> Support.UNOBSERVABLE
                },
                whyNot = if (ev.validatedLost == null) "no default-route capability row in this window" else null),
            p("DNS / TCP / TLS probes failed", 2f, Support.UNOBSERVABLE,
                why = "no probe_result rows — active probing is Phase 2"),
            p("the default route's IP address changed", 1f, tri(ev.addrChanged)),
            p("IPv6-only bearer without 464XLAT", 1f, tri(ev.v6Only && !ev.clat))
        )

        7 -> listOf(
            req("the default route changed transport", 2f,
                when {
                    ev.routeFlips >= 1 -> Support.SUPPORTS
                    ev.defaults.isEmpty() -> Support.UNOBSERVABLE
                    else -> Support.CONTRADICTS
                },
                whyNot = if (ev.defaults.isEmpty()) "no default-route event in this window" else null),
            p("≥3 transport flips inside the window", 1f, tri(ev.routeFlips >= 3)),
            p("Wi-Fi was at the edge of range", 1f, Support.UNOBSERVABLE,
                why = "Wi-Fi RSSI and SSID are not collected")
        )

        8 -> listOf(
            // Never contradicted: none of the device-side mechanisms are observable, so this
            // cause can only ever be "not excluded". Saying otherwise would be a false negative.
            req("doze, thermal throttle or a VPN interfered", 2f,
                if (ev.vpnSeen) Support.SUPPORTS else Support.UNOBSERVABLE,
                whyNot = "screenOn, doze and thermalStatus are not collected"),
            p("thermal status elevated", 1f, Support.UNOBSERVABLE,
                why = "PowerManager.getCurrentThermalStatus() is not sampled"),
            p("a VPN held the default route", 1f, tri(ev.vpnSeen))
        )

        9 -> listOf(
            req("CQI is reported by this chipset", 2f,
                if (ev.cqiEverSeen) Support.SUPPORTS else Support.UNOBSERVABLE,
                whyNot = "CQI returns UNAVAILABLE on this chipset — the cause-9 signature " +
                        "(good RSRP, low CQI) cannot be evaluated here"),
            p("good RSRP with low CQI", 2f,
                if (ev.cqiEverSeen) tri(ev.radio.mapNotNull { it.cqi }.minOrNull()?.let { it <= 6 })
                else Support.UNOBSERVABLE,
                why = "CQI UNAVAILABLE"),
            p("probe latency elevated", 1f, Support.UNOBSERVABLE,
                why = "no probe_result rows — active probing is Phase 2")
        )

        // Tune-away is an *absence* signature: nothing changes on the data SIM and the data
        // stops anyway. Absence is cheap to satisfy, so it is gated on there being a real
        // interruption to explain. Without that gate every quiet minute scored as tune-away.
        10 -> listOf(
            req("a data interruption was actually observed", 2f,
                if (ev.kind == Kind.OUTAGE) Support.SUPPORTS else Support.CONTRADICTS),
            req("a second subscription is active", 2f,
                if (ev.otherSubPresent) Support.SUPPORTS else Support.CONTRADICTS),
            // A positive mechanism beats an absence one. If the default route moved off the
            // data SIM's bearer entirely, the modem's tune-away behaviour did not cause this.
            req("the data path stayed on one transport", 1f, tri(ev.routeFlips == 0)),
            p("serving cell unchanged through the window", 2f, tri(ev.cellStable)),
            p("RSRP stable within 3 dB", 1f, tri(ev.rsrpSpread?.let { it <= 3 })),
            p("the other subscription was active in the same window", 2f,
                tri(ev.otherRegEvents >= 2)),
            p("a sub-second data gap was observed", 1f, Support.UNOBSERVABLE,
                why = "tune-away gaps are shorter than the ~10 s Layer-1 delivery floor")
        )

        11 -> listOf(
            req("the default route's IP address changed", 2f,
                when {
                    ev.addrChanged -> Support.SUPPORTS
                    ev.defaults.isEmpty() -> Support.UNOBSERVABLE
                    else -> Support.CONTRADICTS
                },
                whyNot = if (ev.defaults.isEmpty()) "no default-route link event in this window" else null),
            // Both of these separate a SIM switch from the far commoner explanations of an
            // IP change. A route that just moved Wi-Fi→cellular changes address by definition;
            // calling that a subscription switch would be the guess this app exists to avoid.
            req("the bearer was cellular", 1f, tri(ev.transports.contains("CELLULAR"))),
            req("the route held one transport across the address change", 1f,
                tri(ev.routeFlips == 0)),
            p("activeDataSubId changed", 2f, Support.UNOBSERVABLE,
                why = "ActiveDataSubscriptionIdListener is not registered by the collector")
        )

        12 -> listOf(
            req("this subscription is roaming, or an IWLAN registration exists", 2f,
                when {
                    ev.roaming == true -> Support.SUPPORTS
                    ev.iwlanRowsCollected -> Support.CONTRADICTS
                    else -> Support.UNOBSERVABLE
                },
                whyNot = if (ev.roaming != true && !ev.iwlanRowsCollected)
                    "only WWAN registration rows are persisted, so the ePDG/VoWiFi registration " +
                            "never reaches the database" else null),
            p("IWLAN PS registration transitioned", 2f, Support.UNOBSERVABLE,
                why = "transportType=WLAN rows are not written"),
            p("roaming was active on this subscription", 1f, tri(ev.roaming))
        )

        else -> emptyList()
    }

    private fun p(text: String, w: Float, s: Support, why: String? = null) =
        Predicate(text, s, w, false, if (s == Support.UNOBSERVABLE) why else null)

    private fun req(text: String, w: Float, s: Support, whyNot: String? = null) =
        Predicate(text, s, w, true, if (s == Support.UNOBSERVABLE) whyNot else null)

    private fun tri(b: Boolean?): Support = when (b) {
        true -> Support.SUPPORTS
        false -> Support.CONTRADICTS
        null -> Support.UNOBSERVABLE
    }

    /**
     * Confidence model.
     *
     *   base      = supporting weight / total weight. An unevaluable predicate stays in the
     *               denominator: not knowing lowers confidence, it does not excuse it.
     *   coverage  = fraction of the four layers with any data in this window, mapped to
     *               0.55…1.00. With no probe layer and only partial context, today's structural
     *               ceiling is about 0.78 — which is why nothing here shows 92 %.
     *   floor     = a window shorter than the ~10 s Layer-1 delivery floor is bounded, not
     *               timed, so it is damped further.
     *
     * Hard-capped at 0.95. There is no 100 % from four sampled layers.
     */
    private fun confidence(preds: List<Predicate>, ev: Ev, coverage: Float): Float {
        val total = preds.sumOf { it.weight.toDouble() }.toFloat()
        if (total <= 0f) return 0f
        val supporting = preds.filter { it.support == Support.SUPPORTS }
            .sumOf { it.weight.toDouble() }.toFloat()
        val base = supporting / total
        val cov = 0.55f + 0.45f * coverage
        val floor = if (ev.durationMs in 1 until LAYER1_FLOOR_MS) 0.8f else 1f
        return min(0.95f, base * cov * floor)
    }

    private fun layerCoverage(ev: Ev): Float {
        var n = 0
        if (ev.radio.isNotEmpty() || ev.reg.isNotEmpty()) n++
        if (ev.link.isNotEmpty()) n++
        // TRANSPORT: probe_result does not exist. Never counted.
        if (ev.radio.any { it.dataActivity != null } || ev.otherReg.isNotEmpty()) n++
        return n / 4f
    }

    // ---------------------------------------------------------------- assembly

    private fun build(
        w: Window, rows: IncidentStore.Rows, sims: Map<Int, SimState>, dataSubId: Int?
    ): Incident {
        val ev = evidence(w, rows, sims)
        val coverage = layerCoverage(ev)

        val assessments = CAUSES.map { c ->
            val preds = predicates(c.num, ev)
            val killer = preds.firstOrNull { it.required && it.support == Support.CONTRADICTS }
            Assessment(
                cause = c,
                predicates = preds,
                confidence = if (killer != null) 0f else confidence(preds, ev, coverage),
                ruledOut = killer != null,
                ruledOutReason = killer?.text?.let { "$it — not true" }
            )
        }

        val live = assessments.filterNot { it.ruledOut }.sortedWith(
            compareByDescending<Assessment> { it.confidence }.thenBy { it.cause.num }
        )
        val top = live.firstOrNull()?.takeIf { it.confidence > 0f }
        val ruledOut = assessments.filter { it.ruledOut }
        val notExcluded = live.filter {
            it.confidence < CLASSIFY_FLOOR && it.unobservable.isNotEmpty()
        }

        val durationMs = max(0L, (w.endElapsed - w.startElapsed) / 1_000_000L)
        val classified = (top?.confidence ?: 0f) >= CLASSIFY_FLOOR

        // Unclassified does not mean uninformative: name the symptom that was measured, and
        // leave the naming of a cause to the verdict card, which says plainly that none reached
        // the floor. "Unclassified" alone would throw away the one thing we do know.
        val headline = if (classified) top!!.cause.name else when {
            "SERVICE_LOSS" in w.detectors() -> "Service lost, cause undetermined"
            "PS_REJECT" in w.detectors() -> "PS attach refused, cause undetermined"
            "VALIDATION_LOSS" in w.detectors() -> "Route unvalidated, cause undetermined"
            "SUSPENDED" in w.detectors() -> "Data suspended, cause undetermined"
            "ADDRESS_CHANGE" in w.detectors() -> "IP changed, cause undetermined"
            "ROUTE_FLAP" in w.detectors() -> "Route flapping, cause undetermined"
            "RESELECTION_CHURN" in w.detectors() -> "Cell churn, cause undetermined"
            "QUALITY_FLOOR" in w.detectors() -> "Signal quality floor, cause undetermined"
            else -> "Unclassified interruption"
        }

        val resolutionNote = when {
            durationMs < LAYER1_FLOOR_MS ->
                "shorter than the ~10 s Layer-1 delivery floor — bounded, not timed; " +
                        "evidence gathered over ±10 s"
            w.detectors().contains("VALIDATION_LOSS") ->
                "bounded by NET_CAPABILITY_VALIDATED, which lags the fault it reports"
            ev.radio.isEmpty() && ev.reg.isEmpty() ->
                "no Layer-1 sample landed inside this window"
            else -> null
        }

        val raise = top?.unobservable?.maxByOrNull { it.weight }?.why
            ?: live.firstOrNull()?.unobservable?.firstOrNull()?.why

        return Incident(
            id = "${w.subId}:${w.startElapsed}:${w.detector}",
            subId = w.subId,
            carrier = sims[w.subId]?.carrier ?: "sub ${w.subId}",
            attribution = w.attribution,
            kind = w.kind,
            detectors = w.detectors(),
            startWall = w.startWall,
            endWall = w.endWall,
            durationMs = durationMs,
            ongoing = w.ongoing,
            headline = headline,
            evidenceLine = evidenceLine(w, ev),
            top = top,
            ranked = live.filter { it.confidence > 0f },
            ruledOut = ruledOut,
            notExcluded = notExcluded,
            layers = layerCards(ev, w),
            resolutionNote = resolutionNote,
            raiseConfidence = raise,
            prevalenceNote = null
        )
    }

    private fun Window.detectors() = detector.split("+")

    /** The single line of evidence under the cause name in the list. Facts only, no adjectives. */
    private fun evidenceLine(w: Window, ev: Ev): String {
        val bits = ArrayList<String>()
        if (ev.leftService == true) {
            bits += ev.reg.map { it.regState }.filter { it != "IN_SERVICE" }.distinct()
                .joinToString("/")
        }
        if (ev.rejectNonZero == true)
            bits += "reject ${ev.reg.firstNotNullOfOrNull { it.rejectCause?.takeIf { c -> c != 0 } }}"
        if (ev.validatedLost == true) bits += "VALIDATED lost"
        if (ev.suspendedSeen == true) bits += "NOT_SUSPENDED lost"
        if (ev.addrChanged) bits += "IP changed — sockets reset"
        if (ev.routeFlips > 0) bits += "route ${ev.transports.joinToString("↔")} ×${ev.routeFlips}"
        if (w.detectors().contains("RESELECTION_CHURN")) {
            bits += "PCI ${ev.pcis.distinct().take(3).joinToString("↔")} ×${ev.cellChanges}"
            ev.rsrpSpread?.let { bits += "RSRP spread $it dB" }
        }
        if (w.detectors().contains("QUALITY_FLOOR")) {
            ev.minSinr?.let { bits += "SINR $it dB" }
            ev.minRsrp?.let { bits += "RSRP $it" }
            ev.maxLevel?.let { bits += "bars $it/4" }
        }
        if (bits.isEmpty()) {
            bits += "${ev.radio.size} radio · ${ev.reg.size} reg · ${ev.link.size} link rows"
        }
        return bits.joinToString(" · ")
    }

    private fun layerCards(ev: Ev, w: Window): List<LayerCard> {
        val radioRows = ArrayList<Pair<String, String>>()
        val states = ev.reg.map { it.regState }.distinct()
        radioRows += "state" to (if (states.isEmpty()) "no sample" else states.joinToString("→"))
        radioRows += "signal" to buildString {
            append(ev.minRsrp?.let { "$it" } ?: "—")
            if (ev.maxRsrp != null && ev.maxRsrp != ev.minRsrp) append("→${ev.maxRsrp}")
            append(" dBm")
            ev.minSinr?.let { append(" · SINR $it") }
        }
        radioRows += "cell" to when {
            ev.pcis.isEmpty() -> "no identity sampled"
            ev.cellChanges == 0 -> "PCI ${ev.pcis.first()} stable"
            else -> "PCI ${ev.pcis.distinct().joinToString("↔")} ×${ev.cellChanges}"
        }
        val radioVerdict = when {
            ev.reg.isEmpty() && ev.radio.isEmpty() -> LayerVerdict.NOT_COLLECTED
            ev.leftService == true -> LayerVerdict.LOST
            (ev.minRsrp ?: 0) <= -110 || (ev.minSinr ?: 99) < 0 || ev.cellChanges >= 4 ->
                LayerVerdict.DEGRADED
            else -> LayerVerdict.HEALTHY
        }

        val netRows = ArrayList<Pair<String, String>>()
        netRows += "validated" to when (ev.validatedLost) {
            true -> "lost in window"; false -> "held"; null -> "no event"
        }
        netRows += "route" to (ev.transports.joinToString("↔").ifEmpty { "—" } +
                if (ev.routeFlips > 0) " ×${ev.routeFlips}" else "")
        netRows += "address" to when {
            ev.addrChanged -> "CHANGED — sockets reset"
            ev.defaults.isEmpty() -> "no event"
            ev.v6Only -> "v6 only · 464xlat ${if (ev.clat) "up" else "no"}"
            else -> "held"
        }
        val netVerdict = when {
            ev.defaults.isEmpty() -> LayerVerdict.NOT_COLLECTED
            ev.validatedLost == true || ev.addrChanged -> LayerVerdict.FAILING
            ev.suspendedSeen == true -> LayerVerdict.DEGRADED
            ev.routeFlips > 0 -> LayerVerdict.DEGRADED
            else -> LayerVerdict.HEALTHY
        }

        // TRANSPORT is honest emptiness. probe_result is specified in data-model.md and is not
        // implemented; rendering this card as "healthy" would be the single most misleading
        // thing this screen could do, because layer 3 is where causes 6 and 9 are decided.
        val transportRows = listOf(
            "dns" to "not collected",
            "tcp · tls" to "not collected",
            "http 204" to "not collected"
        )

        val ctxRows = ArrayList<Pair<String, String>>()
        ctxRows += "other sim" to
                if (ev.otherRegEvents == 0) "quiet in window"
                else "${ev.otherRegEvents} reg events · ${ev.otherStates.joinToString("/")}"
        ctxRows += "radio" to if (ev.dormant) "DORMANT seen" else "active"
        ctxRows += "motion" to "not collected"
        val ctxVerdict = when {
            ev.otherRegEvents >= 2 -> LayerVerdict.DEGRADED
            ev.otherReg.isEmpty() && !ev.radio.any { it.dataActivity != null } ->
                LayerVerdict.NOT_COLLECTED
            else -> LayerVerdict.NOMINAL
        }

        return listOf(
            LayerCard(Layer.RADIO, radioVerdict, radioRows),
            LayerCard(Layer.NETWORK, netVerdict, netRows,
                if (w.attribution == Attribution.ASSUMED)
                    "attributed to the data subscription; role history is not recorded" else null),
            LayerCard(Layer.TRANSPORT, LayerVerdict.NOT_COLLECTED, transportRows,
                "no probe_result rows — active probing is Phase 2"),
            LayerCard(Layer.CONTEXT, ctxVerdict, ctxRows,
                "screen, doze, thermal and position are not collected")
        )
    }

    // ---------------------------------------------------------------- formatting

    fun fmtDuration(ms: Long): String {
        if (ms < 1000) return "<10 s"
        val s = (ms / 1000.0).roundToInt()
        if (s < 60) return "$s s"
        val m = s / 60
        val r = s % 60
        return if (m < 60) "${m}m${r.toString().padStart(2, '0')}"
        else "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
    }

    fun fmtTotal(ms: Long): String {
        if (ms <= 0) return "0"
        val s = ms / 1000
        return if (s < 60) "${s}s" else "${s / 60}m${(s % 60).toString().padStart(2, '0')}"
    }
}
