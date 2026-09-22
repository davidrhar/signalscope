# SignalScope

An Android app for diagnosing *why* mobile data drops — not just showing that it did.

> **⚠️ Research build — not ready for general use.**
>
> SignalScope is a single-developer research instrument, not a finished app. Everything it collects
> stays on your phone: **nothing is uploaded anywhere.** Before installing, please be aware:
>
> - **What it records is movement data, and calling it anything softer would be wrong.** The app
>   never stores a latitude or longitude — every position is rounded to a hexagonal cell before it
>   is written. But it keeps a *timestamped history* of those cells, and the finest of them is about
>   65 m across, which over a residential street is enough to identify a home. Separately, and
>   **whether or not you ever grant location permission**, it records which mobile mast served you
>   at each moment — which is a movement history in its own right. Binning reduces the resolution.
>   It does not make the data anonymous. Treat the database as sensitive personal information.
> - **Collection may stop in the background.** The app does not yet ask to be exempted from battery
>   optimisation, so many phones will put it to sleep and the data will have gaps.
> - **It runs small tests automatically.** It can hold the mobile radio awake, and runs connection
>   and speed tests that use some battery and up to about 1 MB of mobile data a day. These are not
>   yet opt-in.
> - **Debug builds are readable over USB.** Anyone with USB-debugging access to your unlocked phone
>   can read everything collected. Release builds close that particular door; the data is still on
>   your phone either way.
>
> **Updating.** Builds are now signed with a permanent key, so an update keeps your data. The one
> exception is the move *from* an older debug build — those were each signed with a throwaway key,
> so the first release build has to be installed fresh, and whatever those builds collected is lost
> at that point. That happens once. See `docs/release.md`.
>
> A build intended for a wider audience — a battery-exemption prompt, opt-in tests, and a consent
> flow for anything that ever leaves the device — has not been made yet.

Status: **Phase 1 running on hardware.** A debug APK builds, installs and collects live on the
reference device (a recent Android 16 flagship, DSDS dual-SIM). The docs below are the design;
`app/` is the implementation.

Build: `JAVA_HOME=<jdk21> ./gradlew :app:assembleDebug` — needs JDK 21 (not 25/26), compileSdk 36.

---

## The problem being solved

"Mobile data goes on and off" is a symptom with about a dozen distinct root causes, and they
need completely different fixes. Existing tools (speed tests, signal-bar widgets, Network Cell
Info) show you *state*. They don't discriminate *cause*. That discrimination is the whole product.

### The twelve causes

| # | Cause | What it looks like | Fix class |
|---|-------|--------------------|-----------|
| 1 | Radio coverage loss | RSRP < -115 dBm, SINR < 0, `ServiceState` → OUT_OF_SERVICE | Location / external antenna |
| 2 | **5G NSA anchor thrash** | Full bars, no data. `TelephonyDisplayInfo` flapping NR_NSA ↔ LTE | Pin to LTE |
| 3 | Cell reselection ping-pong | PCI/CI churn while physically stationary | Pin to LTE / band |
| 4 | PS attach / PDN failure | `NetworkRegistrationInfo.getRejectCause() != 0`, CS registered but PS not | APN / SIM / carrier |
| 5 | Data SUSPENDED | `getDataState() == DATA_SUSPENDED` — voice call on legacy RAT, or inter-RAT handover | VoLTE settings |
| 6 | Connected-but-broken path | Network stays up, `NET_CAPABILITY_VALIDATED` lost. CGNAT rebind, DNS failure, IPv6/464XLAT breakage | DNS / IPv6 settings |
| 7 | **Wi-Fi ↔ cellular thrash** | Phone clinging to a dead AP; default route flapping between transports | Wi-Fi handover settings |
| 8 | Device-side | Doze / app-standby / battery optimiser killing sockets; a VPN app interfering; modem thermal throttle | Per-app exemptions |
| 9 | Carrier-side | Good RSRP but low CQI (congested cell); post-cap throttling | Time-of-day avoidance |
| 10 | **DSDS tune-away** | Data stalls with **no cell change and no signal change** on the data SIM, correlated with the other SIM's activity | Disable/relocate the second SIM |
| 11 | **Data-subscription switch** | `activeDataSubId` changes → new bearer → **new IP → every TCP socket dies** | Fix the data SIM; disable auto-switch |
| 12 | **Roaming / VoWiFi handover** | Roaming sub re-selects networks repeatedly; VoWiFi↔VoLTE transitions drop calls at the Wi-Fi edge | Roaming and Wi-Fi-calling settings |

