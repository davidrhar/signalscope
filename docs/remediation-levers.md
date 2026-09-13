# Remediation levers: what can actually be done at bad SINR

**Companion to [`optimisation.md`](optimisation.md) (the four-rung ladder) and
[`traffic-classes.md`](traffic-classes.md) (what is running decides what is safe).
This document is about the levers themselves: what each one physically changes, what it cannot
change, and how we learn which ones work where.**

---

## 1. Read the situation first

A worked example from the reference device, measured:

```
RSRP  −97 dBm        power:   mediocre, but not a coverage hole
RSRQ  −14 … −16 dB   quality: poor
SINR  −3 … −1 dB     usable:  no
Band  40 (2300 MHz TDD)
```

**This is not weak coverage. It is interference or load.** At −97 dBm the phone hears the tower
perfectly well; at negative SINR it cannot separate that signal from everything else arriving on
the same frequency. Throughput collapses to the lowest modulation, retransmissions climb, and
latency becomes spiky rather than merely high — which is precisely the profile that destroys
calls and video while leaving a speed test looking survivable.

Two structural facts make it worse:

- **Band 40 is a capacity layer, not a coverage layer.** 2300 MHz penetrates buildings poorly.
  Where an operator also holds low-band spectrum (900 MHz and similar), that is the layer that
  works indoors — and the phone is not on it.
- **TDD shares one channel between uplink and downlink.** At negative SINR the uplink usually
  degrades first, because the handset transmits at ~23 dBm against a base station transmitting far
  more. "They can't hear me" arrives before "I can't hear them".

**Diagnostic rule:** RSRP healthy + SINR negative ⇒ interference or congestion. Never report this
as weak signal, and never recommend a remedy aimed at coverage.

### The refinement: measure variance, and look for a dominant server

A single SINR reading is nearly worthless here. Measured over 200 consecutive samples at one
stationary location on the reference device:

| | p10 | p50 | p90 | range | **sd** |
|---|---|---|---|---|---|
| RSRP | −97 | −95 | −94 | −99…−89 | **1.58 dB** |
| RSRQ | −16 | −14 | −13 | −17…−11 | 1.17 dB |
| **SINR** | **−1** | **3** | **5** | **−4…+16** | **2.86 dB** |

**The signal term is stable and the noise term is not.** SINR varies across 20 dB while RSRP
moves 10 and mostly sits within 3. Fading or user movement would move both; only interference
moves one. That asymmetry — sd(SINR) ≫ sd(RSRP) — is a cleaner interference detector than any
absolute threshold.

Over the same window the serving cell was **three different PCIs while stationary**. That is not a
second fault. It is the same one:

> **No dominant server.** Where three co-channel cells arrive at comparable power, they interfere
> with each other *and* none of them wins reselection. Low SINR and reselection ping-pong are two
> symptoms of one condition, and treating them as separate causes would produce two wrong
> remedies.

It also explains the *shape* of the variance: the SINR distribution is a mixture across three
cells, not one cell's noise.

**Two metrics follow, and both belong in the schema:**

- **`sinrVariance`** over a rolling window, reported as p10/p50/p90. For real-time media, variance
  hurts more than level: a stable 3 dB beats an oscillating 0↔5, because codecs and schedulers
  chase the swing and jitter follows it. `measuring-reality.md` already says "report
  distributions, never means" about probe latency; the same rule applies to SINR, and the Live
  dashboard's flickering instantaneous number currently breaks it.
- **`serverDominance`** = serving RSRP − best neighbour RSRP. Below ~3 dB means no dominant
  server. Computable today from the neighbour list.

**What this does to the levers.** When the cause is *no dominant server*, forcing reselection is
close to pointless — the phone lands on one of the same three cells and the condition is unchanged.
This is the case where the honest answer is positional or Wi-Fi, and where an app that cycles the
radio is performing activity rather than help.

---

## 2. The lever inventory, honestly tiered

### What we cannot do, ever

Band selection, cell locking, PLMN priority, forcing a handover. These are modem and carrier
territory and need a UICC-signed app. **The band is not ours to choose.**

### Tier 0 — no privilege

| Lever | What it changes | Honest expectation |
|---|---|---|
| Prefer a known-good Wi-Fi | Moves *everything* off a failing bearer | The single largest win when Wi-Fi exists |
| Encourage Wi-Fi calling | Moves the *call* off the failing bearer | Large for calls; we can detect it is unused and say so |
| Defer our own probes | Stops us competing for scarce capacity | Small, but free and correct |
| **Positional guidance** | Changes the physics | See §4 — the most underrated lever |

### Tier 2 — Shizuku, and a distinction that matters

An earlier draft treated "data re-anchor" as the universal remedy. **It is not, and the difference
is physical:**

