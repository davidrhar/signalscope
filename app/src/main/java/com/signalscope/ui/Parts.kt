package com.signalscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

private val Mono = FontFamily.Monospace

/** mono, uppercase, letterspaced section header with an optional right-hand aux note */
@Composable
fun SecHead(title: String, aux: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            title.uppercase(), color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f)
        )
        if (aux != null) Text(aux, color = T.Faint, fontSize = 10.sp, fontFamily = Mono)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(T.CardRadius))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(T.CardRadius))
            .padding(14.dp),
        content = content
    )
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.11f))
            .border(1.dp, color.copy(alpha = 0.26f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
    }
}

/** metric tile: label, big mono value, and a quality bar underneath */
@Composable
fun Metric(label: String, value: String, unit: String, frac: Float, color: Color, mod: Modifier) {
    Column(
        mod.clip(RoundedCornerShape(11.dp))
            .background(Color(0x6B07090D))
            .border(1.dp, Color(0x0EFFFFFF), RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(label, color = T.Faint, fontSize = 9.sp, fontFamily = Mono, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 19.sp, fontFamily = Mono,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(unit, color = T.Faint, fontSize = 9.sp,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(Color(0x14FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(color))
        }
    }
}

/** one cell of the 3-column identity grid */
@Composable
fun KvCell(k: String, v: String, mod: Modifier, vColor: Color = T.Text) {
    Column(mod.padding(end = 8.dp)) {
        Text(k.uppercase(), color = T.Faint, fontSize = 9.sp, fontFamily = Mono, letterSpacing = 0.8.sp)
        Text(v, color = vColor, fontSize = 12.sp, fontFamily = Mono,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** evidence row: fixed-width mono key, free-text value */
@Composable
fun ERow(k: String, v: String, vColor: Color = T.Dim) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp)) {
        Text(k, color = T.Faint, fontSize = 10.sp, fontFamily = Mono,
            maxLines = 1, modifier = Modifier.width(96.dp).padding(end = 8.dp))
        Text(v, color = vColor, fontSize = 10.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) T.Brand.copy(alpha = 0.13f) else T.Surface)
            .border(1.dp, if (selected) T.Brand.copy(alpha = 0.35f) else T.Line,
                RoundedCornerShape(999.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (selected) T.Brand else T.Dim, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.LineSoft).padding(vertical = 0.dp))
}

/* ------------------------------------------------------------------ plain language
 *
 * The rendering half of [PlainLanguage]. It lives here rather than in one screen because the same
 * verdict is shown in more than one place and the two must not drift apart -- a sentence that
 * reads as a problem on one tab and as a detail on another is worse than either.
 */

/**
 * Accent for a tone. ABSENT is deliberately the faintest thing available: a measurement that does
 * not exist must never draw the eye the way one that does. It is also the only tone with no colour
 * of its own, so an empty panel cannot be mistaken for a green one.
 */
fun toneColor(t: PlainLanguage.Tone): Color = when (t) {
    PlainLanguage.Tone.GOOD -> T.Good
    PlainLanguage.Tone.NEUTRAL -> T.Dim
    PlainLanguage.Tone.WATCH -> T.Warn
    PlainLanguage.Tone.BAD -> T.Bad
    PlainLanguage.Tone.ABSENT -> T.Faint
}

/** The tag is a word, not a grade: "not measured" is a legitimate answer and is labelled as one. */
private fun toneTag(v: PlainLanguage.Verdict): String = when {
    !v.measured -> "NOT MEASURED"
    v.tone == PlainLanguage.Tone.GOOD -> "WORKING"
    v.tone == PlainLanguage.Tone.WATCH -> "WORTH KNOWING"
    v.tone == PlainLanguage.Tone.BAD -> "PROBLEM FOUND"
    else -> "MEASURED"
}

/** The plain sentence. Big enough to be the message, which is the whole point of this layer. */
@Composable
fun Lede(text: String, color: Color = T.Text) {
    Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp, lineHeight = 21.sp)
}

/** Supporting sentences. Still plain language, never the numbers. */
@Composable
fun Prose(text: String, color: Color = T.Dim) {
    Text(text, color = color, fontSize = 11.5.sp, lineHeight = 16.5.sp)
}

/**
 * What would turn an absent measurement into an answer.
 *
 * Mandatory wherever data is missing. "No data" on its own is a dead end that reads, to most
 * people, as "nothing to worry about"; this is the line that stops that.
 */
@Composable
fun ToMeasure(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Text("WHAT WOULD MEASURE IT", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(3.dp))
        Text(text, color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

/**
 * One verdict: the sentence first, the measurement underneath for whoever wants it.
 *
 * The order is load-bearing. Leading with a number is how a diagnostic ends up being read only by
 * the person who wrote it.
 */
@Composable
fun VerdictBlock(v: PlainLanguage.Verdict, tag: Boolean = true) {
    val c = toneColor(v.tone)
    if (tag) {
        Tag(toneTag(v), c)
        Spacer(Modifier.height(8.dp))
    }
    Lede(v.headline, if (v.measured) T.Text else T.Dim)
    if (v.body.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Prose(v.body)
    }
    v.toMeasure?.let { ToMeasure(it) }
    if (v.numbers.isNotEmpty()) {
        Spacer(Modifier.height(9.dp))
        Divider()
        Spacer(Modifier.height(5.dp))
        v.numbers.forEach { (k, value) -> ERow(k, value) }
    }
}

/**
 * A card that carries a verdict's colour. Same construction as the existing heroes -- a wash of
 * the accent over the surface -- so a panel added later does not look like it came from elsewhere.
 */
@Composable
fun AccentCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(T.CardRadius))
            .background(
                Brush.linearGradient(
                    0f to accent.copy(alpha = 0.12f),
                    0.62f to Color.Transparent
                )
            )
            .background(T.Surface.copy(alpha = 0.6f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(T.CardRadius))
            .padding(14.dp),
        content = content
    )
}

/**
 * A verbatim block for a measurement's own report.
 *
 * [CellProbe.asymSummary] and [WarmthExperiment.summary] both write their own verdicts, and those
 * strings are the ones that were argued over. Showing them unedited underneath the plain sentence
 * is how a reader -- or a reviewer -- can check that the sentence above is a fair rendering.
 */
@Composable
fun RawReport(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label.uppercase(), color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF080B10))
                .border(1.dp, T.LineSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp)
        ) {
            Text(text, color = T.Dim, fontSize = 9.5.sp, fontFamily = Mono, lineHeight = 14.sp)
        }
    }
}
