package com.signalscope.collect

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The remediation catalogue, its two gates, and the queue.
 *
 * Two gates, and a blocked action must say which one is holding it:
 *   - **privilege** — the action needs shell UID via Shizuku, which this build cannot reach.
 *   - **traffic**   — the action costs more seconds than the running traffic can hide.
 *
 * Nothing here executes anything. Tier-2 execution is deliberately unwired: see [ActionRunner].
 */

enum class Tier(val label: String) { TIER0("TIER 0"), TIER2("TIER 2 · SHIZUKU") }

data class Remediation(
    val id: String,
    val name: String,
    val blurb: String,
    /** what it would run, shown verbatim so the user can see exactly what we would do */
    val cmd: String?,
    val tier: Tier,
    /** seconds of connectivity the action costs; the traffic gate compares slack against this */
    val costSeconds: Int,
    val reverts: String
)

object ActionCatalog {
    val reAnchor = Remediation(
        id = "reanchor",
        name = "Re-anchor data",
        blurb = "The scriptable airplane-mode toggle. Tears the PDN down and back up, which clears " +
                "causes 2, 4 and 6. Costs about 2 s of connectivity and resets every open socket.",
        cmd = "svc data disable ; sleep 2 ; svc data enable",
        tier = Tier.TIER2,
        costSeconds = 3,
        reverts = "Self-reverting: data comes back in the same call."
    )

    val pinLte = Remediation(
        id = "pinlte",
        name = "Pin to LTE",
        blurb = "Drops NR from the preferred-network bitmask so the modem stops re-selecting an " +
                "unstable NSA anchor. Costs a re-registration.",
        cmd = "setPreferredNetworkTypeBitmask(LTE|…)  ·  hidden API, needs MODIFY_PHONE_STATE",
        tier = Tier.TIER2,
        costSeconds = 6,
        reverts = "Reverts on a 2-hour timer, and on reboot. Never left pinned silently."
    )

    val all = listOf(reAnchor, pinLte)
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/* ------------------------------------------------------------------ privilege */

data class ShizukuStatus(
    val clientLinked: Boolean,
    val managerVisible: Boolean,
    val connected: Boolean,
    val detail: String
) {
    val ready: Boolean get() = clientLinked && connected
}

object ActionPrivilege {
    /**
     * Honest detection of Shizuku's absence.
     *
     * The Shizuku client (`dev.rikka.shizuku:api`) is not a dependency of this build, so there is
     * no binder to ask and `connected` can only ever be false. We can additionally look for the
     * manager app, but package visibility on Android 11+ means a negative there means
     * "absent or invisible", not "absent" — and we say so rather than guessing.
     */
    fun probe(ctx: Context): ShizukuStatus {
        val visible = runCatching {
            ctx.packageManager.getPackageInfo(MANAGER_PKG, 0); true
        }.getOrDefault(false)
        // The client library IS linked now, and a <queries> entry makes the manager visible, so
        // this reports the live state from ShizukuBridge rather than the hardcoded "absent" it
        // returned when neither was true.
        val st = ShizukuBridge.state.value
        return ShizukuStatus(
            clientLinked = true,
            managerVisible = visible,
            connected = st == ShizukuState.READY,
            detail = st.detail
        )
    }

    const val MANAGER_PKG = "moe.shizuku.privileged.api"
}

/* ------------------------------------------------------------------ gating */

data class Gate(
    val privilege: String? = null,
    val traffic: String? = null,
    val rate: String? = null
) {
    val blocked: Boolean get() = privilege != null || traffic != null || rate != null
    val byBoth: Boolean get() = privilege != null && traffic != null

    val tag: String get() = when {
        !blocked -> "READY"
        byBoth -> "BLOCKED · BOTH"
        privilege != null -> "BLOCKED · PRIVILEGE"
        traffic != null -> "BLOCKED · TRAFFIC"
        else -> "RATE-LIMITED"
    }
}

object ActionPolicy {
    const val MAX_PER_HOUR = 3

