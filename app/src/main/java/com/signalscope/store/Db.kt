package com.signalscope.store

import android.content.Context
import androidx.room.*

@Entity(tableName = "radio_sample")
data class RadioSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elapsedNanos: Long,
    val wallMillis: Long,
    val subId: Int,
    val mode: String,
    val rat: String,
    val servingPci: Int?,
    val servingCi: Long?,
    val servingTac: Int?,
    val servingArfcn: Int?,
    val bandNum: Int?,
    val mcc: String?,
    val mnc: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val rssnr: Int?,
    val cqi: Int?,
    val timingAdvance: Int?,
    /** AOSP SignalStrength.getLevel() */
    val level: Int?,
    /** The OEM's own bar. Measured to disagree with [level]. Null if not exposed. */
    val vendorLevel: Int?,
    val dataActivity: Int?,
    val neighbourCount: Int?,

    // ---- added in schema 3. Every one is nullable, so rows collected under schema 2 read as
    // "not recorded" rather than as a value: absent data must never read as good data.

    /** What `CellIdentity.getBands()` said, verbatim. Null when the platform returned nothing. */
    val bandReported: Int? = null,
    /** Band derived from [servingArfcn] by [Bands]. The one to key and group on. */
    val bandDerived: Int? = null,
    /**
     * The serving cell identity's own technology, LTE or NR, which is the table [bandDerived] and
     * [servingArfcn] belong to. Not [rat]: that comes from the display network type, can lag a
     * cell change, and during NSA says LTE while an NR leg exists. B40 and n40 are both 40.
     */
    val cellRat: String? = null,
    /**
     * Age of the serving-cell identity at write time: wall-now minus the modem's own measurement
     * time. Null when no registered cell has ever been reported. A cached cell-info report is how a
     * twenty-minute-old identity used to pass as current; this makes that visible per row.
     */
    val cellAgeMs: Long? = null,
    /** NR SS-RSRP / SS-RSRQ / SS-SINR. Separate from the LTE triple so neither overwrites the other. */
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    /** True only when an NR signal entry carried at least one real value. Null on schema-2 rows. */
    val nrPresent: Boolean? = null,
    /** Bitmask, see [Quality]. 0 = assessed and clean; null = written before the checks existed. */
    val qualityFlags: Int? = null,
    /** `ServiceState.getCellBandwidths()` in kHz, comma-joined ("20000,20000" is two-carrier CA). */
    val cellBandwidths: String? = null,
    /** Probability the device is indoors. Reserved: written null until its signal is integrated. */
    val indoorProb: Double? = null,
    /**
     * The position bin this sample was taken in, where one was known at write time.
     *
     * `data-model.md` s3 always specified this as the right home for position, and it has lived in
     * a separate database instead. Adding the column is the first half of moving it back; nothing
     * populates it yet, and [MapBinBuilder] still recovers position by joining on time. That join
     * is the honest one -- it brackets each sample between the fixes around it and declines to
     * place samples it cannot -- so it is not being replaced by a naive "whatever the last fix
     * said", which would silently attach hours-old positions to samples during a GPS gap.
     */
    val positionBinId: Long? = null
)

/**
 * [RadioSample.qualityFlags] bits. Stored, never used to drop a row: a clamped value is still what
 * the platform said, and discarding it would make a weak-signal area look better measured than it
 * was. The bits let a reader exclude or weight instead.
 *
 * The clamp limits are AOSP's (CellSignalStrengthLte / CellSignalStrengthNr): a value sitting
 * exactly on one is usually the modem saturating, not a measurement that happens to be round.
 */
object Quality {
    /** LTE RSRP exactly -140 or -43 dBm. */
    const val RSRP_CLAMPED = 1 shl 0
    /** LTE RSRQ exactly -34 or 3 dB. */
    const val RSRQ_CLAMPED = 1 shl 1
    /** LTE RSSNR exactly -20 or 30 dB. */
    const val RSSNR_CLAMPED = 1 shl 2
    /** Timing advance outside 0..1282, the LTE range. Out of range is a modem artefact, not a distance. */
    const val TA_OUT_OF_RANGE = 1 shl 3
    /** CQI outside 0..15. */
    const val CQI_OUT_OF_RANGE = 1 shl 4
    /** Serving-cell identity older than [STALE_CELL_MS] when the row was written. */
    const val CELL_STALE = 1 shl 5
    /** NR SS-RSRP exactly -140/-44, SS-RSRQ -43/20, or SS-SINR -23/40. */
    const val NR_CLAMPED = 1 shl 6
    /** More than one band contained the channel after regional resolution; see [Bands.nr]. */
    const val BAND_AMBIGUOUS = 1 shl 7
    /** The platform's reported band and the channel-derived band disagree. */
    const val BAND_MISMATCH = 1 shl 8
    /**
     * The latest cell-info report had no registered LTE/NR cell, so the identity columns were
     * written null rather than carrying the previous cell forward.
     */
    const val IDENTITY_WITHHELD = 1 shl 9

