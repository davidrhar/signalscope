package com.signalscope.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.MapLocationCollector
import com.signalscope.collect.RegionAcquisition
import com.signalscope.store.Bin
import com.signalscope.store.CAUSES
import com.signalscope.store.MapBinBuilder
import com.signalscope.store.Networks
import com.signalscope.store.SharedMap
import com.signalscope.store.MapHex
import com.signalscope.store.MapModel
import com.signalscope.store.MapProbeJoin
import com.signalscope.store.Outcome
import com.signalscope.store.TimeWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private val Mono = FontFamily.Monospace

/**
 * OpenFreeMap: keyless, no account, no usage limit, ODbL, OSM-derived. `map-stack.md` rejects the
 * hosted free tiers for needing an account and `tile.openstreetmap.org` for forbidding app
 * distribution outright; this is what is left, and the web prototype verified it serves a valid
 * style spec and real vector tiles.
 */
// Protomaps-only: the basemap is a local PMTiles archive, never a remote tile server.
// See ui/Basemap.kt for why there is deliberately no online tile fallback.

/**
 * ODbL requires attribution on the map surface, and this exact string.
 *
 * The prototype found MapLibre composes attribution from the style's own sources, and that the
 * OpenFreeMap style supplies "Data from OpenStreetMap" — which is not the same string. So we
 * supply ours ourselves, permanently visible rather than behind an info tap, and it survives the
 * basemap being switched off (see the note there: with the basemap hidden the tile provider's
 * claim is gone but ours still applies to the bins, which came from a map we still owe nothing
 * to — it is displayed anyway because that costs nothing and under-attributing costs something).
 */
private const val ATTRIBUTION = "© OpenStreetMap contributors"

private enum class MapLayer(val key: String, val label: String) {
    QUALITY("quality", "Quality"),
    RSRP("rsrp", "RSRP"),
    SINR("sinr", "SINR"),
    BAND("band", "Band"),
    ANCHOR("anchor", "Anchor"),
    RES("res", "Res")
}

