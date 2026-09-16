# The first off-Wi-Fi measurement

**25 minutes of real cellular use.** Every measurement before this one
was taken while Wi-Fi held the default route, so nothing rode on the cellular bearer and every
reading described an unused radio. This is the first time the thing being measured was also the
thing being used.

The headline is not what the design predicted.

---

## 1. The connection was flawless while in use, and its signal was terrible

Across the whole excursion, on the data SIM:

| | |
|---|---|
| Service loss | **0** registration events other than `IN_SERVICE` |
| Validation loss | **0** |
| IP changes | 1 — the Wi-Fi→cellular transition itself |
| Probe failures | **0 of 68** |
| Probe latency | p50 **145 ms**, p90 711 ms |
| MTU / CLAT | 1500, stable, no CLAT throughout |
| SINR | median **0 dB**, p10 **−3 dB** |
| RSRQ | −14 dB average |
| Serving cells | 8, across **two** sites (sites A and B), bands 40 and 8 |
| Cell changes | 54 in 25.2 min = **2.1/min** |

A median SINR of 0 dB is bad by any textbook. It produced no user-visible failure at all. That
combination is the most useful thing in this dataset, because it falsifies the project's working
assumption: **poor SINR on this network is not what breaks the connection.** Every prior document
treated the quality metrics as the diagnosis. They are not; they are the weather.

## 2. What does break it: the idle radio, not the bad radio

> **Correction, 2026-09-13 — the effect is real but was overstated here, and it is mainly a latency
> effect.** The 12.3 % against 0 % below rests on 93 probes. With 400 clean probes over 20 hours,
> refused binds excluded throughout:
>
> | Radio idle for | Fails, cold vs awake | One-sided p | Median latency, cold vs awake |
> |---|---|---|---|
> | over 10–30 s | 7.2 % vs 3.9 % — 1.8× | 0.12 – 0.13 | **239 vs 122 ms** |
> | over 60 s | 9.2 % vs 4.1 % — **2.27×** | **0.035** | **504 vs 135 ms** |
>
> The robust result is the dose-response in latency: the longer the radio has been idle, the slower
> the first connection -- 122, then 239, then 504 ms -- and it holds under every definition of
> "cold" tried. The failure-rate effect always points the same way but reaches significance only
> after a minute or more of idle. Warm is not perfect either: 3.9 % of already-awake probes fail.
> The honest statement is "reliably slower to wake, and more likely to fail after a long idle",
> not "broken versus perfect". An intermediate figure of 2.46×, p = 0.047, quoted during analysis,
> used a narrower subset and does not survive the larger one.
>
> **Every genuine failure is a timeout at 6.0–7.0 s -- which is the probe's own timeout.** So the
> failure *lengths* here describe the instrument, not the network. A stall-recovery measurement now
> records how long the network actually takes to answer again; see `CellProbe.measureRecovery`.

The failures we had already measured — 6-second stalls, no middle ground — turn out to belong
entirely to a *dormant* bearer. Restricting to like-for-like cold probes (a probe that arrives
with no recent traffic on the radio, which is the old single probe on Wi-Fi and the first of each
pair off Wi-Fi):

| Condition | Cold probes | Failures | Rate | Mean latency (OK) |
|---|---|---|---|---|
| Wi-Fi up, cellular dormant | 57 | **7** | **12.3 %** | 552 ms |
| Wi-Fi off, cellular in use | 36 | **0** | **0 %** | 473 ms |

At the dormant-radio failure rate, 36 cold probes should have produced 4.8 failures. Zero were
observed; P ≈ 0.008. The comparison is cold-against-cold, so it is not an artefact of the paired
probe design introduced shortly before it.

The mechanism shows up directly inside each pair, where the second probe rides on the connection
the first one established:

| Position in pair | n | Mean | Max |
|---|---|---|---|
| First (pays promotion) | 41 | **457 ms** | **5263 ms** |
| Second (connection already up) | 35 | **168 ms** | 1156 ms |

A ~290 ms penalty at the median and a tail that reaches 5.3 s — just short of the 6 s timeout
that the 12.3 % crossed. **The failure is the idle→connected transition, not the connection.**

### Why this is the answer to "drops and reconnects"

The user-visible complaint was never continuous badness; it was interruption and recovery. That
is the exact signature of a bearer that keeps falling dormant and paying to come back:

- YouTube buffers, goes quiet for 30–60 s, then refills — the refill pays promotion
- Teams during silence, or with the screen locked
- a notification or incoming call arriving after a quiet period
- an app resumed from the background
- and worst of all, the Wi-Fi→cellular handover itself: cold radio, new IP, and every app
  demanding data at once

