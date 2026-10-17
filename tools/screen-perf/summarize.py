#!/usr/bin/env python3
"""Summarise completed runs; retain per-run values instead of pooling correlated keys."""
import json
import gzip
import statistics as st
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
runs = json.loads((root/'runs.json').read_text())
measured = []
for item in runs:
    if item['mode'] != 'touch': continue
    directory = root/item['label']
    source = directory/'result.json'
    details = json.loads(source.read_text() if source.exists() else gzip.decompress(source.with_suffix('.json.gz').read_bytes()))
    analysis = json.loads((directory/'analysis.json').read_text())
    measured.append({**item, 'analysis': analysis, 'typing_cpu_ms': details['typing_cpu_ms'],
                     'typing_end_pss_kib': details['typing_end_pss_kib'], 'commit': details['commit'],
                     'switch_labels': details['switch_labels'], 'model_mapped': details['model_mapped'],
                     'fcitx_core_mapped': details['fcitx_core_mapped']})
groups = {}
for workload, prefix in [('steady', 'steady-r'), ('fast', 'fast-'), ('jitter', 'jitter-'), ('pilot', 'pilot-')]:
    subset = [r for r in measured if r['label'].startswith(prefix)]
    if not subset: continue
    groups[workload] = {}
    for backend in ('fcitx', 'direct'):
        selected = [r for r in subset if r['backend'] == backend]
        metrics = {}
        for metric in ('forward_candidate', 'first_candidate_before_next_key', 'attributed_candidate', 'space_to_editor'):
            metrics[metric] = {}
            for percentile in ('p50_ms', 'p95_ms', 'p99_ms', 'mean_ms'):
                values = [r['analysis'][metric][percentile] for r in selected if r['analysis'][metric]['n']]
                metrics[metric][percentile] = {'median':st.median(values), 'min':min(values), 'max':max(values), 'runs':values}
        groups[workload][backend] = {'rounds':len(selected), 'keys':sum(r['keys'] for r in selected),
            'metrics': metrics, 'typing_cpu_ms': [r['typing_cpu_ms'] for r in selected],
            'typing_end_pss_kib':[r['typing_end_pss_kib'] for r in selected],
            'candidate_key_window_coverage':[r['analysis']['candidate_key_window_coverage'] for r in selected],
            'spaces':[r['analysis']['spaces'] for r in selected],
            'space_detections':[r['analysis']['space_to_editor']['n'] for r in selected]}
summary = {'method':'Median of per-run statistics; keys within one run are not independent experimental repeats',
           'order':[r['label'] for r in runs], 'measured_keys':sum(r['keys'] for r in measured),
           'all_recorded_runs_correct':all(r['correct'] for r in runs), 'groups':groups, 'runs':measured}
if 'steady' in groups:
    assert all(groups['steady'][b]['rounds']==3 for b in ('fcitx','direct'))
    assert len(measured)==10 and len(runs)==14
(root/'comparison.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({'measured_keys':summary['measured_keys'], 'groups':list(groups), 'correct':summary['all_recorded_runs_correct']}))
