# Adaptive aggregation

**Bins are not uniform. Resolution is chosen per region by how homogeneous the measured
behaviour is, and by how much evidence supports it.**

This supersedes the fixed "H3 res 9" in `coverage-map.md` and the mockup, which now describe
the *storage* resolution and a default *display* resolution rather than the scheme.

---

## Why, on the merits

Licensing is not the reason (see the note at the end). Adaptive resolution is right for four
reasons that stand on their own:

1. **Sample efficiency.** A uniform fine grid produces thousands of bins with n = 1 — each
   individually meaningless. Merging sparse neighbours until a bin carries real evidence turns
   noise into signal. This is also what makes the **n = 1 single-device map work on day one**:
   your first week's data is sparse everywhere, so it renders coarse and honest rather than
   speckled and fake.

2. **Bin size becomes an encoding of confidence.** A large bin means "we know less here, and
   what we know is uniform". A small bin means "we have dense evidence and the behaviour
   genuinely changes over this distance". That is information the viewer reads for free, and
   uniform grids throw it away.

3. **Privacy falls out of the same mechanism** — *for the published map only.* k-anonymity is
   hardest exactly where data is sparse, and merging upward until k ≥ 5 enforces the threshold
   structurally rather than suppressing bins after the fact.

   **The k gate must not govern the local map.** A personal map has one contributor by
   construction, so applying k ≥ 5 there paints every bin grey forever — the device would refuse
   to show the user their own measurements. Local classification is evidence-based and ungated;
   the crowd threshold is evaluated per bin and *displayed* ("would publish: no — needs k ≥ 5"),
   never used to withhold the user's own data from them.

4. **Legibility.** A city rendered as uniform res-9 hexes is visual mush. Merged regions show
   where behaviour actually changes, which is the thing the map is for.

---

## The mechanism

H3 is hierarchical — every cell has exactly one parent and seven children — so merging is a
walk up that tree rather than a clustering problem.

**Storage resolution is set by fix accuracy, not fixed at res 10.** A 65 m cell recorded against
a 100 m fix asserts precision the position does not have; see `data-model.md` §5. Record at the
finest resolution the accuracy honestly supports, and never destroy it — adaptive merging is a
*derived view* computed at publish or render time, preserving the ability to re-analyse later
under a different rule.

Note the asymmetry this creates: merging upward is always possible, splitting downward never is.
A coarse observation is a permanent floor on detail for that sample, which is another reason to
take fix accuracy seriously at capture.

### Merge rule

Walk res 10 → res 6. Merge a parent's children into the parent when **all four** hold, with the
coverage condition evaluated **first**:

| # | Condition | Test |
|---|---|---|
| **1** | **Areal coverage** | **≥ 35 % of the parent's res-10 children have been surveyed at all** |
| 2 | Same outcome class | All surveyed children share the dominant class |
| 3 | Statistically indistinguishable | Wilson score intervals on `validated_frac` overlap pairwise |
| 4 | No child contradicts | No child with n ≥ k sits in a worse class than the parent's |

Stop at res 6. Without a floor a quiet region collapses into one continent-sized blob.

#### Why condition 1 exists, and why it wins

**Found by building it.** Conditions 2–4 say nothing about how many of a parent's children are
*present*. A sparse fringe trivially satisfies "all children agree" — because two of them agree
and the other forty-seven do not exist — and merges unopposed all the way to the floor. The first
working build painted several **~28 km² hexagons** in a confident colour across the western fringe and the
north coast, inferred from a few dozen observations along one road.

That is not aggregation. It is **interpolation across a gap, disguised as a merge rule** — the
map asserting knowledge of ground nobody has driven. And the res-6 floor does not save you: a
floor bounds *coarseness*, not *evidence*.

This condition is **in direct tension with the old "merge upward unconditionally while n < k"
rule, and coverage must win.** A bin that cannot reach k without claiming unsurveyed ground
**stays unpublished**. It does not grow until it qualifies. Privacy thresholds are a reason to
withhold, never a licence to invent.

