# Optimisation: from observation to action

**The diagnostic half answers "why did that break". This is the other half: doing something
about it, ideally before it breaks.** Design only — nothing here is implemented yet.

**Corrected priority.** An earlier draft led with "switch your data SIM" as the headline win.
That was wrong on three counts, and the third is a hazard rather than a weakness:

1. **Most users have one SIM.** A recommendation that requires two is not a baseline feature.
2. **The second SIM is often roaming.** On the reference device it is — permanently.
3. **Recommending a switch onto a roaming SIM can cost the user money.** Roaming data is billed
   differently and sometimes ruinously. An app that silently moves data onto a foreign plan has
   done harm, not optimisation.

So the data-SIM recommendation is **gated, not headline**: offered only when both subscriptions
are non-roaming on home networks, and never surfaced at all otherwise. It is a bonus on a
minority configuration.

**The baseline is single-SIM, and the baseline must be automation of what can be done without
changing who bills the user.** That is what the rest of this document is about.

---

## The escalation ladder

Four rungs. Each is useful alone; each requires more trust and more privilege than the last.
Nothing skips a rung — an action only becomes automatic after it has been shown to work.

| Rung | What it does | Privilege | Consent model |
|---|---|---|---|
| **A · Inform** | Names the cause, shows the evidence | Tier 0 | None needed |
| **B · Recommend** | Names the fix, deep-links to the setting | Tier 0 | User acts |
| **C · Semi-auto** | Offers the action at the moment it would help | Tier 2 | One tap, per instance |
| **D · Auto** | Acts on a policy the user armed | Tier 2 | Armed once, always logged |

### A · Inform

Already the point of the incident timeline. Worth stating that it *is* an optimisation rung:
"calls fail on this cell between 18:00 and 21:00" changes behaviour without any mechanism at all.

### B · Recommend

Every cause in the twelve-cause table maps to a fix class. The recommendations that matter on a
dual-SIM device, roughly in order of expected value:

1. **Traffic-aware remediation** — see [`traffic-classes.md`](traffic-classes.md). The baseline,
   and the only rung that works on every phone.
2. **Disable the idle roaming SIM**, if the DSDS-contention hypothesis survives its A/B. Note
   this *reduces* cost exposure rather than increasing it, unlike switching onto it.
3. **Switch the data SIM** — **only when both subscriptions are non-roaming home networks.**
   Suppressed entirely when either is roaming, because the recommendation could move the user
   onto roaming billing. Never automatic at rung D under any circumstances: a change that alters
   who bills the user is the user's decision, permanently.
3. **Pin to LTE** where NSA anchor churn correlates with route loss.
4. **Private DNS / IPv6 settings** where resolution is the failure.
5. **Turn off aggressive Wi-Fi→cellular handover** where transport thrash is the cause.
6. **Exempt an app from battery optimisation** where Doze is killing its sockets.

Each deep-links to the relevant Settings screen. We cannot set these; we can put the user one tap
from the right page with the evidence in hand.

### C · Semi-auto

The app already knows the moment the action would help. Two shapes:

- **Reactive** — "data has been unvalidated for 45 s, and a re-anchor cleared this 7 of the last
  9 times. Re-anchor?"
- **Pre-emptive** — the geographic case below.

### D · Auto

Only for actions that have earned it, under a policy the user armed explicitly, with every
firing logged and visible.

---

## The geographic, pre-emptive path

This is the part that only works because of the map, and the only lever that acts **before** the
fault rather than after it.

```
position + heading + speed  →  predicted bins over the next ~30 s
        ↓
bin carries outcome class and dominant cause (from adaptive-aggregation.md)
        ↓
if a predicted bin is class 2/5/6 with confidence above threshold
        ↓
act now, while the connection is still healthy
```

Concretely: approaching a bin that historically loses the route on NSA churn, pin to LTE *before*
arriving, and release on the far side. The phone rides through on a stable anchor instead of
thrashing across it.

**Why prediction is tractable here.** We are not forecasting the network. We are looking up what
already happened, repeatedly, in a place we have measured — a table lookup against our own
history, with the map's own confidence encoding already attached. A bin below the evidence
threshold simply does not trigger anything.

**What the prediction must not do.** Never act on a bin the map is unsure about. The
areal-coverage condition in [`adaptive-aggregation.md`](adaptive-aggregation.md) exists precisely
so that unsurveyed ground cannot masquerade as evidence, and an unsure bin is a reason to do
nothing.

### Cost

Prediction needs continuous position, which is the dominant power cost in the whole design
(GNSS at 38 mA against a ~0.6 %/day baseline). So the forecast loop is **off by default**,
**speed-gated** — stationary means no forecasting is needed — and runs only while a foreground
service is already active. Riding the fused `BALANCED` provider is the default; GNSS duty-cycles
only when moving with the screen on.

---

## The closed loop, which is the part that matters

Most "optimisation" features never check whether they helped. This one must, because every
action here is a hypothesis about a mechanism we inferred rather than observed.

**After every action, measure the same bin and the same cause class with and without it.**

- Did validated uptime in this bin improve after pin-to-LTE?
- Did the re-anchor actually restore the route faster than waiting would have?
- Did disabling slot 1 reduce slot 0's service-loss rate?

Then act on the answer. If an action does not help, **demote it** — stop recommending it, and
tell the user it was tried and did not work. A system that can discover its own advice is wrong
is worth far more than one that is confidently wrong forever, and this is the natural home for
the A/B arms already implied by the multi-SIM and firmware hypotheses.

This also disciplines rung D: an action is only eligible for automation once its measured
success rate clears a threshold **on this device**, not because the mechanism sounds plausible.

---

## Guardrails

Non-negotiable, because the remedies are disruptive:

- **Never act during a call.** A data re-anchor mid-call is the cure causing the disease. Calls
  are the primary complaint; breaking one to fix data would be the worst possible failure.
- **Never act while tethering or on an active video session** without explicit confirmation.
- **Rate-limit hard** — a maximum per hour, with exponential backoff when an action does not help.
- **Everything reverts.** Pin-to-LTE carries a timer; no setting is left changed silently.
- **Everything is logged** and visible in one place, with a global off switch.
- **Never act on a prediction alone when the connection is currently healthy and the bin's
  confidence is low.** The default is to do nothing.

---

## What remains impossible

Restating so it is not rediscovered as a feature request:

- **No influencing other apps' networking.** There is no mechanism for a third-party app to
  change how Teams or YouTube use the network. We can improve the bearer underneath them; we
  cannot instruct them.
- **No band locking, no cell locking, no forced handover.** Pin-to-LTE via the preferred-network
  bitmask is the coarsest possible version of this and the only one available.
- **`ConnectivityDiagnosticsManager`** — the API that looks purpose-built for this — is
  carrier-privilege gated and unreachable.

The realistic ceiling is: choose the better bearer, re-anchor a broken one, avoid a known-bad
place at a known-bad time, and tell the user the one thing they can change that we cannot.
