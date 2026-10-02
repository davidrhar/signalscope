package com.signalscope.store

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * How well data worked in one place, at one time of day.
 *
 * ## Why a place and not a mast
 *
 * A mast is not somewhere you can be. The serving cell changes every couple of seconds and is
 * chosen by the network, so "you are on a bad mast" describes a thing the person did not do and
 * cannot undo. A bin is somewhere they walked into, and will walk into again.
 *
 * It is also the only unit that can look ahead. A bin has neighbours; a mast does not. Knowing a
 * place has been poor makes "the area you are heading into" answerable, which no amount of mast
 * history ever could.
 *
 * ## What this costs, stated plainly
 *
 * This is, deliberately, the thing `map_fix` was dropped in migration 4->5 to avoid, and what the
 * note on [BinAgg.lastSeenDay] warns about: where someone is, against when. Four spatio-temporal
 * points identify 95 % of people. The mitigations are real but they are mitigations, not a defence:
 *
 *  - **Resolution 8**, about 460 m across, never the 66 m the map draws at.
 *  - **Hour of day, not a timestamp.** There is no ordering here and no date. It cannot say which
 *    bin came after which, which is precisely what made a stored fix a journey.
 *  - **It never leaves.** A contribution carries an ISO week and no hour; nothing in this table is
 *    sharable, and no code path sends it.
 *  - **It dies with the rest.** Swept on the same retention as the raw tables, and emptied by
 *    "delete everything collected".
 */
@Entity(tableName = "bin_hour", primaryKeys = ["binId", "hourBucket"])
data class BinHourStat(
    val binId: Long,
    /** 0..23, local time, because the thing being modelled is a person's day and not UTC. */
    @ColumnInfo(name = "hourBucket") val hourBucket: Int,
    val samples: Long,
    val belowZero: Long,
    /** Whole days since the epoch, for retention only -- as coarse as [BinAgg.lastSeenDay]. */
    val lastSeenDay: Int
)
