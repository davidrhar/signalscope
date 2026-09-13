# Region acquisition: how the basemap feeds itself

**Implements [`map-regions.md`](map-regions.md). Before this, the only way to get a `.pmtiles`
archive onto a phone was `adb push`, so the map was blank for every real user on first run.**

Companion to [`map-stack.md`](map-stack.md) (renderer and why Protomaps-only) and
[`multi-sim.md`](multi-sim.md) (which subscription's country counts).

---

## Three layers, and only two of them use the network

| Layer | Zoom | Source | Trigger | Measured |
|---|---|---|---|---|
| **World** | z0–4 | **bundled in the APK** | first run, offline | 6.0 MB, 0 requests |
| **Country context** | z0–N ≤ 9 | HTTP range reads | country held 10 min | a small country: 0.8 MB, 12 tiles |
| **Local detail** | z10–14 | HTTP range reads | 0.5° cell dwelt in 5 min | 14.0 MB, 44 requests, 14.6 MB, 36 s |

The world tier is the change that actually removes "blank map on first run". It is not a
download that might fail, might be deferred, might be on cellular — it is in the APK, it is
unpacked before anything else runs, and it needs no network, no permission and no server.
`map-stack.md` makes the argument better than this document can:

> **This app is used precisely where the network does not work.**

A first run in a dead spot is the normal case for a tool whose subject is dead spots. So the
floor is offline and everything the network adds is detail on top of a map that already works.

---

## The sourcing decision

The obvious source is Protomaps' daily planet builds at `build.protomaps.com`, and it is the
wrong one. Their own documentation says:

> Please note that URLs may change and hotlinking to these downloads are discouraged. Instead,
> you should copy the tileset to your own Cloud Storage.

Three separate reasons, any one of which is sufficient:

1. **They ask you not to.** "Discouraged" is not a licence restriction, but a shipped app doing
   the thing the provider explicitly asked people not to do is the same mistake `map-stack.md`
   identifies with `tile.openstreetmap.org`, and it is no better for being made politely.
2. **The URL expires.** The bucket retains "all builds for the past week", so a dated URL
   compiled into an APK stops resolving within days. This is not a scale problem; it breaks the
   first install after the build ages out.
3. **Every byte is uncached origin egress.** `HEAD https://build.protomaps.com/<date>.pmtiles`
   returns `cf-cache-status: DYNAMIC` on a 137 GB object. Range reads from a shipped app would
   be billed to Protomaps directly.

**What we use instead:** the Source Cooperative mirror Protomaps themselves link to —
`https://data.source.coop/protomaps/openstreetmap/v4.pmtiles`. Source Cooperative is a public
open-data distribution platform run by Radiant Earth whose stated purpose is serving open
geospatial data to the public; the URL is stable and undated; and range-reading 14 MB out of a
134 GB archive is exactly the access pattern PMTiles and object-store hosting were designed for.

It is still someone else's bandwidth. So:

- **fetched once per region and never again** — a region is a file we then possess forever;
- **the client identifies itself** — `User-Agent: SignalScope/0.1 (Android; +<repo>)`, so anyone
  paying for this can see who we are and block us specifically rather than by netblock;
- **requests are merged** — byte ranges are coalesced until the count fits a per-region budget;
  a local region is ~44 requests for 14 MB, 4.7 % overfetch;
- **429 and 5xx back off exponentially and then give up**, rather than retrying into a wall;
- **nothing is fetched at all** unless the phone is on unmetered Wi-Fi.

**At any real install base the right answer is to mirror the planet ourselves.** That is one
constant — `RegionAcquisition.PLANET_URL` — and nothing else changes.

### What was rejected

- **A tile server of our own.** Infrastructure the project does not have, and `map-stack.md`
  already decided against a runtime tile dependency.
- **Bundling a full-detail region.** The archive for one city is 14 MB and would be the wrong
  city for almost everyone.
- **Bundling z0–5 instead of z0–4.** Measured at 14.9 MB against 6.0 MB, for detail the country
  tier supplies within minutes of arriving anywhere.

---

## The extractor

`collect/RegionPmtiles.kt` is `pmtiles extract` reimplemented in Kotlin over `java.*` only — no
new dependency. Parse the 127-byte header, walk the root and leaf directories, work out which
Hilbert tile IDs fall inside a bbox, range-fetch those byte ranges, write a new valid archive
with its own directories, copying tile bodies verbatim so the schema and the ODbL attribution in
the metadata come through unchanged.

Three things were not obvious:

- **Tile IDs are a Hilbert curve.** A subtly wrong rotation produces an archive full of real
  tiles from the wrong place, which renders — just somewhere else. The codec was proved by
  round-tripping a real Protomaps archive's root directory byte-for-byte before any of this ran.
- **Keep-alive is worth an order of magnitude.** `HttpURLConnection.disconnect()` evicts the
  socket from Android's pool. With it, a 16-request country extract took **70 s**; without it,
  a 44-request local extract takes **36 s**. An extract is dozens of requests to one host, so one
  TLS handshake per range dominates everything else.
- **Leaf directories merge like tile data does.** Fetching them individually cost 12 requests for
  a 12-tile region. Coalescing them costs 4.

Output offsets are known before any tile byte is fetched, so the archive is written
header-first and tile bodies are seeked into place with `RandomAccessFile` as ranges arrive.
Peak heap is one 4 MB range buffer regardless of region size.

---

## MapLibre aborts the process on an archive it cannot parse

This is the constraint that shapes everything below it, and it was learned the hard way.

```
thread: PMTilesFileSour
Abort message: 'terminating due to uncaught exception of type
                std::runtime_error: unknown compression method'
```

`PMTilesFileSource` parses archives on its own native thread and throws an **uncaught C++
exception** at anything it cannot read. That calls `std::terminate` and takes the process with
it. There is no Kotlin exception to catch, no try/catch that helps, and no in-app recovery: one
bad file is a Map tab that crashes on every launch, forever, until something deletes the file.

The archive that caused it had a valid header, valid directories, and `pmtiles show` reported it
perfectly healthy. Its **tile bodies were zero-filled**, because two acquisitions of the same
region ran concurrently into the same work file — the 30-second scheduler tick and a manual
trigger — and one deleted the other's file mid-write while the survivor renamed a half-written
archive into place. The first tile MapLibre tried to gunzip killed the app.

Four defences, in order of when they act:

1. **One acquisition at a time.** `RegionAcquisition` holds a `Mutex`; a tick arriving during a
   download is dropped rather than queued behind it. Jobs leave the queue when they *start*, not
   when they finish, so no second caller can pick the same one up.
2. **The renderer can never see a partial file.** Work files live in a `.work` subdirectory of
   the archives directory — excluded from the archive listing structurally, not by a suffix
   convention someone could later change — under a unique name, and are `fsync`ed and renamed
   into place only once complete. A short write is a hard failure, not a smaller archive.
3. **Nothing is exposed unverified.** `RegionPmtiles.verify` proves, on our thread where an
   exception is survivable, that: the magic and spec version are right; internal and tile
   compression are values MapLibre supports; the root and every leaf directory decompress and
   parse; **every tile body starts with the magic its declared compression implies**; and at
   least one tile decompresses end to end. That last-but-one check is what catches a zero-filled
   blob, and it costs one pass of 2-byte reads. It runs over archives this app wrote, archives an
   older version wrote, and archives someone pushed over adb — the case that used to be trusted
   implicitly.
4. **A crash cannot repeat.** The filenames about to be handed to MapLibre are written to
   `.opening` first and erased once a frame has actually rendered. Finding that note still there
   at startup means the last attempt did not survive, so those archives are moved to
   `.quarantine/` with a note saying why, and the Map tab comes back. A false positive costs a
   re-download of a replaceable file; a false negative costs the user their Map tab permanently.
   The bundled world archive is additionally flagged so it is not re-seeded from the APK into the
   same crash.

Nothing is ever deleted by any of this. Quarantine moves files aside and names them in the UI.

---

## Several archives, one style

The map is no longer one file, so `Basemap` builds one style over every archive the phone holds,
stacked coarse-first: world, then country, then adopted, then local. Each source draws the same
Protomaps layer set with id-suffixed layers, and a finer archive paints over a coarser one **only
where it has tiles**. Outside its bbox MapLibre has nothing to draw from it and the coarser
archive shows through — which is exactly what organic growth needs, and costs no zoom-range
bookkeeping. Verified on device with the world archive and a z0–14 regional archive composed
together.

---

## Detection and gating

**Country** comes from `getNetworkCountryIso()` on the **data** subscription — never
`getSimCountryIso()`, which names the SIM's *home* country and is permanently wrong on the
reference device's full-time-roaming second SIM. A new country must be observed continuously for
**10 minutes** before it triggers anything, so a border crossing is one event rather than twenty.
The manual "add this country now" control is not subject to the hysteresis: that rule damps
*automatic* triggering at a border, where the phone cannot tell a crossing from a wobble, and an
explicit tap carries no such ambiguity.

Country bounding boxes ship as a static table of **239 entries, 8.3 KB**, derived from Natural
Earth 10m admin-0. No lookup service, works offline. Two things the obvious implementation gets
wrong:

- **The antimeridian is not an edge case.** A naive min/max of longitude gives the United States,
  Russia, New Zealand, Fiji and Kiribati a box spanning the whole planet, so a "country context"
  download for the USA would be a world download. Each country's extent is measured twice, in
  [−180,180) and in [0,360), and the narrower one kept — so `west > east` means the box wraps, and
  the tiler handles that explicitly.
- **z0–9 for every country is wrong.** `map-regions.md` estimates "low single-digit MB", which
  holds for a small country and fails badly for a large one: Russia's bbox at z9 is roughly
  22,000 tiles, a ~150 MB download. The zoom ceiling is chosen per country from a **tile budget**
  (3,500), so a large country gets coarser context rather than a surprise 150 MB, and the UI
  states which ceiling it got.

**Local detail** follows the map's own position fix — the same fix that produces measurement
bins — so the detail tier is coextensive with the ground the app has data for. Cells are a fixed
0.5° grid rather than a box centred on the user, so walking 200 m never re-downloads an
overlapping region, and a cell can be named, listed and deleted.

**Gating** is on conditions, never on prompts: unmetered and validated Wi-Fi or ethernet, battery
above 20 % or charging, and enough free space that the 400 MB measurement reserve survives. A
blocked download is **queued, not failed**, and the Map tab says exactly what it is waiting for.
One notification after the fact, per region. Nothing asks.

---

## Retention

Nothing is auto-evicted; there is no eviction path in the code. Deletion is a button. Under
storage pressure the UI names the least-recently-added region as a *suggestion* and does nothing.
The asymmetry is stated in the panel itself: **basemap regions are replaceable, measurements are
not.**

---

## Known gaps

- **Acquisition starts when the Map tab is first opened**, not at app launch, because this change
  did not own `MainActivity`. It then runs for the life of the process, which `CollectorService`
  keeps alive. One call from `onCreate` moves it to launch; nothing depends on which.
- **Hysteresis state is in memory.** A process restart restarts the 10-minute timer rather than
  acting on an observation it cannot re-confirm. That is the safe direction.
- **No labels.** See below.

---

## Labels, and what they would cost

The map has no place or street names. MapLibre needs SDF **glyph PBFs** reachable from the
style's `glyphs` URL, and offline means bundling them in the APK. Assessed and deliberately not
done:

- **Latin-only would genuinely be cheap.** Three or four range files, roughly 100 KB, a `glyphs`
  key pointing at `asset://`, and four symbol layers over the Protomaps `places` and `roads`
  source-layers. No new Gradle dependency, no architectural change.
- **But it produces a silently half-labelled world map.** A Latin-only glyph set names nothing
  across China, Japan, Korea, the Middle East, India, Thailand or Greece — and MapLibre renders a
  missing glyph as nothing at all, with no error. A map where some places are named and others
  silently are not is worse than a map where none are, and it is the same class of invisible
  failure this repo keeps refusing elsewhere.
- **Full coverage is not cheap.** CJK alone is tens of MB of SDF per font. Protomaps' own v4
  archives carry `pgf:` glyph hints in their metadata for exactly this reason — a Protomaps-
  specific extension MapLibre GL Native does not read.
- **There is no ready-made asset to drop in.** `protomaps/basemaps-assets` publishes font
  *sources*, not pre-rendered range PBFs; generating them needs a Node or Rust toolchain
  (`fontnik`, `build_pbf_glyphs`) this project does not have.

So the cheap version is only cheap if you accept a map that lies by omission about half the
world, and the honest version needs a new toolchain and a real APK budget. Left alone, as asked.

The recommendation if it is ever picked up: generate glyph ranges for the scripts actually
present in the regions a user holds, acquire them alongside the region rather than bundling them,
and keep the symbol layers off entirely for any script whose glyphs are absent — so a name is
either rendered or visibly missing, never silently dropped.