private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var model by remember { mutableStateOf<MapModel?>(null) }
    var layer by remember { mutableStateOf(MapLayer.QUALITY) }
    var basemap by remember { mutableStateOf(true) }
    /** PLMN to show alone, or null for every network. */
    var onlyNetwork by remember { mutableStateOf<String?>(null) }
    /** Everyone else's measurements, off until asked for. */
    var crowdOn by remember { mutableStateOf(false) }
    var crowd by remember { mutableStateOf<SharedMap.Snapshot?>(null) }
    var crowdBusy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Bin?>(null) }
    var legendOpen by remember { mutableStateOf(false) }
    var tilesRendered by remember { mutableStateOf<Boolean?>(null) }
    var styleError by remember { mutableStateOf<String?>(null) }
    var regionsOpen by remember { mutableStateOf(false) }
    /**
     * Detail panels start CLOSED, because the map is the thing the screen is for.
     *
     * The status strip and the layer panel are each worth reading and together they filled better
     * than half the display, with no way to dismiss either -- so on a phone the map arrived as a
     * strip along the bottom. Collapsed, the strip keeps its one-line summary and the layer panel
     * is a tap away; the numbers were not the problem, occupying the screen unasked was.
     */
    var detailOpen by remember { mutableStateOf(false) }

    val fix by MapLocationCollector.state.collectAsStateWithLifecycle()
    val regions by RegionAcquisition.state.collectAsStateWithLifecycle()

    // Location runs only while this screen is composed. See MapLocationCollector for why.
    DisposableEffect(Unit) {
        MapLocationCollector.start(ctx)
        onDispose { MapLocationCollector.stop() }
    }

    // Basemap acquisition, on the other hand, is started here and never stopped: it runs for the
    // life of the process, which CollectorService keeps alive. It lives here rather than in
    // MainActivity only because this change does not own MainActivity; one call from onCreate
    // would start it at launch instead, and nothing in RegionAcquisition depends on which.
    LaunchedEffect(Unit) { RegionAcquisition.start(ctx) }

    // The merge walk is pure CPU and must never run on the UI thread. It is invalidated by new
    // data, never by a viewport change — panning recomputes nothing.
    LaunchedEffect(Unit) {
        while (true) {
            val m = withContext(Dispatchers.IO) { MapBinBuilder.build(ctx) }
            model = m
            selected = selected?.let { s -> m.bins.firstOrNull { it.id == s.id } }
            delay(15_000)
        }
    }

    val mapState = remember { MapHolder() }

    val mapView = remember {
        MapLibre.getInstance(ctx)
        // textureMode: MapLibre renders into a SurfaceView by default, which composites in its
        // own layer *behind* the app window. Inside a Compose hierarchy that means the GL output
        // never reaches the screen and what you see is the MapView's own load placeholder — a
        // flat pale rectangle, indistinguishable at a glance from "tiles are being blocked".
        // A TextureView draws in-window and composes correctly. This is exactly the failure the
        // brief says to watch for, arriving from the opposite direction: the tiles were fine.
        val opts = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .foregroundLoadColor(android.graphics.Color.parseColor("#07090d"))
        MapView(ctx, opts).apply {
            onCreate(null)
            // The dark style's sprite sheet omits a `wood-pattern` fill image it references.
            // Upstream, cosmetic, one warning per tile that wants it. Absorb with a 1x1.
            addOnStyleImageMissingListener { id ->
                runCatching {
                    mapState.style?.addImage(
                        id, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    )
                }
            }
            addOnDidFailLoadingMapListener { err -> styleError = err }
            // This is the specific failure to watch for: the style loads, the map reports itself
            // rendered, and every tile request was silently blocked. A fully-rendered frame is
            // the nearest thing to evidence that did not happen.
            addOnDidFinishRenderingFrameListener(
                object : MapView.OnDidFinishRenderingFrameListener {
                    override fun onDidFinishRenderingFrame(
                        fully: Boolean, encodingMs: Double, renderingMs: Double
                    ) {
                        if (!fully) return
                        tilesRendered = true
                        // A fully-rendered frame is the proof that the archives currently open did
                        // not kill us, so the note naming them can go. Until this runs, a crash
                        // leaves it behind and the next launch quarantines them.
                        com.signalscope.store.RegionStore.clearOpening(ctx)
                    }
                }
            )
            getMapAsync { map ->
                mapState.attach(map, Basemap.styleJson(ctx)) { bin -> selected = bin }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            // Leaving the tab deliberately is not a crash. Without this, closing the app before
            // the first frame would quarantine perfectly good archives on the next launch.
            com.signalscope.store.RegionStore.clearOpening(ctx)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Geometry is pushed once and the colour expression is swapped; the source is never re-fed
    // to change a layer.
    LaunchedEffect(model) { model?.let { mapState.setData(it) } }
    LaunchedEffect(layer, model) { mapState.setLayer(layer.key) }
    LaunchedEffect(onlyNetwork, model) { mapState.setNetwork(onlyNetwork) }

    // Cache first so the layer is populated before the network answers, then refresh. A failed
    // refresh leaves the cached copy drawn and labelled with its age rather than blanking the
    // layer -- "as of yesterday" beats "nothing", and blanking on a timeout would read as
    // "nobody has measured anything" when the truth is that one request did not come back.
    LaunchedEffect(crowdOn) {
        if (!crowdOn) { mapState.setCrowd(null); return@LaunchedEffect }
        crowdBusy = true
        SharedMap.cached(ctx)?.let { crowd = it; mapState.setCrowd(SharedMap.geoJson(it.cells)) }
        SharedMap.fetch(ctx)?.let { crowd = it; mapState.setCrowd(SharedMap.geoJson(it.cells)) }
        crowdBusy = false
    }
    LaunchedEffect(basemap) { mapState.setBasemap(basemap) }
    LaunchedEffect(selected) { mapState.setSelection(selected?.id) }
    LaunchedEffect(fix.binId) { mapState.setHere(fix.binId) }

    // A region landing changes the style — new source, new layers — so the style is rebuilt and
    // reapplied. `revision` only moves when the set of archives actually changes, so this is not
    // a poll: the first frame after a download completes is the one that shows the new detail.
    LaunchedEffect(regions.revision) {
        if (regions.revision > 0) {
            tilesRendered = null
            val json = withContext(Dispatchers.IO) { Basemap.styleJson(ctx) }
            mapState.setStyleJson(json)
        }
    }

    val m = model

    /**
     * Where "My location" points: the live fix if there is one, otherwise the bin the collector
     * believes the phone is in. Null when neither exists -- the chip is then not drawn at all,
     * rather than drawn and inert.
     *
     * Only bin centres are ever used, never a raw coordinate, which is the same rule the rest of
     * the app follows: the finest thing that exists is a bin.
     */
    val here: Pair<Double, Double>? = when {
        fix.lat != null && fix.lng != null -> fix.lat!! to fix.lng!!
        m?.currentBin != null -> MapHex.cellToLatLng(m.currentBin!!).let { it[0] to it[1] }
        else -> null
    }

    Box(modifier.fillMaxSize().background(T.Page)) {

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Which network's bins are drawn. Null is everything. Built from what the map actually
        // holds rather than a fixed list, so it names only networks this phone has really seen --
        // and quietly disappears on a single-network phone, where the filter would be furniture.
        val networks = remember(m?.bins) {
            m?.bins.orEmpty()
                .filter { it.cls != 0 }
                .groupingBy { it.plmn }.eachCount()
                .entries.filter { it.key != null }
                .sortedByDescending { it.value }
                .mapNotNull { it.key }
        }
        LaunchedEffect(networks) {
            // A filter pinned to a network that has dropped out of the data would show an empty
            // map with no visible cause. Clear it rather than leave the user staring at nothing.
            if (onlyNetwork != null && onlyNetwork !in networks) onlyNetwork = null
        }

        // ---------------------------------------------------------------- top chrome
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MapLayer.entries.forEach { l ->
                    Chip(l.label, layer == l) { layer = l }
                }
                Chip(if (basemap) "Basemap" else "No basemap", !basemap) { basemap = !basemap }
                Chip(regionChipLabel(regions), regionsOpen) { regionsOpen = !regionsOpen }
                Chip(
                    when {
                        crowdBusy -> "Shared…"
                        crowdOn -> "Shared · ${crowd?.cells?.size ?: 0}"
                        else -> "Shared map"
                    },
                    crowdOn
                ) { crowdOn = !crowdOn }
            }
            if (networks.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Chip("All networks", onlyNetwork == null) { onlyNetwork = null }
                    networks.forEach { p ->
                        Chip(Networks.name(p), onlyNetwork == p) {
                            onlyNetwork = if (onlyNetwork == p) null else p
                        }
                    }
                }
            }
            if (m != null) {
                StatusStrip(m, fix, tilesRendered, styleError, regions, detailOpen) {
                    detailOpen = !detailOpen
                }
            }
            // The layer chips now drive this panel. Previously four of the six layers showed a
            // fixed banner and two showed nothing at all, so pressing Quality or Band appeared
            // to do nothing but recolour -- which is what the user reported. It is hidden while
            // a bin sheet or the regions panel is open: those answer the same question about one
            // place, and two panels would fight for the same screen.
            if (m != null && detailOpen && selected == null && !regionsOpen) LayerPanel(m, layer)
            // Empty and sparse are first-class states, not an error screen. It sits in the flow
            // under the status strip rather than floating over it, so the two can never disagree
            // on screen about what has actually been measured.
            if (m != null && m.isEmpty) {
                EmptyState(m, fix, Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }

        // ---------------------------------------------------------------- bottom chrome
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            val sel = selected
            if (regionsOpen) {
                RegionPanel({ regionsOpen = false }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            } else if (sel != null) {
                BinSheet(sel, m, layer) { selected = null }
                Spacer(Modifier.height(6.dp))
            } else if (m != null && !m.isEmpty) {
                if (legendOpen) {
                    Legend(m, layer) { legendOpen = false }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (legendOpen) "Hide legend" else "Legend", legendOpen) {
                        legendOpen = !legendOpen
                    }
                    here?.let { (lat, lng) ->
                        Spacer(Modifier.width(6.dp))
                        Chip("My location", false) { mapState.recentre(lat, lng) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } else if (here != null) {
                // No bins yet, but the phone knows where it is -- the one case where recentring
                // matters most, since there is nothing on screen to orient by.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("My location", false) { mapState.recentre(here.first, here.second) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                ATTRIBUTION, color = T.Dim, fontSize = 9.sp, fontFamily = Mono,
                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                    .background(Color(0xD907090D)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// =====================================================================================
//  The MapLibre side
// =====================================================================================

/**
 * Keeps the renderer behind one small surface — `setData`, `setLayer`, `setSelection` — as
 * `map-stack.md` asks, so swapping MapLibre for something else touches this class and nothing
 * else on the screen.
 */
private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    private var pending: MapModel? = null
    private var pendingNetwork: String? = null
    /** Crowd GeoJSON held until a style exists to put it in, like the other pending state. */
    private var pendingCrowd: String? = null
    private var pendingLayer = "quality"
    private var pendingSel: Long? = null
    private var pendingHere: Long? = null
    private var basemapOn = true
    private var framed = false
    private var onSelect: ((Bin?) -> Unit)? = null
    private var bins: List<Bin> = emptyList()

    fun attach(m: MapLibreMap, styleJson: String, onSelect: (Bin?) -> Unit) {
        map = m
        this.onSelect = onSelect
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false
        m.uiSettings.isRotateGesturesEnabled = false
        m.uiSettings.isTiltGesturesEnabled = false

        setStyleJson(styleJson)

        m.addOnMapClickListener { latLng ->
            val p: PointF = m.projection.toScreenLocation(latLng)
            val hits = m.queryRenderedFeatures(p, "bins-fill")
            val id = hits.firstOrNull()?.getStringProperty("id")
            onSelect(id?.let { s -> bins.firstOrNull { it.id.toString() == s } })
            true
        }
    }

    /**
     * Apply a style, initially or after a region download changes what archives exist.
     *
     * Setting a style discards every source and layer, ours included, so the bin layers are
     * rebuilt here rather than only at attach — otherwise the first acquired region would silently
     * take the overlay away with it, which is a far worse bug than a missing basemap.
     */
    fun setStyleJson(styleJson: String) {
        val m = map ?: return
        framed = framed   // camera is deliberately left where the user put it across a restyle

        m.setStyle(Style.Builder().fromJson(styleJson)) { s ->
            style = s
            // Above every fill and line in the basemap, below its labels — so streets and
            // buildings read *through* a translucent bin instead of being painted over it,
            // and place names stay legible on top.
            //
            // Not "below the first symbol layer", which is the web prototype's rule: OpenFreeMap
            // dark interleaves symbol layers early in the stack, so the first one sits under the
            // road and building fills and the overlay lands at the very bottom. The visible
            // result is a hexagon of solid colour with the whole city drawn on top of it.
            var insertAt = s.layers.size
            for (i in s.layers.indices.reversed()) {
                if (s.layers[i].javaClass.simpleName != "SymbolLayer") { insertAt = i + 1; break }
            }

            s.addSource(GeoJsonSource(SRC, EMPTY_FC))
            s.addSource(GeoJsonSource(SRC_HERE, EMPTY_FC))
            s.addSource(GeoJsonSource(SRC_CROWD, pendingCrowd ?: EMPTY_FC))
            val fill = FillLayer("bins-fill", SRC).withProperties(
                PropertyFactory.fillColor(Expression.get("c_quality")),
                PropertyFactory.fillOpacity(Expression.get("o_quality")),
                PropertyFactory.fillAntialias(true)
            )
            // Stroke weight encodes resolution: the coarser the bin, the heavier its boundary.
            val line = LineLayer("bins-line", SRC).withProperties(
                PropertyFactory.lineColor("#05070a"),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineWidth(
                    Expression.match(
                        Expression.get("res"),
                        Expression.literal(3.4f),
                        Expression.stop(10, Expression.literal(0.6f)),
                        Expression.stop(9, Expression.literal(1.1f)),
                        Expression.stop(8, Expression.literal(1.8f)),
                        Expression.stop(7, Expression.literal(2.6f))
                    )
                )
            )
            val lie = LineLayer("bins-lie", SRC).withProperties(
                PropertyFactory.lineColor("#ff6b6b"),
                PropertyFactory.lineWidth(2.1f),
                PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE)
            ).withFilter(Expression.eq(Expression.get("lie"), Expression.literal(1)))
            val sel = LineLayer("bins-sel", SRC).withProperties(
                PropertyFactory.lineColor("#e9edf4"),
                PropertyFactory.lineWidth(2.4f)
            ).withFilter(Expression.eq(Expression.get("id"), Expression.literal("")))
            // Everyone else's measurements, added BELOW the local bins so your own always win
            // the overlap. The id keeps the `bins-` prefix or setBasemap() would hide it along
            // with the basemap, which is the trap that already caught one layer here.
            val crowdFill = FillLayer("bins-crowd-fill", SRC_CROWD).withProperties(
                PropertyFactory.fillColor(Expression.get("colour")),
                PropertyFactory.fillOpacity(0.34f)
            )
            val crowdLine = LineLayer("bins-crowd-line", SRC_CROWD).withProperties(
                PropertyFactory.lineColor(Expression.get("colour")),
                PropertyFactory.lineOpacity(0.5f),
                PropertyFactory.lineWidth(1.0f)
            )

            val here = LineLayer("here-line", SRC_HERE).withProperties(
                PropertyFactory.lineColor("#4da3ff"),
                PropertyFactory.lineWidth(2.0f),
                PropertyFactory.lineDasharray(arrayOf(2.2f, 1.6f))
            )

            // A SymbolLayer showing the mast count per bin was built here and removed.
            // MapLibre renders no text without a reachable `glyphs` URL, this style has none by a
            // deliberate decision recorded in docs/region-acquisition.md, and the failure is
            // silent -- the layer is added, no error is logged, and nothing appears. Leaving it
            // in would have been a feature that looks present and does nothing, which is the
            // exact failure mode the rest of this codebase is built to avoid.
            //
            // The count itself survives as the `sites` property on every feature and is shown in
            // the bin sheet under "cells serving this bin". Putting it on the map needs a digits-
            // only glyph range in the APK -- a closed set of ten characters, so it would not
            // reintroduce the half-labelled-world problem that ruled glyphs out for place names.

            // Crowd first, so it sits underneath: your own measurements must never be
            // obscured by other people's, and an overlap should read as yours.
            for (l in listOf(crowdFill, crowdLine, fill, line, lie, sel, here)) s.addLayerAt(l, insertAt++)

            pending?.let { setData(it) }
            pendingCrowd?.let { setCrowd(it) }
            setLayer(pendingLayer)
            setNetwork(pendingNetwork)
            setSelection(pendingSel)
            setHere(pendingHere)
            setBasemap(basemapOn)
        }
    }

    /** Draw the shared map, or clear it when [geoJson] is null. */
    fun setCrowd(geoJson: String?) {
        pendingCrowd = geoJson
        style?.getSourceAs<GeoJsonSource>(SRC_CROWD)?.setGeoJson(geoJson ?: EMPTY_FC)
    }

    fun setData(m: MapModel) {
        pending = m
        bins = m.bins
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(m.geoJson)
        if (!framed) frame(m)
    }

    private fun frame(m: MapModel) {
        val mm = map ?: return
        val ids = m.bins.map { it.id }
        when {
            ids.size > 1 -> {
                val b = MapHex.bounds(ids) ?: return
                val bounds = LatLngBounds.from(
                    b[3] + 0.0015, b[2] + 0.0015, b[1] - 0.0015, b[0] - 0.0015
                )
                mm.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 90))
                framed = true
            }
            ids.size == 1 -> {
                val c = MapHex.cellToLatLng(ids[0])
                mm.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c[0], c[1]), 16.0))
                framed = true
            }
            m.currentBin != null -> {
                val c = MapHex.cellToLatLng(m.currentBin)
                mm.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c[0], c[1]), 15.0))
                framed = true
            }
        }
    }

    /**
     * Move the camera to where the phone is now.
     *
     * The map frames itself once, on first data, and then deliberately never moves again -- panning
     * away and having the map yank itself back would be worse than the problem. The cost is that
     * after looking somewhere else, or after coming home from a trip, the map stays where it was
     * left and there is no way back to yourself. This is that way back, and it is driven by a tap
     * rather than by the arrival of a fix, so the map still never moves under the user's hands.
     *
     * [framed] is set so the one-shot auto-framing cannot fire afterwards and move the camera
     * again a moment later.
     */
    fun recentre(lat: Double, lng: Double, zoom: Double = 15.5) {
        val mm = map ?: return
        framed = true
        runCatching {
            mm.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom), 550)
        }
    }

    fun setLayer(key: String) {
        pendingLayer = key
        val s = style ?: return
        s.getLayerAs<FillLayer>("bins-fill")?.setProperties(
            PropertyFactory.fillColor(Expression.get("c_$key")),
            PropertyFactory.fillOpacity(Expression.get("o_$key"))
        )
        s.getLayerAs<LineLayer>("bins-lie")?.setProperties(
            PropertyFactory.visibility(
                if (key == "rsrp") org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE
            )
        )
    }

    /**
     * Draw only one network's bins, or all of them when [plmn] is null.
     *
     * Applied as a source filter on the fill and outline rather than by rebuilding the GeoJSON:
     * the features already carry their PLMN, so switching network is a filter swap on the GPU and
     * not a re-upload of every polygon on every tap.
     *
     * `bins-lie` keeps its own filter and gains this one on top -- a bin that shows full bars and
     * fails is only interesting for the network being looked at.
     */
    fun setNetwork(plmn: String?) {
        pendingNetwork = plmn
        val s = style ?: return
        val f = if (plmn == null) null else Expression.eq(Expression.get("plmn"), Expression.literal(plmn))
        s.getLayerAs<FillLayer>("bins-fill")?.let { if (f == null) it.setFilter(Expression.literal(true)) else it.setFilter(f) }
        s.getLayerAs<LineLayer>("bins-line")?.let { if (f == null) it.setFilter(Expression.literal(true)) else it.setFilter(f) }
        s.getLayerAs<LineLayer>("bins-lie")?.setFilter(
            if (f == null) Expression.eq(Expression.get("lie"), Expression.literal(1))
            else Expression.all(Expression.eq(Expression.get("lie"), Expression.literal(1)), f)
        )
    }

    fun setSelection(id: Long?) {
        pendingSel = id
        style?.getLayerAs<LineLayer>("bins-sel")
            ?.setFilter(Expression.eq(Expression.get("id"), Expression.literal(id?.toString() ?: "")))
    }

    fun setHere(id: Long?) {
        pendingHere = id
        val s = style ?: return
        val src = s.getSourceAs<GeoJsonSource>(SRC_HERE) ?: return
        if (id == null) { src.setGeoJson(EMPTY_FC); return }
        val ring = MapHex.cellToBoundary(id).joinToString(",") { p ->
            "[" + String.format(java.util.Locale.US, "%.6f", p[0]) + "," +
                String.format(java.util.Locale.US, "%.6f", p[1]) + "]"
        }
        src.setGeoJson(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
                """"geometry":{"type":"Polygon","coordinates":[[$ring]]}}]}"""
        )
        if (!framed) map?.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                MapHex.cellToLatLng(id).let { LatLng(it[0], it[1]) }, 15.0
            )
        ).also { framed = true }
    }

    /**
     * `map-stack.md`: "degrade to a plain bin grid with no basemap rather than to a blank screen."
     * Hiding every style layer that is not ours is the same thing the prototype does, and it is
     * what the offline path will look like before a PMTiles archive exists.
     */
    fun setBasemap(on: Boolean) {
        basemapOn = on
        val s = style ?: return
        for (l in s.layers) {
            if (l.id.startsWith("bins-") || l.id.startsWith("here-")) continue
            l.setProperties(
                PropertyFactory.visibility(
                    if (on) org.maplibre.android.style.layers.Property.VISIBLE
                    else org.maplibre.android.style.layers.Property.NONE
                )
            )
        }
    }

    companion object {
        const val SRC = "bins"
        const val SRC_HERE = "here"
        const val SRC_CROWD = "crowd"
        const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
    }
}

