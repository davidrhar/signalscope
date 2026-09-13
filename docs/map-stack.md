# Map stack: OpenStreetMap on Android

**Decision: MapLibre GL Native reading Protomaps PMTiles archives, and nothing else.
No tile server, no API key, no account, no per-tile network traffic at runtime.**

OpenFreeMap has been **removed**. Implementing Protomaps showed the two cannot share a style —
and once the basemap is a local archive, an online tile source has no job left to do. The network
is used once, to acquire an archive; after that the basemap is local permanently.

There is deliberately **no online tile fallback**. Protomaps' planet builds are a build service,
not a CDN for application traffic: pointing a shipped app at them would repeat exactly the
mistake identified below with OSM's own tile servers. Before a region is downloaded there is no
basemap and bins render on flat ground — a first-class degraded state, verified on device, and
the same thing the user sees in any area they have not downloaded.

---

## Why OSM is the right base

Free, no API key, no per-request billing, no terms that restrict what we do with a diagnostic
overlay, and — decisively — **usable offline**. Google Maps' Android SDK is billed, key-gated,
and its offline story is not under our control.

## The trap: OSM data is free, OSM's tile servers are not for us

This catches nearly every project that reaches for OSM. The map *data* is open under ODbL. The
**public tile servers at `tile.openstreetmap.org` are a donated resource with a usage policy
that explicitly forbids app distribution against them.** Pointing a shipped APK at them is a
policy violation and the endpoint may block us.

## Solving the trap: two free sources, no API key, no account

The hosted free-tier providers (MapTiler, Stadia, Geoapify, Thunderforest) all require an
account and a key, meter usage, and can revoke access. **Rejected** — they reintroduce a
dependency we do not need.

The combination that satisfies free + no key + offline:

| Layer | Source | Licence | Key? | Limits |
|---|---|---|---|---|
| **Offline basemap (primary)** | **Protomaps PMTiles** region extract | ODbL (OSM-derived) | No | None — it is a file we hold |
| **Online basemap (fallback)** | **OpenFreeMap** | ODbL (OSM-derived) | **No** | None published; donation-funded |

**Protomaps** publishes a free OSM-derived global basemap as PMTiles, a single-file tile archive.
The `pmtiles extract` CLI cuts a region out of the planet build, producing a file of tens of MB
for a city or small country. No server, no account, no rate limit — we simply possess the map.

**OpenFreeMap** is the key change of provider. It is a public vector-tile server, explicitly
free for public use with **no API key and no usage limits** — which is precisely what
`tile.openstreetmap.org` is not. It exists because the OSM tile policy leaves app developers
with nowhere to go. Being donation-funded, its availability is best-effort, which is fine: it
is the fallback, and the offline archive is the primary.

Both are OSM-derived and ODbL, so one attribution string covers the lot.

**Not bundled in the APK.** The region file is downloaded on first run, with the user choosing
the area — first run is the one moment connectivity is likely, and bundling a large archive for
a region the user may not live in is waste.

---

## Offline is a requirement, not a preference

The strongest argument in this whole document:

> **This app is used precisely where the network does not work.** A basemap that needs data to
> render is blank at the exact moment it is needed.

Someone standing in a dead spot, wanting to see the dead spot they are standing in, cannot
download tiles. So the map must render from local storage, and "offline support" is not a
later-phase nicety — it is the Phase-1 design constraint.

Practically: first-run-download a PMTiles region covering the user's home area and regular
routes, cache opportunistically beyond that, and degrade to a plain bin grid with no basemap
rather than to a blank screen.

---

## Library choice: MapLibre GL Native

**This reverses an earlier lean toward osmdroid.** Once the tile sources above are fixed, both
the offline archive and the online fallback are *vector*, and osmdroid is a raster-first
library. Using it would mean two different rendering paths for the two modes, and a
raster-tile fallback for which no free keyless source exists. One renderer for both modes is
worth more than osmdroid's smaller footprint.

MapLibre GL Native (BSD) also buys what the overlay actually needs: a GeoJSON source with a
fill layer and a colour expression renders tens of thousands of hex bins on the GPU, where
osmdroid's `Polygon` overlay would struggle past a few thousand. Given crowdsourcing is an
explicit goal, that ceiling matters.

Cost: ~10–15 MB of APK per ABI. Acceptable for a diagnostic tool, and a debug APK can ship a
single ABI.

### PMTiles: resolved by spike, on hardware

**The NanoHTTPD shim is not needed. Delete it from the plan.** `PMTilesFileSource` is compiled
into `libmaplibre.so` at 11.11.0, and a local archive renders on device with no server of any
kind.

Three findings from the spike:

**1. The URI wraps an inner URL, not a path.**