Causes **2, 6 and 7** present as "full bars, nothing loads" and are the classically misdiagnosed
set. Causes **10–12** exist only on multi-SIM devices — see [`docs/multi-sim.md`](docs/multi-sim.md).

**Which causes matter is device-specific, and must be established per handset rather than
assumed.** On the reference device (a recent flagship, DSDS, Android 16) measurement showed:

- Cause **2 is inapplicable** — no NR leg is active, so there is no anchor to thrash. The
  original mockup made it the hero and was wrong to.
- Cause **1 is far more prevalent than expected**: the data SIM recorded 18+ `No service` /
  `Emergency calls only` events in ~2 days, with outages up to 86 s. Outright service loss, not
  full-bars-no-data.
- Causes **10 and 12** are live candidates: the second SIM roams permanently and logged 38
  service transitions, and VoWiFi is active via the OEM ePDG.

The diagnostic engine must therefore rank causes by *observed* prevalence on the device, not by
a fixed table order.

### The discriminator

Sample four layers on one clock and correlate:

```
RADIO      does the modem have service?      ServiceState, CellInfo, TelephonyDisplayInfo
NETWORK    is there a validated default route? NetworkCapabilities, LinkProperties
TRANSPORT  do probes actually complete?       DNS / TCP / TLS / HTTP timings
CONTEXT    where, when, moving or still?      coarse location, motion, screen state, thermal
```

Each layer-combination maps to a distinct cause. Radio fine + network validated + probes failing
is cause 9. Radio fine + network *not* validated is cause 6. Radio flapping RAT with network
never dropping is cause 2. That table is the diagnostic engine.

---

## What Android actually permits

Three privilege tiers. Be honest about which is which.

### Tier 0 — normal app, no special setup

Read-only, and enough for full diagnosis:

- **`TelephonyCallback`** (API 31+; `PhoneStateListener` below that) — `ServiceStateListener`,
  `SignalStrengthsListener`, `DisplayInfoListener`, `DataConnectionStateListener`,
  `CellInfoListener`.
- **`getAllCellInfo()` / `requestCellInfoUpdate()`** — serving *and* neighbour cells: PCI, TAC,
  CI, EARFCN/NRARFCN, MCC/MNC, plus RSRP/RSRQ/RSSNR/CQI (LTE) and SS-RSRP/SS-RSRQ/SS-SINR (NR).
- **`TelephonyDisplayInfo`** (API 30+) — the only way to distinguish LTE / LTE_CA /
  LTE_ADVANCED_PRO / NR_NSA / NR_ADVANCED. Cause #2 is invisible without it.