// =====================================================================================
//  Compose chrome
// =====================================================================================

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xE607090D))
            .border(1.dp, T.Line, RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        content = content
    )
}

/** Short, live label for the regions chip so acquisition is visible without opening anything. */
private fun regionChipLabel(r: RegionAcquisition.Ui): String {
    val a = r.active
    if (a != null) {
        val p = r.progress
        val pct = if (p != null && p.total > 0) " ${p.done * 100 / p.total}%" else ""
        return "Downloading$pct"
    }
    if (r.queue.isNotEmpty()) return "Queued ${r.queue.size}"
    return "Regions"
}

@Composable
private fun StatusStrip(
    m: MapModel,
    fix: MapLocationCollector.FixState,
    tilesRendered: Boolean?,
    styleError: String?,
    regions: RegionAcquisition.Ui,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(Modifier.padding(horizontal = 10.dp)) {
        Glass(Modifier.clickable { onToggle() }) {
            Text(
                buildString {
                    append("${m.bins.size} bin${if (m.bins.size == 1) "" else "s"}")
                    // Said first, because otherwise every counter after it reads as a description
                    // of the whole map when it only describes the live pass.
                    if (m.restoredBins > 0) append(" · ${m.restoredBins} from earlier")
                    append(" · ${m.leafCount} leaf")
                    append(" · live ${m.radioRows - m.unlocated}/${m.radioRows} samples")
                    // The same fraction by time, since rows over-count the minutes the screen
                    // was on. Omitted rather than shown as 0 % when no time was observed.
                    m.locatedFrac?.let { append(" (${pct(it)} of time)") }
                    append(" · ${m.probeRows - m.probeUnlocated}/${m.probeRows} probes binned")
                    append(" · ${m.fixRows} fix${if (m.fixRows == 1) "" else "es"}")
                },
                color = T.Dim, fontSize = 10.sp, fontFamily = Mono
            )
            // One affordance line, always visible, so the panel does not look inert when shut.
            Spacer(Modifier.height(2.dp))
            Text(
                if (expanded) "tap to collapse ▲" else "tap for detail ▼",
                color = T.Faint, fontSize = 9.sp, fontFamily = Mono
            )
            if (!expanded) return@Glass
            if (m.unlocated > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${m.unlocated} samples had no fix close enough in time to place them " +
                        "(${dur(m.observedMs - m.locatedMs)}) — counted, never guessed at",
                    color = T.Warn, fontSize = 9.sp, fontFamily = Mono
                )
            }
            m.radioCoverage?.let { cov ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "readings stand for ${dur(m.observedMs)} of ${dur(m.spanMs)} " +
                        "(${pct(cov)}); the rest the phone was not being observed",
                    color = if (cov < 0.5) T.Warn else T.Faint, fontSize = 9.sp, fontFamily = Mono
                )
            }
            Spacer(Modifier.height(2.dp))
            // The old line here said "no offline map for this area — bins only", which is true
            // and reads as a fault. It now says what it is waiting for and what will fix it,
            // because with acquisition in place the honest answer is never "nothing will happen".
            val ctxNow = LocalContext.current
            // Both of these read the filesystem, so they are computed off the composition rather
            // than inside it: this strip recomposes on every model tick, and a directory listing
            // plus an archive verification on the UI thread would be a stutter every 15 seconds.
            // -2 means "no fix yet", -1 means "no archive covers this point".
            // Starts optimistic: the alarming line must not flash on screen before the first
            // check has even run.
            val state by produceState(-2 to true, regions.revision, fix.lat, fix.lng) {
                val lat = fix.lat; val lng = fix.lng
                value = withContext(Dispatchers.IO) {
                    val any = Basemap.haveArchive(ctxNow)
                    val d = if (lat == null || lng == null) -2
                    else com.signalscope.store.RegionStore.detailAt(ctxNow, lat, lng)
                    d to any
                }
            }
            val detail = state.first
            val haveAny = state.second
            Text(
                buildString {
                    append(
                        when {
                            styleError != null -> "basemap FAILED: $styleError"
                            !haveAny ->
                                "no basemap at all — bundled world map did not unpack"
                            detail >= 14 -> "local detail here · z0–$detail"
                            detail >= 5 -> "country context here · z0–$detail"
                            regions.active != null -> "downloading detail for this area…"
                            regions.queue.isNotEmpty() ->
                                "no detailed map for this area yet — " +
                                    (regions.blocked ?: "download starting")
                            detail >= 0 -> "world map only here · z0–$detail · detail arrives on Wi-Fi"
                            tilesRendered == true -> "basemap rendering"
                            else -> "basemap loading…"
                        }
                    )
                    append(" · built in ${m.buildMs} ms")
                },
                color = when {
                    styleError != null -> T.Bad
                    regions.active != null -> T.Brand
                    detail in 0..4 || regions.queue.isNotEmpty() -> T.Warn
                    else -> T.Faint
                },
                fontSize = 9.sp, fontFamily = Mono
            )
            if (fix.binId != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "here: ${MapHex.label(fix.binId!!)} · ±${fix.accuracyM?.toInt() ?: "?"} m " +
                        "→ res ${fix.resolution} (${MapHex.edgeLabel(fix.resolution ?: 10)})",
                    color = T.Brand, fontSize = 9.sp, fontFamily = Mono
                )
            }
            if (m.error != null) {
                Spacer(Modifier.height(2.dp))
                Text("build error: ${m.error}", color = T.Bad, fontSize = 9.sp, fontFamily = Mono)
            }
        }
    }
}

