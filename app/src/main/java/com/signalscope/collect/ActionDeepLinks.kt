package com.signalscope.collect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Rung B of the ladder (docs/optimisation.md): we cannot set these, but we can put the user one
 * tap from the right page with the evidence already in hand. All Tier 0, and — unlike the Tier-2
 * levers — these genuinely work, because opening a Settings screen costs no connectivity at all.
 *
 * Every target is resolved against the package manager before it is offered. On One UI several of
 * the AOSP actions land somewhere other than their AOSP page, and one of them does not exist at
 * all; an unresolvable target is shown as unavailable rather than as a button that throws.
 */
data class DeepLink(
    val id: String,
    val title: String,
    val blurb: String,
    /** which of the twelve causes this addresses */
    val cause: String,
    /** tried in order; the first that resolves is the one offered */
    val candidates: List<Intent>,
    val caveat: String? = null
)

data class ResolvedLink(
    val link: DeepLink,
    val intent: Intent?,
    val target: String?
) {
    val available: Boolean get() = intent != null
}

object ActionDeepLinks {

    private fun action(a: String) = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun component(pkg: String, cls: String) =
        Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val SETTINGS = "com.android.settings"

    fun links(ctx: Context): List<DeepLink> = listOf(
        DeepLink(
            id = "operator",
            title = "Mobile network settings",
            blurb = "Preferred network type, VoLTE, carrier selection, and the data-SIM choice.",
            cause = "causes 2 · 4 · 11",
            candidates = listOf(action(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        ),
        DeepLink(
            id = "privatedns",
            title = "Private DNS",
            blurb = "Where resolution is the failure rather than the radio. A stuck DoT hostname " +
                    "looks exactly like a dead network.",
            cause = "cause 6",
            candidates = listOf(
                action("android.settings.PRIVATE_DNS_SETTINGS"),
                component(SETTINGS, "com.android.settings.Settings\$PrivateDnsSettingsActivity"),
                action(Settings.ACTION_WIRELESS_SETTINGS)
            ),
            caveat = "One UI has no Private DNS activity of its own; the fallback opens Connections, " +
                    "where it lives under More connection settings."
        ),
        DeepLink(
            id = "battery",
            title = "Battery optimisation",
            blurb = "Doze and app-standby kill long-lived sockets. Exempt the app that keeps " +
                    "losing its connection.",
            cause = "cause 8",
            candidates = listOf(
                action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:com.signalscope"))
            )
        ),
        DeepLink(
            id = "wifi",
            title = "Wi-Fi handover preferences",
            blurb = "Switch-to-mobile-data and adaptive Wi-Fi. Transport thrash orphans every " +
                    "unbound socket, which is this device's dominant real-time failure.",
            cause = "cause 7",
            candidates = listOf(
                component(SETTINGS, "com.android.settings.Settings\$IntelligentWifiSettingsActivity"),
                component(SETTINGS, "com.android.settings.Settings\$AdaptiveConnectivitySettingsActivity"),
                action(Settings.ACTION_WIFI_SETTINGS)
            )
        ),
        DeepLink(
            id = "roaming",
            title = "Data roaming",
            // Was "The second subscription roams permanently on this device", which is a fact
            // about one handset baked into a public repo -- the exact hardcoding this project
            // set out to remove, and now derivable at runtime. SubscriptionManager reports
            // getDataRoaming() per subscription and the telephony callbacks report roaming
            // state per sample, so the blurb states the general rule and the UI can name a
            // roaming subscription when it actually finds one.
            blurb = "If a subscription is roaming, turning its data off reduces cost exposure; " +
                    "it never increases it.",
            cause = "causes 10 · 12",
            candidates = listOf(action(Settings.ACTION_DATA_ROAMING_SETTINGS))
        )
    )

    fun resolve(ctx: Context): List<ResolvedLink> = links(ctx).map { l ->
        var chosen: Intent? = null
        var target: String? = null
        for (i in l.candidates) {
            val ri = runCatching {
                ctx.packageManager.resolveActivity(i, 0)
            }.getOrNull() ?: continue
            val ai = ri.activityInfo ?: continue
            // A component we cannot start is worse than one we do not offer.
            if (i.component != null && !ai.exported) continue
            chosen = i
            target = ai.name.substringAfterLast('.').substringAfterLast('$')
            break
        }
        ResolvedLink(l, chosen, target)
    }

    /** Returns null on success, or the message to show when the launch failed. */
    fun open(ctx: Context, r: ResolvedLink): String? {
        val i = r.intent ?: return "No Settings activity on this build handles it."
        return try {
            ctx.startActivity(i)
            ActionQueue.note("opened ${r.link.title} → ${r.target}", "TIER 0")
            null
        } catch (t: Throwable) {
            ActionQueue.note("could not open ${r.link.title}: ${t.javaClass.simpleName}", "FAILED")
            "${t.javaClass.simpleName} — this build refused the deep link."
        }
    }
}
