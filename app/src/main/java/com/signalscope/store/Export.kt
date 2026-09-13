package com.signalscope.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One portable archive of what this device has collected, written to app-private storage and
 * handed out only through an explicit user action.
 *
 * ## The constraint this file is written under
 *
 * **This is the only place data can leave the device, so it is the most conservative thing in the
 * codebase.** `docs/security-review.md` establishes that no collected measurement has an upload
 * path and that every privacy property in this app is a consequence of that. An export is a file
 * that, the moment the user shares it, is in a messaging app or a cloud drive. So:
 *
 * - **Nothing is included that is not already stored.** No device identifier is read, derived or
 *   invented here — no Android ID, no IMEI, no subscriber id, no advertising id, no MAC. The app
 *   does not collect any of them and this file does not start.
 * - **No position, at any resolution.** Not raw — `data-model.md` §5 means there is none to have —
 *   and **not the bins either**. `map_fix` is not in the archive and the roll-ups carry no bin, so
 *   the archive contains no position data of any kind. See [Options.includePositions] for why that
 *   is a default rather than a hard exclusion, and the report for why the default is off.
 * - **An explicit manifest.** `MANIFEST.json` and `README.txt` say what the file contains, what it
 *   deliberately omits, and what a reader can infer from it. A user cannot make a sensible
 *   decision about sharing a file whose contents they have to guess.
 * - **Nothing is written outside app-private storage.** The archive is built in `filesDir`. The
 *   only way it moves is the share sheet or the system file picker, both of which are the user
 *   pressing something.
 *
 * ## What is in it
 *
 * ```
 * MANIFEST.json                     what this file is, what it holds, what it omits
 * README.txt                        the same, for a human who opens the zip
 * rollup/hour/2026-09-05.jsonl      tier-1 roll-ups, one line per bucket (see Compaction)
 * rollup/day/2026-09.jsonl          tier-2 roll-ups, cell identity already dropped
 * raw/radio_sample.csv              whatever raw still exists, i.e. inside the raw window
 * raw/registration_event.csv
 * raw/link_event.csv                minus the address columns; see below
 * raw/probe_result.csv
 * raw/neighbour_cell.csv            strongest non-serving cells per cell-info report (PCI, no CI)
 * raw/instrument_event.csv          when the app's own inputs were degraded or blind
 * ```
 *
 * The roll-up files are decompressed out of their on-disk gzip and re-deflated by the zip, rather
 * than stored as-is. It costs a little CPU and it means someone who opens the archive sees
 * `.jsonl` text rather than a nested container.
 *
 * ## What is deliberately left out, and why
 *
 * | Left out | Why |
 * |---|---|
 * | `map_fix` (binned position history) | The single most sensitive artefact the app holds — the security review names it twice. A timestamped history binned to ~65 m places a dwelling. Nothing in an "export my data" request implies it, so it is not a silent passenger. |
 * | Position bins in roll-ups | There are none: [Compaction] does not carry a bin at any tier. |
 * | `link_event.v4Address` / `v6Address` | A global IPv6 address can embed a stable interface id and identifies a household far more directly than anything else here. |
 * | `link_event.dnsServers` | A resolver set identifies an ISP, an enterprise, or a VPN. |
 * | `link_event.netId` / `interfaceName` | Per-boot handles. No aggregate derives anything from them. |
 * | `probe_result.netId` | Same. |
 * | [DeviceProfile] | Handset model, baseband version and carrier names together are a device fingerprint. It is configuration, not measurement, and the export is of measurements. |
 * | Anything from `IncidentStore` / basemaps | Derived or re-downloadable. An export should carry sources, not conclusions, and not 300 MB of public map tiles. |
 *
 * ## What IS in it, stated plainly because the user should know before they share
 *
 * `radio_sample` carries `mcc`/`mnc` and serving-cell identity per timestamp. That names the
 * carrier and is, over time, a movement history at cell granularity — the security review's P1
 * finding. It is included because without it the export cannot be analysed at all, and it is
 * bounded by the raw window rather than by the life of the install. The manifest says so in the
 * archive, in those words, so the decision to share is an informed one.
 */
object Export {

    const val FORMAT = "signalscope-export"
    const val FORMAT_VERSION = 1

    /** `${applicationId}.export`. See [shareIntent] for what has to exist for this to work. */
    const val PROVIDER_SUFFIX = ".export"

