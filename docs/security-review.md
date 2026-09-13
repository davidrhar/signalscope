# Security review

Pre-publication audit of the SignalScope Android app: secrets, privacy, Android attack surface,
code vulnerabilities, design-level abuse, and supply chain. Conducted against the working tree and
the full git history, with the app built, installed and exercised on a physical handset to confirm
both the findings and the fixes.

Read as a hostile reader would. Where the code and the design documents disagree, the code wins
and the disagreement is itself a finding.

---

## Verdict

**Safe to publish publicly — no blocking items in the code or the git history.**

The two conditions below are not code defects and not blockers; they are the last things to settle
before the repository goes public.

1. **Residual identification in prose and demo data.** The concurrent anonymisation pass has
   already removed the carrier names, the PLMN and the handset model — the material that actually
   fingerprinted a person. Three residues remain, all of them city- or class-level rather than
   personal, and all in files this pass does not own:
   - The SoC family is still named in `README.md:6, 40` and `docs/device-findings.md:3`.
   - The author's city is named in `docs/research-review.md` (four times, in a regulatory context
     where it is arguably load-bearing) and in `mockups/coverage-map-prototype.html:272`.
   - `mockups/coverage-map-prototype.html` hardcodes **12 coordinate pairs** at ~11 m precision
     (e.g. line 521-522) anchoring its demo scenarios to named public landmarks and an expressway
     corridor in that city. These are demo anchors on public infrastructure, **not** a personal
     trace — but they place the author's city unambiguously, which is worth a conscious decision
     rather than an oversight.

   None of this is exposure in the sense the rest of this document uses the word. It is the kind of
   detail that is easier to settle now than after the first clone.
2. **No `LICENSE` file.** A public repository with no licence grants no rights, and the map layer
   carries an ODbL obligation the app already honours in its UI. Pick a licence before publishing
   rather than after.

Nothing else gates release. Specifically:

- **No secret, key, token, keystore or credential has ever been committed** — verified over every
  commit and every blob, not just the working tree.
- **No collected data leaves the device**, and there is no code path that could send it anywhere.
- **The central privacy claim in `data-model.md` §5 is true as implemented** — verified in the
  source *and* against the live on-device schema. Its caveats are documented as finding P1 below.

---

## 1. Secrets and credentials — clean

Method: `git log --all -p` across all 27 commits, pattern-matched for API keys, tokens, bearer
credentials, private-key headers, cloud access-key formats, keystore/signing directives, and
personal identifiers; plus the complete added-path list across all history.

| Check | Result |
|---|---|
| `local.properties` | Present in the working tree, **never committed**. Ignored from the first commit. |
| Keystores / `*.jks` / `*.keystore` | None, in any commit. Ignored from the first commit. |
| `signingConfig` / `storePassword` / `keyAlias` | Never appeared in any build file. |
| API keys, tokens, bearer credentials | None. The map stack is deliberately keyless — see below. |
| APK/AAB binaries | None committed. |
| Grep hits for "token" | All of them prose: the *design* for a per-bin HMAC pseudonym in `coverage-map.md`, and UI colour "tokens". No key material. |

**The absence of keys is structural, not luck.** `map-stack.md` selected OpenFreeMap precisely
because it needs no account and no API key, so there has never been a credential to leak. That
decision is worth keeping when the crowdsourcing backend lands.

One minor, non-blocking note: every commit is authored with a machine-local email address, which
publishes a local username and machine name. Harmless, but if that is not wanted, it must be
rewritten **before** the first push — history cannot be quietly amended afterwards.

---

## 2. Privacy and PII

This is the highest-stakes area and it is, on the whole, handled better than most apps that touch
this data. The findings are about the gap between "no coordinates are stored" and "there is no
movement trace", which are not the same statement.

### P0 — Verified: the binning claim in `data-model.md` §5 is true

> *"Bin at write time, discard the raw fix… the database never contains a precise movement trace."*

**Verified in code, and independently against the live database on the device.**

`collect/MapLocationCollector.kt:139-185` (`onFix`) is the only place a `Location` object is ever
seen. It reads accuracy, derives a bin, and writes `MapFix(elapsedNanos, wallMillis, binId,
resolution, accuracyM, speedMps)` — `store/MapBins.kt:29-39`. `loc.latitude` / `loc.longitude`
reach exactly two calls: `MapHex.resForAccuracy` and `MapHex.latLngToCell`. Neither retains them.
The in-memory `FixState.lat/lng` (`MapLocationCollector.kt:67-68`) holds the **bin centre**, not
the fix, so even a heap dump yields no finer position than the bin.

