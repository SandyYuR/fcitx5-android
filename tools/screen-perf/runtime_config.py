"""Keep the uploaded YAML untouched; explicitly seed the user's requested option."""
from pathlib import Path

def user_seed():
    raw = (Path(__file__).resolve().parent/'config/user.yaml').read_bytes()
    newline = b'\r\n' if b'\r\n' in raw else b'\n'
    key = b'  option:' + newline
    assert raw.count(key) == 1 and b'context_reorder:' not in raw
    return raw.replace(key, key + b'    context_reorder: true' + newline, 1)
