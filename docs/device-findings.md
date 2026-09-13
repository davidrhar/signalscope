# Device findings — what the radio actually exposes, and what it costs to watch

**Source: a live recent Android flagship, Android 16 / SDK 36, DSDS,
read-only `adb` session, 2026-09-12.** Device configuration is described in
[`multi-sim.md`](multi-sim.md); this document does not repeat it.

Two things are established here. **Part A** inventories the telephony/network surface
visible on a real handset and classifies every field by *how an app would actually reach
it* — which is not the same question as "is it in the dumpsys output". **Part B** measures
how fast that surface actually changes, and what it costs to watch, and rewrites the
sampling model in [`data-model.md`](data-model.md) around the measurements.

**Measured vs inferred.** Every number tagged **[M]** was measured on this device in this
session and the method is stated. Every number tagged **[E]** is an estimate or an
inference from platform behaviour, and is labelled as such. Nothing here silently
promotes an estimate to a measurement.

**Caveat on conditions.** All live sampling ran over wireless `adb`, so Wi-Fi was up and
the device was not in deep Doze (`dumpsys deviceidle`: `mState=ACTIVE mLightState=ACTIVE`
**[M]**). Screen was on throughout. Screen-off figures therefore come from the
`dumpsys batterystats` history ring — a recording of the preceding 8.4 h of ordinary use,
made by the platform, not by me — rather than from live polling.

---

## Part A — What is exposed, and at which tier

### A.1 The tier question is decided by permission protection level

Tier classification is not a matter of opinion; it follows from the protection level of
the permission each API requires. Read off this device **[M]**
(`dumpsys package permissions`):

| Permission | `prot=` | Grantable to a normal app? |
|---|---|---|
| `ACCESS_NETWORK_STATE` | `normal\|instant` | Yes, implicitly |
| `READ_BASIC_PHONE_STATE` | `normal` | Yes, implicitly |
| `ACCESS_WIFI_STATE` | `normal` | Yes, implicitly |
| `FOREGROUND_SERVICE_LOCATION` / `_DATA_SYNC` | `normal\|instant` | Yes, implicitly |
| `READ_PHONE_STATE` | `dangerous` | Yes, runtime prompt |
| `ACCESS_FINE_LOCATION` / `_BACKGROUND_LOCATION` | `dangerous\|instant` | Yes, runtime prompt |
| `PACKAGE_USAGE_STATS` | `signature\|privileged\|development\|appop\|retailDemo` | Yes, via Settings (appop) |
| **`READ_PRECISE_PHONE_STATE`** | **`signature\|privileged`** | **No** |
| `READ_PRIVILEGED_PHONE_STATE` | `signature\|privileged\|role` | No |
| `MODIFY_PHONE_STATE` | `signature\|privileged\|role` | No |
| `CONNECTIVITY_USE_RESTRICTED_NETWORKS` | `signature\|privileged` | No |
| `NETWORK_SETTINGS` | `signature` | No |
| `DEVICE_POWER` | `signature\|role` | No |

`READ_PRECISE_PHONE_STATE` is the dividing line for most of the interesting telephony.
It is `signature|privileged` with **no `development` flag**, so `pm grant` cannot grant it
to our app even from a shell.

### A.2 Shizuku's real value is larger than the README assumes

The shell UID (`com.android.shell`) **holds** the following **[M]**
(`dumpsys package com.android.shell`):

```
READ_PRECISE_PHONE_STATE      HELD
READ_PRIVILEGED_PHONE_STATE   HELD
MODIFY_PHONE_STATE            HELD
NETWORK_SETTINGS              HELD
CONNECTIVITY_USE_RESTRICTED_NETWORKS  HELD
PACKAGE_USAGE_STATS           HELD
SATELLITE_COMMUNICATION       HELD
DEVICE_POWER                  HELD
READ_NETWORK_USAGE_HISTORY    not held
NETWORK_STACK                 not held
```

**Consequence.** The README frames Tier 2 / Shizuku as unlocking *actions* (`svc data`,
network-type pinning). That undersells it. Because shell holds `READ_PRECISE_PHONE_STATE`,
Shizuku also unlocks an entire **measurement** class that is otherwise invisible:
`BarringInfo`, `PreciseDataConnectionState` (and with it the IMS PDN's P-CSCF addresses,
per-APN TCP buffer sizes, QoS and fail cause), `PhysicalChannelConfig` (the real carrier
aggregation view), `CallQuality` (per-call RTP loss, jitter and RTT), SRVCC state, and the
IMS registration callbacks. That is a different and stronger argument for the dependency
than "it can cycle mobile data".

### A.3 Field inventory

`Tier 0` = public SDK API, permission named. `Tier 2` = shell UID via Shizuku, or a
hidden/`@SystemApi` call. `—` = visible in dumpsys with no app-accessible path.

Rows marked **NEW** are absent from the current `data-model.md`.

#### Layer 1 — radio

