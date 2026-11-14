import unittest

from apk_signers import signer_sha256


class ApkSignerTest(unittest.TestCase):
    digest = 'd4654e5b91e46a3a6351921b08c56e0fdc1fa15781371afca3e3e999e9673dea'

    def test_build_tools_scheme_label(self):
        self.assertEqual(self.digest, signer_sha256(
            'V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug\n'
            f'V2 Signer: certificate SHA-256 digest: {self.digest}\n'))

    def test_legacy_numbered_label(self):
        self.assertEqual(self.digest, signer_sha256(
            f'Signer #1 certificate SHA-256 digest: {self.digest.upper()}\r\n'))

    def test_versioned_v3_scheme_labels(self):
        for scheme in ('V3.0', 'V3.1'):
            with self.subTest(scheme=scheme):
                self.assertEqual(self.digest, signer_sha256(
                    f'{scheme} Signer: certificate SHA-256 digest: {self.digest}\n'))

    def test_same_certificate_across_schemes(self):
        self.assertEqual(self.digest, signer_sha256(
            f'V2 Signer: certificate SHA-256 digest: {self.digest}\n'
            f'V3 Signer: certificate SHA-256 digest: {self.digest}\n'))

    def test_source_stamp_is_not_apk_signer(self):
        self.assertEqual(self.digest, signer_sha256(
            f'Signer #1 certificate SHA-256 digest: {self.digest}\n'
            f'Source Stamp Signer certificate SHA-256 digest: {"a" * 64}\n'))

    def test_multiple_signing_certificates_fail(self):
        with self.assertRaises(ValueError):
            signer_sha256(f'Signer #1 certificate SHA-256 digest: {self.digest}\n'
                          f'Signer #2 certificate SHA-256 digest: {"a" * 64}\n')

    def test_unknown_or_incomplete_reports_fail(self):
        for report in ('', 'V2 Signer: certificate SHA-256 digest: abc',
                       f'Source Stamp Signer certificate SHA-256 digest: {self.digest}\n'):
            with self.subTest(report=report), self.assertRaises(ValueError):
                signer_sha256(report)


if __name__ == '__main__':
    unittest.main()
