# Multi-SIM handling

**Every earlier doc implicitly assumed one SIM. The reference device is DSDS dual-SIM with a
permanently roaming second SIM, so that assumption is corrected here.**

*Carriers are anonymised throughout. **Carrier A** is the home carrier on the data SIM;
**Carrier R** is the second SIM's home network, which is foreign and never used at home;
**Carrier B** and **Carrier C** are host networks Carrier R roams onto. The findings are what
matter, not whose network they were measured on.*

---

## 1. What the target device actually is

Read from the device over adb, 2026-09-12:

| | Slot 0 | Slot 1 |
|---|---|---|
| subId | 6 | 2 |
| Carrier | **Carrier A**, home network | **Carrier R** (foreign), roaming onto Carrier B / C |
| Band | LTE B40 | LTE B3, `LTE_CA` |
| Roaming | No at home; roams when the user travels abroad | **Yes**, permanently |
| Role | `defaultData` · `defaultVoice` · `defaultSms` · `activeData` | Voice/SMS standby |

`persist.radio.multisim.config = dsds` — **Dual SIM Dual Standby, not DSDA.** Both SIMs are
registered; only one RF chain actively serves data. This is the configuration in which
**tune-away** is real.

### The finding that matters

The subscription log records every service-state change. Over roughly two days:

- **Data SIM (subId 6) lost service 18+ times** — `No service` / `Emergency calls only` —
  roughly **once an hour**, with observed outages of 19 s, 25 s, 51 s and 86 s.
- **Roaming SIM (subId 2) logged 38 transitions**, cycling
  `No service → Emergency calls only → Carrier B → No service → … → Carrier C → …`

This reframes the project's working hypothesis. The original premise was full-bars-no-data.
That still happens, but on this device **the data SIM is frequently losing service outright**.
An 86-second `No service` window is not a subtle diagnostic puzzle; it is the cause of a dropped
call, and it would not show as "full bars" at all.

Two ranked hypotheses, both testable:

1. **Carrier A coverage gaps.** A newer entrant's network is typically thinner than the
   incumbents'. Service loss roughly hourly is consistent with genuine coverage holes.
   (The external review later found the regulator certifies this carrier above 99 % outdoor
   coverage, which weakens this hypothesis — see `research-review.md`.)
2. **DSDS contention from slot 1.** A SIM sitting in `No service` scans aggressively for a
   network. On DSDS that scanning competes for the shared RF chain, stealing time from the data
   SIM. The roaming SIM's 38 transitions are therefore not merely its own problem — they are a
   plausible *contributor* to slot 0's instability.

> **Correction, 2026-09-13 — the service-loss rate above did not reproduce.**
> The app's own collection over 20.2 hours recorded the data SIM leaving service on the PS/WWAN
> domain **once** (965 `IN_SERVICE` against 1 `OUT_OF_SERVICE`), and the roaming SIM four times.
> The "roughly once an hour" figure came from the platform's subscription log over an earlier,
> different period and does not describe this handset now. Hypothesis 1 is weakened further; the
> outages it rested on are not currently happening at anything like that rate.
>
> **Hypothesis 2 was tested, by a narrower intervention than the one proposed below.** Rather than
> disabling slot 1, VoLTE was switched off for slot 1 alone, which stopped a specific retry loop:
> the roaming SIM's IMS bearer was failing roughly every 20–60 s with `IPV6_RS_RA_FAILED`, because
> its home profile requests an IPv6-only IMS bearer while roaming and the host network never
> answers the router solicitation. Once the loop stopped, the data SIM's
> probe failure rate fell from 7.9 % (13/164) to 4.2 % (10/236), one-sided p = 0.09. That is the
> right direction and not significant, and it is confounded: the two windows differ in time of
> day and in where the phone was. Suggestive of DSDS contention, not a demonstration of it.
>
> **One more thing found since:** the collector records a roaming SIM's *home* PLMN
> rather than the network actually serving it. See `spectrum.md` §5.

Hypothesis 2 yields an experiment that can be run today: **disable slot 1 for a day and measure
whether slot 0's service-loss rate falls.** That is exactly the kind of question the app exists
to answer, and it is a controlled A/B rather than a guess.

---

## 2. The Android subscription model

Everything telephony is **per-subscription**. A bare `TelephonyManager` silently serves the
*default* subscription, which is a bug waiting to happen on a dual-SIM device.

```kotlin
val tm = context.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(subId)   // mandatory, always
```

Register a **separate `TelephonyCallback` per active subscription**. N SIMs means N
registrations, N collector instances, N rows per sample tick.

### Subscription inventory

`SubscriptionManager.getActiveSubscriptionInfoList()` (needs `READ_PHONE_STATE`), per entry:

| Field | Why we need it |
|---|---|
| `getSubscriptionId()` | The join key for every telephony row |
| `getSimSlotIndex()` + `getPortIndex()` | **Slot alone is not an identity** — see MEP below |
| `isEmbedded()` | eSIM vs physical |
| `isOpportunistic()` | Carrier-managed secondary data subs behave differently |
| `getCarrierName()`, MCC/MNC, `getCountryIso()` | Carrier identity for the map dimension |
| `getDataRoaming()` | Whether data roaming is permitted on this sub |

### The four "defaults" are different things

```
getDefaultDataSubscriptionId()    which SIM is *configured* for data
getActiveDataSubscriptionId()     which SIM is *currently carrying* data   ← not the same
getDefaultVoiceSubscriptionId()
getDefaultSmsSubscriptionId()
```

`defaultData` and `activeData` diverge during temporary switches (carrier smart-switching, or a
voice call forcing data onto the other sub). **Track both.** On the target device they currently
agree at subId 6.

### eSIM and MEP

