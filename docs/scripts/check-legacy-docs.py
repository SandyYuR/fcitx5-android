"""Protect the restored pre-migration pages, source bytes and visible site entries."""
from hashlib import sha256
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

docs = Path(__file__).resolve().parents[1]
repo = docs.parent
legacy = docs / 'legacy/fcitx'
dist = docs / '.vitepress/dist'
manifest = json.loads((legacy / 'restoration-manifest.json').read_text())
archive = legacy / manifest['source_archive']
assert sha256(archive.read_bytes()).hexdigest() == manifest['source_archive_sha256']
assert len(manifest['pages']) == manifest['source_document_count'] == 64
assert sum(p['source'].startswith('docs/maintainer/') for p in manifest['pages']) == manifest['maintainer_report_count'] == 13

with ZipFile(archive) as z:
    assert len(z.namelist()) == manifest['source_file_count'] == len(manifest['source_files'])
    for source in manifest['source_files']:
        data = z.read(source['source'])
        assert sha256(data).hexdigest() == source['sha256'], source['source']
        destination = source['restored_as']
        if not source['source'].endswith('.md') and destination != 'source-snapshot-edcf536f.zip only':
            assert (repo / destination).read_bytes() == data, destination

class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = set()
    def handle_starttag(self, tag, attrs):
        href = dict(attrs).get('href')
        if tag == 'a' and href:
            url = urlsplit(href)
            if not url.scheme and not url.netloc:
                self.paths.add(unquote(url.path).removeprefix('/fcitx5-android'))

def links(file):
    parser = Links()
    parser.feed(file.read_text())
    return parser.paths

catalog = links(dist / 'legacy/fcitx/contents.html')
reports = links(dist / 'legacy/fcitx/maintainer/index.html')
for page in manifest['pages']:
    source = repo / page['destination']
    assert sha256(source.read_bytes()).hexdigest() == page['published_sha256'], source
    assert '::: info 旧版 Fcitx 文档' in source.read_text(), source
    generated = dist / source.relative_to(docs).with_suffix('.html')
    assert generated.is_file(), generated
    assert page['route'] in catalog, ('missing catalog entry', page['route'])
    if page['source'].startswith('docs/maintainer/'):
        assert page['route'] in reports, ('missing maintenance report entry', page['route'])

home = links(dist / 'index.html')
assert '/legacy/fcitx/contents' in home and '/legacy/fcitx/maintainer/' in home
assert '旧版文档' in (dist / 'index.html').read_text()
print(json.dumps({'restored_pages': 64, 'maintenance_reports': 13,
                  'original_files_verified': manifest['source_file_count'],
                  'source_archive_verified': True, 'visible_entries_verified': True}))
