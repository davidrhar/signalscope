# What the app can do automatically

**App-actuated only. No human action, no "move two metres", no advice the user has to follow.**

---

## First, an honesty constraint that shapes the whole list

**Almost nothing an app does can change SINR.** SINR is a ratio set by transmit power, path loss and
interference from other transmitters. An app controls none of those. The levers below change:

- **which bearer** carries traffic (Wi-Fi vs cellular),
- **which RRC state** the modem sits in (idle vs connected),
- **which cell** the phone is camped on — and only indirectly, by forcing re-registration,
- **what we ask of the link**, and when.

Only the third can move SINR, and only by luck. So an experiment that judges these actions on
"did SINR improve" will mostly return *no*, and concluding from that that nothing works would be
the wrong lesson.

**The honest success metrics are experienced ones:** serving-cell changes per minute, validated
uptime, probe success rate and p90 latency, time-to-recover, and connection setup time. Those are
the things these levers actually touch, and they are what the user feels.

One exception is worth testing on its merits: **RRC state changes who controls mobility**, and
therefore *can* change which cell serves you. See the experiment below.

---

## Tier 0 — available today, no privilege, no user involvement

| # | Action | Mechanism | What it should improve |
|---|---|---|---|
| 1 | **Warm the radio before a session** | Detect `MODE_IN_COMMUNICATION` / dialler / call state, emit a small bound request to move RRC idle→connected | Removes a 0.5–2 s idle→connected transition, including random access, from the first second of a call |
| 2 | **Keepalive during real-time media** | Low-rate traffic bound to the cellular `Network` for the duration of a call | Holds RRC connected, so mobility is *network-controlled handover* rather than *UE-autonomous reselection* — should reduce mid-session cell changes |
| 3 | **Prefer a better bearer for our own traffic** | `requestNetwork` + `bindSocket` / `bindProcessToNetwork` | Our probes stop competing on a failing bearer. Affects only us — no app can move another app's traffic |
| 4 | **Suggest known-good Wi-Fi** | `WifiNetworkSuggestion` — **NOT Tier 0 for this app**: the API requires `CHANGE_WIFI_STATE`, which is not declared and would be a new permission | Would be the largest single win where Wi-Fi exists. Listed here as Tier 0 in error; it is Tier 0 only for an app willing to take that permission |
| 5 | **Suppress probing while dormant** | `getDataActivity() == DORMANT` gate | Avoids an RRC promotion that costs more energy than the rest of the collector combined |
| 6 | **Refuse to cycle the radio when it cannot help** | `serverDominance` < ~3 dB ⇒ reselection lands on an equally bad co-channel neighbour | A *negative* action. Preventing a harmful remediation is as valuable as performing a good one |
| 7 | **Queue remediation for a safe window** | Traffic-class slack from `traffic-classes.md` | A 2 s fix is free behind a 30 s buffer and fatal during a call |
| 8 | **Pre-emptive warning before dialling** | Known-bad state or known-bad bin | Prevents the failure instead of remediating it |

## Tier 2 — Shizuku, not yet installed

| # | Action | Mechanism | Honest expectation |
|---|---|---|---|
| 9 | **Preferred-network-type toggle** | Hidden `setPreferredNetworkTypeBitmask` | Forces re-registration; *may* change serving cell. The least disruptive way to reroll the dice |
| 10 | **Airplane-mode cycle** | Full detach, PLMN scan, reselection | The only lever that can genuinely land you on a different cell. Very disruptive |
| 11 | **Data re-anchor** | `svc data disable/enable` | Rebuilds the PDN and changes IP. Fixes a stuck bearer; **cannot** help a radio-layer problem |

## Not possible at any tier

Band selection, cell locking, transmit power, antenna selection, PLMN priority, and any control
over another app's traffic. Stating this plainly is cheaper than fielding it repeatedly.

---

## The experiment: does holding RRC connected change anything?