```
pmtiles://file:///storage/emulated/0/Android/data/<pkg>/files/maps/<name>.pmtiles   ✅
pmtiles:///storage/emulated/0/.../<name>.pmtiles                                    ❌
```

The bare-path form fails as `Mbgl-HttpRequest: [HTTP] Unable to parse resourceUrl` — the PMTiles
source strips its own prefix and hands the remainder to the HTTP source. The error names HTTP,
which sends you looking for a network problem that does not exist.

**2. Extraction is cheap, which validates organic growth.** Cutting a city out of the global
planet build by HTTP range reads: **47 requests, 20 MB transferred, 18 MB archive, 15.8 s**, at
z0–14 with 5 % overfetch. Adding a country on arrival is not a burden.

**3. The schema is the real problem, not the protocol.** Protomaps archives use Protomaps'
own layer names (`earth`, `landuse`, `water`, `buildings`, `roads`), and OpenFreeMap serves the
**OpenMapTiles/Shortbread** schema. An OpenFreeMap style against a Protomaps archive renders a
blank map with no error — every `source-layer` silently matches nothing.

This breaks an assumption in this document: one renderer over two sources also quietly assumed
**one style**. It does not hold.

### Recommendation: go Protomaps-only

`pmtiles://` accepts a remote URL as readily as a local one, so the same archive format serves
both modes:

| Mode | Source |
|---|---|
| Offline | `pmtiles://file:///…/<region>.pmtiles` |
| Online | `pmtiles://https://…` against the hosted build |

One schema, one style, one code path, and OpenFreeMap's availability stops being a dependency.
The cost is more per-tile requests online than a dedicated tile server, which matters little for
a map that is offline-first by design. Keeping OpenFreeMap would mean shipping and maintaining
two complete styles for one map.

Keep the map behind a small interface anyway — `renderBins(List<BinCell>)`, `renderTowers(...)`,
`setBasemapSource(...)` — so the renderer stays replaceable.

### Rejected

- **osmdroid** — raster-first; see above. Would have been the right call if we were shipping
  raster MBTiles, but no free keyless raster source exists to fall back to.
- **Mapsforge** — genuinely capable offline vector rendering with free per-region `.map` files,
  and the closest runner-up. Rejected only because it solves the offline half well and the
  online half not at all, leaving the same two-path problem as osmdroid.
- **Google Maps SDK** — billed, key-gated, offline not under our control.

---

## What gets drawn on top

The overlay is the product; the basemap is context. Three layers:

1. **Usability bins** — H3 cells from `radio_sample`, coloured by measured usability, *not*
   signal strength. A bin where RSRP is −85 dBm but the route was unvalidated 40 % of the time
   must read as bad. This is the entire point and it is what makes the map different from the
   signal-strength maps that already exist.
2. **Incident pins** — where calls actually dropped. The emotionally legible layer: "here is
   where my call died, four times."
3. **Cell sites** — approximate positions, **estimated from our own measurements**: the
   RSRP-weighted centroid of observations of a given CGI, with timing advance as a distance
   prior. Less accurate than an external database, entirely ours, and it improves as data grows.

   **OpenCelliD is dropped.** It is CC-BY-SA 4.0 (an earlier draft of this doc wrongly said
   ODbL), which is incompatible with ODbL for merging and viral for derived databases. Rather
   than manage that, we removed the dependency: every measurement already carries a GPS-derived
   bin, so tower coordinates were never on the critical path. See
   [`adaptive-aggregation.md`](adaptive-aggregation.md) for why aggregation level does not
   resolve the obligation either.

### Colour must encode usability

A one-line rule that follows from the project's premise: **never colour the map by RSRP.** The
founding observation is that strong signal with no working data is a normal state. Colour by
validated-uptime or probe success rate, and offer signal strength only as a secondary,
explicitly-labelled layer. Colouring by power would rebuild the exact lie the app exists to
correct.

---

## Obligations

- Display **"© OpenStreetMap contributors"** on the map surface. ODbL requires attribution and it
  is non-negotiable; osmdroid has a built-in attribution overlay.
- Attribute the tile provider separately if a hosted one is used.
- If OpenCelliD data is redistributed rather than merely consulted, ODbL's share-alike terms
  attach — worth checking before any crowdsourced backend publishes derived tower positions.

---

## Open questions

- Which geographic region to bundle for first-run offline? Depends on where the problem is.
- Hex resolution for display versus storage: H3 r10 (~65 m) is proposed for storage in
  `data-model.md`; the map may want to render at r8/r9 when zoomed out, which means pre-computed
  rollups per zoom level rather than aggregating thousands of bins on the UI thread.

---

## Field notes from the working prototype

