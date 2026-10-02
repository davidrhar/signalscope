package com.signalscope.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.*
import com.signalscope.store.MapDataPolicy
import com.signalscope.store.ShareConsent
import kotlinx.coroutines.delay

private val Mono = FontFamily.Monospace

private const val PRIVACY_URL = "https://signalscope-map.fly.dev/privacy"

/**
 * Settings — how the app behaves, and the data it holds.
 *
 * Reached from the gear in the header rather than a tab: it is visited rarely, and a tab slot is
 * worth more to something read every day. Everything about the connection itself is on
 * [DiagnosisScreen]; everything here is about the app.
 *
 * Sharing is the one setting that is not *set* here. Its consent wording belongs beside the map it
 * feeds and must exist in exactly one place, so this screen shows its state and hands over to the
 * Map tab rather than carrying a second copy of the question.
 */
@Composable
fun SettingsScreen(onOpenSharing: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val traffic by ActionTraffic.state.collectAsStateWithLifecycle()
    val log by ActionQueue.log.collectAsStateWithLifecycle()
    val selfTest by ActionSelfTest.running.collectAsStateWithLifecycle()
    val selfTestLabel by ActionSelfTest.runningLabel.collectAsStateWithLifecycle()
    val shizuku = remember { ActionPrivilege.probe(ctx) }
    var mapData by remember { mutableStateOf(MapDataPolicy.get(ctx)) }
    val sharing = remember { ShareConsent.enabled(ctx) }
    val lastUpload = remember { ShareConsent.lastUpload(ctx) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
            ?.let { "${it.versionName} (${it.longVersionCode})" } ?: "unknown"
    }

    // The self-test is only evidence if its effect on the classifier can be watched, so the
    // classifier runs while this screen is open, as it does on Diagnosis.
    DisposableEffect(Unit) {
        ActionTraffic.start(ctx)
        onDispose { ActionTraffic.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); ActionTraffic.sample() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        // ------------------------------------------------ map downloads
        SecHead("Map downloads", "basemap regions")
        Card {
            Text(
                "New maps are fetched automatically for the country you are in and the places " +
                    "you spend time. On Wi-Fi only, they arrive at the next Wi-Fi — usually the " +
                    "same evening.",
                color = T.Dim, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(9.dp))
            Choice(
                "Wi-Fi only", "Nothing is fetched over mobile data.",
                mapData == MapDataPolicy.Allow.WIFI_ONLY
            ) { mapData = MapDataPolicy.Allow.WIFI_ONLY; MapDataPolicy.set(ctx, mapData) }
            Choice(
                "Wi-Fi and mobile data",
                "Home network only. A country is a few MB; local detail is tens of MB.",
                mapData == MapDataPolicy.Allow.MOBILE_HOME
            ) { mapData = MapDataPolicy.Allow.MOBILE_HOME; MapDataPolicy.set(ctx, mapData) }
            Choice(
                "Also when roaming",
                "Roaming data can be expensive. Check your plan before choosing this.",
                mapData == MapDataPolicy.Allow.MOBILE_ROAMING,
                warn = true
            ) { mapData = MapDataPolicy.Allow.MOBILE_ROAMING; MapDataPolicy.set(ctx, mapData) }
            Spacer(Modifier.height(6.dp))
            Text(
                "A download stops if the phone moves onto a network this does not allow, and " +
                    "resumes when it is back on one. Held regions are listed on the Map tab.",
                color = T.Faint, fontSize = 10.sp, lineHeight = 14.sp
            )
        }

        // ------------------------------------------------ sharing, by reference
        SecHead("Sharing", if (sharing) "on" else "off")
        Card(Modifier.clickableNoRipple(onOpenSharing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !sharing -> "Not contributing to the shared map."
                        lastUpload > 0 -> "Contributing. Last sent ${ago(lastUpload)}."
                        else -> "Contributing. Nothing sent yet."
                    },
                    color = if (sharing) T.Good else T.Dim, fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("change on Map", color = T.Brand, fontSize = 10.sp)
            }
        }

        // ------------------------------------------------ data on this phone
        YourDataPanel()

        // ------------------------------------------------ privilege
        SecHead("Privilege", "tier 2")
        PrivilegeBanner(shizuku)

        // ------------------------------------------------ advanced
        SecHead("Advanced", "self-test · action log")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("traffic class now", color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
                    modifier = Modifier.weight(1f))
                Text(traffic.klass.label, color = classColor(traffic.klass), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
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

        SecHead("Action log", "everything is logged")
        LogCard(log)

        // ------------------------------------------------ about
        SecHead("About", "SignalScope $version")
        Card(Modifier.clickableNoRipple {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Privacy policy", color = T.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("open", color = T.Brand, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        CannotPanel()

        Spacer(Modifier.height(22.dp))
    }
}

/** One option of a single choice: a dot, a label, and what choosing it costs. */
@Composable
private fun Choice(
    label: String,
    detail: String,
    selected: Boolean,
    warn: Boolean = false,
    onSelect: () -> Unit
) {
    val accent = if (warn && selected) T.Warn else T.Brand
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accent.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (selected) accent.copy(alpha = 0.35f) else T.LineSoft,
                RoundedCornerShape(9.dp))
            .clickableNoRipple(onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(999.dp))
                .border(1.5.dp, if (selected) accent else T.Faint, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(
                Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(accent)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = T.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = if (warn) T.Warn.copy(alpha = 0.85f) else T.Dim,
                fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}