/**
 * Title, one paragraph of why, and — since the layer chips drive this — an optional block of the
 * layer's own numbers underneath. The accent bar stretches to whatever the content needs rather
 * than the fixed 46 dp it used to be, so a panel with rows in it is not a bar with a gap below.
 */
@Composable
private fun Banner(
    title: String,
    body: String,
    accent: Color,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(0.dp, 11.dp, 11.dp, 0.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        // The accent is the outer background showing through a 2 dp start inset rather than a
        // sibling Box of a fixed height: with rows of numbers underneath, a fixed-height bar
        // leaves a gap, and asking for an intrinsic height here would be a layout pass to get
        // wrong for a 2 dp stripe.
        Column(
            Modifier.clip(shape).background(accent).border(1.dp, T.Line, shape)
                .padding(start = 2.dp)
        ) {
            Column(
                Modifier.background(Color(0xE607090D))
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            ) {
                Text(title, color = T.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = T.Dim, fontSize = 10.sp, lineHeight = 13.sp)
                if (content != null) {
                    Spacer(Modifier.height(6.dp))
                    content()
                }
            }
        }
    }
}

// =====================================================================================
//  The layer panel — what the selected layer actually says about the survey
// =====================================================================================

private fun pct(v: Double) = "${Math.round(v * 100)}%"

/** Observed time, at the precision it deserves. */
private fun dur(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1000} s"
    ms < 3_600_000L -> "${ms / 60_000L} min"
    else -> String.format(java.util.Locale.US, "%.1f h", ms / 3_600_000.0)
}
private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

/** Pooled probe evidence across a set of bins. Bins are disjoint, so these sums are honest. */
private class Pooled(bins: List<Bin>) {
    val probeN = bins.sumOf { it.probeN }
    val fails = bins.sumOf { it.probeFail }
    val cold = bins.sumOf { it.coldProbeN }
    val coldFail = bins.sumOf { it.coldProbeFail }
    val latency = bins.flatMap { it.probeOkLatencyMs }.sorted()
    val failFrac: Double? get() = if (probeN == 0) null else fails.toDouble() / probeN
    val coldSuccess: Double? get() =
        if (cold == 0) null else (cold - coldFail).toDouble() / cold
}

/**
 * The panel under the chips, per layer.
 *
 * Quality leads with the cold-wake-up rate because it is an outcome the user felt, where every
 * signal figure on the other layers is an input that may or may not have mattered. `excursion-
 * findings.md` §2 first measured a gap between cold and warm probes and its own dated correction
 * then cut that gap to roughly half what was claimed, mostly latency rather than failure -- so the
 * rate is led with as the most direct measurement available, not as a proven predictor.
 */
