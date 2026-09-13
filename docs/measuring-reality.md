# Measuring reality, not bars

**Scope: Android only. Tier 0 / Tier 2 as defined in the [README](../README.md). No root.**

The premise of SignalScope is that the signal bars are not a bad *rendering* of network
quality — they are not a quality metric at all, and they are not even a fixed one. This doc
establishes why, then specifies what to measure instead.

---

## Part 1 — Why the bars are not informative, and not reliably correct

### 1. The bars are a carrier-configurable marketing knob

Android derives `SignalStrength.getLevel()` (0–4) from thresholds supplied by **CarrierConfig**,
not from anything fixed in the platform:

| Key | What it controls |
|---|---|
| `KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY` | The four RSRP cut-points for LTE bars |
| `KEY_LTE_RSRQ_THRESHOLDS_INT_ARRAY` | RSRQ cut-points |
| `KEY_LTE_RSSNR_THRESHOLDS_INT_ARRAY` | SNR cut-points |
| `KEY_PARAMETERS_USED_FOR_LTE_SIGNAL_BAR_INT` | Bitmask: *which* of the above even feed the bar |
| `KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY` etc. | The NR equivalents |

Two consequences, both fatal to using bars as evidence:

- **The same radio conditions produce different bar counts on different carriers**, and on the
  same carrier after a config push. There is no stable mapping from bars to dBm.
- By default on many configurations the bar is driven by **RSRP alone** — which is a power
  measurement. See below for why that is the wrong input.

The bar is a number the carrier gets to choose. It is closer to a brand asset than a sensor
reading.

### 2. RSRP is power, not quality

RSRP measures received power of the reference signal. It answers "can I hear the tower",
not "can the tower and I exchange data".

- It is **blind to interference**. A cell-edge position with three neighbours at similar power
  has strong RSRP and unusable SINR.
- It is **blind to load**. A tower 200 m away at 19:00 with every commuter attached gives the
  same RSRP as at 04:00 and a fraction of the throughput. RSRQ degrades with load; RSRP does not.
- It is **downlink only**. Your phone transmits at ~23 dBm against a base station transmitting
  vastly higher. The uplink budget fails first, which is why "full bars but nothing sends" is a
  real and common state that the bar cannot represent.

### 3. The bar is a radio-layer widget with no knowledge of data

This is the decisive one. The bar is computed from the serving cell's signal. It does not know:

- Whether the **PS (packet-switched) domain** is registered. You can be CS-registered — calls
  work fine — while PS registration is rejected. Full bars, zero data.
- Whether data is **SUSPENDED** (`getDataState() == DATA_SUSPENDED`).
- Whether there is a **validated default route**. Android runs its own reachability probe and
  exposes the verdict as `NET_CAPABILITY_VALIDATED`. The bar ignores it entirely.
- Whether DNS resolves, whether the path MTU is sane, whether 464XLAT came up on an IPv6-only
  bearer.

Every one of those failures presents as full bars.

### 4. On 5G NSA the bar is ambiguous by construction

Under non-standalone, the phone holds an LTE anchor and an NR leg simultaneously. What the bar
shows — anchor only, NR only, or a vendor blend — is device-dependent and undocumented. Meanwhile
`TelephonyDisplayInfo` may be showing `NR_NSA` purely because the network *advertised* NR
availability, not because an NR leg is carrying traffic. The icon and the bar can both be
confidently wrong at the same time.

### 4a. How often the bars disagree, measured

Across 200 consecutive samples on the reference device, the AOSP `getLevel()` and the OEM's own
bar differed in **194 of them — 97 %**. The disagreement is not an edge case to note in passing;
on this handset it is the normal state, and an app reporting `getLevel()` is reporting a number
the user has essentially never seen.

### 4b. The OEM adds a fourth layer, measured

Confirmed on the target device: a single `SignalStrength` object reported **AOSP `level=4` and
Samsung's own `SignalBarInfo{lteLevel=3}` at the same instant**, at −93 dBm.

So there are not three sources of unreliability but four: carrier-configurable thresholds, a
power-only input, NSA ambiguity, and **an OEM bar that disagrees with the platform's own**. An app
calling `getLevel()` does not get the number in the user's status bar. Record both, and when
showing the user "your phone says N bars", use the vendor value — that is the one they can see.

### 5. The display is smoothed and throttled

Bar updates are debounced to avoid visible flicker. Sub-second nulls, the brief outages during
handover, and the exact moment of a reselection are smoothed away — which are precisely the
events that matter for intermittent data.

---

## Part 2 — What to measure instead

Four layers. **Only Layer 4 is ground truth. Layers 1–3 exist to explain Layer 4.**
That inversion — outcome first, radio as explanation — is the whole design.

### Layer 1 — Radio

Source: `CellInfoLte` / `CellInfoNr` via `getAllCellInfo()` and `TelephonyCallback.CellInfoListener`.

| Metric | API | Reads as | Why it matters |
|---|---|---|---|
| RSRP / SS-RSRP | `getRsrp()` / `getSsRsrp()` | dBm | Coverage. > −85 excellent · −95 good · −105 fair · −115 poor · below that, edge |
| RSRQ / SS-RSRQ | `getRsrq()` / `getSsRsrq()` | dB | **Load and interference proxy.** > −10 good · −15 fair · −20 poor. Degrades under cell load at constant RSRP |
| RSSNR / SS-SINR | `getRssnr()` / `getSsSinr()` | dB | **Best single throughput predictor.** > 20 excellent · 13–20 good · 0–13 fair · < 0 unusable |
| CQI | `getCqi()` | 0–15 | What the scheduler is actually granting. Frequently `UNAVAILABLE` |
| Timing advance | `getTimingAdvance()` | TA units | Distance to tower, ≈ 78 m per unit on LTE. Often `UNAVAILABLE` |
| Neighbour set | `getAllCellInfo()` | list | Count of neighbours within ~6 dB of serving = reselection and interference risk |