| Field | Where seen | Tier | API / permission | Note |
|---|---|---|---|---|
| `rsrp` `rsrq` `rssnr` `rssi` | `mSignalStrength` | **0** | `TelephonyCallback.SignalStrengthsListener`, `READ_PHONE_STATE` | Already specced |
| `cqi`, `cqiTableIndex`, `ta` | `CellSignalStrengthLte` | **0** | same | **`UNAVAILABLE` on this device** — all three read `2147483647` on both SIMs on the serving cell **[M]** |
| `timingAdvance` | `CellInfoLte` | **0** | same | Present on SIM2 (`ta=5`), `UNAVAILABLE` on SIM1 **[M]**. Availability is per-subscription, not per-device |
| `level`, `parametersUseForLevel` | `mSignalStrength` | **0** | `SignalStrength.getLevel()` | See A.4 — this is *not* the bar the user sees |
| Samsung `SignalBarInfo{lteLevel}` | `mSignalStrength` | **—** | vendor field, no API | The value actually rendered in the status bar |
| Neighbour cell list | `mCellInfo` | **0** | `getAllCellInfo()` / `CellInfoListener`, `ACCESS_FINE_LOCATION` | Specced — but see B.2, it is **never pushed** |
| `mCellBandwidths` | `ServiceState` | **0** | `ServiceState.getCellBandwidths()` | **NEW** — `[20000, 20000]` kHz, a cheap aggregate-bandwidth / CA proxy **[M]** |
| `mChannelNumber`, `duplexMode()` | `ServiceState` | **0** | `getChannelNumber()`, `getDuplexMode()` | **NEW** — serving EARFCN and FDD/TDD without touching `CellInfo` |
| `mArfcnRsrpBoost` | `ServiceState` | **—** | hidden | Carrier RSRP offset. `0` here **[M]**, so no correction needed on this device |
| `PhysicalChannelConfig` (per-CC bandwidth, band, `PrimaryServing`/`SecondaryServing`, PCI, `mContextIds`) | `mPhysicalChannelConfigs` | **2** | `TelephonyCallback.PhysicalChannelConfigListener`, `READ_PRECISE_PHONE_STATE` | **NEW** — the only *true* CA / NSA-leg view. `TelephonyDisplayInfo` merely reports what the network advertised |
| Per-sensor temperature incl. **PA** (modem power amplifier) | `dumpsys thermalservice` | **—** | `HardwarePropertiesManager` needs `DEVICE_POWER` | `PA=39.7 °C` vs `AP=40.9`, `SKIN=37.2` **[M]**. Modem-specific thermal is not reachable |
| Aggregate thermal status + headroom | `dumpsys thermalservice` | **0** | `PowerManager.getCurrentThermalStatus()`, `addThermalStatusListener`, `getThermalHeadroom()` | Specced. Status `0` (NONE) **[M]** |

#### Layer 2 — registration

