package com.signalscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.*
import kotlinx.coroutines.delay

private val Mono = FontFamily.Monospace

/**
 * Actions — remediation, honestly gated.
 *
 * Two gates stand between a fault and a fix, and the screen's job is to make both visible:
 * **privilege** (Shizuku, which this build does not link) and **traffic** (what is running right
 * now, which decides whether the action would be invisible or catastrophic). A blocked action
 * always names which of the two is blocking it.
 *
 * Nothing privileged executes from this screen. The gates are evaluated for real; execution stops
 * behind the Shizuku check. The Tier-0 deep links are wired properly, because opening a Settings
 * page costs no connectivity.
 */
@Composable
fun ActionsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val traffic by ActionTraffic.state.collectAsStateWithLifecycle()
    val pending by ActionQueue.pending.collectAsStateWithLifecycle()
    val log by ActionQueue.log.collectAsStateWithLifecycle()
    val selfTest by ActionSelfTest.running.collectAsStateWithLifecycle()
    val selfTestLabel by ActionSelfTest.runningLabel.collectAsStateWithLifecycle()

    val shizuku = remember { ActionPrivilege.probe(ctx) }
    val links = remember { ActionDeepLinks.resolve(ctx) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Callbacks catch the transitions that matter instantly; the 1 s poll is the backstop.
    DisposableEffect(Unit) {
        ActionTraffic.start(ctx)
        onDispose { ActionTraffic.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); ActionTraffic.sample() }
    }

    val fired = ActionQueue.firedThisHour()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        // No title here. The nav shell already renders this screen's name and subtitle, the same
        // way it does for every other tab, and drawing a second pair printed "Actions" twice.
        // The improved subtitle moved to Nav.kt where the first one lives.

        // The diagnosis leads the screen, because an action is only as good as the reason for it —
        // and because these panels are the only place the app says, in plain words, what it found.
        // Everything below them is a lever, and a lever with no finding behind it is a guess.
        DiagnosticPanels()

        SecHead("Running now", "decides what may run")
        TrafficHero(traffic)

        Spacer(Modifier.height(8.dp))
        Btn(
            if (selfTest > 0) "Self-test running — ${selfTest}s · $selfTestLabel"
            else "Self-test · 10 s of silent MEDIA/MUSIC → safe window",
            ghost = true
        ) { ActionSelfTest.start(label = "MEDIA/MUSIC") }
        Spacer(Modifier.height(6.dp))
        Btn("Self-test · 10 s of UNKNOWN attributes → must fail safe", ghost = true) {
            ActionSelfTest.start(
                usage = android.media.AudioAttributes.USAGE_UNKNOWN,
                content = android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN,
                label = "UNKNOWN/UNKNOWN"
            )
        }
        Text(
            "Proves the classifier is live rather than decorative: PCM zeros — digital silence, " +
                    "inaudible at any volume — with no audio-focus request, so nothing is heard " +
                    "and nothing the user is playing is paused. The class above must move to " +
                    "Music while it runs.",
            color = T.Faint, fontSize = 10.sp, lineHeight = 14.5.sp,
            modifier = Modifier.padding(top = 5.dp)
        )

        SecHead("Queue", if (pending.isEmpty()) "empty" else "${pending.size} waiting")
        QueueCard(pending, traffic)

        SecHead("Privilege", "tier 2")
        PrivilegeBanner(shizuku)

        SecHead("Disruptive remediation", "$fired/${ActionPolicy.MAX_PER_HOUR} used this hour")
        ActionCatalog.all.forEach { r ->
            val gate = ActionPolicy.gate(r, traffic, shizuku, fired)
            ActionCard(
                r = r,
                gate = gate,
                windowOpen = ActionPolicy.windowOpenFor(r, traffic),
                queued = pending.any { it.remediationId == r.id },
                onQueue = {
                    ActionQueue.enqueue(r, "queued by hand · ${traffic.klass.label.lowercase()} now")
                },
                onRun = { notice = ActionRunner.run(r, traffic, shizuku).text }
            )
        }

        SecHead("Settings deep links", "tier 0 · these work")
        Text(
            "We cannot set any of these. We can put you one tap from the right page with the " +
                    "evidence in hand. Opening a page costs no connectivity, so the traffic gate " +
                    "does not apply.",
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 7.dp)
        )
        links.forEach { l ->
            LinkCard(l) { notice = ActionDeepLinks.open(ctx, l) ?: "Opened ${l.target}." }
        }

        SecHead("Action log", "everything is logged")
        LogCard(log)

        Spacer(Modifier.height(12.dp))
        CannotPanel()

        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Card {
                Text(notice!!, color = T.Dim, fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                Btn("Dismiss", ghost = true) { notice = null }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

/* ----------------------------------------------------------------- traffic hero */

private fun classColor(k: TrafficClass) = when (k) {
    TrafficClass.REALTIME_CALL, TrafficClass.CONFERENCING -> T.Bad
    TrafficClass.UNKNOWN -> T.Warn
    TrafficClass.VIDEO -> T.Warn
    TrafficClass.MUSIC -> T.Good
    TrafficClass.IDLE -> T.Brand
}

@Composable
private fun TrafficHero(t: TrafficState) {
    val c = classColor(t.klass)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(0f to c.copy(alpha = 0.16f), 0.7f to Color.Transparent))
            .background(T.Surface.copy(alpha = 0.55f))
            .border(1.dp, c.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("RUNNING NOW", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(t.klass.label, color = T.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.7).sp)
            }
            Tag(if (t.klass.blocksDisruption) "NO WINDOW" else "WINDOW OPEN", c)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(
                "SLACK", if (t.klass.slackSeconds == Int.MAX_VALUE) "∞" else t.klass.slackLabel.trim(),
                "budget", slackFrac(t.klass), c, Modifier.weight(1.3f)
            )
            Metric("HELD", t.heldFor().toString(), "s", 0f, T.Dim, Modifier.weight(1f))
            Metric("SAMPLES", t.samples.toString(), "", 0f, T.Dim, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(c.copy(alpha = 0.10f))
                .border(1.dp, c.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text(t.klass.consequence, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp)
            if (t.failedSafe) {
                Spacer(Modifier.height(3.dp))
                Text("failed safe — ambiguity resolves toward assuming real-time",
                    color = T.Faint, fontSize = 10.sp, fontFamily = Mono)
            }
        }

        Spacer(Modifier.height(9.dp))
        ERow("basis", t.basis)
        ERow("audio mode", ActionTraffic.modeName(t.audioMode))
        ERow("call state",
            if (t.callStateReadable) ActionTraffic.callStateName(t.callState) else "UNREADABLE",
            if (!t.callStateReadable) T.Bad
            else if (t.callState != 0) T.Bad else T.Dim)
        ERow("isMusicActive", if (t.musicActive) "true" else "false")
        if (t.streams.isEmpty()) ERow("playback", "no active configuration", T.Faint)
        else t.streams.forEachIndexed { i, s ->
            ERow(if (i == 0) "playback" else "", "${s.usage} · ${s.content}")
        }
    }
}

private fun slackFrac(k: TrafficClass) = when (k) {
    TrafficClass.REALTIME_CALL, TrafficClass.UNKNOWN -> 0.04f
    TrafficClass.CONFERENCING -> 0.08f
    TrafficClass.VIDEO -> 0.4f
    TrafficClass.MUSIC -> 0.75f
    TrafficClass.IDLE -> 1f
}

/* ----------------------------------------------------------------- queue */

@Composable
private fun QueueCard(pending: List<QueuedAction>, t: TrafficState) {
    Card {
        Text(
            "Remediations are queued when the fault is found and executed in the next window wide " +
                    "enough to hide them — not fired the moment something breaks.",
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp
        )
        if (pending.isEmpty()) {
            Spacer(Modifier.height(9.dp))
            Text("Nothing pending.", color = T.Dim, fontSize = 12.sp)
            return@Card
        }
        pending.forEach { q ->
            val r = ActionCatalog.byId(q.remediationId) ?: return@forEach
            val open = ActionPolicy.windowOpenFor(r, t)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = T.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(q.reason, color = T.Faint, fontSize = 10.sp, fontFamily = Mono)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (open) "window is open — would execute now (execution unwired)"
                        else "waiting for a window ≥ ${r.costSeconds} s · ${t.klass.label.lowercase()} holds ${t.klass.slackLabel}",
                        color = if (open) T.Good else T.Warn, fontSize = 10.5.sp, lineHeight = 15.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${q.waitingSeconds()}s", color = T.Dim, fontSize = 12.sp, fontFamily = Mono)
                    Spacer(Modifier.height(5.dp))
                    Text("cancel", color = T.Faint, fontSize = 10.5.sp,
                        modifier = Modifier.clickableNoRipple { ActionQueue.cancel(q.seq) })
                }
            }
        }
    }
}

