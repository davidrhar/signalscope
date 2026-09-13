package com.signalscope.ui

import com.signalscope.collect.BearerWarmth
import com.signalscope.collect.Journey
import com.signalscope.collect.Mobility
import com.signalscope.collect.HandoverPredictor
import com.signalscope.collect.CellProbe
import com.signalscope.collect.Fault
import com.signalscope.collect.WarmthBlock
import com.signalscope.store.MapBinBuilder
import com.signalscope.store.MapProbeJoin

/**
 * State in, sentences out.
 *
 * Everything here is a pure function of state the collectors already publish, with no Compose and
 * no Context, so it can be read, argued with and unit-tested without a device. That separation is
 * the point: the sentences are the part of this app most likely to be wrong, and they are the part
 * a non-technical reader actually reads.
 *
 * **What the sentences are allowed to say.** `docs/excursion-findings.md` moved the diagnosis:
 * across 25 minutes of real cellular use a median SINR of 0 dB — bad by any textbook — produced
 * zero user-visible failures, while the same bearer left dormant behind Wi-Fi failed 12.3 % of
 * cold wake-ups (7 of 57), every failure a ~6 s timeout. Inside each probe pair the first probe
 * averaged 457 ms and peaked at 5.3 s against the second's 168 ms. So RSRP, RSRQ and SINR are the
 * weather and never the headline. The headline is outcomes: did the connection work, how long did
 * waking it take, how often did it fail.
 *
 * **Three rules, in order of how often they have been broken here.**
 *
 *  1. Absent data reads as absent. A missing measurement says so and says what would produce it.
 *     A fresh install must never look healthy; it must look empty, because it is. This is the
 *     project's recurring failure mode and the reason this file exists.
 *  2. A number with an interval is stated as an interval — "between X and Y" — and a figure that
 *     is not observable from an app (avoided wake-ups, for one) stays a range rather than being
 *     collapsed to whichever end reads better.
 *  3. Nothing is written for one device, carrier, SIM, band or profile. Every noun in every
 *     sentence below comes from the state object it was handed.
 */
object PlainLanguage {

    /** How a sentence should feel, not how strong the radio is. */
    enum class Tone {
        /** Working, or deliberately idle because working needs nothing. */
        GOOD,

        /** True and unremarkable. */
        NEUTRAL,

        /** Worth knowing, not broken. */
        WATCH,

        /** Measured and bad. */
        BAD,

        /** Not measured. Never mixed with the ones above. */
        ABSENT
    }

    /**
     * One thing said in one place.
     *
     * [headline] is the sentence a reader who has never heard of RRC or SINR must understand on
     * its own. [numbers] is for the reader who wants the measurement; it is deliberately
     * secondary, never the message. [toMeasure] is what would turn an [Tone.ABSENT] into an
     * answer, and it is required whenever [measured] is false — an empty panel that does not say
     * how to fill itself is the failure mode this whole layer exists to prevent.
     */
    data class Verdict(
        val headline: String,
        val body: String = "",
        val tone: Tone = Tone.NEUTRAL,
        val numbers: List<Pair<String, String>> = emptyList(),
        val measured: Boolean = true,
        val toMeasure: String? = null
    )

    private fun absent(headline: String, body: String, toMeasure: String) = Verdict(
        headline = headline, body = body, tone = Tone.ABSENT,
        measured = false, toMeasure = toMeasure
    )

    // ================================================================= formatting

    /** Seconds once a figure stops being a reflex. Nobody reads 5263 ms as "five seconds". */
    fun ms(v: Int): String = if (v >= 1000) "%.1f s".format(v / 1000.0) else "$v ms"

    fun pct(f: Double): String = "%.0f %%".format(f * 100)

