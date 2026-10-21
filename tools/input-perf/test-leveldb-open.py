#!/usr/bin/env python3
"""Check the public LevelDB Open bridge on Linux, including persisted data and recovery fallback."""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[2]
work = Path(sys.argv[1]).resolve()
work.mkdir(parents=True, exist_ok=True)
digest = '9a37f8a6174f09bd622bc723b55881dc541cd50747cbd08831c2a82d620f6d76'
archive = work / 'leveldb-1.23.tar.gz'
if not archive.exists():
    request = urllib.request.Request('https://codeload.github.com/google/leveldb/tar.gz/refs/tags/1.23',
                                     headers={'User-Agent': 'Codex-Rime-Perf'})
    with urllib.request.urlopen(request, timeout=30) as source, archive.open('wb') as target:
        shutil.copyfileobj(source, target)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == digest, 'LevelDB source checksum changed'
source = work / 'leveldb-1.23'
if not source.is_dir():
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            target = (work / member.name).resolve()
            assert target.is_relative_to(work) and (member.isfile() or member.isdir()), 'Invalid source archive member'
        tar.extractall(work)
cmake = shutil.which('cmake') or (sys.argv[2] if len(sys.argv) > 2 else None)
assert cmake, 'CMake is required; optionally pass its path as the second argument'
build = work / 'build'
subprocess.run([cmake, '-S', str(source), '-B', str(build), '-DLEVELDB_BUILD_TESTS=OFF',
                '-DLEVELDB_BUILD_BENCHMARKS=OFF', '-DHAVE_SNAPPY=OFF', '-DHAVE_CRC32C=OFF',
                '-DCMAKE_BUILD_TYPE=Release'], check=True)
subprocess.run([cmake, '--build', str(build), '-j2'], check=True)
library = build / 'libleveldb.a'
symbols = subprocess.check_output(['nm', '--defined-only', str(library)], text=True)
symbol = re.search(r'\bT (_ZN7leveldb2DB4OpenE\S+)', symbols).group(1)
data = work / 'data'
data.mkdir(exist_ok=True)
results = {'leveldb_source_sha256': digest, 'platform': 'Linux host; not Android timing'}
for force in (False, True):
    binary = work / ('bridged-probe' if force else 'upstream-probe')
    command = ['c++', '-std=c++17', '-fno-rtti', '-Wall', '-Wextra', '-Werror',
               '-I', str(source / 'include'), str(root / 'tools/input-perf/LevelDbOpenProbe.cpp')]
    if force:
        command += [str(root / 'direct-rime/src/main/cpp/leveldb-open.cpp'),
                    f'-DRIME_LEVELDB_OPEN_SYMBOL="{symbol}"', f'-Wl,--wrap={symbol}']
    subprocess.run(command + [str(library), '-pthread', '-o', str(binary)], check=True)
    output = subprocess.check_output([str(binary), str(data)] + (['bridged'] if force else []), text=True)
    print(output)
    results['bridged' if force else 'upstream'] = output.splitlines()
(work / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
