"""Associate individual real input timestamps with existing native trace slices."""
import argparse
import csv
import io
import json
from pathlib import Path
import subprocess


CADENCE_PROTOCOLS={
    'fixed_cadence_100ms_input_manager_async_cached_letters',
    'fixed_cadence_100ms_input_manager_async_cached_targets_v2',
}


def query(processor,trace,sql):
    result=subprocess.run([str(processor),'query','--no-agent','--format','csv',str(trace),sql],
        capture_output=True,text=True,check=True)
    return list(csv.DictReader(io.StringIO(result.stdout)))


def interval_evidence(start,end,utid,sched,states):
    """A zero CPU result is meaningful only when the thread is observed throughout."""
    clipped=[(max(start,ts),min(end,ts+dur),state)
        for ts,dur,thread_id,state in states
        if end>=start and thread_id==utid and ts<end and ts+dur>start]
    state_ns={}
    covered=0
    cursor=start
    for left,right,state in sorted(clipped):
        state_ns[state]=state_ns.get(state,0)+right-left
        covered+=max(0,right-max(cursor,left))
        cursor=max(cursor,right)
    cpu_ns=sum(max(0,min(end,ts+dur)-max(start,ts))
        for ts,dur,thread_id in sched if thread_id==utid)
    running_ns=state_ns.get('Running',0)
    duration=end-start
    # Union coverage detects gaps; the sum also rejects overlapping state records.
    complete=(duration>=0 and covered==duration and sum(state_ns.values())==duration
        and cpu_ns==running_ns)
    return {'utid':utid,'state_covered_ns':covered,'interval_ns':duration,
        'running_ns':running_ns,'sched_cpu_ns':cpu_ns,'complete':complete,
        'cpu_ms':cpu_ns/1e6 if complete else None,
        'state_ms':{state:ns/1e6 for state,ns in state_ns.items()}}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--trace',type=Path,required=True)
    p.add_argument('--processor','--trace-processor',dest='processor',type=Path,required=True)
    p.add_argument('--data',type=Path,required=True)
    p.add_argument('--kind',choices=['candidate','repeat','cadence'],required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    sql_dir=a.output/'queries';sql_dir.mkdir(exist_ok=True)
    def recorded_query(name,sql):
        (sql_dir/(name+'.sql')).write_text(sql.strip()+'\n')
        return query(a.processor,a.trace,sql)
    def save_csv(name,result):
        if result:
            with (a.output/(name+'.csv')).open('w') as f:
                writer=csv.DictWriter(f,fieldnames=list(result[0]),lineterminator='\n')
                writer.writeheader();writer.writerows(result)
    queries={
      'slices':'''SELECT s.id,s.ts,s.dur,s.name,s.parent_id,t.utid,t.tid,t.name AS thread_name,
                 p.pid,p.upid,p.name AS process_name FROM slice s
                 JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
                 JOIN process p ON t.upid=p.upid
                 WHERE s.dur>=0 AND (s.name GLOB 'rime.*' OR s.name='HandleFcitxEvent'
                 OR s.name GLOB '*InputEvent*' OR s.name GLOB '*inputEvent*'
                 OR s.name GLOB 'Choreographer*' OR s.name GLOB '*JIT*'
                 OR s.name GLOB '*commitText*') ORDER BY s.ts''',
      'sched':'''SELECT s.ts,s.dur,s.utid,s.cpu FROM sched s JOIN thread t USING(utid)
                JOIN process p USING(upid) WHERE p.upid IN
                (SELECT DISTINCT t2.upid FROM slice a JOIN thread_track tt2 ON a.track_id=tt2.id
                 JOIN thread t2 ON tt2.utid=t2.utid WHERE a.name GLOB 'rime.direct.*')
                OR p.name GLOB '*inputbench*' ORDER BY s.ts''',
      'thread_states':'''SELECT s.ts,s.dur,s.utid,s.state,s.io_wait,s.blocked_function
                FROM thread_state s JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE s.dur>0 AND (p.upid IN
                (SELECT DISTINCT t2.upid FROM slice a JOIN thread_track tt2 ON a.track_id=tt2.id
                 JOIN thread t2 ON tt2.utid=t2.utid WHERE a.name GLOB 'rime.direct.*')
                OR p.name GLOB '*inputbench*') ORDER BY s.ts''',
      'loss':'''SELECT name,idx,severity,value FROM stats WHERE value>0 AND
               (severity IN ('error','data_loss') OR name GLOB '*lost*' OR name GLOB '*drop*')''',
      'bounds':'SELECT * FROM trace_bounds',
    }
    tables={}
    for name,sql in queries.items():
        result=recorded_query(name,sql);tables[name]=result
        save_csv(name,result)
    slices=[]
    for row in tables['slices']:
        converted=dict(row)
        for key in ['id','ts','dur','utid','tid','pid','upid']:
            converted[key]=int(row[key])
        slices.append(converted)
    native=[row for row in slices if row['name'].startswith('rime.direct.')]
    errors=[]
    unique_process=len({row['upid'] for row in native})==1
    if not unique_process:
        errors.append('Expected exactly one native Rime process instance (upid).')
    bounds=tables['bounds']
    try:
        trace_start,trace_end=(int(bounds[0][key]) for key in ('start_ts','end_ts')) if len(bounds)==1 else (0,0)
    except (KeyError,TypeError,ValueError):
        trace_start,trace_end=0,0
    valid_bounds=trace_start<trace_end
    if not valid_bounds:
        errors.append('Trace bounds are missing or invalid.')
    def covered_by_trace(label,start,end):
        covered=(valid_bounds and trace_start<=start<=end<=trace_end)
        if not covered:
            errors.append(f'{label}: interval [{start}, {end}] is outside trace bounds or reversed.')
        return covered
    sched=[(int(s['ts']),int(s['dur']),int(s['utid'])) for s in tables['sched'] if int(s['dur'])>0]
    states=[(int(s['ts']),int(s['dur']),int(s['utid']),s['state']) for s in tables['thread_states']]
    def core_cpu_ms(row):
        start,end=row['ts'],row['ts']+row['dur']
        return sum(max(0,min(end,ts+dur)-max(start,ts)) for ts,dur,utid in sched if utid==row['utid'])/1e6
    source=json.loads(a.data.read_text());anchors=[]
    if a.kind=='candidate':
        for i,row in enumerate(source['ime']['rounds']):
            for kind in ['n','select']:
                event=row[kind]
                end=row['preedit_collected_ns'] if kind=='n' else source['editor']['commits'][i]['at_ns']
                anchors.append({'phase':'single_editor','round':i+1,'kind':kind,
                    'start_ns':event['dispatch_down_ns'],'up_ns':event['dispatch_up_ns'],
                    'injection_up_ns':event['inject_up_ns'],'end_ns':end})
    elif a.kind=='repeat':
        for row in source['samples']:
            phase=row['phase'];index=row['round']
            editor=json.loads((a.data.parent/(phase+'-editor.json')).read_text())
            commits=[event for event in editor['commits'] if event['text']]
            anchors.append({'phase':phase,'round':index+1,'kind':'select',
                'start_ns':row['candidate_down_ns'],'up_ns':row['candidate_up_ns'],
                'injection_up_ns':row['candidate_up_ns'],'end_ns':commits[index]['at_ns']})
    associated=[]
    for anchor in anchors:
        name='rime.direct.process_key' if anchor['kind']=='n' else 'rime.direct.action_core'
        core=[row for row in native if row['name']==name and anchor['start_ns']<=row['ts']<anchor['end_ns']]
        snapshots=[row for row in native if row['name']=='rime.direct.snapshot' and anchor['start_ns']<=row['ts']<anchor['end_ns']]
        selected=[row for row in native if row['name']=='rime.direct.select_candidate' and anchor['start_ns']<=row['ts']<anchor['end_ns']]
        primary=sorted(core,key=lambda row:row['dur'],reverse=True)
        total=(anchor['end_ns']-anchor['up_ns'])/1e6
        row=dict(anchor,total_up_ms=total,core_calls=len(core),core_wall_ms=sum(c['dur'] for c in core)/1e6,
            core_cpu_ms=sum(core_cpu_ms(c) for c in core),snapshot_ms=sum(c['dur'] for c in snapshots)/1e6,
            core_first_after_up_ms=(min(c['ts'] for c in core)-anchor['up_ns'])/1e6 if core else None,
            after_native_to_endpoint_ms=(anchor['end_ns']-max(c['ts']+c['dur'] for c in (selected or core)))/1e6 if core else None,
            selected_native_wall_ms=sum(c['dur'] for c in selected)/1e6,
            longest_core=primary[0] if primary else None)
        state_ns={}
        for core_row in core:
            start,end=core_row['ts'],core_row['ts']+core_row['dur']
            for ts,dur,utid,state in states:
                overlap=max(0,min(end,ts+dur)-max(start,ts)) if utid==core_row['utid'] else 0
                if overlap:state_ns[state]=state_ns.get(state,0)+overlap
        row['core_thread_state_ms']={k:v/1e6 for k,v in state_ns.items()}
        if anchor['kind']=='n' and anchor['round']==1 and primary:
            first=primary[0];start,end=first['ts'],first['ts']+first['dur']
            name='first-core-system-sched'
            sql=f'''SELECT t.tid,t.name AS thread,p.pid,p.name AS process,s.priority,
                SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1000000.0 AS cpu_ms
                FROM sched s JOIN thread t USING(utid) LEFT JOIN process p USING(upid)
                WHERE s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start}
                GROUP BY t.utid,s.priority ORDER BY cpu_ms DESC'''
            save_csv(name,recorded_query(name,sql))
        associated.append(row)
    # A native duration cannot explain an event that has not reached the IME window.
    # Keep scheduler and graphics attribution for that earlier interval separately.
    threads=recorded_query('ime-threads','''SELECT t.utid,t.tid,t.name,p.pid,p.upid FROM thread t
        JOIN process p USING(upid) WHERE t.upid IN
        (SELECT DISTINCT t2.upid FROM slice s JOIN thread_track tt ON s.track_id=tt.id
         JOIN thread t2 ON tt.utid=t2.utid WHERE s.name GLOB 'rime.direct.*')''')
    save_csv('ime-threads',threads)
    main_threads={int(t['utid']) for t in threads if t['tid']==t['pid']}
    render_threads={int(t['utid']) for t in threads if t['name']=='RenderThread'}
    owner_threads={int(t['utid']) for t in threads if t['name']=='rime-main'}
    input_waits=[]
    if a.kind in ('candidate','cadence'):
        for i,round_data in enumerate(source['ime']['rounds']):
            for key in ['n','i','select']:
                event=round_data[key];start,end=event['inject_up_ns'],event['dispatch_up_ns']
                wait=dict(round=i+1,key=key,start_ns=start,end_ns=end,wait_ms=(end-start)/1e6)
                label=f'round {i+1} {key} input wait'
                in_bounds=covered_by_trace(label,start,end)
                wait['thread_cpu_ms']={}
                wait['thread_coverage']={}
                for name,ids in [('main',main_threads),('render',render_threads),('owner',owner_threads)]:
                    evidence=interval_evidence(start,end,next(iter(ids)),sched,states) if len(ids)==1 else None
                    wait['thread_coverage'][name]=evidence
                    valid=unique_process and in_bounds and evidence is not None and evidence['complete']
                    wait['thread_cpu_ms'][name]=evidence['cpu_ms'] if valid else None
                    if not valid:
                        errors.append(f'{label}: {name} thread identity, state coverage or scheduler coverage is incomplete.')
                main_evidence=wait['thread_coverage']['main']
                wait['main_thread_state_ms']=main_evidence['state_ms'] if main_evidence else {}
                input_waits.append(wait)
                if end-start>8e6:
                    name=f'input-wait-r{i+1}-{key}'
                    sql=f'''SELECT s.id,s.ts,s.dur,s.name,t.utid,t.tid,t.name AS thread,p.pid,
                        p.name AS process FROM slice s JOIN thread_track tt ON s.track_id=tt.id
                        JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
                        WHERE s.dur>0 AND t.tid=p.pid AND s.ts<{end} AND s.ts+s.dur>{start}
                        AND p.upid IN (SELECT DISTINCT t2.upid FROM slice a
                        JOIN thread_track tt2 ON a.track_id=tt2.id JOIN thread t2 ON tt2.utid=t2.utid
                        WHERE a.name GLOB 'rime.direct.*') ORDER BY s.ts,s.dur DESC'''
                    overlap=recorded_query(name,sql)
                    for item in overlap:
                        item['cpu_ms']=core_cpu_ms({k:int(item[k]) for k in ['ts','dur','utid']})
                    save_csv(name,overlap)
                    graphics_sql=f'''SELECT s.id,s.ts,s.dur,s.name,t.utid,t.tid,t.name AS thread,p.pid
                        FROM slice s JOIN thread_track tt ON s.track_id=tt.id
                        JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
                        WHERE s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start}
                        AND (s.name='postAndWait' OR s.name GLOB '*eglSwap*'
                             OR s.name GLOB '*rcCreateSync*')
                        AND p.upid IN (SELECT DISTINCT t2.upid FROM slice a
                        JOIN thread_track tt2 ON a.track_id=tt2.id JOIN thread t2 ON tt2.utid=t2.utid
                        WHERE a.name GLOB 'rime.direct.*') ORDER BY s.ts'''
                    graphics=recorded_query(name+'-graphics',graphics_sql)
                    for item in graphics:
                        item['overlap_ms']=max(0,min(end,int(item['ts'])+int(item['dur']))-max(start,int(item['ts'])))/1e6
                    save_csv(name+'-graphics',graphics)
                    wait['post_and_wait_overlap_ms']=sum(item['overlap_ms'] for item in graphics
                        if item['name']=='postAndWait' and item['tid']==item['pid'])
                    wait['graphics_slices']=graphics
    # Keep all evidence, but reject incomplete/ambiguous attribution instead of reporting
    # a missing scheduler as zero CPU or combining two processes into one input event.
    if tables['loss']:
        errors.append('Trace reports errors or data loss.')
    if not input_waits and not associated:
        errors.append('No input events or native anchors were supplied.')
    if a.kind=='cadence':
        # Fast input can legitimately coalesce the intermediate "n" presentation.
        # Attribute dispatch waits directly; do not invent a native endpoint for it.
        if source['ime'].get('injection_protocol') not in CADENCE_PROTOCOLS:
            errors.append('Unexpected cadence injection protocol.')
        for row in source['ime']['rounds']:
            if row['i']['planned_down_ns']-row['n']['planned_down_ns']!=100_000_000:
                errors.append('Planned n/i cadence is not 100 ms.')
            for key in ('n','i','select'):
                event=row[key]
                if (not event['geometry_valid'] or
                    event['planned_up_ns']-event['planned_down_ns']!=20_000_000 or
                    not event['inject_down_ns']<=event['inject_up_ns']<=event['dispatch_up_ns']):
                    errors.append(f'Invalid cadence touch: {key}.')
    for row in associated:
        core=row['longest_core']
        label=f"{row['phase']} round {row['round']} {row['kind']} native anchor"
        in_bounds=covered_by_trace(label,row['start_ns'],row['end_ns'])
        evidence=interval_evidence(core['ts'],core['ts']+core['dur'],core['utid'],sched,states) if core else None
        row['core_thread_coverage']=evidence
        valid_cpu=unique_process and in_bounds and evidence is not None and evidence['complete']
        row['core_cpu_ms']=evidence['cpu_ms'] if valid_cpu and row['core_calls']==1 else None
        if (row['core_calls']!=1 or not core or
            not row['start_ns']<=row['up_ns']<=row['end_ns'] or
            core['ts']+core['dur']>row['end_ns'] or
            not valid_cpu):
            errors.append(f'{label}: native association or thread coverage is incomplete or ambiguous.')
    result={'trace':a.trace.name,'data':a.data.name,'kind':a.kind,'bounds':tables['bounds'],
        'trace_stats':tables['loss'],'native_slices':len(native),'anchors':associated,'input_waits':input_waits,
        'missing_native_anchors':[{key:r[key] for key in ['phase','round','kind']} for r in associated if not r['core_calls']],
        'valid':not errors,'validation_errors':errors}
    (a.output/'associated.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'trace':a.trace.name,'anchors':len(associated),'missing':result['missing_native_anchors'],
        'trace_stats':result['trace_stats'],'valid':result['valid'],'validation_errors':errors}))
    if errors:
        raise SystemExit('Attribution failed validation; raw queries and associated.json were preserved.')


if __name__=='__main__':main()
