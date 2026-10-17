#!/usr/bin/env python3
"""Use checksum-pinned upstream sources and the user's unmodified YAML files."""
import importlib.util
import json
import subprocess
import sys
import zipfile
from pathlib import Path

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixture', here.parent / 'input-perf' / 'prepare-wanxiang.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
output = Path(sys.argv[1]).resolve()
subprocess.run([sys.executable, str(here.parent / 'input-perf' / 'prepare-wanxiang.py'), str(output)], check=True)
with zipfile.ZipFile(output, 'a') as archive:
    for name in ('wanxiang.custom.yaml', 'user.yaml'):
        assert name not in archive.namelist()
        archive.writestr(zipfile.ZipInfo(name, (2026, 10, 17, 0, 0, 0)), (here / 'config' / name).read_bytes())
manifest = {'sources': fixture.SOURCES, 'config_sha256': {p.name: fixture.checksum(p) for p in (here / 'config').glob('*.yaml')}, 'fixture_sha256': fixture.checksum(output)}
output.with_suffix('.manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
