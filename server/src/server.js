/**
 * SignalScope shared map: ingest, aggregate, serve.
 *
 *   POST /contribute         accept one bundle, store it raw
 *   GET  /shared-map.json    serve the aggregation, rebuilding it if stale
 *   GET  /health             liveness, and a count of what is held
 *   GET  /privacy            the app's privacy policy, the URL Google Play links to
 *
 * ## Why rebuilding is lazy rather than scheduled
 *
 * Fly stops a machine with no traffic. A `setInterval` in a stopped machine does not run, so a
 * timer-driven rebuild would quietly stop happening exactly when nobody was looking -- and the
 * served map would silently go stale rather than fail, which is the worse failure. Instead the
 * aggregate is rebuilt on read when it is older than [MAX_AGE_MS], and after an ingest. A machine
 * that sleeps for a week wakes on the next request and serves a current map.
 *
 * ## What is stored
 *
 * The bundle exactly as sent, under a random name. **No client IP, no cookie, no identifier minted
 * on receipt.** A bundle carries no device id and no timestamp finer than an ISO week; that is a
 * property of the app and this server must not undo it by adding one.
 *
 * Raw bundles are deleted after [RAW_RETENTION_DAYS], and only after a successful rebuild -- doing
 * it the other way round risks losing contributions to a failure between the two steps.
 */
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { mkdir, readdir, readFile, writeFile, stat, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sharedMap } from './aggregate.js';

const DATA = process.env.DATA_DIR || '/data';
const RAW = join(DATA, 'raw');
const MAP = join(DATA, 'shared-map.json');
const PORT = Number(process.env.PORT || 8080);

const MAX_BUNDLE_BYTES = 512 * 1024;
const RAW_RETENTION_DAYS = 30;
/** Older than this and a read rebuilds before answering. */
const MAX_AGE_MS = 60 * 60 * 1000;

await mkdir(RAW, { recursive: true });

// Read once at start. It changes only with a deploy, and a policy page that fails to load is
// something Play review notices before we do.
const PRIVACY = await readFile(join(dirname(fileURLToPath(import.meta.url)), 'privacy.html'));

let rebuilding = null;

async function bundlePaths() {
  const out = [];
  for (const day of await readdir(RAW).catch(() => [])) {
    const dir = join(RAW, day);
    for (const f of await readdir(dir).catch(() => [])) {
      if (f.endsWith('.json')) out.push(join(dir, f));
    }
  }
  return out;
}

/** Delete every stored bundle whose file name is one of [names], in any day folder. */
async function removeBundles(names) {
  if (!names.size) return;
  for (const p of await bundlePaths()) {
    const base = p.slice(p.lastIndexOf('/') + 1, -'.json'.length);
    if (names.has(base)) await rm(p, { force: true });
  }
}

async function rebuild() {
  // One at a time. Two concurrent rebuilds would both read the same raw set and race on the
  // write, and the loser's work is wasted rather than wrong -- but the expiry below deletes, so
  // serialising is the safe choice rather than merely the tidy one.
  if (rebuilding) return rebuilding;
  rebuilding = (async () => {
    const paths = await bundlePaths();
    const bundles = [];
    const cutoff = Date.now() - RAW_RETENTION_DAYS * 86400000;
    const stale = [];

    for (const p of paths) {
      const s = await stat(p).catch(() => null);
      if (s && s.mtimeMs < cutoff) { stale.push(p); continue; }
      try { bundles.push(JSON.parse(await readFile(p, 'utf8'))); }
      catch { /* a corrupt bundle is skipped, never fatal */ }
    }

    const map = sharedMap(bundles);
    await writeFile(MAP, JSON.stringify(map));
    for (const p of stale) await rm(p, { force: true });
    return map;
  })().finally(() => { rebuilding = null; });
  return rebuilding;
}

async function currentMap() {
  const s = await stat(MAP).catch(() => null);
  if (!s || Date.now() - s.mtimeMs > MAX_AGE_MS) return rebuild();
  return JSON.parse(await readFile(MAP, 'utf8'));
}

