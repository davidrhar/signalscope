package com.signalscope.store

import android.content.Context

/**
 * Whether this phone contributes to the shared map, continuously, until told otherwise.
 *
 * ## One decision, not one per upload
 *
 * Asking before every upload would be theatre: nobody reads the tenth prompt, and a daily
 * interruption trains people to dismiss exactly the thing they should read. So consent is asked
 * once, plainly, and then honoured -- which is also why the asking has to be honest enough to
 * carry the weight of everything that follows it.
 *
 * ## Off is the default, and stays off through updates
 *
 * [enabled] is false until somebody chooses otherwise. An update must never turn it on: people
 * installed this on the promise that nothing leaves the phone, and inheriting a yes they never
 * gave is the single worst thing this file could do. The key is versioned so that if the terms
 * ever materially change, the old answer stops counting and the question is asked again rather
 * than a new meaning being read into an old yes.
 *
 * ## Turning it off stops the future, not the past
 *
 * Contributions already merged into a published cell cannot be withdrawn -- they are summed into
 * an aggregate with other people's and there is nothing left to subtract. Revoking stops anything
 * new being sent. The consent screen says so before the yes, not after, because a person who
 * would not have agreed had they known has not agreed.
 */
object ShareConsent {

    private const val PREFS = "share_consent"
    private const val KEY = "enabled_v1"
    private const val KEY_LAST_UPLOAD = "last_upload_wall"
    private const val KEY_LAST_ERROR = "last_error"

    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
    }

    fun lastUpload(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_UPLOAD, 0L)

    fun lastError(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_ERROR, null)

    fun noteUpload(ctx: Context, wall: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_UPLOAD, wall).remove(KEY_LAST_ERROR).apply()
    }

    /**
     * Record why an upload did not happen.
     *
     * Kept and shown rather than swallowed: a sharing toggle that is on while nothing has ever
     * been sent is the quiet failure this project keeps writing rules against, and the only way a
     * person can tell the difference is if the app says so.
     */
    fun noteError(ctx: Context, reason: String) {
        prefs(ctx).edit().putString(KEY_LAST_ERROR, reason.take(160)).apply()
    }

    /** Forget the last failure, without recording a success. */
    fun clearError(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LAST_ERROR).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
