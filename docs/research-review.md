# Research review

**An external pressure-test of the design in this repo, from published sources, September 2026.**

This document exists to find where we are *wrong*, not to confirm what we already wrote. Where the
research agrees with the design it says so in one line and moves on; the space is spent on the
contradictions.

### How to read the evidence grades

| Grade | Meaning |
|---|---|
| **[V]** | Verified against a primary or official source — AOSP source, Android developer docs, a licence text, a standards or vendor document |
| **[D]** | Credible developer or community report — maintained project's own docs, GitHub issue, reputable technical press, published paper |
| **[A]** | Anecdote — a forum post or review. Signal, not proof |

Dates matter throughout. Android background and telephony behaviour changed materially in 14, 15
and 16, and most advice found online predates all three.

---

## Executive summary — the five findings that should most change the plan

### 1. The `dataSync` foreground service type will kill our collector. Declare `location` only.

`docs/data-model.md` §7 and the README both specify foreground service types `location|dataSync`.
On Android 15+ **the system permits `dataSync` services a total of 6 hours in any 24-hour period**,
after which it calls `Service.onTimeout()`, the service stops counting as foreground, and **"if the
service does not call `Service.stopSelf()`, the system throws an internal exception"** —
`RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`
**[V]**. The timer only resets when the user brings the app to the foreground. Android 15+ also
forbids starting a `dataSync` FGS from a `BOOT_COMPLETED` receiver **[V]**.

The target device is SDK 36. A 24/7 collector that declares `dataSync` is guaranteed to stop being
a foreground service after six hours, every day, and to crash if it does not notice.

`location` has **no equivalent timeout** — it is not in the timeout list **[V]**.

> **Action.** Drop `dataSync` from the manifest entirely. Declare `location` only, with
> `FOREGROUND_SERVICE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`. Update `data-model.md` §7 and the
> README's Tier-0 permission list. If a future phase genuinely needs `dataSync` (uploading a
> crowdsourced batch), it must be a *separate, short-lived* service, not the collector.

