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

    private const val PREFS = "plmn_names"

    /**
     * Names the modem itself reported for networks this phone has registered on.
     *
     * A hand-written table can only ever name networks someone thought to add, which is the wrong
     * set: the networks that matter are the ones this phone actually used, and on a phone that
     * travels those are unknowable in advance. Every ServiceState carries the registered
     * operator's numeric code AND its name, so the device is already being told the answer -- this
     * just stops throwing it away. It is learned, not looked up, so it costs no network call, no
     * shipped table, and nothing to keep current.
     *
     * Curated entries still win. A learned name comes from a carrier's own configuration and can
     * be a brand, a sub-brand, or an abbreviation that varies by handset; where this project has
     * checked a code against a public reference, that answer is the better one.
     */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Load what has been learned before. Cheap, and safe to call more than once. */
    fun prime(ctx: android.content.Context) {
        runCatching {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).all
                .forEach { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { learned[k] = it } }
        }
    }

    /** Drop every learned name. Part of "delete everything collected". */
    fun forgetLearned(ctx: android.content.Context) {
        learned.clear()
        runCatching {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    /**
     * Record what the network called itself, if it is worth recording.
     *
     * The guard that matters is [simName]. A carrier can configure the handset to keep showing its
     * own name while the phone is on somebody else's network -- so a reported name identical to
     * the SIM's own, on a network that is not the SIM's own, is the signature of that override
     * rather than a fact about the visited operator. Learning it would relabel every foreign
     * network with the home one's name, which is worse than a bare code: a code says nothing,
     * a wrong name says something false.
     */
    fun learn(
        ctx: android.content.Context,
        plmn: String?,
        reported: String?,
        simPlmn: String? = null,
        simName: String? = null
    ) {
        val p = plmn?.trim().orEmpty()
        val n = reported?.trim().orEmpty()
        if (!p.contains('-') || n.isEmpty()) return
        // A modem with nothing to say often says the numeric code, or the code with a space in it.
        if (n.length > 32 || n.all { it.isDigit() || it == '-' || it == ' ' }) return
        if (n.equals(p, true)) return
        val key = normalise(p)
        if (NAMES.containsKey(key)) return
        // The roaming display-name override, described above.
        if (simName != null && n.equals(simName.trim(), true) &&
            simPlmn != null && normalise(simPlmn.trim()) != key
        ) return
        if (learned[key] == n) return
        learned[key] = n
        runCatching {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(key, n).apply()
        }
    }

    private val RAW: Map<String, String> = mapOf(
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

    /**
     * Keyed by the normalised code, not by the spelling written in [RAW].
     *
     * The table is written the way a person reads a PLMN -- 525-01 -- and looked up through
     * [normalise], which strips the leading zero. Those two did not agree, so every operator with
     * a leading zero in its MNC silently failed to resolve and showed as a bare code. Simba is
     * 525-10 and has no leading zero, which is exactly why it kept working and hid the rest.
     */
    private val NAMES: Map<String, String> = RAW.mapKeys { normalise(it.key) }

    /** The recognisable name, or the code itself when it is not one this app can vouch for. */
    fun name(plmn: String?): String {
        val p = plmn?.trim().orEmpty()
        if (p.isEmpty() || p == "—" || p == "?-?") return "unknown network"
        val key = normalise(p)
        return NAMES[key] ?: learned[key] ?: p
    }

    /** True when [name] would return something other than the code. */
    fun isKnown(plmn: String?): Boolean =
        plmn != null && normalise(plmn).let { NAMES.containsKey(it) || learned.containsKey(it) }

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
