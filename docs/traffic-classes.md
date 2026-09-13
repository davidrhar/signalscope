# Traffic classes: what is running decides what we do

**The same network event is a catastrophe for one kind of traffic and completely invisible to
another.** A 2-second gap destroys a call and is not noticed by Spotify. So "is the network
good?" is the wrong question. The right one is **"is the network good enough for what this phone
is doing right now?"** — and the answer determines which remediation is safe.

This is the single-SIM baseline: it works on every phone, needs no second subscription, and
changes nobody's billing.

---

## The classes

| Class | Latency budget | Jitter | Buffer it holds | How it fails | Deepest gap survivable |
|---|---|---|---|---|---|
| **Cellular voice** (VoLTE/VoWiFi) | <150 ms one-way | <30 ms | ~60 ms jitter buffer | Robotic, then dropped call | **<1 s** |
| **VoIP** (WhatsApp, Signal, FaceTime) | <200 ms | <50 ms | 100–200 ms adaptive | Garbled, then reconnect | **~1–2 s** |
| **Video conferencing** (Teams, Meet, Zoom) | <200 ms | <30 ms | 0.2–1 s adaptive | Video freezes, "Reconnecting…" | **~2–3 s** |
| **Live streaming** | seconds | tolerant | 2–10 s | Rebuffer spinner | ~5–10 s |
| **VOD** (YouTube) | irrelevant | tolerant | 10–40 s | Quality drop, then stall | **~30 s** |
| **Music** (Spotify) | irrelevant | tolerant | tens of seconds to whole tracks | Almost never | **~60 s+** |
| **Background sync** | none | none | n/a | Invisible | Indefinite |

Two things fall out immediately.

**The top three classes cannot be helped by any disruptive action.** Every remediation we have —
re-anchor, pin-to-LTE, transport change — costs at least a second of connectivity. Applying one
during a call *causes* the failure it was meant to prevent. For real-time traffic the only safe
interventions are **preventive, taken before the session starts**, or **protective**, such as
suppressing a Wi-Fi→cellular handover that is about to orphan the sockets.

**The bottom four classes are where we can act freely.** And that gives the design its key idea.

---

## The buffer is our permission to act

> **A remediation that costs 2 seconds is free if something is holding 30 seconds of buffer.**

This inverts the usual framing. Rather than asking "is it safe to act yet?", the app asks "how
much slack does the current traffic have, and does my action fit inside it?"

| Running | Slack | Disruptive remediation |
|---|---|---|
| Voice / VoIP / conferencing | ~1 s | **Forbidden.** Preventive and protective only |
| Video streaming | ~10–30 s | Allowed, and the user will not notice |
| Music | ~30–60 s | Allowed |
| Nothing (screen off, idle) | unbounded | **The ideal window.** Do maintenance here |
| Background sync only | unbounded | Allowed; defer the sync instead |

The consequence is a scheduler, not a trigger: **queue remediations and execute them in the next
window wide enough to hide them.** A route that has been broken for 40 s while the user is in a
call does not get re-anchored at second 41 — it gets re-anchored the moment the call ends, which
is both safer and, for the user, indistinguishable from having worked.

---

## Class-specific optimisation

### Real-time (voice, VoIP, conferencing)

- **Pre-flight.** When a call starts, evaluate the current bearer. If a known-bad handover is
  ~30 s ahead on the predicted path, that is the moment to pin — *at call start*, not mid-call.
- **Freeze the transport.** The dominant real-time failure on this device is the default route
  moving between Wi-Fi and cellular, which orphans unbound sockets. Suppressing that handover for
  the duration of a call is worth more than any radio-layer action.
- **Never re-anchor.** Absolute.
- **Report, don't fix.** For calls, the honest deliverable is often the post-mortem: *"your call
  dropped at 14:23:07 because the bearer was re-established and every connection reset."*

### Buffered media (streaming, music)

This is where pre-emptive optimisation genuinely earns its name, using the geographic prediction
from [`optimisation.md`](optimisation.md):

- **Prefetch ahead of a known-bad zone.** Entering a bin that historically loses the route, with
  a 30 s buffer and 30 s of warning, means the stall can be avoided entirely. We cannot instruct
  the player to buffer, but we *can* avoid degrading the bearer under it and we can defer
  everything else competing for the radio.
- **Hide the remediation.** Re-anchor behind the buffer.
- **Defer background sync** while the buffer is draining in poor coverage.

### Background

Defer to a good bin, a good time, or Wi-Fi. This is the one case where `WorkManager`-style
constraints map cleanly onto what the platform already offers.

---

## Detecting the class — all Tier 0

No special privilege is needed for a usable classifier:

| Signal | API | Gives |
|---|---|---|
| Cellular call in progress | `TelephonyCallback.CallStateListener` | `OFFHOOK` — voice call |
| Audio mode | `AudioManager.getMode()` | `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` (VoIP/conf) |
| **What is playing** | `AudioManager.getActivePlaybackConfigurations()` + `registerAudioPlaybackCallback` | `AudioAttributes` **usage** (`VOICE_COMMUNICATION` vs `MEDIA`) and **content type** (`SPEECH` / `MUSIC` / `MOVIE`) |
| Anything playing at all | `AudioManager.isMusicActive()` | cheap boolean |
| Foreground app | `UsageStatsManager` | app identity — needs a user grant |
| Per-app flows | VpnService (Tier 1) | definitive attribution, later phase |

`getActivePlaybackConfigurations()` is the workhorse: usage plus content type separates a voice
call from a podcast from a film without knowing which app is running, and without any permission
the user must be talked into.

**Classification is a hint, not a fact.** A wrong guess must fail safe — that is, toward
*assuming real-time* and doing nothing. The cost of wrongly acting during a call is a dropped
call; the cost of wrongly not acting during Spotify is a few seconds of degraded audio nobody
notices. Those are not symmetric, and the policy should not pretend they are.

---

## Why this is the right baseline

- It needs **one SIM**.
- It changes **nobody's billing**.
- It works **everywhere**, not only in surveyed bins — the map makes it better but is not required.
- It directly targets the stated complaint: calls, Teams and YouTube dropping.
- Most of it is **Tier 0**; only the remediation itself needs Shizuku, and the scheduling,
  deferral and protective logic do not.
