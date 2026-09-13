package com.signalscope.store

/**
 * Band from the channel number, per 3GPP, rather than from whatever the platform chose to report.
 *
 * ## Why this exists
 *
 * The collector used to store `CellIdentity.getBands()[0]`. Three things were wrong with that, and
 * the review that raised them was checked against the source before anything here was written:
 *
 *  - **`getBands()` is optional.** A modem may return an empty array, in which case the row has no
 *    band at all even though the channel number sitting beside it states the band exactly.
 *  - **LTE B40 and NR n40 are both the integer 40.** Stored bare, a map bin or a roll-up keyed on
 *    band merges two different radio technologies on two different rasters.
 *  - **NR was printed "B40".** The "n" prefix is not decoration; it is how 3GPP distinguishes the
 *    two band tables, and anyone reading an export would take "B40" as LTE.
 *
 * So the band is derived here from the channel, stored beside what the platform reported, and the
 * two are compared. A disagreement is itself a finding -- it is flagged, never silently resolved in
 * either direction.
 *
 * ## LTE: the channel number is unique
 *
 * TS 36.101 Table 5.7.3-1 gives every E-UTRA band a disjoint EARFCN range. Frequencies overlap
 * between bands (B30's 2350-2360 MHz sits inside B40's 2300-2400 MHz; B65 contains B1) but the
 * *numbers* never do, so an EARFCN names exactly one band and no PLMN is needed to decide it.
 * `F_DL = F_DL_low + 0.1 * (N_DL - N_Offs-DL)`.
 *
 * ## NR: the channel number is a frequency, and bands overlap on it
 *
 * NR-ARFCN (TS 38.104 s5.4.2.1) is a single global raster: it encodes a frequency and says nothing
 * about the band. Where band definitions overlap in frequency (TS 38.101-1 Table 5.2-1 and
 * 38.101-2), one NR-ARFCN is legitimately inside several bands -- 2355 MHz is both n30 and n40,
 * 3500 MHz both n77 and n78. The channel alone cannot decide it; where the network is deployed can.
 *
 * The rule, which is general rather than a lookup of any one country:
 *
 *  1. Collect every band whose downlink range contains the frequency.
 *  2. Several bands are specified for, and only deployed in, ITU Region 2 (the Americas): the WCS,
 *     AWS, PCS-extension, 600 MHz, CBRS and US 700 MHz families. Region is read from the MCC's
 *     first digit, which ITU-T E.212 assigns geographically -- 3xx North America and the
 *     Caribbean, 7xx Central and South America. Inside Region 2 those bands are preferred; outside
 *     it they are excluded, because no regulator outside the Americas has assigned them.
 *  3. What remains is ordered by a fixed preference that puts the general band before its
 *     narrower overlay (n40 before n30, n41 before n90, n78 before n77 outside Region 2).
 *  4. If more than one candidate survived step 2, the answer is marked [Derived.ambiguous]. It is
 *     still the best available answer, but a reader must be able to tell it was a choice.
 *
 * With no MCC at all, step 2 is skipped rather than guessed, and the result is ambiguous whenever
 * more than one band fits. Unknown location is not evidence for either region.
 *
 * Pure Kotlin, no Android types, so it can be exercised off-device.
 */
object Bands {

    const val LTE = "LTE"
    const val NR = "NR"

    data class Derived(
        /** `LTE` or `NR`: which band table [band] belongs to. A bare number means nothing without it. */
        val rat: String,
        val band: Int,
        /** Downlink centre frequency of the channel, MHz, rounded to 0.01 kHz precision. */
        val dlMhz: Double,
        /** More than one band contained this channel after regional resolution. */
        val ambiguous: Boolean,
        /** Every band that contained the channel before resolution, for diagnosis. */
        val candidates: List<Int>
    ) {
        val label: String get() = if (rat == NR) "n$band" else "B$band"
    }

    /** "B40" for LTE, "n40" for NR, the 3GPP spellings. Null band gives null, never "B0". */
    fun label(rat: String?, band: Int?): String? = when {
        band == null -> null
        rat == NR -> "n$band"
        else -> "B$band"
    }