@Composable
private fun LayerPanel(m: MapModel, layer: MapLayer) {
    val surveyed = m.bins.filter { it.cls != 0 }
    when (layer) {
        MapLayer.QUALITY -> {
            val p = Pooled(m.bins)
            Banner(
                "Coloured by measured outcome.",
                "The verdict is the probe result on the cellular bearer and the validated " +
                    "fraction of the samples cellular actually carried. Signal is a layer you " +
                    "can switch to, not an input — it is measured here, never assumed to " +
                    "predict the outcome.",
                T.Good
            ) {
                ERow(
                    "cold wake-up",
                    p.coldSuccess?.let { s ->
                        val ci = MapBinBuilder.wilson(s, p.cold)
                        "${p.cold - p.coldFail}/${p.cold} ok · ${pct(s)} " +
                            "CI ${f2(ci[0])}–${f2(ci[1])}"
                    } ?: "not measured yet — no cold probe has landed in a bin",
                    when {
                        p.coldSuccess == null -> T.Faint
                        p.coldSuccess!! < MapProbeJoin.SUCCESS_FLOOR -> T.Bad
                        else -> T.Good
                    }
                )
                ERow(
                    "probe latency",
                    if (p.latency.isEmpty()) "not measured"
                    else "p50 ${MapProbeJoin.percentile(p.latency, 0.5)} ms · " +
                        "p90 ${MapProbeJoin.percentile(p.latency, 0.9)} ms " +
                        "(${p.latency.size} ok)",
                    if (p.latency.isEmpty()) T.Faint else T.Dim
                )
                ERow(
                    "failures",
                    p.failFrac?.let { fr ->
                        val ci = MapBinBuilder.wilson(fr, p.probeN)
                        "${p.fails} of ${p.probeN} · ${f3(fr)} CI ${f2(ci[0])}–${f2(ci[1])}"
                    } ?: "no cellular-bound probe binned",
                    if ((p.failFrac ?: 0.0) > 0.0) T.Warn else T.Dim
                )
                if (m.notMeasured > 0) ERow(
                    "not measured",
                    "${m.notMeasured} bin${if (m.notMeasured == 1) "" else "s"} have samples but " +
                        "no cellular-bound evidence — grey, not green",
                    T.Warn
                )
                ERow(
                    "probes binned",
                    "${m.probeRows - m.probeUnlocated}/${m.probeRows}" +
                        if (m.probeUnlocated > 0) " · ${m.probeUnlocated} had no fix" else "",
                    if (m.probeRows == 0) T.Warn else T.Dim
                )
            }
        }
        MapLayer.RSRP -> {
            // Two ways a bin shows full bars and is still bad: no route at four bars, and a cold
            // wake-up that fails at four bars. Counted together because the bar formula misses
            // both, which is the whole reason this layer is drawn.
            val fullBarsFailing = surveyed.count {
                (it.cls == 1 || it.cls == 4) && MapBinBuilder.carrierBars(it.rsrpP50) >= 4
            }
            Banner(
                "Strength, not quality.",
                "Coloured by the platform's default bar formula — thresholds " +
                    "${MapBinBuilder.LTE_BAR_THRESHOLDS.joinToString(",", "[", "]")}, " +
                    "params=1, so the bar is RSRP alone. It measures how loudly the tower " +
                    "reaches you, not whether data gets through. Bins outlined red show full " +
                    "bars here and fail on the quality layer.",
                T.Warn
            ) {
                ERow("surveyed", "${surveyed.size} of ${m.bins.size} bins")
                // "four bars, failing" is wider than the label column and rendered as "four bars,"
                // on a 1080p phone, which reads as a truncated thought rather than a statistic.
                ERow(
                    "failing at 4/4",
                    "$fullBarsFailing bin${if (fullBarsFailing == 1) "" else "s"}",
                    if (fullBarsFailing > 0) T.Bad else T.Dim
                )
                ERow(
                    "median rsrp here",
                    m.contrast.medianBadRsrp?.let { "$it dBm over the time spent in failing bins" }
                        ?: "no failing bin to take a median of",
                    T.Faint
                )
            }
        }
        MapLayer.SINR -> {
            // Pooled over the time spent in surveyed bins, from the bins' own histograms: exact,
            // and not the median of per-bin medians it used to be, which gave a bin crossed in
            // ten seconds the same vote as one lived in for a day.
            val sinrP50 = MapBinBuilder.pooledPercentile(surveyed, 0.5) { it.sinrHist }
            val sinrMs = MapBinBuilder.pooledMs(surveyed) { it.sinrHist }
            Banner(
                "Descriptive, not diagnostic.",
                "Median SINR per bin, over time rather than readings. It answers “can we exchange " +
                    "data” where RSRP only answers “can I hear the tower”. Closer to the thing " +
                    "that fails, and still not the verdict — bins with a bad median have run " +
                    "stretches of usable data, and bins with a good one have dropped. The " +
                    "outcome layer is the verdict; this is the best available explanation of it.",
                T.Warn
            ) {
                ERow(
                    "median, by time",
                    sinrP50?.let {
                        "$it dB over ${dur(sinrMs)} in ${surveyed.count { b -> b.sinrHist.isNotEmpty() }} bins"
                    } ?: "not measured",
                    if (sinrP50 == null) T.Faint else T.Dim
                )
                ERow(
                    "below 0 dB",
                    "${surveyed.count { (it.sinrP50 ?: 0) < 0 }} bins — textbook-bad, and not on " +
                        "its own a finding"
                )
            }
        }
        MapLayer.BAND -> {
            val bands = surveyed.groupingBy { it.band }.eachCount()
                .entries.sortedByDescending { it.value }
            // Share of observed time per band across surveyed bins -- summed from each bin's own
            // band -> ms map, so it counts every band a bin saw, not just its dominant one, and
            // weights by time rather than by how often the screen was on.
            val bandTime = TimeWeight.sum(surveyed.map { it.bandMs })
            val bandTotal = bandTime.values.sum()
            val sites = MapProbeJoin.mergeCells(m.bins.map { it.cells })
            Banner(
                "Which band, and which mast.",
                "Band per bin, taken from the serving cell. Band selection is unavailable at " +
                    "every privilege tier tested, so this is a description of what happened and " +
                    "not a lever.",
                T.Brand
            ) {
                ERow(
                    "dominant band",
                    if (bands.isEmpty()) "none in a surveyed bin"
                    else bands.joinToString(" · ") { (it.key ?: "unknown") + " ×${it.value} bins" }
                )
                ERow(
                    "share of time",
                    if (bandTotal <= 0) "not measured"
                    else bandTime.entries.sortedByDescending { it.value }.joinToString(" · ") {
                        "${it.key} ${pct(it.value.toDouble() / bandTotal)}"
                    } + " of ${dur(bandTotal)} with a band recorded",
                    if (bandTotal <= 0) T.Faint else T.Dim
                )
                ERow(
                    "sites",
                    if (sites.isEmpty()) "no serving cell recorded"
                    else "${sites.mapNotNull { it.enb }.distinct().size} mast(s) · " +
                        "${sites.size} cell(s)"
                )
                ERow("top cell", sites.firstOrNull()?.label ?: "—", T.Faint)
            }
        }
        MapLayer.ANCHOR -> {
            val worst = surveyed.minByOrNull { it.anchorStability }
            Banner(
                "Transport anchor, not NR anchor.",
                "nr_anchor_stability is null on this handset — dual LTE, no NR leg to lose. " +
                    "Shown instead: 1 − (default-route Wi-Fi↔cellular flaps / ceiling), " +
                    "which is the same failure one layer up.",
                T.Warn
            ) {
                ERow(
                    "unstable",
                    "${surveyed.count { it.anchorStability < 0.60 }} bins below 0.60"
                )
                ERow(
                    "worst bin",
                    worst?.let { "${f2(it.anchorStability)} at ${MapHex.label(it.id)}" } ?: "—",
                    T.Faint
                )
                ERow("nr anchor", "null everywhere — absent, not perfect", T.Faint)
            }
        }
        MapLayer.RES -> {
            Banner(
                "Bin size is the confidence encoding.",
                "Storage resolution follows fix accuracy and never moves. This is a derived view: " +
                    "no bin was merged to hit a rendering budget.",
                T.Brand
            ) {
                ERow(
                    "resolutions",
                    (10 downTo 6).filter { (m.resCounts[it] ?: 0) > 0 }
                        .joinToString(" · ") { "res $it ×${m.resCounts[it]}" }
                        .ifEmpty { "none" }
                )
                ERow("leaves", "${m.leafCount} recorded · ${m.bins.size} published")
            }
        }
    }
}

