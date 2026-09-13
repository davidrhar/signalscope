package com.signalscope.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Mono = FontFamily.Monospace
private val expScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Phase A: measure the landing distribution of a forced cycle.
 *
 * This is an experiment, not a feature. It answers one question — does cycling the radio ever put
 * us on a different cell, or does it deterministically return to the same one? A degenerate result
 * retires the whole forced-reselection idea for this location, which is worth knowing.
 */
@Composable
fun PhaseAPanel() {
    val ctx = LocalContext.current
    val shizuku by ShizukuBridge.state.collectAsStateWithLifecycle()
    val p by PhaseA.progress.collectAsStateWithLifecycle()
    val traffic by ActionTraffic.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { ShizukuBridge.refresh(ctx) }

    SecHead("Experiment · Phase A", "landing distribution")
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Forced reselection", color = T.Text, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Tag(shizuku.label.uppercase(),
                if (shizuku == ShizukuState.READY) T.Good else T.Warn)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Strips LTE and NR from the allowed network types for 4 s, restores them, then records " +
            "which cell the modem lands on. Initial cell selection after a detach is a different " +
            "procedure from priority-based reselection, so the landing may differ — this measures " +
            "whether it actually does.",
            color = T.Dim, fontSize = 11.sp
        )

        Spacer(Modifier.height(10.dp))
        ERow("shizuku", shizuku.detail, if (shizuku == ShizukuState.READY) T.Dim else T.Warn)
        ERow("traffic", "${traffic.klass.label} · slack ${traffic.klass.slackLabel}",
            if (traffic.klass.blocksDisruption) T.Bad else T.Good)
        if (traffic.klass.blocksDisruption)
            ERow("", "blocked: a 4 s detach is the failure this exists to prevent", T.Bad)

        p.error?.let { Spacer(Modifier.height(6.dp)); ERow("note", it, T.Warn) }

        Spacer(Modifier.height(10.dp))
        when {
            shizuku != ShizukuState.READY -> {
                Text(
                    when (shizuku) {
                        ShizukuState.NOT_INSTALLED ->
                            "Install Shizuku, then start it over wireless debugging."
                        ShizukuState.NOT_RUNNING ->
                            "Shizuku is installed but not started. Start it, then return here."
                        else -> "Grant SignalScope access in the prompt."
                    },
                    color = T.Faint, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                if (shizuku == ShizukuState.PERMISSION_NEEDED || shizuku == ShizukuState.DENIED) {
                    Btn("Request access") { ShizukuBridge.requestPermission() }
                } else {
                    Btn("Re-check") { ShizukuBridge.refresh(ctx) }
                }
            }
            p.running -> {
                Text("trial ${p.trial} of ${p.total} · ${p.phase}",
                    color = T.Brand, fontSize = 12.sp, fontFamily = Mono)
                Spacer(Modifier.height(4.dp))
                Text("Mobile data will drop briefly on each trial. Wi-Fi is unaffected.",
                    color = T.Faint, fontSize = 10.sp)
            }
            else -> Btn(if (p.trials.isEmpty()) "Run 10 trials" else "Run again") {
                PhaseA.run(ctx, expScope, 10)
            }
        }

        if (p.trials.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(PhaseA.summary(p.trials), color = T.Text, fontSize = 11.sp, fontFamily = Mono)
            Spacer(Modifier.height(8.dp))
            p.trials.forEach { t ->
                val enb = t.landedCi?.let { it shr 8 }
                val sect = t.landedCi?.let { (it and 0xFF).toInt() }
                ERow(
                    "#${t.index}",
                    if (t.landedCi == null) "no service · ${t.note}"
                    else "site $enb·s$sect B${t.landedBand ?: "?"} · " +
                         "back in ${t.timeToServiceMs / 1000}s · SINR ${t.sinrAfterP50 ?: "—"} · " +
                         "${t.cellChangesIn60s} change(s)/60s",
                    if (t.landedCi == null) T.Bad else T.Dim
                )
            }
        }
    }
}

@Composable
private fun Btn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = T.Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
