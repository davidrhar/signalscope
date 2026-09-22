#!/usr/bin/env python3
"""
Merge contribution bundles into a publishable shared map.

    python3 tools/aggregate_contributions.py out/ bundle1.json bundle2.json ...

This is the half of the design that cannot live in the app. A client deciding what is safe to
publish is a client being trusted with a rule it also has an incentive to relax, and the project's
own security review is explicit: the contributor threshold is a server-side control or it is not a
control. The app produces its contribution honestly; this decides what the world gets to see.

WHAT IT ENFORCES

  k >= MIN_CONTRIBUTORS   distinct bundles must have measured an area/network/band
  n >= MIN_SAMPLES        readings between them

Both, not either. Three contributors who each passed through once is not a measurement, and
dropping k from five to three without the sample floor roughly triples how many thin cells get
published -- which is the trade that makes three defensible rather than merely convenient.

WHY MEDIANS COME FROM HISTOGRAMS

Contributions carry histograms, so merging is addition and the median of ten phones' readings is
the true median. Averaging their medians would be a median of medians: not the same number, and
increasingly wrong the more people contribute.

WHAT IS NOT HERE

No de-duplication of one person submitting twice, because contributions carry no identifier and
that is deliberate -- a stable pseudonym across areas is exactly the linkage a movement trace
needs. A duplicate inflates a contributor count by one; a pseudonym would let someone be followed.
The cheaper problem was chosen on purpose, and the honest place to say so is here.
"""
import json
import os
import sys
from collections import defaultdict

MIN_CONTRIBUTORS = 3
MIN_SAMPLES = 30


def merge_hist(into, src):
    for value, weight in src.items():
        into[int(value)] = into.get(int(value), 0) + int(weight)


def percentile(hist, q):
    """Weighted percentile over a value -> weight histogram."""
    if not hist:
        return None
    total = sum(hist.values())
    if total <= 0:
        return None
    target, seen = total * q, 0
    for value in sorted(hist):
        seen += hist[value]
        if seen >= target:
            return value
    return max(hist)


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip())
        return 2

    out_dir, paths = argv[1], argv[2:]
    os.makedirs(out_dir, exist_ok=True)

    cells = defaultdict(lambda: {
        "contributors": 0, "samples": 0, "observedMs": 0, "rsrp": {}, "sinr": {}, "weeks": set(),
    })
    bundles = 0

    for path in paths:
        with open(path) as fh:
            doc = json.load(fh)
        if doc.get("format") != "signalscope-contribution":
            print(f"skip {path}: not a contribution bundle", file=sys.stderr)
            continue
        bundles += 1
        # Counted once per bundle per cell, never once per record: a phone that measured the same
        # area across three weeks is still one contributor, and counting records would let a single
        # person satisfy the threshold alone -- defeating the entire control.
        seen_here = set()
        for rec in doc.get("records", []):
            key = (rec["area"], rec["network"], rec["band"])
            cell = cells[key]
            if key not in seen_here:
                cell["contributors"] += 1
                seen_here.add(key)
            cell["samples"] += int(rec.get("samples", 0))
            cell["observedMs"] += int(rec.get("observedMs", 0))
            merge_hist(cell["rsrp"], rec.get("rsrp", {}))
            merge_hist(cell["sinr"], rec.get("sinr", {}))
            cell["weeks"].add(rec.get("week"))

    published, withheld = [], 0
    for (area, network, band), cell in cells.items():
        if cell["contributors"] < MIN_CONTRIBUTORS or cell["samples"] < MIN_SAMPLES:
            withheld += 1
            continue
        published.append({
            "area": area,
            "network": network,
            "band": band,
            "contributors": cell["contributors"],
            "samples": cell["samples"],
            "observedMs": cell["observedMs"],
            "rsrpP10": percentile(cell["rsrp"], 0.10),
            "rsrpP50": percentile(cell["rsrp"], 0.50),
            "rsrpP90": percentile(cell["rsrp"], 0.90),
            "sinrP10": percentile(cell["sinr"], 0.10),
            "sinrP50": percentile(cell["sinr"], 0.50),
            "sinrP90": percentile(cell["sinr"], 0.90),
            "weeks": sorted(w for w in cell["weeks"] if w),
        })

    out_path = os.path.join(out_dir, "shared-map.json")
    with open(out_path, "w") as fh:
        json.dump({
            "format": "signalscope-shared-map",
            "version": 1,
            "minContributors": MIN_CONTRIBUTORS,
            "minSamples": MIN_SAMPLES,
            "bundles": bundles,
            "cells": published,
        }, fh, indent=1)

    print(f"bundles read      {bundles}")
    print(f"cells seen        {len(cells)}")
    print(f"cells published   {len(published)}")
    print(f"cells withheld    {withheld}  (below k={MIN_CONTRIBUTORS} or n={MIN_SAMPLES})")
    print(f"written           {out_path}")
    if published:
        networks = defaultdict(int)
        for c in published:
            networks[c["network"]] += 1
        print("networks          " + ", ".join(f"{k}:{v}" for k, v in sorted(networks.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
