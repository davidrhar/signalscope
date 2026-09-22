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
 * "A little battery" was the original wording and it was wrong. `dumpsys batterystats` on the
 * reference device put the foreground service at **231 mAh over 11h 14m** -- 20.6 mAh an hour, or
 * about 0.41% an hour of its 5000 mAh battery, which is roughly a tenth of a full charge a day and
 * made this the third-heaviest app on the phone. Someone told "a little" and then losing 10% a day
 * has been misled about the only cost they can feel.
 *
 * One device, one 11-hour window, on a day of unusually heavy use, so the figure is stated as
 * "roughly" and rounded down rather than presented as precise. Re-measure with:
 * `adb shell "dumpsys batterystats | sed -n '/Estimated power use/,/^$/p' | grep u0aNNN"`.
 *
 * ## What it does not claim
 *
 * Position is no longer stored -- `map_fix` is gone and fixes live in memory only -- so this screen
 * does not describe a location history, because there is not one. What it does still say is that
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
        Body("Your position is never stored. Nothing is uploaded anywhere. Detailed records are " +
            "deleted after 30 days.")
        Body("Collecting costs roughly 10% of a full battery a day, and up to about 1 MB of " +
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