Continuous traffic hides the problem, which is why a voice call survives and a video call that
pauses does not. It is also why 25 minutes of active use produced zero failures.

## 3. The deployable lever

> **Correction, 2026-09-13 — this lever is smaller than the section below claims.** With the effect
> resized above, warmth removes an elevated failure rate after long idle and a large wake-up delay,
> not a failure class. It is worth shipping and it is not an answer to a dropped call. Its battery
> cost remains unmeasured: the first experiment run came back `BLIND` and has re-armed.

**Hold the cellular bearer warm across traffic gaps.** Low-rate traffic on a cellular-bound
socket keeps the connection in RRC_CONNECTED, and the measurements above say that converts a
12.3 % failure class into a 0 % one. It needs no privilege, no root, and no Shizuku — the same
`requestNetwork` + `bindSocket` path the probe already uses.

This is what `KeepaliveExperiment` was built to test, and the excursion tested it better than the
A/B could have: real traffic, larger effect, and a control that arrived for free. That class was
deleted on 2026-09-16 along with `WarmthExperiment`, `PhaseA` and `BearerMove`, when the app was
reduced to an instrument; `BearerWarmth` — the thing they were testing — stayed. Read the
correction at the top of this document before quoting any figure below it.

Targeted rather than continuous, because the cost is battery:

| Trigger | Why |
|---|---|
| First 60–120 s after leaving Wi-Fi | worst case — cold radio, new IP, apps all demanding at once |
| While a media app is buffering-then-idle | the YouTube refill-stall pattern |
| Before and during a call, VoIP included | the original complaint |
| Not while Wi-Fi carries the default route | nothing rides on cellular; pure battery cost |

The remaining unknown is price, not effect: what holding the bearer costs in battery, measured
rather than assumed. That is the next experiment, and it is now a cost question rather than a
"does anything work" question.

## 4. Two things this run also settled

**There is still no dominant server.** A first pass suggested cell A/33 was worth 8 dB of SINR
over B/32. Restricting to the window where the phone actually alternated between them
(an eleven-minute stretch) dissolves it — A/33 averages **−2 dB** there, slightly *worse* than the cell it
was supposed to beat. The apparent advantage was the time of the sample, not the identity of the
cell. The earlier conclusion stands, and it stands for a better reason now, having survived a
same-time control.

**B8 is marginally better than B40, and unreachable anyway.** 45 samples on B8 averaged SINR 3
against B40's 1, with 3 dB better RSRQ. A real difference, far too small to chase, and band
selection remains unavailable at every privilege tier we tested.

## 5. The instrument gap this run exposed

**No location was recorded for the entire excursion — 0 fixes, 0 map bins.**

`MapLocationCollector` starts when the Map tab is composed and stops when it leaves;
`CollectorService` never requests a fix. The app was in the background for the whole trip, so
nothing asked for location and nothing arrived.

This is not the missing `ACCESS_BACKGROUND_LOCATION` grant, and declaring that permission would
not fix it. A foreground service with `foregroundServiceType="location"` — which this service
already declares and was already running as (`types=0x00000008`) — is entitled to location
updates while the UI is in the background. The appop confirms it: `FINE_LOCATION: foreground`,
allowed. Nobody asked.

Consequences, both material:

- **The map cannot grow organically**, which is a stated project goal. Polygons only appear for
  ground covered with the Map tab open, which is nobody's real usage.
- **Movement cannot be separated from network churn.** The 2.1 cell changes per minute here
  against roughly 0.4/min while stationary looks like a large effect of being in use, and it
  cannot be claimed, because the phone was probably also moving. The only hint is indirect: the
  second SIM's RSRP fell from −77 to about −105 on an unchanged PCI, which is attenuation or
  distance, not reselection.

The fix is to start the location collector from `CollectorService` rather than from the Map
screen. No new permission, no prominent-disclosure flow, and it makes both the map and every
zone-based conclusion possible.

---

## What changes in the plan

| Previously | Now |
|---|---|
| Diagnose poor SINR/RSRQ as the cause | SINR is the weather; **dormancy** is the cause |
| Keepalive was one hypothesis among several | Keepalive is **the** lever, with p ≈ 0.008 behind it |
| Bearer move to Wi-Fi/VoWiFi was the biggest win | Still valuable, but it addresses a failure this run could not reproduce |
| Map grows as the phone travels | It does not, and will not until location moves to the service |
