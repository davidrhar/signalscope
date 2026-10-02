/**
 * Regenerate src/plmn-names.json from the upstream MCC/MNC list.
 *
 * Run with `node scripts/build-plmn-names.mjs`. It rewrites the file in place and prints what
 * changed. Nothing calls it at runtime: the generated file is committed, so a deploy never
 * depends on a third party being reachable and the diff of a refresh is reviewable.
 *
 * ## Why this list and not the authoritative one
 *
 * ITU-T E.212 is the authority, published fortnightly in the Operational Bulletin as PDF and
 * HTML. It is the right source and the wrong format: parsing it is a project of its own, and the
 * ITU's own text notes that MNCs are assigned by national regulators who are merely asked to
 * report changes within 90 days -- so it is not more current than a maintained scrape, only more
 * official. This list is MIT-licensed, derived from Wikipedia, and regenerated upstream from the
 * same page it cites.
 *
 * ## Why the key is the brand and not the operator
 *
 * "Etisalat" is what a person sees on a handset; "Emirates Telecommunications Corporation" is
 * what it is called in a filing. The map names networks for people reading a map.
 */
const SOURCE = 'https://raw.githubusercontent.com/cavoq/mcc-mnc-list/master/mcc-mnc-list.json';
const UPSTREAM = 'https://github.com/cavoq/mcc-mnc-list';

import { mkdir, writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Must agree with Networks.normalise in the app, or a lookup silently misses. */
const normalise = (mcc, mnc) => `${mcc}-${String(mnc).replace(/^0+/, '') || '0'}`;

const res = await fetch(SOURCE);
if (!res.ok) throw new Error(`${SOURCE} -> ${res.status}`);
const rows = await res.json();

const out = {};
let skipped = 0;
for (const r of rows) {
  if (!/^\d{3}$/.test(r.mcc ?? '') || !/^\d{1,3}$/.test(r.mnc ?? '')) { skipped++; continue; }
  const name = (r.brand || r.operator || '').trim();
  if (!name) { skipped++; continue; }
  const key = normalise(r.mcc, r.mnc);
  // A code can appear more than once -- a brand that was retired and reassigned, or a test entry
  // beside a live one. An operational network is the one a phone can actually register on, so it
  // wins; otherwise first-seen, which keeps the output stable across refreshes.
  const prior = out[key];
  if (prior && !(prior.s !== 'Operational' && r.status === 'Operational')) continue;
  out[key] = { n: name, c: (r.countryName || '').trim(), s: r.status };
}

const clean = Object.fromEntries(
  Object.entries(out).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => [k, [v.n, v.c]])
);

const doc = {
  _source: UPSTREAM,
  _license: 'MIT (data derived from Wikipedia, CC BY-SA)',
  _generated: new Date().toISOString().slice(0, 10),
  _entries: Object.keys(clean).length,
  names: clean,
};

const here = dirname(fileURLToPath(import.meta.url));
const path = join(here, '..', 'src', 'plmn-names.json');
await writeFile(path, JSON.stringify(doc, null, 0) + '\n');

/*
 * The same table, for the app to carry.
 *
 * The server can only name codes that are IN a snapshot, which are the networks other people
 * contributed -- never the ones on this phone that nobody else has been on. Those are the codes
 * showing as bare numbers on the map's own chips, so the app needs the table itself.
 *
 * Tab-separated rather than JSON: it is read at startup, and 3,000-odd lines of split() is a few
 * milliseconds where the same data through JSONObject is tens of them. Nothing here contains a
 * tab or a newline, and the generator would have to start emitting one for that to matter.
 */
const tsv = Object.entries(clean)
  .map(([k, [name]]) => `${k}\t${name.replace(/[\t\n\r]/g, ' ')}`)
  .join('\n');
const assetDir = join(here, '..', '..', 'app', 'src', 'main', 'assets');
await mkdir(assetDir, { recursive: true });
const assetPath = join(assetDir, 'plmn-names.tsv');
await writeFile(assetPath, `# ${UPSTREAM} -- MIT, from Wikipedia (CC BY-SA) -- ${doc._generated}\n${tsv}\n`);

console.log(`${doc._entries} networks written, ${skipped} rows skipped`);
console.log(`  ${path}`);
console.log(`  ${assetPath}`);