    private fun minutesOf(millis: Long): String {
        val m = millis / 60_000.0
        return if (m < 1.0) "%.0f seconds".format(millis / 1000.0) else "%.0f minutes".format(m)
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"

    /** "between 88 % and 99 %", from a Wilson interval on a proportion. Rule 2. */
    private fun interval(lo: Double, hi: Double) = "between ${pct(lo)} and ${pct(hi)}"

    // ================================================================= connection health

    /**
     * The four fields of a probe row this layer needs, restated locally so the language layer has
     * no dependency on storage and a test can exercise it with four values.
     *
     * [cold] is the distinction the whole diagnosis rests on: a probe that arrived with no recent
     * traffic on the bearer had to pay the idle→connected transition, and one that did not, did
     * not. [MapProbeJoin] decides it from row order and it is passed through unchanged.
     *
     * [noAddressOfFamily] marks a probe that never ran because the bearer had no address of the
     * family it asked for. That is a property of the connection's configuration, not a failure to
     * wake up, and [WarmthExperiment] excludes it from the same endpoint for the same reason. It
     * is counted and reported separately rather than quietly dropped.
     */
    data class ProbeObservation(
        val ok: Boolean,
        val latencyMs: Int,
        val cold: Boolean,
        val onBearer: Boolean,
        val noAddressOfFamily: Boolean = false
    )

    data class ProbeStats(
        val coldTotal: Int = 0,
        val coldFailures: Int = 0,
        val warmTotal: Int = 0,
        val warmFailures: Int = 0,
        /** Latencies of the probes that SUCCEEDED. A failure's latency is the timeout it hit. */
        val coldOkLatency: List<Int> = emptyList(),
        val warmOkLatency: List<Int> = emptyList(),
        /** How long the failures took before giving up — measured, so the wait is not guessed. */
        val coldFailLatency: List<Int> = emptyList(),
        /** Probes with no cellular network to bind to: a statement about the phone, not the place. */
        val noBearer: Int = 0,
        val noAddressRows: Int = 0
    ) {
        val coldOk: Int get() = coldTotal - coldFailures
        val coldSuccess: Double?
            get() = if (coldTotal == 0) null else coldOk.toDouble() / coldTotal
        val warmSuccess: Double?
            get() = if (warmTotal == 0) null else (warmTotal - warmFailures).toDouble() / warmTotal
        val anyMeasurement: Boolean get() = coldTotal > 0 || warmTotal > 0
    }

    /**
     * Below this many cold wake-ups there is no verdict, only a count.
     *
     * Ten is the same floor [CellProbe.asymSummary] uses for its own interval, and it is where a
     * Wilson interval on a zero-failure run first excludes a failure rate the size of the one the
     * findings measured. Under it, "no failures" and "not enough attempts to have seen one" are
     * indistinguishable — and reporting the first when the second is true is exactly the mistake
     * this layer is built to stop.
     */
    const val MIN_WAKEUPS = 10

    fun probeStats(rows: List<ProbeObservation>): ProbeStats {
        var coldTotal = 0; var coldFail = 0; var warmTotal = 0; var warmFail = 0
        var noBearer = 0; var noAddress = 0
        val coldOk = ArrayList<Int>(); val warmOk = ArrayList<Int>(); val coldFailMs = ArrayList<Int>()

        for (r in rows) {
            if (!r.onBearer) { noBearer++; continue }
            if (r.noAddressOfFamily) { noAddress++; continue }
            if (r.cold) {
                coldTotal++
                if (r.ok) coldOk += r.latencyMs else { coldFail++; coldFailMs += r.latencyMs }
            } else {
                warmTotal++
                if (!r.ok) warmFail++ else warmOk += r.latencyMs
            }
        }
        return ProbeStats(
            coldTotal = coldTotal, coldFailures = coldFail,
            warmTotal = warmTotal, warmFailures = warmFail,
            coldOkLatency = coldOk.sorted(), warmOkLatency = warmOk.sorted(),
            coldFailLatency = coldFailMs.sorted(),
            noBearer = noBearer, noAddressRows = noAddress
        )
    }

    /**
     * The overall verdict: did the connection work, and how long did waking it take.
     *
     * Success comes first and latency second, because a connection that fails is not a slow
     * connection. Both limbs are reported with the evidence behind them, and neither is reported
     * before there is enough of it.
     */
    fun connectionHealth(s: ProbeStats): Verdict {
        val numbers = healthNumbers(s)

        if (!s.anyMeasurement) {
            val why = when {
                s.noBearer > 0 ->
                    "The phone has tried ${times(s.noBearer)}, and each time there was no mobile " +
                        "connection available to test at all."
                else ->
                    "Nothing has tested the mobile connection yet, so there is nothing to report — " +
                        "not \"it is fine\"."
            }
            return absent(
                "Not measured yet.",
                why,
                "A test runs about every 45 seconds while mobile data is carrying the phone, and " +
                    "every few minutes while you are on Wi-Fi. Leave collecting switched on and " +
                    "come back."
            ).copy(numbers = numbers)
        }

        if (s.coldTotal < MIN_WAKEUPS) {
            return absent(
                "Too early to say.",
                "The phone has woken the connection from idle ${times(s.coldTotal)} so far. That " +
                    "is not enough attempts for \"no failures\" to mean anything — at least " +
                    "$MIN_WAKEUPS are needed before this panel will give a verdict.",
                "Use the phone off Wi-Fi for a while. Wake-ups are measured roughly every 45 " +
                    "seconds then, so ${MIN_WAKEUPS - s.coldTotal} more is about " +
                    "${((MIN_WAKEUPS - s.coldTotal) * 45) / 60 + 1} minutes of use."
            ).copy(numbers = numbers)
        }

        val rate = s.coldSuccess!!
        val ci = MapBinBuilder.wilson(rate, s.coldTotal)
        val slow = slowWakeUp(s)
        val failWait = MapProbeJoin.percentile(s.coldFailLatency, 0.5)

        return when {
            // A failure rate whose lower bound clears the floor is a finding, not a wobble.
            rate < MapProbeJoin.SUCCESS_FLOOR && ci[0] < MapProbeJoin.SUCCESS_FLOOR -> Verdict(
                headline = "The connection keeps failing to wake up.",
                body = buildString {
                    append("${s.coldFailures} of the ${s.coldTotal} times something needed the ")
                    append("sleeping mobile connection, it did not come back. ")
                    failWait?.let {
                        append("Each of those was a wait of about ${ms(it)} before whatever was ")
                        append("waiting gave up. ")
                    }
                    append("Success rate ${interval(ci[0], ci[1])}, on the measurements so far.")
                },
                tone = Tone.BAD,
                numbers = numbers
            )
            s.coldFailures == 0 && slow -> Verdict(
                headline = "The connection always works, but waking it up is slow.",
                body = wakeUpSentence(s) + " Nothing failed in ${s.coldTotal} wake-ups, so this is " +
                    "a delay rather than a fault: an app that has been quiet for a while waits " +
                    "before it gets anything.",
                tone = Tone.WATCH,
                numbers = numbers
            )
            s.coldFailures == 0 -> Verdict(
                headline = "The connection works, and waking it up is quick.",
                body = "All ${s.coldTotal} attempts to wake the connection from idle succeeded " +
                    "(success rate ${interval(ci[0], ci[1])} given that many attempts). " +
                    wakeUpSentence(s),
                tone = Tone.GOOD,
                numbers = numbers
            )
            else -> Verdict(
                headline = "The connection mostly works, with occasional failures to wake up.",
                body = buildString {
                    append("${s.coldFailures} of ${s.coldTotal} wake-ups failed — a success rate ")
                    append("${interval(ci[0], ci[1])}, which does not rule out a real problem ")
                    append("and does not establish one either. ")
                    append(wakeUpSentence(s))
                },
                tone = Tone.WATCH,
                numbers = numbers
            )
        }
    }

    private fun slowWakeUp(s: ProbeStats): Boolean {
        if (s.coldOkLatency.size < MapProbeJoin.MIN_PROBES_FOR_LATENCY) return false
        val p90 = MapProbeJoin.percentile(s.coldOkLatency, 0.9) ?: return false
        return p90 > MapProbeJoin.SLOW_P90_MS
    }

    /** The latency limb, in one sentence, or an admission that it has not been measured. */
    fun wakeUpSentence(s: ProbeStats): String {
        if (s.coldOkLatency.size < MapProbeJoin.MIN_PROBES_FOR_LATENCY) {
            return "How long waking it up takes is not measured yet: that needs at least " +
                "${MapProbeJoin.MIN_PROBES_FOR_LATENCY} successful wake-ups and there have been " +
                "${s.coldOkLatency.size}."
        }
        val p50 = MapProbeJoin.percentile(s.coldOkLatency, 0.5)!!
        val p90 = MapProbeJoin.percentile(s.coldOkLatency, 0.9)!!
        val warm = if (s.warmOkLatency.size >= MapProbeJoin.MIN_PROBES_FOR_LATENCY)
            MapProbeJoin.percentile(s.warmOkLatency, 0.5) else null
        return buildString {
            append("Waking it up usually takes ${ms(p50)}, and one wake-up in ten takes ${ms(p90)}")
            append(if (p90 > MapProbeJoin.SLOW_P90_MS) " — long enough to see a spinner. " else ". ")
            if (warm != null) {
                append("The same test on a connection that is already awake takes ${ms(warm)}, ")
                append("so most of that wait is the waking, not the network.")
            }
        }
    }

    /**
     * The finding, stated only where this phone's own measurements support it.
     *
     * The excursion's contrast — 12.3 % of cold wake-ups failing against 0 of 36 while the bearer
     * carried traffic — is what the whole feature rests on, but it is a measurement from one trip
     * and it is not this user's. So this returns null unless the same contrast is present in the
     * rows in hand, with enough warm probes for the comparison to mean anything. No local
     * evidence, no claim.
     */
    fun dormancyContrast(s: ProbeStats): Verdict? {
        if (s.coldTotal < MIN_WAKEUPS || s.warmTotal < MIN_WAKEUPS) return null
        val cold = s.coldSuccess ?: return null
        val warm = s.warmSuccess ?: return null
        if (s.coldFailures == 0) return null
        if (warm <= cold) return null
        return Verdict(
            headline = "It is not a weak signal. It is a connection that keeps going to sleep.",
            body = "When the connection had been idle, ${s.coldFailures} of ${s.coldTotal} " +
                "attempts to use it failed (${pct(cold)} worked). When it was already carrying " +
                "traffic, ${s.warmTotal - s.warmFailures} of ${s.warmTotal} worked " +
                "(${pct(warm)}). Same phone, same place, same signal — what differs is whether " +
                "the connection had to be woken up.",
            tone = Tone.BAD,
            measured = true
        )
    }

    /** Why the numbers everyone else leads with are not the headline here. */
    fun signalIsTheWeather(): String =
        "Signal strength is not part of this verdict on purpose. Measured on this project: 25 " +
            "minutes of real use at a signal quality any textbook calls bad broke nothing at " +
            "all, while an idle connection at good signal failed one wake-up in eight. Bars " +
            "describe the weather; what is above describes whether it worked."

    private fun healthNumbers(s: ProbeStats): List<Pair<String, String>> = buildList {
        add("wake-ups" to
            if (s.coldTotal == 0) "none measured yet"
            else "${s.coldOk}/${s.coldTotal} worked" +
                if (s.coldTotal >= MIN_WAKEUPS) {
                    val ci = MapBinBuilder.wilson(s.coldSuccess!!, s.coldTotal)
                    " · ${interval(ci[0], ci[1])}"
                } else " · too few for a rate"
        )
        add("already awake" to
            if (s.warmTotal == 0) "none measured yet"
            else "${s.warmTotal - s.warmFailures}/${s.warmTotal} worked"
        )
        add("wake-up time" to
            if (s.coldOkLatency.size < MapProbeJoin.MIN_PROBES_FOR_LATENCY) "not measured yet"
            else "typical ${ms(MapProbeJoin.percentile(s.coldOkLatency, 0.5)!!)} · " +
                "slowest 1 in 10 ${ms(MapProbeJoin.percentile(s.coldOkLatency, 0.9)!!)}"
        )
        add("when awake" to
            if (s.warmOkLatency.size < MapProbeJoin.MIN_PROBES_FOR_LATENCY) "not measured yet"
            else "typical ${ms(MapProbeJoin.percentile(s.warmOkLatency, 0.5)!!)}"
        )
        if (s.coldFailLatency.isNotEmpty()) {
            add("each failure" to
                "waited ${ms(MapProbeJoin.percentile(s.coldFailLatency, 0.5)!!)} before giving up")
        }
        if (s.noAddressRows > 0) {
            add("not counted" to "${s.noAddressRows} test(s) had no address of the type asked " +
                "for — a setting, not a failure to wake up")
        }
        if (s.noBearer > 0) {
            add("no connection" to "${s.noBearer} test(s) found no mobile connection to use")
        }
    }

    // ================================================================= faults

    /**
     * A short label for a fault card. [CarrierFaults] already writes the description and the
     * suggested action from the evidence it holds — that logic is not repeated here, only framed.
     */
    fun faultHeadline(f: Fault): String {
        val label = f.kind.label.replaceFirstChar { it.uppercase() }
        return if (f.active) label else "$label — stopped"
    }

    /**
     * The empty case, which is the good case and must read like one.
     *
     * It splits on whether anything is actually watching. "No problems found" from a stopped
     * collector is not a clean bill of health, it is an absence of looking, and conflating the two
     * is precisely rule 1.
     */
    fun noFaultsFound(watching: Boolean): Verdict =
        if (!watching) absent(
            "Nothing is watching for repeating faults right now.",
            "This looks for a connection the phone keeps setting up and losing — the kind of " +
                "fault that can leave a feature like HD calling quietly broken for months. " +
                "Nothing is checking while collection is stopped, so an empty list here means " +
                "nothing.",
            "Start collecting. A repeating fault is recognised by its pattern, so it needs a few " +
                "minutes of watching before it can be confirmed."
        )
        else Verdict(
            headline = "No repeating faults found.",
            body = "Nothing on this phone is stuck setting up a connection it never gets. That " +
                "is the result you want, and it is being re-checked every minute — if it " +
                "changes, it will appear here with what to do about it.",
            tone = Tone.GOOD
        )

    /**
     * The same result, but with how long it took to be sure of it.
     *
     * "No faults found" from a detector that started ten seconds ago and from one that has
     * watched all evening are very different statements, and the reader cannot tell them apart
     * unless the duration is on screen. Under a few minutes the honest answer is that it is too
     * early, not that the phone is fine.
     */
    fun noFaultsFound(watching: Boolean, watchingSinceMillis: Long): Verdict {
        if (!watching || watchingSinceMillis <= 0L) return noFaultsFound(watching)
        val forMs = System.currentTimeMillis() - watchingSinceMillis
        if (forMs < 5 * 60_000L) return absent(
            "Too early to say whether there is a repeating fault.",
            "A repeating fault is recognised by its pattern rather than by any single event, so " +
                "it takes a few minutes of watching before its absence means anything. " +
                "Watching for ${forWords(forMs)} so far.",
            "Leave the app collecting. This answers itself in a few minutes."
        )
        return Verdict(
            headline = "No repeating faults found.",
            body = "Watched for ${forWords(forMs)} and found nothing stuck setting up a " +
                "connection it never gets. That is the result you want, and it is re-checked " +
                "every minute — if it changes, it will appear here with what to do about it.",
            tone = Tone.GOOD,
            numbers = listOf("watched for" to forWords(forMs))
        )
    }

    /** Durations as a person says them. "2 h 14 m", not 8040000. */
    fun forWords(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m minute${if (m == 1L) "" else "s"}"
            else -> "${m / 60} h ${m % 60} m"
        }
    }