/**
 * The empty and sparse states, which are the honest normal state for a new user and the ones
 * that matter most. A nearly-empty map must read as "nothing measured here yet", never as
 * "something is broken" — so this says exactly what is missing, how far off the thresholds are,
 * and what would fix it.
 */
@Composable
private fun EmptyState(
    m: MapModel,
    fix: MapLocationCollector.FixState,
    modifier: Modifier = Modifier
) {
    Glass(modifier) {
        Text("No bins yet", color = T.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            "A bin appears when this device has measured one place long enough to say " +
                "something about it. Nothing is interpolated in the meantime.",
            color = T.Dim, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(9.dp))
        ERow("samples", "${m.radioRows} radio rows")
        ERow("link rows", "${m.linkRows} default-route events")
        // Probes are the outcome side and they are collected whether or not the map has a fix, so
        // a device can arrive here with plenty of measured outcomes and nowhere to put them.
        ERow(
            "probes", "${m.probeRows} cellular-bound probe results",
            if (m.probeRows == 0) T.Warn else T.Dim
        )
        ERow(
            "fixes", "${m.fixRows} binned position${if (m.fixRows == 1) "" else "s"}",
            if (m.fixRows == 0) T.Warn else T.Dim
        )
        if (m.unlocated > 0) ERow(
            "unlocated", "${m.unlocated} samples had no fix close enough in time", T.Warn
        )
        m.radioCoverage?.let {
            ERow("observed", "${dur(m.observedMs)} of ${dur(m.spanMs)} · ${pct(it)}", T.Faint)
        }
        ERow("needed", "${MapBinBuilder.N_LOCAL} samples in one bin")
        Spacer(Modifier.height(8.dp))
        when {
            !fix.running && fix.note != null ->
                Text(fix.note!!, color = T.Bad, fontSize = 10.sp, fontFamily = Mono)
            m.fixRows == 0 ->
                Text(
                    "Waiting for a position fix — fused provider, balanced accuracy. " +
                        "Indoors this can take a minute.",
                    color = T.Faint, fontSize = 10.sp, lineHeight = 14.sp
                )
            m.unlocated > 0 ->
                Text(
                    "Those samples were taken when no position fix was close enough in time to " +
                        "place them — moving, a fix has to be within seconds — so they have no " +
                        "bin. They are counted, not guessed at.",
                    color = T.Faint, fontSize = 10.sp, lineHeight = 14.sp
                )
            else ->
                Text(
                    "Fixes are arriving. Bins follow once a place has enough samples.",
                    color = T.Faint, fontSize = 10.sp, lineHeight = 14.sp
                )
        }
    }
}

@Composable
private fun Legend(m: MapModel, layer: MapLayer, onClose: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (layer) {
                    MapLayer.QUALITY -> "OUTCOME CLASS"
                    MapLayer.RSRP -> "CARRIER BARS · RSRP ALONE"
                    MapLayer.SINR -> "SINR · MEASURED QUALITY"
                    MapLayer.BAND -> "BAND"
                    MapLayer.ANCHOR -> "TRANSPORT ANCHOR"
                    MapLayer.RES -> "BIN RESOLUTION · DERIVED"
                },
                color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.weight(1f)
            )
            Text("close", color = T.Dim, fontSize = 10.sp,
                modifier = Modifier.clickableNoRipple(onClose))
        }
        Spacer(Modifier.height(6.dp))
        when (layer) {
            MapLayer.SINR -> {
                listOf(
                    "#2f9e6b" to "20 dB and above · excellent",
                    "#46b07a" to "13 to 19 dB · good",
                    "#b8862f" to "5 to 12 dB · fair",
                    "#a8642c" to "0 to 4 dB · marginal",
                    "#b03a42" to "below 0 dB · unusable",
                    "#2a3240" to "not measured"
                ).forEach { (c, l) -> LegendRow(c, l, "") }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Not on the carrier's scale, on purpose: SINR is the check on that scale.",
                    color = T.Faint, fontSize = 9.sp
                )
            }
            MapLayer.QUALITY -> {
                listOf(6, 5, 2, 3, 4, 1, 0).forEach { c ->
                    val o = Outcome.of(c)
                    val n = m.clsCounts[c] ?: 0
                    val note = when {
                        n > 0 -> "$n"
                        c == 5 -> "no NR leg"
                        c == 2 && m.clsCounts.isEmpty() -> "0"
                        // Reachable now that probe_result is joined; 0 here means no bin has
                        // failed a cold wake-up or exceeded p90 1500 ms, not that it cannot.
                        c == 4 -> "0 · probe-driven"
                        else -> "0"
                    }
                    LegendRow(o.colour, o.label, note, dim = n == 0)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Confidence is opacity, never colour. Class 4 is driven by measured probe " +
                        "outcomes — cold-wake-up success and p90 latency — and class 5 stays " +
                        "dimmed because this handset holds no NR leg. Grey covers two different " +
                        "things: too few samples, and samples with nothing riding on the " +
                        "cellular bearer. The bin sheet says which.",
                    color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                )
            }
            MapLayer.RSRP -> {
                LegendRow("#1f9e5a", "≥ −98 dBm", "4 bars")
                LegendRow("#69b53a", "−108 … −98", "3 bars")
                LegendRow("#d8b32e", "−118 … −108", "2 bars")
                LegendRow("#c0562b", "−128 … −118", "1 bar")
                LegendRow("#8e2f28", "< −128 dBm", "0 bars")
                Spacer(Modifier.height(5.dp))
                val c = m.contrast
                Text(
                    "${c.bad} of ${c.surveyed} surveyed bins lose the route; " +
                        "${c.badFullBars} of those show 4 of 4 bars" +
                        (c.medianBadRsrp?.let { ", median ${it} dBm" } ?: "") +
                        ". That gap is why this layer is drawn: the bars agreed, the connection " +
                        "did not.",
                    color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                )
            }
            MapLayer.BAND -> {
                // Every other layer here lists fixed rows, so it always says something. This one is
                // built entirely from the data, and with no surveyed bins it used to render an
                // empty box under a heading -- which reads as a broken panel rather than as an
                // honest "nothing measured yet", and is exactly the failure this project keeps
                // writing rules against.
                val bands = m.bins.filter { it.cls != 0 }.groupingBy { it.band }.eachCount()
                    .entries.sortedByDescending { it.value }
                if (bands.isEmpty()) {
                    val thin = m.bins.count { it.cls == 0 }
                    Text(
                        if (thin > 0)
                            "No band to show yet. $thin bin${if (thin == 1) "" else "s"} " +
                                "measured so far, none with enough readings to stand for a place."
                        else "No bands measured yet. Bins appear once position is running and the " +
                            "radio has been sampled in one place for a while.",
                        color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                    )
                } else {
                    bands.forEach { (b, n) ->
                        LegendRow(MapBinBuilder.bandColour(b), b ?: "band unknown", "$n")
                    }
                }
            }
            MapLayer.ANCHOR -> {
                LegendRow("#2f9e6b", "≥ 0.95 stable", "")
                LegendRow("#5aa85a", "0.88 – 0.95", "")
                LegendRow("#cfa338", "0.75 – 0.88", "")
                LegendRow("#a8642c", "0.55 – 0.75", "")
                LegendRow("#b03a42", "< 0.55 thrashing", "")
            }
            MapLayer.RES -> {
                (10 downTo 6).filter { (m.resCounts[it] ?: 0) > 0 }.forEach { r ->
                    LegendRow(
                        MapBinBuilder.resColour(r),
                        "res $r · ~${MapHex.edgeLabel(r)} edge", "${m.resCounts[r]}"
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "Big bin = we know less here, and what we know is uniform. Not H3 — an " +
                        "aperture-7 hex lattice with H3's cell sizes; see store/MapHex.kt.",
                    color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                )
            }
        }
    }
}

@Composable
private fun LegendRow(colour: String, label: String, count: String, dim: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(hex(colour)))
        Spacer(Modifier.width(7.dp))
        Text(
            label, color = if (dim) T.Faint else T.Dim, fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        Text(count, color = T.Faint, fontSize = 9.sp, fontFamily = Mono)
    }
}

