"""Touch-shape validation; legacy evidence remains readable with its recorded policy."""

MINIMUM_ACTUAL_HOLD_POLICY = 'minimum_20ms_from_actual_injection_down'
MINIMUM_HOLD_NS = 20_000_000


def validate_touch_hold(ime):
    policy = ime.get('touch_hold_policy')
    if policy is None:
        return False  # Legacy sample; its immutable harness hash identifies its semantics.
    if policy != MINIMUM_ACTUAL_HOLD_POLICY:
        raise ValueError('Unknown touch hold policy')
    rounds = ime.get('rounds')
    if not isinstance(rounds, list) or not rounds:
        raise ValueError('Declared touch hold policy requires observed touch records')
    for index, row in enumerate(rounds, 1):
        for key in ('n', 'i', 'select'):
            event = row.get(key, {})
            down, up = event.get('inject_down_ns'), event.get('inject_up_ns')
            if type(down) is not int or type(up) is not int or down <= 0 or up - down < MINIMUM_HOLD_NS:
                raise ValueError(f'Round {index} {key}: actual hold was shorter than the declared 20ms minimum')
    return True
