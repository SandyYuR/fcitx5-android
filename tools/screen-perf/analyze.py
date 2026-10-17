#!/usr/bin/env python3
"""Uploaded BenchTrace pipeline with independent marker-clock calibration, never candidate fitting."""
import bisect
import csv
import json
import statistics as st
import subprocess as sp
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

def command(args):
    return sp.check_output(list(map(str, args)), text=True)

def percentiles(values):
    if not values: return {'n': 0}
    v = sorted(values)
    return {'n': len(v), 'mean_ms': st.mean(v), **{f'p{p}_ms': v[max(0, (len(v)*p+99)//100-1)] for p in (50, 95, 99)}}

def analyze(run):
    result = json.loads((run/'result.json').read_text())
    host = json.loads((run/'host-before.json').read_text())
    after = json.loads((run/'host-after.json').read_text())
    probe = json.loads(command(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames', '-show_streams', '-show_entries',
        'frame=best_effort_timestamp_time:stream=width,height,r_frame_rate,avg_frame_rate,time_base', '-of', 'json', run/'run.mp4']))
    (run/'ffprobe.json').write_text(json.dumps(probe, indent=2))
    pts = [float(f['best_effort_timestamp_time']) for f in probe['frames']]
    (run/'pts.txt').write_text(''.join(f'{t}\n' for t in pts))
    width, height = probe['streams'][0]['width'], probe['streams'][0]['height']
    png = (run/'before.png').read_bytes()
    assert png[:8] == b'\x89PNG\r\n\x1a\n'
    screen_width, screen_height = struct.unpack('>II', png[16:24])
    sx, sy = width/screen_width, height/screen_height
    assert abs(sx-sy) < .01, 'Unexpected recording aspect ratio'
    def scaled(rect): return [round(v*(sx if i%2 == 0 else sy)) for i,v in enumerate(rect)]
    keys = {key:scaled(rect) for key,rect in result['keys'].items()}
    outw = 600
    # Four actual keyboard rows, a candidate-only strip, and the neutral editor.
    rows = sorted({tuple(r) for key, r in keys.items() if key != ' '}, key=lambda r:r[1])
    groups = []
    for r in rows:
        if not groups or abs(r[1]-groups[-1][0]) > 10: groups.append([r[1], r[3]])
        else: groups[-1][1] = max(groups[-1][1], r[3])
    assert len(groups) == 3, groups
    space = keys[' ']; groups.append([space[1], space[3]])
    candidate = scaled(result['candidate']); editor = scaled(host['editor'])
    regions = [(0, y0, width, y1-y0, 1) for y0, y1 in groups]
    regions += [(0, candidate[1], width, candidate[3]-candidate[1], 3),
                (editor[0], editor[1], editor[2]-editor[0], editor[3]-editor[1], 16)]
    branches = ''.join(f'[s{i}]' for i in range(6))
    filters = [f'[0:v]split=6{branches}']
    filters += [f'[s{i}]crop={w}:{h}:{x}:{y},scale={outw}:{oh}:flags=area[p{i}]' for i,(x,y,w,h,oh) in enumerate(regions)]
    filters += [''.join(f'[p{i}]' for i in range(6))+'vstack=inputs=6,format=rgb24[v]']
    raw = run/'stack.rgb'
    sp.run(['ffmpeg', '-v', 'error', '-y', '-threads', '1', '-i', str(run/'run.mp4'), '-filter_complex_threads', '1',
        '-filter_complex', ';'.join(filters), '-map', '[v]', '-vsync', '0', '-enc_time_base', '-1', '-f', 'rawvideo', str(raw)], check=True)
    assert raw.stat().st_size == len(pts)*outw*23*3, 'PTS/frame count mismatch'
    classes = run.parent/'java-classes'; classes.mkdir(exist_ok=True)
    sp.run(['javac', '-d', str(classes), str(HERE/'upstream/BenchTrace.java')], check=True)
    with (run/'trace.tsv').open('w') as target:
        sp.run(['java', '-cp', str(classes), 'BenchTrace', str(raw), str(outw), str(run/'pts.txt'), '60'], stdout=target, check=True)
    raw.unlink()
    # An independent editor-owned rectangle changes colour before and after typing.
    # Choreographer's device-monotonic frame time maps to its first visible video frame.
    marker = run/'marker.rgb'
    marker_rect = scaled(host['marker'])
    mx, my = (marker_rect[0]+marker_rect[2])//2-8, (marker_rect[1]+marker_rect[3])//2-8
    sp.run(['ffmpeg', '-v', 'error', '-y', '-threads', '1', '-i', str(run/'run.mp4'),
        '-vf', f'crop=16:16:{mx}:{my},scale=1:1:flags=area,format=rgb24', '-vsync', '0', '-enc_time_base', '-1', '-f', 'rawvideo', str(marker)], check=True)
    colors = marker.read_bytes(); assert len(colors) == len(pts)*3
    states = [colors[i] > colors[i+2] for i in range(0, len(colors), 3)]
    edges = [i for i in range(1, len(states)) if states[i] != states[i-1]]
    markers = after['marker_frames']
    assert len(edges) == len(markers) == 12, (len(edges), len(markers))
    offsets = [m['frame_ns']/1e6-pts[i]*1000 for i,m in zip(edges, markers)]
    offset = st.median(offsets)
    residuals = [v-offset for v in offsets]
    drift = st.median(offsets[6:])-st.median(offsets[:6])
    # Reject broken clocks; do not optimise these parameters using candidate responses.
    assert max(residuals)-min(residuals) <= 50 and abs(drift) <= 34, (residuals, drift)
    trace = list(csv.DictReader((run/'trace.tsv').open(), delimiter='\t'))
    events = [e for e in result['events'] if e['kind'] == 'key']
    keytimes = [e['down_ms'] for e in events]
    end = events[-1]['return_ms']+600
    cand = [float(r['t'])*1000+offset for r in trace if int(r['bar_p']) >= 5 and keytimes[0] <= float(r['t'])*1000+offset <= end]
    commits = [float(r['t'])*1000+offset for r in trace if int(r['ed_p']) >= 2 and keytimes[0] <= float(r['t'])*1000+offset <= end]
    # All-key forward metric preserves the supplied tool's definition. It is not per-key
    # engine latency: an unchanged candidate can make it refer to a later key's update.
    forward, attributed, commit, in_window = [], [], [], []
    matches = []
    for i, t in enumerate(keytimes):
        j = bisect.bisect_left(cand, t)
        if j < len(cand) and cand[j]-t <= 500:
            forward.append(cand[j]-t); matches.append(j)
            if cand[j] < (keytimes[i+1] if i+1 < len(keytimes) else end):
                in_window.append(cand[j]-t)
        if events[i]['key'] == ' ':
            j = bisect.bisect_left(commits, t)
            if j < len(commits) and commits[j]-t <= 500: commit.append(commits[j]-t)
    for t in cand:
        i = bisect.bisect_right(keytimes, t)-1
        if i >= 0 and t-keytimes[i] <= 500: attributed.append(t-keytimes[i])
    late = [max(0, e['down_ms']-e['due_ms']) for e in events]
    cadence = [b-a for a,b in zip(keytimes, keytimes[1:])]
    summary = {'metric': 'Device touch DOWN to visible pixel change, aligned by independent editor marker; includes 32ms key hold, IME, editor, rendering and video sampling',
        'clock': {'method': 'median(marker Choreographer frame clock - video PTS), no candidate-derived clock fitting',
                  'offset_ms': offset, 'residual_ms': residuals, 'start_end_drift_ms': drift},
        'video': {'width': width, 'height': height, 'screen_width':screen_width, 'screen_height':screen_height, 'frames': len(pts), 'frame_interval_ms': percentiles([(b-a)*1000 for a,b in zip(pts,pts[1:])]), 'stream':probe['streams'][0]},
        'threshold': {'rgb_sum': 60, 'candidate_changed_pixels': 5, 'editor_changed_pixels': 2},
        'keys': len(events), 'spaces': sum(e['key']==' ' for e in events), 'candidate_changes': len(cand), 'editor_changes': len(commits),
        'forward_candidate': percentiles(forward), 'attributed_candidate': percentiles(attributed), 'space_to_editor': percentiles(commit),
        'first_candidate_before_next_key': percentiles(in_window),
        'candidate_key_window_coverage': len(in_window)/len(events),
        'shared_next_candidate_event': len(matches)-len(set(matches)), 'unmatched_keys': len(events)-len(forward),
        'injection_schedule_lateness': percentiles(late), 'actual_key_interval': percentiles(cadence),
        'note': 'Attributed means latest-key attribution of visible changes, not isolated engine cost. Unmatched/shared events do not prove dropped input.'}
    assert forward and len(commit) >= summary['spaces']*.8, 'Insufficient visual detections'
    (run/'analysis.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps({'run':run.name, 'candidate':summary['forward_candidate'], 'space':summary['space_to_editor'], 'clock_drift_ms':drift}), flush=True)

if __name__ == '__main__': analyze(Path(sys.argv[1]).resolve())
