package com.signalscope.store

import android.content.Context

/**
 * Which networks basemap regions may be downloaded over.
 *
 * ## Wi-Fi only is the default, and an update never widens it
 *
 * Unattended downloads are safe only because the default excludes the failure a prompt would
 * guard against — tens of MB on a foreign cellular plan. Widening that is the user's call, made
 * once in the map panel, never inferred.
 *
 * ## Why roaming is its own step rather than part of "mobile data"
 *
 * The cost of the same megabyte differs by orders of magnitude between a home plan and a roaming
 * one, so allowing one is not allowing the other. And it cannot simply be left out: the reference
 * device's second SIM roams full-time, so for it "mobile data but never roaming" would mean never.
 *
 * The cost of Wi-Fi only is delay, not loss — a region queued in a new country arrives at the
 * first unmetered Wi-Fi, which is usually that evening. This setting trades bytes for that delay.
 */
object MapDataPolicy {

    enum class Allow { WIFI_ONLY, MOBILE_HOME, MOBILE_ROAMING }

    private const val PREFS = "map_data_policy"
    private const val KEY = "allow_v1"

    fun get(ctx: Context): Allow =
        prefs(ctx).getString(KEY, null)
            ?.let { s -> Allow.entries.firstOrNull { it.name == s } }
            ?: Allow.WIFI_ONLY

    fun set(ctx: Context, allow: Allow) {
        prefs(ctx).edit().putString(KEY, allow.name).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
