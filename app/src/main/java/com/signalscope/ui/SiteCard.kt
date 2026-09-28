package com.signalscope.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalscope.collect.SimState
import com.signalscope.store.SiteHistory

/**
 * "You are on a mast that has been poor for you at this time of day."
 *
 * ## What this is for
 *
 * Nothing on a phone can choose a mast -- band and cell selection need a UICC-signed carrier app --
 * so this cannot offer a fix. What it can do is answer the question the user is actually asking
 * while a call breaks up, which is *is it me?*. The answer here is no, this mast, at this hour,
 * has done this to you before, and it is measured rather than guessed.
 *
 * ## Why it stays quiet most of the time
 *
 * Shown only when the site has real history at this time of day and that history is bad. A site
 * with too few readings says nothing -- silence, not reassurance -- and a site that is fine says
 * nothing either, because a badge that is always present is furniture within a week.
 *
 * The verdict is per time of day for the same reason. On the reference device one mast put 94 % of
 * its morning readings below usable SINR and 2 % of its evening ones. A warning keyed on the mast
 * alone would have been wrong about that site two thirds of the day.
 */
@Composable
fun SiteCard(s: SimState) {
    val ctx = LocalContext.current
    var verdict by remember(s.ci, s.plmn) { mutableStateOf<SiteHistory.Verdict?>(null) }

    // Recomputed when the serving cell changes, not on every frame: it is a scan of up to a
    // month of rows and the answer cannot change while the phone stays on one cell.
    LaunchedEffect(s.ci, s.rat, s.plmn) {
        verdict = SiteHistory.forCell(ctx, s.ci, s.rat, s.plmn)
    }

    val v = verdict ?: return
    if (!v.isBad) return

    SecHead("This mast", "from your own readings")
    AccentCard(T.Bad) {
        Text(
            "You have been here before, and it was poor.",
            color = T.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "Around this time of day, ${pct(v.badFraction)} of the readings this phone has taken " +
                "on mast ${v.site} were below the level where calls and video stop working — " +
                "${v.samples} readings over the last month." +
                (v.medianRsrq?.let {
                    " Signal quality here runs about $it dB, where an uncontended cell reads −3."
                } ?: ""),
            color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp
        )
        v.betterPart?.let { part ->
            Spacer(Modifier.height(7.dp))
            Text(
                "The same mast is usually fine ${part}" +
                    (v.betterFraction?.let { " — ${pct(it)} bad then" } ?: "") + ".",
                color = T.Good, fontSize = 12.5.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(7.dp))
        // The honest limit, stated where the claim is made. No app can pick a mast, and implying
        // otherwise would be the one thing worse than saying nothing.
        Text(
            "Nothing on the phone can choose a different mast. Moving even a short distance may " +
                "hand you to another one, and Wi-Fi avoids it entirely.",
            color = T.Faint, fontSize = 11.5.sp, lineHeight = 16.sp
        )
    }
}

private fun pct(f: Double) = "%.0f %%".format(f * 100)
