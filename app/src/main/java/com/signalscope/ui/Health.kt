package com.signalscope.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.CollectorService
import com.signalscope.collect.LiveState
import com.signalscope.collect.MapLocationCollector
import com.signalscope.collect.MobileData

/**
 * One place that answers "is this app actually working, and if not, why".
 *
 * ## Why this exists
 *
 * It used to be answered in four places with four different behaviours, and the week that
 * produced this strip is the argument for it. A phone with no SIM readable invented one and
 * displayed "default - SIM 0". A map with location switched off said "waiting for a position fix,
 * indoors this can take a minute" for twenty-eight minutes. The "My location" control did not
 * render at all, so its owner concluded that some phones have that button and theirs does not.
 * Only the Diagnosis tab said anything true, and nobody opens Diagnosis first.
 *
 * Each of those was a separate small decision that made sense on its own screen. Together they
 * meant the app knew what was wrong and showed something reassuring instead.
 *
 * ## The rule, which this file is the enforcement of
 *
 * Never invent a value. Never reassure past the point it is true. Never hide a control instead of
 * explaining it. Name the reason, and offer the action that fixes it.
 *
 * ## Why it is a strip and not a screen
 *
 * A screen has to be visited. Everything here is the kind of fault where the person does not know
 * to go looking -- they are on Live wondering why the numbers are blank. So it sits under the
 * header on every tab, and is silent when there is nothing to say.
 */
object AppHealth {

    data class Issue(
        val title: String,
        val detail: String,
        /** Null when there is nothing the person can do from here. */
        val actionLabel: String? = null,
        val action: ((Context) -> Unit)? = null,
        /** Severe issues stop the app doing its job. The rest are worth knowing, not alarming. */
        val severe: Boolean = true
    )

    private fun appSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Mobile network settings, where the data switch lives. Falls back to the wireless panel on a
     * phone whose manufacturer does not honour the first action -- a strip that names the fix and
     * then does nothing when tapped is worse than one that says nothing.
     */
    private fun mobileDataSettings(ctx: Context) {
        val tries = listOf(
            Settings.ACTION_DATA_ROAMING_SETTINGS,
            Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS
        )
        for (a in tries) {
            val ok = runCatching {
                ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    private fun locationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Worst first. The order is deliberate: collection being off makes every other complaint
     * irrelevant, and a phone that cannot read its own SIM cannot measure anything at all.
     */
    fun issues(
        ctx: Context,
        running: Boolean,
        sims: Map<Int, com.signalscope.collect.SimState>,
        degraded: String?
    ): List<Issue> = buildList {
        // Only when the person actually stopped it. LiveState.running is false for the first
        // second of every launch, before the service reports in -- announcing "not collecting"
        // in that window would be the strip telling its own kind of lie on every cold start.
        if (!running && !CollectorService.userEnabled(ctx)) add(
            Issue(
                "Not collecting",
                "Nothing is being measured. Tap COLLECTING in the header to start again.",
                "Start now", { CollectorService.start(it) }
            )
        )

        // Slot -1 is the placeholder registration: a subscription the app invented because the
        // real list was unreadable. It is the shape of a missing phone permission.
        // sims.isEmpty() is also true for the first moment of a launch, so the complaint is
        // limited to the placeholder case -- a subscription the app invented, which only ever
        // appears once enumeration has actually run and found nothing.
        val noSim = sims.isNotEmpty() && sims.values.all { it.slot < 0 }
        if (noSim) add(
            Issue(
                "No SIM readable",
                "Without phone permission the app cannot see which network is serving you, " +
                    "which mast, or which band. Signal strength still works; nothing else does.",
                "Open app permissions", ::appSettings
            )
        )

        // Below the SIM check and above location, because it is the one fault that silently
        // empties the map AND the connection verdict at once, and the app used to report both as
        // working. Not severe: the phone is behaving exactly as its owner set it, the radio is
        // still being read, and colouring a deliberate setting red would train the strip to be
        // ignored. It still has to be said, because nothing else says it.
        val bearer = MobileData.state(ctx)
        if (bearer == MobileData.State.SWITCHED_OFF) add(
            Issue(
                "Mobile data is off",
                "There is no mobile connection to test, so the coverage map stays grey and the " +
                    "connection verdict has nothing to report. Signal strength, the mast record " +
                    "and the zone alerts carry on — they read the radio, not data.",
                "Open mobile network settings", ::mobileDataSettings, severe = false
            )
        )

        MapLocationCollector.blockedReason(ctx)?.let { why ->
            val isSystem = why.startsWith("location is switched off")
            add(
                Issue(
                    why.replaceFirstChar { it.uppercase() },
                    "The coverage map cannot be built without it. Everything else — the live " +
                        "readings, the mast record and the zone alerts — still works.",
                    if (isSystem) "Open location settings" else "Open app permissions",
                    if (isSystem) ::locationSettings else ::appSettings
                )
            )
        }

        if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) add(
            Issue(
                "Notifications are off",
                "The alerts for entering and leaving a poor data area have nowhere to appear. " +
                    "The verdict is still on the Live screen.",
                "Open app permissions", ::appSettings, severe = false
            )
        )

        // Whatever the collector itself has decided is not working, in its own words.
        degraded?.let { add(Issue("Collecting with something missing", it, severe = false)) }
    }
}

/**
 * The worst thing currently wrong, and a count of the rest.
 *
 * One at a time on purpose. A list of four faults is a wall that gets scrolled past; the one that
 * matters most, with the button that fixes it, is a thing somebody acts on.
 */
@Composable
fun HealthStrip() {
    val ctx = LocalContext.current
    val running by LiveState.running.collectAsStateWithLifecycle()
    val sims by LiveState.sims.collectAsStateWithLifecycle()
    val degraded by LiveState.degraded.collectAsStateWithLifecycle()

    // Permission and the system location switch can change while the app is in the background,
    // so they are re-read on every recomposition of this strip rather than cached.
    val issues = AppHealth.issues(ctx, running, sims, degraded)
    val top = issues.firstOrNull() ?: return
    val tone = if (top.severe) T.Bad else T.Warn

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(11.dp)
    ) {
        Text(top.title, color = tone, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(top.detail, color = T.Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
        if (top.actionLabel != null && top.action != null) {
            Spacer(Modifier.height(8.dp))
            Btn(top.actionLabel, ghost = true) { top.action.invoke(ctx) }
        }
        if (issues.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "and ${issues.size - 1} more — see Diagnosis",
                color = T.Faint, fontSize = 10.5.sp
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}
