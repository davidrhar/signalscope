# Concrete recommendation: what to deploy

**Written after running Phases A and C on hardware. This supersedes the speculative parts of
`forced-reselection.md` and `automatic-actions.md` wherever they disagree.**

---

## 1. What the experiments actually returned

### Phase A — forced reselection: **the lever does not exist on this device**

Ten trials ran. The radio was never cycled, because **`set-allowed-network-types-for-users`
returns success and changes nothing**. Verified independently of the app: 110 consecutive reads of
the allowed-types bitmask spanning a full trial cycle showed **one value and zero transitions**.

The other cycle levers are also closed:

| Lever | Result |
|---|---|
| `cmd phone restart-modem` | **Permission denied** to the shell UID — so denied to Shizuku too |
| `set-allowed-network-types-for-users` | **Accepted, silently ignored.** Exit code 0, no effect |
| `svc data disable/enable` | Works, but rebuilds the PDN only — **cannot change the serving cell** |
| Airplane-mode cycle | Untested: it drops Wi-Fi, and on a Wi-Fi-tethered debug session that is self-defeating. The only remaining candidate |

**Conclusion: forced reselection is not deployable.** Phases B and C *on that mechanism* are moot —
there is nothing to hold, because nothing lands anywhere new. This is a real finding and it closes
a line of work cheaply, which is what a phase-gated experiment is for.

It also produced a lesson worth keeping: **the runner originally trusted the command's exit code
and reported ten "successful" trials that did nothing.** Actuation is now verified by reading the
value back, and a command that succeeds without effect is recorded as `NO-OP` rather than counted
as evidence.

### Phase C — per-cell outcomes: the instrument was missing, and now exists

The first attempt produced `validated = 100 %` for **every cell on every band**, which is
meaningless. The cause was structural: the default route was Wi-Fi throughout, so the validated
flag described the *Wi-Fi* path. **Nothing was riding on the cellular bearer, so there was no
cellular outcome to attribute.**

Passive radio measurement says what the modem *hears*. It cannot say whether data *works*. On a
Wi-Fi-connected phone — which is most phones, most of the time — that difference is total.

Fixed by probing the cellular network **specifically**, via `requestNetwork` + `bindSocket`, while
Wi-Fi remains the default route for everything else. First real cellular outcomes on this device:

```
TLS OK   1127 ms      TLS OK  540 ms      TLS OK  504 ms      TLS OK 577 ms
TLS FAIL 6332 ms  ← SocketTimeoutException: read timed out
```

One failure in eight probes, on a bearer that passive measurement called healthy.

Two implementation bugs this surfaced, both of the silent-failure class:
- `requestNetwork` needs **`CHANGE_NETWORK_STATE`**, undeclared. Without it the probe never ran and
  reported nothing.
- `Network.getAllByName` **takes no timeout** and blocks indefinitely on a non-default network. The
  loop stopped after one call, with no error. Now bounded.

---

## 2. The measured picture at the test location

Per `(site, sector, band)`, from ~4,000 samples:

| site | sector | band | n | RSRP | RSRQ | SINR p50 | SINR sd |
|---|---|---|---|---|---|---|---|
| **52670** | 3 | **B3** *(other SIM)* | 1836 | −86 | **−8** | **12** | 3.11 |
| A | 33 | B40 | 1200 | −95 | −14 | 3 | 2.44 |
| A | 23 | B40 | 607 | −94 | −14 | 1 | 2.79 |
| A | 22 | B40 | 273 | −94 | −14 | 1 | 4.76 |
| B | 22 | B40 | 117 | −96 | −15 | 0 | 1.54 |
| A | 13 | **B8** | 4 | −84 | −13 | 1 | 1.66 |

Three findings:

1. **The data SIM sits at a sector boundary of one mast.** Sectors 22/23/32/33 of site A are
   carriers and panels of the *same* tower. The "cell hopping" is intra-site.
2. **B8 is not the escape it looked like.** 15 dB more RSRP, but SINR of 1 against B40's 1–3. The
   operator's preference for B40 is defensible, and the earlier "coverage layer is better"
   inference was the power-not-quality error this project exists to correct.
3. **The other SIM's network is ~10 dB better SINR at this location** — 12 against 1–3, on a
   healthier RSRQ. That is the largest measured difference in the entire dataset.

---

## 3. What to deploy

Ranked by measured value, not by how clever the mechanism is.

### Tier 1 — deploy now, Tier 0, no privilege

| # | Feature | Why it earns its place |
|---|---|---|
| **1** | **Cellular-bound probing** | The only thing that makes outcomes observable at all. Everything else is scored against it |
| **2** | **The diagnosis** — site/sector decomposition, SINR distributions, no-dominant-server detection, the twelve-cause engine | The measured value of this project. No rootless tool does it |
| **3** | **Traffic-class gating** | Makes every later action safe by construction. A 2 s fix is free behind a buffer and fatal during a call |
| **4** | **Bearer preference for our own traffic** | We cannot move other apps; we can stop competing |
| **5** | **Pre-call warning** | Prevents the failure rather than remediating it |

### Tier 2 — deploy behind Shizuku, with honest labels

| # | Feature | Status |
|---|---|---|
| **6** | `svc data` re-anchor | Works. Correct for a stuck PDN (causes 4, 6, 11). **Useless for radio-layer problems**, and the UI must say so rather than offering it as a general fix |
| **7** | Everything else | **Closed.** No band selection, no cell locking, no forced reselection, no modem restart |

### Do not build

- **Forced reselection.** Measured unavailable. Revisit only if an airplane-mode cycle is tested and
  proves both effective and acceptable — and it drops Wi-Fi, so it is disruptive even when it works.
- **Any automatic radio actuation.** There is nothing left to actuate.

### The recommendation the data actually supports

**The largest available improvement at this location is not an action the app can take. It is
information: the other subscription's network is materially better here.**

That recommendation is **gated**, per `optimisation.md` — the second SIM is roaming, and moving
data onto it could cost real money. So it is offered only when both subscriptions are non-roaming
home networks, and **never automatically**. Where that gate fails, the app's honest output is the
evidence itself: a site, a sector, a band, a distribution, and a time of day — the kind of thing a
carrier cannot wave away.

---

## 4. The through-line

Three separate levers were investigated and closed by measurement: band selection (no API at any
tier), forced reselection (command silently ignored), and per-cell outcome comparison (needed an
instrument that did not exist until now).

What survived is what the project was always best at: **measuring honestly and naming the cause.**
The optimisation is real but small — schedule around a bad bearer, prefer a better one, warn before
committing to a call — and the diagnosis is large. A tool that says *"your carrier's own bar
formula calls this four bars, the SINR is 1 dB, you are at a sector boundary of site A, and your
other SIM is 10 dB better here"* is worth more than one that cycles the radio and hopes.
