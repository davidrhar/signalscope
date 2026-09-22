package com.signalscope.collect

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import com.signalscope.MainActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the collectors.
 *
 * It keeps the *process* alive. It deliberately does NOT keep the CPU awake:
 * a held wakelock measured 19.6 mA on the reference device, about 9.4 %/day,
 * which would be several times the entire power budget. Wake on callback,
 * write, return.
 */
class CollectorService : LifecycleService() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telephony: TelephonyCollector? = null
    private var connectivity: ConnectivityCollector? = null
    /** Last logged location-gate reason, so the loop reports changes rather than every tick. */
    private var lastLocationReason: String? = null

    override fun onCreate() {
        super.onCreate()

        // Declaring only the `location` FGS type made startForeground() throw a
        // SecurityException whenever location permission was not yet granted -- which is
        // every fresh install, because the service starts before the dialog is answered.
        // That killed the process. Choose the type from what is actually granted, and never
        // let a startForeground failure crash the app.
        if (!startForegroundSafely()) {
            // Cannot run in any mode. Stop cleanly rather than dying.
            LiveState.running.value = false
            stopSelf()
            return
        }

        // Discover this handset and its carriers before collecting. Re-discovered on every
        // start so a swapped SIM, a new default data sub or an OS update is picked up.
        io.launch {
            runCatching {
                val p = com.signalscope.store.DeviceProfile.discover(this@CollectorService)
                com.signalscope.store.DeviceProfile.save(this@CollectorService, p)
                LiveState.profile.value = p
            }
        }

        // The old separate position database. Its rows are a timestamped movement history and
        // nothing reads them any more, so the file is removed rather than left on disk to be
        // forgotten -- a deletion the user cannot perform themselves and would not know to ask for.
        io.launch { runCatching { com.signalscope.store.LegacyPositionFile.remove(this@CollectorService) } }

        // Bounded retention, on every start. `data-model.md` §7 promises raw rows are dropped at
        // 30 days; nothing enforced it, so the movement history the two databases jointly hold
        // grew for the life of the install. Cheap (one indexed DELETE per table) and it runs
        // before the collectors add to them.
        io.launch { runCatching { com.signalscope.store.Retention.sweep(this@CollectorService) } }

        // A collector that cannot start must not take the service down with it. Either one can
        // throw on a handset that lacks the subsystem (no telephony at all on a Wi-Fi tablet),
        // and an exception escaping onCreate() kills the process with the notification already
        // posted -- the user sees a service that claims to be collecting and is not.
        // Basemap acquisition runs from the collector, not from the Map tab: a user who never
        // opens the map should still have one ready when they do, and country detection needs to
        // be watching before the phone crosses a border rather than after.
        runCatching { RegionAcquisition.start(this) }

        // Shizuku is optional; this only observes whether it is available. If a Phase A run was
        // interrupted mid-trial, the radio configuration is restored here before anything else.
        // Probes bound to cellular, so outcomes exist even while Wi-Fi is the default route.
        runCatching { CellProbe.start(this) }

        // Holds the bearer out of dormancy under a trigger. RRC state belongs to the device, not
        // to an app, so this benefits Teams and YouTube as much as us -- and it is gated off
        // entirely while Wi-Fi carries the default route, where it would be pure battery cost.
        runCatching { BearerWarmth.start(this, io) }
        // Started here as well as from the Actions screen: warmth's call and media triggers read
        // this classifier, and until now it only ran while that screen was composed -- so in the
        // background it had zero samples and reported UNKNOWN, which is not evidence of no call.
        runCatching { ActionTraffic.start(this) }
        // Warms the bearer BEFORE Wi-Fi goes, on evidence that it is about to. The handover is
        // the worst moment measured anywhere in this project and the only failure that can be
        // seen coming; this is the sole path allowed to warm while Wi-Fi still holds the route.
        runCatching { HandoverPredictor.start(this, io) }

        io.launch {
            while (isActive) {
                runCatching { CellProbe.probeAndRecord(this@CollectorService) }
                // Uplink against downlink. Self-rate-limited to 15 min on metered cellular and
                // capped at 1 MB/day inside CellProbe, because this one sends real bytes on the
                // user's plan -- so it rides the existing tick rather than adding a cadence.
                runCatching { CellProbe.probeAsymmetricAndRecord(this@CollectorService) }
                // Seed quickly so per-cell outcomes exist at all, then back off. A probe on a
                // dormant radio forces an RRC promotion, so this is not a continuous measurement.
                // Off Wi-Fi is the condition that matters and the one we have never measured,
                // so probe densely then; on Wi-Fi the cellular bearer is idle and probing it
                // mostly buys an RRC promotion we are paying for.
                val offWifi = LiveState.net.value.transport == "CELLULAR"
                val n = LiveState.counters.value.radioRows
                kotlinx.coroutines.delay(
                    when {
                        offWifi -> 45_000L
                        n < 8_000 -> 90_000L
                        else -> 5 * 60_000L
                    }
                )
            }
        }

        // Records off-Wi-Fi sessions unattended, since leaving Wi-Fi ends any session watching.
        runCatching { ExcursionRecorder.start(this, io) }
        // Classifies movement and records journeys. The train case is the project's largest
        // unmeasured claim: across everything collected the phone has never left one tracking
        // area, so nothing here can yet say whether a call fails at speed for a different reason
        // than it fails at rest.
        runCatching { Mobility.start(this, io) }

        runCatching { ShizukuBridge.init(this) }

        // Location is now the service's decision, not the Map tab's. See locationPolicy().
        runCatching { locationPolicy() }

        telephony = TelephonyCollector(this, io).also { c ->
            runCatching { c.start() }
            // Mandatory, not an optimisation. The passive signal callbacks stop entirely when the
            // screen goes off, so without this poll every unattended measurement -- excursions,
            // A/B blocks, overnight baselines -- silently reads one frozen snapshot for hours.
            runCatching { c.startPolling() }
        }
        connectivity = ConnectivityCollector(this, io).also { c -> runCatching { c.start() } }

        // Last, deliberately: the Tier 2 sweep needs ShizukuBridge initialised above, and the
        // per-subscription inventory reads LiveState.sims, which the telephony collector has only
        // just begun to populate.
        runCatching { CarrierFaults.start(this, io) }
        // Last, because it audits everything above it. Three separate instrument failures in two
        // days each produced a confident wrong answer and each was found by accident; this is the
        // check that stops that being the detection mechanism.
        runCatching { InstrumentHealth.start(this, io) }
        // Folds the in-memory fixes into persisted per-bin aggregates every few minutes. Runs from
        // the service, not the Map tab: a user who never opens the map still accumulates one.
        runCatching { com.signalscope.store.BinAggregator.start(this, io) }
        // Indoor likelihood from satellite signal, listened to only while position is already being
        // requested -- it never turns GNSS on by itself, because GNSS is the costliest radio here.
        runCatching { EnvironmentContext.start(this, io) }
        // Which carriers are aggregated and in what role. Widths are public; roles need the
        // privileged channel list, so this degrades to widths when Shizuku is not available.
        runCatching { CarrierAggregation.start(this, io) }

        LiveState.running.value = true
        LiveState.counters.value = LiveState.counters.value.copy(
            startedElapsed = SystemClock.elapsedRealtime()
        )
    }

    /**
     * Decide when position is worth its power, and keep asserting that decision.
     *
     * Location is the dominant power cost in this app -- GNSS measured 38 mA against a collector
     * baseline of roughly 0.6 %/day -- so this is deliberately a gate rather than an always-on
     * request. [MapLocationCollector] used to run only while the Map tab was composed, which
     * sounded conservative and cost us the whole 2026-09-12 excursion: twenty-five minutes of
     * real off-Wi-Fi use, zero fixes, zero map bins, because the app was backgrounded. The map
     * therefore cannot grow as the phone travels, and cell changes cannot be told apart from
     * movement.
     *
     * The gate: position is worth paying for when the phone is actually out in the world, which
     * is to say when cellular holds the default route, or while an excursion is open. Parked on
     * Wi-Fi at home the position does not change and a fix buys nothing.
     *
     * This is a re-asserting loop rather than a pair of one-shot calls because the Map tab drives
     * the same collector and calls `stop()` when it leaves the screen. A single `start()` here
     * would be silently cancelled by the user closing a tab. Both functions are idempotent and
     * synchronized, so asserting the desired state repeatedly is safe and costs one comparison.
     *
     * No `ACCESS_BACKGROUND_LOCATION` grant is involved and none must be requested: a service
     * running as `foregroundServiceType="location"` -- which this one does whenever the
     * permission exists -- is entitled to updates with the UI in the background.
     */
    private fun locationPolicy() = io.launch {
        while (isActive) {
            runCatching {
                val onCellular = LiveState.net.value.transport == "CELLULAR"
                val excursionOpen = ExcursionRecorder.current.value != null
                val bm = getSystemService(android.os.BatteryManager::class.java)
                val pct = bm?.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                // -1 means the device declined to answer, which must not be read as "flat".
                val starved = pct in 0..14 && bm?.isCharging != true

                // Moving matters as much as being off Wi-Fi. On carriage Wi-Fi, or while
                // tethering, cellular does not hold the default route -- and a journey with no
                // speed fix classifies from cell churn alone, which the classifier itself rates
                // WEAK because 16 changes a minute has been recorded walking. A journey is
                // exactly when position is worth its power.
                val moving = runCatching { Mobility.state.value.aboveWalking }.getOrDefault(false)

                // Stationary on Wi-Fi used to mean no position, which was right when a fix became
                // a row in a table: spending power to lengthen a stored movement history nobody
                // asked for. Fixes are transient now -- used to place a reading, then dropped with
                // the process -- so the only remaining cost is battery, and the cost of the gate
                // is a map that stays empty at home, which is where most measuring happens.
                //
                // Still gated on the screen being on. A phone in a pocket is not somewhere its
                // owner is looking at a map, and that is the case worth not paying for.
                val screenOn = runCatching {
                    getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
                }.getOrDefault(false)

                val want = (onCellular || excursionOpen || moving || screenOn) &&
                    !starved &&
                    MapLocationCollector.hasPermission(this@CollectorService)

                // startForService/stopForService, not start/stop: the Map tab holds its own
                // claim on the same collector and must not be cut off by this gate.
                if (want) MapLocationCollector.startForService(this@CollectorService)
                else MapLocationCollector.stopForService()

                // Logged because the interesting branch cannot be exercised from a wireless-adb
                // session -- turning Wi-Fi off to test it is also what ends the session. So the
                // decision states itself, and the next real excursion proves the gate opened.
                val why = when {
                    !MapLocationCollector.hasPermission(this@CollectorService) -> "no permission"
                    starved -> "battery $pct % and not charging"
                    onCellular -> "cellular holds the default route"
                    excursionOpen -> "excursion open"
                    moving -> "moving above walking pace"
                    else -> "Wi-Fi holds the default route, position is static"
                }
                if (why != lastLocationReason) {
                    lastLocationReason = why
                    android.util.Log.i("Collector", "location ${if (want) "ON" else "off"}: $why")
                }
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A start after the service is already running -- the app being opened after a reboot is
        // the case that matters -- is the moment Android permits upgrading to a location service.
        // From the background the attempt simply fails and the current type is kept.
        if (heldType != ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION && hasLocation()) {
            runCatching {
                startForeground(NOTIF_ID, buildNotification("collecting"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                heldType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                LiveState.degraded.value = null
            }
        }
        return START_STICKY
    }

    /** dataSync services are capped at 6 h per 24 h on API 35+; stop cleanly rather than be killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        LiveState.degraded.value = "Collection paused: the 6-hour limit for this mode was reached. " +
                "Grant location permission for uninterrupted collection."
        LiveState.running.value = false
        stopSelf()
    }

    private fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * `location` is the type we want -- it has no timeout. It is only legal while location
     * permission is held, so fall back to `dataSync`, which runs without it at the cost of a
     * 6 h/24 h cap. Returns false if neither can start.
     */
    /** The foreground type currently held, so a later start can tell whether to upgrade. */
    @Volatile private var heldType = 0

    /**
     * Start in the foreground with the best type the current context allows.
     *
     * Tried in order, because which ones are permitted depends on where the start came from:
     *
     *  1. **location** -- full collection including position. Only permitted from a context where
     *     a while-in-use permission is usable: the app open, or already running as location.
     *  2. **specialUse** -- everything except position, with no time cap. This is what a start from
     *     BOOT_COMPLETED lands on: Android 14+ refuses location from boot without
     *     background-location permission, and Android 15+ refuses dataSync from boot outright.
     *  3. **dataSync** -- last resort. Capped at six hours a day, which is why it is no longer
     *     second: a monitoring run should not quietly stop after six hours on a phone where
     *     location was merely unavailable for a moment.
     */
    private fun startForegroundSafely(): Boolean {
        val wantLocation = hasLocation()
        val attempts = buildList {
            if (wantLocation) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION to null)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE to
                if (wantLocation)
                    "Collecting without position: Android does not allow a location service to " +
                        "start here, which is what happens after the phone restarts. Open the app " +
                        "once to resume mapping."
                else
                    "Running without location permission: coverage mapping is off. Grant location " +
                        "for full collection.")
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC to
                "Running in reduced mode: collection stops after 6 hours.")
        }
        for ((type, degraded) in attempts) {
            val note = when (type) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION -> "collecting"
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> "collecting · position paused"
                else -> "collecting · reduced"
            }
            val ok = runCatching { startForeground(NOTIF_ID, buildNotification(note), type) }.isSuccess
            if (ok) {
                heldType = type
                LiveState.degraded.value = degraded
                return true
            }
        }
        LiveState.degraded.value = "Could not start collection. Check app permissions."
        return false
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        runCatching { telephony?.stop() }
        runCatching { connectivity?.stop() }
        // Flushes a journey still in progress, so a ride that ends with the service being
        // killed is still recorded rather than lost.
        runCatching { Mobility.stop() }
        runCatching { HandoverPredictor.stop() }
        runCatching { BearerWarmth.stop() }
        runCatching { CarrierFaults.stop() }
        runCatching { InstrumentHealth.stop() }
        runCatching { com.signalscope.store.BinAggregator.stop() }
        runCatching { EnvironmentContext.stop() }
        runCatching { CarrierAggregation.stop() }
        runCatching { ActionTraffic.stop() }
        // Releases only the service's claim; the Map tab's, if it holds one, survives.
        runCatching { MapLocationCollector.stopForService() }
        telephony = null
        connectivity = null
        LiveState.running.value = false
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Collection", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "SignalScope background collection" }
            )
        }
        // Class.forName() here was a string reference to a class we can name directly: it threw
        // ClassNotFoundException under any rename, and the throw happened inside onCreate() where
        // it kills the service. The component is set explicitly so the intent can never be
        // resolved to anything but our own activity.
        //
        // IMMUTABLE is mandatory rather than stylistic: a mutable PendingIntent handed to the
        // notification shade is a token any holder can rewrite the component of, which is the
        // classic intent-redirection primitive. UPDATE_CURRENT keeps the one instance current
        // instead of leaving a stale extra set behind.
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SignalScope collecting")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "collect"
        private const val NOTIF_ID = 42

        private const val PREFS = "collector"
        private const val KEY_ENABLED = "user_enabled"

        /**
         * Whether the user last left collection on. Read by [BootReceiver], so a restart resumes a
         * run the user started and never resurrects one they stopped. False on a fresh install that
         * has never been opened.
         */
        fun userEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun start(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).apply()
            val i = Intent(ctx, CollectorService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false).apply()
            ctx.stopService(Intent(ctx, CollectorService::class.java))
        }
    }
}