The live schema pulled off the handset confirms it:

```
CREATE TABLE `map_fix` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `elapsedNanos` INTEGER NOT NULL, `wallMillis` INTEGER NOT NULL, `binId` INTEGER NOT NULL,
  `resolution` INTEGER NOT NULL, `accuracyM` REAL NOT NULL, `speedMps` REAL)
```

No latitude column, no longitude column, in either database. The doc is not aspirational here.
Above the 300 m accuracy ceiling nothing is stored at all (`MapHex.kt:267-273`), as specified.

### P1 — The database does contain a movement trace; it is coarse, not absent *(high)*

The claim that survives audit is *"no precise movement trace"*. The claim a hostile reader will
test is *"no movement trace"*, and that one is false.

- `map_fix` writes a row on **every bin change** and at least **once a minute** while the Map tab
  is open (`MapLocationCollector.kt:56-60, 168-171`). Rows are `(bin, timestamp, speed)`, ordered.
  That is a movement trace by any ordinary reading — binned, but a trace.
- The finest bin is **res 10, ~65 m edge** (`MapHex.kt:64, 76`), chosen whenever the fix is
  accurate to 40 m or better. A 65 m hexagon over a residential street identifies a dwelling for
  most purposes. During this review a single res-9 bin (~174 m) rendered over a named residential
  street and was more than specific enough to place the device.
- `speedMps` is stored per fix, which adds a mobility signature on top of the position sequence.
- **The stronger trace does not need location permission at all.** `radio_sample` stores
  `servingCi`, `servingTac`, `servingPci`, `servingArfcn`, `mcc`, `mnc` against `wallMillis` on
  every signal-strength callback (`store/Db.kt:6-32`). Serving-cell identity over time *is* a
  movement history at cell granularity, and it is collected under `READ_PHONE_STATE` whether or
  not the user ever grants location. Joining it to `map_fix` on `elapsedNanos` — which the schema
  is explicitly designed to make easy (`data-model.md` §2) — reconstitutes an annotated trace.

**Not a defect; a documentation defect.** The mechanism is sound and the choice to bin at write
time genuinely reduces exposure. The sentence that oversells it is the problem.

*Fix required (design/docs, not applied — `docs/*.md` is out of scope for this pass):* soften §5
to the claim the code actually supports — *"the database contains no raw coordinates; what it does
contain is a binned position history plus a cell-identity history, and both are movement data"* —
and state the res-10 floor in metres so a reader can judge it.

### P2 — Retention was unbounded, contradicting the stated policy *(high — FIXED)*

`data-model.md` §7 promises *"keep full resolution 7 days… drop raw at 30 days."* **Nothing in the
app deleted anything.** Both databases grew for the life of the install, so the movement history in
P1 was unbounded, and every downstream threat — a device transfer, a forensic image, a stolen
unlocked handset — scaled with install age rather than with a fixed window.

**Fixed.** Added `store/Retention.kt`: a parameterised `DELETE` sweep across `radio_sample`,
`registration_event`, `link_event` and `map_fix` at the documented 30-day cut-off, invoked on every
collector start (`collect/CollectorService.kt:63`). It never throws — a failed sweep must not take
the collector with it. The rollup half of §7 is still unimplemented; the *drop* half is the half
that bounds exposure, so it ships first.

### P3 — Cloud backup was off, device-to-device transfer was not *(medium — FIXED)*

`android:allowBackup="false"` was already set, which is the right call and blocks cloud backup. It
does **not** block Android 12+ device-to-device transfer: `<device-transfer>` defaults to including
the entire app data directory. Moving to a new handset would have carried `signalscope.db`,
`signalscope-map.db` and the `device_profile` preferences — cell identities, carrier identity, IP
addresses and the binned movement history — onto the new device silently.

**Fixed.** Added `app/src/main/res/xml/data_extraction_rules.xml` excluding every domain from both
`<cloud-backup>` and `<device-transfer>`, wired via `android:dataExtractionRules` in the manifest.

### P4 — Data does leave the device, once: basemap tiles *(medium — accepted, now documented)*

