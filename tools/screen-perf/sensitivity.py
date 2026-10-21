#!/usr/bin/env python3
"""Recompute the uploaded final_latency.js pixel gates without refitting clocks."""
import bisect
import csv
import gzip
import json
import sys
from pathlib import Path
from analyze import percentiles

root = Path(sys.argv[1]).resolve()
runs = json.loads((root/'runs.json').read_text())
output = {'method': 'Original candidate >25 / editor >15 pixel gates; fixed primary marker clock; same 500ms matching window', 'runs': []}
for item in runs:
    if item['mode'] != 'touch': continue
    directory = root/item['label']
    source = directory/'result.json'
    details = json.loads(source.read_text() if source.exists() else gzip.decompress(source.with_suffix('.json.gz').read_bytes()))
    primary = json.loads((directory/'analysis.json').read_text())
    events = [e for e in details['events'] if e['kind'] == 'key']
    keytimes = [e['down_ms'] for e in events]
    trace_path = directory/'trace.tsv'
    stream = trace_path.open() if trace_path.exists() else gzip.open(str(trace_path)+'.gz', 'rt')
    with stream: trace = list(csv.DictReader(stream, delimiter='\t'))
    offset = primary['clock']['offset_ms']
    end = events[-1]['return_ms']+600
    signals = {name:[float(row['t'])*1000+offset for row in trace
                     if int(row[column]) > gate and keytimes[0] <= float(row['t'])*1000+offset <= end]
               for name,column,gate in [('candidate','bar_p',25),('editor','ed_p',15)]}
    forward,window,commits = [],[],[]
    for index,event in enumerate(events):
        t = event['down_ms']; candidate = signals['candidate']; editor = signals['editor']
        j = bisect.bisect_left(candidate,t)
        if j < len(candidate) and candidate[j]-t <= 500:
            forward.append(candidate[j]-t)
            if candidate[j] < (keytimes[index+1] if index+1 < len(events) else end): window.append(candidate[j]-t)
        if event['key'] == ' ':
            j = bisect.bisect_left(editor,t)
            if j < len(editor) and editor[j]-t <= 500: commits.append(editor[j]-t)
    output['runs'].append({'label':item['label'],'backend':item['backend'],
        'forward_candidate':percentiles(forward),'first_candidate_before_next_key':percentiles(window),
        'candidate_key_window_coverage':len(window)/len(events),'space_to_editor':percentiles(commits),
        'spaces':primary['spaces']})
(root/'sensitivity.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'runs':len(output['runs']),'clock_refit':False}))
