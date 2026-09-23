/**
 * Merge contribution bundles into a publishable shared map.
 *
 * Kept as a pure function with no Cloudflare types in it, so the rule that decides what the world
 * sees can be run and tested by plain node. A threshold that can only be exercised by deploying it
 * is a threshold nobody checks.
 *
 * This is a second implementation of `tools/aggregate_contributions.py`, and two implementations of
 * one rule drift -- so `test/aggregate.test.mjs` runs both against the same fixtures and fails if
 * they disagree. That test is the only thing making the duplication safe.
 */

export const MIN_CONTRIBUTORS = 3;
export const MIN_SAMPLES = 30;

/** Weighted percentile over a value -> weight histogram. */
export function percentile(hist, q) {
  const keys = Object.keys(hist).map(Number).sort((a, b) => a - b);
  if (keys.length === 0) return null;
  let total = 0;
  for (const k of keys) total += hist[k];
  if (total <= 0) return null;
  const target = total * q;
  let seen = 0;
  for (const k of keys) {
    seen += hist[k];
    if (seen >= target) return k;
  }
  return keys[keys.length - 1];
}

function mergeHist(into, src) {
  for (const [value, weight] of Object.entries(src || {})) {
    const v = Number(value);
    into[v] = (into[v] || 0) + Number(weight);
  }
}

/**
 * @param bundles array of parsed contribution documents, one per contributor submission
 * @returns { cells, seen, withheld }
 */
export function aggregate(bundles) {
  const cells = new Map();

  for (const doc of bundles) {
    if (!doc || doc.format !== 'signalscope-contribution') continue;
    // One contributor per BUNDLE per cell, never one per record. A phone that measured the same
    // area across three weeks submits three records for it and is still one person; counting
    // records would let a single contributor satisfy the threshold alone, which defeats the whole
    // control. This is the case the fixtures exercise explicitly.
    const seenHere = new Set();
    for (const rec of doc.records || []) {
      const key = `${rec.area}|${rec.network}|${rec.band}`;
      let cell = cells.get(key);
      if (!cell) {
        cell = { contributors: 0, samples: 0, observedMs: 0, rsrp: {}, sinr: {}, weeks: new Set() };
        cells.set(key, cell);
      }
      if (!seenHere.has(key)) { cell.contributors += 1; seenHere.add(key); }
      cell.samples += Number(rec.samples || 0);
      cell.observedMs += Number(rec.observedMs || 0);
      mergeHist(cell.rsrp, rec.rsrp);
      mergeHist(cell.sinr, rec.sinr);
      if (rec.week) cell.weeks.add(rec.week);
    }
  }

  const published = [];
  let withheld = 0;
  for (const [key, cell] of cells) {
    // Both, never either. Three contributors who each passed through once is not a measurement,
    // and without the sample floor a k of three publishes roughly three times as many thin cells.
    if (cell.contributors < MIN_CONTRIBUTORS || cell.samples < MIN_SAMPLES) { withheld++; continue; }
    const [area, network, band] = key.split('|');
    published.push({
      area: Number(area), network, band,
      contributors: cell.contributors,
      samples: cell.samples,
      observedMs: cell.observedMs,
      rsrpP10: percentile(cell.rsrp, 0.10),
      rsrpP50: percentile(cell.rsrp, 0.50),
      rsrpP90: percentile(cell.rsrp, 0.90),
      sinrP10: percentile(cell.sinr, 0.10),
      sinrP50: percentile(cell.sinr, 0.50),
      sinrP90: percentile(cell.sinr, 0.90),
      weeks: [...cell.weeks].sort(),
    });
  }

  return { cells: published, seen: cells.size, withheld };
}

/** The published document, exactly as the app will fetch it. */
export function sharedMap(bundles) {
  const { cells, seen, withheld } = aggregate(bundles);
  return {
    format: 'signalscope-shared-map',
    version: 1,
    minContributors: MIN_CONTRIBUTORS,
    minSamples: MIN_SAMPLES,
    bundles: bundles.length,
    generated: new Date().toISOString().slice(0, 10),
    cellsSeen: seen,
    cellsWithheld: withheld,
    cells,
  };
}