/* ----------------------------------------------------------------- privilege */

@Composable
private fun PrivilegeBanner(s: ShizukuStatus) {
    val c = if (s.ready) T.Good else T.Warn
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(0f to c.copy(alpha = 0.10f), 0.62f to Color.Transparent))
            .background(T.Surface)
            .border(1.dp, c.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(999.dp)).background(c))
            Spacer(Modifier.width(11.dp))
            Text(if (s.ready) "Shizuku connected" else "Shizuku not connected",
                color = T.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Tag(if (s.ready) "UID 2000" else "TIER 0 ONLY", c)
        }
        Spacer(Modifier.height(7.dp))
        ERow("client lib", if (s.clientLinked) "dev.rikka.shizuku:api 13.1.5 linked" else "not linked", T.Faint)
        ERow("manager app", if (s.managerVisible) "installed" else "not visible", T.Faint)
        ERow("detail", s.detail, T.Faint)
        Spacer(Modifier.height(6.dp))
        Text(
            "Wiring it needs three things this pass deliberately skips: the two Shizuku " +
                    "artifacts as dependencies, a provider entry in the manifest, and a " +
                    "user-granted binder permission. Until then every Tier-2 lever below stays " +
                    "unexecutable — not merely disabled.",
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp
        )
    }
}

/* ----------------------------------------------------------------- action card */

