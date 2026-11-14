#!/usr/bin/env bash
# Build three exact direct-Rime products with the same test-only cadence probe.
# --verify checks an existing product artifact and the original remote fixture.
# --recover finalizes metadata for an identified failed build without rebuilding APKs.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
mode=build
if [ "${1:-}" = --verify ]; then mode=verify; shift; fi
if [ "${1:-}" = --recover ]; then mode=recover; shift; fi
output=${1:?product output directory}
current_ref=${2:?exact current source commit}
fixture_dir=${3:-}
original_driver_ref=
failed_build_run=
[[ "$current_ref" =~ ^[0-9a-f]{40}$ ]] || { echo 'current_ref must be a full lowercase 40-character commit SHA' >&2; exit 2; }
if [ "$mode" = recover ]; then
    original_driver_ref=${3:?original build driver commit}
    failed_build_run=${4:?failed build run ID}
    fixture_dir=
    [[ "$original_driver_ref" =~ ^[0-9a-f]{40}$ ]] && [[ "$failed_build_run" =~ ^[0-9]+$ ]] || { echo 'Recovery requires an exact original driver SHA and numeric failed run ID' >&2; exit 2; }
    [ -d "$output" ] && [ ! -e "$output/products.json" ] || { echo 'Recovery requires existing partial products without a finalized manifest' >&2; exit 2; }
    for revision in 0380d70f16efb6a9946a164821d5cad7118ce4ff 4d77cd79a580a9984130a5653b13f949ec5bc0a9 "$current_ref" "$original_driver_ref"; do
        git -C "$root" fetch --no-recurse-submodules --no-tags origin "$revision"
    done
fi

if [ "$mode" = build ]; then
    : "${RUNNER_TEMP:?Use a disposable CI build workspace}"
    runner_temp=$(cd "$RUNNER_TEMP" && pwd)
    mkdir -p "$output"
    output=$(cd "$output" && pwd)
    [ -z "$(find "$output" -mindepth 1 -maxdepth 1 -print -quit)" ] || { echo 'Product output must be empty' >&2; exit 2; }
    export BUILD_ABI=x86_64
    for revision in 0380d70f16efb6a9946a164821d5cad7118ce4ff 4d77cd79a580a9984130a5653b13f949ec5bc0a9 "$current_ref"; do
        git -C "$root" fetch --no-recurse-submodules --no-tags origin "$revision"
    done
    python3 - "$root" "$current_ref" <<'PYLOCK'
import hashlib, subprocess, sys
root, current = sys.argv[1:]
for source in ('0380d70f16efb6a9946a164821d5cad7118ce4ff', '4d77cd79a580a9984130a5653b13f949ec5bc0a9', current):
    lock = subprocess.check_output(['git', '-C', root, 'show', source + ':native/rime-sdk.lock.json'])
    if hashlib.sha256(lock).hexdigest() != '6500f364626c863bee98157e51b6539990bb0f4e392c38ec442b6b3e5eeaa92f':
        raise SystemExit('Product changed the frozen SDK lock: ' + source)