Android 13+ supports **Multiple Enabled Profiles**: two eSIM profiles active on one eUICC,
sharing a slot but distinguished by **port index**. So the identity of a SIM is
`(slotIndex, portIndex)`, never slot alone. Any UI or schema keyed on slot will mis-attribute
data on an MEP device. Use `subId` as the key and treat slot/port as descriptive.

---

## 3. Multi-SIM as a *cause* of data interruption

Three mechanisms that do not exist on single-SIM phones. None were in the nine-cause table.

### 3a. DSDS tune-away

On a shared RF chain the modem periodically leaves the data SIM to monitor the other SIM's
paging channel. Normally imperceptible; when the other SIM is *out of service and scanning*, it
is not. Presents as sub-second to few-second data stalls with **no cell change and no signal
change on the data SIM** — which makes it invisible to every existing tool, and easy for us to
identify precisely because of that signature.

**Detection:** data gap on the data sub, with the serving cell and signal steady, correlated in
time with service-state churn or paging activity on the *other* sub. Requires collecting both
subscriptions simultaneously — which is why per-sub collection is mandatory rather than a nicety.

### 3b. Data SIM switching is an IP-change event

When the active data subscription changes, the bearer changes, **the IP address changes, and
every open TCP connection dies.** This is the same socket-killing mechanism as bearer
re-establishment — the thing that makes Teams reconnect — but triggered by SIM switching, which
nobody thinks to look at.

**Detection:** `TelephonyCallback.ActiveDataSubscriptionIdListener` (API 31+) fires exactly on
this transition. Cheap, event-driven, and it pairs directly with the `addressChanged` flag
already in `link_event`. Add it to the collectors.

### 3c. Roaming asymmetry

A roaming sub may have data barred, throttled, or subject to different APN and IMS behaviour
than its home network — and it re-selects networks far more often, as slot 1's 38 transitions
show. Roaming state must be recorded per-sample, not assumed constant.

---

## 4. Consequences for the rest of the design

**Schema.** `subId` is already on every telephony row in `data-model.md`. Add a
`subscription_profile` table (subId, slot, port, carrierName, mcc, mnc, isEmbedded,
isOpportunistic, countryIso) and a `subscription_role_event` row whenever any of the four
defaults or `activeDataSubId` changes. Roles change at runtime; they are events, not config.

**Incidents.** Attribute each incident to the subscription carrying data *at that moment* — which
requires the role history above, not a snapshot.

**The map — an error to avoid.** Measurements must carry a **carrier dimension (PLMN)**, and bins
must never mix carriers. Averaging Carrier A's B40 coverage with Carrier B's B3 coverage in one bin
produces a number describing no real network. Given the adaptive merge rule in
[`adaptive-aggregation.md`](adaptive-aggregation.md), **PLMN is a hard partition: sibling bins on
different PLMNs are never merge candidates**, regardless of how similar their outcomes look.

**Battery.** N subscriptions multiply the passive callback load. Callbacks are cheap, but
`requestCellInfoUpdate` per sub is not — rate-limit per subscription, not globally.

**UI.** Every screen needs a SIM selector, and the Live dashboard should show both subs, since
the diagnostic story here is precisely the *interaction* between them.

---

## 5. Per-SIM profiles — separate analysis, separate views

**Confirmed requirement: subscriptions are never intermingled.** Each active subscription gets
its own profile, and every derived number is computed within one subscription only.

A `subscription_profile` owns:

| | Scoped per subscription |
|---|---|
| **Baseline** | Its own quality distribution — the reference the rest is judged against |
| **Availability** | Which metrics this sub actually reports (on the target device, timing advance is available on one sub and `UNAVAILABLE` on the other — availability is *not* purely a device property) |
| **Bar thresholds** | `CarrierConfigManager` is per-subscription; two SIMs have two different bar scales |
| **Incident stream** | Incidents belong to the sub carrying data at that moment |
| **Coverage bins** | Partitioned by PLMN, never merged across carriers |
| **Derived metrics** | Validated uptime, churn rate, time-to-recover — all per sub |

Aggregating any of these across SIMs produces a number describing no real network. A combined
"your connection is 87 % reliable" across a home SIM and a roaming SIM is meaningless in exactly
the way the signal bar is meaningless.

### The one place they legitimately meet: comparison

Separation means never *averaging* them. It does not mean refusing to show them together —
side-by-side comparison at the same place and time is one of the most useful things a dual-SIM
device can offer, and no single-SIM tool can:

> *At home, the slot-1 network holds a validated route 99.1 % of the time. The slot-0 network
> manages 94.3 %. Your data is on slot 0.*

That is directly actionable — it argues for switching the data SIM, which is a real setting the
user can change. It requires both subscriptions measured independently over the same bins and
the same hours, then displayed adjacently. **Compared, never combined.**

The same applies on the map: a per-SIM layer switcher, with an optional *difference* view
showing which subscription wins in each bin. A difference is a comparison; a blend is not.

### Interaction effects are a third thing

Distinct from both: the DSDS tune-away and contention analysis in §3a is inherently *about* the
pair. It is not an average and not a comparison — it is a correlation between two independently
collected streams, and it is the only analysis that legitimately reads both at once.

## 6. What we cannot do

- **Switching the data SIM** requires `MODIFY_PHONE_STATE` — privileged. Possible at Tier 2 via
  Shizuku; not available to a normal app.
- **Enabling/disabling a subscription** likewise.
- **DSDS vs DSDA** is not cleanly exposed. `getActiveModemCount()` reports how many modems are
  active but does not distinguish standby from dual-active. Here we read it from
  `persist.radio.multisim.config`, which is a system property rather than an API — treat the
  distinction as inferred from observed behaviour, not as a reliable read.
