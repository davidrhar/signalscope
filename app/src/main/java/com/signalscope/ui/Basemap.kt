package com.signalscope.ui

import android.content.Context
import com.signalscope.store.RegionStore
import java.io.File

/**
 * Protomaps-only basemap, composed from every archive the phone holds.
 *
 * One archive format serves the whole app: `pmtiles://file://…` reads a local PMTiles archive
 * with no server, no API key and no per-tile network traffic at all.
 *
 * There is deliberately **no online tile fallback**. Protomaps' planet builds are a build service,
 * not a CDN for application traffic — pointing a shipped app at them would repeat exactly the
 * mistake `map-stack.md` identifies with OSM's own tile servers. The network is used once per
 * region, to acquire an archive; after that the basemap is local forever. See
 * [com.signalscope.collect.RegionAcquisition] for where those archives come from and why from
 * where they do.
 *
 * ## Several archives, one style
 *
 * The map is no longer one file. A phone holds a bundled world archive (z0–4, in the APK), a
 * country context archive per country it has been in, and a local-detail archive per 0.5° cell it
 * has actually dwelt in. They are stacked coarse-first: each source draws the same layer set, and
 * a finer archive paints over a coarser one **only where it has tiles**. Outside its bbox
 * MapLibre simply has nothing to draw from it and the coarser archive shows through, which is
 * exactly the behaviour organic growth needs and costs no zoom-range bookkeeping.
 *
 * The style below uses **Protomaps** layer names. An OpenMapTiles/Shortbread style (OpenFreeMap's
 * schema) against a Protomaps archive renders blank with no error, because every `source-layer`
 * silently matches nothing. The two are not interchangeable.
 *
 * ## No labels
 *
 * There are no symbol layers here, because offline glyph PBFs are not bundled. A `text-field`
 * layer with no reachable `glyphs` URL renders nothing and logs nothing — the map looks the same
 * as if the layer were absent. See `docs/region-acquisition.md` for what adding them would cost.
 */
object Basemap {

    /** Archives live in app-specific external storage, so no permission is needed to read them. */
    fun archivesDir(ctx: Context): File = RegionStore.dir(ctx)

    fun archives(ctx: Context): List<File> =
        RegionStore.renderable(ctx).map { File(archivesDir(ctx), it.file) }.filter { it.exists() }

    fun haveArchive(ctx: Context) = archives(ctx).isNotEmpty()

    /** Set once per process, so the crash sweep runs before the first style is ever built. */
    @Volatile private var swept = false

    /** What the crash sweep moved aside on this launch, for the UI to explain rather than hide. */
    @Volatile var quarantinedThisLaunch: List<String> = emptyList(); private set

    /** `pmtiles://` wraps an inner URL. A bare path reaches the HTTP source and fails to parse. */
    private fun uriFor(f: File) = "pmtiles://file://${f.absolutePath}"

    /**
     * Style over every archive proved readable, or bins-on-flat-ground when there are none.
     *
     * Two things happen here that are not about cartography, because this is the one place every
     * path to the renderer goes through:
     *
     * 1. **The crash sweep**, before anything else. If the previous launch died with archives
     *    open, they are moved aside here rather than opened again.
     * 2. **The opening marker**, written with the exact file list just before it is handed over,
     *    and erased by the Map screen once a frame has rendered.
     *
     * MapLibre's PMTiles source aborts the process on an archive it cannot parse. That makes a bad
     * file a permanently dead Map tab, and these two steps are what make it a temporary one.
     */
    fun styleJson(ctx: Context): String {
        if (!swept) {
            swept = true
            quarantinedThisLaunch = runCatching { RegionStore.sweepCrash(ctx) }.getOrDefault(emptyList())
        }
        val regions = RegionStore.renderable(ctx)
            .filter { File(archivesDir(ctx), it.file).exists() }
        if (regions.isEmpty()) return emptyStyleJson()
        RegionStore.markOpening(ctx, regions.map { it.file })

        val sources = regions.mapIndexed { i, r ->
            val f = File(archivesDir(ctx), r.file)
            """"pm$i":{"type":"vector","url":"${uriFor(f)}","attribution":"$ATTRIBUTION"}"""
        }.joinToString(",")

        val layers = StringBuilder()
        layers.append("""{"id":"bg","type":"background","paint":{"background-color":"#0a0e14"}}""")
        regions.indices.forEach { i -> layers.append(",").append(layersFor(i)) }

        return """
        {"version":8,"name":"signalscope-dark",
         "sources":{$sources},
         "layers":[$layers]}
        """.trimIndent()
    }

    /** One archive's worth of the Protomaps layer stack, id-suffixed so several can coexist. */
    private fun layersFor(i: Int): String = """
          {"id":"earth-$i","type":"fill","source":"pm$i","source-layer":"earth",
           "paint":{"fill-color":"#12161d"}},
          {"id":"landuse-$i","type":"fill","source":"pm$i","source-layer":"landuse",
           "paint":{"fill-color":"#161c24","fill-opacity":0.9}},
          {"id":"natural-$i","type":"fill","source":"pm$i","source-layer":"natural",
           "paint":{"fill-color":"#131a1f"}},
          {"id":"water-$i","type":"fill","source":"pm$i","source-layer":"water",
           "paint":{"fill-color":"#0b1d31"}},
          {"id":"buildings-$i","type":"fill","source":"pm$i","source-layer":"buildings",
           "paint":{"fill-color":"#1b212b","fill-opacity":0.85}},
          {"id":"roads-minor-$i","type":"line","source":"pm$i","source-layer":"roads",
           "filter":["!=",["get","kind"],"highway"],
           "paint":{"line-color":"#2f3a49","line-width":["interpolate",["linear"],["zoom"],10,0.4,16,2.0]}},
          {"id":"roads-major-$i","type":"line","source":"pm$i","source-layer":"roads",
           "filter":["==",["get","kind"],"highway"],
           "paint":{"line-color":"#46536a","line-width":["interpolate",["linear"],["zoom"],10,0.9,16,4.0]}},
          {"id":"boundaries-$i","type":"line","source":"pm$i","source-layer":"boundaries",
           "paint":{"line-color":"#3a4252","line-dasharray":[2,2],"line-width":0.8}}
    """.trimIndent()

    /**
     * No archive at all. Bins still render, on flat ground. With the world archive bundled this
     * should now only be reachable if unpacking it failed, but it stays a first-class state
     * rather than an error path — it is also what a stripped build looks like.
     */
    fun emptyStyleJson(): String = """
        {"version":8,"name":"signalscope-nobasemap","sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#0a0e14"}}]}
    """.trimIndent()

    /** ODbL requires this on the map surface; the style's own attribution is not shown by default. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"
}
