#!/usr/bin/env python3
"""Counterbalanced fresh-process comparisons with a restored fixture per sample."""
import argparse
from touch_timing import validate_touch_hold
import itertools
import json
from pathlib import Path
import random
import re
import statistics
import subprocess
import sys


PROTOCOL = 'fixed_cadence_100ms_input_manager_async_cached_targets_v2'


def make_plan(labels, mode, blocks, seed):
    if len(labels) not in (2, 3) or len(set(labels)) != len(labels):
        raise ValueError('Choose two or three distinct product labels')
    if any(not re.fullmatch(r'[a-z][a-z0-9_-]*', label) for label in labels):
        raise ValueError('Product labels must be simple lowercase names')
    if mode not in ('pilot', 'formal') or not 1 <= blocks <= 24:
        raise ValueError('Invalid study mode or block count')
    if mode == 'pilot':
        if blocks != 1:
            raise ValueError('A pilot uses exactly one block')
        # The caller already selects the forward/reverse pilot order.
        return [list(labels)]
    rng = random.Random(seed)
    if len(labels) == 2:
        a, b = labels
        return [[a, b, b, a] if rng.randrange(2) == 0 else [b, a, a, b]
                for _ in range(blocks)]
    if blocks % 6:
        raise ValueError('A formal three-product study needs a multiple of six blocks')
    # Each complete group of six blocks balances every product's ordinal position.
    permutations = list(itertools.permutations(labels))
    result = []
    while len(result) < blocks:
        rng.shuffle(permutations)
        result.extend([list(order) for order in permutations])
    return result[:blocks]


def positive_ns(record, key):
    value = record.get(key)
    if type(value) is not int or value <= 0:
        raise ValueError(f'{key} must be a positive integer timestamp in nanoseconds')
    return value


