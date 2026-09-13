package com.signalscope.store

import android.content.Context
import android.os.Build
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything device- and carrier-specific is DISCOVERED HERE AT RUNTIME, never hardcoded.
 *
 * The app must behave correctly on any handset, any carrier, any country, with one SIM or
 * several. Nothing about the machine it was developed on may leak into its behaviour. Where
 * something genuinely cannot be discovered, the fallback is a published standard, and it is
 * labelled as a fallback in the UI rather than presented as measurement.
 *
 * Stored in SharedPreferences rather than Room: it is a single record, not a time series, and
 * keeping it out of the database avoids a schema migration that would discard collected samples.
 */
data class SubProfile(
    val subId: Int,
    val slot: Int,
    val portIndex: Int,
    val carrier: String,
    val mcc: String?,
    val mnc: String?,
    val countryIso: String?,
    val isEmbedded: Boolean,
    val isRoaming: Boolean,
    val isDataSub: Boolean,
    /** carrier's own bar cut-points, if the platform will give them to a normal app */
    val lteRsrpThresholds: List<Int>?,
    val lteRsrqThresholds: List<Int>?,
    val lteRssnrThresholds: List<Int>?,
    val nrRsrpThresholds: List<Int>?,
    /** bitmask: which measurements the carrier actually feeds into the bar */
    val lteBarParams: Int?,
    val nrBarParams: Int?,
    val thresholdsAreFallback: Boolean
)

