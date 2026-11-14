"""Read certificates only after apksigner verify has succeeded."""

import re


def signer_sha256(report):
    # New build-tools label scheme signers "V2 Signer:"; older versions use
    # "Signer #1". One certificate may appear under multiple APK schemes.
    certificates = {
        match.lower() for match in re.findall(
            r'^(?:Signer #\d+|V[1-4](?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$',
            report, re.MULTILINE)
    }
    if len(certificates) != 1:
        raise ValueError('Expected one distinct verified APK signing certificate')
    return certificates.pop()