    /**
     * Sorts band labels by technology then number, so "B3,B8,B40,n78" rather than the lexical
     * "B3,B40,B8,n78" -- a lexical sort of numbers reads as a different list.
     */
    fun sortLabels(labels: Collection<String>): List<String> =
        labels.sortedWith(compareBy<String>({ if (it.startsWith("n")) 1 else 0 },
            { it.drop(1).toIntOrNull() ?: Int.MAX_VALUE }, { it }))

    // =============================================================================================
    //  LTE
    // =============================================================================================

    /** One E-UTRA band's downlink EARFCN range, TS 36.101 Table 5.7.3-1. */
    private class LteBand(val band: Int, val fDlLow: Double, val nOffs: Int, val nLast: Int)

    private val LTE_BANDS = listOf(
        // FDD
        LteBand(1, 2110.0, 0, 599), LteBand(2, 1930.0, 600, 1199),
        LteBand(3, 1805.0, 1200, 1949), LteBand(4, 2110.0, 1950, 2399),
        LteBand(5, 869.0, 2400, 2649), LteBand(6, 875.0, 2650, 2749),
        LteBand(7, 2620.0, 2750, 3449), LteBand(8, 925.0, 3450, 3799),
        LteBand(9, 1844.9, 3800, 4149), LteBand(10, 2110.0, 4150, 4749),
        LteBand(11, 1475.9, 4750, 4949), LteBand(12, 729.0, 5010, 5179),
        LteBand(13, 746.0, 5180, 5279), LteBand(14, 758.0, 5280, 5379),
        LteBand(17, 734.0, 5730, 5849), LteBand(18, 860.0, 5850, 5999),
        LteBand(19, 875.0, 6000, 6149), LteBand(20, 791.0, 6150, 6449),
        LteBand(21, 1495.9, 6450, 6599), LteBand(22, 3510.0, 6600, 7399),
        LteBand(23, 2180.0, 7500, 7699), LteBand(24, 1525.0, 7700, 8039),
        LteBand(25, 1930.0, 8040, 8689), LteBand(26, 859.0, 8690, 9039),
        LteBand(27, 852.0, 9040, 9209), LteBand(28, 758.0, 9210, 9659),
        LteBand(29, 717.0, 9660, 9769), LteBand(30, 2350.0, 9770, 9869),
        LteBand(31, 462.5, 9870, 9919), LteBand(32, 1452.0, 9920, 10359),
        // TDD
        LteBand(33, 1900.0, 36000, 36199), LteBand(34, 2010.0, 36200, 36349),
        LteBand(35, 1850.0, 36350, 36949), LteBand(36, 1930.0, 36950, 37549),
        LteBand(37, 1910.0, 37550, 37749), LteBand(38, 2570.0, 37750, 38249),
        LteBand(39, 1880.0, 38250, 38649), LteBand(40, 2300.0, 38650, 39649),
        LteBand(41, 2496.0, 39650, 41589), LteBand(42, 3400.0, 41590, 43589),
        LteBand(43, 3600.0, 43590, 45589), LteBand(44, 703.0, 45590, 46589),
        LteBand(45, 1447.0, 46590, 46789), LteBand(46, 5150.0, 46790, 54539),
        LteBand(47, 5855.0, 54540, 55239), LteBand(48, 3550.0, 55240, 56739),
        LteBand(49, 3550.0, 56740, 58239), LteBand(50, 1432.0, 58240, 59089),
        LteBand(51, 1427.0, 59090, 59139), LteBand(52, 3300.0, 59140, 60139),
        LteBand(53, 2483.5, 60140, 60254),
        // FDD, extended EARFCN space
        LteBand(65, 2110.0, 65536, 66435), LteBand(66, 2110.0, 66436, 67335),
        LteBand(67, 738.0, 67336, 67535), LteBand(68, 753.0, 67536, 67835),
        LteBand(69, 2570.0, 67836, 68335), LteBand(70, 1995.0, 68336, 68585),
        LteBand(71, 617.0, 68586, 68935), LteBand(72, 461.0, 68936, 68985),
        LteBand(73, 460.0, 68986, 69035), LteBand(74, 1475.0, 69036, 69465),
        LteBand(75, 1432.0, 69466, 70315), LteBand(76, 1427.0, 70316, 70365),
        LteBand(85, 728.0, 70366, 70545), LteBand(87, 420.0, 70546, 70595),
        LteBand(88, 422.0, 70596, 70645)
    )

