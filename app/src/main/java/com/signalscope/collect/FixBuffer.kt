package com.signalscope.collect

import com.signalscope.store.MapFix

/**
 * Where position lives now: memory, for as long as the process does, and nowhere else.
 *
 * ## Why
 *
 * Placing a measurement on a map needs to know where the phone was when it was taken. Keeping that
 * knowledge in a table turns it into something else: `map_fix` held an ordered, timestamped
 * sequence of bins down to ~65 m, which is a movement history whatever it is called, and the
 * project's own security review said so before this file existed.
 *
 * Nothing in the diagnosis ever needed it. Band, RSRP, RSRQ, SINR, serving cell and neighbours all
 * come from `radio_sample`, and serving-cell identity arrives under READ_PHONE_STATE without any
 * location permission at all. Position was only ever the thing that put those numbers on a map.
 *
 * So it stays in RAM. The map builds from this buffer, the buffer dies with the process, and there
 * is no file anywhere on the device that says where its owner has been.
 *
 * ## What that costs, stated plainly
 *
 * Map history does not survive a restart. Bins accumulated over days are gone when the collector
 * is restarted, updated or rebooted, and the map starts again from whatever has been seen since.
 * The fix is not to write positions back to disk -- it is to persist *aggregates*: per-bin
 * counters and histograms with no ordering and no timestamps finer than a day, which is a
 * different object from a trace and is what the shared map needs anyway. That is the next piece of
 * work, not this one.
 *
 * ## Bound
 *
 * A fix is appended on a bin change and at most once a minute otherwise, so the cap below is a few
 * days of continuous use. Oldest entries are dropped first; the map simply loses its oldest bins,
 * which is the same thing retention did to the table.
 */
object FixBuffer {

    /** ~2 days of continuous collection at the one-a-minute floor. A fix is ~48 bytes live. */
    private const val CAP = 3000

    private val fixes = ArrayDeque<MapFix>(256)

    @Synchronized
    fun add(f: MapFix) {
        fixes.addLast(f)
        while (fixes.size > CAP) fixes.removeFirst()
    }

    /** Oldest first, matching the order the builder's time-join expects. */
    @Synchronized
    fun all(): List<MapFix> = fixes.toList()

    @Synchronized
    fun latest(): MapFix? = fixes.lastOrNull()

    @Synchronized
    fun count(): Int = fixes.size

    @Synchronized
    fun clear() = fixes.clear()
}