    /**
     * The Tier-2 note, framed as what it is: how to see more, never an error. Deeper attribution
     * is an optional upgrade, and its absence costs nothing that matters.
     */
    fun howToSeeMore(tier2Note: String?): String? =
        tier2Note?.takeIf { it.isNotBlank() }?.let { "How to see more — $it" }

    // ================================================================= warmth

    /**
     * Triggers in the user's terms. The keys are [BearerWarmth]'s own trigger labels; an unknown
     * one falls through to itself rather than being guessed at, so a trigger added later shows up
     * as its own name instead of as a wrong sentence.
     */
    fun triggerWords(trigger: String): String = when {
        trigger == "call" -> "you are on a call"
        trigger == "wifi-handover" -> "the phone has just come off Wi-Fi, the worst moment measured"
        trigger == "media" -> "something is playing"
        trigger == "media-burst-gap" -> "there is a gap in streaming traffic, the pattern that stalls video"
        trigger.startsWith("manual:experiment") -> "a measurement is running"
        trigger.startsWith("manual:") -> "a hold was asked for by hand"
        trigger == "—" -> "nothing right now"
        else -> trigger
    }

    /**
     * What warmth is doing, or why it is not, in one sentence.
     *
     * The inactive reasons are matched on [BearerWarmth]'s own phrasing. An unrecognised reason is
     * shown verbatim rather than smoothed into something friendlier: a reason we cannot translate
     * is still better than a reassuring sentence that is not true.
     */
    fun warmthState(s: BearerWarmth.State): Verdict {
        val numbers = buildList {
            add("state" to if (s.active) "holding · ${s.trigger}" else "not holding")
            if (s.active) add("held for" to "${s.heldSecondsThisSession} s")
            add("reason" to (s.inactiveReason ?: "trigger: ${s.trigger}"))
            add("battery" to
                if (s.batteryKnown) "${s.batteryPct} %"
                else "not readable on this phone — the level gate is running blind")
            add("signal source" to s.triggerSource)
        }

        if (s.active) return Verdict(
            headline = "Holding the connection awake right now.",
            body = "It is being held because ${triggerWords(s.trigger)}. Holding it means the " +
                "next thing that needs the connection does not have to wait for it to wake up — " +
                "and because this is the phone's connection rather than ours, every app benefits.",
            tone = Tone.GOOD,
            numbers = numbers
        )

        val reason = s.inactiveReason ?: ""
        return when {
            // On Wi-Fi there is nothing riding on the mobile connection, so holding it awake
            // would be battery spent for no benefit. Correct behaviour, and it must read as such.
            reason.startsWith("cellular is not the default route") -> Verdict(
                headline = "Nothing to keep awake — you are on Wi-Fi.",
                body = "Wi-Fi is carrying the phone, so the mobile connection is idle and nothing " +
                    "is waiting on it. Holding it awake now would cost battery and buy nothing, " +
                    "so it is deliberately off. It starts by itself the moment you leave Wi-Fi, " +
                    "which is the moment it matters most.",
                tone = Tone.GOOD,
                numbers = numbers
            )
            reason.contains("floor and not charging") -> Verdict(
                headline = "Paused to protect the battery.",
                body = "The battery is low and not charging, so nothing optional is running. It " +
                    "resumes on its own once you plug in or the level recovers.",
                tone = Tone.WATCH,
                numbers = numbers
            )
            reason == "not started" -> absent(
                "Not running yet.",
                "Nothing has held the connection awake, so there is nothing to report about it.",
                "It starts with collection. Turn collecting on and it will run whenever holding " +
                    "the connection would help."
            ).copy(numbers = numbers)
            reason == "stopped" -> absent(
                "Stopped.",
                "Collection is off, so nothing is holding the connection awake and nothing is " +
                    "being measured about it.",
                "Turn collecting back on."
            ).copy(numbers = numbers)
            // Warmth's own words for "I cannot see what is playing". Not a failure, but it does
            // mean a streaming gap can pass unnoticed, and that is worth saying out loud.
            reason.contains("could be missed") -> Verdict(
                headline = "Standing by — but it cannot see what is playing.",
                body = "Nothing right now needs the connection held awake. While the phone is " +
                    "in the background it cannot always tell that something is streaming, so a " +
                    "pause in a video could go unnoticed. Calls and the moment you leave Wi-Fi " +
                    "are still covered.",
                tone = Tone.WATCH,
                numbers = numbers
            )
            reason.startsWith("no trigger active") -> Verdict(
                headline = "Standing by.",
                body = "Nothing right now needs the connection held awake. It holds during a " +
                    "call, for the first couple of minutes after you leave Wi-Fi, and while " +
                    "something is streaming — and it stays out of the way the rest of the time, " +
                    "because holding it costs battery.",
                tone = Tone.GOOD,
                numbers = numbers
            )
            reason.startsWith("manual hold") -> Verdict(
                headline = "Holding the connection awake for a measurement.",
                body = reason,
                tone = Tone.NEUTRAL,
                numbers = numbers
            )
            else -> Verdict(
                headline = "Not holding the connection awake.",
                body = if (reason.isBlank())
                    "No reason was recorded, which itself means something is wrong here rather " +
                        "than that everything is fine."
                else reason.replaceFirstChar { it.uppercase() } + ".",
                tone = if (reason.isBlank()) Tone.WATCH else Tone.NEUTRAL,
                numbers = numbers
            )
        }
    }