The README's "fully local, nothing leaves the device" framing has one real exception.
`ui/MapScreen.kt:59` points MapLibre at `https://tiles.openfreemap.org/styles/dark`. Every pan and
zoom issues tile requests, so **OpenFreeMap (and anyone on the path who sees the TLS SNI) learns
that this IP address is looking at these map tiles**, which is a coarse but genuine location
disclosure. MapLibre also caches tiles in its own unencrypted SQLite file inside app data, which
records which areas were viewed.

This is not a leak of *collected* data — no radio sample, bin or identifier is ever transmitted —
and any basemap has this property unless tiles ship in the APK. It is disclosed here because
"nothing leaves the device" should not be read as "no network activity".

*Mitigation applied:* the `INTERNET` permission now carries a manifest comment naming the single
use, and a network security config pins the app to system CAs with cleartext off, so a future
dependency cannot quietly add plaintext traffic of its own.
*Fix recommended (not applied):* say this plainly in the README's privacy section — one sentence —
and note that the existing "No basemap" toggle is also a privacy control, since it stops tile
requests entirely.

### P5 — Logging and the notification are clean *(informational)*

Audited deliberately, because this is where identifiers usually escape into `logcat`, which any app
with the right tooling or any connected host can read.

- **One** logging call exists in the entire app: `MapLocationCollector.kt:183`, `Log.w(TAG, "fix
  write failed", e)` — a failure path, carrying no position and no identifier. There is no other
  `Log.*`, no `printStackTrace`, no `println`.
- The foreground-service notification text is `"collecting"` or `"collecting · no location
  permission"` (`CollectorService.kt:106`). No carrier, no cell, no position. Confirmed on device:
  the posted notification is `vis=PRIVATE`, so it is hidden on a secure lock screen.

### P6 — On-screen identifiers and screenshot exposure *(low)*

The Live tab renders `ci`, `tac`, `pci`, `arfcn`, local IPv4/IPv6 and the baseband version
(`MainActivity.kt:300-326, 404`). That is the app's purpose and it is all first-party data about
the user's own device. Worth knowing: the window has no `FLAG_SECURE`, so these appear in
screenshots, screen recordings and the recent-apps thumbnail. For a diagnostics tool whose
screenshots are meant to be shareable, `FLAG_SECURE` would be the wrong trade — noted, not
recommended.

### P7 — The k-anonymity story is designed but not yet load-bearing *(informational)*

`MapBinBuilder` evaluates `K_ANON = 5` and `N_CROWD = 30` and exposes `Bin.publishable`
(`store/MapBins.kt:154-155`), and the merge walk unions contributor **sets** rather than summing
counts — which is the correct primitive, and prevents one person walking a long route from
"becoming" five contributors (`MapBins.kt:90-93`).

But `DEVICE_CONTRIBUTOR = "self"` (`MapBins.kt:533`) is a constant, there is one contributor by
construction, and **no measurement has an upload path**, so none of it is enforcing anything yet.
(Since the uplink-asymmetry probe landed there is one outbound POST, of 8 KB of fixed-seed
padding to a speed-test endpoint. It carries no collected data, no identifier and nothing about
the user, so the claim above is about measurements specifically rather than about bytes.) The HMAC
pseudonym scheme in `coverage-map.md` is unimplemented. This is honest — the code says so in its
own comments — but the README should not imply k-anonymity is in force today. See R3 for what
happens when the backend arrives.

---

## 3. Android attack surface

### A1 — `ACCESS_BACKGROUND_LOCATION` was declared and never used *(medium — FIXED)*

The manifest declared background location. Nothing requested it: `MainActivity.kt:74-82` asks only
for `READ_PHONE_STATE`, `ACCESS_FINE_LOCATION` and `POST_NOTIFICATIONS`, and
`MapLocationCollector` is foreground-only by construction — it starts when the Map tab is composed
and stops when it leaves (`MapScreen.kt:99-102`). `CollectorService` never requests a fix at all.

So the declaration bought no capability and cost the user a permission they cannot reason about,
plus a background-location declaration on store review. **Removed**, with a comment recording why,
and what would have to change to reintroduce it honestly.

Every remaining permission is used and justified; the full set is now annotated in the manifest.

### A2 — `PendingIntent` construction *(low — FIXED)*

