package com.signalscope.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Resume collection after the phone restarts, if the user had it running.
 *
 * ## Why this exists
 *
 * The collector restarted after its process was killed (START_STICKY) but never after a reboot,
 * so a multi-day monitoring run would end silently at the first restart -- and Samsung phones
 * restart overnight by default under "Auto optimisation". The data would then show a gap that
 * reads like a phone that stopped being used rather than an app that stopped collecting.
 *
 * ## What it can and cannot restore
 *
 * On Android 14 and later a location foreground service cannot be started from BOOT_COMPLETED
 * without background-location permission, which this app deliberately does not hold; since
 * Android 15 a dataSync one cannot either. So [CollectorService] tries its types in order and,
 * from boot, lands on `specialUse`: telephony, connectivity and probes resume immediately, and
 * position mapping resumes the next time the app is opened -- which is when Android allows a
 * location service to start. InstrumentHealth reports that gap rather than hiding it.
 *
 * ## Safety
 *
 * The receiver must be exported for the system to deliver boot broadcasts to it. BOOT_COMPLETED is
 * a protected broadcast only the system can send, and MY_PACKAGE_REPLACED is delivered only to
 * this app, but an exported receiver can still be sent other intents by any app -- so anything that
 * is not one of those two actions is ignored, and nothing is ever started unless the user
 * previously turned collection on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!CollectorService.userEnabled(context)) return
        runCatching { CollectorService.start(context) }
    }
}
