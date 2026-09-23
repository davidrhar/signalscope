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
import { aggregate, MIN_CONTRIBUTORS, MIN_SAMPLES } from '../src/aggregate.js';

const rec = (area, network, band, samples, week = '2026-W38') => ({
  area, res: 8, network, band, samples,
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
  assert.equal(cells[0].area, 333);
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
