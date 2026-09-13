package com.signalscope.store

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hierarchical hex indexing — a documented Phase-1 stand-in for H3.
 *
 * ## The deviation, stated up front
 *
 * Every doc in `docs/` says H3, and this is not H3. There is no H3 implementation reachable
 * from the dependencies already in `app/build.gradle.kts`, and the brief forbids adding one,
 * so the alternative was to write the indexing here. What follows is an **aperture-7 hex
 * lattice over Web Mercator**, which reproduces the two properties the design actually leans on:
 *
 *  1. **Exact 7:1 hierarchy.** Every cell has exactly one parent and every parent exactly seven
 *     children (the centre child plus its six neighbours — the same "rosette" H3 uses). This is
 *     what makes the merge walk in `adaptive-aggregation.md` a tree walk rather than a
 *     clustering problem, and it is what makes `7^depth` the correct denominator for the
 *     areal-coverage condition.
 *  2. **The same cell sizes.** H3's average edge length falls by exactly sqrt(7) per resolution,
 *     so sizing res 10 at 65.9 m reproduces H3's res 9/8/7/6 edges (174 / 461 / 1220 / 3229 m)
 *     to within a metre. Bin sizes on this map mean what the docs say they mean.
 *
 * What it does **not** reproduce:
 *
 *  - **Cell ids are not H3 indices** and do not interoperate. Anything shared off-device would
 *    have to be re-binned against a real H3 library first. Nothing is shared off-device in
 *    Phase 1, so nothing is broken today; it is a migration cost, recorded here so it is not a
 *    surprise later. Ids carry a `0xA` nibble in the top bits precisely so they cannot be
 *    mistaken for H3 indices.
 *  - **No icosahedral projection.** H3 projects onto an icosahedron to hold cell area roughly
 *    constant worldwide; this grid is uniform in *Mercator* metres, so a cell's ground size
 *    shrinks by cos(latitude). Near the equator that is
 *    0.03 % and invisible. At 45 deg a "65.9 m" cell is 46 m on the ground. [resForAccuracy]
 *    corrects for this when it picks the storage resolution, so the honesty rule — never bin
 *    finer than the fix supports — holds at every latitude even though the labels drift.
 *  - **Pentagons.** H3 has twelve; this grid has none, which is simpler and, near the poles,
 *    wrong in ways that do not matter for a phone.
 *
 * ## The lattice
 *
 * Pointy-top hexagons on an axial lattice `(q, r)`. A cell centre in Mercator metres is
 * `M_res * (q, r)`, where `M_10` is the ordinary pointy-top basis at circumradius 65.9 m and
 * each coarser level is `M_(res-1) = M_res * V`, with
 *
 * ```
 *      V = [[3, 1], [-1, 2]]      (columns: the parent basis, in child axial coordinates)
 * ```
 *
 * `det V = 7`, so each step up is a sqrt(7) scale and a -19.1066 deg rotation. That is the whole
 * hierarchy; everything else here is bookkeeping.
 */
object MapHex {

    /** Storage never goes finer than this. `adaptive-aggregation.md`: res 10, ~65 m edge. */
    const val RES_FINEST = 10

    /** "Stop at res 6." Without a floor a quiet region collapses into one continent-sized blob. */
    const val RES_FLOOR = 6

    /** Coarsest resolution a *stored* fix may claim; beyond it we store nothing at all. */
    const val RES_COARSEST_STORAGE = 8

    /** Above this accuracy, store `null` rather than a bad bin. `data-model.md` §5. */
    const val ACCURACY_CEILING_M = 300.0

    /** Circumradius (== edge length) of a res-10 cell, in Mercator metres at the equator. */
    private const val EDGE10_M = 65.9

    private const val EARTH_R = 6378137.0

    /** Average edge length in metres, matching H3's own table. */
    fun edgeMetres(res: Int): Double = EDGE10_M * Math.pow(7.0, (RES_FINEST - res) / 2.0)

    fun edgeLabel(res: Int): String {
        val e = edgeMetres(res)
        return if (e >= 1000) String.format("%.1f km", e / 1000.0) else String.format("%.0f m", e)
    }

    // ---------------------------------------------------------------- basis

