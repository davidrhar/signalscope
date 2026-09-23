package com.signalscope.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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
import com.signalscope.store.ShareConsent
import com.signalscope.store.Uploader
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
    var sharing by remember { mutableStateOf(ShareConsent.enabled(ctx)) }
    var lastUpload by remember { mutableStateOf(ShareConsent.lastUpload(ctx)) }
    var lastError by remember { mutableStateOf(ShareConsent.lastError(ctx)) }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) {
            runCatching { Contribution.recordCount(ctx) }.getOrDefault(0)
        }
        // A stored failure outlives the condition that caused it. With nothing to contribute there
        // is nothing that can have failed, so the old message is cleared rather than left under a
        // line that already says the app is waiting -- two contradictory statements, one of them
        // in red, is worse than either alone.
        if (records == 0) {
            ShareConsent.clearError(ctx)
            lastError = null
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

        // No early return when there is nothing to send.
        //
        // This used to bail out here, which hid the consent control completely -- and it hid it
        // precisely when it was most likely to be looked for, because a fresh install, or one
        // whose stored bins predate per-band histograms, has nothing to contribute yet. Consent is
        // a decision about what happens from now on, not about what happens to be in the buffer
        // this minute, so it is always reachable and the app simply sends nothing until there is
        // something to send.
        if (records == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Nothing to send yet — areas appear once the app has measured one place for long " +
                    "enough to say something about it. You can still switch contributing on; it " +
                    "will start sending when there is something.",
                color = T.Faint, fontSize = 11.5.sp, lineHeight = 16.sp
            )
        }

        if (records != 0) {
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

        }

        Spacer(Modifier.height(11.dp))

        // The consent control. One decision, honoured until withdrawn -- asking before every
        // upload would train people to dismiss exactly the prompt they should read.
        if (!sharing && !confirming) {
            Btn("Contribute automatically") { confirming = true }
        } else if (confirming) {
            Text(
                "Your measurements will be sent about once a day, on Wi-Fi only, for as long as " +
                    "this stays on. You can turn it off at any time.",
                color = T.Dim, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Turning it off later stops anything new being sent. What you have already " +
                    "contributed stays in the shared map \u2014 once it is mixed with other " +
                    "people's it cannot be pulled back out.",
                color = T.Warn, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(9.dp))
            Btn("Yes, contribute") {
                ShareConsent.setEnabled(ctx, true)
                sharing = true; confirming = false
                note = "Sending…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Uploader.uploadNow(ctx) }
                    lastUpload = ShareConsent.lastUpload(ctx)
                    lastError = ShareConsent.lastError(ctx)
                    note = if (ok) "Sent. It will send again about once a day."
                           else "Not sent: " + (lastError ?: "unknown reason")
                }
            }
            Spacer(Modifier.height(6.dp))
            Btn("Not now", ghost = true) { confirming = false }
        } else {
            Text(
                when {
                    lastUpload > 0 -> "Contributing. Last sent " + ago(lastUpload) + "."
                    records == 0 -> "Contributing. Waiting for something to send."
                    else -> "Contributing. Nothing sent yet."
                },
                color = T.Good, fontSize = 12.sp, lineHeight = 16.sp
            )
            lastError?.takeIf { records != 0 }?.let {
                Spacer(Modifier.height(5.dp))
                // Shown rather than swallowed: a toggle that is on while nothing has ever been
                // sent is the quiet failure this project keeps writing rules against.
                Text("Last attempt failed: $it", color = T.Bad, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Btn("Stop contributing", ghost = true) {
                ShareConsent.setEnabled(ctx, false); sharing = false
                note = "Stopped. Nothing further will be sent."
            }
        }

        if (records != 0) {
        Spacer(Modifier.height(7.dp))
        // The manual path, kept alongside the toggle. Sending the file yourself is how you check
        // what a contribution actually contains, and how someone who will not switch on automatic
        // sharing can still hand over a one-off.
        Btn("Share my measurements", ghost = true) {
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
        append("area          network    band                  samples  week\n")
        val show = minOf(recs.length(), 6)
        for (i in 0 until show) {
            val r = recs.getJSONObject(i)
            // The area id is a lattice cell, not a coordinate, and is shown truncated because its
            // full 19 digits invite the reader to think it is more precise than ~460 m.
            val area = r.optLong("area").toString().takeLast(6)
            append(
                "…%-8s  %-9s  %-20s  %-7d  %s\n".format(
                    area,
                    Networks.name(r.optString("network")).take(9),
                    Networks.band(r.optString("band")).take(18),
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

/** "3 hours ago" -- coarse on purpose; the exact second of an upload is nobody's business. */
private fun ago(wall: Long): String {
    val m = (System.currentTimeMillis() - wall) / 60_000
    return when {
        m < 2 -> "just now"
        m < 60 -> "$m minutes ago"
        m < 48 * 60 -> "${m / 60} hours ago"
        else -> "${m / 1440} days ago"
    }
}