| Action | What it actually does | Can it change cell or band? |
|---|---|---|
| `svc data disable/enable` | Tears down and rebuilds the **PDN**. New IP. | **No.** The modem stays camped on the same cell |
| Airplane-mode cycle | Full detach, PLMN scan, **reselection** | **Yes** — the only lever that can land you elsewhere |
| Preferred-network-type change | Re-registration, often forces reselection | **Sometimes**, and less disruptively |

So for the worked example above, a data re-anchor is the *wrong* tool: it fixes a stuck bearer,
and the bearer is not what is broken. The lever with any chance of improving SINR is the one that
forces reselection — and even then it is a shake-the-box move, not a guarantee.

**Make it evidence-led rather than hopeful.** `getAllCellInfo()` reports neighbours with their
ARFCN, and ARFCN maps to band. So:

> If SINR is negative on a high band, **and** a neighbour on a low band is visible at usable
> RSRP, a reselection attempt has a reason to exist. If no better neighbour is visible, it does
> not — and we should say so instead of cycling the radio for nothing.

That single predicate turns the most disruptive action we have into one we only fire when the
data says it might land somewhere better.

---

## 3. Change what rides on the bearer, not the bearer

At SINR −3 the radio is what it is. The remaining wins are in *what we ask of it*:

- **Move real-time media to Wi-Fi** where any is reachable, including Wi-Fi calling.
- **Shrink the working set during the bad window.** We cannot throttle other apps — Android gives
  no such mechanism to a third-party app, and pretending otherwise is the fantasy `optimisation.md`
  already rejects. What we *can* do is stop adding to the load ourselves, and defer anything of
  our own that can wait.
- **Warn before committing.** Starting a call on a bearer we already know is unusable is the
  avoidable failure. A one-line warning at the moment of dialling is worth more than any automatic
  action taken afterwards.

---

## 4. Positional guidance — the lever nobody ships

At negative SINR the most effective intervention available to a human is usually **two metres of
movement**, and the phone is uniquely placed to know that, because it has the user's own history.

Not "your signal is weak" — everyone knows that already — but:

> *"SINR here is −3. Twenty metres north, measured eleven times over the last month, it averages
> +9. The window side of this room is the difference."*

This requires nothing we do not already collect: binned position, SINR, and a timestamp. It is
Tier 0, it costs no privilege, it needs no remediation machinery, and it changes the physics
instead of negotiating around them. It also degrades honestly — with no history for a place, it
simply says nothing.

Of everything in this document, this is the item most likely to produce a felt improvement, and
it is the one an app that thinks of itself as a *radio* tool would never think to build.

---

## 5. Pre-emption from zone history

The map already stores, per bin, what happened there. That makes prediction a lookup rather than
a forecast:

| Signal | Pre-emptive action |
|---|---|
| Entering a bin with a poor SINR record | Finish handshake-heavy work **now**, while the link still works |
| Call starting in a known-bad bin | Warn, and offer Wi-Fi calling if Wi-Fi is present |
| Known-bad bin + known-bad hour | Say so — congestion is often a timetable, not a place |
| Approaching a bin where reselection historically helped | The one place a radio-layer action is justified pre-emptively |

The guard from [`adaptive-aggregation.md`](adaptive-aggregation.md) applies throughout: a bin
below the evidence threshold triggers nothing. Acting on an unsurveyed bin is inventing coverage.

---

## 6. Every action is a hypothesis, so measure it

Each lever above is a claim about a mechanism we inferred rather than observed. So per
[`optimisation.md`](optimisation.md), each one is measured **per bin** and **per cause class**:

- Did a reselection attempt in this bin actually improve SINR, or just cost 12 seconds of outage?
- Did the Wi-Fi recommendation hold up, or is that access point worse than the cellular it replaced?
- Did positional guidance reproduce, or was the +9 dB a one-off?

Actions that do not help get **demoted** and stop being offered here. An action becomes eligible
for automation only once its measured success rate clears a threshold **in this bin, on this
device** — never because the mechanism sounds plausible. A system that can discover its own advice
is wrong in a particular place is worth more than one that is confidently wrong everywhere.

---

## 7. Applied to the worked example

RSRP −97, RSRQ −15, SINR −2, Band 40, at a known location:

1. **Say what it is.** Interference or load on a capacity band — not weak signal.
2. **Wi-Fi?** If reachable, move to it, and route calls over it. Largest available win.
3. **Dominant server?** Compute serving RSRP − best neighbour RSRP. If it is under ~3 dB the
   condition is *no dominant server*, reselection will land on an equally bad neighbour, and the
   correct action is to not cycle the radio at all.
4. **Better band available?** Only if a neighbour on a *low* band shows usable RSRP is a
   reselection attempt justified. Otherwise say why nothing is being done.
5. **Never re-anchor data for this.** The bearer is not what is broken.
6. **Positional history?** If this place has a better spot on record, say where.
7. **Warn before a call**, since we know what starting one here is likely to do.
8. **Record the outcome**, so the next visit to this bin is better informed than this one.