| Field | Where seen | Tier | API / permission | Note |
|---|---|---|---|---|
| `NetworkRegistrationInfo` per `domain` (PS/CS) | `mNetworkRegistrationInfos` | **0** | `ServiceState.getNetworkRegistrationInfo()`, `ACCESS_FINE_LOCATION` | Specced |
| `rejectCause` | same | **0** | `NetworkRegistrationInfo.getRejectCause()` | Specced. **Verify at build time** — confirm it is public on the compile SDK before designing cause #4 around it |
| **`transportType=WLAN` + `accessNetworkTechnology=IWLAN` NRI** | same | **0** | same | **NEW — this is the Tier-0 VoWiFi/ePDG registration indicator.** On this device SIM2 shows `domain=PS transportType=WLAN registrationState=HOME availableServices=[DATA]` while SIM1's WLAN NRI is `UNKNOWN` **[M]**. Per-subscription VoWiFi state with no privileged call |
| `availableServices=[VOICE,SMS,VIDEO]` / `[DATA,MMS]` | same | **0** | `getAvailableServices()` | **NEW** — a direct, finer statement of the CS-up/PS-down split than `regState` alone |
| `isNonTerrestrialNetwork` | same | **0** | `NetworkRegistrationInfo.isNonTerrestrialNetwork()` | **NEW**, Android 15+. `TERRESTRIAL` on both SIMs **[M]** |
| `isUsingCarrierAggregation` | same + `ServiceState` | **0** | `NetworkRegistrationInfo.isUsingCarrierAggregation()` | **NEW** — **verify API level on compile SDK.** Observed flapping true↔false three times in 2 s **[M]** |
| `LteVopsSupportInfo` (`mVopsSupport`, `mEmcBearerSupport`) | `DataSpecificRegistrationInfo` | **2** | `DataSpecificRegistrationInfo.getVopsSupportInfo()`, `@SystemApi` | **NEW** — whether the cell supports VoLTE at all. Directly relevant to cause #5 |
| `isDcNrRestricted`, `isNrAvailable`, `isEnDcAvailable` | `DataSpecificRegistrationInfo` | **2** | `@SystemApi` | **NEW** — the honest NSA-availability triple, as opposed to the display override |
| `nrState` | `ServiceState` | **0** | `NetworkRegistrationInfo.getNrState()` | Specced |
| `TelephonyDisplayInfo` network / overrideNetwork | `mTelephonyDisplayInfo` | **0** | `DisplayInfoListener` | Specced |
| `TelephonyDisplayInfo.isRoaming()`, **`isNtn()`, `isSatelliteConstrainedData()`** | same | **0** | API 35 / **API 36** | **NEW**, Android 16. Both `false` here **[M]**. Treat `isNtn`/`isSatelliteConstrainedData` as flagged API — feature-detect, do not hard-depend |
| `mDataActivity` | registry | **0** | `TelephonyManager.getDataActivity()`, **no permission** | **NEW and important.** `4` = `DATA_ACTIVITY_DORMANT` = RRC idle. The cheapest available proxy for "is the radio actually connected right now", and the gate for probe scheduling (see B.5) |
| `mIsDataEnabled`, `mUserMobileDataState` | registry | **0** | `TelephonyManager.isDataEnabled()` | **NEW** — rules out the most embarrassing false positive |
| `mAllowedNetworkTypeValue` / `Reason` | registry | **2** | `getAllowedNetworkTypesForReason()`, `READ_PRIVILEGED_PHONE_STATE` | Readable from shell: `GPRS\|EDGE\|UMTS\|HSDPA\|HSUPA\|HSPA\|LTE\|HSPA+\|GSM\|LTE_CA\|NR` **[M]** |
| **`BarringInfo`** | `mBarringInfo` | **2** | `TelephonyCallback.BarringInfoListener` / `getBarringInfo()`, `READ_PRECISE_PHONE_STATE` | **NEW.** Per service type (0–9): `mBarringType`, `mIsConditionallyBarred`, `mConditionalBarringFactor`, `mConditionalBarringTimeSeconds`. All `NONE` here **[M]**. Assessed in A.5 |
| `mSrvccState` | registry | **2** | `SrvccStateListener`, `READ_PRECISE_PHONE_STATE` | **NEW** — VoLTE→2G/3G call handover, a named call-drop mechanism |
| `mCarrierRoamingNtnMode` / `Eligible` / `NtnSignalStrength` / `AvailableServices` | registry | **2** | `SATELLITE_COMMUNICATION` (shell holds it) | **NEW**, Android 16. Inert on this carrier — `satellite_attach_supported_bool=false`, `satellite_esos_supported_bool=false` **[M]** |
| `activeDataSubId` vs `defaultDataSubId` | `dumpsys isub` | **0** | `SubscriptionManager.getActiveDataSubscriptionId()`, no permission | **NEW** — the DSDS data-switch detector. Both `6` at dump time **[M]**. See `multi-sim.md` §3b |
| `mSimState[n]` | `dumpsys isub` | **0** | `TelephonyManager.getSimState(slot)` | **NEW** |
| `carrierId`, `carrierName`, `displayNameSource` | `dumpsys isub` | **0** | `SubscriptionInfo.getCarrierId()` | **NEW** — a stable carrier key that survives SPN cosmetics |

#### Layer 3 — IP

| Field | Where seen | Tier | API / permission | Note |
|---|---|---|---|---|
| `VALIDATED`, `NOT_SUSPENDED`, `NOT_CONGESTED`, `NOT_METERED`, `NOT_ROAMING` | `nc{...}` | **0** | `NetworkCallback.onCapabilitiesChanged`, `ACCESS_NETWORK_STATE` | Specced |
| **`NOT_BANDWIDTH_CONSTRAINED`** | `nc{...}` | **0** | `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED`, **API 36** | **NEW**, Android 16. Present on both networks **[M]**. Its *loss* is a first-class "network is up but deliberately throttled" signal — feature-detect on older SDKs |
| `LinkProperties`: addresses, DNS, MTU, routes, CLAT | `lp{...}` | **0** | `onLinkPropertiesChanged` | Specced |
| **`TcpBufferSizes`** | `lp{...}` | **0**\* / **2** | getter `LinkProperties.getTcpBufferSizes()` is `@hide` | \*Recoverable by parsing `LinkProperties.toString()`, which is public and does include the field **[M]**. Fragile across versions; treat as best-effort. Differs per bearer on this device: cellular internet PDN `…,16777216,512000,…` vs Wi-Fi `…,16777216,2097152,…` **[M]** |
| **`PcscfAddresses`** (IMS PDN) | `lp{...}` on the IMS network | **2** | `LinkProperties.getPcscfServers()` is `@SystemApi`; and the IMS `Network` itself is restricted | Assessed in A.5 |
| `firstValidated` / `lastValidated` / `created` | `NetworkAgentInfo` | **—** | ConnectivityService internal | Not reachable — but exactly reconstructable from our own callback stream, which is the point of the monotonic clock in `data-model.md` §2 |
| Per-UID **`blockedReasons`** bitmask | `dumpsys connectivity` | **2** | `@SystemApi`; the boolean form `onBlockedStatusChanged(Network, boolean)` is **Tier 0** | **NEW.** Tier 0 tells you *that* your sockets are blocked; only Tier 2 tells you *why* (Doze / app-standby / battery-saver / background). Cause #8 is detectable at Tier 0 and only *diagnosable* at Tier 2 |
| `getLinkDownstreamBandwidthKbps()` | `nc{...}` | **0** | `NetworkCapabilities` | Specced. Backed by `mLinkCapacityEstimateList`, which read `2147483647` (unavailable) for a 355 s window and then jumped to a real value **[M]**. It is an estimate; see B.7 — recommend cutting it as a stored metric |
| `mLinkCapacityEstimateList` (typed, per-CC) | registry | **2** | `@SystemApi` | The richer form of the above |
| `PreciseDataConnectionState` (APN setting, per-PDN `LinkProperties`, `EpsQos`, fail cause, network validation status) | `mPreciseDataConnectionStates` | **2** | `PreciseDataConnectionStateListener`, `READ_PRECISE_PHONE_STATE` | **NEW.** The per-APN view: separate `internet` and `ims` PDNs with different addresses, DNS, MTU and TCP buffers **[M]** |
| Per-RAT byte counters | `dumpsys netstats` | **0** | `NetworkStatsManager`, `PACKAGE_USAGE_STATS` (user-granted) | **NEW** — `netstats_combine_subtype_enabled=false` on this device **[M]**, so per-RAT breakdown is actually retained. Supports cause #9 |

