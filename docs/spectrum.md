# Spectrum, checked against the handset

**How to test a spectrum claim against what a phone actually reports, and what the answer
changes in a diagnosis.**

*This file is deliberately general. The reference handset's own analysis -- which operator, which
country, which masts -- identifies its owner, so it is kept outside the public repository. What
belongs here is the method and the engineering that transfers to any network.*

---

## 1. Method: the device reports channel numbers, and they convert exactly

Spectrum charts, regulator tables and research summaries are all second-hand. The phone is not: it
reports the channel it is actually served on, and each band has a fixed formula from channel to
frequency (3GPP TS 36.101 for LTE, TS 38.104 for NR). `store/Bands.kt` implements them.

| Band | Downlink formula | Example |
|---|---|---|
| B40 TDD | 2300 + 0.1 × (N − 38650) | N = 38750 → 2310.0 MHz |
| B8 FDD | 925 + 0.1 × (N − 3450) | N = 3650 → 945.0 MHz |
| B3 FDD | 1805 + 0.1 × (N − 1200) | N = 1600 → 1845.0 MHz |
| B7 FDD | 2620 + 0.1 × (N − 2750) | N = 2850 → 2630.0 MHz |

Two carriers whose channels place them edge to edge, both reported 20 MHz wide, are a 40 MHz
contiguous holding -- which can be checked against a regulator's assignment to the megahertz.

**Lesson from doing it:** a machine-generated spectrum chart used for this project matched the
device exactly on its largest holdings, and was wrong in its own drawing on another -- its caption
cited the correct frequencies while the picture placed the operator elsewhere in the band. Treat any
chart as a lead. Where it disagrees with the handset, the handset wins.

## 2. What a TDD capacity layer implies

When most of an operator's capacity is TDD (Band 40, Band 38, n40, n78), several things this project
measured stop looking like faults:

- **Idle time concentrates on the TDD band.** An operator whose capacity *is* that band steers idle
  phones onto it by design. There may be no FDD layer of comparable size to move to, so band
  selection is not a lever -- closed for actuation, retained for diagnosis.
- **Uplink is structurally disadvantaged.** The common downlink-heavy frame layout (configuration 2,
  special subframe 7) gives uplink about 20 % of airtime. For the same uplink throughput that is
  roughly 5–7 dB behind an FDD band, so uplink fails first at the cell edge while downlink RSRP still
  looks healthy.
- **"Reliable but slow" is a capacity signature.** A network with little spectrum shows high
  reliability and low throughput together. That profile is closed by spectrum or cell density, not
  by a setting on a phone.

A small uplink/downlink probe will not necessarily see the uplink penalty: an 8 KB transfer that
shares its connection setup between legs attenuates any ratio toward 1. "Not detected at 8 KB" is
not "uplink is fine"; a sustained upload is the test that separates them.

## 3. Hypotheses a spectrum analysis raises

**In-device coexistence at 2.4 GHz.** Band 40 ends at 2400 MHz, where Wi-Fi channel 1 begins. A
handset transmitting near the top of Band 40 while using 2.4 GHz Wi-Fi or Bluetooth is a known
coexistence hazard. How much it matters depends on where the operator's block sits: a block at the
bottom of the band is well clear. Testable: compare SINR and probe outcomes on 2.4 GHz versus 5 GHz
Wi-Fi, and with Bluetooth on versus off.

**Cross-border co-channel interference via atmospheric ducting.** Where a neighbouring country's
operators use the same TDD block across a sea path, warm humid air can form ducts carrying a base
station's downlink 100–300 km -- far past the protection a normal guard period gives (special
subframe 7 protects to about 43 km). It lands in the victim's uplink. The signature is distinctive:
uplink interference highest in the first uplink symbols, worst at night and early morning, affecting
many cells at once, and varying with weather. It is the one mechanism that predicts failures
clustering by **time** rather than by **place**, and it is testable from the phone side by splitting
readings by hour of week and by coastal versus inland location.

**The dormancy finding matches a known operator-side failure class.** "First packet slow or fails
after idle" is RRC idle-to-connected setup, paging and inactivity timers, with operator remedies of
timer and DRX tuning and `RRC_INACTIVE` on 5G standalone. That independently supports the latency
dose-response measured here, and it means the durable remedy is network-side.

**A 5G layer the handset never uses.** An operator can run standalone 5G on a band a given handset
never attaches to. Worth checking, because standalone 5G brings `RRC_INACTIVE`, which shortens exactly
the idle transition measured as the main latency cost.

**Checking SINR is not a modem artefact.** Some modems mis-scale RSSNR and read stuck near 0–3 dB,
which would make a "SINR 0 dB" finding meaningless. Check whether the same modem reports much higher
values on another subscription or at other times. On the reference handset it did, so the low
readings are real.

## 4. Data-model defects, and their status

Raised by an external review of this codebase; each was verified against source before being
accepted.

| Priority | Defect | Status |
|---|---|---|
| P0 | A roaming SIM's network recorded from the card rather than the serving cell | **Fixed.** Serving network from the serving cell, falling back to the registered operator; the card's network kept as a label only. Earlier rows are not relabelled, since which host network served each is not recoverable |
| P0 | Cell identity carried forward with no registered cell, and stamped fresh | **Fixed.** No serving cell means no update to identity or freshness; freshness is the modem's own measurement time, persisted per row as `cellAgeMs` |
| P0 | Band from `getBands()[0]` with no channel derivation; LTE B40 and NR n40 both stored as 40 | **Fixed.** `bandReported` and `bandDerived` stored separately, derived from the 3GPP tables, with LTE and NR labelled apart |
| P1 | NR signal values overwritten by LTE in shared columns | **Fixed.** Separate NR columns and an `nrPresent` flag |
| P1 | Last-known location fixes stamped with the current time; a fixed 120 s match window | **Fixed.** Fixes carry their own measurement time, stale and mock fixes are rejected, and the window scales with speed |
| P1 | Statistics weighted by row rather than by time | **Fixed.** Signal statistics are weighted by the time each reading stands for, capped, with coverage reported; probe failure rates stay per probe by design |
| P2 | Carrier aggregation, neighbour cells, range checks and quality flags, indoor context | **Fixed.** Coastal and underground-rail tagging remain out of scope: they need region-specific data |
