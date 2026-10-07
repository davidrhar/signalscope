package com.signalscope.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Is there a mobile connection for the app to test at all, and if not, why.
 *
 * ## Why this exists
 *
 * A second phone ran for eight days and produced 742 connection tests, every one of which
 * recorded `no cellular network`. The map stayed grey, Diagnosis said "Not measured yet", and the
 * instrument panel said **"Everything this app measures with is working."** All three were reading
 * the same cause and none of them named it: mobile data was switched off, so the only cellular
 * networks the phone had were the IMS ones that carry calls, which have no `INTERNET` capability
 * and can never be bound to.
 *
 * Nothing was broken. But the app knew the answer and showed a reassuring sentence instead, which
 * is the one failure [InstrumentHealth] exists to prevent. The missing piece was that no code
 * anywhere asked the obvious question, so this file asks it.
 *
 * ## Why the capability and not just the setting
 *
 * `isDataEnabled` is the user's switch; it says nothing about whether the modem actually has a
 * data connection up. [CellProbe] binds its socket to a network with `TRANSPORT_CELLULAR` **and**
 * `NET_CAPABILITY_INTERNET`, so that is the condition tested first and the setting is only
 * consulted to explain an absence. The two disagree exactly where the distinction matters: data
 * switched on with no service is a network problem worth measuring, data switched off is not.
 */
object MobileData {

    enum class State {
        /** A cellular network with INTERNET exists: probes can run. */
        CARRYING,
        /** No such network, and mobile data is switched off. Nothing can be measured, by choice. */
        SWITCHED_OFF,
        /** Mobile data is on, but the phone has no data connection right now. */
        NO_SERVICE,
        /** The phone would not say. Never reported as a fault. */
        UNKNOWN
    }

    /**
     * IMS networks are deliberately not accepted. A roaming phone with data off still carries two
     * of them for VoLTE and Wi-Fi calling, they are `CONNECTED` and `VALIDATED`, and reading them
     * as a usable bearer is what would turn this check back into the reassuring lie.
     */
    private fun hasCellularInternet(ctx: Context): Boolean? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrNull()

    /** Null when the phone declines to answer, which must not be read as "off". */
    private fun dataEnabled(ctx: Context): Boolean? = runCatching {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return@runCatching null
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        val forSub =
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(sub)
            else tm
        forSub.isDataEnabled
    }.getOrNull()

    /**
     * Both reads are binder calls, and the two callers are a Compose health strip drawn on every
     * tab and a panel that recomposes with the row counter -- so uncached this would cross into
     * the telephony service once per frame. Two seconds is short enough that throwing the data
     * switch updates the strip while the person is still looking at it, and long enough that a
     * recomposition storm costs one call rather than sixty.
     */
    private const val CACHE_MS = 2_000L

    @Volatile private var cached: State = State.UNKNOWN
    @Volatile private var cachedAt = 0L

    fun state(ctx: Context): State {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) return cached
        val s = read(ctx)
        cached = s
        cachedAt = now
        return s
    }

    private fun read(ctx: Context): State {
        if (hasCellularInternet(ctx) == true) return State.CARRYING
        return when (dataEnabled(ctx)) {
            false -> State.SWITCHED_OFF
            true -> State.NO_SERVICE
            null -> State.UNKNOWN
        }
    }

    /** One sentence naming the cause, or null when there is nothing to explain. */
    fun why(s: State): String? = when (s) {
        State.CARRYING, State.UNKNOWN -> null
        State.SWITCHED_OFF ->
            "Mobile data is switched off on this phone, so there is no mobile connection to test. " +
                "The calling connection is still there — that is why calls and texts work — but it " +
                "carries no data and cannot be measured."
        State.NO_SERVICE ->
            "Mobile data is switched on, but the phone has no data connection at the moment, so " +
                "there is nothing to test."
    }
}
