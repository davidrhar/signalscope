package com.signalscope.store

import androidx.room.Entity

/**
 * What one mast has been like, at one hour of the day, accumulated.
 *
 * Hour of day is part of the primary key rather than something filtered at read time. The same
 * mast is effectively a different mast at a different hour -- one site on the reference device put
 * 94 % of its morning readings below usable SINR and 2 % of its evening ones -- so a single figure
 * per site would make every statement about that mast wrong for most of the day.
 *
 * Counters and a histogram, never a stored average: these rows are merged by addition as new
 * samples arrive, and a mean of means drifts while a summed histogram stays exact.
 */
@Entity(tableName = "site_stat", primaryKeys = ["plmn", "site", "hourBucket"])
data class SiteStat(
    val plmn: String,
    /** eNodeB, i.e. Cell Identity >> 8. LTE only; NR's gNB-ID length is not knowable. */
    val site: Long,
    /** 0-23, local time on the device when the reading was taken. */
    val hourBucket: Int,
    val samples: Long,
    /** Readings below 0 dB SINR, where real-time traffic stops. */
    val belowZero: Long,
    /** value -> count, as JSON. A real median survives merging; an average would not. */
    val rsrqHist: String,
    /** Whole days since the epoch. A mast's record is not a timeline of visits. */
    val lastSeenDay: Int
)
