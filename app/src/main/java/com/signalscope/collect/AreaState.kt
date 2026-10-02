package com.signalscope.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.signalscope.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "You are in a patch where data does not work" -- and, later, "you are out of it".
 *
 * ## Why SINR and not the mast
 *
 * The obvious design was to warn on arriving at a mast whose own record says it is poor at this
 * hour. Three measurements killed it. The serving mast changes every two seconds, so there is no
 * state to be in. Holding out each day and predicting it from the others gives 64 % precision and
 * 66 % recall -- wrong about one warning in three. And there is no warning anyway: 58.5 % of the
 * first ten seconds after arriving on a known-bad mast is already below usable SINR, so the flag
 * fires as the connection degrades rather than before it.
 *
 * A rolling window of SINR has none of those problems. It needs no history, so it works on a fresh
 * install in a country nobody has measured, and it is checkable: across the reference device's own
 * data, 26.2 % of real network probes failed while this state was on and 9.7 % while it was off.
 *
 * ## Where the numbers come from
 *
 * Every threshold below was chosen by replaying nine days of collected readings, not picked for
 * looking reasonable. A ten-minute window entering at 70 % and leaving at 30 %, with a quarter of
 * an hour before it may change its mind again, puts the phone in this state 15 % of the time and
 * speaks three times a day. Shorter windows and tighter hysteresis were measurably chattier for no
 * gain: five minutes at 60/30 gave 7.8 a day and found the same 19 %.
 *
 * ## Why it says nothing on Wi-Fi
 *
 * The claim is about data, and on Wi-Fi the data is fine. Telling someone their connection is poor
 * while their connection is working is how a warning gets switched off. The state still tracks --
 * the Live tab shows it -- but the notification is held. That alone removes a third of them.
 *
 * ## What it does not do
 *
 * It does not say "entering". Nothing in the data supports a warning that arrives before the
 * trouble does, and a notification that claims to see ahead when it cannot is worse than none. It
 * reports a state the phone is already in, which is still worth knowing: it answers *is it me?*
 * while a call is breaking up, and nothing else on the phone answers that.
 */
object AreaState {

    private const val WINDOW_MS = 10 * 60_000L
    private const val MIN_SAMPLES = 30
    private const val ENTER = 0.70
    private const val LEAVE = 0.30

    /** Nothing may change back inside this. The hysteresis stops flapping; this stops nagging. */
    private const val MIN_DWELL_MS = 15 * 60_000L

    private const val CHANNEL = "area"
    private const val NOTIF_ID = 43

    data class Ui(
        val poor: Boolean = false,
        val since: Long = 0L,
        /** Share of the window below usable SINR, for the Live tab. Null before enough samples. */
        val share: Double? = null
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    private val window = ArrayDeque<Pair<Long, Boolean>>()
    private var changedAt = 0L

    /** One reading. Cheap enough to call on every sample; holds only the last ten minutes. */
    @Synchronized
    fun offer(ctx: Context, wallMillis: Long, sinrDb: Int?) {
        if (sinrDb == null) return
        window.addLast(wallMillis to (sinrDb < 0))
        // A clock that jumped backwards would otherwise never expire anything.
        while (window.isNotEmpty() && wallMillis - window.first().first > WINDOW_MS) window.removeFirst()
        if (window.size < MIN_SAMPLES) return

        val share = window.count { it.second }.toDouble() / window.size
        val now = _state.value
        val want = when {
            !now.poor && share >= ENTER -> true
            now.poor && share <= LEAVE -> false
            else -> now.poor
        }
        if (want == now.poor) { _state.value = now.copy(share = share); return }
        if (changedAt != 0L && wallMillis - changedAt < MIN_DWELL_MS) {
            _state.value = now.copy(share = share); return
        }

        val held = if (now.since > 0L) wallMillis - now.since else 0L
        changedAt = wallMillis
        _state.value = Ui(poor = want, since = wallMillis, share = share)
        runCatching { notify(ctx, want, held) }
    }

    /** Forget everything. Used when collection stops, so a restart does not announce a stale state. */
    @Synchronized
    fun reset() {
        window.clear(); changedAt = 0L; _state.value = Ui()
    }

    /**
     * Silent by construction: a low-importance channel makes no sound, no vibration and no
     * heads-up card. It appears in the shade and waits to be read, which is the whole intent --
     * nothing here is urgent, because nothing here can be acted on except by moving or by using
     * Wi-Fi.
     */
    private fun notify(ctx: Context, poor: Boolean, heldMs: Long) {
        // The claim is about data, and on Wi-Fi the data is fine.
        if (LiveState.net.value.transport == "WIFI") return

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Data area", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Entering and leaving places where mobile data does not work well"
                enableVibration(false)
                setSound(null, null)
            }
        )
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (poor) "Entered poor data area" else "Left poor data area"
        val text = if (poor) {
            "Signal quality here is too low for data to work reliably. Wi-Fi avoids it; " +
                "moving a short distance may hand you to another mast."
        } else {
            "Data quality is back to normal" + (words(heldMs)?.let { " after $it" } ?: "") + "."
        }
        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun words(ms: Long): String? = when {
        ms <= 0L -> null
        ms < 90 * 60_000L -> "${(ms / 60_000L).coerceAtLeast(1)} minutes"
        else -> "%.1f hours".format(ms / 3_600_000.0)
    }
}
