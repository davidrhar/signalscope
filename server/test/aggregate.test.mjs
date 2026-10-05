/**
 * The JS aggregator must agree with the Python one, cell for cell.
 *
 * `tools/aggregate_contributions.py` and `server/src/aggregate.js` implement the same rule twice,
 * which is how thresholds quietly diverge: someone tightens k in one place, ships, and the offline
 * path keeps publishing what the live one now withholds. This test runs both over identical
 * fixtures and fails on any disagreement, so the duplication has to stay honest.
 *
 *   node --test server/test/
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { aggregate, sharedMap, MIN_CONTRIBUTORS, MIN_SAMPLES } from '../src/aggregate.js';

const rec = (area, network, band, samples, week = '2026-W38') => ({
  area: String(area), res: 8, network, band, samples,
  observedMs: samples * 1000,
  rsrp: { '-100': Math.floor(samples / 2), '-90': Math.floor(samples / 2) },
  sinr: { '2': Math.floor(samples / 2), '8': Math.floor(samples / 2) },
  week,
});

const bundle = (records) => ({
  format: 'signalscope-contribution', version: 1,
  generated: '2026-09-23', shareRes: 8, records,
});

/** Every withholding path, plus the one that matters most. */
const FIXTURES = [
  // alone in 111 -> fails k. shares 333 (publishes) and 444 (fails n).
  bundle([
    rec(111, '525-10', '40', 500),
    rec(333, '525-10', '40', 200),
    rec(444, '525-10', '40', 5),
    // one phone, one cell, three weeks: must count as ONE contributor, not three
    rec(555, '525-10', '40', 400, '2026-W36'),
    rec(555, '525-10', '40', 400, '2026-W37'),
    rec(555, '525-10', '40', 400, '2026-W38'),
  ]),
  bundle([rec(333, '525-10', '40', 200), rec(444, '525-10', '40', 5)]),
  bundle([rec(333, '525-10', '40', 200), rec(444, '525-10', '40', 5), rec(222, '525-05', '3', 900)]),
  bundle([rec(222, '525-05', '3', 900)]),   // 222 reaches only two contributors -> fails k
];

test('thresholds: only the qualifying cell publishes', () => {
  const { cells } = aggregate(FIXTURES);
  assert.equal(cells.length, 1, 'exactly one cell should clear both thresholds');
  // A STRING, and the test says so. A MapHex id does not survive being a JavaScript number,
  // so an assertion that passes against 333 would pass against a corrupted id too.
  assert.equal(cells[0].area, '333');
  assert.equal(typeof cells[0].area, 'string');
  assert.equal(cells[0].contributors, 3);
  assert.equal(cells[0].samples, 600);
});

test('one phone across three weeks is one contributor, not three', () => {
  const { cells } = aggregate(FIXTURES);
  assert.ok(!cells.some((c) => c.area === 555),
    'a single contributor must never satisfy the contributor threshold alone');
});

test('k and n are both required, not either', () => {
  const { cells } = aggregate(FIXTURES);
  assert.ok(!cells.some((c) => c.area === 111), '500 samples from one person must still fail k');
  assert.ok(!cells.some((c) => c.area === 444), 'three people with 15 samples must still fail n');
  assert.ok(!cells.some((c) => c.area === 222), 'two people with 1800 samples must still fail k');
});

