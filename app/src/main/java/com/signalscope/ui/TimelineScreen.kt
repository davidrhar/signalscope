package com.signalscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.IncidentEngine
import com.signalscope.collect.IncidentEngine.Attribution
import com.signalscope.collect.IncidentEngine.Incident
import com.signalscope.collect.IncidentEngine.Kind
import com.signalscope.collect.IncidentEngine.Layer
import com.signalscope.collect.IncidentEngine.LayerVerdict
import com.signalscope.collect.IncidentEngine.Support
import com.signalscope.collect.LiveState
import com.signalscope.store.IncidentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace

/** Layer accent colours, lifted from the mockup's --l-* tokens. */
private val LRadio = Color(0xFFFF9F4A)
private val LNetwork = Color(0xFF4DA3FF)
private val LTransport = Color(0xFF3DDC97)
private val LContext = Color(0xFFB388FF)

private fun layerColor(l: Layer) = when (l) {
    Layer.RADIO -> LRadio
    Layer.NETWORK -> LNetwork
    Layer.TRANSPORT -> LTransport
    Layer.CONTEXT -> LContext
}

private fun confColor(c: Float) = when {
    c >= 0.70f -> T.Good
    c >= 0.45f -> T.Warn
    else -> T.Faint
}

private fun sevColor(i: Incident) = when {
    i.kind == Kind.DEGRADED -> T.Brand
    i.durationMs >= 60_000 -> T.Bad
    else -> T.Warn
}

private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val hhmmss: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun t(ms: Long, f: DateTimeFormatter) =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(f)

private fun day(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

private enum class Filter(val label: String) {
    ALL("All"), OUTAGE("Outages"), DEGRADED("Degraded"),
    RADIO("Radio"), NETWORK("Network")
}

private fun Incident.matches(f: Filter) = when (f) {
    Filter.ALL -> true
    Filter.OUTAGE -> kind == Kind.OUTAGE
    Filter.DEGRADED -> kind == Kind.DEGRADED
    Filter.RADIO -> top?.cause?.layer == Layer.RADIO
    Filter.NETWORK -> top?.cause?.layer == Layer.NETWORK
}

// ------------------------------------------------------------------ screen

@Composable
fun TimelineScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val sims by LiveState.sims.collectAsStateWithLifecycle()
    val counters by LiveState.counters.collectAsStateWithLifecycle()

    var result by remember { mutableStateOf<IncidentEngine.Result?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var open by remember { mutableStateOf<String?>(null) }

    // Re-derive as rows accumulate. Keyed on coarsened counts so a busy collector does not
    // re-run the engine on every single callback.
    LaunchedEffect(counters.radioRows / 20, counters.regRows / 5, counters.linkRows / 5, sims.size) {
        // The read and the derivation both run against live storage: a corrupt or
        // mid-migration database throws SQLiteException, and an unhandled throw inside a
        // LaunchedEffect takes the whole activity down. MapBinBuilder.build() already fails to
        // an empty model for exactly this reason; the timeline now does the same, keeping the
        // last good result on screen instead of crashing to the launcher.
        val next = runCatching {
            withContext(Dispatchers.IO) {
                val rows = IncidentStore.load(ctx)
                IncidentEngine.analyse(rows, sims, sims.values.firstOrNull { it.isDataSub }?.subId)
            }
        }.getOrNull()
        if (next != null) result = next
        loaded = true
    }

    val res = result
    val subIds = res?.subIds.orEmpty()
    val activeSub = sub ?: sims.values.firstOrNull { it.isDataSub }?.subId ?: subIds.firstOrNull()

    // Per subscription, always. Two SIMs are compared, never combined.
    val forSub = res?.incidents.orEmpty().filter { activeSub == null || it.subId == activeSub }
    val shown = forSub.filter { it.matches(filter) }
    val current = open?.let { id -> forSub.firstOrNull { it.id == id } }

    BackHandler(enabled = current != null) { open = null }

    Column(modifier.fillMaxSize()) {
        if (current != null) {
            Detail(current) { open = null }
            return@Column
        }

        val activeSim = activeSub?.let { sims[it] }
        Head(activeSim?.carrier, activeSim?.plmn, res)

        if (subIds.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                subIds.forEach { id ->
                    val s = sims[id]
                    Chip(
                        "${s?.carrier?.take(10) ?: "sub $id"}${if (s?.isDataSub == true) " · data" else ""}",
                        id == activeSub
                    ) { sub = id; open = null }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item { SummaryStrip(forSub, res) }

            // Incidents are what broke badly enough to be noticed. This is the other half of the
            // same question and the half that is usually the answer: whether the connection comes
            // back when something needs it, and how long that takes. It sits above the list
            // because "no incidents" and "wake-ups keep failing" are true at the same time, and
            // the second one is the finding.
            item { ConnectionHealthPanel(compact = true) }

            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Filter.entries.forEach { f ->
                        val n = forSub.count { it.matches(f) }
                        Chip("${f.label} $n", f == filter) { filter = f }
                    }
                }
            }

            if (!loaded) {
                item { Note("Reading the collected rows…") }
            } else if (shown.isEmpty()) {
                item { EmptyState(res, forSub.isNotEmpty()) }
            } else {
                var lastDay: String? = null
                shown.forEach { inc ->
                    val d = day(inc.startWall)
                    if (d != lastDay) {
                        lastDay = d
                        item(key = "day-$d") { DayLabel(d) }
                    }
                    item(key = inc.id) { IncidentRow(inc) { open = inc.id } }
                }
            }
        }
    }
}