`CollectorService.kt:161` already used `FLAG_IMMUTABLE`, which is the flag that matters — a mutable
`PendingIntent` handed to the notification shade is a token whose component any holder can rewrite,
the classic intent-redirection primitive. Two smaller problems were fixed alongside:

- The target was resolved by `Class.forName("com.signalscope.MainActivity")` — a string reference
  to a class we can name directly, which throws `ClassNotFoundException` under any rename or
  aggressive shrinking, **inside `onCreate`, where it kills the service**. Now
  `MainActivity::class.java`, an explicit component that can resolve to nothing else.
- Added `FLAG_UPDATE_CURRENT` so the single instance stays current rather than leaving stale extras
  behind.

### A3 — Deep links: no intent-redirection risk *(informational)*

`collect/ActionDeepLinks.kt` was audited specifically for redirection. It is safe, and not by
accident:

- Every intent is built from **compile-time constants** (`ActionDeepLinks.kt:48-102`). No component,
  action, package or URI is ever taken from an incoming intent, a file, the network, or any other
  untrusted source.
- `MainActivity` reads **no intent extras at all** — there is no `getIntent()` usage anywhere, so
  there is no data to redirect.
- `resolve()` checks each candidate against the package manager and **skips explicit components
  that are not exported** (`ActionDeepLinks.kt:107-117`), which is a correctness check most apps
  skip.
- `open()` catches `Throwable` around `startActivity` (`ActionDeepLinks.kt:122-132`).

The one theoretical weakness is inherent to deep links: `component(SETTINGS, ...)` targets
`com.android.settings` by package name, so on a device where that package is not the real Settings
app, the intent goes wherever it resolves. It carries no data and requests `ACTION_MAIN`, so the
worst case is an unexpected activity opening. Not worth fixing.

### A4 — Component export and transport *(FIXED / verified)*

| Item | State |
|---|---|
| `MainActivity` | `exported="true"` with only MAIN/LAUNCHER. Required for a launcher icon; no data filter, no extras read. Correct. |
| `CollectorService` | `exported="false"`, and `onBind` returns null. Correct. |
| Receivers / providers / WebView | **None.** No `WebView`, no `FileProvider`, no content URI, no exported receiver, no file export path anywhere in the app. |
| `android:debuggable` | Never set in the manifest; supplied per build type as it should be. |
| `usesCleartextTraffic` | Was unset (defaulting to off at targetSdk 36). **Now explicit `false`.** |
| Network security config | Was absent. **Added**: cleartext off, system CAs only. |

---

## 4. Code vulnerabilities

### C1 — Injection: no path found *(verified clean)*

- **SQL.** `store/IncidentStore.kt:77-131` builds three queries with static SQL and binds the only
  variable (`since`) as a parameter. `store/MapBins.kt:254-291` reads through
  `openHelper.readableDatabase` with **fully static** statements — no interpolation at all. The new
  `Retention.sweep` follows the same rule. There is no string-concatenated SQL in the app.
- **The map agent's hex code.** `store/MapHex.kt` is pure integer and floating-point geometry over
  a bit-packed cell id. No parsing, no string handling, nothing attacker-reachable.
- **GeoJSON.** `MapBins.kt:777-807` assembles GeoJSON by hand, which is exactly where a hostile
  carrier-supplied string (a carrier name containing a quote, say) would break out. It does not:
  every emitted value is a number formatted `Locale.US`, or a colour from a compile-time palette.
  **No database-derived string reaches the JSON.** The `Locale.US` formatting is itself a
  correctness guard the comment explains well.
- **JSON parsing.** `store/DeviceProfile.kt:146-220` parses only its own `SharedPreferences` blob,
  through `org.json` with `opt*` accessors and a `runCatching` wrapper. Input is first-party and
  malformed input degrades to null.

### C2 — `NullPointerException` on a device with no telephony *(medium — FIXED)*

`TelephonyCollector.tmFor()` was `baseTm!!`. On a handset where `getSystemService(TelephonyManager)`
returns null — a Wi-Fi-only tablet — that threw `KotlinNullPointerException` out of `registerFor`,
which runs inside `CollectorService.onCreate`, **killing the process on first launch** with the
foreground notification already posted.

**Fixed** (`TelephonyCollector.kt:75-84`): `tmFor` returns `TelephonyManager?` and every call site
handles null. Both collectors' `start()` calls are additionally wrapped at
`CollectorService.kt:69-70`, and `onDestroy` now guards its `stop()` calls, so a collector that
cannot start never takes the service down with it.

