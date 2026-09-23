# The shared map backend

Three jobs, deliberately small:

| | |
|---|---|
| `POST /contribute` | accept one contribution bundle, store it raw |
| `GET /shared-map.json` | serve the last aggregation |
| cron, hourly | merge what is stored, publish, expire the raw |

## Deployed

**https://signalscope-map.fly.dev** — Fly.io, Singapore (`sin`), one shared-cpu-1x machine with a
1 GB encrypted volume at `/data`.

```bash
cd server
flyctl deploy          # after changes
flyctl logs            # what it is doing
flyctl status          # whether the machine is up
flyctl ssh console     # look inside /data
```

The machine is allowed to sleep when idle and wakes on the next request. That is safe here only
because the aggregate is rebuilt lazily on read rather than on a timer — a timer in a stopped
machine does not fire, and a map that goes quietly stale is worse than one that goes loudly down.

Nothing uploads by itself. The app shares a file through Android's share sheet; automatic upload is
a separate feature needing its own consent, because sending a file once is not agreement to a
standing upload.

## Testing it without deploying

```bash
cd server && npm test
```

Four tests. Three exercise the thresholds; the fourth runs `tools/aggregate_contributions.py` over
the same fixtures and fails if the two implementations disagree cell for cell.

That last one is the point. The rule is implemented twice — once here in JS for the Worker, once in
Python for the offline path — and two implementations of one rule drift. Someone tightens `k` in
one place, ships, and the other keeps publishing what the first now withholds. The test is what
makes the duplication safe; if it is ever deleted, the duplication stops being safe with it.

## What it stores

The bundle exactly as sent, under a random key. **No client IP, no cookie, no identifier minted on
receipt.** A bundle carries no device id, no account and no timestamp finer than an ISO week — that
is a property of the app, and this Worker must not undo it by adding one.

Raw bundles are deleted after 30 days. The aggregate survives and is the only thing ever served.

## Abuse, honestly

With no identity there is no way to tell one person submitting ten bundles from ten people
submitting one each. That was a deliberate trade in the app — a stable pseudonym across areas is
exactly the linkage a movement trace needs — and the cost lands here: **the contributor threshold
can be gamed by anyone willing to submit repeatedly.**

What this bounds rather than solves:

- a size cap and a shape check on ingest
- medians rather than means, so a single spammer moves a published cell very little
- `k ≥ 3` **and** `n ≥ 30`, both, so thin cells never publish however many bundles arrive

Fixing it properly means attestation — Play Integrity, which proves a real device without
identifying one — and that is a later decision, not an omission nobody noticed.

## Changing the thresholds

`MIN_CONTRIBUTORS` and `MIN_SAMPLES` live in `src/aggregate.js` and are mirrored in the Python
tool. Raising `k` takes effect on the next cron with no app update, which is why three was
defensible as a launch setting — but the app's consent copy says "three different people", so the
copy has to change with it.