    /**
     * Two minutes. The stationary poll requests cell info every 20 s and the modem may answer
     * from cache, so a few tens of seconds is normal; beyond two minutes the identity has missed
     * several refreshes and a cell change inside that window would be invisible.
     */
    const val STALE_CELL_MS = 120_000L
}

/**
 * Up to six strongest non-serving cells from one cell-info report.
 *
 * Its own table rather than the `neighbourJson` column `data-model.md` s3 sketched, so it can be
 * joined on `elapsedNanos` and queried without parsing. Written only when a new cell-info report
 * arrives -- never per signal row -- which is what keeps it bounded. PCI and ARFCN only, never CI:
 * neighbours are measured at the physical layer and the modem does not decode their identity.
 */
@Entity(tableName = "neighbour_cell", indices = [Index("elapsedNanos")])
data class NeighbourCell(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elapsedNanos: Long,
    val wallMillis: Long,
    val subId: Int,
    /** LTE or NR: which raster [arfcn] is on. */
    val rat: String,
    val arfcn: Int?,
    val pci: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    /** LTE RSSNR, or NR SS-SINR. */
    val rssnr: Int?
)

/**
 * What InstrumentHealth concluded about the app's own inputs, over time. Exists so a later
 * analysis can tell a quiet network from a blind instrument -- the failure that cost three
 * experiments before anything recorded it.
 */
@Entity(tableName = "instrument_event")
data class InstrumentEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallMillis: Long,
    val check: String,
    val level: String,
    val detail: String?
)

@Entity(tableName = "registration_event")
data class RegistrationEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elapsedNanos: Long,
    val wallMillis: Long,
    val subId: Int,
    val domain: String,
    val transportType: String,
    val accessNetworkTechnology: String?,
    val regState: String,
    val rejectCause: Int?,
    val nrState: String?,
    val overrideNetworkType: String?,
    val roaming: Boolean,
    val dataState: Int?
)

@Entity(tableName = "link_event")
data class LinkEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elapsedNanos: Long,
    val wallMillis: Long,
    val netId: String,
    val transport: String,
    val isDefault: Boolean,
    val validated: Boolean,
    val notSuspended: Boolean,
    val metered: Boolean,
    val v4Address: String?,
    val v6Address: String?,
    /** The socket-killer flag. */
    val addressChanged: Boolean,
    val dnsServers: String?,
    val mtu: Int?,
    val interfaceName: String?,
    val hasClat: Boolean
)

/**
 * Outcome of an active probe. Deliberately has no cell column: adding one later would mean a
 * destructive migration, and the monotonic `elapsedNanos` already joins cleanly to `radio_sample`,
 * which is exactly what the correlation clock exists for.
 */
@Entity(tableName = "probe_result", indices = [Index("wallMillis")])
data class ProbeResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val elapsedNanos: Long,
    val wallMillis: Long,
    val netId: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val latencyMs: Int,
    val errorCode: String?
)

@Dao
interface CollectorDao {
    @Insert suspend fun insertProbe(s: ProbeResult)
    @Query("SELECT COUNT(*) FROM probe_result") suspend fun probeCount(): Int
    @Query("SELECT * FROM probe_result WHERE id > :sinceId") suspend fun probesSince(sinceId: Int): List<ProbeResult>
    /**
     * Probes since a wall time, answered from an index. `probesSince(0).filter { wallMillis >= x }`
     * materialises the entire table on every call, which grows with retention.
     */
    @Query("SELECT * FROM probe_result WHERE wallMillis >= :wallMillis ORDER BY id ASC")
    suspend fun probesSinceWall(wallMillis: Long): List<ProbeResult>

    @Insert suspend fun insertNeighbours(rows: List<NeighbourCell>)

    @Insert suspend fun insertInstrumentEvent(e: InstrumentEvent)
    @Query("SELECT * FROM instrument_event WHERE wallMillis >= :wallMillis ORDER BY wallMillis ASC")
    suspend fun instrumentEventsSince(wallMillis: Long): List<InstrumentEvent>

    @Insert suspend fun insertRadio(s: RadioSample)
    @Insert suspend fun insertReg(s: RegistrationEvent)
    @Insert suspend fun insertLink(s: LinkEvent)

    @Query("SELECT COUNT(*) FROM radio_sample") suspend fun radioCount(): Int
    @Query("SELECT COUNT(*) FROM registration_event") suspend fun regCount(): Int
    @Query("SELECT COUNT(*) FROM link_event") suspend fun linkCount(): Int
}