### C3 — Unhandled exception in a coroutine takes down the process *(medium — FIXED)*

`ActionSelfTest` constructed an `AudioTrack` outside its `try` block, in a scope built as
`CoroutineScope(Dispatchers.IO)` with no `SupervisorJob` and no handler. `AudioTrack`'s constructor
raises `IllegalArgumentException` / `UnsupportedOperationException` where the format or session
cannot be honoured, and an exception escaping a plain `launch` reaches the thread's default handler
and **terminates the process**. This was the only uncaught throw found in the app.

**Fixed** (`ActionSelfTest.kt:36-42, 67-82`): the scope gains `SupervisorJob` and a
`CoroutineExceptionHandler`, and the constructor is wrapped — a failed self-test now costs a line
in the action queue rather than the app.

### C4 — Unguarded derivation on the Timeline tab *(medium — FIXED)*

`TimelineScreen.kt:119-125` ran `IncidentStore.load` + `IncidentEngine.analyse` inside a
`LaunchedEffect` with no error handling. A corrupt or mid-migration database throws
`SQLiteException`, and an unhandled throw inside a `LaunchedEffect` takes the activity down.
`MapBinBuilder.build` already fails to an empty model for precisely this reason
(`MapBins.kt:335-345`); the timeline did not.

**Fixed**: wrapped in `runCatching`, keeping the last good result on screen instead of crashing to
the launcher.

### C5 — Lost-update races on shared live state *(medium — FIXED)*

`LiveState` is written from at least three threads: the telephony collector's single-thread
executor, connectivity's binder callbacks, and `ActionTrafficClassifier`'s main-looper posts.

- `LiveState.updateSim` did `sims.value = sims.value.toMutableMap()...` — a read-modify-write with
  no atomicity. Two callbacks landing between the read and the write silently dropped a field: an
  RSRP that never updated, a `cellUpdates` count that lost increments. **Fixed**
  (`LiveState.kt:82-84`) using `MutableStateFlow.update`, which retries on a concurrent write.
- `ConnectivityCollector`'s `lastV4` / `lastV6` / `addressChanges` were plain mutable fields
  compared and incremented from two different binder callbacks. `addressChanged` is described in
  `data-model.md` §3 as *"the highest-value single field in the schema"*, so a lost increment is a
  missed diagnosis. **Fixed** (`ConnectivityCollector.kt:90-107`): the whole read-compare-write runs
  under one lock.

These are data-correctness bugs rather than crashes, which is what made them worth fixing rather
than merely noting.

### C6 — Reflection on `getNrState` *(informational — correct as written)*

`TelephonyCollector.kt:184-188` reads `NetworkRegistrationInfo.getNrState()` reflectively, inside
`runCatching`, storing null on failure. This is the right shape for a hidden-API read: on Android
16 the call is blocked by the hidden-API policy and simply yields null, with no crash, no log spam
and no fallback that invents a value. The comment says so. No change needed. It is worth noting for
a public reader that this is a *read* of a blocked getter, not a bypass — the app makes no attempt
to defeat the hidden-API restriction.

### C7 — `CellInfo.UNAVAILABLE` sentinel handling *(verified correct)*

`nz()` / `nzL()` (`TelephonyCollector.kt:89-90`) map `Integer.MAX_VALUE` and `Long.MAX_VALUE` to
null, and every affected column is nullable, honouring `data-model.md` §3's *"store `null`, never
`0`, and never a sentinel."* Sampled the call sites: `rsrp`, `rsrq`, `rssnr`, `cqi`,
`timingAdvance`, `pci`, `ci`, `tac`, `arfcn` all pass through it. Consistent.

### C8 — Resource lifecycle *(mostly correct; two residual notes)*

Correct: `TelephonyCollector.stop()` unregisters every callback and shuts its executor;
`ConnectivityCollector.stop()` unregisters both network callbacks; `ActionTrafficClassifier` is
reference-counted and unregisters all three listeners; `MapScreen` drives the full `MapView`
lifecycle and calls `onDestroy` on dispose (`MapScreen.kt:154-171`); `MapLocationCollector.stop()`
removes the location listener on dispose.

Residual, both low and both left alone deliberately:

