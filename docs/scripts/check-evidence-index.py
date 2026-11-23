"""Keep the evidence page in step with the attachment directories that are actually published.

copy-downloads.mjs publishes every non-Markdown file under docs/ at its relative URL, and
maintainer/evidence.md is the reader's index of those files. Every attachment root under
maintainer/ (maintainer/audit/<name>/, maintainer/perf/<name>/, or another maintainer directory
that holds files) must be discoverable in one of three ways:

* per-file sections: one "### <directory>" section per directory, listing every file in it with a
  download link (the style used up to 2026-11-06);
* a row in the table between the evidence-roots markers that points to the report page responsible
  for the directory (the style used for later, larger directories);
* outside audit/ and perf/ only: every file in the directory is already linked or embedded by
  some page (for example a diagram next to an implementation page).

Sections whose directory no longer holds files, table rows for missing directories and rows whose
report page does not exist are reported as well. Attachments outside maintainer/ (feature images,
the frozen legacy site guarded by check-legacy-docs.py) are not part of this index.

With --write, missing roots are appended to the marked table. The report link is the root's
index page, or the first page that links into the root; review the generated rows before committing.
"""
import argparse
import json
import posixpath
import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote

docs = Path(__file__).resolve().parents[1]
EVIDENCE = 'maintainer/evidence.md'
START, END = '<!-- evidence-roots:start -->', '<!-- evidence-roots:end -->'
SKIP_NAMES = {'node_modules', '__pycache__'}
SKIP_TOP = {'scripts', 'public'}
SITE_BASE = '/fcitx5-android/'
FENCED_CODE = re.compile(
    r'^ {0,3}(\x60{3,})[^\n]*\n.*?(?:^ {0,3}\1\x60*[ \t]*$|\Z)'
    r'|^ {0,3}(~{3,})[^\n]*\n.*?(?:^ {0,3}\2~*[ \t]*$|\Z)',
    re.M | re.S,
)
REFERENCE_DEFINITION = re.compile(
    r'^ {0,3}\[([^\]\n]+)\]:[ \t]*(?:<([^>\n]+)>|([^\s<>]+))[^\n]*$', re.M
)
REFERENCE_USE = re.compile(r'(?<!\\)\[([^\]\n]+)\](?:[ \t]*\[([^\]\n]*)\])?(?!\()')


def collect(root=docs):
    """Return (attachments, pages) the same way copy-downloads.mjs and build-catalog.mjs see them."""
    attachments, pages = [], {}

    def walk(directory):
        for entry in sorted(directory.iterdir(), key=lambda item: item.name):
            if entry.name.startswith('.') or entry.name in SKIP_NAMES:
                continue
            if directory == root and entry.name in SKIP_TOP:
                continue
            if entry.is_dir():
                walk(entry)
                continue
            path = entry.relative_to(root).as_posix()
            if re.search(r'\.(md|mdx)$', path, re.I):
                pages[path] = entry.read_text(encoding='utf-8').replace('\r\n', '\n')
            elif not re.fullmatch(r'package(?:-lock)?\.json', path):
                attachments.append(path)
    walk(root)
    return attachments, pages


def root_of(path):
    parts = path.split('/')
    if parts[0] != 'maintainer':
        return None
    if len(parts) > 3 and parts[1] in ('audit', 'perf'):
        return '/'.join(parts[:3])
    return posixpath.dirname(path)


def link_targets(page, text):
    base = posixpath.dirname(page)
    text = FENCED_CODE.sub('', text)
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
    text = re.sub(r'(\x60+)[^\x60\n]*\1', '', text)
    references = {}
    for name, bracketed, plain in REFERENCE_DEFINITION.findall(text):
        references.setdefault(' '.join(name.casefold().split()), bracketed or plain)
    text = REFERENCE_DEFINITION.sub('', text)
    raw = re.findall(r'\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\)', text) + re.findall(r'(?:href|src)="([^"]+)"', text)
    for caption, label in REFERENCE_USE.findall(text):
        url = references.get(' '.join((label or caption).casefold().split()))
        if url is not None:
            raw.append(url)
    for url in raw:
        if re.match(r'^[a-z][a-z0-9+.-]*:', url, re.I) or url.startswith('#'):
            continue
        url = unquote(url.split('#')[0].split('?')[0])
        if not url:
            continue
        if url.startswith(SITE_BASE):
            yield url[len(SITE_BASE):]
        elif url.startswith('/'):
            yield url[1:]
        else:
            yield posixpath.normpath(posixpath.join(base, url))


def page_for_route(route, pages):
    route = route.strip('/')
    route = re.sub(r'\.(md|html)$', '', route)
    for candidate in (f'{route}.md', f'{route}/index.md') if route else ('index.md',):
        if candidate in pages:
            return candidate
    return None