    /**
     * The rolling history.
     *
     * Two honesty obligations live here. Coverage: a hold with no keepalive behind it warmed
     * nothing, and [BearerWarmth] measures that rather than assuming intent equals effect. And
     * avoided wake-ups, which [WarmthSession] reports as a range because the true figure is not
     * observable from an app — one continuous idle span costs exactly one wake-up no matter how
     * long it is, while a bearer left alone may be re-woken once per inactivity timeout. The range
     * is presented as a range; picking a number out of it would be inventing certainty.
     */
    fun warmthHistory(s: BearerWarmth.State): Verdict {
        if (s.sessions == 0) return absent(
            "It has not held the connection awake yet.",
            "Nothing has been measured here, which is what a fresh install looks like. It holds " +
                "only when holding would help, so an empty history can simply mean you have not " +
                "left Wi-Fi during a call yet.",
            "Take a call, or use the phone for a couple of minutes just after leaving Wi-Fi."
        )

        val covered = s.coverage
        val lo = s.wakeupsAvoidedLower
        val hi = s.wakeupsAvoidedUpper
        return Verdict(
            headline = "Held the connection awake ${times(s.sessions)}, " +
                "${minutesOf(s.totalHeldMs)} in total.",
            body = buildString {
                append("A keepalive actually went out for ${pct(covered)} of that time. ")
                if (covered < 0.7) {
                    append("The rest of it warmed nothing: with the screen off the phone defers ")
                    append("our timer, so the hold was intended but not delivered. That gap is ")
                    append("measured rather than assumed. ")
                }
                append("Idle gaps spanned: ")
                append(if (lo == hi) "$lo–$hi (both ends of the range agree)"
                       else "between $lo and $hi")
                append(". It is a range because an app cannot see the phone's own idle timer — ")
                append("one long quiet spell costs one wake-up, several short ones cost more.")
                if (s.sendFailures > 0) {
                    append(" ${s.sendFailures} of ${s.sends} keepalives did not get out at all.")
                }
            },
            tone = if (covered < 0.7) Tone.WATCH else Tone.GOOD,
            numbers = listOf(
                "holds" to s.sessions.toString(),
                "held total" to minutesOf(s.totalHeldMs),
                "covered" to "${pct(covered)} of held time had a keepalive behind it",
                "idle gaps spanned" to
                    (if (lo == hi) "$lo–$hi" else "between $lo and $hi") + " (not observable exactly)",
                "keepalives" to "${s.sends} sent · ${s.sendFailures} failed"
            )
        )
    }

