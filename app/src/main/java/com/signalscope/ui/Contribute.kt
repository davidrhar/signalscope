package com.signalscope.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.signalscope.store.Contribution
import com.signalscope.store.Export
import com.signalscope.store.Networks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val Mono = FontFamily.Monospace

/**
 * Offer this phone's measurements to a shared map, having first shown exactly what that means.
 *
 * ## Why the preview is the primary action
 *
 * The consent draft settled this: the payload is shown as **rows, not prose**. Anyone can read six
 * lines and see for themselves that there is no timestamp and no coordinate in them. A paragraph
 * claiming the same thing asks to be believed; the rows can be checked. So "See exactly what would
 * be sent" sits above the share button and is built from this phone's real data, never an example.
 *
 * ## Why there is no automatic upload
 *
 * There is no backend yet, and this panel deliberately does not pretend otherwise. Sharing here is
 * a file the user hands over through Android's own share sheet -- they pick the destination, they
 * can read it first, and nothing leaves without a deliberate act. When an ingest endpoint exists it
 * will need its own consent, because "I sent a file once" is not agreement to a standing upload.
 *
 * ## The threshold is stated, and it is not enforced here
 *
 * The copy says three contributors because that is what the aggregator enforces. Nothing in the app
 * can enforce it -- a client deciding what is safe to publish is a client trusted with a rule it
 * has reason to relax -- so this says what will happen to the data, not what it has done to it.
 */
@Composable
fun ContributePanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) {
            runCatching { Contribution.recordCount(ctx) }.getOrDefault(0)
        }
    }

    SecHead(
        "Add to the shared map",
        when (val n = records) {
            null -> "checking"
            0 -> "nothing to share yet"
            else -> "$n area${if (n == 1) "" else "s"} ready"
        }
    )

    AccentCard(T.Line) {
        Text(
            "Everyone running this app can see one map built from everyone's measurements — which " +
                "networks actually work where, rather than which have the best coverage claims.",
            color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(9.dp))
        Text(
            "Summaries per area, per network, per band. No exact locations, no times of day, no " +
                "mast identities, no device identifier and no account. An area only ever appears " +
                "on the shared map once three different people have measured it.",
            color = T.Faint, fontSize = 11.5.sp, lineHeight = 16.sp
        )

        if (records == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Nothing to share yet — areas appear here once the app has measured one place for " +
                    "long enough to say something about it.",
                color = T.Faint, fontSize = 11.5.sp, lineHeight = 16.sp
            )
            return@AccentCard
        }

        Spacer(Modifier.height(11.dp))
        Btn("See exactly what would be sent", ghost = true) {
            if (busy) return@Btn
            busy = true
            scope.launch {
                preview = withContext(Dispatchers.IO) {
                    runCatching { previewOf(ctx) }.getOrNull() ?: "could not build a preview"
                }
                busy = false
            }
        }

        preview?.let { p ->
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF05070A))
                    .border(1.dp, T.Line, RoundedCornerShape(9.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(p, color = T.Dim, fontSize = 10.sp, fontFamily = Mono, lineHeight = 15.sp)
            }
        }

        Spacer(Modifier.height(7.dp))
        Btn("Share my measurements") {
            if (busy) return@Btn
            busy = true; note = "Building…"
            scope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { writeBundle(ctx) }.getOrNull() }
                note = if (file == null) "Could not build the contribution."
                else {
                    val i = runCatching { Export.shareIntent(ctx, file) }.getOrNull()
                    if (i == null) "Wrote ${file.name}, but could not open the share sheet."
                    else { runCatching { ctx.startActivity(i) }; "Shared ${file.name}." }
                }
                busy = false
            }
        }

        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = T.Faint, fontSize = 11.5.sp)
        }
    }
}

/**
 * The first few records, formatted for a person rather than a parser.
 *
 * Deliberately the real bundle, truncated -- not a hand-written sample. A mocked preview would be
 * the one part of this screen that cannot be wrong, which makes it the one part worth nothing.
 */
private suspend fun previewOf(ctx: Context): String {
    val doc = JSONObject(Contribution.build(ctx))
    val recs = doc.getJSONArray("records")
    if (recs.length() == 0) return "no areas measured yet"
    return buildString {
        append("area          network    band  samples  week\n")
        val show = minOf(recs.length(), 6)
        for (i in 0 until show) {
            val r = recs.getJSONObject(i)
            // The area id is a lattice cell, not a coordinate, and is shown truncated because its
            // full 19 digits invite the reader to think it is more precise than ~460 m.
            val area = r.optLong("area").toString().takeLast(6)
            append(
                "…%-8s  %-9s  %-4s  %-7d  %s\n".format(
                    area,
                    Networks.name(r.optString("network")).take(9),
                    r.optString("band"),
                    r.optLong("samples"),
                    r.optString("week")
                )
            )
        }
        if (recs.length() > show) append("… and ${recs.length() - show} more areas\n")
        append("\nno coordinates · no timestamps · no cell ids · no device id")
    }
}

private suspend fun writeBundle(ctx: Context): File {
    val json = Contribution.build(ctx)
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())
    // Into the same directory Export uses, because the FileProvider is scoped to exactly that path
    // and nothing else on the device is shareable.
    val f = File(Export.dir(ctx), "signalscope-contribution-$stamp.json")
    f.writeText(json)
    return f
}

/** Local copy: ActionsScreen's is private to that file. */
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