@Composable
private fun Head(carrier: String?, plmn: String?, res: IncidentEngine.Result?) {
    val span = res?.rows?.let { r ->
        val a = r.observedFromWall; val b = r.observedToWall
        if (a == null || b == null) null else IncidentEngine.fmtTotal(b - a)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text("Incidents", color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp)
            Text(
                listOfNotNull(carrier, plmn, span?.let { "observed $it" }).joinToString(" · ")
                    .ifEmpty { "no subscription" },
                color = T.Faint, fontSize = 10.sp, fontFamily = Mono, maxLines = 1
            )
        }
        Tag("24 H WINDOW", T.Faint)
    }
}

@Composable
private fun SummaryStrip(incidents: List<Incident>, res: IncidentEngine.Result?) {
    val outages = incidents.filter { it.kind == Kind.OUTAGE }
    val offline = outages.sumOf { it.durationMs }
    // Top cause is measured prevalence on this device, not the table order.
    val top = incidents.filter { it.classified }.mapNotNull { it.top?.cause }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(14.dp))
    ) {
        SumCell("incidents", incidents.size.toString(), T.Text, Modifier.weight(1f))
        VLine()
        SumCell("offline", if (outages.isEmpty()) "0" else IncidentEngine.fmtTotal(offline),
            if (offline > 0) T.Bad else T.Dim, Modifier.weight(1f))
        VLine()
        SumCell("top cause", top?.let { "#${it.num}" } ?: "—",
            if (top == null) T.Dim else T.Warn, Modifier.weight(1f))
    }
    if (outages.isEmpty() && incidents.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "No outage in this window — ${incidents.size} degraded period" +
                    if (incidents.size == 1) "" else "s",
            color = T.Faint, fontSize = 10.sp, fontFamily = Mono
        )
    }
    if (res != null && res.prevalence.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "ranked by measured prevalence: " + res.prevalence.entries
                .sortedByDescending { it.value }.joinToString(" · ") { "#${it.key}×${it.value}" },
            color = T.Faint, fontSize = 9.sp, fontFamily = Mono
        )
    }
}

