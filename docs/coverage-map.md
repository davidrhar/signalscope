# Coverage map & crowdsourcing

An extension to SignalScope: turn the per-device diagnostic stream into a map of *measured
connectivity outcomes*, and — eventually — a shared one.

Status: **design only**. Depends on Phase 2 (rule engine + incident timeline) being real first.
This doc assumes everything in the root `README.md`, especially the nine-cause table, the
four-layer discriminator, and the tier-0/1/2 privilege boundaries.

**Summary of the recommendation:** build the single-device map first and ship it as a personal
map. It delivers most of the value. The crowdsourced layer is a later phase, is
privacy-expensive, and should stay geographically scoped until it is dense enough to be worth
anything.

---

## 1. A real view of actual connectivity

### Signal strength is the wrong metric

The whole premise of SignalScope is that full bars can mean no data. A coverage map coloured by
RSRP reproduces exactly the error the app exists to correct — it is a carrier marketing map with
a different data source. Carriers already publish RSRP-shaped maps and they are already useless
for the same reason.

**The map's value is that it is coloured by outcome, not by signal.**

### The unit of the map

Two candidate units, and they answer different questions:

| Unit | Answers | Do we have the data? |
|---|---|---|
| Cell coverage polygon | "where does tower X reach" | **No.** No tower position, no sector azimuth, no tilt, no power. Deriving a polygon from measurements means inventing a shape. |
| Binned measurement grid | "what actually happens *here*" | **Yes.** Every observation already has a location and an outcome. |

**Use a grid. H3, resolution 9** (~174 m edge, ~0.105 km²). That is roughly one city block —
small enough to separate a lift lobby from the street outside, large enough that a handful of
passes fills it.

Resolution choice is opinionated and should not be user-configurable:

- **res 9** — the storage and display resolution. Default everywhere.
- **res 8** (~461 m edge) — the fallback for rendering when res 9 bins fail the k-threshold, and
  the resolution for rural/sparse areas.
- **res 6** (~3.2 km edge) — the *file* partition key for distribution, not a display resolution.

Cell polygons are not abandoned entirely, but they are **a secondary, derived overlay**, not the
map. Given enough samples you can render the convex hull (better: alpha shape) of the bins where
a given CGI was serving. That is an honest "observed footprint of this cell" and it is visibly
different from a marketing coverage blob. Render it as an outline only, never as a fill, so it
never competes with the measurement layer.

### The key, and a problem with it

A bin's value is not one number; it is a value per **(bin, network, band, daypart)**.

```
key = h3_r9 | mcc | mnc | rat | band | daypart
```

**PCI is not a key.** PCI is 0–503 (LTE) / 0–1007 (NR) and is reused aggressively; two different
towers a few kilometres apart routinely share one. The README's phrasing "this happens at home on
PCI 411" is correct *for a single device in a single place*, where PCI is locally unique and
therefore a perfectly good local label. It does not survive being pooled across devices. The
global key is **CGI** = MCC + MNC + TAC + CI.

This is a genuine complication, not a detail: `getAllCellInfo()` reliably gives CI for the
**serving** cell, but neighbour entries frequently carry PCI and EARFCN only, with CI absent or
`Integer.MAX_VALUE`. **Neighbour measurements therefore cannot be keyed to a cell across
devices.** Use neighbours for local diagnosis (reselection churn, cause 3) and exclude them from
the shared map.

### Daypart, because congestion is a clock

Cause 9 is time-of-day. A bin with one value hides it. Bin time coarsely — this is also a privacy
control (§3):

| Daypart | Hours |
|---|---|
| `night` | 00–07 |
| `am` | 07–10 |
| `day` | 10–17 |
| `pm` | 17–21 |
| `eve` | 21–24 |

Split weekday/weekend only where sample counts allow. Ten buckets is already more than most bins
can fill honestly.

### What each bin stores