`mockups/coverage-map-prototype.html` is a real MapLibre map over real OpenFreeMap tiles with
real H3 geometry, centred on a dense Asian city. It exists to test the decisions above rather than
restate them. Everything below was observed, not assumed.

**It is a local file and must stay one.** Open it directly in a browser, or serve the
`mockups/` directory over a static HTTP server. It **cannot** be published as a hosted
artifact: that sandbox applies a Content-Security-Policy that permits scripts from a CDN
allowlist but blocks `fetch`/XHR to arbitrary hosts. MapLibre itself would load; every tile
request to `tiles.openfreemap.org` would be blocked *silently*, and the result is a blank dark
rectangle with hexes floating on it — precisely the abstract-polygon mockup this prototype
replaces. The failure is invisible, which makes it worse than a loud one.

### OpenFreeMap: it works

| Checked | Result |
|---|---|
| `https://tiles.openfreemap.org/styles/dark` | 200, valid style spec v8, no key, no account, no referrer check |
| `https://tiles.openfreemap.org/planet` | 200, TileJSON, tiles at `/planet/<build>/{z}/{x}/{y}.pbf` |
| A z14 city tile | 200, `application/vnd.mapbox-vector-tile` |
| Planet build served | `20260906_080001_pt` — six days old at time of writing |
| CORS | permissive; loads fine from a `file://`-origin and a localhost page |

Streets, water, rail, building footprints and labels all render, at every zoom tried from
z10.7 to z15.1. **No reason to downgrade confidence in OpenFreeMap as the online fallback.**

Two small things worth knowing:

- The `dark` style's sprite sheet (`ofm_f384`) references a `wood-pattern` fill image it does
  not contain, so MapLibre logs one warning per tile that wants it. Upstream, cosmetic. A
  `styleimagemissing` handler that supplies a transparent 1×1 absorbs most of them; a few still
  escape from tiles whose worker request was already in flight.