def title_of(text, fallback):
    body = re.sub(r'^---\n[\s\S]*?\n---(?:\n|$)', '', text)
    match = re.search(r'^#\s+(.+)$', body, re.M)
    title = match.group(1) if match else fallback
    return re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', title).replace('`', '').replace('|', '／').strip()


def route_of(page):
    return '/' + re.sub(r'(^|/)index$', r'\1', page[:-3])


def parse_index(text):
    start = text.find('## 按目录查找全部附件')
    if start < 0:
        raise SystemExit(f'{EVIDENCE} lacks the "按目录查找全部附件" section.')
    end = text.find('\n## ', start + 3)
    scope = text[start:end if end > 0 else len(text)]
    sections = {}
    for match in re.finditer(r'^### ([A-Za-z0-9_./-]+)\s*$', scope, re.M):
        body_end = scope.find('\n### ', match.end())
        body = scope[match.end():body_end if body_end > 0 else len(scope)]
        directory = 'maintainer' if match.group(1) == 'maintainer' else f'maintainer/{match.group(1)}'
        sections[directory] = set(re.findall(r'href="' + re.escape(SITE_BASE) + r'([^"]+)"\s+download', body))
    if text.count(START) != 1 or text.count(END) != 1 or text.index(START) > text.index(END):
        raise SystemExit(f'{EVIDENCE} must contain exactly one {START} … {END} block around the directory table.')
    table = text[text.index(START):text.index(END)]
    rows = {}
    for line in table.splitlines():
        match = re.match(r'^\|\s*`(maintainer/[^`]+?)/?`\s*\|(.*)\|\s*$', line)
        if match:
            rows[match.group(1)] = [route for route in re.findall(r'\]\(([^)\s]+)\)', match.group(2))]
    return sections, rows


def check(attachments, pages):
    text = pages[EVIDENCE]
    sections, rows = parse_index(text)
    roots = defaultdict(list)
    for path in attachments:
        root = root_of(path)
        if root:
            roots[root].append(path)
    linked = set()
    for page, body in pages.items():
        linked.update(link_targets(page, body))
    errors, missing = [], []
    existing_directories = {posixpath.dirname(path) for path in attachments}

    for directory, listed in sorted(sections.items()):
        files = [path for path in attachments if posixpath.dirname(path) == directory]
        if not files:
            errors.append({'error': 'per-file section has no files', 'directory': directory})
            continue
        for path in files:
            if path not in listed:
                errors.append({'error': 'file missing from its per-file section', 'file': path})
        for path in listed:
            if path not in files:
                errors.append({'error': 'per-file section lists a file that does not exist', 'file': path})
    for directory, routes in sorted(rows.items()):
        if directory not in roots and directory not in existing_directories:
            errors.append({'error': 'table row for a directory without attachments', 'directory': directory})
        if not routes:
            errors.append({'error': 'table row has no report link', 'directory': directory})
        for route in routes:
            if not route.startswith(('http:', 'https:')) and page_for_route(route.split('#')[0], pages) is None:
                errors.append({'error': 'table row links a missing page', 'directory': directory, 'link': route})

    for root, files in sorted(roots.items()):
        if root in rows:
            continue
        nested = root.startswith(('maintainer/audit/', 'maintainer/perf/'))
        covered = [directory for directory in sections if directory == root or (nested and directory.startswith(root + '/'))]
        if covered:
            for path in files:
                if posixpath.dirname(path) not in sections:
                    errors.append({'error': 'file outside every per-file section of its root', 'file': path})
            continue
        # Diagrams and other page assets outside audit/ and perf/ are reachable through their page.
        if not nested and all(path in linked for path in files):
            continue
        missing.append(root)
        errors.append({'error': 'attachment directory is not registered in the evidence index', 'directory': root,
                       'files': len(files), 'unlinked_files': sum(path not in linked for path in files)})
    return errors, missing, roots, sections, rows


def proposed_row(root, pages):
    index = page_for_route(root, pages)
    candidates = [index] if index else []
    if not candidates:
        for page in sorted(pages):
            if page == EVIDENCE:
                continue
            if any(target == root or target.startswith(root + '/') for target in link_targets(page, pages[page])):
                candidates.append(page)
                break
    if not candidates:
        return f'| `{root}/` | （尚无说明页：先在对应报告中说明并链接附件，再补本行） |'
    page = candidates[0]
    return f'| `{root}/` | [{title_of(pages[page], page)}]({route_of(page)}) |'


def write_rows(missing, pages, root=docs):
    path = root / EVIDENCE
    text = path.read_bytes().decode('utf-8')
    newline = '\r\n' if '\r\n' in text else '\n'
    end = text.index(END)
    before = text[:end].rstrip('\r\n')
    added = newline.join(proposed_row(item, pages) for item in missing)
    text = before + newline + added + newline + newline + text[end:]
    path.write_text(text, encoding='utf-8', newline='')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--write', action='store_true', help='append rows for unregistered attachment directories')
    args = parser.parse_args(argv)
    attachments, pages = collect()
    errors, missing, roots, sections, rows = check(attachments, pages)
    if args.write and missing:
        write_rows(missing, pages)
        attachments, pages = collect()
        errors, missing, roots, sections, rows = check(attachments, pages)
    print(json.dumps({'attachment_roots': len(roots), 'per_file_sections': len(sections), 'table_rows': len(rows),
                      'unregistered_roots': len(missing), 'errors': len(errors)}, ensure_ascii=False))
    for error in errors:
        print(json.dumps(error, ensure_ascii=False))
    if missing and not args.write:
        print('Run `python3 docs/scripts/check-evidence-index.py --write` to append rows, then review them:')
        for item in missing:
            print('  ' + proposed_row(item, pages))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