#### Layer 4 / context

| Field | Where seen | Tier | API / permission | Note |
|---|---|---|---|---|
| Call state | `mCallState` | **0** | `CallStateListener`, `READ_PHONE_STATE` | Specced |
| **`CallQuality`** (RTP tx/rx/lost, jitter mean+max, RTT, codec, `rtpInactivityDetected`, tx/rx silence, dropped packets, playout delay) | `mCallQuality` | **2** | `CallQualityListener` (`CallAttributes`), `READ_PRECISE_PHONE_STATE` | **NEW and high-value.** `data-model.md` escalates for the whole duration of a call because "calls are the primary complaint" — this is the field that would actually say what went wrong on the call. Carrier thresholds are configured at `rtp_inactivity_time_threshold_millis=5000`, `rtp_jitter_threshold_millis=120`, `rtp_packet_loss_rate_threshold=40` **[M]** |
| `ImsReasonInfo` call disconnect cause | `mImsCallDisconnectCause` | **2** | `READ_PRECISE_PHONE_STATE` | **NEW** — named IMS failure reasons |
| IMS registration / MmTel capability state | (no `dumpsys ims` on this build) | **2** | `ImsMmTelManager.registerImsRegistrationCallback`, `READ_PRECISE_PHONE_STATE` | The Tier-0 substitute is the IWLAN NRI above |
| Wi-Fi connection event history (`level2FailureCode`, `ASSOCIATION_REJECTION`, `AUTHENTICATION_FAILURE`, `numConsecutiveConnectionFailure`, roam type) | `dumpsys wifi` | **—** | no `WifiManager` equivalent | Relevant to cause #7 and **not reachable**. Our Tier-0 substitute is the transport-change stream from `registerDefaultNetworkCallback` |
| Wi-Fi `RSSI`, `linkSpeed`, `frequency`, `standard`, `score`, `isUsable` | `mWifiInfo` | **0** partial | `WifiManager.getConnectionInfo()` / `TransportInfo`, `ACCESS_FINE_LOCATION` | RSSI/linkSpeed/frequency are Tier 0; `score` and `isUsable` are internal |
| `persist.radio.silent-reset` (**314** modem resets) | `getprop` | **—** | `SystemProperties` is hidden and blocklisted | Striking, and not reachable. Noted only so nobody spends a day trying |
| `persist.radio.block_atcmd.status=1` | `getprop` | **—** | — | Confirms the README: AT commands are blocked at the vendor layer **[M]** |

### A.4 The bar premise, now measured on this carrier

`measuring-reality.md` Part 1 argues the bars are a carrier-configurable marketing knob.
The device confirms it, and adds a twist the doc does not anticipate.

From `dumpsys carrier_config`, the home carrier **[M]**:

```
lte_rsrp_thresholds_int_array   = [-128, -118, -108, -98]
lte_rsrq_thresholds_int_array   = [-20, -17, -14, -11]
lte_rssnr_thresholds_int_array  = [-3, 1, 5, 13]
parameters_used_for_lte_signal_bar_int = 1      # RSRP only
use_only_rsrp_for_lte_signal_bar_bool  = false
eutran_rsrp_hysteresis_db_int   = 2
eutran_rsrq_hysteresis_db_int   = 2
eutran_rssnr_hysteresis_db_int  = 2
```

Three findings:

1. **The bar is RSRP-only on this carrier.** `parameters_used_for_lte_signal_bar_int = 1`
   selects RSRP alone. The doc's claim is not a generality here, it is this carrier's
   actual configuration **[M]**.
