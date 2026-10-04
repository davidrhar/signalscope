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

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * code -> [brand, country]. Generated; see scripts/build-plmn-names.mjs.
 *
 * Read rather than imported: a JSON import attribute is a syntax the runtime either supports or
 * rejects at parse time, which turns a Node version difference between here and the container
 * into a module that will not load at all. readFileSync cannot fail that way.
 */
const PLMN_NAMES_DOC = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'plmn-names.json'), 'utf8'),
);
const PLMN_NAMES = PLMN_NAMES_DOC.names;

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
  let dropped = 0;

  for (const doc of bundles) {
    if (!doc || doc.format !== 'signalscope-contribution') continue;
    // One contributor per BUNDLE per cell, never one per record. A phone that measured the same
    // area across three weeks submits three records for it and is still one person; counting
    // records would let a single contributor satisfy the threshold alone, which defeats the whole
    // control. This is the case the fixtures exercise explicitly.
    const seenHere = new Set();
    for (const rec of doc.records || []) {
      /*
       * A numeric area is a corrupted area, and is dropped rather than published.
       *
       * Version 1 of the contribution format sent the id as a JSON number, which this runtime
       * cannot hold: it was rounded on parse and the hexagon it names is several hundred
       * kilometres from where it was measured. Those ids cannot be repaired -- the information is
       * gone -- so the only honest thing is to not draw them. Publishing a measurement at the
       * wrong place is worse than publishing nothing, because nothing is visibly nothing.
       */
      if (typeof rec.area !== 'string') { dropped++; continue; }
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
      /*
       * A STRING, never Number().
       *
       * A MapHex id uses the full 64-bit range and this is JavaScript, where every number is an
       * IEEE-754 double. Number() on one rounds it to the nearest representable double -- a step
       * of 1024 at that magnitude -- which keeps the tag and resolution and destroys the low bits
       * where r lives. Every published hexagon was landing up to several hundred kilometres from
       * where it was measured. It is an opaque key here and nothing in this file does arithmetic
       * on it, so there was never a reason to make it a number in the first place.
       */
      area, network, band,
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

  return { cells: published, seen: cells.size, withheld, dropped };
}

/**
 * Names for the networks in a snapshot, and nothing else.
 *
 * A phone can learn what a network calls itself only by registering on it, which names exactly
 * the networks its owner has used and none of the ones everybody else contributed. The shared map
 * is the one place where that is not enough, so the names travel with the data.
 *
 * Only the codes actually present are sent. The full list is 3,384 entries and a snapshot holds a
 * handful, so shipping all of it would mean the names outweighing the measurements by an order of
 * magnitude for no reader's benefit.
 */
function namesFor(cells) {
  const out = {};
  for (const c of cells) {
    const key = normalisePlmn(c.network);
    if (!key || out[key]) continue;
    const hit = PLMN_NAMES[key];
    if (hit) out[key] = hit;
  }
  return out;
}

/** Must agree with Networks.normalise in the app and with the generator, or lookups miss. */
function normalisePlmn(plmn) {
  const parts = String(plmn ?? '').split('-');
  if (parts.length !== 2) return null;
  return `${parts[0]}-${parts[1].replace(/^0+/, '') || '0'}`;
}

/** The published document, exactly as the app will fetch it. */
export function sharedMap(bundles) {
  const { cells, seen, withheld, dropped } = aggregate(bundles);
  return {
    format: 'signalscope-shared-map',
    version: 1,
    minContributors: MIN_CONTRIBUTORS,
    minSamples: MIN_SAMPLES,
    bundles: bundles.length,
    generated: new Date().toISOString().slice(0, 10),
    cellsSeen: seen,
    cellsWithheld: withheld,
    /** Records from clients that sent the area as a number, which this runtime cannot hold. */
    recordsDropped: dropped,
    cells,
    names: namesFor(cells),
    namesSource: PLMN_NAMES_DOC._source,
  };
}