    /**
     * The limit of the claim, stated where the claim is made.
     *
     * Warmth removes one measured failure class. It is not a fix for everything that has ever gone
     * wrong with a mobile connection, and a UI that lets a reader believe otherwise has lied by
     * omission.
     */
    fun warmthScope(): String =
        "What this fixes, and what it does not: it removes one measured failure — a connection " +
            "that had gone to sleep and then took seconds to wake, which happened to one wake-up " +
            "in eight on an idle connection. It does nothing for a connection lost while moving " +
            "at speed, on a train or in a car: that is a different mechanism, and holding the " +
            "connection awake does not address it."

    // ================================================================= upload vs download

    /**
     * Upload against download, rendered as [CellProbe.asymSummary] found it.
     *
     * That function puts validity before effect and is willing to say "too few samples" or "no
     * detectable asymmetry". The verdict token it chose is matched here and turned into a
     * sentence; the number is never recovered from an inconclusive result, and the summary's own
     * report is shown underneath so the rendering can be checked against the measurement.
     */
    fun upVersusDown(a: CellProbe.AsymSummary?): Verdict {
        if (a == null) return absent(
            "Not measured yet.",
            "Nothing has compared sending against receiving on this phone yet.",
            "The comparison runs by itself every few minutes while mobile data is in use. It " +
                "sends real bytes, so it is capped at 1 MB a day and takes a while to gather " +
                "enough pairs."
        )

        val numbers = buildList {
            add("paired tests" to "${a.pairs} run · ${a.complete} usable")
            add("failed halves" to "sending ${a.upFailures} · receiving ${a.downFailures}")
            add("typical time" to
                if (a.downMedianMs == null || a.upMedianMs == null) "not measured yet"
                else "receiving ${ms(a.downMedianMs)} · sending ${ms(a.upMedianMs)}")
            add("ratio" to
                if (a.ratio == null || a.ratioLo == null || a.ratioHi == null) "no interval yet"
                else "%.2fx · between %.2fx and %.2fx".format(a.ratio, a.ratioLo, a.ratioHi))
        }
        val r = a.report

        // Matched on the summary's own verdict tokens, in the order it decides them, so this can
        // never report an effect the measurement refused to claim.
        return when {
            r.contains("TOO FEW SAMPLES") -> absent(
                "Not enough tests yet to compare sending with receiving.",
                "${a.complete} usable pairs so far. Below ten, any difference found would be " +
                    "noise wearing a number.",
                "It runs itself every few minutes while mobile data is in use, within a 1 MB " +
                    "daily cap. Leave it collecting off Wi-Fi."
            ).copy(numbers = numbers)
            r.contains("INCOMPLETE") -> Verdict(
                headline = "One direction is failing outright, so there is nothing to compare.",
                body = "${a.upFailures + a.downFailures} of ${a.pairs} paired tests lost a half " +
                    "(sending ${a.upFailures}, receiving ${a.downFailures}). That split is the " +
                    "finding. Timing only the tests that survived is exactly how a broken " +
                    "direction comes out looking healthy, so no ratio is given.",
                tone = Tone.BAD,
                numbers = numbers
            )
            r.contains("CONFOUNDED") -> absent(
                "No answer: the tests cannot separate direction from order.",
                "One direction went first in ${pct(a.downFirstFraction)} of the tests, and " +
                    "whichever goes first pays the cost of waking the connection. A difference " +
                    "measured that way is indistinguishable from a difference in direction.",
                "More tests, with the order evening out — it alternates by design, so this " +
                    "resolves on its own."
            ).copy(numbers = numbers)
            r.contains("NO INTERVAL") || r.contains("TOO VARIABLE") -> absent(
                "No answer: the results are too scattered to bound.",
                "The tests so far disagree with each other by more than any difference between " +
                    "the two directions, so nothing can be claimed either way.",
                "More paired tests. They accumulate while mobile data is in use."
            ).copy(numbers = numbers)
            r.contains("UPLINK SLOWER") -> Verdict(
                headline = "Sending is slower than receiving on this connection.",
                body = "The same small amount of data takes between " +
                    "%.2f and %.2f times as long to send as to receive. ".format(a.ratioLo, a.ratioHi) +
                    "That is the direction nothing else in this app measures, and it is the one " +
                    "that hurts a video call, a photo upload or a voice note. Both halves " +
                    "include the same connection setup, which drags the figure toward 1 — so " +
                    "this is a floor on the difference, not a ceiling.",
                tone = Tone.WATCH,
                numbers = numbers
            )
            r.contains("DOWNLINK SLOWER") -> Verdict(
                headline = "Receiving is slower than sending on this connection.",
                body = "This is the opposite of what was expected, and it is reported as " +
                    "measured. The same small amount of data takes between " +
                    "%.2f and %.2f times as long to receive as to send."
                        .format(1.0 / (a.ratioHi ?: 1.0), 1.0 / (a.ratioLo ?: 1.0)),
                tone = Tone.WATCH,
                numbers = numbers
            )
            r.contains("NO DETECTABLE ASYMMETRY") -> Verdict(
                headline = "No difference found between sending and receiving.",
                body = "Across ${a.complete} paired tests the two directions are within " +
                    "measurement error of each other. That is not proof they are identical — it " +
                    "means any difference is smaller than this many tests can see.",
                tone = Tone.GOOD,
                numbers = numbers
            )
            else -> absent(
                "No conclusion yet.",
                "The comparison has run but has not reached a verdict it is willing to state.",
                "More paired tests; the measurement's own words are below."
            ).copy(numbers = numbers)
        }
    }