| Field | Type | Why |
|---|---|---|
| `validated_frac` | 0–1 | Fraction of sampled seconds with `NET_CAPABILITY_VALIDATED`. **The headline metric.** |
| `nr_anchor_stability` | 0–1 | 1 − (NR_NSA↔LTE transitions per minute / ceiling). Null where no NR leg exists. |
| `transport_anchor_stability` | 0–1 | 1 − (default-route transport changes per minute / ceiling). Wi-Fi↔cellular, incl. VoWiFi/ePDG. |
| `anchor_stability` | 0–1 | **The generalisation: min of whichever of the two above are non-null.** |
| `reselect_rate` | per min | Serving-cell changes per minute, normalised by speed. Cause 3. |
| `suspend_frac` | 0–1 | Fraction with `DATA_SUSPENDED`. Cause 5. |
| `probe_success` | 0–1 | DNS + TCP + HTTP/204 completion rate. |
| `probe_p50_ms`, `probe_p90_ms` | ms | Transport latency. p90 matters more than p50. |
| `rsrp_p50`, `sinr_p50` | dBm / dB | Kept for context and for the alternate map layer. Never the default colour. |
| `incident_rate` | per hour | Incidents per device-hour in this bin. |
| `top_cause` | 1–9 | Modal cause, with its share. |
| `n_obs`, `n_contributors` | int | Confidence, and the k-anonymity gate. |

### Rendering: outcome classes, not a colour ramp

**Why class 2 was generalised.** As originally specified, `anchor_stability` measured only
NR_NSA↔LTE flapping. On the reference handset — dual LTE, no NR leg — it reads 1.00 in every bin
and the layer is simply empty; building the prototype surfaced this. The failure that *does*
happen constantly there is one layer up: the default route flapping between Wi-Fi and cellular
with VoWiFi live over ePDG. Class 2 is therefore read as **"the anchor this connection depends on
is unstable"**, of which the NR case is one instance rather than the definition.

A continuous ramp cannot express "5G NSA but the anchor is unstable" versus "solid LTE", because
those differ in *kind*, not degree. Use a small set of **mutually exclusive outcome classes**,
evaluated in order — first match wins:

| # | Class | Condition | Colour |
|---|---|---|---|
| 0 | Unsurveyed | `n_contributors < k` or `n_obs < 30` | grey, flat |
| 1 | Route loss | `validated_frac < 0.80` | red |
| 2 | Anchor unstable | `anchor_stability < 0.60` — either the NR leg or the transport the connection rides on | amber |
| 3 | Reselection churn | `reselect_rate > 2/min` at < 5 km/h | amber |
| 4 | Validated but slow | `probe_p90_ms > 1500` or `probe_success < 0.95` | orange |
| 5 | 5G NSA stable | `rat = NR_NSA` and all above pass | purple |
| 6 | LTE solid | otherwise | green |

Two rendering rules that matter more than the palette:

1. **Confidence is opacity, never colour.** A bin with 31 observations and one with 4,000 get the
   same hue and different alpha. Encoding confidence as a *different colour* teaches people to
   read it as a different outcome.
2. **Unsurveyed is grey and stays grey.** Never interpolate across a gap. An interpolated map is a
   map that lies exactly where it is least supported, and that is the failure mode of every
   carrier coverage map ever published.

Ordering classes 1 before 5 is the whole thesis restated as a switch statement: a bin that loses
the route is red even if it is 5G with a −80 dBm median.

### Layers the user can switch to

Default is **Data quality** (the classes above). Offer `RSRP`, `Band`, and `Anchor stability` as
alternates — mostly so that a sceptical user can see for themselves that the RSRP layer and the
quality layer disagree. That disagreement *is* the pitch.

---

## 2. Pre-optimising applications and bandwidth

The appealing version of this feature is "the phone knows a dead zone is 30 seconds ahead, so the
device gets ready." Most of that is not available to a third-party app. Here is the honest split.

### What the app can actually do

