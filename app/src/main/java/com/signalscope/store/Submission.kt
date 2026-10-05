package com.signalscope.store

import android.content.Context
import java.security.SecureRandom

/**
 * A rotating, opaque name for this installation's submissions -- the smallest thing that makes
 * k-anonymity mean what it says.
 *
 * ## Why this exists at all, having been deliberately avoided
 *
 * Contributions were built to carry no identifier of any kind, and that is still the right
 * instinct. It had one consequence nobody traced through: a bundle is this phone's WHOLE history,
 * re-sent daily, and the server stored each arrival as a separate file and counted one contributor
 * per file. So one phone uploading for five days became five contributors for every area it had
 * ever measured, cleared the floor of three on its own, and multiplied every sample count by five.
 * The control that decides what the world sees was, in practice, not being applied.
 *
 * Refusing an identifier bought less than it looked like, too. A full measurement history IS an
 * identifier: five bundles listing near-identical sets of areas are trivially the same phone,
 * whatever they are called. This makes that linkage explicit and bounded instead of implicit and
 * permanent, and in exchange the floor protects everyone the way the consent screen says it does.
 *
 * ## What it is
 *
 * 128 random bits from [SecureRandom]. Not derived from anything about the device -- no serial, no
 * advertising id, no hash of either -- so it identifies a sequence of uploads and nothing else, and
 * dies with the app's data.
 *
 * ## Rotation, and the handover
 *
 * It changes every [ROTATE_DAYS] days. On the upload that follows a rotation the client names the
 * id it is replacing, and the server deletes that bundle: without it the phone would be counted
 * twice for as long as the old bundle survived retention, which is the exact fault this class was
 * written to fix, reintroduced on a timer.
 *
 * That handover is the one moment an old id and a new one are visible together. It is a single
 * request, the server records nothing from it, and the alternative is a floor that quietly
 * overcounts a third of the time. The server storing one id per phone at a time, changed
 * quarterly, is the thing being bought.
 *
 * The id never appears in an exported bundle -- [Contribution.build] has no knowledge of it. A
 * file someone exports and sends on by hand stays exactly as identifier-free as it was before.
 * Only the upload carries it, and only to the one endpoint.
 */
object Submission {

    private const val PREFS = "submission"
    private const val KEY_ID = "id"
    private const val KEY_BORN = "born"
    private const val KEY_RETIRE = "retire"

    private const val ROTATE_DAYS = 90L
    private const val ROTATE_MS = ROTATE_DAYS * 86_400_000L

    /** The id to send, and the one this upload should replace, if a rotation is pending. */
    data class Ticket(val id: String, val retire: String?)

    @Synchronized
    fun ticket(ctx: Context): Ticket {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val id = p.getString(KEY_ID, null)
        val born = p.getLong(KEY_BORN, 0L)

        // A clock that has gone backwards must not be read as "not due yet" forever, nor as a
        // reason to rotate on every upload. Age is only trusted when it is not negative.
        val age = now - born
        val due = id == null || born <= 0L || age >= ROTATE_MS

        if (!due) return Ticket(id!!, p.getString(KEY_RETIRE, null))

        val fresh = mint()
        val e = p.edit().putString(KEY_ID, fresh).putLong(KEY_BORN, now)
        // Only a real predecessor is retired. The first id on a fresh install replaces nothing.
        if (id != null) e.putString(KEY_RETIRE, id)
        e.apply()
        return Ticket(fresh, if (id != null) id else p.getString(KEY_RETIRE, null))
    }

    /**
     * The server accepted the upload, so the retirement is done.
     *
     * Cleared only on success. A failed upload leaves it pending, because the old bundle is still
     * sitting on the server and would otherwise be counted forever.
     */
    fun confirm(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RETIRE).apply()
    }

    /** Part of "delete everything collected": the next upload is from a phone the server has
     *  never seen, which is what a fresh install means. */
    fun forget(ctx: Context) {
        // The id goes; the pending retirement stays, and if there was none, the current id
        // becomes one. clear() dropped both -- so the bundle already filed under that id sat on
        // the server until retention expired it, still counted toward every published cell, and
        // the next upload under a fresh id made the same phone a SECOND contributor. That is the
        // double count this whole mechanism exists to prevent, reintroduced by the delete path,
        // and it contradicts the privacy page in as many words: "after which your phone is one
        // we have never seen."
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retire = p.getString(KEY_RETIRE, null) ?: p.getString(KEY_ID, null)
        p.edit().clear().apply()
        if (retire != null) p.edit().putString(KEY_RETIRE, retire).apply()
    }

    private fun mint(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