    // ================================================================= battery cost

    private val COST_MA = Regex("""warmth costs ([+-]?\d+\.\d+) mA""")

    /**
     * What holding the connection awake costs, including all the ways the answer can honestly be
     * "we do not know".
     *
     * [WarmthExperiment] reports validity before effect and has five distinct ways of declining to
     * answer. Each one is rendered as a first-class outcome, because each one is the honest result
     * of a run that could not measure what it set out to measure — and a UI that hid them behind
     * "no data" would be throwing away the most useful thing the harness produces.
     */
    fun batteryCost(
        running: Boolean,
        block: Int,
        total: Int,
        arm: String,
        blocks: List<WarmthBlock>,
        summary: String?
    ): Verdict {
        val numbers = buildList {
            add("blocks" to
                if (blocks.isEmpty()) "none finished yet"
                else "${blocks.size} finished · ${blocks.count { it.arm == "B" }} holding, " +
                    "${blocks.count { it.arm == "A" }} not")
            blocks.firstOrNull()?.let {
                add("battery readout" to when (it.energyEndpoint) {
                    "CHARGE_COUNTER" -> "fine-grained counter — can resolve ten minutes"
                    "CAPACITY" -> "whole percent only — too coarse for ten minutes"
                    else -> "this phone reports no usable battery figure"
                })
            }
            if (blocks.any { it.charging }) {
                add("discarded" to "${blocks.count { it.charging }} block(s) saw the charger, " +
                    "which makes drain unmeasurable")
            }
        }

        if (running) return Verdict(
            headline = "Measuring the battery cost now — block $block of $total.",
            body = "Half the blocks hold the connection awake and half do not, alternating, so " +
                "the difference between them is the cost. It takes about an hour and runs once, " +
                "by itself. Currently in the ${if (arm == "B") "holding" else "not holding"} half.",
            tone = Tone.NEUTRAL,
            measured = false,
            toMeasure = "Leave the phone off Wi-Fi until the run finishes. A stretch of Wi-Fi " +
                "mid-run invalidates it, because the connection is deliberately not held awake there."
        ).copy(numbers = numbers)

        if (blocks.isEmpty()) return absent(
            "The battery cost has not been measured.",
            "Holding a connection awake costs power, and how much is not assumed here — it is " +
                "measured or it is left blank. Nothing has measured it on this phone yet.",
            "It runs itself once, automatically, the first time the phone spends an hour on " +
                "mobile data: six ten-minute blocks, half holding the connection awake, half not."
        ).copy(numbers = numbers)

        val s = summary ?: return absent(
            "The battery cost has not been summarised.",
            "${blocks.size} blocks were recorded but no summary could be produced from them.",
            "Another run. The measurement needs blocks from both halves to compare."
        ).copy(numbers = numbers)

        return when {
            s.contains("need both arms") -> absent(
                "The run did not finish.",
                "Only one half of the comparison was recorded, and one half on its own says " +
                    "nothing about a difference.",
                "A full run: six ten-minute blocks, alternating between holding the connection " +
                    "awake and not."
            ).copy(numbers = numbers)
            s.contains("BLIND") -> absent(
                "No result: the phone stopped reporting during the test.",
                "For most of the run the radio readings never changed, which is what a phone with " +
                    "its screen off looks like. The same snapshot read hundreds of times is not " +
                    "hundreds of measurements, so the run is discarded rather than averaged.",
                "A run with the screen on, or at least in use — and this is a real limit of " +
                    "measuring a phone from an app on the phone."
            ).copy(numbers = numbers)
            s.contains("INVALID") -> absent(
                "No result: the phone was on Wi-Fi for most of the test.",
                "Holding the connection awake is deliberately switched off on Wi-Fi, so most of " +
                    "this run measured nothing being done.",
                "A run that stays on mobile data for the full hour."
            ).copy(numbers = numbers)
            s.contains("NO TREATMENT") -> absent(
                "No result: the half that should have held the connection awake never did.",
                "Something refused the hold for most of those blocks — most likely the low " +
                    "battery gate, or Wi-Fi taking the connection back.",
                "A run on mobile data with the battery above the low-battery floor."
            ).copy(numbers = numbers)
            s.contains("CONTAMINATED CONTROL") -> absent(
                "No result: the comparison half was held awake too.",
                "A real call or a Wi-Fi handover during the half that was supposed to do nothing " +
                    "held the connection awake anyway, so the two halves were not different and " +
                    "there is nothing to compare.",
                "A quieter hour — no calls and no Wi-Fi changes during the run."
            ).copy(numbers = numbers)
            s.contains("COST NOT MEASURABLE") -> absent(
                "This phone cannot measure the cost.",
                "It reports its battery in whole percent, and one percent is worth about three " +
                    "hours of the draw being looked for. Dividing two whole numbers here would " +
                    "produce a figure that looks like a measurement and is not one.",
                "A phone that exposes a fine-grained charge counter, or a much longer run. " +
                    "Neither is something this app can arrange."
            ).copy(numbers = numbers)
            s.contains("COST UNMEASURED") -> absent(
                "The cost could not be read from this run.",
                "Every block in one half saw the charger, and a charging phone has no drain to " +
                    "measure.",
                "A run on battery, unplugged for the full hour."
            ).copy(numbers = numbers)
            s.contains("indistinguishable from noise") -> Verdict(
                headline = "Holding the connection awake cost too little to measure.",
                body = "The two halves drew the same power to within the resolution of this " +
                    "measurement — under 1 mA apart over ten-minute blocks. That is a ceiling on " +
                    "the cost, not a claim that it is zero.",
                tone = Tone.GOOD,
                numbers = numbers
            )
            else -> {
                val ma = COST_MA.find(s)?.groupValues?.get(1)?.toDoubleOrNull()
                Verdict(
                    headline = when {
                        ma == null -> "The run produced a cost figure."
                        ma <= 0.0 -> "Holding the connection awake drew no extra power in this run."
                        else -> "Holding the connection awake cost about %.1f mA.".format(ma)
                    },
                    body = when {
                        ma == null ->
                            "The measurement's own words are below; this panel will not restate " +
                                "a number it cannot read out of them."
                        ma <= 0.0 ->
                            "The half that held the connection awake drew no more than the half " +
                                "that did not. One hour of blocks cannot rule out a small cost — " +
                                "it can only say this run did not see one."
                        else ->
                            "Measured over ten-minute blocks, half holding the connection awake " +
                                "and half not. It is only paid while the connection is actually " +
                                "being held — during a call, just after leaving Wi-Fi, or while " +
                                "something is streaming — and never on Wi-Fi or on a low battery."
                    },
                    tone = if (ma != null && ma > 20.0) Tone.WATCH else Tone.GOOD,
                    numbers = numbers
                )
            }
        }
    }

