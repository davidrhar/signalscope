# Collection spec and data model

**Phase 1. Android only, Tier 0. This is the foundation everything else reads from —
the map, the incident engine, and any later optimisation.**

Design rule: *anything not captured at collection time cannot be recovered later.* Position,
monotonic timing and per-sample availability flags all fall into that category, so they are
specified here rather than bolted on.

---

## 1. Sampling model

**Revised from measurement.** The original spec here — 30 s/5 s heartbeats and a 1 Hz burst mode
— was written from the API surface and is wrong about what the platform will actually deliver.
Empirical cadence is in [`device-findings.md`](device-findings.md); the numbers below follow from
it.

### What the platform actually gives us

Measured on the target device, screen on, stationary: 300 registry polls over 355 s produced
**5 `SignalStrength` changes and 0 `CellInfo` changes**, with cached `CellInfo` up to 1440 s
stale and never refreshing on its own. Over 8.4 h of history, signal-level changes ran
**8.4/hour screen-off against 359/hour screen-on** — screen state is a ~40× gate on delivery.

Three consequences, each of which overturns something the earlier spec assumed:

1. **1 Hz burst mode is unachievable for Layer 1.** The callback rate *is* the measurement rate;
   there is no faster source. **Layer-1 incident resolution is bounded near 10 s by the
   platform**, not by our code, and no amount of asking changes it.
2. **`CellInfoListener` delivers nothing at rest.** Neighbour data must come from an explicit
   `requestCellInfoUpdate()`. This inverts the earlier guidance, which treated the listener as
   primary and explicit refresh as a burst-only tool.
3. **`ServiceState` and `TelephonyDisplayInfo` fire every 8–12 s regardless.** So the RAT-thrash,
   CA and registration detectors are already well served, for free.

### The modes

| Mode | Trigger | Rate | Purpose |
|---|---|---|---|
| **Event** | Platform callbacks, per subscription | As delivered | The primary stream. Registered permanently; effectively free |
| **Alignment tick** | Timer, screen-off | **15 min** | A heartbeat only, for continuity. Not a sample |
| **Heartbeat** | Timer, screen-on | **30 s** | Was 5 s; nothing changes that fast |
| **In-call / moving** | Call active, or motion | 5 s | The windows that matter |
| **Burst** | Anomaly detected | **Layer 3/4 only**, 10 s floor on `requestCellInfoUpdate` | Captures the transient within platform limits |

**The screen-off heartbeat is cut entirely.** At 30 s it would write ~2,700 byte-identical rows a
night — cost with no information. Write on callback instead, with the 15-minute tick purely to
prove the collector is alive.

**Burst mode survives, redefined.** It can no longer mean "sample Layer 1 faster", because that
is impossible. It means: escalate Layer 3 and Layer 4 — capability changes, link properties,
active probes — where we *do* control the rate, and refresh cell info at a 10 s floor.

Triggers unchanged: loss of `VALIDATED`, serving-cell change, RAT transition, IP address change,
`DATA_SUSPENDED`, probe failure, `activeDataSubId` change, call state entering `OFFHOOK`.

### The dormancy gate

`TelephonyManager.getDataActivity()` is Tier 0, needs **no permission**, and returns
`DATA_ACTIVITY_DORMANT` when the radio is in RRC idle.

**Never issue an active probe while dormant.** A probe that wakes an idle radio triggers an RRC
promotion that costs more energy than the entire rest of the collector combined. Gate every probe
on this check; when dormant, wait for traffic the device was going to generate anyway.

This is the cheapest high-value field found in the whole survey, and it belongs in the hot path.

---

## 2. The correlation clock

**Every row carries two timestamps:**

- `elapsedNanos` — `SystemClock.elapsedRealtimeNanos()`. Monotonic, survives sleep. **This is
  the join key.**
- `wallMillis` — `System.currentTimeMillis()`, for display only.

Wall clock jumps on NTP sync and timezone change. We are correlating events milliseconds apart
across four subsystems; a single NTP correction would silently reorder them and produce a false
diagnosis. Note that `CellInfo.getTimestampMillis()` is already elapsed-realtime based, which
makes it directly comparable — use it rather than the arrival time of the callback.