    /**
     * EARFCN to band. [mcc] is accepted for symmetry with [nr] and deliberately unused: the LTE
     * ranges are disjoint, so a PLMN could only ever override the correct answer with a wrong one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun lte(earfcn: Int?, mcc: String? = null): Derived? {
        if (earfcn == null || earfcn < 0) return null
        val b = LTE_BANDS.firstOrNull { earfcn >= it.nOffs && earfcn <= it.nLast } ?: return null
        val mhz = b.fDlLow + 0.1 * (earfcn - b.nOffs)
        return Derived(LTE, b.band, round2(mhz), ambiguous = false, candidates = listOf(b.band))
    }

    // =============================================================================================
    //  NR
    // =============================================================================================

    /** NR-ARFCN to downlink frequency, TS 38.104 s5.4.2.1 Table 5.4.2.1-1. Null off the raster. */
    fun nrArfcnToMhz(n: Int?): Double? = when {
        n == null || n < 0 -> null
        n <= 599_999 -> 0.005 * n
        n <= 2_016_666 -> 3000.0 + 0.015 * (n - 600_000)
        n <= 3_279_165 -> 24250.08 + 0.060 * (n - 2_016_667)
        else -> null
    }

    /**
     * One NR band's downlink range in MHz, and whether it exists only in ITU Region 2.
     *
     * Listed in PREFERENCE order, not numeric order: when several survive regional resolution the
     * first listed wins, and the order encodes "the general band before its overlay".
     */
    private class NrBand(
        val band: Int, val lo: Double, val hi: Double, val region2Only: Boolean = false,
        /**
         * A renumbering of an earlier-listed band over the same spectrum (n90 is n41 on a finer
         * raster). It can still be the answer, but it does not make the answer ambiguous -- the
         * two numbers describe the same carrier.
         */
        val renumbering: Boolean = false
    )

    private val NR_BANDS = listOf(
        // Sub-1 GHz
        NrBand(71, 617.0, 652.0, true), NrBand(105, 612.0, 652.0),
        NrBand(29, 717.0, 728.0, true), NrBand(12, 729.0, 746.0, true),
        NrBand(85, 728.0, 746.0, true), NrBand(67, 738.0, 758.0),
        NrBand(13, 746.0, 756.0, true), NrBand(14, 758.0, 768.0, true),
        NrBand(28, 758.0, 803.0), NrBand(20, 791.0, 821.0),
        NrBand(5, 869.0, 894.0), NrBand(26, 859.0, 894.0, true), NrBand(18, 860.0, 875.0),
        NrBand(100, 919.4, 925.0), NrBand(8, 925.0, 960.0),
        // 1.4-2.2 GHz
        NrBand(51, 1427.0, 1432.0), NrBand(76, 1427.0, 1432.0), NrBand(91, 1427.0, 1432.0),
        NrBand(93, 1427.0, 1432.0),
        NrBand(75, 1432.0, 1517.0), NrBand(50, 1432.0, 1517.0), NrBand(92, 1432.0, 1517.0),
        NrBand(94, 1432.0, 1517.0),
        NrBand(74, 1475.0, 1518.0), NrBand(24, 1525.0, 1559.0, true), NrBand(54, 1670.0, 1675.0),
        NrBand(3, 1805.0, 1880.0), NrBand(39, 1880.0, 1920.0), NrBand(101, 1900.0, 1910.0),
        NrBand(2, 1930.0, 1990.0, true), NrBand(25, 1930.0, 1995.0, true),
        NrBand(70, 1995.0, 2020.0, true), NrBand(34, 2010.0, 2025.0),
        NrBand(66, 2110.0, 2200.0, true), NrBand(1, 2110.0, 2170.0), NrBand(65, 2110.0, 2200.0),
        // 2.3-2.7 GHz
        NrBand(40, 2300.0, 2400.0), NrBand(30, 2350.0, 2360.0, true),
        NrBand(53, 2483.5, 2495.0),
        NrBand(41, 2496.0, 2690.0), NrBand(90, 2496.0, 2690.0, renumbering = true),
        NrBand(38, 2570.0, 2620.0), NrBand(7, 2620.0, 2690.0),
        // 3-7 GHz
        NrBand(48, 3550.0, 3700.0, true), NrBand(78, 3300.0, 3800.0), NrBand(77, 3300.0, 4200.0),
        NrBand(79, 4400.0, 5000.0), NrBand(46, 5150.0, 5925.0), NrBand(47, 5855.0, 5925.0),
        NrBand(102, 5925.0, 6425.0), NrBand(96, 5925.0, 7125.0, true), NrBand(104, 6425.0, 7125.0),
        // FR2
        NrBand(258, 24250.0, 27500.0), NrBand(257, 26500.0, 29500.0),
        NrBand(261, 27500.0, 28350.0, true), NrBand(260, 37000.0, 40000.0),
        NrBand(259, 39500.0, 43500.0), NrBand(262, 47200.0, 48200.0, true)
    )

