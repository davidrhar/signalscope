/**
 * One phone is one contributor, however many times it uploads.
 *
 * This is the regression guard for the fault that made the k-anonymity floor decorative: a bundle
 * is the sender's whole history, re-sent daily, and the server used to store each arrival under a
 * fresh random name and count one contributor per stored file. Five days of uploading from one
 * phone published cells as though five people had been there.
 *
 * It runs the real server over a temporary data directory, because the bug lived in the storage
 * layer and not in `aggregate()` -- which was, and still is, correct about the bundles it is
 * handed. A test at the aggregate level would have passed throughout.
 *
 *   node --test server/test/
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'server.js');
const ID_A = 'a'.repeat(32);
const ID_B = 'b'.repeat(32);

const rec = (area, samples = 500) => ({
  area: String(area), res: 8, network: '525-10', band: '40', samples,
  observedMs: samples * 1000,
  rsrp: { '-100': samples }, sinr: { '2': samples }, week: '2026-W40',
});

const bundle = (extra = {}) => JSON.stringify({
  format: 'signalscope-contribution', version: 1,
  generated: '2026-10-02', shareRes: 8, records: [rec(111)], ...extra,
});

async function withServer(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'ss-server-'));
  const port = 20000 + Math.floor(Math.random() * 20000);
  const proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, DATA_DIR: dir, PORT: String(port) },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const base = `http://127.0.0.1:${port}`;
  try {
    for (let i = 0; i < 100; i++) {
      try { await fetch(`${base}/shared-map.json`); break; } catch { await new Promise((r) => setTimeout(r, 50)); }
    }
    await fn(base, dir);
  } finally {
    proc.kill();
    rmSync(dir, { recursive: true, force: true });
  }
}

const post = (base, body) =>
  fetch(`${base}/contribute`, { method: 'POST', headers: { 'content-type': 'application/json' }, body });

/**
 * The published map, rebuilt from what is on disk right now.
 *
 * A POST does not wait for the merge and a GET serves the cached document for an hour, both
 * deliberately -- so a test that simply reads after posting is reading whatever was published
 * before it started. Dropping the cached file forces the next read to rebuild, which is the
 * state these tests are actually about.
 */
const freshMap = async (base, dir) => {
  rmSync(join(dir, 'shared-map.json'), { force: true });
  const d = await (await fetch(`${base}/shared-map.json`)).json();
  return { cells: d.cells, seen: d.cellsSeen, bundles: d.bundles };
};

test('one phone uploading five times is one contributor, not five', async () => {
  await withServer(async (base, dir) => {
    for (let i = 0; i < 5; i++) assert.equal((await post(base, bundle({ submission: ID_A }))).status, 200);

    const { cells, seen, bundles } = await freshMap(base, dir);
    assert.equal(bundles, 1, 'five uploads from one phone must leave one stored bundle');
    assert.equal(seen, 1, 'one area was measured, so one cell should be known');
    // k is 3, so one contributor publishes nothing. That is the whole point: before this, five
    // uploads from this one phone would have published it.
    assert.equal(cells.length, 0, 'a single phone must not be able to clear the floor alone');
  });
});

test('three different phones do publish', async () => {
  await withServer(async (base, dir) => {
    for (const id of [ID_A, ID_B, 'c'.repeat(32)]) await post(base, bundle({ submission: id }));
    const { cells } = await freshMap(base, dir);
    assert.equal(cells.length, 1);
    assert.equal(cells[0].contributors, 3);
    // Samples are each phone's own, counted once -- not one phone's counted three times.
    assert.equal(cells[0].samples, 1500);
  });
});

test('a rotated id retires its predecessor', async () => {
  await withServer(async (base, dir) => {
    await post(base, bundle({ submission: ID_A }));
    await post(base, bundle({ submission: ID_B, retire: ID_A }));
    const { cells, seen, bundles } = await freshMap(base, dir);
    assert.equal(seen, 1);
    assert.equal(cells.length, 0, 'the retired bundle must not still be counted');
  });
});

test('a bundle with no id is still accepted, and stored without one', async () => {
  await withServer(async (base, dir) => {
    // The hand-exported file. It gets a random name, which counts it as its own contributor --
    // the safe way to be wrong about a bundle whose sender is genuinely unknown.
    assert.equal((await post(base, bundle())).status, 200);
    const { seen } = await freshMap(base, dir);
    assert.equal(seen, 1);
  });
});
