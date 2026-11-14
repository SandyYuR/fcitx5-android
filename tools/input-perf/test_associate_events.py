"""Exercise attribution validation without a device or trace-processor subprocess."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec=importlib.util.spec_from_file_location('associate_events',Path(__file__).with_name('associate-events.py'))
associate=importlib.util.module_from_spec(spec)
spec.loader.exec_module(associate)
MS=1_000_000


def touch(start):
    return {'planned_down_ns':start,'planned_up_ns':start+20*MS,
        'inject_down_ns':start,'inject_up_ns':start+20*MS,
        'dispatch_down_ns':start+MS,'dispatch_up_ns':start+25*MS,
        'geometry_valid':True}


class AssociationValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='input-perf-association-')
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.tables={
            'bounds':[{'start_ts':'0','end_ts':str(1000*MS)}],
            'loss':[],
            'threads':[
                {'utid':'1','tid':'100','pid':'100','upid':'1','name':'main'},
                {'utid':'2','tid':'101','pid':'100','upid':'1','name':'rime-main'},
                {'utid':'3','tid':'102','pid':'100','upid':'1','name':'RenderThread'},
            ],
            'states':[self.state(utid,0,1000*MS,'Running' if utid==2 else 'S') for utid in (1,2,3)],
            'sched':[{'ts':'0','dur':str(1000*MS),'utid':'2','cpu':'0'}],
            'slices':[
                self.native(1,127*MS,2*MS,'rime.direct.process_key'),
                self.native(2,327*MS,2*MS,'rime.direct.action_core'),
            ],
        }
        self.source={'ime':{'injection_protocol':'fixed_cadence_100ms_input_manager_async_cached_targets_v2',
            'rounds':[{'n':touch(100*MS),'i':touch(200*MS),'select':touch(300*MS),
                       'preedit_collected_ns':135*MS}]},
            'editor':{'commits':[{'text':'你','at_ns':335*MS}]}}

    @staticmethod
    def native(identifier,start,duration,name,upid=1):
        return {'id':str(identifier),'ts':str(start),'dur':str(duration),'name':name,
            'parent_id':'[NULL]','utid':'2','tid':'101','pid':'100','upid':str(upid),
            'thread_name':'rime-main','process_name':'test-ime'}

    @staticmethod
    def state(utid,start,duration,state):
        return {'ts':str(start),'dur':str(duration),'utid':str(utid),'state':state,
            'io_wait':'[NULL]','blocked_function':'[NULL]'}

    def query(self,processor,trace,sql):
        if 'SELECT * FROM trace_bounds' in sql:
            key='bounds'
        elif 'FROM stats' in sql:
            key='loss'
        elif 'FROM thread_state' in sql:
            key='states'
        elif 'SELECT s.ts,s.dur,s.utid,s.cpu FROM sched' in sql:
            key='sched'
        elif 'SELECT t.utid,t.tid,t.name,p.pid,p.upid' in sql:
            key='threads'
        elif 's.parent_id' in sql:
            key='slices'
        else:
            return []  # Optional detailed graphics/system scheduling queries.
        return copy.deepcopy(self.tables[key])

    def run_analysis(self,kind='cadence',success=True):
        source=self.source
        if kind=='repeat':
            source={'samples':[{'phase':'editor','round':0,
                'candidate_down_ns':self.source['ime']['rounds'][0]['select']['dispatch_down_ns'],
                'candidate_up_ns':self.source['ime']['rounds'][0]['select']['dispatch_up_ns']}]}
            (self.root/'editor-editor.json').write_text(json.dumps(self.source['editor']))
        path=self.root/'events.json'
        path.write_text(json.dumps(source))
        output=self.root/kind
        argv=['associate-events.py','--trace','mock.trace','--processor','unused',
            '--data',str(path),'--kind',kind,'--output',str(output)]
        with patch.object(sys,'argv',argv),patch.object(associate,'query',side_effect=self.query),contextlib.redirect_stdout(io.StringIO()):
            if success:
                associate.main()
            else:
                with self.assertRaisesRegex(SystemExit,'Attribution failed validation'):
                    associate.main()
        result=json.loads((output/'associated.json').read_text())
        self.assertEqual(result['valid'],success)
        self.assertEqual(bool(result['validation_errors']),not success)
        self.assertTrue((output/'queries'/'bounds.sql').is_file())
        self.assertTrue((output/'queries'/'sched.sql').is_file())
        self.assertTrue((output/'slices.csv').is_file() or not self.tables['slices'])
        return result

    def test_valid_candidate_repeat_and_cadence_samples(self):
        for kind in ('candidate','repeat','cadence'):
            with self.subTest(kind=kind):
                result=self.run_analysis(kind)
                if result['anchors']:
                    self.assertEqual(result['anchors'][0]['core_cpu_ms'],2.0)

    def test_legacy_cadence_protocol_remains_readable(self):
        self.source['ime']['injection_protocol']='fixed_cadence_100ms_input_manager_async_cached_letters'
        self.run_analysis()

    def test_events_outside_trace_fail_in_every_mode(self):
        self.tables['bounds'][0]['end_ts']=str(10*MS)
        for kind in ('candidate','repeat','cadence'):
            with self.subTest(kind=kind):
                result=self.run_analysis(kind,success=False)
                self.assertTrue(any('outside trace bounds' in error for error in result['validation_errors']))

    def test_partially_covered_input_interval_fails(self):
        self.tables['bounds'][0]['end_ts']=str(322*MS)
        result=self.run_analysis(success=False)
        self.assertEqual(result['input_waits'][0]['thread_cpu_ms']['main'],0.0)
        self.assertIsNone(result['input_waits'][2]['thread_cpu_ms']['main'])

    def test_gap_in_main_thread_states_is_not_reported_as_idle(self):
        self.tables['states']=[row for row in self.tables['states'] if row['utid']!='1']
        self.tables['states'] += [self.state(1,0,122*MS,'S'),self.state(1,123*MS,877*MS,'S')]
        result=self.run_analysis(success=False)
        self.assertIsNone(result['input_waits'][0]['thread_cpu_ms']['main'])
        self.assertEqual(result['input_waits'][0]['thread_coverage']['main']['state_covered_ns'],4*MS)

    def test_missing_sched_for_running_state_fails(self):
        self.tables['sched']=[]
        result=self.run_analysis(success=False)
        self.assertIsNone(result['input_waits'][0]['thread_cpu_ms']['owner'])

    def test_fully_observed_idle_interval_accepts_zero_cpu(self):
        self.tables['states']=[self.state(utid,0,1000*MS,'S') for utid in (1,2,3)]
        self.tables['sched']=[]
        result=self.run_analysis()
        self.assertEqual(result['input_waits'][0]['thread_cpu_ms'],{'main':0.0,'render':0.0,'owner':0.0})

    def test_gap_covering_native_core_fails_even_when_input_waits_are_covered(self):
        self.tables['states']=[row for row in self.tables['states'] if row['utid']!='2']
        self.tables['states'] += [self.state(2,0,127*MS,'Running'),self.state(2,129*MS,871*MS,'Running')]
        result=self.run_analysis('candidate',success=False)
        self.assertIsNone(result['anchors'][0]['core_cpu_ms'])
        self.assertTrue(result['input_waits'][0]['thread_coverage']['owner']['complete'])

    def test_two_native_process_instances_with_same_pid_fail_in_every_mode(self):
        self.tables['slices'].append(self.native(3,900*MS,MS,'rime.direct.snapshot',upid=2))
        for kind in ('candidate','repeat','cadence'):
            with self.subTest(kind=kind):
                result=self.run_analysis(kind,success=False)
                self.assertTrue(any('exactly one native Rime process' in error for error in result['validation_errors']))

    def test_missing_thread_does_not_become_zero_cpu(self):
        self.tables['threads']=[row for row in self.tables['threads'] if row['name']!='RenderThread']
        result=self.run_analysis(success=False)
        self.assertIsNone(result['input_waits'][0]['thread_cpu_ms']['render'])

    def test_no_native_process_preserves_failure_evidence(self):
        self.tables['slices']=[]
        self.run_analysis(success=False)

    def test_missing_or_null_trace_bounds_preserve_failure_evidence(self):
        for bounds in ([],[{'start_ts':'[NULL]','end_ts':'[NULL]'}]):
            with self.subTest(bounds=bounds):
                self.tables['bounds']=bounds
                result=self.run_analysis(success=False)
                self.assertIn('Trace bounds are missing or invalid.',result['validation_errors'])

    def test_overlapping_states_cannot_hide_a_coverage_gap(self):
        self.tables['states']=[row for row in self.tables['states'] if row['utid']!='1']
        self.tables['states'] += [self.state(1,120*MS,3*MS,'S'),self.state(1,120*MS,2*MS,'S')]
        result=self.run_analysis(success=False)
        self.assertIsNone(result['input_waits'][0]['thread_cpu_ms']['main'])
        self.assertEqual(result['input_waits'][0]['thread_coverage']['main']['state_covered_ns'],3*MS)

    def test_reversed_wait_preserves_failure_evidence(self):
        self.source['ime']['rounds'][0]['i']['dispatch_up_ns']=219*MS
        result=self.run_analysis(success=False)
        self.assertIsNone(result['input_waits'][1]['thread_cpu_ms']['main'])


if __name__=='__main__':
    unittest.main()