function send(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json',
    'access-control-allow-origin': '*',
    'content-length': Buffer.byteLength(text),
  });
  res.end(text);
}

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    // HEAD is answered as GET; Node drops the body itself. Link checkers -- Play Console's
    // privacy-policy check among them -- probe with HEAD, and a 404 there reads as a dead page.
    const method = req.method === 'HEAD' ? 'GET' : req.method;
    const path = url.pathname.toLowerCase().replace(/\/+$/, '') || '/';

    if (method === 'GET' && url.pathname === '/health') {
      return send(res, 200, { ok: true, bundles: (await bundlePaths()).length });
    }

    if (method === 'GET' && path === '/privacy') {
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'public, max-age=3600',
        'content-length': PRIVACY.length,
      });
      return res.end(PRIVACY);
    }

    if (method === 'GET' && url.pathname === '/shared-map.json') {
      const map = await currentMap();
      const text = JSON.stringify(map);
      res.writeHead(200, {
        'content-type': 'application/json',
        'cache-control': 'public, max-age=900',
        'access-control-allow-origin': '*',
        'content-length': Buffer.byteLength(text),
      });
      return res.end(text);
    }

    if (req.method === 'POST' && url.pathname === '/contribute') {
      const chunks = [];
      let size = 0;
      for await (const c of req) {
        size += c.length;
        if (size > MAX_BUNDLE_BYTES) { req.destroy(); return send(res, 413, { error: 'too large' }); }
        chunks.push(c);
      }
      let doc;
      try { doc = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { return send(res, 400, { error: 'not json' }); }

      if (doc?.format !== 'signalscope-contribution') return send(res, 400, { error: 'wrong format' });
      if (!Array.isArray(doc.records)) return send(res, 400, { error: 'no records' });

      /*
       * One file per submission id, so one phone is one contributor.
       *
       * A bundle is the sender's whole history, re-sent daily. Storing each arrival separately
       * and counting one contributor per file meant a single phone uploading for five days became
       * five contributors for every area it had measured -- clearing the floor of three alone and
       * multiplying every sample count by five. The floor that decides what the world sees was
       * not, in practice, being applied.
       *
       * The id names a sequence of uploads and nothing else: the client mints it from 128 random
       * bits, rotates it quarterly, and never derives it from anything about the device. A bundle
       * without one keeps the old behaviour and gets a random name -- that is the hand-exported
       * file, submitted rarely, and treating it as its own contributor is the safe way to be
       * wrong about it.
       */
      const id = /^[a-f0-9]{32}$/.test(doc.submission ?? '') ? doc.submission : randomUUID();
      const retire = /^[a-f0-9]{32}$/.test(doc.retire ?? '') ? doc.retire : null;

      // The id is the file's identity, not the day it arrived, so a re-upload must displace the
      // earlier one wherever it is filed. Deleting first also means a crash between the two leaves
      // this phone uncounted for one cycle rather than counted twice, which is the better failure.
      await removeBundles(new Set([id, retire].filter(Boolean)));

      // Day folder only so expiry can work by age; the file's mtime is its last upload.
      const day = new Date().toISOString().slice(0, 10);
      await mkdir(join(RAW, day), { recursive: true });
      // The id does not go into storage. It names the file so the next upload can replace it,
      // and is stripped from the contents so a stored bundle holds nothing about its sender.
      const { submission, retire: _retire, ...stored } = doc;
      await writeFile(join(RAW, day, `${id}.json`), JSON.stringify(stored));

      rebuild().catch(() => {});   // not awaited: the contributor should not wait for a merge
      return send(res, 200, { ok: true, records: doc.records.length });
    }

    return send(res, 404, { error: 'not found' });
  } catch (e) {
    return send(res, 500, { error: String(e?.message || e) });
  }
}).listen(PORT, '0.0.0.0', () => {
  console.log(`signalscope shared map on :${PORT}, data in ${DATA}`);
});