| Lever | API | Real? |
|---|---|---|
| Defer **its own** background work | `WorkManager` + `Constraints.setRequiredNetworkRequest(...)` (API 28+/31+) | **Yes.** Fully supported, this is what constraints are for. |
| Prefetch into **its own** cache | ordinary HTTP before entering the bad bin | **Yes**, but see the caveat below. |
| Prefer a validated, unmetered network | `ConnectivityManager.requestNetwork` with `NET_CAPABILITY_VALIDATED` / `NOT_METERED` | **Yes**, for the app's own sockets, and only to *select* among networks that exist. It cannot conjure a good one. |
| React to capability changes | `NetworkCallback.onCapabilitiesChanged` | **Yes.** This is already the NETWORK layer of the discriminator. |
| Read a bandwidth estimate | `getLinkDownstreamBandwidthKbps()` | **Technically yes, practically no.** It is a coarse per-RAT constant, not a measurement. Do not build on it. |
| **Pre-emptive pin to LTE** | Tier 2 / Shizuku, `setPreferredNetworkTypeBitmask` | **Yes, and this is the good one.** See below. |
| Tell *other* apps to prefetch | — | **No.** |
| Throttle another app's background data | `cmd netpolicy` (Tier 2) | Possible but hostile. Don't. |
| Subscribe to platform network-quality events | `ConnectivityDiagnosticsManager` (API 30) | **Assume no.** Delivery is gated — carrier-privileged apps and the active VPN app. Verify on-device before designing anything around it; do not put it on a roadmap. |

### The thing that does not survive contact with Android

**A third-party app cannot influence other apps' networking.** There is no system-wide "network
quality hint" bus that Chrome, YouTube, Grab or Gojek subscribe to. There is no way to make
another app prefetch, pause, downshift its video bitrate, or flush its queue. Anything in a plan
that reads "warn other apps" is fiction.

And the second-order problem with prefetching: **SignalScope does not know what to prefetch.** It
is a diagnostic tool. It has no map tiles, no feed, no media queue of its own worth warming. A
prefetch engine with nothing to prefetch is theatre.

### So what is actually worth building, ranked

1. **Pre-emptive pin-to-LTE (Tier 2).** If the map says the next bin is class 2 (anchor
   unstable) and the device is moving toward it, dropping NR *before* arrival prevents the
   incident instead of diagnosing it. This is the only lever that changes the radio's behaviour
   ahead of the fault, and it is the single strongest argument for building the map at all.
   Rate-limit it, log it, revert on exit, and never do it silently.
2. **Tell the user.** "Data drops here — about 40 seconds." A notification, a Quick Settings tile,
   a glance widget. Low effort, and it is the thing people actually want: not a fix, an
   explanation delivered before the frustration rather than after.
3. **An opt-in on-device API for apps you control.** A bound `Service` (AIDL) or a signature-level
   broadcast exposing `getBinForecast(lookaheadSeconds)`. Be realistic: **no third party will
   integrate this.** It is worth building only because there is already a second app on this
   machine that could consume it — `taxiwrapper/FareWrapperApp` could defer a fare scrape or warm
   its map tiles before a known-bad stretch of the expressway. One real consumer justifies it. Zero does
   not.
4. **Defer SignalScope's own uploads** (crowdsource submissions, exports) via WorkManager
   constraints. Small, correct, nearly free.

### The lookahead, honestly

Thirty seconds at 50 km/h is ~420 m — two to three res-9 bins. To predict you need position and
heading, which means active location, which is the expensive part. Rules:

- Only forecast when a foreground service is **already** running for diagnosis. Never start one
  for the map alone.
- Only forecast above a speed threshold (~15 km/h). Stationary users do not need a lookahead;
  they need the incident timeline.
- Take the H3 `kRing` of the current bin, keep the cells within a cone around the heading vector,
  and score by class. No route-matching, no map-matching, no road graph — the added complexity
  buys very little over a heading cone.
- Below the speed threshold, fall back to `GeofencingApi` on the handful of known-bad bins the
  user visits regularly. Geofence transition latency is minutes, not seconds — useless for a
  30 s lookahead, fine for "you just got home."

**Battery warning.** The README's diagnostic collection can be location-light. This feature is
not. Continuous location plus a forecast loop changes the battery calculus materially, and the
README already names 15%/day as the uninstall threshold. Ship the forecast as off-by-default.

---

## 3. Crowdsourcing

The hard part, and the part most likely to be done badly.

### The privacy threat model, stated plainly

**Cell ID + timestamp + location is a movement trace.** It is one of the most re-identifiable
data types there is. Three specific attacks:

1. **Trace reconstruction.** Order any contributor's observations by time and you have their day.
2. **Home and workplace inference.** The bin someone reports from at 03:00 every night is where
   they sleep. No clever analysis required.
3. **Small-bin attribution.** A bin with one contributor *is* that contributor. Publishing
   anything about it publishes something about them.

### The rules

Non-negotiable, and they should be visible in the app's own copy:

1. **No raw observation ever leaves the device.** Not encrypted, not pseudonymous — not at all.
   Aggregation happens on-device; the device uploads bin summaries only.
2. **No coordinates leave the device.** Upload the H3 index, never lat/lon, never altitude,
   never accuracy radius.
3. **No timestamps leave the device.** Upload the daypart bucket and a date. Sub-hour resolution
   is discarded before upload.
4. **k-anonymity before publication.** A (bin, daypart) is not published until **k ≥ 5 distinct
   contributors** and **n ≥ 30 observations**. Below that it renders grey (class 0) for everyone,
   including the people who contributed it. This is the grey in the mockup's legend.
5. **Dwell suppression.** The device identifies its own top-2 dwell bins (home, work) and
   excludes them from upload by default. Including them is an explicit, separate opt-in with its
   own explanation. This costs real data — those are the bins the user cares about most — and it
   is still the right default, because those bins are shown to the user from local data anyway.
6. **Retention.** Raw observations on-device: 14 days, then rolled up and deleted. Server: rollups
   only, no raw. There is nothing to breach because there is nothing there.

### Counting contributors without identifying them

k-anonymity needs a distinct-contributor count, which needs an identifier — the obvious tension.
Resolve it with a **per-bin pseudonym** rather than a per-device one:

```
token = HMAC(device_secret, h3_r9 || epoch_month)   -- computed on device, truncated to 64 bits
```

The server can count distinct tokens *within* one (bin, epoch) to enforce k. It cannot link a
contributor's tokens across bins, because each is keyed to a different `h3_r9`, and it cannot link
across months. `device_secret` never leaves the device and rotates if the user resets
contributions.

The honest caveat: this is computed client-side, so a modified client can mint arbitrary tokens
and defeat k on its own. Privacy and anti-abuse pull in opposite directions here. The token
protects honest users from the *server*; §"Anti-abuse" protects the dataset from dishonest
*clients*. Both are needed and neither substitutes for the other.

**Differential privacy: recommended against, at least at MVP.** At k = 5 and n = 30, calibrated
noise large enough to provide a meaningful ε swamps the signal entirely. Adding DP noise to counts
this small produces a map that is both private and wrong, and the wrongness is invisible.
Revisit only if a bin routinely carries hundreds of contributors.

**Deletion, honestly.** Once a contribution is aggregated into a k ≥ 5 bin it is not individually
retrievable — that is the point of the design. The consent copy must say this in plain words
rather than promise a delete that cannot be performed. What *can* be offered, and should be:
delete everything local, rotate `device_secret`, and stop contributing. Under GDPR and comparable
PDPA the pre-aggregation data is the personal data, and it is all on-device and deletable.

### Shared schema

**Upload record** (one per bin × daypart × network × day, batched):

| Field | Type | Notes |
|---|---|---|
| `schema` | int | Version. Refuse anything you can't parse; never guess. |
| `h3` | uint64 | Res 9. |
| `day` | date | No time. |
| `daypart` | enum | Five buckets. |
| `mcc`, `mnc` | int | `525`, `03`. |
| `rat` | enum | `LTE`, `LTE_CA`, `NR_NSA`, `NR_SA`. From `TelephonyDisplayInfo`. |
| `band` | int | `3`, `78`. Derived from EARFCN/NR-ARFCN. |
| `cgi_hash` | uint64 | Salted hash of MCC+MNC+TAC+CI. Joins bins to cells without publishing a cell inventory. |
| `validated_frac`, `anchor_stability`, `probe_success` | uint8 | Quantised 0–200. Float precision here is a fingerprint. |
| `contributor_set` | — | **Never transmitted as a count.** Cardinality of a *union*; summing counts defeats k-anonymity by aggregation. |
| `probe_p50_ms`, `probe_p90_ms` | uint16 | Clamped. |
| `rsrp_p50`, `sinr_p50` | int8 | Quantised to 2 dB. |
| `n_obs` | uint16 | Capped at 1000 — see influence capping. |
| `top_cause` | uint8 | 1–9, or 0. |
| `token` | uint64 | Per-bin pseudonym. |
| `integrity` | bytes | Play Integrity verdict. |