    private const val MIME = "application/zip"

    data class Options(
        /**
         * Include `map_fix` and any position field in the archive.
         *
         * **Off.** Not because position is worthless in an export, but because an export is the one
         * artefact in this app that leaves the device, and a timestamped binned position history is
         * the one thing in it that identifies a person from public data alone. If it is ever wanted
         * it should be its own action with its own confirmation and its own coarsening, not a flag
         * that quietly defaults on. The flag exists so that the decision is visible in the code and
         * reversible in one place rather than buried in an omission.
         *
         * Setting it true today adds nothing: `map_fix` export is not implemented, and the manifest
         * records the flag's value either way so an archive always states which rule produced it.
         */
        val includePositions: Boolean = false,
        /** Include what raw rows still exist. Off would leave an archive of roll-ups only. */
        val includeRaw: Boolean = true,
        /** Keep only the newest archive afterwards. See [prune]. */
        val pruneOlder: Boolean = true
    )

    data class Entry(val path: String, val rows: Long, val bytes: Long)

    data class Result(
        val file: File?,
        val bytes: Long = 0,
        val uncompressedBytes: Long = 0,
        val entries: List<Entry> = emptyList(),
        val error: String? = null
    ) {
        val ok: Boolean get() = file != null && error == null
        /** The point of compressing: this data is extremely repetitive. */
        val ratio: Double? get() =
            if (bytes > 0 && uncompressedBytes > 0) uncompressedBytes.toDouble() / bytes else null
    }

    // =================================================================================
    //  Paths
    // =================================================================================

    fun dir(ctx: Context): File = File(ctx.filesDir, "export").apply { mkdirs() }