Sources: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) ·
[Behavior changes: Android 15](https://developer.android.com/about/versions/15/behavior-changes-15) ·
[Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)

### 2. The one API that defeats idle throttling needs `MODIFY_PHONE_STATE` — and the shell UID has it.

`device-findings.md` §B.1 measured the problem empirically: `CellInfo` did not refresh once in
355 s and was up to 1440 s stale; `SignalStrength` changed 5 times in 355 s. The research confirms
this is deliberate platform behaviour, not a device defect, **and** finds the intended escape hatch:

- `TelephonyManager.setSignalStrengthUpdateRequest()` (API 31) lets a caller register
  `SignalThresholdInfo` objects — per measurement type (RSRP/RSRQ/RSSNR/SSRSRP/SSRSRQ/SSSINR), up
  to 4 thresholds each, with `hysteresisDb` and `hysteresisMs` both settable to **0 to disable
  hysteresis** — and `setReportingRequestedWhileIdle(true)` to **"require reporting on thresholds
  in this request when device is idle"** **[V]**.
- **It requires `android.permission.MODIFY_PHONE_STATE` or carrier privileges** **[V]**. That is
  signature|privileged. A Tier-0 app cannot call it. This is a hard wall on our stated Tier 0.
- **But `packages/Shell/AndroidManifest.xml` in AOSP declares `MODIFY_PHONE_STATE`** (along with
  `READ_PRIVILEGED_PHONE_STATE` and `READ_PRECISE_PHONE_STATE`) **[V]**. Shizuku runs as shell UID
  2000 **[D]**. So the request is plausibly reachable at Tier 2.
- The docs also warn: **"the thresholds in the request will be used on a best-effort basis; the
  system may modify requests to multiplex various request sources or to optimize power consumption.
  The caller should not expect to be notified with exactly the same thresholds."** **[V]**

This reframes Shizuku. The design treats Tier 2 as *actions* (`svc data`, pin-to-LTE), deferred to
Phase 3. In fact Tier 2 is the only route to **measurement resolution** — the thing
`device-findings.md` identified as the binding limitation on the whole product.

> **Action.** Promote a Shizuku spike into Phase 1, with one question: can a shell-UID process call
> `setSignalStrengthUpdateRequest` on this device, and does it actually increase callback rate while
> idle? If yes, the app has two honestly different measurement modes and that becomes a headline
> capability, not a phase-3 nicety. If no, Tier 0's sampling floor (~7 min screen-off) is final and
> the UI must say so.
>
> Note the corollary: `setSignalStrengthUpdateRequest` is **per-subscription** — "To request for
> multiple subIds, pass a request object to each TelephonyManager object created with
> `createForSubscriptionId`" **[V]**. That fits `multi-sim.md` exactly.

Sources: [`SignalStrengthUpdateRequest.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/SignalStrengthUpdateRequest.java) ·
[`SignalThresholdInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/SignalThresholdInfo.java) ·
[`setSignalStrengthUpdateRequest` javadoc mirror](https://learn.microsoft.com/en-us/dotnet/api/android.telephony.telephonymanager.setsignalstrengthupdaterequest?view=net-android-34.0) ·
[`packages/Shell/AndroidManifest.xml`](https://android.googlesource.com/platform/frameworks/base/+/master/packages/Shell/AndroidManifest.xml)

### 3. The Samsung risk is real but *second*. Android 15/16's own rules are the certain one.

dontkillmyapp still rates Samsung **5/5 (worst)** **[D]** — but its own **07/2024 update** records
Samsung's commitment that **"since One UI 6.0, foreground services of apps targeting Android 14 will
be guaranteed to work as intended so long as they are developed according to Android's new
foreground service API policy"**, from a Samsung/Google joint statement **[V/D]**. Samsung's
historic Android 11 divergence — blocking wakelocks held by foreground services — is the specific
behaviour that was walked back **[D]**.

Meanwhile a *certain*, documented platform change sits directly on our architecture: on **Android 16,
regardless of target SDK, "background jobs started from a foreground service now must adhere to
their respective runtime quotas... including jobs scheduled directly with JobScheduler, as well as
jobs created by other libraries like WorkManager"** **[V]**. `README.md` puts active probes on
WorkManager. Under Android 16 those probe jobs are quota-limited even though an FGS is running, and
`JobInfo.Builder#setImportantWhileForeground` is now ignored **[V]**.

> **Action.** Two changes.
> 1. **Probes run inside the foreground service**, on its own coroutine scheduler — not as
>    WorkManager jobs. WorkManager stays for genuinely deferrable work (rollups, map region
>    downloads, retention pruning). Amend the architecture sketch in `README.md`, which currently
>    says `probe/ ... (active, WorkManager)`.
> 2. Treat Samsung as a **first-run setup checklist**, not an architecture driver: request
>    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and deep-link the user to Device Care →
>    *Sleeping apps* / *Deep sleeping apps* / *Put unused apps to sleep* and have them exclude the
>    app. dontkillmyapp is explicit that there is **no programmatic fix; the user must whitelist
>    manually** **[D]**. Use `JobScheduler#getPendingJobReasonsHistory` (Android 16) to detect and
>    *report* suppression rather than trying to defeat it **[V]**.

Sources: [dontkillmyapp / Samsung](https://dontkillmyapp.com/samsung) ·
[Improving Consistency of Background Work on Android](https://android-developers.googleblog.com/2023/05/improving-consistency-of-background-work-on-android.html) ·
[Android Authority, May 2023](https://www.androidauthority.com/samsung-google-background-app-promise-3321467/) ·
[Behavior changes: all apps (Android 16)](https://developer.android.com/about/versions/16/behavior-changes-all)

### 4. MapLibre Android reads `pmtiles://` natively. Delete the shim — and pick *one* offline mechanism.

`map-stack.md` hedges with "either native PMTiles support in the MapLibre version we pin, or —
guaranteed to work regardless — a localhost shim: a ~50-line NanoHTTPD server". The hedge is
resolved: **MapLibre Android 11.8.0 (January 2025) added PMTiles support; "On Android and iOS the
`pmtiles://` protocol is handled by MapLibre Native itself, with no extra code"**, and it works for
vector, raster and raster-dem sources, from style JSON or programmatically **[V]**.

Two constraints come with it **[V]**:

- The URL inside `pmtiles://` must be **fully specified** — `pmtiles://https://host/x.pmtiles` or a
  `file://` path, never a bare relative name.
- **`AssetManagerFileSource` does not implement byte-range reads**, which PMTiles requires. A
  bundled asset will not work; the archive must live on device storage and be addressed `file://`.
  (This happens to reinforce the existing "not bundled in the APK" decision for a better reason than
  the one given.)

**The bigger problem this exposes is a contradiction between two of our own docs.** `map-stack.md`
makes PMTiles the primary offline basemap. `map-regions.md` builds organic growth on MapLibre's
**`OfflineManager`** / `OfflineTilePyramidRegionDefinition`. These are two mutually exclusive
offline systems: `OfflineManager` downloads a tile pyramid from a *tile server* into MapLibre's own
SQLite cache; it does not ingest or produce `.pmtiles` **[D]**. You can have one or the other.

> **Action.** Choose, and rewrite whichever doc loses. The honest trade:
>
> | | PMTiles archive | `OfflineManager` regions |
> |---|---|---|
> | Source | Protomaps daily planet build, `pmtiles extract` per region | OpenFreeMap, tile by tile |
> | Growth | We write the downloader (an HTTP range-read extract is ~200 lines) | Built in, enumerable, deletable — exactly what `map-regions.md` wants |
> | Eviction | None, it is our file | Offline regions exempt from ambient eviction |
> | Tile-count cap | n/a | Real, silent, must be raised |
>
> `map-regions.md`'s organic-growth design is the stronger product idea and `OfflineManager` is the
> only thing that implements it off the shelf. Recommendation: **`OfflineManager` against
> OpenFreeMap as primary**, and keep PMTiles as an optional bulk "pre-load a country over Wi-Fi"
> import — which is exactly the inverse of what `map-stack.md` currently says. *(Note: another agent
> holds `map-stack.md`; this is a recommendation for them, not an edit.)*

Sources: [MapLibre Android PMTiles example](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) ·
[MapLibre newsletter, Jan 2025](https://maplibre.org/news/2025-02-03-maplibre-newsletter-january-2025/) ·
[maplibre-native#3166](https://github.com/maplibre/maplibre-native/issues/3166) ·
[Protomaps basemap downloads](https://docs.protomaps.com/basemaps/downloads)

### 5. The 18 "No service" events may be a firmware regression, not coverage and not tune-away.

`multi-sim.md` ranks two hypotheses for the data SIM's hourly service loss: (1) the home carrier coverage gaps,
(2) DSDS contention from slot 1. Research supplies a **third** that neither doc considers, and
weakens the first.

**Against hypothesis 1.** the home carrier is not a thin network by the regulator's measure. the national regulator requires
operators in the reference market to meet **>99 % nationwide outdoor coverage, >85 % per building, >99 % in
road and MRT tunnels**, publishes quarterly QoS results per operator, and the home carrier is certified against
that bar **[V]**. Hourly total service loss is not what a 99 %-outdoor network looks
like. The "TPG's network is newer and thinner" premise in `multi-sim.md` is an assumption we should
stop repeating without evidence.

**The third hypothesis: the build.** There are multiple independent community reports of
**"Emergency calls only" / "No service" on the the reference device specifically after moving to
One UI 8 / Android 16** — an Android Community thread attributing it to a security update, an XDA
thread on the reference model exactly where the SIM works fine in another phone, and a Samsung
Members report of a One UI 8 Wi-Fi-calling bug on the S23 **[A]**. More substantively, **Samsung
paused the One UI 8 rollout to the Galaxy S23 family about three weeks in, and also paused it for
the S24, S22 and Fold SE**, without a public explanation; press reports mention isolated battery and
5G-stability complaints, the latter cleared by an airplane-mode cycle **[D]**.

None of this is confirmed by Samsung and none of it is proof. But it is a *cheap, high-prior*
explanation for "the modem loses service about once an hour on this exact model on this exact
Android version", and it is currently absent from our causal model entirely.

> **Action.** Three things.
> 1. **Add build identity to `device_profile`**: `Build.FINGERPRINT`, `Build.DISPLAY` (the One UI
>    build), the baseband/radio version, and the security-patch level. Without it we cannot tell a
>    firmware regression from a network fault, ever — and "anything not captured at collection time
>    cannot be recovered later" is already this project's stated design rule.
> 2. **Re-rank the hypotheses in `multi-sim.md`** to three: (a) firmware/modem regression,
>    (b) DSDS contention from slot 1, (c) coverage. Drop the unsupported claim about TPG network
>    thinness or attach evidence to it.
> 3. **The A/B gets a third arm.** Disabling slot 1 for a day tests (b). It does not distinguish (a)
>    from (c). A second arm — same SIM, different handset, or the same handset on the previous
>    firmware — is what separates those, and it is worth saying so explicitly because it is the
>    difference between a real finding and a plausible story.

Sources: [the national regulator 4G service monitoring](https://www.imda.gov.sg/about-imda/research-and-statistics/4g-service-monitoring) ·
[the national regulator QoS results Jul–Sep 2025](https://www.imda.gov.sg/regulations-and-licensing-listing/dealer-and-equipment-registration-framework/compliance-to-imda-standards/4g-services/jul-sep-2025) ·
[GSMArena: Samsung halts One UI 8 for S23 family](https://m.gsmarena.com/samsung_halts_one_ui_8_rollout_for_galaxy_s23_family-news-70002.php) ·
[Android Central](https://www.androidcentral.com/phones/samsung-galaxy/samsung-yanks-one-ui-8-from-galaxy-s23-users-amid-rolling-suspensions) ·
[Android Community thread](https://support.google.com/android/thread/421956556/) ·
[XDA: this model, emergency calls only](https://xdaforums.com/t/help-galaxy-s23-ultra-sm-s918b-emergency-calls-only-sim-works-on-other-phones.4773797/)

---

## 1. Prior art

### The headline: nothing found does cause-diagnosis. The niche is genuinely open.

Every rootless Android tool surveyed sits in one of three buckets — **display**, **log/contribute**,
or **speed-test**. None correlates radio, registration, IP and transport layers on one clock to
*name a cause*. The one tool that does real cross-layer causal analysis, MobileInsight, needs root.

| App | What it is | Does it well | Complaints | Cause diagnosis? |
|---|---|---|---|---|
| **Network Cell Info Lite** (m2catalyst) | The default "show me dBm" app. Gauges, cell map, Wi-Fi. 4.06★ / ~85 k ratings **[D]** | Breadth of fields; longevity | Ads and permission-nagging; "no network" / stale display on Android 13; conflicting 5G SA data; map failures **[A]** | **No** — display only |
| **NetMonster** (mroczis) | The technically-best display tool. GSM→5G SA, detects LTE-A / NSA carrier aggregation, cell logging, map, export. Free + paid pro **[D]** | Correctness. Its open-source [`netmonster-core`](https://github.com/mroczis/netmonster-core) actively works around vendor RIL lies — e.g. issue #53, Samsung basebands reporting `CONNECTION_SECONDARY_SERVING` for cells not carrying data **[D]** | App core is proprietary; tower locations crowdsourced and of variable accuracy **[D]** | **No** — display + log |
| **SignalCheck Pro** | Display + neighbour cells + user-defined alerts + shortcuts into hidden Android engineering screens. 3.98★ / ~900 ratings **[D]** | Alerting on user-defined events is the closest thing to event detection in the field | Cluttered UI, ads, buried features **[A]** | **No** — thresholds, not causes |
| **CellMapper / Tower Collector** | Crowdsourced tower mapping. Tower Collector is MPL-2.0, on F-Droid, uploads to **OpenCelliD *and* beaconDB** **[V]** | Clean contribution model | — | No, and not trying to |
| **Network Survey** (christianrowlands) | Open-source cellular/Wi-Fi/BT/GNSS survey recorder; local-first, optional MQTT/gRPC/OpenCelliD/beaconDB upload; tower map **[V]** | The closest thing to our Phase-1 collector that already exists, and it is open source | — | No — records, does not interpret |
| **G-NetTrack / Network Signal Guru** | Drive-test grade. NSG decodes L2/L3/SIP — **but "NSG needs a super user access... you have to root the device"**, except on some HiSilicon firmware **[V]** | Real protocol visibility | Root | Partly, and out of reach |
| **MobileInsight** | Research tool. Reads `/dev/diag` via a `diag_revealer` daemon for RRC/NAS messages and does genuine cross-layer analysis **[V]** | The only true cause-analysis prior art | **Requires root**, Qualcomm-only, research-grade UX | **Yes — but rooted** |
| **OpenSignal / nPerf** | Crowdsourced coverage + speed tests; background passive+active collection, sensor and battery telemetry **[V]** | Scale | It is a data-collection business; the user is the instrument. Measures throughput, our stated wrong metric | No |
| **PCAPdroid / NetGuard** | Loopback `VpnService` per-app flow capture / firewall **[V]** | Exactly the Tier-1 technique `README.md` cites | — | No |
| **WiGLE** | Wardriving. App BSD-3-Clause; **database proprietary**, [EULA-governed](https://wigle.net/eula.html), commercial use opt-in only **[V]** | Huge dataset | Licence is not open data | No |

**Implication — the design bet survives, with one correction.** "Diagnosis-by-cause rather than
metric display" is not occupied. But note *why*: the rootless tools all stop at display because
Tier 0 does not give them the sub-second radio timeline that causal attribution seems to need.
Our answer — that **Layer 4 outcome is the ground truth and radio is merely the explanation**
(`measuring-reality.md` Part 2) — is precisely the move that makes causal diagnosis possible without
root. That inversion is the defensible idea in the project and it deserves to be stated as such.

**Two concrete borrowings.**

- **Read `netmonster-core` before writing `collect/`.** It is a maintained catalogue of the exact
  vendor-RIL divergences `measuring-reality.md` Part 4 tells us to calibrate for, including
  Samsung-baseband-specific ones. Re-deriving it from scratch is waste.
- **Tier 1 has a hard blocker worth stating now:** Android permits **one `VpnService` at a time**;
  PCAPdroid's own FAQ says it cannot run alongside NetGuard **[V]**. Phase 4 therefore *takes the
  user's VPN slot*. On a daily driver with a VPN installed, per-app attribution is unavailable —
  not degraded, unavailable. Put that in the Phase-4 scope note rather than discovering it later.

---

## 2. Android API reality checks

### 2.1 Callback throttling — confirmed, and it is by design

`device-findings.md` §B.1–B.3 measured it. Published sources corroborate the mechanism:

- **`getAllCellInfo` no longer refreshes.** "Apps targeting Android Q or higher will no longer
  trigger a refresh of the cached CellInfo by invoking the `getAllCellInfo` API, and instead will
  receive the latest cached results, which may not be current" — and callers are told to check
  `CellInfo#getTimeStamp()` for recency **[V]**. Our measurement of 1440 s-stale `CellInfo` is the
  documented contract, not a fault.
- **`requestCellInfoUpdate()` is explicitly unreliable.** "In all cases, updates will be rate-limited
  and are not guaranteed" **[V]**. No numeric rate is published, by anyone, anywhere found. This
  means **the burst-mode budget cannot be designed from documentation** — it has to be measured.
- **Reporting criteria live in the modem, not the framework.** Since Android 11 the platform sets
  signal-strength reporting criteria per RAN via `setSignalStrengthReportingCriteria_1_5`, and
  **"when the reporting criteria of a measurement type is enabled for a RAN, the reporting criteria
  of other measurement types are disabled"** **[V]**. So which of RSRP/RSRQ/RSSNR even *generates*
  callbacks is a carrier-config-driven, per-device property — which is a sharper version of
  `measuring-reality.md` Part 1's argument than the doc currently makes, and it should be folded in.
- **Default hysteresis is 2 dB**, and 0 disables dB-based hysteresis; `hysteresisMs` 0 disables
  time-based hysteresis **[V]** — but only via `SignalThresholdInfo`, which needs the privileged
  request in finding #2.

> **Verdict on "1 Hz burst mode": unachievable at Tier 0 for Layer 1, as `device-findings.md`
> already concluded.** The research adds *why* and adds the escape hatch. Nothing found contradicts
> the measured ~7-minute screen-off floor. Note also that a 1 Hz *poll* is not merely useless but
> actively wrong: it re-reads one cached object and writes duplicate rows, which is what
> `device-findings.md` §B.7 found.

### 2.2 Background `getAllCellInfo` on 14/15/16

Nothing found contradicts the design's claim that a `location`-typed foreground service with
`ACCESS_BACKGROUND_LOCATION` is sufficient on Android 10+ **[V]**. The constraint to note is the
one on *starting* it: `location` FGS "cannot be created while your app is in the background, unless
you've been granted `ACCESS_BACKGROUND_LOCATION`" **[V]** — so the start path after a reboot or a
process death needs care, and `location` is at least not on the `BOOT_COMPLETED` prohibition list
that `dataSync` is on.

### 2.3 Foreground service types — the full constraint set

| Constraint | Applies | Source |
|---|---|---|
| `location` needs `FOREGROUND_SERVICE_LOCATION` + coarse/fine at runtime + location services enabled | A14+ | **[V]** |
| `location` **has no timeout** | A15/16 | **[V]** |
| `dataSync` capped at **6 h / 24 h**, then `onTimeout()` then fatal exception | A15+ | **[V]** |
| `dataSync` cannot start from `BOOT_COMPLETED` | A15+ | **[V]** |
| Jobs started while an FGS runs obey **JobScheduler runtime quotas**, WorkManager included | **A16, all apps** | **[V]** |
| FGS types must be declared in **Play Console → Policy → App content** | A14+ target | **[V]** |
| Background location needs a **Play permissions declaration + demo video + single named feature**; multiple features = rejection | Play policy | **[V]** |

The last two only bite if we distribute via Play. `objective.md` says debug APK, so treat them as a
**distribution risk, deferred but recorded** — and be aware that "continuous background cellular
measurement" is close to the shape of thing Play scrutinises hardest, and that the declaration form
requires naming exactly *one* background-location feature.

---

## 3. Samsung One UI specifically

The nuanced picture, in order of confidence:

1. **[V]** Samsung publicly committed, jointly with Google, that from One UI 6.0 foreground services
   of apps targeting Android 14+ work as intended *if built to the FGS API policy*. The Android
   Developers Blog post of May 2023 is the primary record.
2. **[D]** dontkillmyapp nonetheless still rates Samsung worst (5/5), and its per-version notes list
   the surviving mechanisms: **Sleeping apps**, **Deep sleeping apps**, **Unused apps**, adaptive
   battery, and — the one it calls out as most damaging — *"put an app you did not use for X days to
   a mode with restricted background processing"*, with X as low as **3 days**. One UI 7/8 moved
   these settings to a different path but kept them.
3. **[D]** The Android 11 divergence that earned the rating — **blocking wakelocks held inside
   foreground services** — is the specific thing that was reverted. That matters directly to us:
   `device-findings.md` §B.4 measured a continuously-held wakelock at 19.6 mA ≈ 9.4 %/day, i.e.
   1.5× the entire battery budget, so **we must not hold one anyway**. The historically most
   Samsung-hostile pattern is one our own power measurements already forbid.
4. **[A]** Users continue to report apps being slept and reloaded on One UI 7/8, and there are
   reports of foreground apps being killed on 4 GB One UI 8 devices. The the reference device has 8–12 GB, so
   memory pressure is not our scenario.

> **Assessment: not severe enough to change the architecture.** A `location`-typed foreground
> service with an ongoing notification on One UI 8 / Android 14+-targeting is the *supported* path
> and Samsung has publicly committed to it. Design instead for **detecting and reporting**
> interruption rather than preventing it, which is cheap and turns a platform weakness into a
> feature:
>
> - Persist a heartbeat row; on every service start, compute and log the gap since the last one.
> - Surface **"collection gaps"** as a first-class thing in the UI and in exports. A diagnostic tool
>   that silently has holes in its timeline is worse than one that shows them — and an unexplained
>   gap is otherwise indistinguishable from a network outage, which would generate *false
>   diagnoses*. This is a correctness requirement, not just hygiene.
> - First-run checklist: battery-optimisation exemption, plus deep-links into Device Care.
> - Use `getPendingJobReasonsHistory()` on A16 to attribute suppression when it happens.

---

## 4. DSDS tune-away

**The mechanism is confirmed; the magnitude is not published; our specific hypothesis is plausible
and directly supported by one patent's problem statement.**

The literature is almost entirely patents — which is itself informative: this is a modem-vendor
implementation concern, not a standardised 3GPP behaviour, so there is no spec to cite and no
published numbers.

- **Mechanism [V].** On DSDS the RF chain is shared; "RF tune away" pauses operation on SIM 1 to
  serve SIM 2. Crucially, **"PS data operation on the first SIM is considered a low priority whereas
  signaling, paging, system information reading, and measurements on the second SIM are considered
  high priority"** (Samsung, US10568073B2). That is exactly the priority inversion our hypothesis
  requires.
- **Our hypothesis is the named problem [V].** The same patent's problem statement lists the
  consequences of tune-away as *"delay in resource allocation post RF blackout, a degradation of
  throughput, an increase in power consumption, **loss of service**, **paging misses**, and
  scalability issues"*. "Loss of service" on the data SIM as a consequence of the other SIM's
  activity is stated by the vendor itself.
- **A SIM in No-service is the worst case [D].** Qualcomm's *Tune Away Adjustment Procedure*
  (US2017/0094568) and several others describe mitigations that key on the second subscription's
  channel conditions and paging cycle — increasing the Slot Cycle Index to page less often when the
  second sub is weak. The mitigation exists because the problem does: a second sub in poor or no
  coverage tunes away more and for longer.
- **Interruption is real at the RLC layer [V].** "Because the UE is tuned away to the second RAT
  with a single receiver, retransmissions of the RLC PDU from the network may be missed, resulting
  in an interruption in communication on the first RAT."
- **VoLTE is explicitly protected [V].** Multiple patents (US9131429B1, WO2016003562A1) describe
  *blocking* tune-away during a VoLTE call on DSDS. **This predicts something testable and
  interesting: tune-away stalls should be suppressed during a voice call and present between them.**
  If our data shows data gaps that vanish during `OFFHOOK`, that is a tune-away fingerprint. Add it
  to the detection rule in `multi-sim.md` §3a.

**What is not available:** any published figure for throughput or outage impact. No operator
engineering post, no chipset datasheet, no 3GPP number was found. So we can assert the mechanism
with confidence and must treat magnitude as unknown — which makes the slot-1-disabled A/B the only
way to size it, exactly as `multi-sim.md` says.

**One caution against over-claiming.** Tune-away is sub-second to few-second by construction. The
device logged outages of **19, 25, 51 and 86 seconds**. Those are an order of magnitude too long for
a tune-away stall. Tune-away may well explain a class of short unexplained gaps in our data; it is a
poor fit for the headline 86-second `No service` event. `multi-sim.md` currently lets those two
findings sit next to each other as if hypothesis 2 could explain the observed outages — it cannot,
at that duration, and the doc should say so.

Sources: [US10568073B2 (Samsung)](https://patents.google.com/patent/US10568073B2/en) ·
[US2017/0094568 (Qualcomm)](https://www.freepatentsonline.com/y2017/0094568.html) ·
[US9131429B1](https://patents.google.com/patent/US9131429B1/en) ·
[US20130303181A1](https://patents.google.com/patent/US20130303181A1/en)

---

## 5. Crowdsourced coverage ecosystem

| Project | Status | Licence | API | Would it take our data? |
|---|---|---|---|---|
| **OpenCelliD** | Live, Unwired Labs | **CC-BY-SA 4.0** **[V]** | Downloads + API key | Yes (Tower Collector path) |
| **beaconDB** | **Live and real** — 6.7 M towers, 140 M+ networks, 4.7 M beacons, 217 countries **[V]** | *Intended* public domain; **dumps not yet published** — "still working on obfuscating the data" **[V]** | `api.beacondb.net/v1/geolocate` (MLS/Ichnaea-compatible) and `/v2/geosubmit` **[V]** | **Yes** — opt-in submissions, no PII |
| **radiocells.org** (ex-openbmap) | Live, ~700 k cells / 10 M Wi-Fi **[D]** | **Dual: cells CC-BY-SA 3.0, Wi-Fi ODbL** **[D]** | UnifiedNlp backend, offline DB | Yes |
| **WiGLE** | Live | **Proprietary DB**, EULA, commercial use opt-in **[V]** | API | One-way |
| **Ichnaea / MLS** | Mozilla Location Service **retired**; Ichnaea is the software, beaconDB the live successor **[V]** | — | — | n/a |

**beaconDB is confirmed to exist and is the right partner if we ever contribute.** It is
MLS-API-compatible, privacy-first (opt-in only, published data obfuscated), and already has two
established Android contributor apps — Tower Collector and Network Survey **[V]**.

**But the licence question is not closed, and this matters more than the doc allows.**
`adaptive-aggregation.md` states flatly that OpenCelliD is dropped and therefore the obligation is
gone. That reasoning holds for *consuming*. It does not cover *contributing*, and beaconDB's
public-domain licence is still **stated as a future intention, not a published term** — there are no
dumps yet to carry a licence. Contributing to a database whose output licence is not yet fixed is a
decision to make consciously.

> **Action.** Record a one-line position in `coverage-map.md`: *we consume nothing, and we contribute
> nothing until a partner's output licence is published and compatible.* That keeps the door open
> without incurring an obligation. And note the asymmetry these projects have that we do not: they
> want **tower positions**; we produce **usability bins**, which nobody else collects. There is no
> existing database that would take our actual output — which is a moat, not a gap.

---

## 6. Map stack viability

**OpenFreeMap is genuinely live, genuinely keyless, and genuinely fragile in exactly the way we
assumed.**

- **[V]** Stated policy: *"Using our public instance is completely free: there are no limits on the
  number of map views or requests"*; *"There's no registration, no user database, no API keys, and
  no cookies."* No acceptable-use clause restricting mobile apps or bulk fetching was found. No SLA,
  no uptime guarantee, no warranty language.
- **[D]** Funding: a single maintainer (Zsolt Ero / Hyperknot), donation-funded at roughly
  **$500/month**, Hetzner dedicated servers in round-robin DNS, with **Cloudflare sponsoring
  bandwidth**.
- **[D]** Capacity is proven: August 2025, **3 billion requests in 24 hours, peaking near 100 000
  requests/second**, surviving with only some missing tiles.
- **[V]** Self-hosting is *not* a drop-in fallback: *"OpenFreeMap is not something you can install
  locally. This repo is a deploy script specifically made to set up clean Ubuntu 24.04 servers."*

> **Assessment: fine as a fallback, correct to refuse as the primary.** The concentration risk is
> one person and $500/month. `map-stack.md`'s reasoning — offline primary, OpenFreeMap fallback — is
> right, but the *reason* should be restated: not just "used where the network doesn't work" but
> "the online source can disappear with no notice and no contract." Protomaps' ODbL daily planet
> build is the insurance policy; **note its terms: noncommercial use free, commercial use asks for
> GitHub sponsorship** **[V]** — worth recording even though we are noncommercial.

**PMTiles:** resolved, see finding #4. **Delete the NanoHTTPD shim from the plan.**

**H3 rendering on MapLibre Android:** no published benchmark was found for polygon counts. What is
documented: a laggy-sync-GeoJSON issue fixed via `GeoJsonOptions.withSynchronousUpdate(true)`, and
fill-pattern rendering artefacts at tile boundaries in Android 11.0.0/11.0.1 **[D]**. So
`map-stack.md`'s "tens of thousands of hex bins on the GPU" is **plausible but unverified** — treat
it as an assumption to benchmark in the Phase-1 spike, not a known.

**One additional dependency to size:** `h3-java` is a JNI binding over the C library. It now
publishes an **`h3-android`** artifact and has been updated for Android's 16 KB page-size
requirement **[D]** — so it is viable, but it adds native `.so` files per ABI on top of MapLibre's
10–15 MB per ABI. Confirm the combined APK size early; a pure-Kotlin H3 implementation or storing
`(lat,lng)` rounded to a fixed grid are the escape hatches.

---

## 7. Shizuku

**Works on Android 16, with caveats, and is more valuable than the design currently assumes.**

- **[D]** Android 16 support is in the **GitHub builds**, not the Play Store release, which has
  been stale for over a year. Ship instructions pointing at GitHub.
- **[D]** OEM friction is real: on recent One UI / OxygenOS-16-class builds, users must enable
  *Disable System Optimization* (formerly *Disable Permission Monitoring*) in Developer options
  before Shizuku's wireless-debugging start works. Community forks (ShizukuPlus) exist specifically
  for One UI / Android 16+ compatibility fixes. Expect setup support burden.
- **[D]** `Shizuku.getUid()` returns **2000** for shell-backed sessions. Shell can run `pm`, `am`,
  **`svc`**, `settings` — so **`svc data disable/enable` remains reachable**. No evidence of a
  blocklist change removing it was found. Unverified on One UI 8 specifically.
- **[D]** **Shizuku bundles a hidden-API blacklist bypass**, so `setPreferredNetworkTypeBitmask`
  is not blocked by the API blocklist. The *permission* is the question, and here the news is good:
  **`com.android.shell` declares `MODIFY_PHONE_STATE`** in AOSP **[V]** — the permission that
  `setPreferredNetworkTypeBitmask` and `setSignalStrengthUpdateRequest` both require.

> A manifest declaration is not proof of a grant. The concrete on-device check, one command:
> `adb shell dumpsys package com.android.shell | grep -i MODIFY_PHONE_STATE`.
> If granted, Tier 2 unlocks **both** pin-to-LTE *and* unthrottled signal reporting — and the
> phasing in `README.md` (Tier 2 = Phase 3, actions only) is wrong by a phase and by a category.

Sources: [Shizuku](https://github.com/RikkaApps/Shizuku) ·
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) ·
[Shizuku privileged API notes](https://hacktricks.wiki/en/mobile-pentesting/android-app-pentesting/shizuku-privileged-api.html) ·
[ShizukuPlus](https://github.com/thejaustin/ShizukuPlus) ·
[DroidWin: Shizuku on Android 16](https://droidwin.com/shizuku-not-working-on-android-16-fix/)

---

## 8. Metrics and QoE evidence

**The thresholds in `measuring-reality.md` are reasonable and broadly consistent with the
literature. Two corrections and one addition.**

**What the evidence supports.**

- **RSRQ is the load/interference metric and it predicts VoLTE degradation.** Field-measurement work
  finds *"the most impacting factor is RSRQ... jitter and RTP error rate exhibit significant
  degradation when RSRQ is degraded"*, and recommends optimising handover parameters on RSRQ as well
  as RSRP **[D]**. This directly supports `measuring-reality.md` Layer 1's framing of RSRQ.
- **SINR is the throughput predictor** and RSRP alone is insufficient **[D]**. Supported.
- **Handover interruption, quantified [V/D].** Pre-Rel-14 LTE handover interruption is **at least
  45 ms** (3GPP TR 36.881); L1/L2-triggered mobility brings it to **20–30 ms**; MCG failure recovery
  after RLF gives **30–70 ms**; a traditional RLF → RRC re-establishment is *"hundreds of
  milliseconds"* (Ericsson).
- **eSRVCC targets < 0.3 s voice interruption and ≤ 1 % call drop** **[D]**.

**Correction 1 — `objective.md`'s handover numbers are pessimistic and its RLF numbers are
optimistic.** The doc says handover is "50–300 ms normally" (true-ish; the floor is 45 ms) and RLF
→ RRC re-establishment is "a 1–3 s gap". The Ericsson material puts re-establishment at *hundreds of
milliseconds*, and 30–70 ms where MCG failure recovery applies. **A 1–3 s gap is not a normal
re-establishment — it is a failed one, or something else entirely.** That matters, because
`objective.md` ranks RLF as cause #2 for socket death partly on that 1–3 s figure. Tighten the
numbers; the *ranking* still holds, because IP change (cause #1) remains categorically worse than
any gap.

**Correction 2 — do not expect a clean metric→MOS mapping.** One field study reports that *"across
a wide range of RF conditions, LTE provides MOS > 3 with no obvious trend correlation for a wide
range of RSRP and RSRQ values"* **[D]**. VoLTE is robust until it isn't — the relationship is a
cliff, not a slope. This is **evidence for our design, not against it**: it is precisely why
threshold-colouring a map by RSRP fails, and why `measuring-reality.md`'s refusal of a single 0–100
score is right. But it also means *our* Layer-1 thresholds should be presented as **coarse bands for
explanation**, never as a predictor of call quality. Add that caveat to the Layer-1 table.

**Addition — the strongest external support for the whole project.** Jia et al., *Performance
Characterization and Call Reliability Diagnosis Support for Voice over LTE*, **MobiCom 2015**,
University of Michigan **[D]**. Its finding is that VoLTE failures are rooted in **"the lack of
coordination among protocols designed for different purposes, and invalid assumptions made by
protocols... when integrated with VoLTE"** — identifying consistent call-setup failure, mis-ordered
inter-dependent actions causing drops, and cross-layer coordination failures causing extremely long
muting. That is a peer-reviewed statement that **call failures are cross-layer coordination
failures, not signal failures** — which is this project's founding premise, arrived at
independently. It belongs as a citation in `objective.md`.

Sources: [Jia et al., MobiCom 2015](https://www.sigmobile.org/mobicom/2015/papers/p452-jiaA.pdf) ·
[VoLTE field measurement (arXiv 1810.02968)](https://arxiv.org/abs/1810.02968) ·
[Ericsson: L1/L2-triggered mobility](https://www.ericsson.com/en/reports-and-papers/ericsson-technology-review/articles/reducing-handover-interruption-l1l2-triggered-mobility) ·
[Ericsson: fast recovery from RLF](https://www.ericsson.com/en/blog/2020/9/fast-recovery-from-radio-link-failure)

**One more, on `NET_CAPABILITY_VALIDATED`.** `measuring-reality.md` Layer 3 correctly calls it a
lagging indicator. Corroboration: validation is a `generate_204`-style HTTP probe against
`connectivitycheck.gstatic.com` / `www.google.com` with a **10 000 ms default timeout** **[D]**.
A 10-second timeout is an upper bound on how late the flag can be — useful for bounding an incident
and a good number to put in the UI when explaining why the timeline is fuzzy.

---

## 9. Reference-market specifics

- **[V] the home carrier is held to the same regulatory bar as the incumbents.** the national regulator's 4G QoS framework
  requires **>99 % nationwide outdoor coverage, >85 % in-building per building, >99 % in road and
  MRT tunnels**, and the national regulator publishes per-operator quarterly results. Anyone can cite the published
  quarter against the device's own measurements — which is, incidentally, a *product feature*: "your
  carrier is certified at >99 % outdoor coverage; here is your measured validated uptime at this
  location." That comparison is more persuasive than any internal metric.
- **[D]** the home carrier's spectrum is 900 MHz (20 MHz), 2.3 GHz (40 MHz) and 2.5 GHz (10 MHz), plus 2.1 GHz
  and mmWave for 5G. The device is on **B40 (2.3 GHz)** — the home carrier's *capacity* band, not its coverage
  band. **900 MHz (B8) is the propagation layer.** A device parked on B40 indoors or at the edge is
  on the wrong layer for coverage, and B40 TDD at 2.3 GHz has materially worse building penetration
  than B8. *This is a testable, specific, local hypothesis the docs do not currently contain:* **log
  `bandNum` against service-loss events and see whether losses correlate with B40-only camping and
  whether B8 is ever selected.** If the device never falls back to B8, that is a carrier
  configuration or device band-priority issue and it is nameable.
- **[A]** No credible corpus of the home carrier "no service" complaints was found. Absence of evidence at
  this search depth, but it does weaken hypothesis 1 in `multi-sim.md` further.
- **[V] VoWiFi while roaming is architecturally special.** For international-roaming subscribers the
  **ePDG selects a dedicated, separately-configured P-GW** (Cisco ePDG documentation), and operators
  configure ePDG handover behaviour differently for roamers — including suppressing handover
  requests for VoWiFi international-roaming subscribers. So the roaming SIM's VoWiFi path is not the
  same path as the home SIM's, and VoWiFi↔VoLTE continuity for a roamer is a known
  operator-configuration surface rather than a device property. `README.md` cause #12 is right to
  exist; it is more likely on slot 1 than slot 0 and the doc should say which.
- **[A]** Community reports of a **One UI 8 Wi-Fi-calling bug on the Galaxy S23** exist, which
  interacts with the finding-#5 firmware hypothesis. Worth knowing before attributing VoWiFi
  behaviour to the carrier.

---

## What we still don't know — the on-device test list

Research cannot settle these. Each needs a measurement on the actual handset.

**Blocking, do first**

1. **Does shell UID actually hold `MODIFY_PHONE_STATE` on this device?**
   `adb shell dumpsys package com.android.shell | grep -i MODIFY_PHONE_STATE`. Everything in
   finding #2 hangs on this one line of output.
2. **If yes: does `setSignalStrengthUpdateRequest` with `hysteresisDb=0`, `hysteresisMs=0`,
   `setReportingRequestedWhileIdle(true)` measurably raise the callback rate while idle and
   screen-off?** The docs say best-effort and explicitly warn thresholds may be modified. This is
   the single highest-value experiment in the project, because it determines whether Layer 1 has
   useful time resolution at all.
3. **What is the real `requestCellInfoUpdate()` rate limit on this modem?** No published figure
   exists. Measure: call it in a tight loop and record `CellInfo.getTimestampMillis()` deltas to find
   the point at which fresh results stop arriving. That number sets the entire burst-mode budget.
4. **Does a `location`-only foreground service survive 24 h+ on One UI 8 with battery optimisation
   disabled?** And separately, does it survive *without* the exemption? The delta is what the
   first-run checklist is worth.

**Design-determining**

5. **Does disabling slot 1 reduce slot 0's service-loss rate?** The `multi-sim.md` A/B — now with
   the third arm from finding #5 (different firmware or different handset) to separate a modem
   regression from a network fault.
6. **Do the short unexplained data gaps disappear during `OFFHOOK`?** If tune-away is blocked during
   VoLTE calls as the patents describe, this is a clean fingerprint.
7. **Do service-loss events correlate with B40-only camping, and does the device ever select B8?**
   The market-specific hypothesis from §9.
8. **Which Layer-1 metrics return `UNAVAILABLE`, per subscription?** `multi-sim.md` already observed
   that timing advance differs between subs on the same device — availability is not purely a device
   property, and the `device_profile` schema needs to reflect that.
9. **`getRssnr()` units — dB or 0.1 dB on this RIL?** Flagged in two docs, still unresolved, and it
   silently corrupts every quality judgement downstream until it is.

**Sizing and feasibility**

10. **MapLibre Android polygon budget.** At what bin count does the fill layer stop holding 60 fps on
    this device? No published benchmark exists. Determines whether adaptive aggregation is a nicety
    or a hard requirement.
11. **APK size with MapLibre Native + h3-android per ABI.** Estimated 10–15 MB + native H3; unmeasured.
12. **`OfflineManager` tile-count cap** — `map-regions.md` flags it as failing silently. Find the
    actual default before designing around it.
13. **Real cost of a `location` FGS with fused `BALANCED` location over 24 h**, measured against the
    3 %/day budget from `device-findings.md` §B.4.
14. **Does `svc data disable/enable` still work via Shizuku on One UI 8?** No contrary report found,
    no confirmation either.

**Open and possibly unanswerable**

15. **Is the the reference device / One UI 8 service-loss report a real regression, and does it apply to this
    build?** Samsung has published no explanation for pausing the rollout. Only a firmware-controlled
    comparison will tell us, and that may not be available.
