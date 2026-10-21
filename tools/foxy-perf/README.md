# Unmodified Foxy / updated direct-Rime comparison

This harness only tests the updated product and the exact uploaded Foxy APK.
The uploaded ARM64 APK is not repackaged. Both products run ARM64 on the same
API 30 Google APIs emulator's `libndk_translation.so` bridge. These are not phone
performance measurements, nor an isolated before/after measurement of each fix.

1. Build an ARM64 signed Release with `build-release.sh OUTPUT`; preserve its
   source hash, signing metadata and SHA256. `apk_profiles.py` audits the packed
   binary ART profile against the actual DEX method tables and ZIP CRCs.
2. Build the independent editor/instrumentation host with
   `bash tools/screen-perf/build-host.sh "$PWD/build/foxy-host" "$PWD/tools/foxy-perf/host"`.
   Use a new, empty absolute output directory (the helper generates its test key).
3. Prepare the pinned base v18.1.3 + LTS fixture with
   `python3 tools/screen-perf/prepare.py FIXTURE.zip`.
4. On a disposable emulator only, run `pilot.py --foxy ORIGINAL.apk
   --direct DIRECT.apk --host HOST.apk --fixture FIXTURE.zip --output CALIBRATION`.
   Inspect keyboard/candidate PNGs, actual selected service, compiled schema,
   option logs, Chinese commit and the model/native-bridge mappings. This is
   setup, not performance data. Actual soft touches must select the first
   candidate correctly in both UIs before timing. The saved points target the
   calibrated 320x640 default layouts, not a phone or another emulator geometry.
5. Run `run.py --points points.json --output RESULTS`. It alternates product
   order across six process-fresh trials **per case** (`ni` → 你,
   `zaoshanghao` → 早上好), each with eight selections, followed
   by a real trip to a non-editor Settings activity and eight further selections.
   Exact output and one-to-one nonempty commits are required; failures and raw partial
   evidence are saved, never silently removed from a successful sample pool.

The external driver injects DOWN and UP with a 24ms hold. Event times are sampled
immediately **before** framework injection, not inside either IME's listener.
The independent editor timestamps `commitText` entry, TextWatcher and CPU onDraw;
onDraw is not GPU presentation. Both DOWN/UP origins are retained because an IME
may act on DOWN. After session readiness, both products use a fixed 1000ms settling interval
after the final key, then the real candidate tap. First-candidate screenshots
are taken before the timed tap in each phase. Actual editor text validates the
selection. This does not measure first-letter or preedit latency: Foxy floating
preedit is inaccessible to the query, and fuzzy pinyin may format differently.

Each APK's own ProfileInstaller receiver and `speed-profile` compiler are invoked
equally; shell responses and package dexopt state are saved. File caches, deployed
artifacts and user learning persist: process-fresh is not a fresh storage cache.
Do not add a synthetic word warmup, disable Wanxiang options, or time deployment
as typing latency. A tiny first-sample group does not establish a reliable P95.

Release reuse is valid only if its product sources are identical to the current
checkout. Future product changes require a new Release. Never relabel an old APK
with a new source hash. Preserve the original artifact's source and signing data.
The original Foxy fixture is a user-authorized temporary public branch; remove
that remote branch after the final run. Never commit the APK or decompiled source
into the feature branch. Raw user phone logs also stay outside the repository.


The timing replay waits for the actual **current editor binding**, via
UiAutomation's read-only shell log access and its IME window. Direct binding and
ready generations must match; Foxy must emit soft_cursor after the latest
onStartInput for this editor. The condition must remain stable for 500ms.
Foxy's visible keyboard can precede session creation. Initial keyboard-ready and
session-ready observation times are saved separately. Selection numbers therefore
start after readiness; they do not validate typing immediately on first display.
The original early-input and inaccessible-preedit harness failures remain
archived. Do not equate the latter with product input loss; screenshots prove
valid candidates existed despite the observation assertion failing.

For a separate candidate-then-Send observation, build the extended host with
`python3 build-send-host.py NEW_ABSOLUTE_OUTPUT` and run `send_race.py --points
points.json --output OUTPUT` after calibration. It seeds a prefix, uses real
candidate and Send touches, records what Send reads, clears the draft and calls
restartInput. Outcomes distinguish complete, missing, and late-to-next-draft
within an 800ms post-Send observation window; missing does not prove permanent loss.
This test client is not the user's chat app. No artificial native delay is added;
a pass cannot prove a slow phone failure fixed. Keep these outcomes separate
from the normal selection latency statistics.


The GitHub workflow is manual-only after this authorized comparison. It requires
a full fixture commit SHA, verifies the exact original APK hash, builds the
current signed Release, and runs both observations with `compare.sh`. A failure
in the normal comparison does not suppress the separate Send diagnostic. Do not
reintroduce an automatic trigger pointing at an already deleted fixture branch.
Fixture publication needs authorization; delete its remote branch after the run.


The archived six-trial fresh comparison used the earlier 500ms post-key delay;
all 192 fresh selections were correct. Its rebind phases used stale readiness,
so the overall run failed and rebind timings cannot be compared. Later targeted
validation uses the stricter current-binding guard and 1000ms post-key interval;
those results must not be pooled with the fresh comparison. Readiness bugs and
screenshots without a candidate must not be reported as post-selection text loss.
`NORMAL_TRIALS`, `NORMAL_ROUNDS`, and `SEND_TRIALS` override the defaults in
compare.sh for a bounded diagnostic run; preserve these parameters in reports.

GitHub only exposes workflow_dispatch when the workflow is registered on the
default branch. Before merge, use the documented disposable-emulator scripts if
the manual entry is unavailable; the default branch is not changed by this work.