@Database(
    entities = [RadioSample::class, RegistrationEvent::class, LinkEvent::class,
        ProbeResult::class, NeighbourCell::class, InstrumentEvent::class],
    version = 5,
    exportSchema = false
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): CollectorDao

    companion object {
        /** Additive only: creates probe_result and touches nothing already collected. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `probe_result` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`elapsedNanos` INTEGER NOT NULL, `wallMillis` INTEGER NOT NULL, " +
                    "`netId` TEXT NOT NULL, `probeType` TEXT NOT NULL, `target` TEXT NOT NULL, " +
                    "`outcome` TEXT NOT NULL, `latencyMs` INTEGER NOT NULL, `errorCode` TEXT)"
                )
            }
        }

        /**
         * Additive only, like 1->2. The device holds collected measurements that cannot be
         * re-collected, so nothing here drops, renames or rebuilds a table: new radio columns are
         * nullable ALTERs (existing rows read as "not recorded"), and the new tables and indices
         * are CREATEs. Verified against a copy of the reference device's real database, with every
         * table's row count identical before and after.
         *
         * No DEFAULT clauses: Room validates a column's default against the entity, and the
         * entities declare none, so a DEFAULT here would fail validation on open -- and there is
         * deliberately no destructive fallback to catch it.
         */
        internal val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE `radio_sample` ADD COLUMN `bandReported` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `bandDerived` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `cellRat` TEXT",
            "ALTER TABLE `radio_sample` ADD COLUMN `cellAgeMs` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `ssRsrp` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `ssRsrq` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `ssSinr` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `nrPresent` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `qualityFlags` INTEGER",
            "ALTER TABLE `radio_sample` ADD COLUMN `cellBandwidths` TEXT",
            "ALTER TABLE `radio_sample` ADD COLUMN `indoorProb` REAL",
            "CREATE TABLE IF NOT EXISTS `neighbour_cell` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`elapsedNanos` INTEGER NOT NULL, `wallMillis` INTEGER NOT NULL, " +
                "`subId` INTEGER NOT NULL, `rat` TEXT NOT NULL, `arfcn` INTEGER, `pci` INTEGER, " +
                "`rsrp` INTEGER, `rsrq` INTEGER, `rssnr` INTEGER)",
            "CREATE INDEX IF NOT EXISTS `index_neighbour_cell_elapsedNanos` " +
                "ON `neighbour_cell` (`elapsedNanos`)",
            "CREATE TABLE IF NOT EXISTS `instrument_event` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `wallMillis` INTEGER NOT NULL, " +
                "`check` TEXT NOT NULL, `level` TEXT NOT NULL, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_probe_result_wallMillis` " +
                "ON `probe_result` (`wallMillis`)"
        )

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * Additive, like the two before it: brings position into this database and adds the column
         * that will eventually carry it per sample.
         *
         * Superseded one migration later by 4->5, which drops `map_fix` again: position stopped
         * being stored at all rather than being stored in a better place. Kept as written because a
         * migration that has run on a device is history, not a draft -- rewriting it to match what
         * the schema eventually became would make the chain lie about what those devices did.
         */
        internal val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE `radio_sample` ADD COLUMN `positionBinId` INTEGER",
            "CREATE TABLE IF NOT EXISTS `map_fix` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`elapsedNanos` INTEGER NOT NULL, `wallMillis` INTEGER NOT NULL, " +
                "`binId` INTEGER NOT NULL, `resolution` INTEGER NOT NULL, " +
                "`accuracyM` REAL NOT NULL, `speedMps` REAL)"
        )

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * Position stops being stored at all.
         *
         * `map_fix` was an ordered, timestamped sequence of bins as fine as ~65 m -- a movement
         * history, whatever it was called, and the one piece of collected data that could identify
         * where its owner lives. Nothing in the diagnosis used it: band, RSRP, RSRQ, SINR, serving
         * cell and neighbours all come from `radio_sample`, and serving-cell identity needs no
         * location permission at all. Position only ever put those numbers on a map, and a map can
         * be drawn from a buffer in memory.
         *
         * So the table is dropped rather than swept, and the rows in it go with it. This is the
         * one migration in the project that destroys collected data, and it does that deliberately:
         * leaving the history in place while claiming not to store position would be worse than
         * either choice made honestly.
         *
         * `positionBinId` on radio_sample stays. It is nullable, nothing writes it, and it costs a
         * byte a row -- and stamping a bin onto a timestamped sample would rebuild exactly the
         * trace this removes, so it stays empty until there is an aggregated home for it.
         */
        internal val MIGRATION_4_5_SQL = listOf("DROP TABLE IF EXISTS `map_fix`")

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach { db.execSQL(it) }
            }
        }

        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "signalscope.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { inst = it }
        }
    }
}