@Composable
private fun ActionCard(
    r: Remediation,
    gate: Gate,
    windowOpen: Boolean,
    queued: Boolean,
    onQueue: () -> Unit,
    onRun: () -> Unit
) {
    val c = if (gate.blocked) (if (gate.traffic != null) T.Bad else T.Warn) else T.Good
    Column(
        Modifier.fillMaxWidth().padding(bottom = 7.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (gate.blocked) Color(0x08FFFFFF) else T.Surface)
            .border(1.dp, if (gate.blocked) T.Line else T.Good.copy(alpha = 0.30f),
                RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, color = T.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(9.dp))
            Tag(gate.tag, c)
        }
        Text(r.blurb, color = T.Faint, fontSize = 11.sp, lineHeight = 15.5.sp,
            modifier = Modifier.padding(top = 4.dp))

        if (r.cmd != null) {
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF080B10))
                    .border(1.dp, T.LineSoft, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("$ ${r.cmd}", color = T.Dim, fontSize = 10.sp, fontFamily = Mono, maxLines = 2)
            }
        }

        Spacer(Modifier.height(8.dp))
        gate.traffic?.let { Blocker("TRAFFIC", it, T.Bad) }
        gate.privilege?.let { Blocker("PRIVILEGE", it, T.Warn) }
        gate.rate?.let { Blocker("RATE", it, T.Warn) }

        Spacer(Modifier.height(3.dp))
        ERow("cost", "~${r.costSeconds} s of connectivity", T.Faint)
        ERow("reverts", r.reverts, T.Faint)

        Spacer(Modifier.height(9.dp))
        if (gate.traffic != null) {
            // Visibly impossible, not merely discouraged: there is no button to press.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(T.Bad.copy(alpha = 0.09f))
                    .border(1.dp, T.Bad.copy(alpha = 0.26f), RoundedCornerShape(11.dp))
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Run is unavailable while this is running", color = T.Bad, fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(7.dp))
        } else {
            Btn(if (gate.blocked) "Run now — blocked" else "Run now", ghost = gate.blocked, onClick = onRun)
            Spacer(Modifier.height(7.dp))
        }
        Btn(
            when {
                queued -> "Already queued"
                windowOpen -> "Queue it — window is open now"
                else -> "Queue for the next safe window"
            },
            ghost = true,
            onClick = { if (!queued) onQueue() }
        )
    }
}