- `MapLocationCollector.executor` (`MapLocationCollector.kt:79`) is never shut down. It belongs to
  a process-lifetime `object`, so this is a permanently-idle thread, not a leak that grows.
- `MapScreen` does not forward `onLowMemory` / `onSaveInstanceState` to the `MapView`. The effect is
  a missed cache trim under memory pressure, not a leak.

---

## 5. Red-team: attacking the design

### R1 — A malicious app on the same device *(low, as shipped)*

The realistic reader question: can another app read the movement history?

- Both databases sit in the app's private data directory, mode `0660`, owned by the app's UID, in a
  `0771` directory — verified on device. Another app cannot read them without root or an exploit.
- There is no exported provider, no exported receiver, no file export, and the one exported
  component ignores its intent entirely, so there is no IPC surface to feed.
- The realistic paths are therefore: (a) a rooted or exploited device, (b) a backup or transfer —
  **closed by P3**, (c) physical access to an unlocked handset with a debuggable build installed,
  which is a property of debug builds generally.

The residual: `radio_sample` grows without the user thinking of it as location data, because they
granted "phone state", not "location". P2's retention sweep bounds it; the README should say it.

### R2 — A hostile network feeding crafted values *(low)*

A rogue base station or a hostile SIM controls `mcc`, `mnc`, carrier name, `ci`, `tac`, `pci`,
`arfcn`, reject causes and the vendor's `SignalStrength.toString()`. Traced each:

- Numeric fields land in nullable integer columns and flow into arithmetic that is range-clamped
  (`coerceIn`, Wilson intervals) — extreme values distort a classification, they do not escape it.
- **Strings never reach an interpreter.** They are not concatenated into SQL, and C1 establishes
  they never reach the GeoJSON either. The worst case is a nonsense carrier label in the UI, which
  the UI already truncates (`MainActivity.kt:127`).
- The vendor-level regex (`TelephonyCollector.kt:37`) runs over an OEM-controlled string; it is
  anchored to a short numeric group and falls back to null. No catastrophic-backtracking shape.

### R3 — The future crowdsourcing backend is the real risk, and it is not built yet *(high, prospective)*

This is the finding to carry forward, because every mitigation above is a property of *nothing
being uploaded*.

`coverage-map.md` proposes uploading bins with a per-bin HMAC pseudonym, and is admirably honest
that a modified client can mint arbitrary tokens and defeat k on its own. Three things follow, and
they should be settled **before** the first line of upload code:

1. **k-anonymity computed on the client is not a security control.** The server must enforce k
   against contributions it has actually received, from contributors it can distinguish, or k is
   decoration. Client-side `Bin.publishable` is a UI hint and should never be the gate.
2. **A bin sequence is a re-identification vector even with perfect pseudonyms.** Bins near a home
   at night and an office by day identify a person from public data, regardless of what the
   contributor id looks like. Uploading *per-bin aggregates over a long window* is materially safer
   than uploading *the bins one device visited*, and the difference is invisible in the schema.
3. **The device must never upload `radio_sample` identity columns.** `servingCi` + timestamps is
   the trace; the map does not need it (`data-model.md` §3 already says neighbour readings stay
   local — the same logic extends to serving-cell sequences leaving the device).

### R4 — Shizuku as a privilege-escalation surface *(not present today; constrain it when wired)*

Currently inert and honestly labelled: the client library is not a dependency,
`ActionPrivilege.probe` can only ever return `connected = false`
(`collect/ActionRemediation.kt:78-92`), and every Tier-2 action is gated behind it
(`ActionRemediation.kt:119-121`). Nothing to attack.

When it is wired, the two properties to preserve: **bind to Shizuku by its known signature, not its
package name** (a package name is claimable on a device without the real Shizuku installed), and
keep the shell-UID surface to a fixed allowlist of specific calls — never a general "run this
command" bridge, which converts a user-granted debugging aid into a local privilege-escalation
service any bug in the app can reach.

---

## 6. Supply chain — clean, and now pinned harder