@Composable
private fun SumCell(k: String, v: String, color: Color, mod: Modifier) {
    Column(mod.padding(horizontal = 10.dp, vertical = 11.dp)) {
        Text(v, color = color, fontSize = 18.sp, fontFamily = Mono,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, maxLines = 1)
        Text(k.uppercase(), color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun VLine() {
    Box(Modifier.width(1.dp).height(48.dp).background(T.LineSoft))
}

@Composable
private fun DayLabel(text: String) {
    Text(text.uppercase(), color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
        letterSpacing = 1.3.sp,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 8.dp))
}

// ------------------------------------------------------------------ list row

@Composable
private fun IncidentRow(inc: Incident, onClick: () -> Unit) {
    val sev = sevColor(inc)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(14.dp))
            .clickableNoRipple(onClick)
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(sev))
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(50.dp)) {
                Text(t(inc.startWall, hhmm), color = T.Text, fontSize = 12.5.sp,
                    fontFamily = Mono, fontWeight = FontWeight.SemiBold)
                Text(
                    if (inc.ongoing) "ongoing" else IncidentEngine.fmtDuration(inc.durationMs),
                    color = if (inc.ongoing) T.Warn else T.Faint,
                    fontSize = 10.sp, fontFamily = Mono,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CNum(inc.top?.cause?.num, inc.classified)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        inc.headline,
                        color = if (inc.classified) T.Text else T.Dim,
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.15).sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false)
                    )
                    if (inc.kind == Kind.DEGRADED) {
                        Spacer(Modifier.width(6.dp))
                        Tag("DEGRADED", T.Brand)
                    }
                }
                Text(
                    inc.evidenceLine, color = T.Faint, fontSize = 10.5.sp, fontFamily = Mono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConfBar(inc.confidence, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Text("${(inc.confidence * 100).roundToInt()}%",
                        color = confColor(inc.confidence), fontSize = 10.sp,
                        fontFamily = Mono, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CNum(num: Int?, classified: Boolean) {
    Box(
        Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).background(T.Surface3),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (num != null && classified) num.toString() else "?",
            color = if (classified) T.Dim else T.Faint,
            fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ConfBar(c: Float, mod: Modifier) {
    Box(mod.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x13FFFFFF))) {
        Box(
            Modifier.fillMaxWidth(c.coerceIn(0f, 1f)).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(confColor(c))
        )
    }
}

// ------------------------------------------------------------------ empty / notes

@Composable
private fun Note(text: String) {
    Text(text, color = T.Faint, fontSize = 11.sp, fontFamily = Mono,
        modifier = Modifier.padding(vertical = 18.dp))
}

@Composable
private fun EmptyState(res: IncidentEngine.Result?, filteredOut: Boolean) {
    val rows = res?.rows
    Card(Modifier.padding(top = 4.dp)) {
        Text(
            if (filteredOut) "Nothing matches this filter." else "No incident in this window.",
            color = T.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (filteredOut) "Other incidents exist — clear the filter."
            else "Incidents are derived, never seeded. Nothing is shown until the collected " +
                    "rows contain one.",
            color = T.Dim, fontSize = 11.5.sp, lineHeight = 16.sp
        )
        if (rows != null) {
            Spacer(Modifier.height(10.dp))
            ERow("rows read", "${rows.radio.size} radio · ${rows.reg.size} reg · ${rows.link.size} link")
            val a = rows.observedFromWall; val b = rows.observedToWall
            ERow(
                "observed",
                if (a == null || b == null) "nothing yet"
                else "${t(a, hhmmss)} → ${t(b, hhmmss)} · ${IncidentEngine.fmtTotal(b - a)}"
            )
        }
        if (!filteredOut) {
            Spacer(Modifier.height(10.dp))
            Text("WAITING FOR", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(4.dp))
            IncidentEngine.ARMED.forEach {
                Text("· $it", color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
                    lineHeight = 15.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ detail

@Composable
private fun Detail(inc: Incident, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                    .background(T.Surface2).clickableNoRipple(onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Back", tint = T.Dim,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Incident", color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp)
                Text(
                    "${t(inc.startWall, hhmmss)} → ${t(inc.endWall, hhmmss)} · " +
                            IncidentEngine.fmtDuration(inc.durationMs),
                    color = T.Faint, fontSize = 10.sp, fontFamily = Mono
                )
            }
        }

        VerdictCard(inc)

        SecHead("Four-layer evidence", "one clock · 10 s floor")
        val l = inc.layers
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LayerCardView(l[0], Modifier.weight(1f).fillMaxHeight())
            LayerCardView(l[1], Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LayerCardView(l[2], Modifier.weight(1f).fillMaxHeight())
            LayerCardView(l[3], Modifier.weight(1f).fillMaxHeight())
        }

        l.mapNotNull { it.note }.distinct().forEach {
            Text("· $it", color = T.Faint, fontSize = 9.5.sp, fontFamily = Mono,
                lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp))
        }

        SecHead("Recommended fix")
        FixCard(inc)
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun VerdictCard(inc: Incident) {
    val sev = sevColor(inc)
    val conf = inc.confidence
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    0f to sev.copy(alpha = 0.13f),
                    0.6f to sev.copy(alpha = 0.02f),
                    1f to Color.Transparent
                )
            )
            .background(T.Surface.copy(alpha = 0.6f))
            .border(1.dp, sev.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(13.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(
                if (inc.classified && inc.top != null) "CAUSE ${inc.top!!.cause.num}"
                else "UNCLASSIFIED",
                if (inc.classified) T.Brand else T.Faint
            )
            Tag("CONFIDENCE ${(conf * 100).roundToInt()}%", confColor(conf))
            if (inc.kind == Kind.DEGRADED) Tag("DATA STAYED UP", T.Dim)
            if (inc.ongoing) Tag("ONGOING", T.Warn)
        }
        Spacer(Modifier.height(8.dp))
        Text(inc.headline, color = T.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp, lineHeight = 23.sp)

        val top = inc.top
        if (!inc.classified) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No cause reaches the ${(IncidentEngine.CLASSIFY_FLOOR * 100).roundToInt()}% floor. " +
                        (top?.let { "Closest is cause ${it.cause.num}, ${it.cause.name}, at " +
                                "${(it.confidence * 100).roundToInt()}%." } ?: ""),
                color = T.Dim, fontSize = 11.5.sp, lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
        Spacer(Modifier.height(8.dp))

        if (top != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "rule ${inc.detectors.joinToString("+")}",
                    color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("${top.supported} / ${top.predicates.size} predicates",
                    color = T.Faint, fontSize = 10.sp, fontFamily = Mono)
            }
            Spacer(Modifier.height(6.dp))
            top.predicates.forEach { PredicateRow(it) }
        }

        inc.resolutionNote?.let {
            Spacer(Modifier.height(6.dp))
            Text("timing · $it", color = T.Warn, fontSize = 9.5.sp, fontFamily = Mono,
                lineHeight = 14.sp)
        }
        if (inc.attribution == Attribution.ASSUMED) {
            Text("sub · attributed to the data subscription, not observed per-sub",
                color = T.Faint, fontSize = 9.5.sp, fontFamily = Mono, lineHeight = 14.sp)
        }
        inc.prevalenceNote?.let {
            Text("rank · $it", color = T.Faint, fontSize = 9.5.sp, fontFamily = Mono,
                lineHeight = 14.sp)
        }

        // What was excluded, and why. This block is the difference between a diagnosis and
        // a guess, so it is not collapsible and it is not optional.
        if (inc.ruledOut.isNotEmpty() || inc.notExcluded.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
            Spacer(Modifier.height(7.dp))
            inc.ruledOut.forEach { a ->
                RuledRow("ruled out ${a.cause.num}", a.ruledOutReason ?: "predicate contradicted", true)
            }
            inc.notExcluded.take(4).forEach { a ->
                RuledRow(
                    "not excluded ${a.cause.num}",
                    a.unobservable.firstOrNull()?.why ?: "no evidence either way",
                    false
                )
            }
        }
    }
}

@Composable
private fun RuledRow(head: String, body: String, struck: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            head, color = if (struck) T.Dim else T.Warn, fontSize = 9.5.sp, fontFamily = Mono,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            modifier = Modifier.width(104.dp).padding(end = 8.dp)
        )
        Text(body, color = T.Faint, fontSize = 9.5.sp, fontFamily = Mono,
            lineHeight = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PredicateRow(p: IncidentEngine.Predicate) {
    val (mark, color) = when (p.support) {
        Support.SUPPORTS -> "✓" to T.Good
        Support.CONTRADICTS -> "✗" to T.Bad
        Support.UNOBSERVABLE -> "?" to T.Faint
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
        Text(mark, color = color, fontSize = 10.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.text,
                color = if (p.support == Support.UNOBSERVABLE) T.Faint else T.Dim,
                fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp
            )
            p.why?.let {
                Text("↳ $it", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                    lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun LayerCardView(card: IncidentEngine.LayerCard, mod: Modifier) {
    val lc = layerColor(card.layer)
    val collected = card.verdict != LayerVerdict.NOT_COLLECTED
    val vColor = when (card.verdict) {
        LayerVerdict.HEALTHY -> T.Good
        LayerVerdict.NOMINAL -> T.Dim
        LayerVerdict.DEGRADED -> T.Warn
        LayerVerdict.FAILING, LayerVerdict.LOST -> T.Bad
        LayerVerdict.NOT_COLLECTED -> T.Faint
    }
    Row(
        mod.clip(RoundedCornerShape(4.dp, 13.dp, 13.dp, 4.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(4.dp, 13.dp, 13.dp, 4.dp))
    ) {
        Box(
            Modifier.width(2.5.dp).fillMaxHeight()
                .background(if (collected) lc else lc.copy(alpha = 0.28f))
        )
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(card.layer.label, color = if (collected) lc else lc.copy(alpha = 0.5f),
                    fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                Text(
                    if (collected) card.verdict.name else "NO DATA",
                    color = vColor, fontSize = 9.sp, fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp
                )
            }
            Spacer(Modifier.height(5.dp))
            card.rows.forEach { (k, v) ->
                Text(k, color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                    modifier = Modifier.padding(top = 3.dp))
                Text(v, color = if (collected) T.Dim else T.Faint, fontSize = 10.sp,
                    fontFamily = Mono, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun FixCard(inc: Incident) {
    val top = inc.top
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    0f to T.Brand.copy(alpha = 0.12f), 0.62f to Color.Transparent
                )
            )
            .background(T.Surface.copy(alpha = 0.6f))
            .border(1.dp, T.Brand.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(13.dp)
    ) {
        if (inc.classified && top != null) {
            Tag(top.cause.tier, T.Brand)
            Spacer(Modifier.height(8.dp))
            Text(top.cause.fix, color = T.Dim, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            Tag("NO FIX RECOMMENDED", T.Faint)
            Spacer(Modifier.height(8.dp))
            Text(
                "The evidence does not name a cause with enough confidence to act on. " +
                        "Acting on a guess is how the wrong setting gets changed.",
                color = T.Dim, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        inc.raiseConfidence?.let {
            Spacer(Modifier.height(10.dp))
            Text("TO RAISE CONFIDENCE", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(3.dp))
            Text(it, color = T.Faint, fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp)
        }
    }
}