def measurements(data, expected_commit, expected_protocol=PROTOCOL):
    ime, editor = data['ime'], data['editor']
    if not re.fullmatch(r'[a-f0-9]{40}', expected_commit) or ime['commit'] != expected_commit:
        raise ValueError('Sample product does not match its pinned source')
    if ime['injection_protocol'] != expected_protocol:
        raise ValueError('Sample protocol does not match the planned harness')
    if ime.get('model_mapped') is not True or ime.get('fcitx_core_mapped') is not False:
        raise ValueError('Sample must map the pinned model and use the direct Rime engine')
    validate_touch_hold(ime)
    rounds = ime['rounds']
    if len(rounds) != 6 or len(editor['commits']) != 6 or editor['text'] != '你' * 6:
        raise ValueError('A sample must contain exactly six successful selections')
    rows = []
    for index, row in enumerate(rounds):
        if type(row.get('round')) is not int or row['round'] != index + 1:
            raise ValueError('Sample rounds must be ordered from one through six')
        n, i, selected = row['n'], row['i'], row['select']
        for event, key in ((n, 'n'), (i, 'i'), (selected, '你')):
            for field in ('planned_down_ns', 'planned_up_ns', 'inject_down_ns', 'inject_up_ns',
                          'inject_down_return_ns', 'inject_up_return_ns',
                          'dispatch_down_ns', 'dispatch_up_ns', 'dispatch_return_ns'):
                positive_ns(event, field)
            # These four reads occur in order on the injection thread. A return
            # timestamp before its call proves invalid timing even if dispatch
            # still happens to produce a positive interval on another thread.
            if not (event['inject_down_ns'] <= event['inject_down_return_ns'] <=
                    event['inject_up_ns'] <= event['inject_up_return_ns']):
                raise ValueError('Injection call timestamps violate causal order')
            if (event.get('key') != key or event.get('geometry_valid') is not True or
                    event['planned_up_ns'] - event['planned_down_ns'] != 20_000_000 or
                    not event['planned_down_ns'] <= event['inject_down_ns'] <= event['inject_up_ns'] or
                    not event['planned_up_ns'] <= event['inject_up_ns'] or
                    not event['inject_down_ns'] <= event['dispatch_down_ns'] <= event['dispatch_up_ns'] or
                    not event['inject_up_ns'] <= event['dispatch_up_ns'] <= event['dispatch_return_ns']):
                raise ValueError('Incomplete or reversed input timestamps')
        if (i['planned_down_ns'] - n['planned_down_ns'] != 100_000_000 or
                n['inject_up_ns'] > i['inject_down_ns'] or
                i['dispatch_up_ns'] > selected['inject_down_ns']):
            raise ValueError('Sample does not follow the planned n/i/select sequence')
        ic = positive_ns(editor['commits'][index], 'at_ns')
        ni_ready = positive_ns(row, 'ni_ready_ns')
        if editor['commits'][index].get('text') != '你':
            raise ValueError('Each selection must commit exactly one expected word')
        # A layout may produce preedit on DOWN, so do not require ni_ready >= i UP.
        if (ic < selected['dispatch_up_ns'] or
                not i['dispatch_down_ns'] <= ni_ready <= selected['inject_down_ns']):
            raise ValueError('Response endpoint precedes the corresponding input')
        if index and n['inject_down_ns'] < rounds[index - 1]['select']['dispatch_up_ns']:
            raise ValueError('Sample round timestamps overlap an earlier selection')
        rows.append({
            'round': index + 1,
            'i_planned_up_to_dispatch_ms': (i['dispatch_up_ns'] - i['planned_up_ns']) / 1e6,
            'i_injected_up_to_dispatch_ms': (i['dispatch_up_ns'] - i['inject_up_ns']) / 1e6,
            'i_submission_lateness_ms': (i['inject_up_ns'] - i['planned_up_ns']) / 1e6,
            'i_injected_down_to_dispatch_up_ms': (i['dispatch_up_ns'] - i['inject_down_ns']) / 1e6,
            'i_dispatch_down_to_dispatch_up_ms': (i['dispatch_up_ns'] - i['dispatch_down_ns']) / 1e6,
            'i_actual_hold_ms': (i['inject_up_ns'] - i['inject_down_ns']) / 1e6,
            'n_planned_down_to_ni_ready_ms': (ni_ready - n['planned_down_ns']) / 1e6,
            'n_injected_down_to_ni_ready_ms': (ni_ready - n['inject_down_ns']) / 1e6,
            'n_dispatch_down_to_i_dispatch_up_ms': (i['dispatch_up_ns'] - n['dispatch_down_ns']) / 1e6,
            'select_planned_up_to_ic_ms': (ic - selected['planned_up_ns']) / 1e6,
            'select_injected_up_to_ic_ms': (ic - selected['inject_up_ns']) / 1e6,
            'select_dispatch_up_to_ic_ms': (ic - selected['dispatch_up_ns']) / 1e6,
        })
    first = {key: value for key, value in rows[0].items() if key != 'round'}
    warm = {key: statistics.median(row[key] for row in rows[1:]) for key in first}
    return {'first': first, 'warm_process_median': warm, 'rounds': rows}


def describe(values):
    return {'n': len(values), 'median': statistics.median(values),
            'min': min(values), 'max': max(values), 'values': values}


def summarize(samples, reference):
    by_product = {}
    for label in sorted({sample['label'] for sample in samples}):
        product = [sample for sample in samples if sample['label'] == label]
        by_product[label] = {
            phase: {metric: describe([sample['measurements'][phase][metric] for sample in product])
                    for metric in product[0]['measurements'][phase]}
            for phase in ('first', 'warm_process_median')
        }
    paired = {}
    for label in by_product:
        if label == reference:
            continue
        differences = {}
        for block in sorted({sample['block'] for sample in samples}):
            left = [sample for sample in samples if sample['block'] == block and sample['label'] == reference]
            right = [sample for sample in samples if sample['block'] == block and sample['label'] == label]
            if not left or not right:
                continue
            for metric in left[0]['measurements']['first']:
                # An ABBA block is one paired observation, not four independent processes.
                difference = (statistics.mean(sample['measurements']['first'][metric] for sample in right) -
                              statistics.mean(sample['measurements']['first'][metric] for sample in left))
                differences.setdefault(metric, []).append(difference)
        paired[label] = {metric: describe(values) for metric, values in differences.items()}
    return {'products': by_product, 'paired_block_deltas_vs_reference': paired}


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apks', 'fixture', 'fixture-manifest', 'host', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd-name', required=True)
    parser.add_argument('--allow-destructive-reset', action='store_true')
    parser.add_argument('--mode', choices=('pilot', 'formal'), required=True)
    parser.add_argument('--order', default='baseline,regressed,current')
    parser.add_argument('--reference', default='baseline')
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--blocks', type=int, required=True)
    parser.add_argument('--profile', action='store_true')
    return parser


