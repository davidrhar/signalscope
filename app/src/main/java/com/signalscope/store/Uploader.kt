package com.signalscope.store

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Send this phone's contribution to the shared map, once a day, while consent stands.
 *
 * ## Why daily and not continuous
 *
 * "Continuous" in the useful sense means the map keeps up, not that packets keep flowing. The
 * shared map is a weeks-long picture built from aggregates that barely move hour to hour, and the
 * bundle is a few tens of kilobytes. Uploading more often would cost battery and data to publish
 * the same answer.
 *
 * ## Unmetered only
 *
 * A measurement app that quietly spends someone's mobile data to publish measurements about their
 * mobile data would be a poor joke. Wi-Fi, or it waits.
 *
 * ## Failure is recorded, never silent
 *
 * A toggle that is on while nothing has ever been sent is exactly the failure mode this project
 * keeps catching, so the reason is written where the user can see it rather than swallowed.
 */
object Uploader {

    private const val ENDPOINT = "https://signalscope-map.fly.dev/contribute"
    private const val EVERY_MS = 24 * 60 * 60 * 1000L
    private const val CHECK_MS = 30 * 60_000L
    private const val TIMEOUT_MS = 30_000

    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            while (isActive) {
                delay(CHECK_MS)
                runCatching { maybeUpload(app) }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** @return true when something was actually sent. */
    suspend fun maybeUpload(ctx: Context): Boolean {
        if (!ShareConsent.enabled(ctx)) return false
        val since = System.currentTimeMillis() - ShareConsent.lastUpload(ctx)
        if (since < EVERY_MS) return false
        if (!unmetered(ctx)) return false
        return uploadNow(ctx)
    }

    /** The same send the daily job makes, on demand. */
    suspend fun uploadNow(ctx: Context): Boolean {
        if (!ShareConsent.enabled(ctx)) return false
        val body = runCatching { Contribution.build(ctx) }.getOrNull()
        if (body == null) { ShareConsent.noteError(ctx, "could not build a contribution"); return false }
        if (runCatching { org.json.JSONObject(body).getJSONArray("records").length() }
                .getOrDefault(0) == 0) {
            ShareConsent.noteError(ctx, "nothing measured yet to share")
            return false
        }
        return runCatching {
            val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                if (code in 200..299) {
                    ShareConsent.noteUpload(ctx, System.currentTimeMillis())
                    true
                } else {
                    ShareConsent.noteError(ctx, "server said $code")
                    false
                }
            } finally { runCatching { c.disconnect() } }
        }.getOrElse {
            ShareConsent.noteError(ctx, it.message ?: it.javaClass.simpleName)
            false
        }
    }

    private fun unmetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}
