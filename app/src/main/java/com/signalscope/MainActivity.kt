package com.signalscope

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.*
import com.signalscope.store.DeviceProfile
import com.signalscope.store.SubProfile
import com.signalscope.ui.*

private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Targeting SDK 35+ makes the window edge-to-edge whether we ask or not, so declare it
        // and pick light system-bar icons for the dark surface. Insets are consumed below;
        // without that, content sits under the status bar and behind the navigation bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root() } }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val sims by LiveState.sims.collectAsStateWithLifecycle()
    val net by LiveState.net.collectAsStateWithLifecycle()
    val counters by LiveState.counters.collectAsStateWithLifecycle()
    val running by LiveState.running.collectAsStateWithLifecycle()
    val degraded by LiveState.degraded.collectAsStateWithLifecycle()
    val profile by LiveState.profile.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.LIVE) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val ordered = sims.values.sortedBy { it.slot }
    val active = ordered.firstOrNull { it.subId == selected } ?: ordered.firstOrNull()

    val perms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { CollectorService.start(ctx) }

    LaunchedEffect(Unit) {
        perms.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }

    Column(
        Modifier.fillMaxSize().background(T.Page)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppHead(tab, active, running, counters) {
            if (running) CollectorService.stop(ctx) else CollectorService.start(ctx)
        }

        degraded?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(T.Warn.copy(alpha = 0.10f))
                    .border(1.dp, T.Warn.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(msg, color = T.Warn, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
        }

        if (tab != Tab.LIVE) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.TIMELINE -> TimelineScreen()
                    Tab.MAP -> MapScreen()
                    Tab.ACTIONS -> ActionsScreen()
                    else -> {}
                }
            }
            BottomNav(tab) { tab = it }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (ordered.size > 1) {
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ordered.forEach { s ->
                        Chip(
                            "${s.carrier.take(10)}${if (s.isDataSub) " · data" else ""}",
                            s.subId == (active?.subId ?: -1)
                        ) { selected = s.subId }
                    }
                }
            }

            val sp = profile?.sub(active?.subId ?: -1)
            if (active != null) Hero(active, sp) else {
                Spacer(Modifier.height(24.dp))
                Text("Waiting for a subscription — grant phone and location permission.",
                    color = T.Dim, fontSize = 13.sp)
            }

            SecHead("Default route", net.ifname ?: "—")
            RouteCard(net)

            if (active != null) {
                SecHead("Serving cell", "updated live")
                CellCard(active)
                SecHead("Registration", "sub ${active.subId}")
                RegCard(active)
                SecHead("This device", "discovered, not assumed")
                DeviceCard(profile, sp)
            }

            Spacer(Modifier.height(18.dp))
        }

        BottomNav(tab) { tab = it }
    }
}

@Composable
private fun AppHead(tab: Tab, s: SimState?, running: Boolean, c: Counters, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.title, color = T.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp)
            Text(
                when {
                    tab != Tab.LIVE -> tab.subtitle
                    // Serving network first; the card's home network only as a label, and only when
                    // it differs -- which is exactly when a SIM is roaming.
                    s != null -> "${s.carrier} · ${s.plmn}" +
                        (if (s.simPlmn != "—" && s.simPlmn != s.plmn) " (home ${s.simPlmn})" else "") +
                        " · SIM ${s.slot + 1}"
                    else -> "no subscription"
                },
                color = T.Faint, fontSize = 11.sp, fontFamily = Mono, maxLines = 1
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background((if (running) T.Good else T.Faint).copy(alpha = 0.10f))
                    .border(1.dp, (if (running) T.Good else T.Faint).copy(alpha = 0.28f),
                        RoundedCornerShape(999.dp))
                    .clickableNoRipple(onToggle)
                    .padding(horizontal = 9.dp, vertical = 5.dp)) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (running) T.Good else T.Faint))
                Spacer(Modifier.width(6.dp))
                Text(if (running) "COLLECTING" else "STOPPED",
                    color = if (running) T.Good else T.Faint, fontSize = 9.sp,
                    fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text("${c.radioRows}·${c.regRows}·${c.linkRows} rows",
                color = T.Faint, fontSize = 9.sp, fontFamily = Mono)
        }
    }
}