data class DeviceProfile(
    val model: String,
    val manufacturer: String,
    val soc: String,
    val sdkInt: Int,
    val release: String,
    val fingerprint: String,
    val baseband: String,
    val multiSimConfig: String,
    val activeModems: Int,
    val supportedModems: Int,
    val subs: List<SubProfile>,
    /** metrics observed at least once as present; anything absent stays unknown until seen */
    val metricsSeen: Set<String>,
    val metricsAbsent: Set<String>,
    val discoveredAtMillis: Long
) {
    fun sub(subId: Int) = subs.firstOrNull { it.subId == subId }

    companion object {
        private const val PREFS = "device_profile"
        private const val KEY = "json"

        // CarrierConfig is a PersistableBundle keyed by string. Several of these have no public
        // constant in SDK 36, so use the platform's own key names -- the same ones that appear in
        // `adb shell dumpsys carrier_config`. A missing key simply yields null, which is handled.
        private const val K_LTE_RSRP = "lte_rsrp_thresholds_int_array"
        private const val K_LTE_RSRQ = "lte_rsrq_thresholds_int_array"
        private const val K_LTE_RSSNR = "lte_rssnr_thresholds_int_array"
        private const val K_NR_SSRSRP = "5g_nr_ssrsrp_thresholds_int_array"
        private const val K_LTE_BAR_PARAMS = "parameters_used_for_lte_signal_bar_int"
        private const val K_NR_BAR_PARAMS = "parameters_use_for_5g_nr_signal_bar_int"

        /** 3GPP-derived defaults, used ONLY when the carrier will not tell us. Labelled as such. */
        val FALLBACK_LTE_RSRP = listOf(-115, -105, -95, -85)
        val FALLBACK_LTE_RSRQ = listOf(-19, -15, -12, -10)
        val FALLBACK_LTE_RSSNR = listOf(-3, 1, 5, 13)
        val FALLBACK_NR_RSRP = listOf(-110, -100, -90, -80)

        fun discover(ctx: Context): DeviceProfile {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            val ccm = ctx.getSystemService(CarrierConfigManager::class.java)

            val subs = buildList {
                val active = runCatching { sm?.activeSubscriptionInfoList }.getOrNull().orEmpty()
                val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
                active.forEach { info ->
                    val cfg = runCatching { ccm?.getConfigForSubId(info.subscriptionId) }.getOrNull()
                    fun arr(key: String): List<Int>? =
                        runCatching { cfg?.getIntArray(key)?.toList()?.takeIf { it.isNotEmpty() } }.getOrNull()
                    fun int(key: String): Int? =
                        runCatching { if (cfg?.containsKey(key) == true) cfg.getInt(key) else null }.getOrNull()

                    val rsrp = arr(K_LTE_RSRP)
                    add(
                        SubProfile(
                            subId = info.subscriptionId,
                            slot = info.simSlotIndex,
                            portIndex = runCatching { info.portIndex }.getOrDefault(-1),
                            carrier = info.carrierName?.toString() ?: "—",
                            mcc = info.mccString,
                            mnc = info.mncString,
                            countryIso = info.countryIso,
                            isEmbedded = info.isEmbedded,
                            isRoaming = runCatching {
                                tm?.createForSubscriptionId(info.subscriptionId)?.isNetworkRoaming == true
                            }.getOrDefault(false),
                            isDataSub = info.subscriptionId == dataSub,
                            lteRsrpThresholds = rsrp,
                            lteRsrqThresholds = arr(K_LTE_RSRQ),
                            lteRssnrThresholds = arr(K_LTE_RSSNR),
                            nrRsrpThresholds = arr(K_NR_SSRSRP),
                            lteBarParams = int(K_LTE_BAR_PARAMS),
                            nrBarParams = int(K_NR_BAR_PARAMS),
                            thresholdsAreFallback = rsrp == null
                        )
                    )
                }
            }

            return DeviceProfile(
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                soc = runCatching { Build.SOC_MODEL }.getOrDefault("unknown"),
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
                fingerprint = Build.FINGERPRINT,
                baseband = runCatching { Build.getRadioVersion() ?: "unknown" }.getOrDefault("unknown"),
                multiSimConfig = when {
                    (tm?.activeModemCount ?: 1) > 1 -> "multi-sim (${tm?.activeModemCount} active modems)"
                    else -> "single active modem"
                },
                activeModems = runCatching { tm?.activeModemCount ?: 1 }.getOrDefault(1),
                supportedModems = runCatching { tm?.supportedModemCount ?: 1 }.getOrDefault(1),
                subs = subs,
                metricsSeen = emptySet(),
                metricsAbsent = emptySet(),
                discoveredAtMillis = System.currentTimeMillis()
            )
        }

        fun load(ctx: Context): DeviceProfile? {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }

        fun save(ctx: Context, p: DeviceProfile) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, toJson(p).toString()).apply()
        }

        private fun toJson(p: DeviceProfile) = JSONObject().apply {
            put("model", p.model); put("manufacturer", p.manufacturer); put("soc", p.soc)
            put("sdkInt", p.sdkInt); put("release", p.release); put("fingerprint", p.fingerprint)
            put("baseband", p.baseband); put("multiSimConfig", p.multiSimConfig)
            put("activeModems", p.activeModems); put("supportedModems", p.supportedModems)
            put("discoveredAtMillis", p.discoveredAtMillis)
            put("metricsSeen", JSONArray(p.metricsSeen.toList()))
            put("metricsAbsent", JSONArray(p.metricsAbsent.toList()))
            put("subs", JSONArray().apply {
                p.subs.forEach { s ->
                    put(JSONObject().apply {
                        put("subId", s.subId); put("slot", s.slot); put("portIndex", s.portIndex)
                        put("carrier", s.carrier); put("mcc", s.mcc ?: JSONObject.NULL)
                        put("mnc", s.mnc ?: JSONObject.NULL)
                        put("countryIso", s.countryIso ?: JSONObject.NULL)
                        put("isEmbedded", s.isEmbedded); put("isRoaming", s.isRoaming)
                        put("isDataSub", s.isDataSub)
                        put("lteRsrp", JSONArray(s.lteRsrpThresholds ?: emptyList<Int>()))
                        put("lteRsrq", JSONArray(s.lteRsrqThresholds ?: emptyList<Int>()))
                        put("lteRssnr", JSONArray(s.lteRssnrThresholds ?: emptyList<Int>()))
                        put("nrRsrp", JSONArray(s.nrRsrpThresholds ?: emptyList<Int>()))
                        put("lteBarParams", s.lteBarParams ?: JSONObject.NULL)
                        put("nrBarParams", s.nrBarParams ?: JSONObject.NULL)
                        put("thresholdsAreFallback", s.thresholdsAreFallback)
                    })
                }
            })
        }

        private fun ints(o: JSONObject, k: String): List<Int>? {
            val a = o.optJSONArray(k) ?: return null
            if (a.length() == 0) return null
            return (0 until a.length()).map { a.getInt(it) }
        }

        private fun strs(o: JSONObject, k: String): Set<String> {
            val a = o.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }

        private fun fromJson(o: JSONObject): DeviceProfile {
            val sa = o.optJSONArray("subs") ?: JSONArray()
            return DeviceProfile(
                model = o.optString("model"), manufacturer = o.optString("manufacturer"),
                soc = o.optString("soc"), sdkInt = o.optInt("sdkInt"),
                release = o.optString("release"), fingerprint = o.optString("fingerprint"),
                baseband = o.optString("baseband"), multiSimConfig = o.optString("multiSimConfig"),
                activeModems = o.optInt("activeModems", 1),
                supportedModems = o.optInt("supportedModems", 1),
                metricsSeen = strs(o, "metricsSeen"), metricsAbsent = strs(o, "metricsAbsent"),
                discoveredAtMillis = o.optLong("discoveredAtMillis"),
                subs = (0 until sa.length()).map { i ->
                    val s = sa.getJSONObject(i)
                    SubProfile(
                        subId = s.optInt("subId"), slot = s.optInt("slot"),
                        portIndex = s.optInt("portIndex", -1), carrier = s.optString("carrier"),
                        mcc = s.optString("mcc").ifEmpty { null },
                        mnc = s.optString("mnc").ifEmpty { null },
                        countryIso = s.optString("countryIso").ifEmpty { null },
                        isEmbedded = s.optBoolean("isEmbedded"),
                        isRoaming = s.optBoolean("isRoaming"),
                        isDataSub = s.optBoolean("isDataSub"),
                        lteRsrpThresholds = ints(s, "lteRsrp"),
                        lteRsrqThresholds = ints(s, "lteRsrq"),
                        lteRssnrThresholds = ints(s, "lteRssnr"),
                        nrRsrpThresholds = ints(s, "nrRsrp"),
                        lteBarParams = if (s.isNull("lteBarParams")) null else s.optInt("lteBarParams"),
                        nrBarParams = if (s.isNull("nrBarParams")) null else s.optInt("nrBarParams"),
                        thresholdsAreFallback = s.optBoolean("thresholdsAreFallback", true)
                    )
                }
            )
        }
    }
}