    // M_10, row-major: x = s*sqrt(3)*(q + r/2), y = s*1.5*r
    private val M10 = doubleArrayOf(
        EDGE10_M * sqrt(3.0), EDGE10_M * sqrt(3.0) / 2.0,
        0.0, EDGE10_M * 1.5
    )

    /** `M_res`, cached per resolution. */
    private val basis: Map<Int, DoubleArray> = buildMap {
        var m = M10
        put(RES_FINEST, m)
        for (res in RES_FINEST - 1 downTo RES_FLOOR) {
            // m = m * V, V = [[3,1],[-1,2]]
            m = doubleArrayOf(
                m[0] * 3 + m[1] * -1, m[0] * 1 + m[1] * 2,
                m[2] * 3 + m[3] * -1, m[2] * 1 + m[3] * 2
            )
            put(res, m)
        }
    }

    /** Cumulative lattice rotation, radians: -19.1066 deg per level above res 10. */
    private fun rotation(res: Int) = (RES_FINEST - res) * -0.333473172

    // ---------------------------------------------------------------- ids

    /**
     * `0xA | res | q | r` packed into a Long. The leading nibble is a deliberate tell: these are
     * not H3 indices and must never be pooled with any that are.
     */
    private const val TAG = 0xAL shl 60
    private const val MASK28 = 0xFFFFFFFL

    fun encode(res: Int, q: Int, r: Int): Long =
        TAG or (res.toLong() shl 56) or
            ((q.toLong() and MASK28) shl 28) or (r.toLong() and MASK28)

    fun resolutionOf(id: Long): Int = ((id ushr 56) and 0xF).toInt()

    private fun signed28(v: Long): Int {
        val x = (v and MASK28).toInt()
        return if (x and 0x8000000 != 0) x or 0xF0000000.toInt() else x
    }

    fun qOf(id: Long): Int = signed28(id ushr 28)
    fun rOf(id: Long): Int = signed28(id)

    /** Short display form. Deliberately not 15 hex chars, so it never reads as an H3 index. */
    fun label(id: Long): String =
        "h${resolutionOf(id)}:${qOf(id)}:${rOf(id)}"

    // ---------------------------------------------------------------- projection

    private fun lngToX(lng: Double) = EARTH_R * Math.toRadians(lng)
    private fun latToY(lat: Double): Double {
        val phi = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return EARTH_R * ln(tan(PI / 4 + phi / 2))
    }

    private fun xToLng(x: Double) = Math.toDegrees(x / EARTH_R)
    private fun yToLat(y: Double) = Math.toDegrees(2 * atan(exp(y / EARTH_R)) - PI / 2)

    // ---------------------------------------------------------------- indexing

    fun latLngToCell(lat: Double, lng: Double, res: Int): Long {
        val m = basis.getValue(res)
        val x = lngToX(lng)
        val y = latToY(lat)
        val det = m[0] * m[3] - m[1] * m[2]
        val qf = (m[3] * x - m[1] * y) / det
        val rf = (-m[2] * x + m[0] * y) / det
        val (q, r) = hexRound(qf, rf)
        return encode(res, q, r)
    }

    /** Cube rounding — the only correct way to snap fractional axial coordinates to a cell. */
    private fun hexRound(qf: Double, rf: Double): Pair<Int, Int> {
        val xf = qf
        val zf = rf
        val yf = -xf - zf
        var rx = Math.round(xf).toInt()
        var ry = Math.round(yf).toInt()
        var rz = Math.round(zf).toInt()
        val dx = abs(rx - xf)
        val dy = abs(ry - yf)
        val dz = abs(rz - zf)
        if (dx > dy && dx > dz) rx = -ry - rz
        else if (dy > dz) ry = -rx - rz
        else rz = -rx - ry
        return rx to rz
    }

    fun cellToLatLng(id: Long): DoubleArray {
        val m = basis.getValue(resolutionOf(id))
        val q = qOf(id).toDouble()
        val r = rOf(id).toDouble()
        val x = m[0] * q + m[1] * r
        val y = m[2] * q + m[3] * r
        return doubleArrayOf(yToLat(y), xToLng(x))
    }