    /**
     * Region 2 preference for bands that also exist outside it. Inside the Americas n77 is the
     * C-band allocation and n66 the AWS one, so where they overlap a global band those win; this
     * list is consulted only when the MCC places the network in Region 2.
     */
    private val REGION2_PREFERRED = setOf(77, 66, 25, 12, 14, 30, 71, 48)

    /** ITU Region 2 from the MCC's geographic first digit (ITU-T E.212). Null when unknown. */
    fun isRegion2(mcc: String?): Boolean? {
        val d = mcc?.trim()?.takeIf { it.length == 3 && it.all(Char::isDigit) }?.first() ?: return null
        return d == '3' || d == '7'
    }

    fun nr(nrarfcn: Int?, mcc: String? = null): Derived? {
        val mhz = nrArfcnToMhz(nrarfcn) ?: return null
        val all = NR_BANDS.filter { mhz >= it.lo && mhz <= it.hi }
        if (all.isEmpty()) return null
        val region2 = isRegion2(mcc)
        val pool = when (region2) {
            null -> all
            true -> all.sortedBy { if (it.band in REGION2_PREFERRED) 0 else 1 }   // stable sort
            false -> all.filter { !it.region2Only }.ifEmpty { all }
        }
        val pick = pool.first()
        return Derived(
            NR, pick.band, round2(mhz),
            // Inside Region 2 a preferred band beating a global one is still a choice between two
            // specified bands, so it stays marked. A region-less answer is always marked if it had
            // any competitor.
            ambiguous = pool.count { !it.renumbering } > 1,
            candidates = all.map { it.band }
        )
    }

    /**
     * Derive from whichever raster the cell identity used. [rat] must be the identity's own
     * technology (CellIdentityLte vs CellIdentityNr), not the display network type, which can lag a
     * cell change and would put an EARFCN through the NR raster.
     */
    fun derive(rat: String?, channel: Int?, mcc: String?): Derived? = when (rat) {
        LTE -> lte(channel, mcc)
        NR -> nr(channel, mcc)
        else -> null
    }

    /**
     * For rows written before the identity's RAT was stored. Every EARFCN in TS 36.101 is below
     * 71 000 and the lowest NR-ARFCN any FR1 band uses is above 120 000 (n105 at 612 MHz), so the
     * magnitude of the number separates the two rasters without guessing.
     */
    fun deriveLegacy(channel: Int?, mcc: String?): Derived? = when {
        channel == null || channel < 0 -> null
        channel < 100_000 -> lte(channel, mcc)
        else -> nr(channel, mcc)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
