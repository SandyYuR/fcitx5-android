"""Exercise the APK catalog checks without needing a compiled native fixture."""
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("apk_verifier", Path(__file__).with_name("verify-direct-rime-apk.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/clearurls"


class ClearUrlsApkAssetsTest(unittest.TestCase):
    def fixture(self, omit=None, replace=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for path in ASSETS.iterdir():
                if path.name != omit:
                    archive.writestr(f"assets/clearurls/{path.name}", (replace or {}).get(path.name, path.read_bytes()))
        data.seek(0)
        return zipfile.ZipFile(data)

    def test_exact_catalog_and_notices_pass(self):
        with self.fixture() as archive:
            self.assertEqual(206, VERIFIER.verify_clipboard_rules(archive, {"files": {}})["providers"])

    def test_missing_license_is_rejected(self):
        with self.fixture(omit="LICENSE.LGPL-3.0.txt") as archive:
            with self.assertRaisesRegex(AssertionError, "Missing bundled ClearURLs asset"):
                VERIFIER.verify_clipboard_rules(archive, {"files": {}})

    def test_changed_catalog_is_rejected(self):
        with self.fixture(replace={"data.min.json": b'{"providers":{}}'}) as archive:
            with self.assertRaisesRegex(AssertionError, "Unexpected ClearURLs asset bytes"):
                VERIFIER.verify_clipboard_rules(archive, {"files": {}})

    def test_host_catalog_cannot_be_copied_to_native_data(self):
        with self.fixture() as archive:
            with self.assertRaisesRegex(AssertionError, "must not be copied"):
                VERIFIER.verify_clipboard_rules(archive, {"files": {"clearurls/data.min.json": "hash"}})


if __name__ == "__main__":
    unittest.main()