@Composable
private fun Hero(s: SimState, sp: SubProfile?) {
    val inService = s.serviceState == "IN_SERVICE"
    val barsDisagree = s.vendorLevel != null && s.level != null && s.vendorLevel != s.level
    val rsrpT = sp?.lteRsrpThresholds ?: DeviceProfile.FALLBACK_LTE_RSRP
    val rsrqT = sp?.lteRsrqThresholds ?: DeviceProfile.FALLBACK_LTE_RSRQ
    val fullBarsBadSinr = (s.rssnr ?: 99) < 3 && (s.level ?: 0) >= 3

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    0f to Color(0x2AB388FF), 0.42f to Color(0x124DA3FF), 0.74f to Color(0x00000000)
                )
            )
            .background(T.Surface.copy(alpha = 0.55f))
            .border(1.dp, Color(0xFF2B2740), RoundedCornerShape(18.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.rat, color = T.Text, fontSize = 29.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.9).sp)
                    if (s.overrideNetwork != "NONE" && s.overrideNetwork != "—") {
                        Text(" ${s.overrideNetwork}", color = T.Nr, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                Text(
                    "band ${s.band ?: "—"} · arfcn ${s.arfcn ?: "—"}" +
                            if (s.roaming) " · roaming" else "",
                    color = T.Dim, fontSize = 10.sp, fontFamily = Mono
                )
            }
            Tag(s.serviceState, if (inService) T.Good else T.Bad)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("RSRP", s.rsrp?.toString() ?: "—", "dBm",
                Q.rsrpFrac(s.rsrp, rsrpT), Q.rsrpColor(s.rsrp, rsrpT), Modifier.weight(1f))
            Metric("RSRQ", s.rsrq?.toString() ?: "—", "dB",
                Q.rsrqFrac(s.rsrq, rsrqT), Q.rsrqColor(s.rsrq, rsrqT), Modifier.weight(1f))
            Metric("SINR", s.rssnr?.toString() ?: "—", "dB",
                Q.sinrFrac(s.rssnr), Q.sinrColor(s.rssnr), Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(T.Page).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BARS", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f))
                Text("carrier ${Q.carrierLevel(s.rsrp, rsrpT) ?: "—"}/4", color = T.Dim,
                    fontSize = 11.sp, fontFamily = Mono)
                Spacer(Modifier.width(10.dp))
                Text("aosp ${s.level ?: "—"}/4", color = T.Dim, fontSize = 11.sp, fontFamily = Mono)
                Spacer(Modifier.width(10.dp))
                Text("vendor ${s.vendorLevel ?: "—"}/4",
                    color = if (barsDisagree) T.Warn else T.Dim,
                    fontSize = 11.sp, fontFamily = Mono,
                    fontWeight = if (barsDisagree) FontWeight.Bold else FontWeight.Normal)
            }
            if (barsDisagree) {
                Spacer(Modifier.height(4.dp))
                Text("the bar computations disagree", color = T.Warn, fontSize = 10.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (sp?.thresholdsAreFallback != false)
                    "carrier scale unavailable — using 3GPP fallback thresholds"
                else "carrier bar uses ${Q.barInputs(sp.lteBarParams)} · cut-points ${rsrpT.joinToString(",")}",
                color = T.Faint, fontSize = 9.sp, fontFamily = Mono
            )
            if (fullBarsBadSinr) {
                Spacer(Modifier.height(4.dp))
                Text("full bars · SINR ${s.rssnr} dB — power is fine, quality is not",
                    color = T.Bad, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RouteCard(n: NetState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.transport, color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Tag(if (n.validated) "VALIDATED" else "NOT VALIDATED",
                if (n.validated) T.Good else T.Bad)
        }
        Spacer(Modifier.height(9.dp))
        Row {
            KvCell("v4", n.v4 ?: "—", Modifier.weight(1f))
            KvCell("mtu", n.mtu?.takeIf { it > 0 }?.toString() ?: "—", Modifier.weight(0.5f))
            KvCell("464xlat", if (n.hasClat) "up" else "no", Modifier.weight(0.6f))
        }
        Spacer(Modifier.height(8.dp))
        ERow("v6", n.v6 ?: "—")
        ERow("suspended", if (n.notSuspended) "no" else "YES",
            if (n.notSuspended) T.Dim else T.Bad)
        ERow("addr changes", n.addressChanges.toString(),
            if (n.addressChanges > 0) T.Warn else T.Dim)
        if (n.addressChanges > 0) {
            ERow("", "an address change resets every open TCP socket", T.Warn)
        }
    }
}

@Composable
private fun CellCard(s: SimState) {
    Card {
        // LTE packs the site and the sector into one Cell Identity: CI = eNodeB << 8 | sector.
        // Decomposing it turns "three different cells" into "three sectors/carriers of one mast",
        // which is a completely different diagnosis and is not shown by any other tool.
        val enb = s.ci?.let { it shr 8 }
        val sector = s.ci?.let { (it and 0xFF).toInt() }
        Row {
            KvCell("site (eNB)", enb?.toString() ?: "—", Modifier.weight(1.2f),
                if (enb != null) T.Brand else T.Text)
            KvCell("sector", sector?.toString() ?: "—", Modifier.weight(1f))
            KvCell("tac", s.tac?.toString() ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            KvCell("pci", s.pci?.toString() ?: "—", Modifier.weight(1f))
            KvCell("cell id", s.ci?.toString() ?: "—", Modifier.weight(1.4f))
            KvCell(
                "serving",
                s.plmn + if (s.simPlmn != "—" && s.simPlmn != s.plmn) " · home ${s.simPlmn}" else "",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row {
            KvCell("arfcn", s.arfcn?.toString() ?: "—", Modifier.weight(1f))
            KvCell("band", s.band?.toString() ?: "—", Modifier.weight(1.4f))
            KvCell("cqi", s.cqi?.toString() ?: "n/a", Modifier.weight(1f),
                if (s.cqi == null) T.Faint else T.Text)
        }
        Spacer(Modifier.height(10.dp))
        ERow("timing adv",
            s.timingAdvance?.let { ta ->
                Q.taMetres(ta, s.rat)?.let { "$ta · ~$it m" } ?: "$ta (no distance conversion for ${s.rat})"
            } ?: "not reported by this chipset",
            if (s.timingAdvance == null) T.Faint else T.Dim)
        ERow("neighbours", s.neighbourCountSeen?.toString() ?: "—")
        if (enb != null) ERow("", "cells sharing site $enb are sectors and carriers of one mast, " +
                "so hopping between them is not a gap between towers", T.Faint)
        ERow("updates", "signal ${s.signalUpdates} · cell ${s.cellUpdates}", T.Faint)
    }
}

@Composable
private fun RegCard(s: SimState) {
    Card {
        Row {
            KvCell("ps", yn(s.psRegistered), Modifier.weight(1f),
                if (s.psRegistered == false) T.Bad else T.Text)
            KvCell("cs", yn(s.csRegistered), Modifier.weight(1f))
            KvCell("iwlan", yn(s.iwlanRegistered), Modifier.weight(1f),
                if (s.iwlanRegistered == true) T.Good else T.Text)
        }
        Spacer(Modifier.height(10.dp))
        ERow("reject cause", s.rejectCause?.takeIf { it != 0 }?.toString() ?: "0 · none")
        ERow("data activity", dataActName(s.dataActivity),
            if (s.dataActivity == 4) T.Warn else T.Dim)
        if (s.dataActivity == 4) ERow("", "radio is dormant — probing now would cost an RRC wake", T.Faint)
        ERow("roaming", if (s.roaming) "YES" else "no", if (s.roaming) T.Warn else T.Dim)
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xF00C0F14))
            .border(0.dp, Color.Transparent)
            .padding(top = 9.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem(Tab.LIVE, Icons.Filled.PlayArrow, current, onSelect)
        NavItem(Tab.TIMELINE, Icons.Filled.List, current, onSelect)
        NavItem(Tab.MAP, Icons.Filled.Place, current, onSelect)
        NavItem(Tab.ACTIONS, Icons.Filled.Settings, current, onSelect)
    }
}

@Composable
private fun NavItem(tab: Tab, icon: ImageVector, current: Tab, onSelect: (Tab) -> Unit) {
    val on = tab == current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickableNoRipple { onSelect(tab) }.padding(horizontal = 14.dp)
    ) {
        Icon(icon, contentDescription = tab.label,
            tint = if (on) T.Brand else T.Faint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(tab.label, color = if (on) T.Brand else T.Faint, fontSize = 10.sp)
    }
}

@Composable
private fun DeviceCard(p: DeviceProfile?, sp: SubProfile?) {
    Card {
        if (p == null) {
            Text("discovering…", color = T.Faint, fontSize = 11.sp, fontFamily = Mono)
            return@Card
        }
        Row {
            KvCell("device", "${p.manufacturer} ${p.model}", Modifier.weight(1.4f))
            KvCell("soc", p.soc, Modifier.weight(1f))
            KvCell("android", "${p.release} (${p.sdkInt})", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        ERow("baseband", p.baseband)
        ERow("modems", "${p.activeModems} active of ${p.supportedModems} supported")
        if (sp != null) {
            ERow("sim", buildString {
                append(if (sp.isEmbedded) "eSIM" else "physical")
                append(" · slot ${sp.slot}")
                if (sp.portIndex >= 0) append(" port ${sp.portIndex}")
                sp.countryIso?.let { append(" · home ${it.uppercase()}") }
            })
            ERow("carrier scale",
                if (sp.thresholdsAreFallback) "not supplied — 3GPP fallback in use"
                else "supplied by carrier config",
                if (sp.thresholdsAreFallback) T.Warn else T.Good)
        }
        ERow("", "all of the above is read from the phone at run time", T.Faint)
    }
}

private fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "—" }
private fun dataActName(a: Int?) = when (a) {
    0 -> "NONE"; 1 -> "IN"; 2 -> "OUT"; 3 -> "INOUT"; 4 -> "DORMANT"; else -> "—"
}
