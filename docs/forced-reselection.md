# Forcing reselection: rerolling a cell we cannot choose

**The question this answers: we cannot *select* a cell or a band, so is there nothing to do?
No — we cannot aim, but we can *reroll and stop when we like the result*, and then hold it.**

> **Status, 2026-09-16 — answered, and the implementation is gone.** Phase A ran and reported ten
> successful trials that changed nothing: the landing distribution is degenerate on this network,
> so the reroll has nothing to reroll into. That is the result, not a failure, and it is the whole
> reason the code was removed. `PhaseA.kt` and `PhaseAPanel.kt` were deleted along with the other
> concluded experiments when the app was reduced to an instrument; this document stays as the
> record of what was tried and what came back. Nothing here should be re-implemented without new
> evidence that the distribution is no longer degenerate.

---

## 1. Yes, cycles can be forced. They are not all the same thing

"Cycle" is four different mechanisms with different radio effects. Conflating them is why an
earlier draft of `optimisation.md` treated the data re-anchor as a general remedy.

| Lever | Mechanism | Radio effect | Outage | Reachable |
|---|---|---|---|---|
| Data re-anchor | `svc data disable/enable` | PDN only. **Camping unchanged** | ~2–3 s | Shizuku |
| **Network-selection reset** | `setNetworkSelectionModeAutomatic()` | Full PLMN search → **initial cell selection** | ~5–10 s | Shizuku (`MODIFY_PHONE_STATE`) |
| Preferred-network-type toggle | `setPreferredNetworkTypeBitmask` LTE→other→LTE | Forces LTE detach and re-entry | ~5–15 s | Shizuku |
| Airplane cycle | RF off/on | Full detach, PLMN search, initial selection | ~10–20 s | Shizuku |
| **Manual PLMN selection** | `setNetworkSelectionModeManual(plmn)` | Chooses the **operator**, not the cell | ~10 s | Shizuku |

Two of these were missing from earlier documents and both matter:

- **`setNetworkSelectionModeAutomatic()`** is a lighter way to force a full network search than
  airplane mode — it does not drop Wi-Fi or Bluetooth, and it is a normal API rather than a
  settings poke.
- **`setNetworkSelectionModeManual()`** genuinely *does* let us choose something: the **PLMN**.
  Useless on a single home SIM, but on a roaming subscription that can see several host networks
  it is a real choice between operators. Cost-gated exactly like the data-SIM recommendation.

---

## 2. The insight: initial selection ≠ reselection

This is the part that makes rerolling worth anything.

- In **idle**, the modem performs **priority-based reselection** using `cellReselectionPriority`
  broadcast by the network. The operator sets the capacity layer (B40 here) above the coverage
  layer (B8), so the phone is *pulled* onto B40 and held there. We cannot change those priorities
  at any privilege tier.
- After a detach, the modem performs **initial cell selection** — a different procedure, which
  camps on the first *suitable* cell it finds. Priorities then reassert themselves, but there is
  a window.

So a forced cycle is a **draw from a distribution of landing cells**, not a deterministic return
to the same one. We cannot bias the draw. **We can repeat it, observe the result, and stop when we
like it.** That is rejection sampling, and it is a legitimate engineering answer to "you cannot
choose".

Whether the distribution is actually non-degenerate here is an empirical question — it may land on
B40 every single time, in which case five trials tell us so and the lever is retired for this
location. That is a finding, not a failure.

---

## 3. The second insight: how to *keep* a good landing

A successful reroll onto B8 would normally be pulled back to B40 within seconds by
priority-based reselection. Except:

> **Priority-based reselection runs in RRC_IDLE. It does not run in RRC_CONNECTED.**

In connected mode, mobility is network-controlled handover driven by measurement reports, and
operators generally do not configure a handover *off* the coverage layer while it is serving
adequately. So:

**Reroll → detect what we landed on → if it is the cell we wanted, immediately establish traffic
and hold RRC connected with a keepalive.** The cycle chooses; the keepalive holds.

Both halves use levers we have: the cycle is Shizuku, the keepalive is Tier 0 (bound socket, small
periodic packet). Neither requires anything we have said is impossible.

**Stated as a hypothesis, because it is one.** The network may hand us back immediately, the
keepalive costs battery, and B8 may not be better than B40 once measured — its RSRP is 15 dB
higher here but the few SINR samples we have on it are *worse*, and 900 MHz carriers are narrow
and tightly reused. Nothing here assumes the answer.

---

## 4. As an optimisation problem

This is a **contextual bandit with constraints**, which is the right frame for what the app is
deciding.

**Objectives** — maximise usable-data fraction, minimise interruption count, minimise energy.
**Decision** — at time *t*, in bin *b*, with state *s*: do nothing, reroll, pin, or advise.
**Context** — SINR p50 and variance, server dominance, band, neighbour set, traffic class, bin history.

**Hard constraints, non-negotiable:**

- **Never while real-time media is active.** A 10-second reroll during a call *is* the failure we
  are trying to prevent. The traffic classifier already gates this.
- Rate-limited per hour, with exponential backoff when outcomes do not improve.
- Never when the link is currently healthy.
- Never on battery below threshold.
- Never when no better-band neighbour is visible — a reroll among co-channel siblings is
  motion without progress.

**Reward** — measured over the window *after* the action: validated uptime, probe success, p90
latency, and cell-change count. Explicitly **not** SINR alone, because SINR can improve while the
experience does not.

The policy is learned **per bin**, because the answer is a property of a place. "Rerolling helps
at home" and "rerolling is useless at the office" are both plausible and both actionable.

---

## 5. The experiment that has to come first

Nothing above should ship before this is measured, because every line of it is a hypothesis.

**Phase A — is the landing distribution degenerate?**
Ten forced cycles at this fixed location, idle, no call. Record for each: landing cell (eNB,
sector, band), time to first data, SINR over the following 60 s, and how long before the serving
cell changes again.

*Reads as:* if all ten land on B40, the lever is dead here and we say so. If any land on B8, we
have a probability and the rest of the design becomes worth building.

**Phase B — does a keepalive hold the landing?**
For landings on the preferred cell, alternate: keepalive on, keepalive off. Measure dwell time
before reselection pulls us away.

*Reads as:* the difference in dwell time is the entire value of the pin.

**Phase C — is the preferred cell actually better?**
Per-band outcome accounting, which is the prerequisite for judging A and B at all: validated
uptime, probe success, p90 latency and uplink/downlink asymmetry, split by `(eNB, sector, band)`.

*Reads as:* if B8 is not better here, A and B were solving the wrong problem — and that is worth
knowing before automating anything.

**Blocks must interleave**, never run in sequence: interference is load-driven and load follows
the clock, so a sequential comparison measures the evening rush and calls it a treatment effect.
