/**
 * SignalScope shared map: ingest, aggregate, serve.
 *
 * Three jobs, deliberately small:
 *
 *   POST /contribute         accept one bundle, store it raw
 *   GET  /shared-map.json    serve the last aggregation
 *   cron                     merge what is stored, publish, expire the raw
 *
 * ## What is stored, and for how long
 *
 * A bundle carries no device id, no account and no timestamp finer than an ISO week -- that is a
 * property of the app, not of this Worker, and this Worker must not add any. So it does not record
 * the client IP, does not set a cookie, and does not mint an identifier on receipt. What lands in
 * R2 is the bundle exactly as sent, under a random key.
 *
 * Raw bundles are deleted after RAW_RETENTION_DAYS. The aggregate is what survives, and it is the
 * only thing ever served.
 *
 * ## Abuse, honestly
 *
 * With no identity there is no way to tell one person submitting ten bundles from ten people. That
 * was a deliberate trade in the app -- a stable pseudonym across areas is exactly the linkage a
 * movement trace needs -- and the cost lands here: the contributor threshold can be gamed by
 * anyone willing to submit repeatedly. What this Worker does is bound the damage: a size cap, a
 * shape check, and medians rather than means, so a spammer moves a published cell very little
 * without submitting a great many bundles.
 *
 * Fixing it properly means attestation (Play Integrity) rather than an identifier, and that is a
 * later decision, not an omission nobody noticed.
 */
import { sharedMap } from './aggregate.js';

const MAX_BUNDLE_BYTES = 512 * 1024;
const RAW_PREFIX = 'raw/';
const MAP_KEY = 'shared-map.json';
const RAW_RETENTION_DAYS = 30;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname === '/shared-map.json') {
      const obj = await env.BUNDLES.get(MAP_KEY);
      if (!obj) return json({ error: 'no aggregation yet' }, 404);
      return new Response(obj.body, {
        headers: {
          'content-type': 'application/json',
          // Public aggregate, cheap to cache, and the cron only republishes hourly.
          'cache-control': 'public, max-age=900',
          'access-control-allow-origin': '*',
        },
      });
    }

    if (request.method === 'POST' && url.pathname === '/contribute') {
      const len = Number(request.headers.get('content-length') || 0);
      if (len > MAX_BUNDLE_BYTES) return json({ error: 'too large' }, 413);

      let doc;
      try {
        doc = await request.json();
      } catch {
        return json({ error: 'not json' }, 400);
      }
      if (doc?.format !== 'signalscope-contribution') return json({ error: 'wrong format' }, 400);
      if (!Array.isArray(doc.records)) return json({ error: 'no records' }, 400);

      // Random key, date-prefixed only so the cron can expire by age. crypto.randomUUID() is not
      // derived from anything about the sender, so two bundles from one phone are not linkable
      // here any more than they are in the app.
      const day = new Date().toISOString().slice(0, 10);
      const key = `${RAW_PREFIX}${day}/${crypto.randomUUID()}.json`;
      await env.BUNDLES.put(key, JSON.stringify(doc), {
        httpMetadata: { contentType: 'application/json' },
      });
      return json({ ok: true, records: doc.records.length });
    }

    return json({ error: 'not found' }, 404);
  },

  /** Merge everything stored, publish, then expire the raw bundles. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(rebuild(env));
  },
};

async function rebuild(env) {
  const bundles = [];
  const stale = [];
  const cutoff = Date.now() - RAW_RETENTION_DAYS * 86400000;

  let cursor;
  do {
    const page = await env.BUNDLES.list({ prefix: RAW_PREFIX, cursor });
    for (const obj of page.objects) {
      if (obj.uploaded && obj.uploaded.getTime() < cutoff) { stale.push(obj.key); continue; }
      const body = await env.BUNDLES.get(obj.key);
      if (!body) continue;
      try { bundles.push(await body.json()); } catch { /* a corrupt bundle is skipped, not fatal */ }
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);

  const map = sharedMap(bundles);
  await env.BUNDLES.put(MAP_KEY, JSON.stringify(map), {
    httpMetadata: { contentType: 'application/json' },
  });

  // Only after the aggregate is safely written. Deleting first would risk losing contributions to
  // a failure between the two.
  for (const key of stale) await env.BUNDLES.delete(key);
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' },
  });
}
