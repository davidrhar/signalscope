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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.AreaState
import com.signalscope.collect.SimState
import com.signalscope.store.SiteHistory

/**
 * The answer, in plain words, at the top of the screen the app opens on.
 *
 * ## Why this replaced two cards
 *
 * The verdict used to be two separate things -- one card for where you are now, another for what
 * this mast has done before -- and both sat BELOW the hero, and both rendered only when the news
 * was bad. So the app opened on RSRP, RSRQ and SINR, and somebody in a good area was told nothing
 * about areas at all. The question this whole project exists to answer, "is it me?", was not
 * answered on the screen you land on.
 *
 * Now and before are one answer anyway. "It is poor here, and it has been poor here before" is a
 * sentence; splitting it across two cards made the reader assemble it.
 *
 * ## Why it is never silent
 *
 * Everywhere else in this app, silence is correct: a mast with too little history says nothing
 * rather than guessing. That is wrong for the first thing on the first screen, where silence
 * reads as the app having no opinion. So this always says something, including "still measuring"
 * and "no record of this spot yet" -- which are both facts, and neither is a guess.
 *
 * ## What it does not do
 *
 * It does not round in its own favour. The grade comes from [AreaState.Ui.grade], which follows
 * the measurement rather than the hysteresis that governs when the notification speaks, and every
 * claim carries the share it was computed from.
 */
@Composable
fun HereCard(s: SimState) {
    val ctx = LocalContext.current
    val area by AreaState.state.collectAsStateWithLifecycle()

    val rat = s.cellRat ?: s.rat
    var past by remember(s.ci, s.plmn, s.band) { mutableStateOf<SiteHistory.Verdict?>(null) }
    LaunchedEffect(s.ci, rat, s.plmn, s.band) {
        past = SiteHistory.forCell(ctx, s.ci, rat, s.plmn, s.band)
    }

    val tone = when (area.grade) {
        AreaState.Grade.BAD -> T.Bad
        AreaState.Grade.MARGINAL -> T.Warn
        AreaState.Grade.GOOD -> T.Good
        AreaState.Grade.UNKNOWN -> T.Line
    }
    val headline = when (area.grade) {
        AreaState.Grade.BAD -> "Data is unlikely to work here."
        AreaState.Grade.MARGINAL -> "Data may stall here."
        AreaState.Grade.GOOD -> "Data should work fine here."
        AreaState.Grade.UNKNOWN -> "Still measuring."
    }

    SecHead("Here", "measured on this phone")
    AccentCard(tone) {
        Text(
            headline,
            color = T.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp
        )
        Spacer(Modifier.height(7.dp))

        // Now. The share is always given, because "unlikely to work" is a claim and this is the
        // evidence for it -- and because a reader who disagrees should be able to see why.
        Text(
            area.share?.let {
                "${pct(it)} of the last ten minutes was below the level where calls and " +
                    "video stop working."
            } ?: "A verdict needs about ten minutes of readings. Nothing is guessed at before then.",
            color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp
        )

        // Before. Said in both directions: that a place has been fine here before is as much an
        // answer to "is it me?" as that it has been poor.
        Spacer(Modifier.height(7.dp))
        val p = past
        Text(
            when {
                p == null -> "No record of this spot yet — this is the first time, or close to it."
                p.isBad -> "You have been here before, and it was poor. Around this time of day, " +
                    "${pct(p.badFraction)} of readings on mast ${p.site}" +
                    (p.band?.let { " band $it" } ?: "") + " have been below that level, " +
                    "from ${p.samples} readings."
                else -> "You have been here before. Around this time of day, " +
                    "${pct(p.badFraction)} of readings on mast ${p.site}" +
                    (p.band?.let { " band $it" } ?: "") + " have been below that level, " +
                    "from ${p.samples} readings."
            },
            color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp
        )

        // The limit, and only where it is useful. Saying "nothing can choose a mast" to somebody
        // whose connection is fine is noise; saying it to somebody whose connection is not is the
        // difference between an explanation and a reproach.
        if (area.grade == AreaState.Grade.BAD || (p?.isBad == true)) {
            Spacer(Modifier.height(7.dp))
            Text(
                "Nothing on the phone can choose a different mast. Moving even a short distance " +
                    "may hand you to another one, and Wi-Fi avoids it entirely.",
                color = T.Faint, fontSize = 11.5.sp, lineHeight = 16.sp
            )
        }
    }
}

private fun pct(f: Double) = "%.0f %%".format(f * 100)
