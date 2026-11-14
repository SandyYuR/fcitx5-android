"""Reject a declared 20ms stimulus that was compressed by a late DOWN."""
import copy
import unittest
from touch_timing import MINIMUM_ACTUAL_HOLD_POLICY, validate_touch_hold


class TouchHoldTest(unittest.TestCase):
    def sample(self):
        event = {'inject_down_ns': 1_000_000_000, 'inject_up_ns': 1_020_000_000}
        return {'touch_hold_policy': MINIMUM_ACTUAL_HOLD_POLICY,
                'rounds': [{key: copy.copy(event) for key in ('n', 'i', 'select')}]}

    def test_late_down_keeps_a_valid_twenty_ms_hold(self):
        data = self.sample()
        for event in data['rounds'][0].values():
            event.update(planned_down_ns=900_000_000, planned_up_ns=920_000_000)
        self.assertTrue(validate_touch_hold(data))

    def test_compressed_hold_is_rejected_for_every_touch(self):
        for key in ('n', 'i', 'select'):
            with self.subTest(key=key):
                data = self.sample()
                data['rounds'][0][key]['inject_up_ns'] = 1_000_795_796
                with self.assertRaisesRegex(ValueError, 'actual hold'):
                    validate_touch_hold(data)

    def test_legacy_sample_does_not_acquire_the_new_guarantee(self):
        self.assertFalse(validate_touch_hold({'rounds': []}))

    def test_unknown_policy_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown'):
            validate_touch_hold({'touch_hold_policy': 'unrecognized'})

    def test_declared_policy_requires_observed_touches(self):
        with self.assertRaisesRegex(ValueError, 'observed'):
            validate_touch_hold({'touch_hold_policy': MINIMUM_ACTUAL_HOLD_POLICY, 'rounds': []})

    def test_missing_or_noninteger_timestamp_is_rejected(self):
        for value in (None, True, 1.0):
            data = self.sample()
            data['rounds'][0]['i']['inject_down_ns'] = value
            with self.assertRaises(ValueError):
                validate_touch_hold(data)