    // ================================================================= journeys

    /**
     * What a recorded journey says, and — more often — what it declines to say.
     *
     * The handover classes are inferences drawn from correlation, not readings: no public API
     * reports a handover failure, so a "suspected failed" is a cell change that happened to sit
     * within six seconds of a service drop, a lost route or a failed probe. That caveat is not a
     * footnote here. It goes in the body of every verdict this function produces, because a count
     * that looks like a measurement will be read as one.
     */
    fun journeySummary(j: Journey?): Verdict {
        if (j == null) return absent(
            "No journey recorded yet.",
            "A journey is any stretch spent moving faster than walking. It is worth recording " +
                "separately because a connection can fail while moving for a reason that has " +
                "nothing to do with why it fails sitting still: instead of going to sleep and " +
                "struggling to wake, it gets dropped by one mast before the next one has taken " +
                "it on.",
            "Travel by car, bus or train with the phone in your pocket. Anything above walking " +
                "pace for a minute or more starts a recording, and it happens by itself."
        )

        // Coverage first. A journey the radio slept through reports few cell changes and no
        // failures, which is indistinguishable from a journey that went perfectly.
        if (j.coverage < 0.30) return absent(
            "That journey was not measured well enough to read.",
            "Only ${pct(j.coverage)} of the checks during it returned a fresh reading, so the " +
                "counts below describe the gaps as much as the network. A quiet result from a " +
                "sleeping instrument is not a quiet network.",
            "Longer journeys, or the screen on for part of one, give the radio more chances to " +
                "report."
        )

        val total = j.suspectedFailedHandovers + j.apparentlyCleanHandovers
        if (total < 5) return absent(
            "Too few mast changes on that journey to judge.",
            "Only $total change${if (total == 1) "" else "s"} between masts were seen in " +
                "${forWords(j.durationMs)}, which is not enough to tell a bad handover rate from " +
                "an ordinary one.",
            "A longer journey, or a faster one. Crossing masts is what produces the evidence."
        )

        val badFrac = j.suspectedFailedHandovers.toDouble() / total
        val numbers = listOf(
            "moved for" to forWords(j.durationMs),
            "fastest" to (j.maxSpeedMps?.let { "%.0f km/h".format(it * 3.6) } ?: "not measured"),
            "mast changes" to "$total · ${"%.1f".format(j.cellChangesPerMin)}/min",
            "looked clean" to "${j.apparentlyCleanHandovers}",
            "looked like failures" to "${j.suspectedFailedHandovers}",
            "bounced back" to "${j.pingPongReturns}",
            "masts seen" to "${j.distinctCells}",
            "reading coverage" to pct(j.coverage)
        )
        val caveat = "These are not readings. The phone is never told that a handover failed, " +
            "so a change is counted as a suspected failure when it lands within a few seconds " +
            "of the connection dropping, losing its route, or failing a test. Treat them as a " +
            "pattern worth following, not a verdict."

        return when {
            badFrac >= 0.25 -> Verdict(
                headline = "Moving is where this connection breaks.",
                body = "${j.suspectedFailedHandovers} of $total changes between masts happened " +
                    "alongside the connection dropping. That is a different fault from the one " +
                    "that happens sitting still — here the connection is being lost in the " +
                    "handover itself, not failing to wake from sleep. $caveat",
                tone = Tone.BAD, numbers = numbers
            )
            badFrac >= 0.10 -> Verdict(
                headline = "Some mast changes on that journey looked like failures.",
                body = "${j.suspectedFailedHandovers} of $total. Not enough to call the journey " +
                    "bad, enough to be worth watching across a few more. $caveat",
                tone = Tone.WATCH, numbers = numbers
            )
            else -> Verdict(
                headline = "That journey went cleanly.",
                body = "The phone changed masts $total times and the connection came through " +
                    "all but ${j.suspectedFailedHandovers} of them without a visible break. " +
                    "$caveat",
                tone = Tone.GOOD, numbers = numbers
            )
        }
    }