2. **`getLevel()` is not what the user sees.** At `rsrp = -93 dBm` on SIM1, AOSP
   `getLevel()` returned `4` — correct against the thresholds above — while Samsung's
   vendor field `SignalBarInfo{lteLevel=3}` in the same object read **3** **[M]**. The
   status bar renders the vendor value. So `measuring-reality.md` is in fact
   *understating* the case: even the platform's own `getLevel()` disagrees with the icon,
   and there is no API for the number the user is looking at. The app should show its own
   measurement against the carrier thresholds and say plainly that the phone's own icon
   uses an undocumented vendor curve.
3. **The 2 dB hysteresis is the mechanism behind the frozen readings** in Part B. The
   platform suppresses a `SignalStrength` report until a metric moves ≥ 2 dB, or the level
   changes.

**Risk to the calibration plan.** `measuring-reality.md` Part 4.2 proposes reading the
carrier's bar thresholds via `CarrierConfigManager` and displaying them. Since Android 13,
`getConfigForSubId` returns only an allowlisted subset to callers without carrier
privileges or `READ_PRIVILEGED_PHONE_STATE`, and the RSRP/RSRQ/SNR threshold keys are
unlikely to be in it **[E — inferred from platform behaviour, not measured; I had no app
on the device]**. **Action: probe this at first run** and fall back to shipping AOSP
defaults labelled as defaults. Do not build UI that assumes the real thresholds are
readable.

### A.5 The two fields you asked about specifically

**`BarringInfo` — real, useful, Tier 2, and worth it.** Present in the registry for both
SIMs. SIM1 carries all ten service types with `mBarringType=NONE`; SIM2 carries only type
8 **[M]** — i.e. the barring table itself is sparse and carrier-dependent, so absence of a
service type is not the same as "not barred". `mConditionalBarringFactor` /
`mConditionalBarringTimeSeconds` are exactly the ACB/UAC parameters that produce
"full bars, nothing connects" under congestion, which is cause #9 as currently written and
which nothing at Tier 0 can distinguish from a congested-but-unbarred cell. This is the
single strongest Tier-2-only diagnostic in the inventory.

**`PcscfAddresses` — Tier 2, and *not* worth the dependency.** Two barriers, not one: the
getter is `@SystemApi`, and the IMS PDN is a restricted `Network` that a normal app cannot
even match with a `NetworkRequest` without `CONNECTIVITY_USE_RESTRICTED_NETWORKS`. Reaching
it needs the full `PreciseDataConnectionState` path. And the payoff is thin — P-CSCF
addresses tell you which IMS proxy was assigned, which almost never explains a data
interruption. **Recommendation: skip it.** If the Shizuku path is built anyway for
`BarringInfo` and `CallQuality`, capture P-CSCF opportunistically as a one-line field;
do not let it justify the dependency on its own.

**Verdict on Shizuku.** Worth the dependency, for `BarringInfo` + `CallQuality` +
`PhysicalChannelConfig` — congestion barring, call-quality forensics, and true CA/NSA
state. Not worth it for P-CSCF. It must stay strictly optional: everything in Phase 1 has
to work without it, and Tier-2 fields must be nullable and rendered as "not collected"
rather than as zero.

---

## Part B — What it costs to watch

### B.1 The headline: the platform, not our code, sets the sampling rate

**Measurement [M].** `dumpsys telephony.registry` polled 300 times over **355.4 s**
(mean interval 1.19 s), screen **on**, Wi-Fi the default route, device stationary,
SIM1 `mDataActivity=4` (DORMANT). Counting changes to each cached field:

| Field | SIM1 (home carrier) | SIM2 (foreign SIM, roaming) |
|---|---|---|
| `mSignalStrength` | **5 changes / 355 s** (1 per 71 s; changes cluster — median gap *within* a cluster 2.3 s, max gap 18 s) | 1 change / 355 s |
| `mCellInfo` | **0 changes** | **0 changes** |
| `mTelephonyDisplayInfo` | 0 changes | 1 change |
| `mPhysicalChannelConfigs` | 0 changes | 1 change |
| `mDataActivity` | 0 changes | 0 changes |
| `mLinkCapacityEstimateList` | 0 changes | 2 changes |

This reproduces and explains the 18 byte-identical readings over 38 s. It is **not** a
dumpsys artefact. `TelephonyRegistry` updates its cached copy at the moment it broadcasts
to listeners, so an unchanged cache means **no callback fired**. The registry view *is* the
callback stream.

**The `CellInfo` result is the severe one.** The embedded `mTimeStamp` field is
elapsed-realtime based, so its age is directly measurable against `/proc/uptime`. At the
start of the window the cached `CellInfo` was **517 s old for SIM1 and 1085 s old for
SIM2**, and it did not refresh once in 355 s — ending at **872 s and 1440 s stale**
respectively **[M]**.

> **`CellInfoListener` delivers nothing while the device is idle.** Every neighbour-cell
> reading the app will ever have must come from an explicit `requestCellInfoUpdate()`.

