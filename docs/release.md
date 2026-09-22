# Releasing a build other people install

Until now every APK this project produced was a debug build signed with a throwaway key. Android
identifies an app by its **signing key**, so each of those was a different app: an existing install
could not be updated, only uninstalled — taking every measurement with it. That is tolerable for one
developer testing on one handset. It is not tolerable for a build people are asked to run for
months, and it is fatal for a crowdsourced one, where the value of a contributor's data is exactly
the length of the history behind it.

So: **one key, generated once, backed up, never replaced.**

---

## 1. Generate the key — once, ever

Run this yourself. It prompts for a password and for the name fields; nothing about the key should
pass through a chat log, an environment variable, or the repository.

```bash
keytool -genkeypair -v -keystore ~/signalscope-release.jks -alias signalscope -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

`-validity 10000` is about 27 years. A key that expires is a key that ends the app.

**Then back it up**, to somewhere that survives this laptop — a password manager's file attachment,
an encrypted archive in cloud storage, a second physical device. Back up the **password with it**
and in the same place-of-record, because a keystore whose password is lost is exactly as useless as
no keystore.

> There is no recovery path. If the key is lost, every existing install is permanently stranded on
> the version it has. The only remedy is a new application id, which is a new app that shares
> nothing with the old one — no data, no history, no contributors.

## 2. Point the build at it

```bash
cp keystore.properties.template keystore.properties
```

Fill in `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The file is gitignored. A path
outside the repository is better than one inside it, so the key survives the working copy being
deleted.

Without `keystore.properties` the release build still compiles — it just comes out unsigned. A
fresh clone, another machine and CI must never need the private key in order to build.

## 3. Build

```bash
JAVA_HOME=/usr/local/opt/openjdk@21 ./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Confirm it is actually signed with the right key
before sending it anywhere:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The SHA-256 of the certificate is the app's identity. Record it here the first time, and check every
later build still matches — a build that comes out with a different fingerprint is a build nobody
can install over the last one.

Certificate SHA-256: _(fill in after the first signed build)_

## 4. Version discipline

`versionCode` is an integer that **must increase by one on every build handed to anyone**, and must
never go backwards: Android refuses to install an older code over a newer one, and a user who ends
up there can only fix it by uninstalling. `versionName` is for humans and carries no rules.

Both live in `app/build.gradle.kts`. Bump `versionCode` in the same commit that produces the build —
not afterwards, because an APK is out in the world the moment it is sent.

## 5. The one-time break

The first release-signed build **cannot** install over any existing debug build; the keys differ.
Everyone testing today has to uninstall once, losing what they have collected so far.

That cost is paid exactly once. Every build after it updates in place and keeps its data — which is
the entire point of doing this before asking anyone to collect for months.

Tell people plainly, and tell them before they install, not after.
