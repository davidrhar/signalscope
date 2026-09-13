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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.RegionAcquisition
import com.signalscope.store.RegionBoxes
import com.signalscope.store.RegionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/**
 * The basemap, made legible.
 *
 * Everything acquisition does is unattended, which means the only way the user can tell it apart
 * from a broken app is if it says what it is doing. So this screen answers, in order: what map do
 * I have here, what is being fetched, what is queued and *what is it waiting for*, what do I hold
 * in total, and what would deleting something cost me.
 *
 * It is deliberately a sheet over the map rather than a settings page. The question "why is there
 * no map here?" is asked while looking at the map.
 */
@Composable
fun RegionPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val st by RegionAcquisition.state.collectAsStateWithLifecycle()
    var regions by remember { mutableStateOf<List<RegionStore.Region>>(emptyList()) }
    var space by remember { mutableStateOf(RegionStore.Space(0, 0)) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload, st.revision, st.active) {
        while (true) {
            val r = withContext(Dispatchers.IO) { RegionStore.scan(ctx) }
            val s = withContext(Dispatchers.IO) { RegionStore.space(ctx) }
            regions = r; space = s
            delay(4_000)
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            // Near-opaque, unlike the map's other glass panels: this one is dense text over a
            // basemap, and the map bleeding through made the status strip behind it read as part
            // of the panel.
            .background(Color(0xFA07090D))
            .border(1.dp, T.Line, RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BASEMAP REGIONS", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.weight(1f)
            )
            Text("close", color = T.Dim, fontSize = 10.sp,
                modifier = Modifier.clickableNoRipple(onClose))
        }
        Spacer(Modifier.height(7.dp))

        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 430.dp)) {

            // ------------------------------------------------ what is happening right now
            val active = st.active
            if (active != null) {
                Section("Downloading")
                Text(active.label, color = T.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                val p = st.progress
                val frac = if (p != null && p.total > 0) p.done.toFloat() / p.total else 0f
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp))
                        .background(T.Surface3)
                ) {
                    Box(
                        Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(T.Brand)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    (p?.let { "${it.phase} · ${it.done}/${it.total} · " +
                        "${it.transferred / 1_000_000} MB transferred" } ?: "starting…"),
                    color = T.Brand, fontSize = 9.sp, fontFamily = Mono
                )
                Spacer(Modifier.height(3.dp))
                Text("cancel", color = T.Dim, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { RegionAcquisition.cancel() })
            }

            // ------------------------------------------------ queue and the reason for waiting
            if (st.queue.isNotEmpty()) {
                Section("Queued")
                st.queue.forEach { j ->
                    ERow(j.label, "~${j.estBytes / 1_000_000} MB · ${j.estTiles} tiles")
                }
                val blocked = st.blocked
                Spacer(Modifier.height(3.dp))
                Text(
                    blocked ?: "conditions met — starting",
                    color = if (blocked != null) T.Warn else T.Good,
                    fontSize = 10.sp, lineHeight = 13.sp
                )
                Text(
                    "A deferred download is queued, not failed. Nothing here will ever prompt, " +
                        "and nothing is fetched on cellular.",
                    color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                )
            }

            // ------------------------------------------------ detection
            Section("Region detection")
            ERow(
                "network country",
                st.networkCountry?.let { "$it · ${RegionBoxes.name(it)}" } ?: "unknown",
                if (st.networkCountry == null) T.Warn else T.Dim
            )
            val held = st.candidateHeldMs
            if (st.confirmedCountry == st.candidate && st.confirmedCountry != null) {
                ERow("confirmed", "${st.confirmedCountry} · held ${held / 60_000} min", T.Good)
            } else if (st.candidate != null) {
                val left = ((RegionAcquisition.COUNTRY_HYSTERESIS_MS - held) / 60_000).coerceAtLeast(0)
                ERow("confirming", "${st.candidate} · $left min of hysteresis left", T.Warn)
            }
            Text(
                "From getNetworkCountryIso() on the data subscription — never getSimCountryIso(), " +
                    "which names the SIM's home country and is permanently wrong on a roaming SIM. " +
                    "A new country must hold for 10 minutes, so a border crossing is one event " +
                    "rather than twenty.",
                color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
            )
            Spacer(Modifier.height(5.dp))
            // Not a prompt: nothing here asks. These are the manual override for someone who
            // wants the map for where they are standing now and does not want to wait out a
            // damping rule that exists for automatic triggering.
            Row {
                Text("add this country now", color = T.Brand, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { RegionAcquisition.forceCountry(ctx) })
                Spacer(Modifier.width(14.dp))
                Text("add detail here now", color = T.Brand, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { RegionAcquisition.forceLocal(ctx) })
            }

            // ------------------------------------------------ conditions
            st.conditions?.let { c ->
                Section("Conditions")
                ERow("transport", c.transport + if (c.unmetered) " · unmetered" else " · metered",
                    if (c.unmetered) T.Good else T.Warn)
                ERow(
                    "battery",
                    (if (c.batteryPct >= 0) "${c.batteryPct}%" else "unknown") +
                        if (c.charging) " · charging" else "",
                    if (c.charging || c.batteryPct >= RegionAcquisition.BATTERY_FLOOR) T.Good else T.Warn
                )
                ERow("free storage", "${space.freeBytes / 1_000_000} MB",
                    if (space.healthy) T.Good else T.Warn)
            }

            // ------------------------------------------------ what we hold
            Section("Held regions · ${regions.size} · ${space.usedByMaps / 1_000_000} MB")
            if (regions.isEmpty()) {
                Text(
                    "No archives. The bundled world map should have unpacked on first run; if it " +
                        "did not, bins render on flat ground and detail still downloads normally.",
                    color = T.Warn, fontSize = 10.sp, lineHeight = 13.sp
                )
            }
            regions.forEach { r ->
                RegionRow(r) {
                    RegionStore.delete(ctx, r.id)
                    reload++
                }
            }

            // ------------------------------------------------ anything the renderer refused
            val rejected = RegionStore.rejections
            val quarantined = remember(reload) { RegionStore.quarantined(ctx) }
            if (rejected.isNotEmpty() || quarantined.isNotEmpty() ||
                Basemap.quarantinedThisLaunch.isNotEmpty()
            ) {
                Section("Not rendered")
                Text(
                    "MapLibre's PMTiles reader aborts the whole process on an archive it cannot " +
                        "parse — there is no exception to catch. So archives are proved readable " +
                        "before the renderer sees them, and anything that fails is set aside here " +
                        "rather than opened.",
                    color = T.Dim, fontSize = 10.sp, lineHeight = 13.sp
                )
                Spacer(Modifier.height(4.dp))
                Basemap.quarantinedThisLaunch.forEach {
                    ERow(it, "quarantined: the app did not survive opening it", T.Bad)
                }
                rejected.forEach { (name, why) -> ERow(name, why, T.Warn) }
                quarantined.forEach { (name, why) -> ERow(name, why, T.Faint) }
            }

            // ------------------------------------------------ retention
            Section("Retention")
            Text(
                "Nothing is ever auto-evicted. Deleting is manual, above, and only ever a " +
                    "suggestion here: basemap regions are replaceable, measurements are not. A " +
                    "deleted region can be re-downloaded; a deleted measurement history is gone. " +
                    "Storage pressure is resolved against the basemap first, by asking.",
                color = T.Dim, fontSize = 10.sp, lineHeight = 13.sp
            )
            if (!space.healthy) {
                Spacer(Modifier.height(4.dp))
                val s = RegionStore.suggestion(ctx)
                Text(
                    "Storage is low. " + (s?.let {
                        "Least recently added region: ${it.label}, ${it.bytes / 1_000_000} MB. " +
                            "Delete it above if you want the space back."
                    } ?: "Nothing but the bundled world map is held, so there is nothing to suggest."),
                    color = T.Warn, fontSize = 10.sp, lineHeight = 13.sp
                )
            }

            // ------------------------------------------------ provenance
            Section("Source")
            Text(
                st.source, color = T.Faint, fontSize = 9.sp, fontFamily = Mono, lineHeight = 12.sp
            )
            Text(
                "Byte ranges out of the Protomaps planet, via the Source Cooperative mirror — not " +
                    "build.protomaps.com, whose own docs discourage hotlinking and whose URLs " +
                    "expire weekly. Fetched once per region and never again. ${RegionBoxes.size} " +
                    "country boxes ship in the APK; no lookup service is contacted.",
                color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
            )

            st.lastResult?.let {
                Spacer(Modifier.height(6.dp))
                Text("last: $it", color = T.Good, fontSize = 9.sp, fontFamily = Mono, lineHeight = 12.sp)
            }
            st.lastError?.let {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("error: $it", color = T.Bad, fontSize = 9.sp, fontFamily = Mono,
                        lineHeight = 12.sp, modifier = Modifier.weight(1f))
                    Text("retry", color = T.Brand, fontSize = 10.sp,
                        modifier = Modifier.clickableNoRipple { RegionAcquisition.requeue(ctx) })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(9.dp))
    Text(
        title.uppercase(), color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
    )
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun RegionRow(r: RegionStore.Region, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp)).background(Color(0x66151A22))
            .border(1.dp, T.LineSoft, RoundedCornerShape(9.dp)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.label, color = T.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "z${r.minZoom}–${r.maxZoom} · ${r.extent} · ${r.bytes / 1_000_000} MB",
                    color = T.Dim, fontSize = 9.sp, fontFamily = Mono
                )
                if (r.requests > 0) Text(
                    "cost ${r.requests} requests · ${r.transferred / 1_000_000} MB · " +
                        "${r.millis / 1000}s",
                    color = T.Faint, fontSize = 9.sp, fontFamily = Mono
                )
            }
            if (r.kind == RegionStore.Kind.WORLD) {
                Tag("BUNDLED", T.Brand)
            } else if (!confirm) {
                Text("delete", color = T.Dim, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { confirm = true })
            } else {
                Text("confirm", color = T.Bad, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { confirm = false; onDelete() })
                Spacer(Modifier.width(8.dp))
                Text("no", color = T.Dim, fontSize = 10.sp,
                    modifier = Modifier.clickableNoRipple { confirm = false })
            }
        }
    }
}
