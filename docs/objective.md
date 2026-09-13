# Objective

**Platform: Android only. Deliverable: an installable APK. No root; Tier 0 and Tier 2 only.**

## The goal

A diagnostic app that gives an accurate view of what the radio, the network and the transport
are actually doing — cells, bands, bearers, routes, probes — so that the quality of **calls and
real-time media** can be understood, explained, and where possible improved.

The user-facing problem is not "slow internet". It is that **calls, Teams and YouTube drop and
reconnect**, repeatedly, often while the phone reports full bars. The app exists to explain
each of those events and say what can be done about it.

## The reframe this forces

Real-time media does not fail the way bulk transfer fails.

| | Bulk transfer (downloads, backups) | Real-time media (calls, Teams, YouTube) |
|---|---|---|
| Cares about | Sustained throughput | **Continuity** |
| Survives | Latency, jitter, brief gaps | Almost nothing above ~1 s |
| Killed by | Low bandwidth | Handover gaps, IP change, loss bursts |
| Measured by | Speed test | Speed test measures **none of this** |

A 2-second interruption is invisible in a throughput average and fatal to a call. This is why
speed tests and signal bars both report "fine" during exactly the experience being complained
about. **Continuity, not capacity, is the metric.**

Budgets worth designing against:

- **Conversational voice** — one-way latency < 150 ms, jitter < 30 ms, loss < 1 %.
- **Teams video** — similar, and it renegotiates aggressively when breached, which is the
  "reconnecting…" banner.
- **YouTube** — buffered, so it tolerates latency well but fails on any outage deeper than its
  buffer, then restarts the stream.

## Why they "drop and try to connect" — the mechanism

The reconnect is the tell. Something is destroying the sockets, not slowing them down. Ranked
by likelihood:

1. **Bearer re-establishment changes the IP address.** When the PDN is torn down and rebuilt,
   the phone gets a new address and **every open TCP connection dies instantly**. Teams
   reconnects. This is visible in `LinkProperties` as an address change — cheap to detect and
   almost certainly the single highest-value signal in the whole app.
2. **Radio link failure → RRC re-establishment.** A 1–3 s gap. Deep enough to kill a call,
   shallow enough to be invisible to everything else.
3. **Handover and inter-RAT transitions.** 50–300 ms normally, far worse when the 5G NSA leg is
   added or dropped mid-session.
4. **Wi-Fi ↔ cellular default-route moves.** The phone switches transport; sockets not bound to
   a specific `Network` are orphaned. Classic when walking out of the house mid-call.
5. **CGNAT rebinding.** The carrier's NAT drops an idle mapping; the connection is dead but
   neither end knows until a timeout.

A useful, testable prediction falls out of this: **YouTube should survive some events that kill
Teams**, because QUIC can migrate a connection across an IP change via its connection ID while
TCP cannot. If that asymmetry shows up in the data, it confirms cause #1 outright.

## What "improve or maximise quality" realistically means

Honest about the limits established in the [README](../README.md) — no band locking, no cell
locking, no forced handover:

1. **Explain every drop.** Name the cause, show the evidence. Most of the value is here, because
   the current experience is an unexplained failure with no recourse.
2. **Recommend the specific fix.** Pin to LTE when NSA thrash is confirmed; change Private DNS
   when resolution is the failure; disable aggressive Wi-Fi handover when the transport moves
   are the cause; exempt an app from battery optimisation when Doze is killing its sockets.
3. **Act, where permitted.** Tier 2 (Shizuku) data re-anchor and pin-to-LTE.
4. **Advise on timing and place.** "Calls fail on this cell between 18:00 and 21:00" is
   actionable by a human even when the phone can do nothing.
5. **Produce evidence.** An export a carrier cannot dismiss.

## Success criteria

The app is working if it can answer, for a specific dropped call:

> *"At 14:23:07 the serving cell changed from PCI 411 to PCI 88, the bearer was re-established
> with a new IP 0.4 s later, and every open connection was reset. The radio never dropped below
> −98 dBm — the bars showed full throughout."*

That sentence is the product.

## Scope

**In:** cellular radio and registration, data bearer and IP state, active transport probes,
Wi-Fi *as a transport* (because handoff is a primary cause), per-app flow attribution via
loopback VpnService in a later phase, local history, export.

**Out:** throughput benchmarking as a headline feature (it measures the wrong thing — retained
only as a secondary data point), TLS interception, anything requiring root or carrier signing,
any claim to control the radio.

## Build

Debug APK. The GitHub Actions workflow pattern from the SmartRadio project applies directly —
`gradle/actions/setup-gradle` plus `gradle assembleDebug`, no local Android Studio required and
no wrapper jar needed in the repo.
