package com.signalscope.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily

/**
 * What someone agrees to before this app records anything.
 *
 * ## Why it is one screen
 *
 * It was drafted as two -- what the app does, then what that means about the person running it.
 * Splitting them lets someone accept the capability and meet the consequence separately, which is
 * the shape of a flow designed to get a yes. Here the cost sits directly under the benefit and both
 * are read in one pass.
 *
 * ## Why the decline says Uninstall
 *
 * The app does nothing without collection. "Not now" left people holding a dead icon and a vague
 * sense they had declined something. An app cannot remove itself silently, so this opens Android's
 * own uninstall dialog; backing out of that lands here again, which is correct.
 *
 * ## The battery figure is measured, not guessed
 *
 * "A little battery" was the original wording and it was wrong. It is now stated as a range,
 * because two measurements on the same device differ by more than three times and both are real:
 *
 *   2026-09  231 mAh over 11h 14m  = 20.6 mAh/h = 0.41 %/h  ~ 10 % of a 5000 mAh battery a day
 *   2026-10   14.6 mAh over 2h 22m =  6.2 mAh/h = 0.12 %/h  ~  3 % a day
 *
 * The gap is not an error in either. The first was a day of travelling on poor signal, which is
 * when this app works hardest: more cell changes, more probing, more time with the radio awake.
 * The second was a stationary phone at home on Wi-Fi -- where, additionally, most connection tests
 * were being refused at the bind and therefore never ran at all, which flatters the figure.
 *
 * So the honest statement is a range with its cause attached, rather than a single number that
 * will be wrong for most people most of the time. Quoting the low end would understate it for
 * exactly the person who most needs to know; quoting the high end would scare off someone whose
 * phone sits on a desk.
 *
 * Re-measure with:
 * `adb shell "dumpsys batterystats --charged | grep 'UID u0aNNN'"` against "Time on battery".
 *
 * ## What it does not claim
 *
 * Position is no longer stored -- `map_fix` is gone and fixes live in memory only -- so this screen
 * does not describe a location history, because there is not one -- which stopped being true
 * when bin_agg and bin_hour were added, and the screen now says so instead. What it does still say is that
 * serving-cell identity is recorded against time, arrives with no location permission at all, and
 * is a record of movement in its own right. That is the sentence a reader is least likely to
 * already know and most likely to want.
 */
private val Mono = FontFamily.Monospace

object Consent {

    private const val PREFS = "consent"
    private const val KEY_ACCEPTED = "accepted_v1"

    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun accept(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACCEPTED, true).apply()
    }

    /** Clears acceptance, so the screen is shown again. Used when everything is deleted. */
    fun revoke(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACCEPTED, false).apply()
    }

    fun uninstall(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(T.Page)
            // Without this the decline button sits under the system navigation bar: reachable by
            // scrolling, but the only visible choice on first paint is the one that says yes.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 26.dp)
    ) {
        Text(
            "SIGNALSCOPE",
            color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "This app analyses your phone's radio.",
            color = T.Text, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(14.dp))
        Body("It records which mast you're on, how strong and how clean the signal is, and " +
            "whether data gets through \u2014 about once a minute, in the background. That is how " +
            "it can tell you why calls drop, instead of showing bars that say everything is fine " +
            "while nothing loads.")

        Spacer(Modifier.height(8.dp))
        Text(
            "WHAT THAT MEANS ABOUT YOU",
            color = T.Dim, fontSize = 9.5.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(9.dp))
        Body("It records which mast served you at each moment \u2014 a record of your movements at " +
            "the scale of a neighbourhood. That happens whether or not you grant location " +
            "permission, because Android reports the mast to any app with phone permission.")
        // All three clauses here were wrong, which a red-team pass established line by line.
        // "Your position is never stored" was true of coordinates and false of the 66 m bins and
        // the place-by-hour table. "Nothing is uploaded anywhere" was contradicted by the sharing
        // feature, the map-tile fetches and the speed test. "Detailed records are deleted after
        // 30 days" described a compaction, not a deletion. This is the screen a reviewer and a
        // court read as the consent; it has to be the most accurate text in the app rather than
        // the most reassuring.
        Body("No coordinate is ever stored. What is kept is a coarse area — about 460 m across " +
            "for anything shared, finer on this phone only — and which mast served you, against " +
            "the hour of the day.")
        Body("Nothing leaves this phone unless you switch sharing on, which is off by default. " +
            "The app does fetch map tiles, and runs a small speed test, both over the internet.")
        Body("Detailed records are compacted after 30 days: cell identity and timing advance are " +
            "removed, and what remains is a summary rather than the original readings. You can " +
            "export everything, or delete all of it, at any time.")
        Body("Collecting costs somewhere between about 3% and 10% of a full battery a day — " +
            "nearer the low end on a phone that stays put, nearer the high end while travelling " +
            "on poor signal, which is when it has the most to measure — and up to about 1 MB of " +
            "mobile data. It runs constantly, so it is one of the heavier apps on a phone.")

        Spacer(Modifier.height(18.dp))
        Button("Start collecting", primary = true, onClick = onAccept)
        Spacer(Modifier.height(8.dp))
        Button("Uninstall", primary = false, onClick = onDecline)
        Spacer(Modifier.height(9.dp))
        Text(
            "You can stop, export or delete everything at any time.",
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 14.sp
        )
    }
}

@Composable
private fun Body(s: String) {
    Text(s, color = T.Dim, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun Button(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(if (primary) T.Brand else T.Surface2)
            .clickableNoRipple(onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) T.Page else T.Dim,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
}