| Item | Finding |
|---|---|
| `gradle-wrapper.jar` | **Provenance verified.** SHA-256 `81a82aae…ae45f` matches Gradle's published `gradle-8.13-wrapper.jar.sha256` byte for byte. Contents are the 33 standard `org/gradle/wrapper` entries and `META-INF` — nothing injected. |
| Gradle distribution | Was **unpinned** — the wrapper ran whatever the URL served, and the first thing a clone of a public repo does is download and execute it. **FIXED**: `distributionSha256Sum` added, value taken from Gradle's own published checksum. The build was re-run afterwards and passes, which is itself the verification. |
| Dependency versions | All pinned to exact versions; no dynamic ranges, no `+`, no snapshots. The Compose BOM is a pinned platform. |
| Repositories | `google()` and `mavenCentral()` only, with `FAIL_ON_PROJECT_REPOS`. No custom or HTTP repository. |
| Known advisories | Queried OSV for MapLibre 11.11.0, Room 2.7.1, AGP 8.10.1, Kotlin 2.1.20, core-ktx 1.15.0, activity-compose 1.9.3 — **zero advisories** against any pinned version. |
| Unpinned build-time fetches | None. No build script downloads anything; `tools/make_icon.sh` is local image processing. |

*Recommended, not applied:* add a `release` build type. There is none, so `assembleRelease` yields
an unminified, unsigned APK. If an APK is ever distributed, add:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

Left unapplied deliberately: R8 stripping cannot be validated without a release-build run on the
device, and shipping an untested shrink configuration is worse than shipping none.

---

## What was changed

Every change below is in the working tree, compiles, and was verified on a physical handset — the
app installs, launches, collects, and the Map tab renders bins over live tiles with no crash.

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | Removed `ACCESS_BACKGROUND_LOCATION` (A1); added `dataExtractionRules` (P3), explicit `usesCleartextTraffic="false"` and `networkSecurityConfig` (A4); annotated the permission set. |
| `app/src/main/res/xml/data_extraction_rules.xml` | **New.** Excludes every domain from cloud backup *and* device-to-device transfer. |
| `app/src/main/res/xml/network_security_config.xml` | **New.** Cleartext off, system CAs only. |
| `app/src/main/java/com/signalscope/store/Retention.kt` | **New.** Parameterised 30-day sweep of all four raw tables (P2). |
| `app/src/main/java/com/signalscope/collect/CollectorService.kt` | Retention sweep on start; `PendingIntent` target and flags (A2); collector start/stop no longer able to kill the service (C2). |
| `app/src/main/java/com/signalscope/collect/TelephonyCollector.kt` | `tmFor` null-safe (C2). |
| `app/src/main/java/com/signalscope/collect/LiveState.kt` | `updateSim` made atomic (C5). |
| `app/src/main/java/com/signalscope/collect/ConnectivityCollector.kt` | Address-change counter made thread-safe (C5). |
| `app/src/main/java/com/signalscope/collect/ActionSelfTest.kt` | Supervised scope + guarded `AudioTrack` construction (C3). |
| `app/src/main/java/com/signalscope/ui/TimelineScreen.kt` | Derivation guarded against a throwing database read (C4). |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionSha256Sum` pinned. |
| `.gitignore` | Ignore local tool state and any `*.db` pulled off a device. |

## What was not changed, and why

| Item | Reason |
|---|---|
| `data-model.md` §5 wording (P1) | A documentation change in a file another pass owns. It is the single most important remaining item. |
| README privacy section (P1, P4, R1) | Same. Should state: no coordinates are stored; a binned position history and a cell history are; tiles are fetched from a third party. |
| Retention rollup (`data-model.md` §7) | A feature, not a fix. The drop half bounds exposure and is done. |
| `release` build type | Cannot validate R8 output without a release-build device run. |
| `FLAG_SECURE` (P6) | Wrong trade for a diagnostics tool whose screenshots are meant to be shareable. |
| Commit author email | Rewriting history must happen before the first push, and is the owner's call. |

---

## The single most serious thing

Not a vulnerability — a claim. **`data-model.md` §5 says the database "never contains a precise
movement trace", and a reader will hear "no movement trace".** The code earns the first statement
and not the second: `map_fix` holds a timestamped position history at up to ~65 m resolution, and
`radio_sample` holds a timestamped serving-cell history that is a movement trace in its own right,
collected under `READ_PHONE_STATE` whether or not location was ever granted. Until this pass,
neither was ever deleted.

Everything else in this review is smaller than that, because a privacy claim a hostile reader can
falsify from the source is worth more to them than any single bug — and this project's entire
credibility rests on its documents being true about its code.