#### The subscription partition must reach the leaf

**Found by porting this to the device.** `data-model.md` calls PLMN a hard partition for
*merging*. That is not far enough down: binning must be per-subscription at the **leaf** as well.

Reading `radio_sample` across both subscriptions made the serving cell appear to alternate
between two carriers, and the reselection rate read **12.85/min while stationary** — a fabricated
cause 3, invented purely by mixing two SIMs in one bin. Per-subscription it reads **0.70/min**.

A partition applied only at merge time cannot repair a leaf that was already wrong.

#### Overlapping bins are a consequence of accuracy-driven resolution

Also found on device, and not previously addressed. Standing still while fix quality drifts
records the same ground at res 10 and again at res 9 — a small hexagon drawn inside a large one,
both claiming the same place.

**Collapse the finer leaf into the coarser.** This follows from the asymmetry already stated
above: merging up is always possible, splitting down never is. The coarse observation is the one
whose claim is honest.

#### Contributor counts are a set union, never a sum

Also found by building it, and a privacy defect rather than a cosmetic one.

If merging bins **adds** their contributor counts, k-anonymity is clearable **by aggregation
alone**: one person walking a long route contributes to five res-10 bins, which sum to "5
contributors" at res 8 and clear a k = 5 gate that exists precisely to prevent a single person's
track being published.

**Union the contributor sets; take the cardinality of the union.** Never sum. This applies to
every rollup, at every resolution, including the published tiles.

**Wilson intervals, not normal approximation** — at n = 12 the normal approximation on a
proportion near 0 or 1 is badly wrong, and small n is the common case here.

### Merge on outcome, never on signal strength

The same rule that governs colour governs merging. Two adjacent bins at −85 dBm are *not*
mergeable if one loses the route 30 % of the time and the other never does. Merging on RSRP
would rebuild the exact error the project exists to correct, one layer deeper and harder to see.

---

## The failure mode to design against

**MAUP** — the modifiable areal unit problem. Aggregation boundaries can manufacture effects
that are not there and hide effects that are. The specific danger: a merge that averages a
genuinely bad pocket into a large good region, erasing the very thing the user is trying to find.

Three guards:

- **Never merge to hit a target bin count or a rendering budget.** Merge only on homogeneity and
  the k-threshold. Performance pressure is not a merge reason.
- **Never merge across unsurveyed ground** — condition 1 above. This is the guard that stops the
  map inventing coverage it has never measured, and it is the one the first implementation
  missed.
- **The "no child contradicts" condition above** is specifically the anti-erasure guard: a
  well-evidenced bad child blocks the merge outright.
- **Record the merge.** Each published bin carries its resolution, child count, and the rule
  version that produced it. An aggregate whose derivation cannot be reconstructed cannot be
  trusted or corrected.

Corollary: an incident pin is **never** aggregated. "A call dropped here" is a point event, and
merging it into a regional average is precisely how you lose the finding.

---

## On the licensing argument

Recorded because it was considered and rejected, so it does not get re-proposed later.

Aggregation level does not resolve CC-BY-SA obligations. Share-alike attaches to derived
databases, has **no research or non-commercial exemption** (that is CC-BY-NC, a different
licence), and the test turns on whether a **substantial portion** of the source was used as
input — not on how coarse the output is. Non-uniform binning is not a legal transformation.

It is moot regardless: **OpenCelliD is dropped from the design.** Every measurement already
carries a GPS-derived bin, so tower coordinates are a display garnish rather than a dependency.
Removing it removes the obligation, the attribution burden and a network dependency in one move.

If approximate tower positions are ever wanted, they can be *estimated from our own data* —
the centroid of observations of a given CGI, weighted by RSRP, with timing advance as a distance
prior. Less accurate than OpenCelliD, entirely ours, and it improves as the data grows.
