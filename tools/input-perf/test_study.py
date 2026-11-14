"""Study planning and raw-event validation; never execute a device subprocess."""
import contextlib
from collections import Counter
import importlib.util
import io
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name('run-study.py')
SPEC = importlib.util.spec_from_file_location('run_study', SCRIPT)
study = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(study)
MS = 1_000_000
COMMIT = 'a' * 40


def sample(commit=COMMIT):
    # Keep integer precision visible: these timestamps exceed exact float integers.
    origin = 10 ** 18 + 123
    rounds, commits = [], []
    for index in range(6):
        base = origin + index * 1000 * MS

        def touch(key, offset):
            down = base + offset * MS
            return {'key': key, 'planned_down_ns': down, 'planned_up_ns': down + 20 * MS,
                    'inject_down_ns': down + MS, 'inject_up_ns': down + 21 * MS,
                    'inject_down_return_ns': down + 2 * MS, 'inject_up_return_ns': down + 22 * MS,
                    'dispatch_down_ns': down + 2 * MS, 'dispatch_up_ns': down + 24 * MS,
                    'dispatch_return_ns': down + 25 * MS, 'geometry_valid': True}

        row = {'round': index + 1, 'n': touch('n', 0), 'i': touch('i', 100),
               'select': touch('你', 250), 'ni_ready_ns': base + (130 + index) * MS,
               'preedit_collected_ns': None}
        row['i']['dispatch_up_ns'] += index * MS
        row['i']['dispatch_return_ns'] += index * MS
        commits.append({'at_ns': row['select']['dispatch_up_ns'] + (9 + index) * MS, 'text': '你'})
        rounds.append(row)
    return {'ime': {'commit': commit, 'injection_protocol': study.PROTOCOL,
                    'model_mapped': True, 'fcitx_core_mapped': False, 'rounds': rounds},
            'editor': {'text': '你' * 6, 'commits': commits}}


class StudyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='input-perf-study-')
        self.addCleanup(self.temporary.cleanup)
        self.contexts = contextlib.ExitStack()  # Also supported by Python 3.10.
        self.addCleanup(self.contexts.close)
        self.root = Path(self.temporary.name)
        self.apks = self.root / 'apks'
        self.apks.mkdir()
        self.output = self.root / 'results'
        self.sources = {'baseline': 'a' * 40, 'regressed': 'b' * 40, 'current': 'c' * 40}
        self.products = {'sources': self.sources, 'protocol': study.PROTOCOL, 'harness_sha256': 'd' * 64}
        self.write_manifest()
        self.argv = ['--apks', str(self.apks), '--fixture', str(self.root / 'fixture.zip'),
                     '--fixture-manifest', str(self.root / 'fixture.json'), '--host', str(self.root / 'host.apk'),
                     '--output', str(self.output), '--serial', 'emulator-5574', '--expected-avd-name', 'rime',
                     '--mode', 'pilot', '--seed', '1', '--blocks', '1']
        self.commands = []
        self.fail_at = None
        self.mutate_raw = None
        self.run_mock = self.contexts.enter_context(patch.object(study.subprocess, 'run', side_effect=self.child_run))
        self.popen_mock = self.contexts.enter_context(patch.object(
            study.subprocess, 'Popen', side_effect=AssertionError('Real subprocess creation is forbidden')))
        self.check_output_mock = self.contexts.enter_context(patch.object(
            study.subprocess, 'check_output', side_effect=AssertionError('Real subprocess creation is forbidden')))

    def write_manifest(self):
        (self.apks / 'products.json').write_text(json.dumps(self.products), encoding='utf-8')

    def args(self, extra=()):
        return study.build_parser().parse_args([*self.argv, *extra])

    def execute(self, extra=()):
        with contextlib.redirect_stdout(io.StringIO()):
            return study.run(self.args(['--allow-destructive-reset', *extra]))

    def child_run(self, command, **kwargs):
        self.commands.append(command)
        self.assertEqual(sys.executable, command[0])
        self.assertEqual('run-cadence.py', Path(command[1]).name)
        label = command[command.index('--order') + 1]
        output = Path(command[command.index('--output') + 1])
        output.mkdir(parents=True)
        raw = sample(self.sources[label])
        if self.mutate_raw is not None:
            self.mutate_raw(raw, len(self.commands))
        (output / (label + '-1.json')).write_text(json.dumps(raw), encoding='utf-8')
        kwargs['stdout'].write('Mocked child diagnostic output retained.\n')
        if len(self.commands) == self.fail_at:
            raise subprocess.CalledProcessError(1, command)
        return subprocess.CompletedProcess(command, 0)

    def test_pilot_preserves_explicit_forward_and_reverse_orders_for_every_seed(self):
        for labels in (tuple(self.sources), tuple(reversed(self.sources))):
            for seed in (0, 1, 123):
                self.assertEqual(study.make_plan(labels, 'pilot', 1, seed), [list(labels)])

    def test_formal_three_products_balance_each_complete_group_of_six(self):
        labels = tuple(self.sources)
        plan = study.make_plan(labels, 'formal', 12, 7)
        self.assertEqual(plan, study.make_plan(labels, 'formal', 12, 7))
        expected = Counter(itertools.permutations(labels))
        for start in (0, 6):
            block = plan[start:start + 6]
            self.assertEqual(Counter(map(tuple, block)), expected)
            for position in range(3):
                self.assertEqual(Counter(order[position] for order in block), Counter(dict.fromkeys(labels, 2)))

    def test_formal_two_product_blocks_are_abba_or_baab(self):
        plan = study.make_plan(('a', 'b'), 'formal', 24, 9)
        for block in plan:
            self.assertIn(block, (['a', 'b', 'b', 'a'], ['b', 'a', 'a', 'b']))
            self.assertEqual(sum(i for i, label in enumerate(block, 1) if label == 'a'), 5)
            self.assertEqual(sum(i for i, label in enumerate(block, 1) if label == 'b'), 5)

    def test_invalid_or_unbalanced_plans_are_rejected(self):
        for blocks in (0, 1, 5, 7, 25):
            with self.subTest(blocks=blocks), self.assertRaises(ValueError):
                study.make_plan(tuple(self.sources), 'formal', blocks, 0)
        for labels in (('a', 'a'), ('a',), ('a', 'b', 'c', 'd'), ('a', '../b')):
            with self.subTest(labels=labels), self.assertRaises(ValueError):
                study.make_plan(labels, 'pilot', 1, 0)

    def test_metrics_use_original_integer_ns_and_full_cross_stage_intervals(self):
        raw = sample()
        i = raw['ime']['rounds'][0]['i']
        i['dispatch_up_ns'] = i['inject_up_ns'] + 17
        i['dispatch_return_ns'] = i['dispatch_up_ns'] + 300
        result = study.measurements(raw, COMMIT)
        first = result['first']
        self.assertEqual(first['i_injected_up_to_dispatch_ms'], 0.000017)
        self.assertEqual(first['i_actual_hold_ms'], 20.0)
        self.assertEqual(first['i_injected_down_to_dispatch_up_ms'], 20.000017)
        self.assertEqual(first['n_injected_down_to_ni_ready_ms'], 129.0)
        self.assertEqual(first['select_injected_up_to_ic_ms'], 12.0)
        self.assertEqual(first['select_dispatch_up_to_ic_ms'], 9.0)
        self.assertEqual(result['warm_process_median']['i_injected_up_to_dispatch_ms'], 6.0)

    def test_warm_median_is_taken_after_combining_each_events_stages(self):
        raw = sample()
        late, waits = [0, 0, 100, 100, 100], [100, 100, 0, 0, 100]
        for index, (lateness, wait) in enumerate(zip(late, waits), 1):
            row = raw['ime']['rounds'][index]
            i = row['i']
            i['inject_up_ns'] = i['planned_up_ns'] + lateness * MS
            i['inject_up_return_ns'] = i['inject_up_ns'] + MS
            i['dispatch_up_ns'] = i['inject_up_ns'] + wait * MS
            i['dispatch_return_ns'] = i['dispatch_up_ns'] + MS
            for key in list(row['select']):
                if key.endswith('_ns'):
                    row['select'][key] += 500 * MS
            raw['editor']['commits'][index]['at_ns'] += 500 * MS
        warm = study.measurements(raw, COMMIT)['warm_process_median']
        self.assertEqual(warm['i_submission_lateness_ms'], 100.0)
        self.assertEqual(warm['i_injected_up_to_dispatch_ms'], 100.0)
        self.assertEqual(warm['i_planned_up_to_dispatch_ms'], 100.0)

    def test_down_triggered_preedit_and_coalesced_n_are_allowed(self):
        raw = sample()
        row = raw['ime']['rounds'][0]
        row['ni_ready_ns'] = row['i']['dispatch_down_ns'] + 1
        self.assertIsNone(row['preedit_collected_ns'])
        study.measurements(raw, COMMIT)

    def test_missing_noninteger_and_reversed_timestamps_are_rejected(self):
        for field, value in (('planned_down_ns', None), ('planned_up_ns', True),
                             ('inject_up_ns', 3.5), ('dispatch_up_ns', -1)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                raw = sample()
                event = raw['ime']['rounds'][0]['i']
                if value is None:
                    del event[field]
                else:
                    event[field] = value
                study.measurements(raw, COMMIT)
        for change in ('reversed_plan', 'wrong_cadence', 'early_ni', 'early_ic'):
            with self.subTest(change=change), self.assertRaises(ValueError):
                raw = sample()
                row = raw['ime']['rounds'][0]
                if change == 'reversed_plan':
                    row['i']['planned_up_ns'] = row['i']['planned_down_ns'] - 1
                elif change == 'wrong_cadence':
                    row['i']['planned_down_ns'] += MS
                    row['i']['planned_up_ns'] += MS
                elif change == 'early_ni':
                    row['ni_ready_ns'] = row['i']['dispatch_down_ns'] - 1
                else:
                    raw['editor']['commits'][0]['at_ns'] = row['select']['dispatch_up_ns'] - 1
                study.measurements(raw, COMMIT)

    def test_injection_clock_reversal_is_rejected_even_with_positive_dispatch_wait(self):
        for field, before in (('inject_down_return_ns', 'inject_down_ns'),
                              ('inject_up_return_ns', 'inject_up_ns'),
                              ('inject_up_ns', 'inject_down_return_ns')):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'causal order'):
                raw = sample()
                event = raw['ime']['rounds'][1]['i']
                event[field] = event[before] - 1
                study.measurements(raw, COMMIT)

    def test_wrong_source_protocol_model_round_or_committed_word_is_rejected(self):
        for change in ('source', 'protocol', 'model', 'round', 'word', 'geometry'):
            with self.subTest(change=change), self.assertRaises(ValueError):
                raw = sample()
                if change == 'source':
                    raw['ime']['commit'] = 'b' * 40
                elif change == 'protocol':
                    raw['ime']['injection_protocol'] = 'different-harness'
                elif change == 'model':
                    raw['ime']['model_mapped'] = False
                elif change == 'round':
                    raw['ime']['rounds'][1]['round'] = 1
                elif change == 'word':
                    raw['editor']['commits'][0]['text'] = '你你'
                else:
                    raw['ime']['rounds'][0]['n']['geometry_valid'] = False
                study.measurements(raw, COMMIT)
        with self.assertRaises(ValueError):
            study.measurements(sample(commit='short'), 'short')

    def test_paired_statistics_count_abba_blocks_not_individual_processes(self):
        observations = []
        for block, a_values, b_values in ((1, [0, 100], [20, 22]), (2, [10, 14], [16, 18])):
            for label, values in (('baseline', a_values), ('current', b_values)):
                for value in values:
                    observations.append({'block': block, 'label': label, 'measurements': {
                        'first': {'latency': value}, 'warm_process_median': {'latency': value + 1}}})
        result = study.summarize(observations, 'baseline')
        paired = result['paired_block_deltas_vs_reference']['current']['latency']
        self.assertEqual(paired['n'], 2)
        self.assertEqual(paired['values'], [-29.0, 5.0])
        self.assertEqual(result['products']['baseline']['warm_process_median']['latency']['n'], 4)

    def test_without_explicit_reset_neither_entry_starts_a_subprocess(self):
        with self.assertRaises(ValueError):
            study.run(self.args())
        with patch.object(sys, 'argv', [str(SCRIPT), *self.argv]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                study.main()
        self.run_mock.assert_not_called()
        self.popen_mock.assert_not_called()
        self.check_output_mock.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_pilot_resets_one_fresh_process_per_label_and_records_source(self):
        result = self.execute()
        self.assertEqual(result['completed_samples'], 3)
        self.assertEqual(result['selection_count'], 18)
        self.assertEqual([command[command.index('--order') + 1] for command in self.commands], list(self.sources))
        for command in self.commands:
            self.assertEqual(command[command.index('--trials') + 1], '1')
            self.assertEqual(command[command.index('--serial') + 1], 'emulator-5574')
            self.assertEqual(command[command.index('--expected-avd-name') + 1], 'rime')
            self.assertIn('--allow-destructive-reset', command)
        self.assertEqual(result['plan']['sources'], self.sources)
        self.assertTrue((self.output / 'samples.json').is_file())

    def test_profile_is_only_a_separate_pilot(self):
        with self.assertRaises(ValueError):
            self.execute(['--mode', 'formal', '--blocks', '6', '--profile'])
        self.run_mock.assert_not_called()
        result = self.execute(['--profile'])
        self.assertTrue(result['traced'])
        self.assertTrue(all('--profile' in command for command in self.commands))

    def test_bad_manifest_and_nonempty_output_are_rejected_before_child(self):
        self.products['sources']['current'] = 'short'
        self.write_manifest()
        with self.assertRaises(ValueError):
            self.execute()
        self.run_mock.assert_not_called()
        self.products['sources']['current'] = 'c' * 40
        self.write_manifest()
        self.output.mkdir()
        original = self.output / 'prior.json'
        original.write_text('prior evidence', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.execute()
        self.run_mock.assert_not_called()
        self.assertEqual(original.read_text(encoding='utf-8'), 'prior evidence')

    def test_child_failure_preserves_raw_log_plan_and_completed_samples(self):
        self.fail_at = 2
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute()
        self.assert_preserved_failure()

    def test_invalid_raw_source_preserves_failed_sample_for_review(self):
        def wrong_source(raw, number):
            if number == 2:
                raw['ime']['commit'] = 'f' * 40
        self.mutate_raw = wrong_source
        with self.assertRaises(ValueError):
            self.execute()
        self.assert_preserved_failure()
        bad = json.loads((self.output / 'b01-p02-regressed' / 'regressed-1.json').read_text(encoding='utf-8'))
        self.assertEqual(bad['ime']['commit'], 'f' * 40)

    def assert_preserved_failure(self):
        failure = json.loads((self.output / 'failure.json').read_text(encoding='utf-8'))
        self.assertEqual(failure['completed_samples'], 1)
        self.assertEqual(len(json.loads((self.output / 'samples.json').read_text(encoding='utf-8'))), 1)
        self.assertTrue((self.output / 'study-plan.json').is_file())
        self.assertTrue((self.output / 'b01-p02-regressed' / 'regressed-1.json').is_file())
        self.assertIn('diagnostic output retained', (self.output / 'b01-p02-regressed.runner.log').read_text(encoding='utf-8'))
        self.assertFalse((self.output / 'summary.json').exists())


if __name__ == '__main__':
    unittest.main()
