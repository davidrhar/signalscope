package com.signalscope.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * See the Wi-Fi-to-cellular handover coming, and warm the bearer before it lands.
 *
 * ## Why this is worth building at all
 *
 * The handover is the worst moment in everything this project has measured, and it is the only
 * one of its failures that is *predictable*. Three things happen at once when Wi-Fi drops: the
 * cellular radio has been dormant for hours and must be woken, which failed 12.3 % of the time
 * in the excursion data and cost ~6 s when it did; the IP address changes, which kills every open
 * socket rather than merely pausing it, and is what makes an app say "reconnecting" instead of
 * stalling; and every app on the phone demands data in the same instant, so the wake-up happens
 * under the heaviest contention it will ever face.
 *
 * Arriving at that moment already connected removes the first of the three outright. It cannot
 * help with the address change -- nothing at this privilege level can -- but one of three is worth
 * ninety seconds of radio.
 *
 * ## Why it needs its own permission from BearerWarmth
 *
 * [BearerWarmth]'s first and most important gate refuses to warm while Wi-Fi holds the default
 * route, because that is battery spent on a bearer nobody is using. That gate is correct and this
 * is the sole exception to it, so it goes through [BearerWarmth.preemptNow] rather than
 * `holdNow` -- the exception is visible at the call site instead of buried in a flag, and it is
 * bounded, so a wrong prediction costs a known amount of radio and then lapses.
 *
 * ## Signals, and what they cost
 *
 * `NetworkCapabilities.getSignalStrength()` carries Wi-Fi RSSI in dBm from API 29 and arrives on
 * a callback we would be registering anyway. It needs no `ACCESS_WIFI_STATE` and no location
 * grant, which is why it is used here in preference to `WifiManager.getConnectionInfo()`, whose
 * fields are redacted without location permission on modern Android. It is optional on some
 * builds, so an absent value disables the RSSI limbs and leaves the others working rather than
 * disabling the feature.
 */
object HandoverPredictor {

    data class State(
        val watching: Boolean = false,
        /** Last Wi-Fi RSSI in dBm, or null where the platform declines to report one. */
        val wifiRssi: Int? = null,
        val wifiValidated: Boolean? = null,
        val predictions: Int = 0,
        val lastReason: String? = null,
        val note: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Edge of usefulness rather than edge of range. Wi-Fi typically holds a usable link to about
     * -80 dBm and Android's own scoring starts abandoning a network in the high -70s, so -75 is
     * roughly "one room further and this is over" -- early enough to be worth acting on, late
     * enough that sitting in a normal house does not trip it continuously.
     */
    private const val RSSI_EDGE_DBM = -75

    /**
     * A fall this steep is someone walking out of the building, and it fires before the absolute
     * threshold does. Ten dB is a bit over a halving of signal power; sampled across 20 s so one
     * noisy reading cannot trigger it on its own.
     */
    private const val RSSI_FALL_DB = 10
    private const val RSSI_FALL_WINDOW_MS = 20_000L

    /** Long enough that a normal roam between access points does not read as a departure. */
    private const val HOLD_MS = 60_000L

    /** One prediction per window at most; without this a decaying AP re-arms on every callback. */
    private const val MIN_GAP_MS = 45_000L

    private var cm: ConnectivityManager? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var appCtx: Context? = null
    private var scope: CoroutineScope? = null

    /** (elapsedRealtime, rssi) history, trimmed to the fall window. */
    private val history = ArrayDeque<Pair<Long, Int>>()
    @Volatile private var lastPredictionElapsed = 0L

    @Synchronized
    fun start(ctx: Context, s: CoroutineScope) {
        if (cb != null) return
        val app = ctx.applicationContext
        appCtx = app
        scope = s
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            _state.value = _state.value.copy(note = "no ConnectivityManager")
            return
        }
        cm = manager

        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                runCatching { onWifi(caps) }
            }

            override fun onLost(n: Network) {
                // Wi-Fi has gone. The handover is not imminent, it is happening -- so this is the
                // one limb that does not wait for evidence to accumulate. BearerWarmth's own
                // wifi-handover trigger covers the window *after* the switch completes; this
                // covers the gap between Wi-Fi leaving and cellular being declared default, which
                // is where an app's first reconnect attempt lands.
                runCatching { predict("wifi-lost") }
                history.clear()
                _state.value = _state.value.copy(wifiRssi = null, wifiValidated = null)
            }
        }
        cb = c
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                c
            )
            _state.value = _state.value.copy(watching = true, note = null)
        }.onFailure {
            cb = null
            _state.value = _state.value.copy(
                watching = false, note = "could not watch Wi-Fi: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        cb?.let { c -> runCatching { cm?.unregisterNetworkCallback(c) } }
        cb = null
        history.clear()
        _state.value = _state.value.copy(watching = false)
    }

    private fun onWifi(caps: NetworkCapabilities) {
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // SIGNAL_STRENGTH_UNSPECIFIED is Int.MIN_VALUE; some builds simply never populate it.
        val rssi = runCatching { caps.signalStrength }
            .getOrNull()
            ?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it < 0 }

        val wasValidated = _state.value.wifiValidated
        _state.value = _state.value.copy(wifiRssi = rssi, wifiValidated = validated)

        // Losing validation while still associated means Android has decided this network does
        // not reach the internet, and it is about to stop using it. That is a stronger signal
        // than any RSSI reading, and it does not depend on the platform reporting a level.
        if (wasValidated == true && !validated) {
            predict("wifi-lost-internet")
            return
        }

        if (rssi == null) return
        val now = SystemClock.elapsedRealtime()
        history.addLast(now to rssi)
        while (history.isNotEmpty() && now - history.first().first > RSSI_FALL_WINDOW_MS) {
            history.removeFirst()
        }

        // Steep fall first: it is the earlier warning of the two, and someone walking out trips
        // it while still well above the absolute threshold.
        val oldest = history.firstOrNull()
        if (oldest != null && history.size >= 3 && oldest.second - rssi >= RSSI_FALL_DB) {
            predict("wifi-falling ${oldest.second}→$rssi dBm")
            return
        }

        if (rssi <= RSSI_EDGE_DBM) predict("wifi-weak $rssi dBm")
    }

    private fun predict(reason: String) {
        val ctx = appCtx ?: return
        val s = scope ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPredictionElapsed < MIN_GAP_MS) return
        lastPredictionElapsed = now
        _state.value = _state.value.copy(
            predictions = _state.value.predictions + 1, lastReason = reason)
        runCatching { BearerWarmth.preemptNow(ctx, s, reason, HOLD_MS) }
    }
}