Two implementation notes. **Always check for `CellInfo.UNAVAILABLE`** — availability varies by
chipset and several of these are absent on a lot of hardware. And **sanity-check `getRssnr()`
units on the actual device**: vendor RIL implementations have historically disagreed about
dB versus 0.1 dB, so calibrate against a known-good reading before trusting it.

### Layer 2 — Registration

Source: `TelephonyCallback.ServiceStateListener`, `DisplayInfoListener`, `DataConnectionStateListener`.

- `NetworkRegistrationInfo` **split by domain** — PS and CS separately. Non-negotiable; the
  CS-up/PS-down split is invisible any other way.
- `getRejectCause()` — a non-zero value names the failure outright.
- `getNrState()` — `NONE` / `RESTRICTED` / `NOT_RESTRICTED` / `CONNECTED`. `CONNECTED` is the
  only one meaning an NR leg is actually established.
- `TelephonyDisplayInfo.getOverrideNetworkType()` — LTE / LTE_CA / LTE_ADVANCED_PRO / NR_NSA /
  NR_ADVANCED. Log every transition; the *rate* of transition is the NSA-thrash signal.
- `getDataState()` — watch for `DATA_SUSPENDED`.

### Layer 3 — IP

Source: `ConnectivityManager.NetworkCallback`.

- `NET_CAPABILITY_VALIDATED` — Android's own verdict on whether the path works. Losing this
  while radio is healthy is the signature of cause #6.

  **It is a lagging indicator.** The flag is the result of Android's own periodic connectivity
  probe, so it trails reality by seconds and its transitions are not second-accurate. Good
  enough to bound an incident and to classify a bin; **not** good enough to time a sub-second
  gap or to treat a per-bin validated fraction as precise. Where timing precision matters, our
  own Layer-4 probes are the finer instrument.
- `NET_CAPABILITY_NOT_SUSPENDED`.
- `LinkProperties` — v4/v6 addresses, DNS servers, MTU, routes, whether a `v4-` CLAT interface
  is present on an IPv6-only bearer.
- Which transport currently holds the default route — the Wi-Fi/cellular thrash detector.

Treat `getLinkDownstreamBandwidthKbps()` as **an estimate the platform supplies, not a
measurement**. Label it as such in the UI or it becomes a support burden.

### Layer 4 — Transport (ground truth)

Active probes, rate-limited and backed off hard when the screen is off and Layer 3 looks healthy.

| Probe | Mechanism | Yields |
|---|---|---|
| DNS | `DnsResolver` (API 29+, async, real error codes) | Resolve time, failure class |
| TCP | Connect to a stable anchor host | Handshake RTT |
| TLS | Handshake to same | Crypto + round-trip cost |
| HTTP | `generate_204`-style endpoint | Success, TTFB |
| UDP/QUIC | 443 reachability | Whether QUIC is blocked or degraded |

**Report distributions, never means.** A p50 of 40 ms with a p99 of 4 s is the actual user
experience of "on and off", and an average erases it completely.

And state the limits in the UI: **no ICMP ping, no traceroute.** `InetAddress.isReachable()`
silently falls back to a TCP connect and misreports — it is not used anywhere in this project.

---

## Part 3 — The derived metrics that bars cannot express

These are the outputs worth putting on the dashboard. Each is something a bar structurally
cannot say.

| Metric | Definition | Reads as |
|---|---|---|
| **Validated uptime** | % of wall-clock with a validated default route | The honest headline "how good is my data" |
| **Churn rate** | PCI/CI changes per 10 min **while stationary** | Reselection ping-pong (cause #3) |
| **NSA flap count** | NR_NSA ↔ LTE transitions per hour | Cause #2, the one nothing else catches |
| **Time-to-recover** | Median seconds from loss to re-validated | Whether drops are survivable or fatal to a call |
| **Quality-at-power** | SINR conditioned on RSRP band | Isolates congestion from coverage |
| **Uplink asymmetry** | Probe upload vs download success divergence | Uplink-limited state |
| **Domain split** | % of time CS-registered but PS-not | Cause #4, definitively |

### On scoring

The temptation is a single 0–100 "network health" number. **Resist it** — it reproduces exactly
the failure of the bars: one scalar that hides which of nine things is wrong.

Publish a short vector instead — *usable · stable · fast* — where each maps to a named cause
class, and the headline figure is validated uptime, because that one is a measurement rather
than an index.

---

## Part 4 — Calibration

Before any of this is trustworthy on a given handset:

1. **Enumerate availability.** Log which Layer-1 metrics return `UNAVAILABLE` on the device.
   The feature set is hardware-dependent; the UI must degrade honestly rather than show zeros.
2. **Read the carrier's own thresholds.** `CarrierConfigManager` exposes the bar cut-points.
   Displaying "your carrier calls −105 dBm three bars" next to the measurement is, on its own,
   a persuasive demonstration of the whole premise.
3. **Establish a known-good baseline.** A few minutes stationary on strong coverage gives the
   reference distribution everything else is judged against.
4. **Verify SNR units** as noted above.