~40 bytes packed. A heavy day is a few hundred records.

**Published record** is the same minus `token` and `integrity`, plus `n_contributors` and the
computed `class`.

### Anti-abuse and data trust

Threats, in rough order of likelihood: buggy clients; rooted devices with spoofed location;
someone who wants their neighbourhood to look bad (or a competitor's coverage to look bad);
emulators at scale.

| Control | What it catches |
|---|---|
| Play Integrity on submit | Emulators, most tampered clients. Not a wall — a cost. |
| Physical plausibility | RSRP ∉ [−140, −44], SINR ∉ [−20, 40], PCI out of range, EARFCN↔band mismatch, invalid MCC/MNC, `validated_frac > 1`. Cheap, catches buggy clients. |
| Geometric plausibility | Same token-epoch reporting bins that require impossible speed. |
| Consensus median | A bin's value is the **median across contributors**, never the mean, and never a per-observation average. One contributor cannot move a median. |
| Influence cap | No contributor supplies more than 25% of a bin's weight, regardless of `n_obs`. This is why `n_obs` is capped. |
| Deviation down-weighting | A contributor consistently > 2 MAD from bin consensus across many bins gets down-weighted. Decays; not a permanent ban. |
| k ≥ 5 | Does double duty — an attacker needs five plausible identities per bin to publish anything at all. |

Reputation is per-install, decays over weeks, and is never shown to the user. A visible score
invites gaming.

### Backend at minimum viable scale

Do the arithmetic before choosing architecture. 1,000 active users × ~200 bin-dayparts/day
× 40 bytes ≈ **8 MB/day**. That is not a data platform problem. It is a cron job.

```
device ──HTTPS──▶ ingest (edge fn)  ──▶ object storage, NDJSON, partitioned by day
                        │ validate, verify integrity, drop malformed
                        ▼
                   daily rollup job  ──▶ median / k-gate / classify
                        │
                        ▼
                 published tiles: one JSON per res-6 parent  ──▶ CDN
```

- **Ingest:** one authenticated HTTPS endpoint. One batch per device per hour, ≤ 100 KB, hard
  rate limit. Validate and append. No queue.
- **Rollup:** a scheduled daily job. Read yesterday's partition, merge with the running
  aggregate, apply consensus/k/classification, emit tiles.
- **Serve:** **static files on a CDN.** One JSON per res-6 parent (~3.2 km edge) containing all
  published res-9 children. A client fetches one file to cover everything it can see. No
  per-request compute, no database on the read path, trivially cacheable, and it degrades to "the
  map is a bit stale" rather than "the map is down."
- **Concrete stack:** Cloudflare Workers + R2 + a scheduled Worker. Or S3 + Lambda + CloudFront.
  Either is a few dollars a month at this scale.

**Do not build:** Kafka, a streaming pipeline, PostGIS, a vector tile server, a time-series
database, or a Kubernetes anything. None of them are justified by 8 MB/day, and every one of them
becomes the reason the project stalls.

### Cold start — the actual hard problem

With one user there is no crowd. Everything above is inert. This is the risk that kills the
feature, so plan around it rather than hoping.

**The resolution: the single-user map is the product, and it ships first.**

- For your own data, **k-anonymity does not apply** — it is your trace, shown only to you, never
  uploaded. So the personal map works at n = 1, on day one, with no backend at all.
- It is immediately useful precisely because the problem is at home and on a daily commute. Bins
  you visit twice a day fill up within a week.
- It resolves the README's open question about tower geolocation: for a *bin* map you already
  have coordinates from GPS, so **PCI-relative is enough** and OpenCelliD is not on the critical
  path.

**Enrichment, not bootstrap: OpenCelliD.** It maps CGI → approximate tower coordinates offline,
which lets the app draw "the tower you are on is roughly there" from the first launch with zero
contributors. Two caveats, both real:

- **Licence.** OpenCelliD is CC-BY-SA 4.0. Share-alike is viral for derived databases. Keep it as
  a **display-time overlay joined at render**, strictly separate from the measurement store, or
  the entire crowdsourced dataset inherits a share-alike obligation. This is a licensing decision
  that is very hard to reverse later — make it deliberately, now.
- **Not a substitute.** OpenCelliD is tower-location-centric. It holds nothing about outcomes,
  which is the only thing that makes this map different from what already exists. It can seed the
  overlay; it cannot seed the product.

**Mozilla Location Services was retired in 2024** and is not an option. Do not design around it.

**Seeding strategy, if the crowd layer is ever switched on:** do not chase strangers. Recruit
5–20 people on **one corridor** — one MRT line, one expressway, one campus. A map that is dense
along one commuter corridor beats a map that is one sample per postcode across a whole country, because
density is what clears k. Geographic scoping is not a limitation to apologise for; it is the only
way the first published bin ever appears.

**The uncomfortable conclusion:** the single-device map delivers most of the value, and the
crowdsourced layer may never pay back its privacy, legal and operational cost. Build it only if
the personal map proves out first and real people ask to share.

---

## 4. Recommended path

| Phase | Scope | Gate to proceed |
|---|---|---|
| **5a** | Local bin store (Room), outcome classification, personal map. No network. | Phase 2 incident timeline is real and trusted. |
| **5b** | Heading-cone forecast + user notification. Off by default. | Personal map shows stable, repeatable bad bins. |
| **5c** | Pre-emptive pin-to-LTE on forecast class 2. Tier 2 only. | 5b forecasts verifiably precede real incidents. |
| **5d** | AIDL forecast service, consumed by `FareWrapperApp`. | A second app actually exists to call it. |
| **5e** | Crowdsourced upload, k-gate, static tiles. One corridor. | Someone other than the author asks for it. |

Stop at 5a if nothing after it earns its place. 5a alone is a better product than any carrier
coverage map, because it is measured, it is yours, and it is about outcomes.

---

## 5. Things that sound good and are not

Matching the README's section of the same spirit — state these before someone spends a sprint on
them:

- **"Warn other apps before a dead zone."** No such mechanism exists for third-party apps. There
  is no network-quality bus, no prefetch broadcast, no way to influence another app's sockets.
- **"Use `ConnectivityDiagnosticsManager` for platform-grade network health."** Gated to
  carrier-privileged apps and the active VPN. Assume unavailable.
- **"Use the reported link bandwidth to pick a quality tier."** `getLinkDownstreamBandwidthKbps()`
  is a per-RAT constant, not a measurement. It will tell you 5G is fast while nothing loads.
- **"Sample cell info at 1 Hz for the map."** `SignalStrength` and `TelephonyDisplayInfo`
  callbacks can be that fast; `requestCellInfoUpdate()` cannot. It is throttled (Android 12+
  tightened this further) and returns cached results in the background. The dashboard's "1 Hz" is
  honest for the callback-driven fields and would be a lie for full cell info.
- **"`NET_CAPABILITY_VALIDATED` is a real-time signal."** It is Android's periodic captive-portal
  probe. It lags reality by seconds. Good enough for incident boundaries; too coarse to treat
  `validated_frac` as second-accurate.
- **"Interpolate between bins so the map looks finished."** A map that is confident where it has
  no data is the exact failure being corrected. Grey stays grey.
- **"Differential privacy makes it safe to publish small bins."** At n = 30 the noise exceeds the
  signal. k-anonymity plus dwell suppression is the honest control at this scale.
- **"Merge OpenCelliD into the measurement database for better coverage."** CC-BY-SA share-alike
  would then apply to the derived dataset. Join it at render time or not at all.
- **"Predict with a road graph and map-matching."** A heading cone over an H3 kRing gets ~90% of
  the accuracy for ~5% of the work, and does not need a routing engine or map data licences.
