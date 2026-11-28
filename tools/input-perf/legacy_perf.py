#!/usr/bin/env python3
"""Identity gates shared by legacy disposable-emulator performance drivers."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


PACKAGE = re.compile(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+')
NAMESPACE = r'(?:android:|http://schemas\.android\.com/apk/res/android:)'


def device_arguments(parser):
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd-name', required=True)
    parser.add_argument('--allow-destructive-reset', action='store_true',
                        help='Acknowledge installation, app-data resets and device-setting changes')
    parser.add_argument('--adb', default='adb')


class Device:
    def __init__(self, args):
        # Validate permission and spelling before even querying a device.
        if not args.allow_destructive_reset:
            raise ValueError('--allow-destructive-reset is required; this benchmark changes device data')
        if not re.fullmatch(r'emulator-\d+', args.serial):
            raise ValueError('--serial must name one emulator explicitly')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.expected_avd_name):
            raise ValueError('--expected-avd-name must be an exact nonempty AVD name')
        self.serial, self.executable = args.serial, args.adb
        if self('get-serialno').stdout.strip() != self.serial:
            raise ValueError('Connected serial does not match --serial')
        if self('get-state').stdout.strip() != 'device':
            raise ValueError('The named emulator is not online')
        names = self('emu', 'avd', 'name').stdout.strip().splitlines()
        if names and names[-1] == 'OK':
            names.pop()
        if names != [args.expected_avd_name]:
            raise ValueError('AVD name does not match --expected-avd-name; no device changes made')

    def command(self, *parts):
        return [self.executable, '-s', self.serial, *map(str, parts)]

    def __call__(self, *parts, check=True, timeout=900, input=None):
        return subprocess.run(self.command(*parts), input=input, text=True,
                              capture_output=True, check=check, timeout=timeout)


def find_aapt():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    executable = 'aapt2.exe' if os.name == 'nt' else 'aapt2'
    candidates = list((sdk / 'build-tools').glob('*/' + executable))
    if not candidates:
        raise ValueError('Android SDK build-tools with aapt2 are required to verify APK identities')
    return max(candidates, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))


def parse_manifest(tree):
    packages = re.findall(r'^\s*A: package="([^"]+)"', tree, re.MULTILINE)
    if len(packages) != 1 or not PACKAGE.fullmatch(packages[0]):
        raise ValueError('APK must declare one literal package name')
    blocks, current, indent = [], None, -1
    for line in tree.splitlines():
        element = re.match(r'^(\s*)E: (\S+)', line)
        if element:
            depth = len(element[1])
            if current is not None and depth <= indent:
                current = None
            if element[2] == 'instrumentation':
                current, indent = {}, depth
                blocks.append(current)
        elif current is not None:
            attribute = re.search(r'A:\s+' + NAMESPACE + r'(name|targetPackage)(?:\([^)]*\))?=(.*)', line)
            if attribute:
                value = re.match(r'"([^"]+)"', attribute[2])
                if value is None or attribute[1] in current:
                    raise ValueError('Instrumentation attributes must be unique literal strings')
                current[attribute[1]] = value[1]
    if len(blocks) > 1:
        raise ValueError('APK declares multiple instrumentation runners')
    identity = {'package': packages[0], 'target_package': None, 'runner': None}
    if blocks:
        block = blocks[0]
        target, runner = block.get('targetPackage', ''), block.get('name', '')
        if runner.startswith('.'):
            runner = packages[0] + runner
        elif '.' not in runner:
            runner = packages[0] + '.' + runner
        if not PACKAGE.fullmatch(target) or not PACKAGE.fullmatch(runner):
            raise ValueError('Instrumentation must declare a literal targetPackage and runner')
        identity.update(target_package=target, runner=runner)
    return identity


def apk_identity(path, aapt):
    path = Path(path)
    if not path.is_file():
        raise ValueError(str(path) + ' is not a file')
    tree = subprocess.run([str(aapt), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(path)],
                          check=True, text=True, capture_output=True, timeout=120).stdout
    return parse_manifest(tree)


def apk_pair(app, tests, aapt):
    product, harness = apk_identity(app, aapt), apk_identity(tests, aapt)
    if product['target_package'] is not None:
        raise ValueError('Product APK unexpectedly declares instrumentation')
    if harness['target_package'] != product['package'] or harness['package'] == product['package']:
        raise ValueError('Test APK targetPackage does not match its product APK')
    return {'package': product['package'], 'test_package': harness['package'],
            'instrumentation': harness['package'] + '/' + harness['runner']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='action', required=True)
    device = subparsers.add_parser('verify-device')
    device_arguments(device)
    pair = subparsers.add_parser('apk-pair')
    pair.add_argument('app', type=Path)
    pair.add_argument('tests', type=Path)
    pair.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.action == 'verify-device':
        print(Device(args).serial)
    else:
        identity = apk_pair(args.app, args.tests, find_aapt())
        args.output.write_text(json.dumps(identity, indent=2) + '\n')
        print(identity['package'])


if __name__ == '__main__':
    main()
