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
@Entity(tableName = "site_stat", primaryKeys = ["plmn", "site", "band", "hourBucket"])
data class SiteStat(
    val plmn: String,
    /** eNodeB, i.e. Cell Identity >> 8. LTE only; NR's gNB-ID length is not knowable. */
    val site: Long,
    /**
     * The band, in the key, because a mast is not one thing on a phone using carrier aggregation.
     *
     * Measured on the reference device: exactly one cell is registered in every report (119 of
     * 119), on exactly one channel, and it is always the channel ServiceState names as primary --
     * and that single cell changes band every 4.6 seconds, 75 times in 119 readings. The modem
     * offers one answer at a time and flips which carrier it is. No selection rule can be stable
     * because there is never a choice to make, which is why three attempts at one changed nothing.
     *
     * So the record stops pretending there is a single mast. Keyed with the band, mast 4018 on
     * B40 and mast 4018 on B3 are separate records of separate things, which is what they are.
     * Keyed without it they were one blurred average over both -- which is why this card read
     * 42 % for a site the per-band analysis put at 84 % on one carrier.
     *
     * -1 when the band is not known, so a reading is never silently dropped from the record.
     */
    val band: Int,
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