- MapLibre composes attribution from the style's own sources, so **`© OpenStreetMap
  contributors` must be added as `customAttribution`** — the style supplies "Data from
  OpenStreetMap", which is not the same string. Pleasingly, hiding the basemap layers drops the
  tile-provider attribution automatically and leaves ours, which is the correct behaviour for
  the offline / no-basemap degraded mode.

### Versions pinned

- **MapLibre GL JS 5.6.1** (`cdn.jsdelivr.net/npm/maplibre-gl@5.6.1/dist/maplibre-gl.js`).
  Pinned deliberately: **MapLibre 6.x ships ESM only** — `dist/maplibre-gl.mjs`, no UMD bundle —
  which is awkward for a single-file local prototype and pulls in cross-origin module and worker
  loading. 5.x still ships the self-contained UMD build. This constrains the web prototype only;
  the Android target is MapLibre GL **Native**, versioned separately.
- **h3-js 4.5.0** (`dist/h3-js.umd.js`, global `h3`). v4 API throughout:
  `latLngToCell`, `cellToBoundary(cell, true)` → `[lng, lat]`, `cellToParent`, `cellToLatLng`,
  `gridDisk`, `getResolution`.

### H3 and rendering performance

Measured in-page, desktop Chromium, cold load:

| Step | Cost |
|---|---|
| 3,297 res-10 leaf bins → merge walk → 1,428 published bins → GeoJSON | **~140 ms** total |
| Same work on a warm engine | ~40 ms |
| Switching map layer (quality → RSRP → band → anchor) | **< 1 ms** |

The layer switch is that cheap because **every layer's colour and opacity is precomputed onto
the feature as a property** and switching is one `setPaintProperty('fill-color', ['get',
'c_rsrp'])`. No source is re-fed, no geometry is rebuilt, and the GPU does the rest. Do the same
on Android: `renderBins()` should push geometry once and swap a colour expression, never
re-upload the source to change a layer.

Implications for the Android port:

- 1,400 fill features plus a matching line layer is nothing for the GPU; the doc's claim that
  MapLibre handles tens of thousands of bins where osmdroid would not is consistent with this.
- The expensive part is not rendering, it is **`cellToBoundary` plus the merge walk**, and it
  is pure CPU. 140 ms is fine once; it is not fine on the UI thread on every pan. Compute the
  adaptive view off-thread, cache the derived bins keyed by rule version, and invalidate on new
  data — not on viewport change.
- `cellToBoundary` returning `[lng, lat]` with `formatAsGeoJson = true` still needs the ring
  closed manually before it is a valid GeoJSON polygon.

### What building it proved wrong in our own docs

**1. The merge rule in `adaptive-aggregation.md` is incomplete: it has no areal-coverage
condition, and without one it manufactures exactly the lie it forbids.**

The rule as written merges a parent whenever its *present* children agree. It says nothing
about how many of the parent's children are present at all. In practice the sparse fringe of a
surveyed area — a handful of res-10 cells from one pass — satisfies "all children agree" and
"all children are below k" trivially, so it merges upward unopposed, all the way to the res-6
floor. A res-6 cell is ~28 km². The first build of this prototype duly painted several
multi-kilometre hexagons across the western fringe, the northern fringe and the north coast in a confident colour,
derived from a few dozen observations along one road.

That is "interpolate across a gap" wearing a merge rule as a disguise, and the res-6 floor does
not save you from it — the floor bounds how coarse a bin gets, not how little evidence may
claim it.

The prototype adds a fourth condition, evaluated before the others:

> **Areal coverage.** A parent may only be claimed when at least `MIN_PARENT_COVERAGE` (0.35 in
> the prototype) of its res-10 children were actually surveyed. Otherwise the children are
> published at their current resolution, whatever that means for their class — including
> staying grey.

With it, the giant blobs disappear, 75 bins correctly stay grey at the floor, and the sparse
regions render as honest speckle instead of confident continents. **This belongs in
`adaptive-aggregation.md` as a named guard alongside the three MAUP guards, because it is the
same failure mode.** Note the interaction: it is in direct tension with "merge upward
unconditionally while n < k", and coverage must win — a bin that cannot clear k without
claiming unsurveyed ground should stay unpublished, not grow until it qualifies.

**2. Contributor counts must be a union, not a sum.**

Related, and not stated anywhere: if merging seven bins adds their contributor counts, then
k-anonymity is clearable by aggregation alone, which defeats the whole gate. One person walking
a long route would "become" five contributors at res 8. The prototype models contributors as
sets and unions them on merge; `coverage-map.md`'s distinct-token scheme is compatible with
this, but the doc never says the count is a set cardinality, and an implementer reading it would
plausibly sum.

**3. `anchor_stability` is undefined on the reference handset, and the map needs the
transport-layer analogue instead.**

`coverage-map.md` defines `anchor_stability` as `1 − (NR_NSA↔LTE transitions per minute /
ceiling)` and offers it as one of four map layers. The reference device is running **dual LTE
with no NR leg on either SIM** — the home carrier on B40, and a foreign SIM roaming onto a host
network on B3 with `overrideNetwork=LTE_CA`. There are no NSA transitions, so
the metric reads 1.00 in every bin and the layer is dead.

The failure that *does* occur constantly on this device is one layer up: the **default route
flapping between Wi-Fi and cellular**, with VoWiFi live over Samsung's ePDG and a separate IMS
PDN on LTE. The prototype renders a `transport_anchor` metric — `1 − (default-route transport
flaps / ceiling)` — under the same layer name, labelled honestly in the UI. `data-model.md`
should carry both fields, and the outcome-class table's class 2 should be understood as one
instance of a general "the anchor this connection depends on is unstable" class, not an
NR-specific one.

**4. Outcome classes 2 and 5 are unreachable on LTE-only hardware, and the legend should say
so rather than omit them.** The prototype keeps both in the legend, dimmed, annotated "no NR on
this device". Silently dropping classes would make the map look like it had surveyed something
it had not.

### What the prototype demonstrates, for the record

- **Adaptive resolution is legible without explanation.** In one view the CBD renders as a
  fine res-10 mosaic (behaviour genuinely changes street to street), the inner-east and eastern districts as large
  res-9/res-8 hexes (homogeneous, well evidenced), the expressway as a narrow strand of fine bins along
  the carriageway, and the sparse west as grey. The "colour bins by resolution" toggle makes it
  unambiguous. Bin size really does read as confidence, unprompted.
- **The RSRP contrast lands.** 163 of 1,353 surveyed bins lose the route; **153 of those show
  4 of 4 bars** on this carrier's own thresholds (`[-128, -118, -108, -98]`,
  `parameters_used_for_lte_signal_bar_int = 1`, so the bar is RSRP alone), median RSRP −89 dBm.
  Only 6 are genuinely weak. Flipping to the RSRP layer turns almost the whole map green, and
  the route-loss bins are outlined in red so the disagreement is visible in a single frame. The
  RSRP layer is not a courtesy — it is the exhibit.
- **Degrading to no basemap works and looks fine.** Hiding every style layer leaves the bin
  grid on a flat ground, which is the behaviour specified above for the offline case.

### Still unverified

- PMTiles. Nothing here tests `pmtiles://` support or the NanoHTTPD localhost shim; that is
  still a Phase-1 spike, and it is the half of the stack that actually matters, since the online
  fallback is the part we can live without.
- MapLibre GL **Native** behaviour on Android, as opposed to GL JS in a browser. The colour-
  expression and GeoJSON-source approach is shared, but the performance numbers above are
  desktop-browser numbers and should not be quoted as device numbers.