    fun gate(r: Remediation, t: TrafficState, sh: ShizukuStatus, firedThisHour: Int): Gate {
        val privilege = if (r.tier == Tier.TIER2 && !sh.ready)
            "Shizuku is not connected — ${sh.detail}" else null

        val traffic = when {
            t.slackSeconds >= r.costSeconds -> null
            t.klass == TrafficClass.REALTIME_CALL ->
                "you are on a call. This one is absolute: a re-anchor mid-call is the cure causing the disease."
            t.klass == TrafficClass.CONFERENCING ->
                "a real-time session is open — it holds ${t.klass.slackLabel} of buffer, the action costs ~${r.costSeconds} s."
            t.klass == TrafficClass.UNKNOWN ->
                "what is running cannot be classified, so real-time is assumed. Failing safe."
            else ->
                "${t.klass.label.lowercase()} holds ${t.klass.slackLabel}, the action costs ~${r.costSeconds} s."
        }

        val rate = if (firedThisHour >= MAX_PER_HOUR)
            "rate limit reached — $firedThisHour of $MAX_PER_HOUR this hour" else null

        return Gate(privilege, traffic, rate)
    }

    /** Is the current window wide enough to hide this action? Traffic only, ignoring privilege. */
    fun windowOpenFor(r: Remediation, t: TrafficState) = t.slackSeconds >= r.costSeconds
}

/* ------------------------------------------------------------------ queue */

data class QueuedAction(
    val seq: Long,
    val remediationId: String,
    val reason: String,
    val queuedAtElapsed: Long
) {
    fun waitingSeconds() = (SystemClock.elapsedRealtime() - queuedAtElapsed) / 1000
}

data class ActionLogEntry(val atElapsed: Long, val line: String, val outcome: String)

/**
 * A scheduler, not a trigger (docs/traffic-classes.md). Remediations are queued when the fault is
 * detected and executed in the next window wide enough to hide them — a route broken for 40 s
 * during a call is not re-anchored at second 41, it is re-anchored the moment the call ends.
 */
object ActionQueue {
    val pending = MutableStateFlow<List<QueuedAction>>(emptyList())
    val log = MutableStateFlow<List<ActionLogEntry>>(emptyList())

    private var seq = 0L
    private val fired = ArrayDeque<Long>()

    fun enqueue(r: Remediation, reason: String) {
        if (pending.value.any { it.remediationId == r.id }) return
        seq++
        pending.value = pending.value + QueuedAction(seq, r.id, reason, SystemClock.elapsedRealtime())
        note("queued ${r.name} — $reason", "PENDING")
    }

    fun cancel(seqId: Long) {
        val item = pending.value.firstOrNull { it.seq == seqId } ?: return
        pending.value = pending.value.filterNot { it.seq == seqId }
        note("cancelled ${ActionCatalog.byId(item.remediationId)?.name ?: item.remediationId}", "CANCELLED")
    }

    fun note(line: String, outcome: String) {
        log.value = (listOf(ActionLogEntry(SystemClock.elapsedRealtime(), line, outcome)) + log.value).take(40)
    }

    fun firedThisHour(): Int {
        val cut = SystemClock.elapsedRealtime() - 3_600_000
        while (fired.isNotEmpty() && fired.first() < cut) fired.removeFirst()
        return fired.size
    }
}

/* ------------------------------------------------------------------ execution */

sealed class RunResult(val text: String) {
    class Refused(text: String) : RunResult(text)
    class Unwired(text: String) : RunResult(text)
}

object ActionRunner {
    /**
     * Execution is deliberately not implemented. The gates are evaluated for real — this is the
     * code path a wired build would take — and then it stops, because the reference device is a
     * daily driver and nothing in this pass may change its state.
     */
    fun run(r: Remediation, t: TrafficState, sh: ShizukuStatus): RunResult {
        val g = ActionPolicy.gate(r, t, sh, ActionQueue.firedThisHour())
        if (g.blocked) {
            val why = listOfNotNull(g.traffic, g.privilege, g.rate).first()
            ActionQueue.note("refused ${r.name} — $why", "REFUSED")
            return RunResult.Refused(why)
        }
        ActionQueue.note("${r.name} would run here — execution unwired", "UNWIRED")
        return RunResult.Unwired(
            "Gates passed. Execution is unwired in this build: no privileged command is ever sent."
        )
    }
}