---

## 3. Schema

Room entities. Nullable columns everywhere a metric can be `CellInfo.UNAVAILABLE` — store
`null`, never `0`, and never a sentinel.

### `radio_sample`
```
id, elapsedNanos, wallMillis, subId, mode(event|heartbeat|burst)
rat                     -- LTE, NR_SA, NR_NSA, WCDMA, GSM
servingPci, servingCi, servingTac, servingArfcn, bandNum, mcc, mnc
rsrp, rsrq, rssnr, cqi, timingAdvance          -- all nullable
level                    -- AOSP getLevel()
vendorLevel              -- the OEM's own bar (Samsung SignalBarInfo). NOT the same number
dataActivity             -- DORMANT gates all probing; see s1
neighbourCount, neighboursWithin6dB
neighbourJson            -- compact: [{pci, rsrp}], capped at 8
positionBinId, positionAccuracyM               -- nullable
```

### Cell identity: serving vs neighbour

These two are not equally identified, and the difference decides what can be pooled.

| | Android provides | Globally unique | Poolable across devices |
|---|---|---|---|
| **Serving cell** | MCC, MNC, TAC, **CI** (together: CGI), PCI, ARFCN, full signal | Yes | **Yes** |
| **Neighbour cells** | PCI, ARFCN, signal — usually **no CI** | No | No |

The modem measures neighbours at the physical layer, where PCI is a scrambling code reused
constantly (0–503 LTE, 0–1007 NR); it does not decode each neighbour's broadcast to recover CI.
A neighbour reading is therefore meaningful **relative to the observer's position** — which is
exactly what reselection analysis needs — but pooling it across devices would merge unrelated
cells that happen to share a PCI.

So: **neighbour readings stay local; serving-cell readings carry CGI and travel fine.** This
does not constrain the map, which is keyed on position bins rather than cell identity.

In user-facing text, prefer CGI whenever an identity has to survive leaving the handset. "PCI 411
at home" is correct and useful on this device; it is not an identifier anyone else can resolve.

### Tower position

Android returns cell **identity**, never cell **coordinates** — for serving and neighbour cells
alike. Tower positions are therefore *derived*: the RSRP-weighted centroid of this device's
observations of a given CGI, with timing advance as a distance prior. Coarse at first, improving
with observations. See [`map-stack.md`](map-stack.md) for why no external position database is
used.

### `registration_event`
```
id, elapsedNanos, wallMillis, subId
domain(PS|CS), transportType(WWAN|WLAN), accessNetworkTechnology
regState, rejectCause, nrState
overrideNetworkType, roaming, dataState
availableServices        -- [VOICE,SMS,VIDEO] vs [DATA,MMS]: a finer failure statement
```
The **PS/CS split is mandatory**, not a nicety — CS-up/PS-down is invisible any other way and is
a named root cause.

`transportType` matters as much. A `PS` registration on `transportType=WLAN` with
`accessNetworkTechnology=IWLAN` **is the VoWiFi/ePDG registration** — Tier 0, per subscription,
no privileged call. It was observed live on slot 1 while slot 0's was `UNKNOWN`. This is how
cause 12 (VoWiFi↔VoLTE handover) becomes observable rather than merely suspected.

### `link_event`
```
id, elapsedNanos, wallMillis, netId, transport(CELLULAR|WIFI|VPN)
isDefault, validated, notSuspended, metered, notBandwidthConstrained
v4Address, v6Address, addressChanged            -- addressChanged is the socket-killer flag
dnsServers, mtu, interfaceName, hasClat
```
`addressChanged` is the highest-value single field in the schema. It is the mechanism behind
"Teams reconnected".

### `probe_result`
```
id, elapsedNanos, wallMillis, netId
probeType(DNS|TCP|TLS|HTTP|QUIC), target
outcome(OK|TIMEOUT|REFUSED|DNS_FAIL|TLS_FAIL), latencyMs, errorCode
```

