#!/usr/bin/env python3
"""Summarize paired engine replays, with an optional measurement-only native trace."""
import argparse
import csv
import hashlib
import io
import json
import math
import statistics
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--trace-processor', type=Path)
    parser.add_argument('--trace', type=Path, help='Separate fixed-APK profiling trace; never pooled into timing rounds')
    args = parser.parse_args()
    if args.trace and not args.trace_processor:
        parser.error('--trace requires --trace-processor')
    source = json.loads((args.directory / 'comparison.json').read_text())
    summary = {'metric': 'Backend API to event collector; excludes touch and display rendering',
               'rounds_per_backend': 3, 'identical_commits': source['identical_commits'],
               'identical_candidate_texts': source['identical_candidate_texts'], 'backends': {}}
    for backend, rounds in source['metrics'].items():
        stats = {}
        for metric in ('send_key_return', 'key_to_candidates', 'space_to_commit', 'word_to_result'):
            stats[metric] = {quantile: {
                'median_ms': statistics.median(r[metric][quantile] for r in rounds),
                'min_ms': min(r[metric][quantile] for r in rounds),
                'max_ms': max(r[metric][quantile] for r in rounds),
            } for quantile in ('p50_ms', 'p95_ms', 'p99_ms')}
        for metric in ('wall_ms', 'process_cpu_ms'):
            stats[metric] = statistics.median(r[metric] for r in rounds)
        stats['memory_after_median'] = {key: statistics.median(r['memory_after'][key] for r in rounds)
                                        for key in rounds[0]['memory_after']}
        summary['backends'][backend] = stats
    if args.trace_processor:
        trace = args.trace or args.directory / 'wanxiang-direct.perfetto-trace'
        query = '''SELECT s.name, s.dur / 1000000.0 AS ms
            FROM slice s JOIN slice phase ON phase.name = 'wanxiang.measure' AND phase.dur > 0
            WHERE s.ts >= phase.ts AND s.ts + s.dur <= phase.ts + phase.dur
              AND s.dur > 0 AND s.name GLOB 'rime.direct.*' ORDER BY s.ts'''
        run = subprocess.run([str(args.trace_processor), 'query', str(trace), query],
                             capture_output=True, text=True, check=True)
        groups = {}
        for row in csv.DictReader(io.StringIO(run.stdout)):
            groups.setdefault(row['name'], []).append(float(row['ms']))
        if not groups:
            raise SystemExit('No native slices inside the measured replay phase')
        summary['profile_slices'] = {name: {
            'samples': len(times), 'total_ms': sum(times),
            **{f'p{p}_ms': sorted(times)[math.ceil(len(times) * p / 100) - 1] for p in (50, 95, 99)}
        } for name, times in sorted(groups.items())}
        assert summary['profile_slices']['rime.direct.process_key']['samples'] == 784
        assert summary['profile_slices']['rime.direct.snapshot']['samples'] == 784
        summary['profile_trace_sha256'] = hashlib.sha256(trace.read_bytes()).hexdigest()
    output = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (args.directory / 'analysis.json').write_text(output)
    print(output)


if __name__ == '__main__':
    main()
