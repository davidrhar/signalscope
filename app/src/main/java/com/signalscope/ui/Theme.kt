package com.signalscope.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Tokens lifted verbatim from mockups/app-mockup.html so the app and the mockup agree. */
object T {
    val Page      = Color(0xFF07090D)
    val Surface   = Color(0xFF151A22)
    val Surface2  = Color(0xFF1B212B)
    val Surface3  = Color(0xFF222936)
    val Line      = Color(0xFF242C38)
    val LineSoft  = Color(0xFF1B222C)

    val Text      = Color(0xFFE9EDF4)
    val Dim       = Color(0xFF8E99AB)
    val Faint     = Color(0xFF5C6677)

    val Brand     = Color(0xFF4DA3FF)
    val Good      = Color(0xFF3DDC97)
    val Warn      = Color(0xFFFFB648)
    val Bad       = Color(0xFFFF6B6B)
    val Nr        = Color(0xFFB388FF)

    val CardRadius = 16.dp
}

/**
 * Quality ramps.
 *
 * Cut-points are NOT hardcoded. Power thresholds come from the carrier's own CarrierConfig for
 * that subscription, so a reading is judged on the scale the phone itself uses; where the
 * platform will not supply them to a normal app we fall back to 3GPP-derived defaults and the UI
 * says so. Quality thresholds (SINR) are deliberately independent of the carrier, because the
 * carrier's scale is the thing this project exists to check rather than to trust.
 */
object Q {
    /** level 0..4 from four ascending cut-points, the same way the platform derives the bar */
    private fun level(v: Int?, t: List<Int>): Int? {
        if (v == null) return null
        var lvl = 0
        t.forEach { if (v >= it) lvl++ }
        return lvl.coerceIn(0, 4)
    }

    private fun frac(v: Int?, lo: Float, hi: Float) =
        v?.let { ((it - lo) / (hi - lo)).coerceIn(0f, 1f) } ?: 0f

    private fun byLevel(l: Int?) = when (l) {
        null -> T.Dim
        4, 3 -> T.Good
        2 -> T.Warn
        else -> T.Bad
    }

    fun rsrpFrac(v: Int?, t: List<Int>) = frac(v, (t.first() - 10).toFloat(), (t.last() + 5).toFloat())
    fun rsrqFrac(v: Int?, t: List<Int>) = frac(v, (t.first() - 3).toFloat(), (t.last() + 3).toFloat())
    /** SINR is fixed-scale on purpose: it is the independent check on the carrier's own scale. */
    fun sinrFrac(v: Int?) = frac(v, -5f, 25f)

    fun rsrpColor(v: Int?, t: List<Int>) = byLevel(level(v, t))
    fun rsrqColor(v: Int?, t: List<Int>) = byLevel(level(v, t))

    fun sinrColor(v: Int?) = when {
        v == null -> T.Dim
        v >= 13 -> T.Good
        v >= 0 -> T.Warn
        else -> T.Bad
    }

    /** carrier's own bar level for this reading, for the side-by-side comparison */
    fun carrierLevel(v: Int?, t: List<Int>) = level(v, t)

    /** which measurements the carrier feeds into its bar; bit 0 RSRP, 1 RSRQ, 2 RSSNR */
    fun barInputs(mask: Int?): String = when (mask) {
        null -> "unknown"
        else -> listOfNotNull(
            if (mask and 1 != 0) "RSRP" else null,
            if (mask and 2 != 0) "RSRQ" else null,
            if (mask and 4 != 0) "SINR" else null
        ).joinToString("+").ifEmpty { "none" }
    }

    /**
     * Timing advance to distance. The unit differs by RAT -- LTE is ~78 m per step; NR depends on
     * subcarrier spacing and is not a single constant, so we decline to convert rather than
     * print a number that is wrong.
     */
    fun taMetres(ta: Int?, rat: String): Int? =
        if (ta == null) null else if (rat.startsWith("LTE")) ta * 78 else null
}