    private fun utc(fmt: String) = SimpleDateFormat(fmt, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun nameFor(now: Long) = "signalscope-export-${utc("yyyyMMdd-HHmmss").format(java.util.Date(now))}.zip"

    /** Archives currently sitting in app storage, newest first. */
    fun existing(ctx: Context): List<File> = runCatching {
        (dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".zip") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }.getOrDefault(emptyList())

    /**
     * Keep the newest archive and delete the rest.
     *
     * A stale export is a full copy of the sensitive data sitting in app storage, reachable by
     * every path the security review lists — a forensic image, an unlocked handset, a debuggable
     * build. Keeping one is a convenience; keeping a history of them is an unmanaged second
     * database with no retention policy at all.
     */
    fun prune(ctx: Context, keep: Int = 1): Int = runCatching {
        var n = 0
        existing(ctx).drop(keep.coerceAtLeast(0)).forEach { if (it.delete()) n++ }
        n
    }.getOrDefault(0)

    /** Deletes every archive. For a "I have saved it, remove the copy" button. */
    fun clear(ctx: Context): Int = prune(ctx, keep = 0)

    // =================================================================================
    //  CSV
    // =================================================================================

    /**
     * RFC 4180 quoting. Every value written here is a number or a short enum-ish string from the
     * platform, but a carrier-supplied string reaches `registration_event` and the rule in
     * `security-review.md` C1 is that a hostile carrier's quote must break nothing.
     */
    private fun csv(v: String?): String {
        if (v == null) return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + v.replace("\"", "\"\"") + "\""
        else v
    }

    private fun cell(c: android.database.Cursor, i: Int): String =
        if (c.isNull(i)) "" else csv(c.getString(i))

    /** Streams one query straight into the zip; nothing is held in memory but the current row. */
    private fun writeCsv(
        ctx: Context, zip: ZipOutputStream, path: String, columns: List<String>, sql: String,
        counted: (Long) -> Unit
    ): Long {
        var rows = 0L
        var bytes = 0L
        val db = Db.get(ctx).openHelper.readableDatabase
        zip.putNextEntry(ZipEntry(path))
        val w = OutputStreamWriter(zip, Charsets.UTF_8)
        val header = columns.joinToString(",") + "\n"
        w.write(header); bytes += header.length
        db.query(sql).use { c ->
            val sb = StringBuilder(256)
            while (c.moveToNext()) {
                sb.setLength(0)
                for (i in columns.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(cell(c, i))
                }
                sb.append('\n')
                w.write(sb.toString())
                bytes += sb.length
                rows++
            }
        }
        w.flush()
        zip.closeEntry()
        counted(rows)
        return bytes
    }

    // =================================================================================
    //  Build
    // =================================================================================

    /**
     * Build the archive. Blocking; call from `Dispatchers.IO`. Never throws.
     *
     * Written to a `.part` file and renamed on success, so a kill mid-export leaves no archive
     * rather than a truncated one that looks complete. Same reason [Compaction] writes that way.
     */
    fun build(
        ctx: Context,
        options: Options = Options(),
        now: Long = System.currentTimeMillis()
    ): Result = runCatching {
        val out = File(dir(ctx), nameFor(now))
        val part = File(dir(ctx), out.name + ".part")
        runCatching { part.delete() }

        val entries = ArrayList<Entry>()
        var uncompressed = 0L

        ZipOutputStream(part.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)

            // ---- roll-ups. Decompressed out of their on-disk gzip so the archive holds plain
            // text; the zip's own deflate does the work and the ratio is the same.
            for (tier in Compaction.Tier.entries) {
                for (period in Compaction.periods(ctx, tier)) {
                    val src = File(File(Compaction.root(ctx), tier.dir), "$period.jsonl.gz")
                    if (!src.exists()) continue
                    val path = "rollup/${tier.dir}/$period.jsonl"
                    var bytes = 0L
                    var lines = 0L
                    runCatching {
                        zip.putNextEntry(ZipEntry(path))
                        GZIPInputStream(src.inputStream()).bufferedReader(Charsets.UTF_8).use { r ->
                            val w = OutputStreamWriter(zip, Charsets.UTF_8)
                            var line = r.readLine()
                            while (line != null) {
                                w.write(line); w.write("\n")
                                bytes += line.length + 1
                                lines++
                                line = r.readLine()
                            }
                            w.flush()
                        }
                        zip.closeEntry()
                    }.onFailure { runCatching { zip.closeEntry() } }
                    // Minus the header line, so the count is buckets rather than lines.
                    entries.add(Entry(path, (lines - 1).coerceAtLeast(0), bytes))
                    uncompressed += bytes
                }
            }

            // ---- raw, whatever is left inside the raw window.
            if (options.includeRaw) {
                var rows = 0L
                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/radio_sample.csv",
                        listOf("elapsedNanos", "wallMillis", "subId", "mode", "rat", "servingPci",
                            "servingCi", "servingTac", "servingArfcn", "bandNum", "mcc", "mnc",
                            "rsrp", "rsrq", "rssnr", "cqi", "timingAdvance", "level", "vendorLevel",
                            "dataActivity", "neighbourCount", "bandReported", "bandDerived", "cellRat",
                            "cellAgeMs", "ssRsrp", "ssRsrq", "ssSinr", "nrPresent", "qualityFlags",
                            "cellBandwidths", "indoorProb"),
                        // The schema-3 columns are measurements of what was already here -- the
                        // same cell, its band from its own channel, the NR leg that used to be
                        // overwritten -- so they add analysis, not exposure. indoorProb is a
                        // classification of the environment, not a position; it is null today.
                        "SELECT elapsedNanos, wallMillis, subId, mode, rat, servingPci, servingCi, " +
                            "servingTac, servingArfcn, bandNum, mcc, mnc, rsrp, rsrq, rssnr, cqi, " +
                            "timingAdvance, level, vendorLevel, dataActivity, neighbourCount, " +
                            "bandReported, bandDerived, cellRat, cellAgeMs, ssRsrp, ssRsrq, ssSinr, " +
                            "nrPresent, qualityFlags, cellBandwidths, indoorProb " +
                            "FROM radio_sample ORDER BY elapsedNanos ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/radio_sample.csv", rows, 0))

                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/registration_event.csv",
                        listOf("elapsedNanos", "wallMillis", "subId", "domain", "transportType",
                            "accessNetworkTechnology", "regState", "rejectCause", "nrState",
                            "overrideNetworkType", "roaming", "dataState"),
                        "SELECT elapsedNanos, wallMillis, subId, domain, transportType, " +
                            "accessNetworkTechnology, regState, rejectCause, nrState, " +
                            "overrideNetworkType, roaming, dataState " +
                            "FROM registration_event ORDER BY elapsedNanos ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/registration_event.csv", rows, 0))

                // netId, v4Address, v6Address, dnsServers and interfaceName are not selected. See
                // the table in the class comment: they are the columns that identify a household
                // or an ISP, and nothing downstream reads them in aggregate.
                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/link_event.csv",
                        listOf("elapsedNanos", "wallMillis", "transport", "isDefault", "validated",
                            "notSuspended", "metered", "addressChanged", "mtu", "hasClat"),
                        "SELECT elapsedNanos, wallMillis, transport, isDefault, validated, " +
                            "notSuspended, metered, addressChanged, mtu, hasClat " +
                            "FROM link_event ORDER BY elapsedNanos ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/link_event.csv", rows, 0))

                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/probe_result.csv",
                        listOf("elapsedNanos", "wallMillis", "probeType", "target", "outcome",
                            "latencyMs", "errorCode"),
                        "SELECT elapsedNanos, wallMillis, probeType, target, outcome, latencyMs, " +
                            "errorCode FROM probe_result ORDER BY elapsedNanos ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/probe_result.csv", rows, 0))

                // Neighbours carry PCI and ARFCN only. PCI is a reused physical-layer label, not a
                // global identity, so this is strictly less identifying than the serving-cell
                // columns already in radio_sample, and bounded by the same raw window.
                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/neighbour_cell.csv",
                        listOf("elapsedNanos", "wallMillis", "subId", "rat", "arfcn", "pci",
                            "rsrp", "rsrq", "rssnr"),
                        "SELECT elapsedNanos, wallMillis, subId, rat, arfcn, pci, rsrp, rsrq, rssnr " +
                            "FROM neighbour_cell ORDER BY elapsedNanos ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/neighbour_cell.csv", rows, 0))

                // The instrument's verdicts on itself. Without them an analysis of this archive
                // cannot tell a quiet network from a blind app, which is the error it most needs
                // to avoid. `detail` is the app's own wording about its inputs.
                uncompressed += runCatching {
                    writeCsv(
                        ctx, zip, "raw/instrument_event.csv",
                        listOf("wallMillis", "check", "level", "detail"),
                        "SELECT wallMillis, `check`, level, detail FROM instrument_event " +
                            "ORDER BY wallMillis ASC"
                    ) { rows = it }
                }.getOrDefault(0L)
                entries.add(Entry("raw/instrument_event.csv", rows, 0))
            }

            // ---- manifest last, so it can state the counts it just observed.
            val manifest = manifest(ctx, options, entries, now).toString(2)
            zip.putNextEntry(ZipEntry("MANIFEST.json"))
            OutputStreamWriter(zip, Charsets.UTF_8).apply { write(manifest); flush() }
            zip.closeEntry()
            uncompressed += manifest.length

            val readme = readme(ctx, options, entries, now)
            zip.putNextEntry(ZipEntry("README.txt"))
            OutputStreamWriter(zip, Charsets.UTF_8).apply { write(readme); flush() }
            zip.closeEntry()
            uncompressed += readme.length
        }

        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        if (options.pruneOlder) prune(ctx, keep = 1)

        Result(file = out, bytes = out.length(), uncompressedBytes = uncompressed, entries = entries)
    }.getOrElse {
        runCatching { File(dir(ctx), nameFor(now) + ".part").delete() }
        Result(file = null, error = "${it.javaClass.simpleName}: ${it.message ?: "export failed"}")
    }

    // =================================================================================
    //  Manifest
    // =================================================================================

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})"
    }.getOrDefault("unknown")

    private fun manifest(
        ctx: Context, options: Options, entries: List<Entry>, now: Long
    ): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("formatVersion", FORMAT_VERSION)
        put("rollupFormat", Compaction.FORMAT)
        put("rollupFormatVersion", Compaction.FORMAT_VERSION)
        put("rollupRule", Compaction.RULE_VERSION)
        put("generatedMillis", now)
        put("generatedUtc", utc("yyyy-MM-dd'T'HH:mm:ss'Z'").format(java.util.Date(now)))
        put("app", appVersion(ctx))
        put("source", "SignalScope, on the device that collected it. Nothing was fetched or uploaded to build this file.")

        put("retention", JSONObject().apply {
            put("rawMaxAgeDays", Retention.RAW_MAX_AGE_MS / 86_400_000L)
            put("rawFullAgeDays", Retention.RAW_FULL_AGE_MS / 86_400_000L)
            put("identityMaxAgeDays", Retention.IDENTITY_MAX_AGE_MS / 86_400_000L)
            put("rollupMaxAgeDays", Retention.ROLLUP_MAX_AGE_MS / 86_400_000L)
            put("note", "Cell identity and timing advance exist in no tier older than " +
                "identityMaxAgeDays. Raw older than rawMaxAgeDays has become a roll-up, not been deleted.")
        })

        put("contents", JSONArray().apply {
            for (e in entries) put(JSONObject().apply {
                put("path", e.path); put("rows", e.rows)
            })
        })

        put("includes", JSONArray(listOf(
            "Radio samples: serving-cell identity (ci/pci/tac/arfcn), band, MCC/MNC, and the " +
                "rsrp/rsrq/rssnr/cqi/level readings, per timestamp.",
            "Registration events: registration state, reject causes, NR state, roaming flag.",
            "Link events: transport, validated/suspended/metered flags, address-change flag, MTU.",
            "Probe results: probe type, target, outcome, latency, error text.",
            "Radio samples also carry: band as reported and as derived from the channel number, " +
                "the cell's RAT, the age of the cell reading, NR SS signal values, quality flags, " +
                "and configured carrier bandwidths.",
            "Neighbour cells: up to six strongest non-serving cells per cell-info report, as " +
                "RAT, ARFCN, PCI and signal. No cell identity (CI).",
            "Instrument events: when this app judged its own inputs degraded or blind.",
            "Roll-ups: counts, coverage and histograms per subscription, PLMN, band, RAT — and, " +
                "in the hourly tier only, per serving cell."
        )))

        // The point of this section: a user cannot make a sensible decision about sharing a file
        // whose contents they have to guess at.
        put("readAsPersonalData", JSONArray(listOf(
            "MCC/MNC names the mobile network operator this device used.",
            "Neighbour PCI/ARFCN/signal against timestamps is a weaker form of the same trace as " +
                "serving-cell identity: meaningful only relative to where the device was. It is " +
                "present in raw/neighbour_cell.csv and bounded by the same window.",
            "Serving-cell identity against timestamps is a movement history at cell granularity. " +
                "It is present in raw/radio_sample.csv and in rollup/hour/*.jsonl, and in neither " +
                "case does it extend past " + (Retention.IDENTITY_MAX_AGE_MS / 86_400_000L) + " days.",
            "Treat this archive as personal data about the person carrying the device."
        )))

        put("excluded", JSONArray(listOf(
            "Position of any kind. No coordinate is ever stored by this app (data-model.md §5), " +
                "and the binned position history (map_fix) is not in this archive either. No " +
                "roll-up carries a position bin.",
            "IP addresses (link_event.v4Address / v6Address) and DNS server lists.",
            "Network handles: link_event.netId / interfaceName, probe_result.netId.",
            "Device profile: handset model, baseband version, carrier display names.",
            "Device identifiers of every kind — Android ID, IMEI, subscriber id, advertising id, " +
                "MAC. This app collects none of them and this export derives none.",
            "Basemap tiles and regions: public data, re-downloadable, and not a measurement.",
            "Derived incidents and map bins: conclusions, not sources."
        )))

        put("options", JSONObject().apply {
            put("includePositions", options.includePositions)
            put("includeRaw", options.includeRaw)
        })

        put("rollupSemantics", JSONArray(listOf(
            "One JSON object per line; line 0 is a header. Buckets are tagged by \"k\": " +
                "r=radio, g=registration, l=link, p=probe, i=instrument health.",
            "From rollupRule rollup/v2, radio buckets key on band derived from the channel plus " +
                "\"brat\" (LTE or NR) -- B40 and n40 are both 40. \"qf\" counts quality-flag bits " +
                "out of \"qfA\" assessed samples and \"nr\" counts NR-present samples out of " +
                "\"nrA\"; samples collected before those checks existed are in \"n\" but in neither.",
            "Histograms are sparse maps of value -> sample count. Merge by summing per key. " +
                "Radio quality histograms use 1 dB bins, so percentiles taken from them equal " +
                "percentiles taken from the raw samples.",
            "Probe latency histogram keys are bin lower edges in ms: 10 ms bins below 1000, " +
                "100 ms bins below 10000, then a single 10000 overflow bin. Successes only.",
            "No mean and no percentile is stored — only the counts they are computed from. " +
                "Never average these buckets together; sum them.",
            "\"n\" is the sample count and \"cov\" the number of distinct minutes covered. A " +
                "bucket with few samples or low coverage is a thin measurement, not a good one.",
            "\"cells\" in a daily bucket is a per-day cardinality. Do not sum it across days."
        )))
    }

    private fun readme(ctx: Context, options: Options, entries: List<Entry>, now: Long): String {
        val days = Retention.IDENTITY_MAX_AGE_MS / 86_400_000L
        val list = entries.joinToString("\n") { "  ${it.path}  (${it.rows} rows)" }
        return """
            SignalScope export
            ${utc("yyyy-MM-dd HH:mm:ss 'UTC'").format(java.util.Date(now))}
            App ${appVersion(ctx)}

            This archive was built on the device that collected the data. Nothing was uploaded to
            produce it and nothing was fetched. MANIFEST.json is the machine-readable version of
            everything below.

            CONTENTS
            $list

            THIS IS PERSONAL DATA
            raw/radio_sample.csv and rollup/hour/*.jsonl contain serving-cell identity against
            timestamps. That names the mobile operator and is, read in sequence, a movement history
            at cell granularity. Neither extends past $days days. Treat this file accordingly.

            WHAT IS NOT IN IT
            No position of any kind. This app never stores a coordinate; it stores position bins,
            and the bins are not in this archive either. No IP addresses, no DNS servers, no
            handset model, no baseband version, no device identifier of any kind.

            READING THE ROLL-UPS
            One JSON object per line, header first. Buckets carry counts and sparse histograms,
            never averages, so they can be summed. "n" is how many samples a bucket came from and
            "cov" how many distinct minutes they covered - a bucket with a small n or a small cov
            is a thin measurement and must not be read as a good one.
        """.trimIndent() + "\n"
    }

    // =================================================================================
    //  Getting it off the device — always a user action
    // =================================================================================

    /**
     * `ACTION_SEND` through the system share sheet, wrapped in a chooser.
     *
     * Returns null if the export provider is not declared, rather than throwing: `FileProvider`
     * needs a manifest entry and a paths resource, and neither is in this file's remit. What is
     * required, exactly:
     *
     * ```xml
     * <provider
     *     android:name="androidx.core.content.FileProvider"
     *     android:authorities="${'$'}{applicationId}.export"
     *     android:exported="false"
     *     android:grantUriPermissions="true">
     *     <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
     *                android:resource="@xml/file_paths" />
     * </provider>
     * ```
     * with `res/xml/file_paths.xml` containing exactly one path and no more:
     * ```xml
     * <paths><files-path name="export" path="export/" /></paths>
     * ```
     *
     * `exported="false"` with `grantUriPermissions="true"` is the combination that matters: no
     * other app can reach the provider on its own, and the only access anyone gets is the
     * single-URI, read-only grant this intent carries, scoped to the one directory in `file_paths`.
     * That keeps `security-review.md` A4's finding ("no file export path anywhere in the app")
     * true in spirit — there is still no way for another app to *ask* for this data, only a way for
     * the user to hand one archive over.
     *
     * [callerGrant] exists because `FLAG_GRANT_READ_URI_PERMISSION` on a chooser covers the target
     * the user picks, and nothing else.
     */
    fun shareIntent(ctx: Context, file: File, callerGrant: Boolean = true): Intent? = runCatching {
        val authority = ctx.packageName + PROVIDER_SUFFIX
        if (ctx.packageManager.resolveContentProvider(authority, 0) == null) return null
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // No EXTRA_TEXT and no EXTRA_SUBJECT carrying anything about the device. The filename
            // is a timestamp; the archive says the rest.
            putExtra(Intent.EXTRA_SUBJECT, "SignalScope export")
            if (callerGrant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Share SignalScope export").apply {
            if (callerGrant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    /**
     * Fallback for a caller that would rather use the system file picker than the share sheet:
     * `ACTION_CREATE_DOCUMENT` needs no provider, no manifest entry and no permission, and the
     * user chooses the destination themselves.
     *
     * Launch this with an activity result contract, then hand the returned URI to [copyTo].
     */
    fun createDocumentIntent(fileName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

    /**
     * Copy the archive to a URI the user picked. Blocking; call from `Dispatchers.IO`.
     *
     * This is the only function in the app that writes outside app-private storage, and it can
     * only ever be reached by the user having chosen a destination in the system picker.
     */
    fun copyTo(ctx: Context, dest: Uri, file: File): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(dest)?.use { os ->
            file.inputStream().use { it.copyTo(os) }
            true
        } ?: false
    }.getOrDefault(false)
}