test('agrees with the Python aggregator, cell for cell', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ss-agg-'));
  try {
    const paths = FIXTURES.map((b, i) => {
      const p = join(dir, `b${i}.json`);
      writeFileSync(p, JSON.stringify(b));
      return p;
    });
    execFileSync('python3', [
      new URL('../../tools/aggregate_contributions.py', import.meta.url).pathname,
      join(dir, 'out'), ...paths,
    ], { stdio: 'pipe' });

    const py = JSON.parse(readFileSync(join(dir, 'out', 'shared-map.json'), 'utf8'));
    const js = aggregate(FIXTURES);

    assert.equal(py.minContributors, MIN_CONTRIBUTORS, 'k must match across implementations');
    assert.equal(py.minSamples, MIN_SAMPLES, 'n must match across implementations');

    const norm = (c) => [c.area, c.network, c.band, c.contributors, c.samples,
                         c.rsrpP50, c.sinrP50].join('|');
    assert.deepEqual(
      js.cells.map(norm).sort(),
      py.cells.map(norm).sort(),
      'the two aggregators published different cells',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('names cover the published networks and nothing else', () => {
  const doc = sharedMap(FIXTURES);
  const published = new Set(doc.cells.map((c) => c.network));
  assert.ok(published.size > 0, 'fixture publishes nothing, so this proves nothing');

  // The leading zero is the trap: the app normalises 525-05 to 525-5 before looking it up, and a
  // table keyed the other way misses silently -- which is how Singtel and StarHub spent weeks
  // displayed as bare codes in the app itself.
  for (const plmn of published) {
    const key = `${plmn.split('-')[0]}-${plmn.split('-')[1].replace(/^0+/, '')}`;
    assert.ok(doc.names[key], `no name for published network ${plmn} (looked up as ${key})`);
  }
  assert.equal(doc.names['525-10'][0], 'SIMBA');
  assert.equal(doc.names['525-10'][1], 'Singapore');

  // Only what is in the snapshot. The full table is 3,000-odd entries and sending it would make
  // the names larger than the measurements by an order of magnitude.
  assert.equal(Object.keys(doc.names).length, published.size);
  assert.equal(doc.names['424-2'], undefined);
});

test('a v1 contribution, whose area is a number, is dropped rather than drawn', () => {
  // The id was rounded by JSON.parse before it ever reached storage -- at the magnitude MapHex
  // uses, to the nearest multiple of 1024 -- so it names a hexagon several hundred kilometres
  // from where the measurement was taken. It cannot be repaired, and a measurement published at
  // the wrong place is worse than one not published at all.
  const numeric = bundle([{ ...rec(333, '525-10', '40', 200), area: 333 }]);
  const { cells, dropped } = aggregate([numeric, numeric, numeric]);
  assert.equal(dropped, 3);
  assert.equal(cells.length, 0);
});

test('a real MapHex id survives the round trip intact', () => {
  // The actual value observed on the server, which lost its low bits on the way in.
  const id = '-6341066927791655000';
  const r = (a) => ({ ...rec(0, '525-10', '40', 200), area: a });
  const { cells } = aggregate([bundle([r(id)]), bundle([r(id)]), bundle([r(id)])]);
  assert.equal(cells.length, 1);
  assert.equal(cells[0].area, id, 'every digit, or the hexagon moves');
});

/*
 * Hostile input. There was none of this before, and a red-team pass found four separate ways in
 * about as many minutes -- one of which took the public map down permanently with a single
 * unauthenticated POST, and with it the 30-day deletion promise, because expiry ran only after a
 * successful aggregate.
 *
 * /contribute has no authentication by design: a contribution carries no account precisely so it
 * cannot be tied to one. That makes every field below attacker-chosen, and makes these tests the
 * only thing standing between the public document and whoever wants to edit it.
 */
const hostile = (records) => ({
  format: 'signalscope-contribution', version: 2,
  generated: '2026-10-05', shareRes: 8, records,
});
const honest = () => [1, 2, 3].map(() => bundle([rec(111, '525-10', '40', 50)]));

test('a null record does not take the whole map down', () => {
  // One request did this to the live service: aggregate threw, so the map was never written and
  // -- far worse -- expiry never ran again for anybody.
  assert.doesNotThrow(() => aggregate([hostile([null]), hostile([undefined]), hostile(['x'])]));
  assert.equal(aggregate([hostile([null])]).cells.length, 0);
});

test('a negative sample count cannot erase an area from the map', () => {
  const before = aggregate(honest()).cells.length;
  const after = aggregate([...honest(), hostile([{ ...rec(111, '525-10', '40', 50), samples: -1e9 }])]);
  assert.equal(before, 1);
  assert.equal(after.cells.length, 1, 'one bundle must not withhold an area that qualified');
});

test('one bundle cannot own the published percentiles', () => {
  // The claim being defended is "medians rather than means, so a single spammer moves a
  // published cell very little". That is only true while the adversary does not write the
  // weights -- these are weighted quantiles over a client-supplied histogram.
  const bomb = hostile([{ ...rec(111, '525-10', '40', 50), rsrp: { '-140': 1e18 } }]);
  const out = aggregate([...honest(), bomb, bomb]);
  assert.equal(out.cells[0].rsrpP50, -100, 'the honest readings still decide the median');
});

test('a separator in a key field cannot forge a different cell', () => {
  // Cells are keyed `area|network|band` and split back apart, so a network containing '|'
  // would be republished with somebody else's area and band.
  const forged = hostile([{ ...rec(111, '525-10', '40', 50), network: 'XX|YY' }]);
  const out = aggregate([...honest(), forged, forged, forged]);
  assert.equal(out.cells.length, 1);
  assert.equal(out.cells[0].network, '525-10');
});