/** Bin detail. Every number here is measured; the merge record says how it was derived. */
@Composable
private fun BinSheet(b: Bin, m: MapModel?, layer: MapLayer, onClose: () -> Unit) {
    val o = Outcome.of(b.cls)
    // Effective n of the time weights, not the row count. See TimeWeight.effectiveN.
    val ci = MapBinBuilder.wilson(b.validatedFrac, b.effectiveN)
    val bars = MapBinBuilder.carrierBars(b.rsrpP50)
    val centre = MapHex.cellToLatLng(b.id)

    // Tall enough that the merge record is reachable without the map disappearing behind it:
    // the derivation is the part of this sheet that makes the number trustworthy, so it must not
    // be the part that is cut off.
    Glass(Modifier.fillMaxWidth().fillMaxHeight(0.52f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(MapHex.label(b.id), color = T.Faint, fontSize = 10.sp, fontFamily = Mono)
                Text(
                    "res ${b.res} · ~${MapHex.edgeLabel(b.res)} edge",
                    color = T.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
            // "Not measured" is its own tag, because THIN and "nothing rode on the bearer" are
            // different findings that both render grey.
            if (b.cls == 0 && !b.hasCellEvidence && b.nObs >= MapBinBuilder.N_LOCAL) {
                Tag("NOT MEASURED", T.Warn)
            } else {
                Tag(o.short, hex(o.colour).takeIf { b.cls != 0 } ?: T.Dim)
            }
            Spacer(Modifier.width(8.dp))
            Text("×", color = T.Faint, fontSize = 15.sp,
                modifier = Modifier.clickableNoRipple(onClose))
        }
        Spacer(Modifier.height(7.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (b.cls == 0) {
                Text(
                    if (b.nObs >= MapBinBuilder.N_LOCAL && !b.hasCellEvidence)
                        "Enough samples, no outcome. Cellular held the default route for 0 of " +
                            "${b.nObs} samples here (Wi-Fi for ${b.wifiRouteSamples}) and no " +
                            "cellular-bound probe landed in this bin, so nothing was riding on " +
                            "the bearer this bin describes. That is \"not measured\", which is " +
                            "not \"good\": the signal numbers further down are what the modem " +
                            "heard, not whether data worked."
                    else
                        "Below the local evidence threshold (n ≥ ${MapBinBuilder.N_LOCAL}). It " +
                            "stays grey until it has been measured, and nothing is interpolated in.",
                    color = T.Dim, fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(6.dp))
            }

            // The chips at the top select the dimension; this is that dimension for this bin, so
            // switching layer with a bin open changes what the sheet leads with.
            LayerFocus(b, layer)
            Spacer(Modifier.height(8.dp))
            OutcomeBlock(b)

            SecHead("evidence", "${b.nObs} samples · ${dur(b.observedMs)} observed")
            ERow(
                "validated",
                f3(b.validatedFrac) + " of the time  CI " + f2(ci[0]) + "–" + f2(ci[1]) +
                    " (n≈${b.effectiveN})  · default route, any transport",
                T.Dim
            )
            ERow(
                "route was",
                "cellular ${dur(b.cellRouteMs)} · wifi ${dur(b.wifiRouteMs)} · other " +
                    dur((b.observedMs - b.cellRouteMs - b.wifiRouteMs).coerceAtLeast(0L)),
                T.Faint
            )
            // The honest one. Never rendered as a number when no cellular-route time stands
            // behind it -- that is the specific lie this rewrite exists to remove.
            ERow(
                "validated·cell",
                b.cellValidatedFrac?.let {
                    f3(it) + " over ${dur(b.cellRouteMs)} on the cellular route " +
                        "(${b.cellRouteSamples} samples)"
                } ?: "not measured — cellular never held the default route here",
                when {
                    b.cellValidatedFrac == null -> T.Warn
                    b.cellValidatedFrac!! < 0.80 -> T.Bad
                    else -> T.Good
                }
            )
            ERow("suspended", f3(b.suspendFrac), if (b.suspendFrac > 0.10) T.Warn else T.Dim)
            ERow(
                "reselect",
                String.format(
                    java.util.Locale.US, "%.2f/min @ %.0f km/h", b.reselectRate, b.speedKph
                ),
                if (b.reselectRate > 2 && b.speedKph < 5) T.Warn else T.Dim
            )
            ERow(
                "transport anchor", f2(b.transportAnchor),
                if (b.transportAnchor < 0.75) T.Bad else T.Dim
            )
            ERow("nr anchor", "null — no NR leg measured", T.Faint)
            // The band label already carries its RAT's prefix ("B" for LTE, "n" for NR).
            ERow("network", "${b.plmn} · ${b.rat}${b.band?.let { " · $it" } ?: ""} · sub ${b.subId}")
            if (b.otherSubSamples > 0) ERow(
                "other sub",
                "${b.otherSubSamples} samples here, kept separate",
                T.Faint
            )
            ERow(
                "dominant cause",
                if (b.topCause == 0) "none dominant"
                else "#${b.topCause} ${CAUSES[b.topCause]} · " +
                    "${(b.causeShare * 100).toInt()}%"
            )

            Spacer(Modifier.height(8.dp))
            CellsBlock(b)

            SecHead("signal", "descriptive")
            ERow(
                "median rsrp",
                (b.rsrpP50?.let { "$it dBm → $bars of 4 bars" } ?: "not measured"),
                if (bars >= 4 && (b.cls == 1 || b.cls == 4)) T.Bad else T.Dim
            )
            ERow("median sinr", b.sinrP50?.let { "$it dB" } ?: "not measured")
            Text(
                "Medians are over time spent here, not over readings, so minutes with the screen " +
                    "on do not outvote the rest. Shown because they are worth seeing, not " +
                    "because they decide anything — the outcome rows above are what this bin " +
                    "was coloured by.",
                color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
            )

            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Color(0x124DA3FF))
                    .border(1.dp, Color(0x384DA3FF), RoundedCornerShape(9.dp))
                    .padding(9.dp)
            ) {
                Text(
                    "RECORD OF THE MERGE", color = T.Brand, fontSize = 9.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(4.dp))
                ERow("resolution", "res ${b.res} · ${MapHex.edgeLabel(b.res)} edge · " +
                    String.format(java.util.Locale.US, "%.3f km²",
                        MapHex.areaKm2(b.res, centre[0])))
                ERow("children", "${b.leafCount} leaf · ${b.childUnits} res-10 units")
                ERow("contributors", "${b.contributorCount} (set union, never a sum)")
                ERow(
                    "would publish",
                    if (b.publishable) "yes"
                    else "no — needs k ≥ ${MapBinBuilder.K_ANON} contributors " +
                        "and n ≥ ${MapBinBuilder.N_CROWD}",
                    if (b.publishable) T.Good else T.Faint
                )
                ERow("rule", MapBinBuilder.RULE_VERSION)
                ERow("reason", b.mergeReason)
            }

            if (m != null && m.bins.size == 1) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "One bin is the honest picture of an hour indoors. It is held at its recorded " +
                        "resolution because the areal-coverage condition blocks the merge: a " +
                        "single leaf covers ${
                            String.format(
                                java.util.Locale.US, "%.0f", 100.0 / 7
                            )
                        }% of its parent, under the 35% floor. That guard is what stops one " +
                        "measurement being painted across 28 km² of ground nobody has crossed.",
                    color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
                )
            }
        }
    }
}

// =====================================================================================
//  Bin sheet blocks
// =====================================================================================