@Composable
private fun Blocker(kind: String, why: String, c: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(kind, color = c, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp, modifier = Modifier.width(72.dp).padding(top = 1.dp, end = 8.dp))
        Text(why, color = T.Dim, fontSize = 10.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
}

/* ----------------------------------------------------------------- deep links */

@Composable
private fun LinkCard(l: ResolvedLink, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 7.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.link.title, color = T.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(9.dp))
            Tag(if (l.available) "TIER 0" else "NO TARGET", if (l.available) T.Brand else T.Faint)
        }
        Text(l.link.blurb, color = T.Faint, fontSize = 11.sp, lineHeight = 15.5.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(7.dp))
        ERow("addresses", l.link.cause, T.Faint)
        ERow("resolves to", l.target ?: "nothing on this build", if (l.available) T.Dim else T.Bad)
        l.link.caveat?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = T.Warn, fontSize = 10.sp, lineHeight = 14.5.sp)
        }
        if (l.available) {
            Spacer(Modifier.height(9.dp))
            Btn("Open", ghost = true, onClick = onOpen)
        }
    }
}

/* ----------------------------------------------------------------- log */

@Composable
private fun LogCard(log: List<ActionLogEntry>) {
    Card {
        if (log.isEmpty()) {
            Text("Nothing has been attempted yet. Every attempt, refusal and deep-link open " +
                    "lands here.", color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp)
            return@Card
        }
        log.take(8).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), verticalAlignment = Alignment.Top) {
                Text(e.outcome, color = outcomeColor(e.outcome), fontSize = 9.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(72.dp).padding(top = 1.dp, end = 8.dp))
                Text(e.line, color = T.Dim, fontSize = 10.sp, fontFamily = Mono,
                    lineHeight = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun outcomeColor(o: String) = when (o) {
    "REFUSED", "FAILED" -> T.Bad
    "PENDING", "UNWIRED" -> T.Warn
    "TIER 0" -> T.Brand
    else -> T.Faint
}

/* ----------------------------------------------------------------- cannot panel */

@Composable
private fun CannotPanel() {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(T.Bad.copy(alpha = 0.035f))
            .border(1.dp, T.Bad.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text("✕  What SignalScope cannot do", color = T.Bad, fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold)
        Text("Hard Android limits, not missing features. Please don't file them as bugs.",
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
        CannotRow("No ICMP ping", "isReachable() uses TCP/7 and lies")
        CannotRow("No traceroute", "raw sockets need root")
        CannotRow("No modem diagnostics", "/dev/diag, AT, RIL are vendor-only")
        CannotRow("No band or cell locking", "needs a UICC-signed app")
        CannotRow("No forced handover", "re-anchor is the strongest lever")
        CannotRow("No control of other apps' networking", "we improve the bearer, never the app")
    }
}

@Composable
private fun CannotRow(what: String, why: String) {
    Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.Bad.copy(alpha = 0.11f)))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            Text("✕", color = T.Bad.copy(alpha = 0.65f), fontSize = 9.sp, fontFamily = Mono,
                modifier = Modifier.width(16.dp).padding(top = 2.dp))
            Text(buildString { append(what); append("  ·  "); append(why) },
                color = T.Dim, fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.5.sp,
                modifier = Modifier.weight(1f))
        }
    }
}

/* ----------------------------------------------------------------- button */

@Composable
private fun Btn(text: String, ghost: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (ghost) T.Surface2 else T.Brand)
            .border(1.dp, if (ghost) T.Line else Color.Transparent, RoundedCornerShape(11.dp))
            .clickableNoRipple(onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (ghost) T.Text else Color(0xFF05192E), fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
    }
}