    /** Where the movement classification came from, since a guess and a reading are not equal. */
    fun mobilityConfidence(s: Mobility.State): String = when (s.confidence) {
        Mobility.Confidence.MEASURED -> "from a speed reading"
        Mobility.Confidence.INDICATIVE ->
            "from a change of area — certain movement, unknown speed"
        Mobility.Confidence.WEAK ->
            "guessed from how often the mast changed, which is weak evidence: sixteen changes " +
                "a minute has been recorded while walking"
        Mobility.Confidence.NONE -> "not established"
    }

    /**
     * The pre-emptive warming counter.
     *
     * Worth its own line because it is the only part of the app that acts on a prediction, and a
     * prediction that never fires and a prediction that fires uselessly look identical from the
     * outside unless the count is shown.
     */
    fun handoverPrediction(s: HandoverPredictor.State): Verdict {
        if (!s.watching) return absent(
            "Not watching for the moment Wi-Fi drops.",
            "Leaving Wi-Fi is the hardest moment for a phone: the mobile connection has been " +
                "asleep for hours, its address changes so open connections die, and every app " +
                "asks for data at once.",
            "This starts with collection. If it stays off, the phone would not report its " +
                "Wi-Fi signal."
        )
        if (s.predictions == 0) return Verdict(
            headline = "Watching for the moment Wi-Fi drops.",
            body = "When the Wi-Fi starts to fade, the mobile connection is woken early so it is " +
                "ready before the switch happens. It has not needed to yet — nothing has " +
                "looked like it was about to lose Wi-Fi." +
                (s.wifiRssi?.let { " Wi-Fi signal is currently $it dBm, which is strong." } ?: ""),
            tone = Tone.GOOD
        )
        return Verdict(
            headline = "Woke the mobile connection early ${s.predictions} time" +
                (if (s.predictions == 1) "" else "s") + ".",
            body = "Each time, the Wi-Fi looked like it was about to go and the mobile " +
                "connection was woken before the switch, so it was ready rather than starting " +
                "cold. This removes one of the three things that go wrong at that moment; it " +
                "cannot do anything about the address change that closes open connections.",
            tone = Tone.GOOD,
            numbers = listOfNotNull(
                "woken early" to "${s.predictions}",
                s.lastReason?.let { "last trigger" to it },
                s.wifiRssi?.let { "Wi-Fi now" to "$it dBm" }
            )
        )
    }
}