def run(args):
    if not args.allow_destructive_reset:
        raise ValueError('The named study AVD must be explicitly authorized for destructive reset')
    if args.profile and args.mode != 'pilot':
        raise ValueError('Tracing is a separate pilot; do not mix it into formal measurements')
    labels = tuple(args.order.split(','))
    if args.reference not in labels:
        raise ValueError('Reference product must be in the declared order')
    plan = make_plan(labels, args.mode, args.blocks, args.seed)
    products = json.loads((args.apks / 'products.json').read_text(encoding='utf-8'))
    if any(label not in products['sources'] for label in labels):
        raise ValueError('A planned product is missing from the source manifest')
    if any(not re.fullmatch(r'[a-f0-9]{40}', products['sources'][label]) for label in labels):
        raise ValueError('Every planned product needs a full pinned commit SHA')
    if products['protocol'] != PROTOCOL:
        raise ValueError('The study requires the cached_targets_v2 harness protocol')
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('Preserve previous measurements; study output must be empty')
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'mode': args.mode, 'seed': args.seed, 'profile': args.profile,
                'serial': args.serial, 'expected_avd_name': args.expected_avd_name,
                'reference': args.reference, 'sources': products['sources'],
                'protocol': products['protocol'], 'harness_sha256': products['harness_sha256'],
                'plan': plan, 'unit': 'fresh process; six selections, with first and warm separated',
                'fixture_reset': 'before every process, outside the measured interval'}
    (args.output / 'study-plan.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    samples = []
    runner = Path(__file__).with_name('run-cadence.py')
    try:
        for block, order in enumerate(plan, 1):
            for position, label in enumerate(order, 1):
                name = f'b{block:02d}-p{position:02d}-{label}'
                output = args.output / name
                command = [sys.executable, str(runner), '--apks', str(args.apks),
                           '--fixture', str(args.fixture), '--fixture-manifest', str(args.fixture_manifest),
                           '--host', str(args.host), '--output', str(output), '--order', label,
                           '--trials', '1', '--serial', args.serial,
                           '--expected-avd-name', args.expected_avd_name, '--allow-destructive-reset']
                if args.profile:
                    command.append('--profile')
                print(f'Study {name} source={products["sources"][label]} traced={args.profile}', flush=True)
                with (args.output / (name + '.runner.log')).open('w', encoding='utf-8') as log:
                    subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
                raw_path = output / f'{label}-1.json'
                raw = json.loads(raw_path.read_text(encoding='utf-8'))
                sample = {'block': block, 'position': position, 'label': label,
                          'source': products['sources'][label], 'raw': str(raw_path.relative_to(args.output)),
                          'measurements': measurements(raw, products['sources'][label], products['protocol'])}
                samples.append(sample)
                (args.output / 'samples.json').write_text(json.dumps(samples, indent=2) + '\n', encoding='utf-8')
                print(json.dumps({'sample': name, 'first': sample['measurements']['first']}), flush=True)
    except Exception as error:
        (args.output / 'failure.json').write_text(json.dumps({'error': str(error), 'completed_samples': len(samples)},
                                                           indent=2) + '\n', encoding='utf-8')
        raise
    result = {'plan': manifest, 'completed_samples': len(samples),
              'selection_count': len(samples) * 6, 'traced': args.profile,
              'statistics': summarize(samples, args.reference)}
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = build_parser()
    args = parser.parse_args()
    if not args.allow_destructive_reset:
        parser.error('This study restores benchmark apps; --allow-destructive-reset is required')
    run(args)


if __name__ == '__main__':
    main()
