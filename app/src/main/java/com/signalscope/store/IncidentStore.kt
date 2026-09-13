package com.signalscope.store

import android.content.Context
import android.database.Cursor

/**
 * Read-side access to the collected rows, for the incident engine.
 *
 * Deliberately does *not* add a Dao to [Db]: `Db.kt` is shared with the other screens and a
 * schema edit would collide. Room's support database is public API, so the queries go through
 * `openHelper.readableDatabase` and map cursors by hand. Nothing here writes.
 *
 * Incidents are also not persisted. `data-model.md` reserves an `incident` table, but the whole
 * derivation runs over a day of rows (~1–2 k) in a few milliseconds, and recomputing means the
 * verdicts always reflect the current rule version rather than whatever was stored under an
 * older one. If this ever gets expensive, cache it — do not cache it yet.
 */
object IncidentStore {

    /** Default analysis window. The mockup's header says "last 24 h"; this is that. */
    const val WINDOW_MS = 24L * 60L * 60L * 1000L

    data class RadioRow(
        val subId: Int, val elapsedNanos: Long, val wallMillis: Long,
        val rat: String, val pci: Int?, val ci: Long?, val band: Int?,
        val rsrp: Int?, val rsrq: Int?, val rssnr: Int?, val cqi: Int?,
        val level: Int?, val vendorLevel: Int?, val dataActivity: Int?,
        val mcc: String?, val mnc: String?
    )

    data class RegRow(
        val subId: Int, val elapsedNanos: Long, val wallMillis: Long,
        val domain: String, val transportType: String, val accessTech: String?,
        val regState: String, val rejectCause: Int?, val nrState: String?,
        val overrideNetworkType: String?, val roaming: Boolean
    )

    data class LinkRow(
        val elapsedNanos: Long, val wallMillis: Long, val netId: String,
        val transport: String, val isDefault: Boolean, val validated: Boolean,
        val notSuspended: Boolean, val metered: Boolean,
        val v4: String?, val v6: String?, val addressChanged: Boolean,
        val dns: String?, val mtu: Int?, val ifname: String?, val hasClat: Boolean
    )

    /** Everything the engine reads, for one window. */
    data class Rows(
        val radio: List<RadioRow>,
        val reg: List<RegRow>,
        val link: List<LinkRow>,
        val windowStartWall: Long,
        val loadedAtWall: Long
    ) {
        val isEmpty: Boolean get() = radio.isEmpty() && reg.isEmpty() && link.isEmpty()

        /** Wall-clock span actually covered by data — never claim 24 h of a 20-minute run. */
        val observedFromWall: Long?
            get() = listOfNotNull(
                radio.firstOrNull()?.wallMillis,
                reg.firstOrNull()?.wallMillis,
                link.firstOrNull()?.wallMillis
            ).minOrNull()

        val observedToWall: Long?
            get() = listOfNotNull(
                radio.lastOrNull()?.wallMillis,
                reg.lastOrNull()?.wallMillis,
                link.lastOrNull()?.wallMillis
            ).maxOrNull()
    }

    /** Blocking. Call from Dispatchers.IO. */
    fun load(ctx: Context, nowWall: Long = System.currentTimeMillis()): Rows {
        val since = nowWall - WINDOW_MS
        val db = Db.get(ctx).openHelper.readableDatabase

        val radio = db.query(
            """SELECT subId, elapsedNanos, wallMillis, rat, servingPci, servingCi, bandNum,
                      rsrp, rsrq, rssnr, cqi, level, vendorLevel, dataActivity, mcc, mnc
               FROM radio_sample WHERE wallMillis >= ? ORDER BY elapsedNanos""",
            arrayOf<Any>(since)
        ).use { c ->
            c.mapAll {
                RadioRow(
                    subId = it.getInt(0), elapsedNanos = it.getLong(1), wallMillis = it.getLong(2),
                    rat = it.getString(3) ?: "—",
                    pci = it.nInt(4), ci = it.nLong(5), band = it.nInt(6),
                    rsrp = it.nInt(7), rsrq = it.nInt(8), rssnr = it.nInt(9), cqi = it.nInt(10),
                    level = it.nInt(11), vendorLevel = it.nInt(12), dataActivity = it.nInt(13),
                    mcc = it.nStr(14), mnc = it.nStr(15)
                )
            }
        }

        val reg = db.query(
            """SELECT subId, elapsedNanos, wallMillis, domain, transportType,
                      accessNetworkTechnology, regState, rejectCause, nrState,
                      overrideNetworkType, roaming
               FROM registration_event WHERE wallMillis >= ? ORDER BY elapsedNanos""",
            arrayOf<Any>(since)
        ).use { c ->
            c.mapAll {
                RegRow(
                    subId = it.getInt(0), elapsedNanos = it.getLong(1), wallMillis = it.getLong(2),
                    domain = it.getString(3) ?: "PS", transportType = it.getString(4) ?: "WWAN",
                    accessTech = it.nStr(5), regState = it.getString(6) ?: "UNKNOWN",
                    rejectCause = it.nInt(7), nrState = it.nStr(8),
                    overrideNetworkType = it.nStr(9), roaming = it.getInt(10) != 0
                )
            }
        }

        val link = db.query(
            """SELECT elapsedNanos, wallMillis, netId, transport, isDefault, validated,
                      notSuspended, metered, v4Address, v6Address, addressChanged,
                      dnsServers, mtu, interfaceName, hasClat
               FROM link_event WHERE wallMillis >= ? ORDER BY elapsedNanos""",
            arrayOf<Any>(since)
        ).use { c ->
            c.mapAll {
                LinkRow(
                    elapsedNanos = it.getLong(0), wallMillis = it.getLong(1),
                    netId = it.getString(2) ?: "—", transport = it.getString(3) ?: "—",
                    isDefault = it.getInt(4) != 0, validated = it.getInt(5) != 0,
                    notSuspended = it.getInt(6) != 0, metered = it.getInt(7) != 0,
                    v4 = it.nStr(8), v6 = it.nStr(9), addressChanged = it.getInt(10) != 0,
                    dns = it.nStr(11), mtu = it.nInt(12), ifname = it.nStr(13),
                    hasClat = it.getInt(14) != 0
                )
            }
        }

        return Rows(radio, reg, link, since, nowWall)
    }

    private fun <T> Cursor.mapAll(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out.add(f(this))
        return out
    }

    private fun Cursor.nInt(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.nLong(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.nStr(i: Int): String? = if (isNull(i)) null else getString(i)
}