PYLOCK
    for label in baseline regressed current; do
        case "$label" in
            baseline) revision=0380d70f16efb6a9946a164821d5cad7118ce4ff ;;
            regressed) revision=4d77cd79a580a9984130a5653b13f949ec5bc0a9 ;;
            current) revision=$current_ref ;;
        esac
        checkout="$runner_temp/latency-study-$label"
        [ ! -e "$checkout" ] || { echo "Refusing to reuse checkout: $checkout" >&2; exit 2; }
        git clone --shared --no-checkout "$root" "$checkout"
        git -C "$checkout" checkout --detach "$revision"
        (
            cd "$checkout"
            ./prepare_personal_build.sh
            target=app/src/androidTest/java/org/fcitx/fcitx5/android/core
            mkdir -p "$target"
            cp "$root/tools/input-perf/CadenceCandidateTapTest.kt" "$target/CandidateTapTest.kt"
            cmp "$root/tools/input-perf/CadenceCandidateTapTest.kt" "$target/CandidateTapTest.kt"
            sha256sum "$target/CandidateTapTest.kt" | awk '{print $1}' > "$output/$label-harness-sha256.txt"
            # Test instrumentation is the only overlay; never relabel a modified product.
            git diff --exit-code -- app/src/main direct-rime native build-logic gradle \
                app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties
            git submodule status > "$output/$label-submodules.txt"
            ./gradlew -PbuildABI=x86_64 -Pkotlin.compiler.execution.strategy=in-process \
                --max-workers=2 --console=plain :app:assembleFxDebug :app:assembleFxDebugAndroidTest \
                2>&1 | tee "$output/$label-build.log"
            app_apks=(app/build/outputs/apk/fx/debug/*.apk)
            test_apks=(app/build/outputs/apk/androidTest/fx/debug/*.apk)
            [ "${#app_apks[@]}" -eq 1 ] && [ -f "${app_apks[0]}" ]
            [ "${#test_apks[@]}" -eq 1 ] && [ -f "${test_apks[0]}" ]
            cp "${app_apks[0]}" "$output/$label.apk"
            cp "${test_apks[0]}" "$output/$label-tests.apk"
            git rev-parse HEAD > "$output/$label-commit.txt"
            python3 tools/verify-direct-rime-apk.py "$output/$label.apk" > "$output/$label-apk-audit.json"
            python3 tools/audit-direct-rime-link.py "$output/$label.apk" \
                --build-root "$checkout/app/.cxx" --prebuilt "$checkout/native/rime-sdk" \
                > "$output/$label-native-link-audit.json"
            ./gradlew --stop
        )
    done
    # Build once for every measurement VM; only the APK is copied, never the generated key.
    bash "$root/tools/screen-perf/build-host.sh" "$runner_temp/latency-study-host" "$root/tools/candidate-perf/host"
    cp "$runner_temp/latency-study-host/host.apk" "$output/host.apk"
fi

python3 - "$mode" "$root" "$output" "$current_ref" "$fixture_dir" "$original_driver_ref" "$failed_build_run" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

mode, root_arg, output_arg, current_ref, fixture_arg, original_driver_ref, failed_build_run = sys.argv[1:]
root, output = Path(root_arg), Path(output_arg)
protocol = 'fixed_cadence_100ms_input_manager_async_cached_targets_v2'
sources = {
    'baseline': '0380d70f16efb6a9946a164821d5cad7118ce4ff',
    'regressed': '4d77cd79a580a9984130a5653b13f949ec5bc0a9',
    'current': current_ref,
}
host_files = (
    'tools/candidate-perf/host/BenchHost.java',
    'tools/candidate-perf/host/BenchControl.java',
    'tools/candidate-perf/host/AndroidManifest.xml',
    'tools/screen-perf/build-host.sh',
)

def require(condition, message):
    if not condition:
        raise SystemExit(message)

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args])

harness_hash = digest(root / 'tools/input-perf/CadenceCandidateTapTest.kt')
host_hashes = {name: digest(root / name) for name in host_files}
expected_apks = {label + suffix for label in sources for suffix in ('.apk', '-tests.apk')}

if mode == 'recover':
    # Match the compiled probe/host to the original build driver, not the recovery commit.
    require(hashlib.sha256(git('show', original_driver_ref + ':tools/input-perf/CadenceCandidateTapTest.kt')).hexdigest() == harness_hash,
            'Recovery changed the original build probe')
    require({name: hashlib.sha256(git('show', original_driver_ref + ':' + name)).hexdigest() for name in host_files} == host_hashes,
            'Recovery changed the original neutral host source')
    for label, sha in sources.items():
        require((output / (label + '-commit.txt')).read_text().strip() == sha, 'Original product source record mismatch: ' + label)
        require((output / (label + '-harness-sha256.txt')).read_text().strip() == harness_hash,
                'Original product probe record mismatch: ' + label)
        reports = json.loads((output / (label + '-native-link-audit.json')).read_text())
        require(len(reports) == 1 and reports[0]['apk'] == label + '.apk' and reports[0]['abi'] == 'x86_64',
                'Missing or unexpected original native audit: ' + label)
        with zipfile.ZipFile(output / (label + '.apk')) as apk:
            packaged_native = hashlib.sha256(apk.read('lib/x86_64/libdirect-rime.so')).hexdigest()
        require(reports[0]['apk_native_sha256'] == packaged_native and reports[0]['fcitx_cpp_symbols'] == [],
                'Original native audit does not match the reused APK: ' + label)
        apk_audit = json.loads((output / (label + '-apk-audit.json')).read_text())
        require(apk_audit['apk_sha256'] == digest(output / (label + '.apk')) and apk_audit['fcitx_dependencies'] == [],
                'Original APK audit does not match the reused APK: ' + label)

if mode in ('build', 'recover'):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    aapt = max((sdk / 'build-tools').glob('*/aapt2'),
               key=lambda p: [int(n) for n in re.findall(r'\d+', p.parent.name)])
    signer = aapt.with_name('apksigner')
    packages, certificates = {}, {}
    for name in sorted(expected_apks):
        path = output / name
        packages[name] = subprocess.check_output([str(aapt), 'dump', 'packagename', str(path)], text=True).strip()
        report = subprocess.check_output([str(signer), 'verify', '--print-certs', str(path)], text=True)
        report_suffix = '.recovery.signer.txt' if mode == 'recover' else '.signer.txt'
        (output / (name + report_suffix)).write_text(report)
        sys.path.insert(0, str(root / 'tools/input-perf'))
        from apk_signers import signer_sha256
        try:
            certificates[name] = signer_sha256(report)
        except ValueError as error:
            raise SystemExit(f'{name}: {error}') from error
    app_packages = {packages[label + '.apk'] for label in sources}
    require(len(app_packages) == 1 and next(iter(app_packages)).endswith('.debug'), 'Products must preserve the same .debug package name')
    for label in sources:
        require(packages[label + '-tests.apk'] == packages[label + '.apk'] + '.test', 'Instrumentation package must match its product')
    require(len(set(certificates.values())) == 1, 'Products and probes must use the same CI debug signer')
    locks = {label: hashlib.sha256(git('show', sha + ':native/rime-sdk.lock.json')).hexdigest()
             for label, sha in sources.items()}
    require(set(locks.values()) == {'6500f364626c863bee98157e51b6539990bb0f4e392c38ec442b6b3e5eeaa92f'},
            'All products must use the original frozen SDK lock')
    require(protocol in (root / 'tools/input-perf/CadenceCandidateTapTest.kt').read_text(), 'Expected the v2 cadence probe')
    record = {
        'sources': sources,
        'driver_commit': original_driver_ref if mode == 'recover' else git('rev-parse', 'HEAD').decode().strip(),
        'metadata_driver_commit': git('rev-parse', 'HEAD').decode().strip(),
        'sdk_lock_sha256': locks,
        'protocol': protocol,
        'harness_sha256': harness_hash,
        'host_sources_sha256': host_hashes,
        'host_sha256': digest(output / 'host.apk'),
        'packages': packages,
        'signer_sha256': certificates,
        'native_audit_sha256': {label: digest(output / (label + '-native-link-audit.json')) for label in sources},
        'apks': {name: digest(output / name) for name in sorted(expected_apks)},
    }
    if mode == 'recover':
        record['recovery'] = {'failed_build_run': failed_build_run,
                              'operation': 'metadata_finalization_only', 'rebuilt_products': False}
    (output / 'products.json').write_text(json.dumps(record, indent=2) + '\n')

record = json.loads((output / 'products.json').read_text())
require(record['sources'] == sources, 'Product source commits do not match this study')
require(record['protocol'] == protocol, 'Product manifest has an incompatible injection protocol')
require(record['harness_sha256'] == harness_hash, 'Product probe differs from this workflow checkout')
require(record['host_sources_sha256'] == host_hashes, 'Neutral host source differs from this workflow checkout')
require(record['host_sha256'] == digest(output / 'host.apk'), 'Neutral host APK hash mismatch')
require(set(record['apks']) == expected_apks, 'Unexpected or missing product APKs')
require(set(record['native_audit_sha256']) == set(sources), 'Missing native audit identities')
require(set(record['sdk_lock_sha256']) == set(sources) and
        set(record['sdk_lock_sha256'].values()) == {'6500f364626c863bee98157e51b6539990bb0f4e392c38ec442b6b3e5eeaa92f'},
        'SDK identity does not match the original study')
for label, sha in sources.items():
    require((output / (label + '-commit.txt')).read_text().strip() == sha, 'Embedded source record mismatch: ' + label)
    require((output / (label + '-harness-sha256.txt')).read_text().strip() == harness_hash,
            'The product used a different probe: ' + label)
    require(digest(output / (label + '-native-link-audit.json')) == record['native_audit_sha256'][label],
            'Native audit changed: ' + label)
for name, expected in record['apks'].items():
    require(digest(output / name) == expected, 'APK checksum mismatch: ' + name)

if mode == 'verify':
    require(bool(fixture_arg), '--verify requires the downloaded fixture directory')
    fixture = Path(fixture_arg)
    manifest = json.loads((fixture / 'fixture.manifest.json').read_text())
    expected_fixture = '333e0a0c8c9beb8a942bd56d3f9c55f1c9123836635a4663744e23f21228404f'
    require(manifest['fixture_sha256'] == expected_fixture and digest(fixture / 'fixture.zip') == expected_fixture,
            'The original fixture ZIP changed')
    require(manifest['base']['digest'] == 'sha256:ddebf0372a2a3d9d8f7a5017704b2afd7397a961c32be490a91e49ec3ee90999' and
            manifest['model']['digest'] == 'sha256:769854c83f6c914a05a1e1abb67c31c00f1ae622a41878e05957bcdee3dbda9a',
            'The original Wanxiang base or LTS identity changed')
    require(manifest['original_config_sha256'] == {
        'wanxiang.custom.yaml': '42bf76351ceb6f2db87396ae13edf9d9914712a065a61cdf74c66c52900c1319',
        'user.yaml': 'dd82ae3dc637e850446eb6e4170d916bb3d628b08d85572096c6b4c62ccb9a57',
    } and manifest['runtime_user_sha256'] == 'ac3b6b2da6ebc3318a35c2c2ff2a2e903a6247fa5d05dbcabb6db9462936e74f',
            'The original configuration identity changed')
print(json.dumps({'verified_sources': sources, 'protocol': protocol,
                  'harness_sha256': harness_hash, 'fixture_verified': mode == 'verify'}, indent=2))
PY
