# Organic map growth

**The basemap is not a fixed download. It grows as the phone travels, and what it gains it
keeps.**

Companion to [`map-stack.md`](map-stack.md) (renderer and tile sources) and
[`multi-sim.md`](multi-sim.md) (which subscription's country counts).

---

## Trigger: detect the country from the network, not the SIM

The cheapest reliable country signal is the **MCC of the network currently serving the data
subscription**. No geocoding, no network call, no location permission — the MCC *is* the
country code.

### The trap

```
getSimCountryIso()      the SIM's HOME country      ← wrong
getNetworkCountryIso()  the network being used      ← correct
```

On the reference device these differ **permanently**: slot 1 holds a foreign SIM that has never
been used in its home country — it roams full-time on local networks. Keying map regions off the
SIM country would download the wrong country and never download the one the phone is in.

Use `getNetworkCountryIso()` on the **data subscription**, per
[`multi-sim.md`](multi-sim.md). Where two subs disagree — near a border, or a roaming sub
latching something different — the data sub is authoritative for the map.

### Hysteresis is required, not optional

The reference device regularly crosses a land border between two adjacent countries. At a border
the serving network can flap between MCCs repeatedly within minutes. Without damping, that queues and
cancels downloads on every flap.

**Rule:** a new country must be observed continuously for **10 minutes** before it triggers
anything. Cheap to implement, and it turns a border crossing into one event instead of twenty.

---

## What gets downloaded: two tiers

Downloading an entire country at full detail is wasteful — most of it will never be looked at.
Split by zoom instead:

| Tier | Zoom | Extent | When | Rough size |
|---|---|---|---|---|
| **Country context** | z0–9 | Whole country bbox | On confirmed new country | Low single-digit MB |
| **Local detail** | z10–14 | ~25 km around visited areas | As the phone actually goes there | Tens of MB per urban area |

The country tier gives instant orientation — you can see where you are in a newly entered country — for very
little storage. The detail tier is the part that grows organically: **detail arrives only where
you have actually been**, which is also exactly where measurement bins exist.

Country bounding boxes ship as a static table in the APK (~250 entries, a few KB). No lookup
service, works offline, no dependency.

Sizes above are estimates from tile-count arithmetic, not measurements — confirm during the
Phase 1 spike.

---

## Mechanism

MapLibre's **`OfflineManager`** is the supported path and works against any tile source,
including OpenFreeMap:

```
OfflineTilePyramidRegionDefinition(styleUrl, bounds, minZoom, maxZoom, pixelRatio)
  → offlineManager.createOfflineRegion(definition, metadata, callback)
```

Two properties make it the right fit:

- **Offline regions are not subject to ambient-cache eviction.** The ambient cache — tiles
  incidentally fetched while panning — is evicted under pressure. Offline regions are not. That
  is precisely the "what it gains, it keeps" requirement, and it is why we use explicit regions
  rather than relying on cache warming.
- Regions are **individually enumerable and deletable**, which gives the storage UI something
  real to manage.

**Watch the tile-count limit.** MapLibre inherits a default per-region tile cap from its Mapbox
lineage (`setOfflineMapboxTileCountLimit`). It must be raised, or regions kept small enough to
fit — a country at z0–9 can exceed a naive default. Verify early; this fails silently and
looks like a download that simply stops.

### Download policy — fully automatic, gated on conditions rather than on prompts

Region downloads **complete without user interaction**. Detection, queueing and fetching all run
unattended; the user is informed, never asked.

Conditions, checked before and during a download:

| Condition | Behaviour |
|---|---|
| **Unmetered Wi-Fi** | Proceed |
| Cellular, or metered Wi-Fi | **Defer** — queue and wait. Never prompt, never fetch |
| Battery < 20 % and not charging | Defer until charging or recovered |
| Storage below threshold | Defer, surface in the storage screen |

Gating on unmetered transport is what makes silence safe: the failure mode a prompt would
protect against — an unexpected 40 MB on a foreign cellular plan — is structurally excluded
rather than delegated to the user. A deferred download is not a failed one; it resumes when
conditions allow, which on a phone is usually the same evening.

The user is notified once per new country, after the fact ("map added for <country>, 38 MB"), and
everything is visible and reversible in the storage screen. A setting can switch to
ask-first for anyone who wants it, but the default is unattended.

---

## Retention

**Nothing is auto-evicted.** The user asked for growth that persists, and the value compounds —
a region downloaded once serves every future visit.

The storage screen lists regions by country with sizes and last-used dates, and deletion is
manual only. If storage genuinely runs short, *prompt* with the least-recently-used region
suggested; never act unilaterally.

Note the asymmetry worth stating in the UI: **basemap regions are replaceable, measurements are
not.** A deleted map region can be re-downloaded; a deleted measurement history is gone. Any
storage pressure is resolved against the basemap first, and the measurement store is never
touched automatically.

---

## Why this compounds

A phone that regularly crosses borders accumulates a personal geography of the places that
actually matter to its owner,
at detail levels justified by real visits. After a few months the offline map is not a generic
download; it is a map of *their* territory, and it is exactly coextensive with the regions where
they have coverage measurements to overlay.