    /** Closed GeoJSON ring, `[lng, lat]`, first point repeated last. */
    fun cellToBoundary(id: Long): Array<DoubleArray> {
        val res = resolutionOf(id)
        val m = basis.getValue(res)
        val cx = m[0] * qOf(id) + m[1] * rOf(id)
        val cy = m[2] * qOf(id) + m[3] * rOf(id)
        val s = edgeMetres(res)
        val rot = rotation(res)
        val out = ArrayList<DoubleArray>(7)
        for (k in 0..5) {
            val a = rot + Math.toRadians(30.0 + 60.0 * k)
            val vx = cx + s * cos(a)
            val vy = cy + s * sin(a)
            out.add(doubleArrayOf(xToLng(vx), yToLat(vy)))
        }
        out.add(out[0])
        return out.toTypedArray()
    }

    // ---------------------------------------------------------------- hierarchy

    /**
     * The 7-cell rosette, keyed by `(q + 3r) mod 7`.
     *
     * `phi(q, r) = (q + 3r) mod 7` is a homomorphism onto Z/7 whose kernel is exactly the parent
     * sublattice — `phi(3,-1) = phi(1,2) = 0`. The centre cell and its six neighbours hit all
     * seven residues exactly once, so this table is a complete, unambiguous set of coset
     * representatives: one parent per child, seven children per parent, no gaps and no overlaps
     * in the *index*. (Geometrically the seven hexagons do not tile the parent hexagon exactly;
     * neither do H3's, and for the same reason.)
     */
    private val ROSETTE: Array<IntArray> = arrayOf(
        intArrayOf(0, 0),   // phi 0
        intArrayOf(1, 0),   // phi 1
        intArrayOf(-1, 1),  // phi 2
        intArrayOf(0, 1),   // phi 3
        intArrayOf(0, -1),  // phi 4
        intArrayOf(1, -1),  // phi 5
        intArrayOf(-1, 0)   // phi 6
    )

    fun cellToParent(id: Long, parentRes: Int): Long {
        var cur = id
        while (resolutionOf(cur) > parentRes) cur = parentOnce(cur)
        return cur
    }

    private fun parentOnce(id: Long): Long {
        val res = resolutionOf(id)
        val q = qOf(id)
        val r = rOf(id)
        val o = ROSETTE[Math.floorMod(q + 3 * r, 7)]
        val q2 = q - o[0]
        val r2 = r - o[1]
        // Exact: (q2, r2) is on the parent sublattice, so both divisions are integral.
        val a = (2 * q2 - r2) / 7
        val b = (q2 + 3 * r2) / 7
        return encode(res - 1, a, b)
    }

    /** Number of res-[RES_FINEST] cells inside a cell at [res]. The areal-coverage denominator. */
    fun childUnits(res: Int): Long {
        var n = 1L
        repeat(RES_FINEST - res) { n *= 7 }
        return n
    }

    // ---------------------------------------------------------------- accuracy

    /**
     * Storage resolution as a function of fix accuracy — `data-model.md` §5.
     *
     * "A 65 m cell recorded against a 100 m fix asserts precision the position does not have."
     * Thresholds are the doc's (40 m / 120 m); the accuracy is first divided by cos(latitude) so
     * that the comparison is against the cell's real *ground* size rather than its Mercator size.
     * Returns null above the ceiling: store nothing rather than a bad bin.
     */
    fun resForAccuracy(accuracyM: Float, lat: Double): Int? {
        val scale = cos(Math.toRadians(lat.coerceIn(-85.0, 85.0))).coerceAtLeast(0.05)
        val a = accuracyM / scale
        return when {
            a <= 40.0 -> 10
            a <= 120.0 -> 9
            a <= ACCURACY_CEILING_M -> RES_COARSEST_STORAGE
            else -> null
        }
    }

    /** Rough ground area of a cell, km², for the detail sheet. */
    fun areaKm2(res: Int, lat: Double): Double {
        val e = edgeMetres(res) * cos(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        return 2.598076 * e * e / 1_000_000.0
    }

    /** Bounding box of a set of cells: `[west, south, east, north]`. */
    fun bounds(ids: Collection<Long>): DoubleArray? {
        if (ids.isEmpty()) return null
        var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
        for (id in ids) for (p in cellToBoundary(id)) {
            if (p[0] < w) w = p[0]
            if (p[0] > e) e = p[0]
            if (p[1] < s) s = p[1]
            if (p[1] > n) n = p[1]
        }
        return doubleArrayOf(w, s, e, n)
    }
}
