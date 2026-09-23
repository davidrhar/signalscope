package com.signalscope.store

/**
 * PLMN to the name a person would recognise.
 *
 * A shared map is for comparing networks, and "525-10" compares nothing to anyone who does not
 * already know what it means. The app has always had the real name for the *local* SIM, because
 * Android supplies it -- but a contribution from someone else's phone carries only the PLMN, so a
 * table is the only way to name it.
 *
 * ## Rules
 *
 * **Unknown codes render as the code.** Never a guess, never "Unknown": a reader who sees
 * `525-99` can look it up, and a reader who sees "Unknown" has been told nothing and cannot tell
 * whether the app failed or the network is genuinely obscure.
 *
 * **Verified entries only.** Singapore's codes below are checked against two independent
 * references. 525-05 in particular is StarHub and not Singtel, which this project asserted wrongly
 * once already; Singtel is 525-01. Codes are not added here from memory.
 *
 * **The list is short on purpose.** It covers where the app has actually been used. A global PLMN
 * table is a maintenance burden with no user until there is a user outside these countries, and a
 * half-remembered global table is worse than a small correct one.
 */
object Networks {

    private val NAMES: Map<String, String> = mapOf(
        // Singapore
        "525-01" to "Singtel",
        "525-02" to "Singtel",
        "525-03" to "M1",
        "525-05" to "StarHub",
        "525-06" to "StarHub",
        "525-10" to "Simba",
        "525-11" to "M1",
        // Indonesia -- seen while roaming; Telkomsel and Smartfren are the 2300 MHz operators.
        "510-10" to "Telkomsel",
        "510-11" to "XL Axiata",
        "510-01" to "Indosat",
        "510-89" to "Tri"
    )

    /** The recognisable name, or the code itself when it is not one this app can vouch for. */
    fun name(plmn: String?): String {
        val p = plmn?.trim().orEmpty()
        if (p.isEmpty() || p == "—" || p == "?-?") return "unknown network"
        return NAMES[normalise(p)] ?: p
    }

    /** True when [name] would return something other than the code. */
    fun isKnown(plmn: String?): Boolean =
        plmn != null && NAMES.containsKey(normalise(plmn))

    /**
     * A band label as a person should read it.
     *
     * "?40" is what a contribution carries when the RAT did not say whether the number is LTE's
     * or NR's. Rendering that raw invites the reader to think the app is confused; saying what is
     * actually unknown invites them to discount it correctly.
     */
    fun band(label: String?): String {
        val b = label?.trim().orEmpty()
        if (b.isEmpty()) return "band unknown"
        return if (b.startsWith("?")) "band ${b.drop(1)}, type not reported" else b
    }

    /**
     * `525-010` and `525-10` are the same network written two ways.
     *
     * The modem reports MNC with or without a leading zero depending on the API that produced it,
     * and both spellings have been seen in this project's own data -- where they split what should
     * have been one map bin in two. Comparison happens on the trimmed form; the original is what
     * gets displayed when there is no match.
     */
    private fun normalise(plmn: String): String {
        val parts = plmn.split('-')
        if (parts.size != 2) return plmn
        val mnc = parts[1].trimStart('0').ifEmpty { "0" }
        return parts[0] + "-" + mnc
    }
}
