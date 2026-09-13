package com.signalscope.collect

import android.content.Context
import android.net.*
import android.os.SystemClock
import com.signalscope.store.Db
import com.signalscope.store.LinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Two callbacks, deliberately.
 *
 * The default-network callback says what apps are actually using — it catches the
 * Wi-Fi/cellular route moves that orphan sockets. The cellular-specific request keeps
 * the cellular network observable *while Wi-Fi is default*, which matters because
 * walking out of Wi-Fi range mid-call is a primary failure mode; with only the first
 * callback we would be blind over exactly the evidence we need.
 */
class ConnectivityCollector(
    private val ctx: Context,
    private val scope: CoroutineScope
) {
    private val cm by lazy { ctx.getSystemService(ConnectivityManager::class.java) }
    private val dao by lazy { Db.get(ctx).dao() }

    // Touched from two different binder threads (the default-network callback and the
    // cellular-specific one). The address-change counter is the evidence behind the
    // "socket-killer" flag, so a lost increment is a missed diagnosis; the whole
    // read-compare-write runs under one lock rather than racing.
    private val lock = Any()
    private var lastV4: String? = null
    private var lastV6: String? = null
    private var addressChanges = 0

    private val defaultCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c, null, true)
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = update(n, null, lp, true)
        override fun onLost(n: Network) {
            LiveState.net.value = LiveState.net.value.copy(validated = false, transport = "LOST")
        }
    }

    private val cellCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = update(n, c, null, false)
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(defaultCb) }
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build(),
                cellCb
            )
        }
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(defaultCb) }
        runCatching { cm?.unregisterNetworkCallback(cellCb) }
    }

    private fun update(n: Network, capsIn: NetworkCapabilities?, lpIn: LinkProperties?, isDefault: Boolean) {
        val caps = capsIn ?: cm?.getNetworkCapabilities(n)
        val lp = lpIn ?: cm?.getLinkProperties(n)

        // A VPN is an overlay, not a bearer. The radio underneath is still Wi-Fi or cellular, and
        // reporting "VPN" here silently disabled most of this app: BearerWarmth's first gate,
        // the location policy, the excursion recorder and the warmth experiment all test for
        // CELLULAR, so a user with a VPN running would have had every one of them quietly refuse
        // while the app went on looking healthy. The reference device has a DNS-filtering VPN
        // installed and one VPN default-network event already on record.
        //
        // So the transport reported is the EFFECTIVE bearer, resolved through the overlay, and
        // the fact that a VPN is in the path is kept separately rather than thrown away -- it
        // matters for reading latency, and a tunnel is a real thing to know about.
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        // NetworkCapabilities.getUnderlyingNetworks() would be the direct route, but it does not
        // resolve against this compile target and the platform only populates it when the VPN app
        // declares its underlying networks -- which many do not. Asking the system which
        // non-VPN network is currently validated is both available and more reliable: the tunnel
        // has to be riding on something, and that something is the bearer we care about.
        val under: NetworkCapabilities? = if (!vpn) null else runCatching {
            cm?.allNetworks?.mapNotNull { cm?.getNetworkCapabilities(it) }?.firstOrNull { c ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            }
        }.getOrNull()

        val transport = when {
            caps == null -> "—"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            under?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            under?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            // Only when the overlay will not say what it rides on. Still reported, never guessed.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val notSusp = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) != false
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

        val v4 = lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
        val v6 = lp?.linkAddresses?.firstOrNull {
            it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress
        }?.address?.hostAddress
        // getStackedLinks()/allInterfaceNames are hidden API; the clat interface name is the
        // public signal. An IPv6-only bearer with no v4 address and no v4- iface means no CLAT.
        val clat = lp?.interfaceName?.startsWith("v4-") == true

        var changed = false
        if (isDefault) {
            synchronized(lock) {
                if ((v4 != null && lastV4 != null && v4 != lastV4) ||
                    (v6 != null && lastV6 != null && v6 != lastV6)
                ) {
                    changed = true
                    addressChanges++
                }
                if (v4 != null) lastV4 = v4
                if (v6 != null) lastV6 = v6

                LiveState.net.value = NetState(
                    transport = transport, validated = validated, notSuspended = notSusp,
                    metered = metered, v4 = v4, v6 = v6, mtu = lp?.mtu,
                    hasClat = clat, ifname = lp?.interfaceName, addressChanges = addressChanges,
                    vpn = vpn
                )
            }
        }

        scope.launch {
            runCatching {
                dao.insertLink(
                    LinkEvent(
                        elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                        wallMillis = System.currentTimeMillis(),
                        netId = n.toString(),
                        transport = transport,
                        isDefault = isDefault,
                        validated = validated,
                        notSuspended = notSusp,
                        metered = metered,
                        v4Address = v4,
                        v6Address = v6,
                        addressChanged = changed,
                        dnsServers = lp?.dnsServers?.joinToString(",") { it.hostAddress ?: "" },
                        mtu = lp?.mtu,
                        interfaceName = lp?.interfaceName,
                        hasClat = clat
                    )
                )
            }
        }
    }
}
