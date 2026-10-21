#!/usr/bin/env python3
"""Use checksum-pinned upstream sources and the user's unmodified YAML files."""
import importlib.util
import json
import sys
import zipfile
from pathlib import Path
from runtime_config import user_seed

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixture', here.parent / 'input-perf' / 'prepare-wanxiang.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
output = Path(sys.argv[1]).resolve()
# LTS is a moving release tag. It changed during preparation on 2026-10-17
# 09:59:19 UTC (asset 650124920); keep the historical API replay pin untouched.
fixture.SOURCES[1] = (fixture.SOURCES[1][0], fixture.SOURCES[1][1],
    '1849e5e4acbdbfaf41fceeb86b739d5d0c85d574bd8a688562cba1e6beb78488')
sys.argv = [str(here/'prepare.py'), str(output), '--cache', 'build/wanxiang-screen-source']
fixture.main()
with zipfile.ZipFile(output, 'a') as archive:
    for name in ('wanxiang.custom.yaml', 'user.yaml'):
        assert name not in archive.namelist()
        data = user_seed() if name == 'user.yaml' else (here / 'config' / name).read_bytes()
        archive.writestr(zipfile.ZipInfo(name, (2026, 10, 17, 0, 0, 0)), data)
manifest = {'sources': fixture.SOURCES, 'model_asset_id': 650124920, 'model_updated_at': '2026-10-17T09:59:19Z',
            'config_sha256': {p.name: fixture.checksum(p) for p in (here / 'config').glob('*.yaml')}, 'fixture_sha256': fixture.checksum(output)}
manifest['runtime_user_adjustment'] = 'Add var/option/context_reorder: true; all other uploaded YAML content preserved'
output.with_suffix('.manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print('User-config fixture sha256:' + manifest['fixture_sha256'])
