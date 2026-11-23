"""Check that the source links in the feature coverage table still exist on the code branch.

maintainer/changes/feature-coverage.md links each current feature page to production files at
the commit where the row was registered. A file that was later renamed or deleted keeps a valid
GitHub link, so link checking alone never notices it. This guard compares every linked path with
the file list of the current code branch:

* current rows must use a commit SHA and link to a path in the current code tree;
* rows inside the dated increment log ("增量记录") only warn, because they record what was true on that date.

Links to other repositories (for example SandyYuR/librime) are not checked.

Usage:
  python3 docs/scripts/check-coverage-sources.py --code-tree code-tree.txt --require
  python3 docs/scripts/check-coverage-sources.py --code-dir ../fx-rime-only --ref origin/fx-rime-only
code-tree.txt holds one path per line, e.g. from `git ls-tree -r --name-only <branch>`.
Without --code-tree/--code-dir the check is skipped (exit 0) unless --require is given.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

docs = Path(__file__).resolve().parents[1]
COVERAGE = docs / 'maintainer/changes/feature-coverage.md'
REPOSITORY = 'SandyYuR/fcitx5-android'
HISTORICAL_SECTIONS = {'增量记录'}
LINK = re.compile(
    r'https://github\.com/' + re.escape(REPOSITORY) + r'/(blob|tree)/([^/)\s#?]+)/([^)\s#?]+)'
)
COMMIT_SHA = re.compile(r'[0-9a-fA-F]{7,40}')
MINIMUM_TREE_SIZE = 100


def load_tree(args):
    if args.code_tree:
        lines = Path(args.code_tree).read_text(encoding='utf-8').splitlines()
        return {line.strip().removeprefix('./') for line in lines if line.strip()}
    try:
        tree = subprocess.run(
            ['git', '-C', args.code_dir, 'rev-parse', '--verify', '--end-of-options', f'{args.ref}^{{tree}}'],
            check=True, capture_output=True, text=True,
        ).stdout.strip()
        listing = subprocess.run(
            ['git', '-C', args.code_dir, 'ls-tree', '-rz', '--full-tree', '--name-only', tree],
            check=True, capture_output=True, text=True, encoding='utf-8',
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = exc.stderr.strip() if isinstance(exc, subprocess.CalledProcessError) else str(exc)
        raise SystemExit(f'Cannot list the Git tree for {args.ref} in {args.code_dir}: {detail}')
    return set(listing.rstrip('\0').split('\0')) if listing else set()


def section_locator(text):
    marks = [(match.start(), match.group(1).strip()) for match in re.finditer(r'^## (.+)$', text, re.M)]

    def locate(position):
        current = '(page header)'
        for start, title in marks:
            if start > position:
                break
            current = title
        return current
    return locate


def check(text, files):
    directories = set()
    for path in files:
        parts = path.split('/')
        for end in range(1, len(parts)):
            directories.add('/'.join(parts[:end]))
    locate = section_locator(text)
    errors, warnings, paths = [], [], set()
    for match in LINK.finditer(text):
        kind, ref, raw = match.groups()
        section = locate(match.start())
        line = text.count('\n', 0, match.start()) + 1
        issues = warnings if section in HISTORICAL_SECTIONS else errors
        row = {'line': line, 'section': section, 'ref': ref, 'kind': kind}
        if not COMMIT_SHA.fullmatch(ref):
            issues.append({**row, 'reason': 'source link must use a pinned commit SHA', 'link': match.group(0)})
            continue
        path = unquote(raw).rstrip('/')
        paths.add(path)
        if path not in files and path not in directories:
            issues.append({**row, 'reason': 'source path is missing from the code tree', 'path': path})
    return errors, warnings, paths


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--code-tree', help='file with one code-branch path per line')
    source.add_argument('--code-dir', help='checked-out code branch')
    parser.add_argument('--ref', help='commit or ref to read with --code-dir (default: HEAD)')
    parser.add_argument('--require', action='store_true', help='fail instead of skipping when no code tree is given')
    args = parser.parse_args(argv)
    if args.ref is not None and args.code_dir is None:
        parser.error('--ref requires --code-dir')
    args.ref = args.ref or 'HEAD'
    if not args.code_tree and not args.code_dir:
        message = 'No code tree given; pass --code-tree or --code-dir to check coverage source paths.'
        if args.require:
            raise SystemExit(message)
        print(json.dumps({'skipped': True, 'reason': message}, ensure_ascii=False))
        return 0
    files = load_tree(args)
    if len(files) < MINIMUM_TREE_SIZE:
        raise SystemExit(f'Code tree has only {len(files)} paths; the clone or listing step probably failed.')
    text = COVERAGE.read_text(encoding='utf-8').replace('\r\n', '\n')
    errors, warnings, paths = check(text, files)
    print(json.dumps({'code_paths': len(files), 'linked_paths': len(paths),
                      'current_issues': len(errors), 'historical_issues': len(warnings),
                      'errors': len(errors)}, ensure_ascii=False))
    for row in warnings:
        print(json.dumps({'warning': row['reason'], **row}, ensure_ascii=False))
    for row in errors:
        print(json.dumps({'error': row['reason'], **row}, ensure_ascii=False))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
