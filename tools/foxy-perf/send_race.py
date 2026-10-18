#!/usr/bin/env python3
"""Observe real candidate/Send ordering; never fabricate an IME processing delay."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess as sp
from pilot import adb, selected_ime, HOST
from run import PACKAGES, CASES


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--points',type=Path,required=True)
    p.add_argument('--trials',type=int,default=3)
    a = p.parse_args()
    assert adb('get-serialno').startswith('emulator-'), 'Disposable emulator only'
    a.output.mkdir(parents=True,exist_ok=True)
    points = json.loads(a.points.read_text())
    observations, failures = [], []
    for trial in range(a.trials):
        for case, (sequence, word) in CASES.items():
            for gap in (20,100):
                order = list(PACKAGES) if (trial+(gap==100)+(case=='phrase'))%2==0 else list(reversed(PACKAGES))
                for label in order:
                    package = PACKAGES[label]
                    out = a.output/f'{trial:02d}-{case}-{gap}-{label}'
                    out.mkdir(exist_ok=True)
                    adb('shell','am','start','-W','-a','android.settings.SETTINGS')
                    for pkg in [HOST,*PACKAGES.values()]:
                        adb('shell','am','force-stop',pkg,check=False)
                    adb('shell','run-as',HOST,'sh','-c',shlex.quote('rm -f files/*.json files/*.png'))
                    selected_ime(package)
                    adb('logcat','-c')
                    args = ['am','instrument','-w','-e','package',package,'-e','points',json.dumps(points[label],separators=(',',':')),
                            '-e','sequence',sequence,'-e','gap',str(gap),HOST+'/.SendReplay']
                    try:
                        response = adb('shell',shlex.join(args),timeout=360)
                    except sp.TimeoutExpired as error:
                        response = 'Instrumentation timeout: '+str(error)
                    (out/'instrumentation.txt').write_text(response)
                    (out/'logcat.txt').write_text(adb('logcat','-d','-v','threadtime'))
                    for name in ['send-race.json','candidate-editor.json','send-race.png']:
                        result = sp.run(['adb','exec-out','run-as',HOST,'cat','files/'+name],capture_output=True)
                        if result.returncode==0:
                            (out/name).write_bytes(result.stdout)
                    row = {'trial':trial,'case':case,'scheduled_gap_ms':gap,'product':label}
                    try:
                        timing = json.loads((out/'send-race.json').read_text())
                        if 'failure' in timing:
                            raise ValueError(timing['failure'])
                        editor = json.loads((out/'candidate-editor.json').read_text())
                        assert len(editor['sends'])==1, 'Send click not observed exactly once'
                        sent = editor['sends'][0]
                        row.update(sent_text=sent['text'],final_draft=editor['text'],
                            candidate_up_to_client_send_ms=(sent['at_ns']-timing['candidate_up_ns'])/1e6,
                            commit_calls=editor['commits'])
                        if sent['text']=='前文'+word and editor['text']=='':
                            row['outcome']='complete'
                        elif sent['text']=='前文' and editor['text']=='':
                            row['outcome']='candidate_missing'
                        elif sent['text']=='前文' and editor['text']==word:
                            row['outcome']='late_in_next_draft'
                        else:
                            row['outcome']='unexpected_text'
                    except (ValueError,AssertionError,FileNotFoundError,KeyError) as error:
                        row.update(outcome='harness_failed',error=str(error))
                        failures.append(row)
                    observations.append(row)
                    print(json.dumps(row,ensure_ascii=False),flush=True)
                    (a.output/'observations.json').write_text(json.dumps(observations,ensure_ascii=False,indent=2))
    summary = {'method':'Separate chat-client activity with existing prefix; real candidate then real Send touch; clears draft and restarts input',
               'limits':'Prepared files/caches; test client is not the user chat app. Passing does not prove the phone failure fixed.',
               'observations':len(observations),'harness_failures':len(failures),'groups':{}}
    for label in PACKAGES:
        for case in CASES:
            for gap in (20,100):
                rows = [r for r in observations if r['product']==label and r['case']==case and r['scheduled_gap_ms']==gap]
                counts = {}
                for row in rows:
                    counts[row['outcome']]=counts.get(row['outcome'],0)+1
                summary['groups'][f'{label}-{case}-{gap}']=counts
    (a.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    if failures:
        raise SystemExit(f'{len(failures)} send diagnostics failed; all evidence retained')


if __name__=='__main__':
    main()