That inverts the guidance in `data-model.md` §1 ("Prefer `TelephonyCallback.CellInfoListener`
and treat explicit refresh as a burst-mode-only tool"). The listener is not an alternative
to the explicit refresh; on this device it is not a source at all at rest.

### B.2 Screen state is the dominant gate — measured over 8.4 h

**Measurement [M].** `dumpsys batterystats` history: 44 381 events spanning
**8.40 h** (06:44 → 15:08), counting `phone_signal_strength=` transitions bucketed by
`+/-screen` and `+/-mobile_radio`:

| `mobile_radio` | `screen` | minutes | level changes | per hour | mean gap |
|---|---|---|---|---|---|
| idle | **off** | 157.9 | 22 | **8.4** | **431 s** |
| idle | on | 240.9 | 409 | 101.9 | 35.3 s |
| active | **off** | 11.7 | 0 | 0 | — |
| active | on | 93.6 | 560 | **358.9** | **10.0 s** |

Screen on was 334.5 min of the 8.40 h window (66.3 % duty, 21 sessions, median session
616 s) **[M]**.

Two caveats, stated plainly. `phone_signal_strength` is the **quantised level** (0–4), so
these counts are a *lower bound* on raw `SignalStrength` callbacks. And the
`active`/`screen off` cell has only 11.7 minutes in it, so its zero is weak evidence.

**Interpretation.** Screen-off suppresses telephony reporting by roughly **40×**
(8.4/h vs 359/h). Radio activity multiplies by a further ~3.5× when the screen is on. The
practical floor is one radio datapoint per **~7 minutes** with the screen off, and one per
**~10 seconds** at best with the screen on and the modem connected.

### B.3 What *does* move fast

Not everything is slow. From the `telephony.registry` local-log ring — 256 entries over a
17-minute span **[M]**:

| Notification | Count | Rate |
|---|---|---|
| `notifyServiceStateForSubscriber` | 120 | 1 per 8.5 s |
| `notifyDisplayInfoChanged` | 82 | 1 per 12.5 s |
| `notifyDataConnectionForSubscriber` | 52 | 1 per 19.7 s |

And within one 2-second span, `overrideNetwork` was observed flapping
`LTE_CA → NONE → LTE_CA → NONE` with `isUsingCarrierAggregation` toggling in step **[M]**.

**So `ServiceState` and `TelephonyDisplayInfo` are pushed roughly 40× more often than
`SignalStrength` and infinitely more often than `CellInfo`.** The NSA/CA-thrash detector in
cause #2 and #3 is therefore well served by free callbacks and needs no polling at all —
it is `CellInfo` and `SignalStrength`, the things we most wanted at 1 Hz, that are starved.

### B.4 Power, measured on this device

**Measurement [M].** `dumpsys batterystats` estimated power use, 7 h 48 m on battery,
5000 mAh battery, computed drain 2789 mAh:

| Bucket | mAh | Duration | Derived average |
|---|---|---|---|
| `screen` | 1244 | — | — |
| `cpu` | 1124 | 7 h 48 m | 144 mA |
| `phone` (cellular) | 113 | 7 h 48 m | **14.5 mA** |
| `wifi` | 20.7 | 7 h 48 m | 2.7 mA |
| `wakelock` | 18.2 | **55 m 47 s** | **19.6 mA while held** |
| `sensors` | 14.8 | — | 1.9 mA |
| `mobile_radio` (data) | 2.41 | — | — |
| `gnss` | 1.92 | **3 m 2 s** | **38 mA while active** |

**The two numbers that govern the design:**

- **GNSS costs ~38 mA while active.** Continuous GNSS = 912 mAh/day = **18 %/day**.
- **Holding a wakelock costs ~19.6 mA.** Held continuously = 470 mAh/day = **9.4 %/day**.

**Budget arithmetic.** 1 % of this battery = 50 mAh. The 3 %/day target =
**150 mAh/day = 6.25 mA continuous**. A single permanently-held wakelock is 1.5× the
entire budget. *Never hold a wakelock across samples* is not a guideline here, it is the
binding constraint.

For reference, one `dumpsys telephony.registry` costs **55 ms** of device CPU **[M]** —
but that is the shell path; an app reading the same state from cached
`TelephonyManager` getters is far cheaper, and reading it from a callback costs nothing at
all beyond the binder wake.

### B.5 Probes are the hidden radio cost

The modem baseline measured **14.5 mA** average. A probe issued while
`getDataActivity() == DORMANT` forces an RRC idle→connected promotion and then holds the
connection through the network's inactivity timer (seconds to tens of seconds) before
releasing. **[E]** Estimating the promotion + tail at ~100–200 mA for ~10 s gives
**0.3–0.6 mAh per probe that wakes an idle radio**. At one per minute that is
430–860 mAh/day — **3–6× the entire budget, from probing alone**.

This gives the single most valuable scheduling rule in this document, and it has a Tier-0
gate:

> **Never issue a probe that wakes an idle radio.** Probe only when
> `getDataActivity() != DATA_ACTIVITY_DORMANT`, or the screen is on, or a call is active —
> i.e. piggyback on traffic something else is already paying for.

`getDataActivity()` is public API with **no permission requirement**, which makes this rule
free to implement.

### B.6 Location, which is indeed the dominant cost

**Measurements [M],** same 8.40 h window:

| | Sessions | Total | Duty | Median session | Median gap between starts |
|---|---|---|---|---|---|
| GNSS (`+gps`) | 29 | 3.0 min | 0.60 % | 4.8 s | **9.4 min** |
| Wi-Fi scan | 166 | 4.5 min | 0.90 % | 1.1 s | **1.6 min** |

And from `dumpsys location` **[M]**:

- GNSS KPI over 2 d 19 h: 7 399 location reports, **TTFF mean ≈ 3 s**, position accuracy
  mean ≈ 6 m, 0.1 % failure rate. Warm starts are fast and cheap on this device.
- `gps provider: ProviderRequest[OFF]`, `mStarted=false`, and the **last GPS fix was
  2 d 18 h old**; the last fused fix was **2 d 19 h old at hAcc = 100 m**.
- The passive provider already has **11 registrations**, including Google Play services
  fused requests at `BALANCED`.
- The network provider's only standing request is `LOW_POWER` **every 6 hours**.

**Conclusion on the four cheap-position strategies:**

| Strategy | Cost | Yield on this device | Verdict |
|---|---|---|---|
| **Passive listening** (`PASSIVE_PROVIDER` / `PRIORITY_PASSIVE`) | Zero | ~3.5 GNSS fixes/h at ~9 min spacing; at rest, *nothing for days* | Keep — free — but it cannot be the only source |
| **Cell-derived position** | Zero | Coarse; and `CellInfo` is never pushed (B.1), so it is not actually free | Reject as a primary source |
| **Fused `BALANCED`** | Near-zero marginal — resolves off the 166 Wi-Fi scans/8.4 h that happen anyway | ~60–100 m accuracy, ~1.6 min cadence | **This is the workhorse** |
| **Duty-cycled GNSS** | 38 mA × ~5 s ≈ **0.05 mAh per fix** [M for mA, E for the 5 s including TTFF] | ~6 m | Reserve for screen-on + moving |

GNSS budget from the measured 0.05 mAh/fix: 1 fix/min = 72 mAh/day = **1.4 %/day** (half
the budget); 1 fix/5 min = **0.3 %/day**; continuous = **18 %/day**.

**Consequence for the map.** `data-model.md` §5 proposes H3 resolution 10 (~65 m edge). A
fused `BALANCED` fix at 60–100 m accuracy cannot honestly fill a 65 m bin.
**Recommendation: bin adaptively by accuracy** — resolution 10 only when
`accuracyM ≤ 30`, resolution 8 (~460 m edge) for `accuracyM ≤ 150`, and `null` above that.
Store the resolution alongside the bin id so the map can render mixed resolutions honestly
instead of implying precision the fix never had.

### B.7 Revised sampling policy

Free = already-broadcast callbacks, no marginal radio or CPU cost beyond a binder wake.
Register these **permanently and for every active subscription** via
`TelephonyManager.createForSubscriptionId()` — a single-subId collector would have missed
the entire IWLAN/VoWiFi registration on SIM2 (A.3).

**Always on, always free:** `SignalStrengthsListener`, `ServiceStateListener`,
`DisplayInfoListener`, `DataConnectionStateListener`, `CallStateListener`,
`CellInfoListener` (free, but see B.1 — it will rarely fire),
`registerDefaultNetworkCallback`, `registerNetworkCallback(TRANSPORT_CELLULAR)`,
`addThermalStatusListener`, `OnSubscriptionsChangedListener`.

Everything below is what *costs*:

| State | Heartbeat row | `requestCellInfoUpdate()` | Location | Probes |
|---|---|---|---|---|
| **Charging + screen on** | 5 s | 30 s | fused `BALANCED` 30 s | 60 s |
| **Screen on, in call** | 5 s | 20 s | fused `BALANCED` 15 s | 30 s |
| **Screen on, moving** | 10 s | 30 s | GNSS 30 s (`HIGH_ACCURACY`) | on incident only |
| **Screen on, stationary** | 30 s | 300 s | passive + 1 fused / 5 min | 300 s |
| **Screen off, moving** | none — write on callback | 120 s | passive only; 1 fused on significant-motion | none |
| **Screen off, stationary** | none — write on callback | **off** | passive only | none |
| **Incident / burst** | 1 s for 60 s, Layer 3/4 only | 10 s floor | last known fix | 5 s for 60 s, gated on B.5 |

**Estimated budget [E, built on the measured mA figures in B.4]:**

| Component | Estimated cost | %/day |
|---|---|---|
| All telephony + connectivity callbacks | ~0.1 mA | 0.05 |
| Batched Room writes, WAL, ~6 k rows/day | ~0.1 mA | 0.05 |
| Foreground service + notification (30 s, screen-on only) | ~0.2 mA | 0.10 |
| `requestCellInfoUpdate()` ≈ 250/day | ~1.5 mAh/day | 0.03 |
| Location: passive + ~120 fused/day + ~60 GNSS/day | ~9 mAh/day | 0.18 |
| Probes ≈ 200/day, all piggybacked | ~10 mAh/day | 0.20 |
| **Total** | | **≈ 0.6 %/day** |

That is comfortably inside the 3 % target — and the reason is not clever engineering. It is
that **the platform gives us so little that there is very little to spend power on.** The
budget is not the constraint; the platform's push rate is.

### B.8 What to cut from the current spec

1. **Cut the 30 s screen-off heartbeat.** Measured: with the screen off, radio state
   changes 8.4 times/hour (B.2) and `CellInfo` never. A 30 s heartbeat would write ~2 880
   rows/night of which ~2 700 are byte-identical duplicates — battery and storage spent to
   record that nothing happened. Replace with **write-on-callback plus a 15-minute
   WorkManager alignment tick** that records liveness and nothing else.
2. **Cut the 5 s screen-on heartbeat to 30 s** except in-call or moving. Measured
   screen-on-and-idle `SignalStrength` change rate is 1 per 71 s (B.1); 5 s sampling is
   14× oversampled against a value that is not moving.
3. **Cut 1 Hz burst mode for Layer 1 — it is unachievable.** At 1 Hz you re-read the same
   cached `SignalStrength` object; the platform will not give you a fresh measurement. **Redefine
   burst mode as a Layer-3/Layer-4 burst** — link state, capability transitions and probes,
   all of which genuinely are sub-second-capable — plus a 10 s-floor `requestCellInfoUpdate()`
   loop for Layer 1. State the resulting resolution honestly in the UI: **our Layer-1
   incident timing resolution is bounded at roughly 10 s by the platform, not by our code.**
4. **Cut `neighboursWithin6dB` and `neighbourJson` as continuously-maintained fields.**
   They depend on a neighbour list that is never pushed (B.1). Make them explicitly
   request-driven and nullable-by-default, and never let the UI imply the neighbour set is
   current.
5. **Cut `getLinkDownstreamBandwidthKbps()` as a stored metric.** Measured unavailable
   (`2147483647`) for a 355 s window, then jumping to a real value (B.1). It is a platform
   estimate about a link, not a measurement of one, and `measuring-reality.md` already says
   so. Keep it as a display-only annotation if at all.
6. **Cut H3 resolution 10 as the fixed bin size** — see B.6.

### B.9 Schema additions implied by Part A

To `radio_sample`: `dataActivity` (RRC-idle proxy), `cellBandwidthsKhz`, `channelNumber`,
`duplexMode`, `isUsingCarrierAggregation`, `binResolution` (see B.6).

To `registration_event`: `iwlanRegState` + `iwlanAvailableServices` (Tier-0 VoWiFi),
`availableServices` for the WWAN domains, `isNonTerrestrialNetwork`, `activeDataSubId`,
`simSlotIndex`, `carrierId`.

To `link_event`: `notBandwidthConstrained` (API 36), `blocked` (boolean, Tier 0),
`tcpBufferSizes` (best-effort, `toString()`-derived).

To `context_sample`: nothing — but note `thermalStatus` reads `0` throughout and the
modem-specific **PA** temperature that would actually indicate modem throttling is **not
reachable** (A.3). The thermal cause (#8) is detectable only at whole-device granularity.

Tier-2 tables, behind the Shizuku opt-in, all nullable: `barring_event` (per service type,
with the conditional-barring factor and timer), `call_quality_sample` (RTP loss, jitter,
RTT, codec, inactivity), `physical_channel_config` (per component carrier).

---

## What was not measured

Stated so nobody mistakes a gap for a finding.

- **`requestCellInfoUpdate()` cost and throttle.** Needs an installed app; there is no
  read-only shell path to it. Its energy cost and its real rate limit are the largest
  remaining unknowns in the budget, and B.1 makes it the *only* neighbour-cell source. **Measure
  it first in Phase 1.**
- **Screen-off behaviour under live polling.** Turning the screen off would have mutated a
  daily-driver device's state; screen-off numbers come from the platform's own 8.4 h
  history ring instead.
- **Deep Doze.** The wireless `adb` session kept the device out of it
  (`mState=ACTIVE` **[M]**). Real screen-off-plus-Doze rates may be *lower still* than B.2.
- **Moving vs stationary** was not isolated as a variable; the 8.4 h history mixes both.
- **Whether `CarrierConfigManager` returns the bar thresholds to a normal app** (A.4) —
  inferred, not measured, and worth a first-run probe.
- **Actual `TelephonyCallback` delivery rate to an app**, as opposed to registry cache
  updates. These should be identical by construction, but it is an inference.
- **NR/5G behaviour.** No NR leg was active during the session, though `batterystats` shows
  ~65 min of NR (3–6 GHz) earlier in the same discharge cycle **[M]**. Cause #2 could not be
  exercised.