### `call_quality` (Tier 2 — Shizuku)
```
id, elapsedNanos, subId, callId
rtpPacketsLost, jitterMs, rttMs, codecType, rtpInactivityDetected
```
A gap the earlier spec left open: we escalate sampling for the whole duration of a call because
calls are the primary complaint, and then collect nothing that says what went wrong *on the call*.
`CallQuality` closes it — RTP loss, jitter, RTT, codec. It needs `READ_PRECISE_PHONE_STATE`, which
the shell UID holds, so Shizuku reaches it.

### `barring_event` (Tier 2 — Shizuku)
```
id, elapsedNanos, subId, serviceType, barringType
conditionalBarringFactor, conditionalBarringTimeSeconds
```
The only thing that cleanly separates **"congested cell"** from **"access-barred cell"** — two
causes with the same symptom and different fixes.

### `context_sample`
```
id, elapsedNanos, wallMillis
positionBinId, accuracyM, speedMps
motionState(STILL|WALKING|VEHICLE|UNKNOWN)
screenOn, batteryPct, charging, thermalStatus
callState(IDLE|RINGING|OFFHOOK)
```
`thermalStatus` via `PowerManager.getCurrentThermalStatus()` — modem throttling under heat is a
real cause and cheap to rule in or out.

### `bin_rollup` (derived)
```
id, h3Index, resolution, childCount, surveyedChildFrac, ruleVersion
plmn                     -- hard partition: bins never merge across carriers
subId
validatedFrac, probeSuccess
nrAnchorStability        -- nullable: no NR leg means null, not 1.0
transportAnchorStability -- Wi-Fi <-> cellular route flapping, incl. VoWiFi/ePDG
anchorStability          -- min of the non-null two above
contributorSet           -- a SET. Cardinality of the union; never a sum of child counts
dominantClass, sampleCount
```
`nrAnchorStability` must be **null** where no NR leg exists, never 1.0 — an absent measurement is
not a perfect score, and conflating them would paint a dual-LTE device's map uniformly excellent
on a metric it never measured.

### `incident` (derived, Phase 2)
```
id, startElapsedNanos, endElapsedNanos, durationMs
causeClass, confidence, evidenceJson, recommendedAction
```

### `app_flow_event` (Phase 4, VpnService)
```
id, elapsedNanos, uid, packageName, remoteHost, event(OPEN|RESET|FAIL|CLOSE)
```

---

## 4. Two network callbacks, not one

A subtle but important detail:

- `registerDefaultNetworkCallback()` — what apps are *actually using*. Detects the Wi-Fi↔cellular
  route moves that orphan sockets.
- `registerNetworkCallback(NetworkRequest(TRANSPORT_CELLULAR))` — keeps cellular observable
  **even while Wi-Fi is the default**.

With only the first, the app goes blind to the cellular network whenever Wi-Fi is up — and since
walking out of Wi-Fi range mid-call is a primary failure mode, that blindness would sit directly
over the evidence we need.

---

## 5. Position, and why it is binned at write time

The map needs a position on every radio sample. Raw coordinates are never stored.

- **Bin resolution is a function of the fix accuracy, not a constant.** H3 res 10 (~65 m edge)
  is *dishonest* against a 60–100 m fused fix — it asserts precision the position does not have.
  Choose the finest resolution whose cell is no smaller than the reported accuracy: roughly
  res 10 at ≤40 m, res 9 (~174 m) at ≤120 m, res 8 (~460 m) beyond that.
- Store `positionBinId`, its `resolution`, and `accuracyM`. Discard the raw fix.
- Above a configurable accuracy ceiling, store `null` rather than a bad bin.

This also feeds the merge rule in [`adaptive-aggregation.md`](adaptive-aggregation.md): a bin
recorded at res 8 cannot be split into res 10 later, so accuracy at capture sets a permanent floor
on map detail for that observation. Better to record honestly coarse than precisely wrong.

Doing this at write time rather than at export time means **the database never contains raw
coordinates**. Verified against the live schema: neither table has a latitude or longitude column.

**But be precise about what that does and does not mean.** It is not "no movement trace". Two
traces remain, and honesty about them is the point:

- `map_fix` holds a **timestamped position history binned to as fine as ~65 m**. Coarser than GPS,
  still a trace.