**Hypothesis.** In `RRC_IDLE` the handset performs cell reselection autonomously; in
`RRC_CONNECTED` the network controls mobility through measurement-report-driven handover. If that
is true here, a keepalive that holds the connection should reduce serving-cell churn, and *may*
place the device on a better-serving cell — which would move SINR as a second-order effect.

**Conditions.** The reference device has not moved for the duration of this project, so position,
orientation and building are constants. That removes the largest confound available.

### Design

**Alternating blocks, never sequential.** Interference here is load-driven, and load follows the
clock. A plain A-then-B comparison would confound treatment with time of day and report the
evening rush as an effect.

```
A (idle)  5 min  →  B (keepalive)  5 min  →  A  →  B  →  …
```

Randomise the starting arm; run at least six blocks per arm before reading anything.

**Measured per block, per subscription:**

| Metric | Why |
|---|---|
| SINR p10 / p50 / p90 and sd | The second-order hypothesis, and the variance matters more than the level |
| RSRP p50 and sd | The control. If RSRP moves, something physical changed and the block is void |
| Serving-cell changes per minute | **The primary endpoint** — this is what the mechanism claims to change |
| Distinct PCIs seen | Ping-pong breadth |
| RSRQ p50 | Load proxy |

**Analysis.** Paired by block index, so an A block is compared with its neighbouring B block rather
than with the grand mean. With sd(SINR) ≈ 2.9 dB and the platform capping Layer-1 samples near one
per 10 s, a 5-minute block yields roughly 30 samples and a standard error near 0.5 dB — so a 1 dB
effect needs several block pairs to separate from noise. Report a confidence interval, not a
point estimate, and be willing to report "no detectable effect".

**Pre-registered stopping rule.** The primary endpoint is cell changes per minute. If that does not
move, the mechanism did not work, regardless of what SINR does — because a SINR difference with
unchanged cell churn is interference drifting, not our doing.

---

## Tier 2 doors, tested rather than assumed (2026-09-13)

Run against uid 2000, which is the UID Shizuku provides, so these are results and not guesses.
Read-only throughout; no device state was changed.

| Door | Result |
|---|---|
| `cmd wifi status` | **Works.** A real gain: this app declares no `ACCESS_WIFI_STATE`, so at Tier 0 it cannot tell "Wi-Fi off" from "Wi-Fi on with nothing in range". Only the first line is parsed — the rest carries SSID, BSSID and MAC |
| `cmd wifi set-wifi-enabled` | Present, and shell holds both `CHANGE_WIFI_STATE` and `NETWORK_SETTINGS`. **Not verified by effect**: Wi-Fi was already on, and turning off a daily driver's Wi-Fi to manufacture a test is not acceptable — it is also the adb transport. Recorded `PRESENT_UNVERIFIED` |
| `settings get global wfc_ims_enabled` | **null**, as are `wfc_ims_mode` and `volte_vt_enabled`. The preference no longer lives there, so writing it would write a key nothing reads — success that is really failure |
| `content query content://telephony/siminfo` | **Refused**: `SecurityException: Access SIMINFO table from not phone/system UID` |
| `cmd phone cc get-value carrier_wfc_ims_available_bool` | **Permission denied**, despite shell holding `READ_PRIVILEGED_PHONE_STATE` — a vendor restriction above AOSP |
| `cmd phone ims enable/disable` | Exists, but switches IMS for the whole slot and there is no VoWiFi subcommand. IMS carries VoLTE too, so the only available direction trades HD calling for nothing |

**The conclusion, stated plainly: no tier available to this app can set the Wi-Fi-calling
preference.** Enabling the Wi-Fi *radio* is reachable at Tier 2 and probably works. VoWiFi is not,
and the door is closed by a vendor restriction rather than by anything AOSP documents.

VoWiFi remains **observable**: `ServiceState`'s `domain=PS transportType=WLAN` row is registered on
the reference device and is already collected as `SimState.iwlanRegistered`, so the app can say
whether the ePDG tunnel is up. It cannot say whether a given call is riding it — that is
`Call.Details.PROPERTY_WIFI`, readable only by the default dialler.

Closed for actuation, retained for diagnosis.
