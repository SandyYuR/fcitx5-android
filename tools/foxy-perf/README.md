# Unmodified Foxy / updated direct-Rime comparison

This harness only tests the updated product and the exact uploaded Foxy APK.
The uploaded ARM64 APK is not repackaged. Both products run ARM64 on the same
API 30 Google APIs emulator's `libndk_translation.so` bridge. These are not phone
performance measurements, nor an isolated before/after measurement of each fix.

1. Build an ARM64 signed Release with `build-release.sh OUTPUT`; preserve its
   source hash, signing metadata and SHA256. `apk_profiles.py` audits the packed
   binary ART profile against the actual DEX method tables and ZIP CRCs.
2. Build the independent editor/instrumentation host with
   `bash tools/screen-perf/build-host.sh OUTPUT tools/foxy-perf/host`.
3. Prepare the pinned base v18.1.3 + LTS fixture with
   `python3 tools/screen-perf/prepare.py FIXTURE.zip`.
4. On a disposable emulator only, run `pilot.py --foxy ORIGINAL.apk
   --direct DIRECT.apk --host HOST.apk --fixture FIXTURE.zip --output CALIBRATION`.
   Inspect keyboard/candidate PNGs, actual selected service, compiled schema,
   option logs, Chinese commit and the model/native-bridge mappings. This is
   setup, not performance data. Select n, i and the first candidate coordinates
   independently for each UI and save their calibration images.
5. Run `run.py --points points.json --output RESULTS`. It alternates product
   order across six process-fresh trials, each with eight selections, followed
   by a real trip to a non-editor Settings activity and eight further selections.
   Exact output and one-to-one commits are required; failures and raw partial
   evidence are saved, never silently removed from a successful sample pool.

The external driver injects DOWN and UP with a 24ms hold. Event times are sampled
immediately **before** framework injection, not inside either IME's listener.
The independent editor timestamps `commitText` entry, TextWatcher and CPU onDraw;
onDraw is not GPU presentation. Both DOWN/UP origins are retained because an IME
may act on DOWN. UI automation observes the final ni preedit at coarse polling
resolution; it is not a precise first-letter frame measurement. A screenshot is
only taken after each phase, outside the timed selections.

Each APK's own ProfileInstaller receiver and `speed-profile` compiler are invoked
equally; shell responses and package dexopt state are saved. File caches, deployed
artifacts and user learning persist: process-fresh is not a fresh storage cache.
Do not add a synthetic word warmup, disable Wanxiang options, or time deployment
as typing latency. A tiny first-sample group does not establish a reliable P95.

The workflow reuses the verified Release artifact only if its product sources
are identical to the current checkout. Future product changes require a new
Release and explicit source/hash updates. Never relabel an old APK with a new hash.
The original Foxy fixture is a user-authorized temporary public branch; remove
that remote branch after the final run. Never commit the APK or decompiled source
into the feature branch. Raw user phone logs also stay outside the repository.