/** A titled block inside the bin sheet — the merge record's visual, with any accent. */
@Composable
private fun SheetBlock(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(9.dp))
            .padding(9.dp)
    ) {
        Text(
            title, color = accent, fontSize = 9.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * What the currently selected layer says about this one bin.
 *
 * This is the other half of the fix for "the layer buttons do nothing": the chips change the
 * colour on the map, the panel under them, and now the top of the bin sheet as well.
 */
@Composable
private fun LayerFocus(b: Bin, layer: MapLayer) {
    val bars = MapBinBuilder.carrierBars(b.rsrpP50)
    when (layer) {
        MapLayer.QUALITY -> {
            val notMeasured = !b.hasCellEvidence
            SheetBlock(
                "QUALITY · THIS BIN",
                if (notMeasured) T.Warn else hex(Outcome.of(b.cls).colour)
            ) {
                ERow(
                    "verdict",
                    if (notMeasured) "not measured — no cellular-bound evidence"
                    else Outcome.of(b.cls).label,
                    if (notMeasured) T.Warn else T.Text
                )
                // Straight out of the classifier's own rule list, so the sheet cannot explain the
                // colour with a condition that is not the one that fired.
                ERow("because", MapBinBuilder.verdictReason(b), T.Dim)
            }
        }
        MapLayer.RSRP -> SheetBlock("RSRP · THIS BIN", T.Bad) {
            ERow(
                "carrier bars",
                b.rsrpP50?.let { "$bars of 4 · median $it dBm" } ?: "not measured",
                if (bars >= 4 && (b.cls == 1 || b.cls == 4)) T.Bad else T.Dim
            )
            ERow(
                "and the outcome",
                if (bars >= 4 && (b.cls == 1 || b.cls == 4))
                    "four bars, and failing anyway"
                else "${Outcome.of(b.cls).label}, on measured outcomes",
                T.Dim
            )
        }
        MapLayer.SINR -> SheetBlock("SINR · THIS BIN", T.Warn) {
            ERow("median sinr", b.sinrP50?.let { "$it dB" } ?: "not measured")
            ERow(
                "predicts",
                b.coldSuccessFrac?.let { "nothing here — cold wake-up ${pct(it)} is the measured one" }
                    ?: "nothing measured to compare it against yet",
                T.Faint
            )
        }
        MapLayer.BAND -> SheetBlock("BAND · THIS BIN", T.Brand) {
            ERow(
                "dominant band",
                b.band?.let { band ->
                    val total = b.bandMs.values.sum()
                    val share = TimeWeight.fraction(b.bandMs[band] ?: 0L, total)
                    band + (share?.let { " · ${pct(it)} of the time with a band recorded" } ?: "")
                } ?: "not recorded"
            )
            ERow(
                "served by",
                if (b.cells.isEmpty()) "no serving-cell identity recorded"
                else "${b.cells.mapNotNull { it.enb }.distinct().size} site(s) · " +
                    "${b.cells.size} cell(s) — see below"
            )
        }
        MapLayer.ANCHOR -> SheetBlock("ANCHOR · THIS BIN", T.Warn) {
            ERow(
                "transport anchor", f2(b.transportAnchor),
                if (b.transportAnchor < 0.60) T.Bad else T.Dim
            )
            ERow(
                "wifi ↔ cellular",
                "route was wifi for ${dur(b.wifiRouteMs)} of ${dur(b.observedMs)} observed",
                T.Faint
            )
        }
        MapLayer.RES -> SheetBlock("RESOLUTION · THIS BIN", T.Brand) {
            ERow("res", "${b.res} · ~${MapHex.edgeLabel(b.res)} edge")
            ERow("held here because", b.mergeReason, T.Dim)
        }
    }
}

/**
 * The block this change exists for: what happened to traffic that was actually on the cellular
 * bearer in this bin.
 *
 * Cold wake-up leads because it is the closest thing here to what a user actually notices: a bin
 * whose cold probes fail is a bin where apps stall on resume, and no signal metric shows it. The
 * reference device did measure cold probes failing more often than warm ones, but read
 * `excursion-findings.md` §2 with its dated correction attached -- the effect there was about half
 * the size first reported and mostly a delay rather than a failure.
 */
@Composable
private fun OutcomeBlock(b: Bin) {
    val cold = b.coldSuccessFrac
    val accent = when {
        b.probeN == 0 -> T.Warn
        cold != null && cold < MapProbeJoin.SUCCESS_FLOOR -> T.Bad
        b.probeFail > 0 -> T.Warn
        else -> T.Good
    }
    SheetBlock("MEASURED OUTCOME · CELLULAR-BOUND", accent) {
        if (b.probeN == 0) {
            Text(
                "No cellular-bound probe has landed in this bin" +
                    (if (b.probeNoBearer > 0)
                        " — ${b.probeNoBearer} probe(s) here found no cellular network at all, " +
                            "which is a fact about the device and not about this place"
                    else "") +
                    ". Probes fire from the collector every 45 s off Wi-Fi and every 90 s to 5 " +
                    "min on it, so a bin fills with time spent in it. Until then there is no " +
                    "outcome here and none is being claimed.",
                color = T.Dim, fontSize = 10.sp, lineHeight = 13.sp
            )
        } else {
            ERow(
                "cold wake-up",
                cold?.let {
                    val ci = b.coldSuccessCi
                    "${b.coldProbeN - b.coldProbeFail}/${b.coldProbeN} ok · ${pct(it)} " +
                        "CI ${f2(ci[0])}–${f2(ci[1])}"
                } ?: "not measured — every probe here rode a warm bearer",
                when {
                    cold == null -> T.Warn
                    cold < MapProbeJoin.SUCCESS_FLOOR -> T.Bad
                    else -> T.Good
                }
            )
            ERow(
                "probe p50/p90",
                if (b.probeOkLatencyMs.isEmpty()) "no probe succeeded here"
                else "${b.probeP50Ms} / ${b.probeP90Ms} ms over ${b.probeOkLatencyMs.size} ok",
                if ((b.probeP90Ms ?: 0) > MapProbeJoin.SLOW_P90_MS) T.Warn else T.Dim
            )
            val fci = b.probeFailCi
            ERow(
                "failure rate",
                "${b.probeFail} of ${b.probeN} · ${f3(b.probeFailFrac ?: 0.0)} " +
                    "CI ${f2(fci[0])}–${f2(fci[1])}",
                if (b.probeFail > 0) T.Warn else T.Dim
            )
            b.probeTopError?.let { (reason, n) ->
                ERow("top reason", "$reason ×$n of ${b.probeFail}", T.Warn)
            }
            if (b.probeNoBearer > 0) ERow(
                "no bearer",
                "${b.probeNoBearer} probe(s) found no cellular network — counted, never failed",
                T.Faint
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cold means no probe traffic on the bearer for " +
                    "${MapProbeJoin.COLD_GAP_MS / 1000} s beforehand, which separates the first " +
                    "of each back-to-back probe pair from the second. The second rides the " +
                    "connection the first paid to establish — 168 ms mean against 457 ms — so " +
                    "pooling them hides the failure. Probe outcomes are per bin, not per SIM: " +
                    "probe_result carries no subscription.",
                color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
            )
        }
    }
}

/**
 * Which masts and sectors served this bin.
 *
 * The excursion saw 8 serving cells across 2 sites; without decomposing the identity that reads
 * as chaos, and with it, it reads as two masts and a handful of sectors — a different diagnosis
 * and a different conversation with a carrier.
 */
@Composable
private fun CellsBlock(b: Bin) {
    SheetBlock("CELLS SERVING THIS BIN", T.Brand) {
        if (b.cells.isEmpty()) {
            Text(
                "No serving-cell identity was recorded in this bin.",
                color = T.Dim, fontSize = 10.sp, lineHeight = 13.sp
            )
        } else {
            // Shares of time, not of readings: a cell heard while the screen was on would
            // otherwise look dominant for being looked at.
            val total = b.cellMs
            val masts = b.cells.mapNotNull { it.enb }.distinct()
            ERow(
                "sites",
                if (masts.isEmpty()) "identity does not decompose on this RAT"
                else masts.joinToString(" · ") { "eNB $it" }
            )
            b.cells.take(5).forEach { c ->
                ERow(
                    c.label,
                    TimeWeight.fraction(c.ms, total)?.let { "${dur(c.ms)} · ${pct(it)}" }
                        ?: "${c.samples} samples · no observed time",
                    T.Dim
                )
            }
            if (b.cells.size > 5) ERow(
                "", "+${b.cells.size - 5} more cell(s), smaller shares", T.Faint
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "LTE packs the site and the sector into one identity — CI = eNodeB << 8 | sector " +
                    "— so these are sectors of ${masts.size.coerceAtLeast(1)} mast(s) rather " +
                    "than that many separate towers. NR's NCI has a configurable split point and " +
                    "is not decomposed. Shares are of the time covered by readings that carried " +
                    "an identity.",
                color = T.Faint, fontSize = 9.sp, lineHeight = 12.sp
            )
        }
    }
}