- `radio_sample` holds a **timestamped serving-cell history**, which is a movement trace in its own
  right — and it is collected under `READ_PHONE_STATE` **whether or not location is ever granted**.

So binning reduces resolution; it does not eliminate the trace. What bounds it is **retention**
(§7) — without an enforced sweep the history grows without limit, which is the state this project
was in until the security review added one. Treat the local database as sensitive personal data
and say so in the UI, rather than implying the binning has made it anonymous.

Motion state matters for interpretation: PCI churn while `STILL` is reselection ping-pong (a
fault), while `VEHICLE` it is normal handover (not a fault). Without motion context the two are
indistinguishable and the app would generate false diagnoses for anyone on a train.

---

## 6. What the map layer requires from collection

Interface definition only — map design proper is in `coverage-map.md`.

Every `radio_sample` must carry: `positionBinId`, `rat`, `bandNum`, the quality triple
(`rsrp`/`rsrq`/`rssnr`), and — critically — a join path to whether the network was **validated**
and whether **probes succeeded** at that moment.

That last part is what separates this from every existing coverage map. Signal strength maps
already exist and they are misleading for exactly the reason this project began: strong RSRP with
no usable data is a normal state. The map must be able to render *usability*, not power.

---

## 7. Budget

**Volume.** Lower than first estimated, because cutting the screen-off heartbeat removes most of
it. Write-on-callback yields roughly 1–2 k rows/day at ~200 bytes — well under 1 MB/day. Keep full
resolution 7 days, roll up to per-bin/per-hour aggregates after that, drop raw at 30 days. Fully
local; nothing leaves the device in Phase 1.

**Battery. Estimated ≈ 0.6 %/day**, against the original < 3 % target.

Measured component costs on the target device: **GNSS 38 mA active**, **a held wakelock 19.6 mA**,
modem 14.5 mA. The wakelock figure is the one to respect — held continuously it alone is
**9.4 %/day, roughly 3× the entire original budget**. So: *never hold a wakelock*. The foreground
service keeps the process alive; it must not keep the CPU awake.

Levers in order of effect:

1. **No wakelocks.** Wake on callback, write, return.
2. **No probe while `getDataActivity() == DORMANT`** — waking an idle radio costs more than
   everything else combined.
3. **Fused `BALANCED` location**, riding the Wi-Fi scans the device already performs (166 observed
   in 8.4 h). Duty-cycled GNSS only when screen-on *and* moving. Passive-only listening is not
   viable — it went **2.8 days without a fix** on this device.
4. Callbacks over polling; no explicit cell refresh outside burst, floored at 10 s.
5. Batched Room writes with WAL, never a write per sample.

An honest note on the headroom: 0.6 %/day is not a triumph of engineering. It is a consequence of
the platform handing us so little data that there is very little to spend power on. The binding
constraint on this project is **data availability, not battery**.

**Foreground service.** Required for background `getAllCellInfo` on Android 10+. Types
`location|dataSync`, with `ACCESS_BACKGROUND_LOCATION`. The notification should show live state
rather than being a dead placeholder — it is the one persistent surface the app owns.

---

## 8. Per-device calibration, stored not assumed

On first run, and cached in a `device_profile` row:

- Which Layer-1 metrics return `UNAVAILABLE` on this handset. The UI degrades honestly instead
  of rendering zeros.
- The carrier's bar thresholds from `CarrierConfigManager` — **with a caveat**: since Android 13
  non-privileged callers receive only an allowlisted subset of keys. Treat any threshold read this
  way as *inferred* until a first-run probe confirms the keys actually arrive; they were read here
  over adb, which is a shell-privileged path and not what the app will have.
- **Both bar numbers.** AOSP `getLevel()` and the OEM's own value are different numbers from the
  same object — measured 4 and 3 simultaneously at −93 dBm on this device. Record both; the one
  the user sees in their status bar is the OEM's.
- A `getRssnr()` unit sanity check — vendor RIL implementations have historically disagreed on
  dB versus 0.1 dB, and an uncalibrated SNR silently corrupts every quality judgement downstream.
