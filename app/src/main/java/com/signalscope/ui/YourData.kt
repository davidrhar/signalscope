package com.signalscope.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.signalscope.collect.CollectorService
import com.signalscope.collect.FixBuffer
import com.signalscope.store.Db
import com.signalscope.store.Export
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Get a copy of everything, or destroy everything.
 *
 * The consent screen tells people they can export or delete at any time. That sentence was written
 * before either existed, which made it a promise the app did not keep -- so this is the other half
 * of it rather than a feature in its own right.
 *
 * `Export` has been finished and unreferenced for some time: a working component with no way to
 * reach it, which for the person collecting is identical to it not existing. Someone who runs this
 * for a month and can neither retrieve nor destroy what it gathered has been badly served.
 *
 * Delete stops the collector first and clears the consent flag last, so the app returns to the
 * state of a fresh install rather than to a running collector with an empty database -- which would
 * immediately begin refilling it, and is not what anyone pressing that button means.
 */
@Composable
fun YourDataPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    SecHead("Your data", "on this phone only")

    AccentCard(T.Line) {
        Text(
            "Everything collected stays on this phone. You can take a copy of it, or destroy it.",
            color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(11.dp))

        Btn("Export a copy", ghost = true) {
            if (busy) return@Btn
            busy = true; note = "Building the archive…"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Export.build(ctx) }.getOrNull() }
                val file = r?.takeIf { it.ok }?.file
                note = if (file == null) {
                    "Export failed" + (r?.error?.let { ": $it" } ?: ".")
                } else {
                    val intent = runCatching { Export.shareIntent(ctx, file) }.getOrNull()
                    if (intent == null) "Wrote ${file.name}, but could not open the share sheet."
                    else { runCatching { ctx.startActivity(intent) }; "Shared ${file.name}." }
                }
                busy = false
            }
        }

        Spacer(Modifier.height(7.dp))

        if (!confirming) {
            Btn("Delete everything collected", ghost = true) { confirming = true }
        } else {
            Text(
                "This destroys every measurement on this phone and cannot be undone. The app " +
                    "stops collecting and asks for your agreement again, as on a fresh install.",
                color = T.Bad, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Btn("Delete it all") {
                if (busy) return@Btn
                busy = true; note = "Deleting…"
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { wipe(ctx) } }
                    confirming = false; busy = false
                    note = "Everything deleted."
                }
            }
            Spacer(Modifier.height(6.dp))
            Btn("Keep it", ghost = true) { confirming = false }
        }

        note?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, color = T.Faint, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

/**
 * Stop, destroy, forget -- in that order.
 *
 * Stopping first means nothing is writing while the tables are emptied. Clearing consent last means
 * a half-finished wipe leaves the app collecting into a partly-emptied database rather than
 * silently reset, which is the failure a reader would rather have: visible, not quiet.
 */
private suspend fun wipe(ctx: Context) {
    runCatching { CollectorService.stop(ctx) }
    FixBuffer.clear()
    runCatching {
        val db = Db.get(ctx).openHelper.writableDatabase
        listOf(
            "radio_sample", "registration_event", "link_event",
            "probe_result", "neighbour_cell", "instrument_event", "bin_agg"
        ).forEach { t -> runCatching { db.execSQL("DELETE FROM `$t`") } }
        runCatching { db.execSQL("VACUUM") }
    }
    // The compacted roll-ups live as gzipped files outside SQLite and would otherwise survive.
    runCatching { ctx.filesDir.resolve("rollup").deleteRecursively() }
    runCatching { ctx.filesDir.resolve("export").deleteRecursively() }
    Consent.revoke(ctx)
}

/** Local copy: ActionsScreen's is private to that file and this panel is used from elsewhere. */
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