- **`NetworkRegistrationInfo`** — per-domain (CS vs PS) registration state and reject cause.
- **`ConnectivityManager.NetworkCallback`** — `onCapabilitiesChanged` gives
  `NET_CAPABILITY_VALIDATED` (Android's own reachability verdict) and `NOT_SUSPENDED`;
  `onLinkPropertiesChanged` gives addresses, DNS servers, MTU, routes, and whether 464XLAT is up.
- **Active probes** — `DnsResolver` (API 29+, async with real error codes), TCP connect timing,
  TLS handshake timing, HTTP/204 generate-204 checks, QUIC/UDP 443 reachability.
- **`NetworkStatsManager`** — per-app usage, to catch a background hog (needs
  `PACKAGE_USAGE_STATS`, user-granted via Settings).
- **`SubscriptionManager`** — the active subscription list, and `defaultData` vs **`activeData`**
  (they diverge during temporary switches). Every telephony read must go through
  `TelephonyManager.createForSubscriptionId()`; a bare instance silently serves the default
  subscription, which is wrong on any multi-SIM device. See [`docs/multi-sim.md`](docs/multi-sim.md).

**Permissions:** `ACCESS_FINE_LOCATION` (mandatory for any cell identity),
`ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `READ_BASIC_PHONE_STATE`, plus a foreground
service with `location|dataSync` types. Android 10+ blocks `getAllCellInfo` from the background
without exactly this.

### Tier 1 — VpnService (still no root)

A **loopback-only** `VpnService` — the packets never leave the device, it's a local tap, not a
tunnel. This is how PCAPdroid and NetGuard work. It buys:

- Per-app attribution of every flow, via `ConnectivityManager.getConnectionOwnerUid()` (API 29+).
- DNS query/response logging with failure codes.
- Observed TCP retransmits, RTT, and connection-failure attribution.
- SNI visibility (plaintext unless ECH) — enough to name the destination.

**No TLS interception, no CA install.** Timing, loss, DNS and SNI are sufficient for
diagnostics, and MITM is not worth the security cost on a daily driver.

### Tier 2 — Shizuku (ADB-level, still no root)

[Shizuku](https://shizuku.rikka.app/) grants an app shell-UID privileges via wireless-debugging
pairing. The user starts it manually; it survives until reboot. Unlocks *actions*:

- `svc data disable && svc data enable` — a clean, scriptable data re-anchor. This is the single
  most useful remediation, and the one people currently do by hand with airplane mode.
- Hidden-API `setPreferredNetworkTypeBitmask` — programmatically pin to LTE to escape cause #2.
- `cmd netpolicy`, `settings put global` — Private DNS, Wi-Fi handover aggressiveness.

### What is **not** possible without root or carrier signing

State this plainly in the app's own UI, or it'll get treated as a bug report:

- **No ICMP.** `InetAddress.isReachable()` silently falls back to TCP/7 and lies. No real ping.
- **No traceroute.** Receiving ICMP TTL-exceeded needs a raw socket → root.
- **No modem diagnostics.** `/dev/diag`, QXDM, AT commands, RIL logs — all root or vendor-only.
  `*#*#4636#*#*` is heavily reduced on modern Pixels.
- **No band locking, no cell locking, no PLMN priority.** Radio-layer control is carrier and
  modem territory. Carrier privileges need a UICC-signed app; not attainable here.
- **No forcing a handover.** The best available lever is the Tier-2 data re-anchor.

---

## Improving and maintaining quality

The realistic remediation set, given the above:

1. **Detect and recommend.** The diagnostic table already names the fix. Deep-link into the right
   Settings screen (`ACTION_NETWORK_OPERATOR_SETTINGS`, Private DNS, battery optimisation).
2. **Auto re-anchor** (Tier 2). On confirmed cause 2/4/6, cycle `svc data`. Rate-limited, logged,
   never silent.
3. **Learn the environment.** A local history of (cell, location, hour) → outcome turns into
   "this happens at home on PCI 411 between 18:00 and 21:00" — which is a carrier-actionable
   complaint with evidence attached.
4. **Export.** CSV/JSON, plus a plain-English summary suitable for pasting into a carrier support
   ticket. Evidence is the point.

There is also an **Accessibility Service** fallback for the toggles — you already have a working
accessibility automation engine in `taxiwrapper/FareWrapperApp` (`AutomationEngine.kt`, the
`Step` vocabulary). That's directly reusable for driving quick-settings tiles on devices where
Shizuku isn't set up. Shizuku is cleaner where available.

---

## Architecture sketch

```
app/
  collect/     TelephonyCollector, ConnectivityCollector, LocationCollector   (passive, callbacks)
  probe/       DnsProbe, TcpProbe, TlsProbe, HttpProbe                        (active, WorkManager)
  diagnose/    RuleEngine — the nine-cause table as evaluable predicates
  act/         ShizukuBridge, AccessibilityBridge, SettingsDeepLinks
  store/       Room time-series + rollups; retention policy
  ui/          Compose: live dashboard, event timeline, incident detail, export
  vpn/         (phase 3, optional) loopback VpnService flow tap
```

**Stack:** Kotlin, Compose, Room, WorkManager, a foreground service for continuous collection.
Matches the SmartRadio setup, so the toolchain is already proven on this machine.

**Battery is the main engineering constraint.** Callback-driven wherever possible;
`requestCellInfoUpdate` is expensive and must be rate-limited; active probes back off hard when
the screen is off and the network looks healthy. An always-on diagnostic that costs 15%/day
gets uninstalled.

### Phasing

- **Phase 1** — Tier 0 collection + Room + live dashboard. Proves the data is there.
- **Phase 2** — rule engine + incident timeline + export. This is where it becomes *useful*.
- **Phase 3** — Tier 2 actions behind an explicit opt-in.
- **Phase 4** — optional VpnService per-app attribution.

---

## Open questions

- Which phone, which Android version, which carrier? Tier-0 API availability and the NSA-thrash
  story both depend on it.
- Single SIM or dual/eSIM? Multi-subscription doubles the collection surface.
- Is the problem reproducible at a known place/time, or fully intermittent? Changes whether
  Phase 1 alone is enough.
- Tower geolocation: OpenCelliD can map CI → approximate coordinates offline. (Mozilla Location
  Services was retired, so it's not an option.) Worth it, or is PCI-relative enough?
