import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


def load_gate(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


coverage = load_gate('coverage_gate', 'check-coverage-sources.py')
evidence = load_gate('evidence_gate', 'check-evidence-index.py')


class CoverageGateTest(unittest.TestCase):
    def test_current_links_reject_branch_refs_and_missing_paths(self):
        text = (
            '## 现行\n'
            '[default](https://github.com/SandyYuR/fcitx5-android/blob/fx-rime-only/app/A.kt)\n'
            '[feature](https://github.com/SandyYuR/fcitx5-android/blob/fix/editor/app/A.kt)\n'
            '[gone](https://github.com/SandyYuR/fcitx5-android/blob/b4670127/app/Gone.kt)\n'
            '[current](https://github.com/SandyYuR/fcitx5-android/blob/b4670127/app/A.kt)\n'
        )
        errors, warnings, paths = coverage.check(text, {'app/A.kt'})
        self.assertEqual(['fx-rime-only', 'fix', 'b4670127'], [error['ref'] for error in errors])
        self.assertEqual('app/Gone.kt', errors[2]['path'])
        self.assertEqual([], warnings)
        self.assertEqual({'app/A.kt', 'app/Gone.kt'}, paths)

    def test_increment_log_only_warns(self):
        text = (
            '## 增量记录\n'
            '[old branch](https://github.com/SandyYuR/fcitx5-android/tree/fx-rime-only/old)\n'
            '[old file](https://github.com/SandyYuR/fcitx5-android/blob/b4670127/old.kt)\n'
        )
        errors, warnings, _ = coverage.check(text, set())
        self.assertEqual([], errors)
        self.assertEqual(2, len(warnings))

    def test_code_dir_uses_only_the_requested_git_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                subprocess.run(['git', '-C', directory, *args], check=True, capture_output=True)

            git('init', '-q')
            (root / '字号.kt').touch()
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'First')
            git('branch', 'comparison')
            git('rm', '字号.kt')
            (root / 'New.kt').touch()
            git('add', 'New.kt')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'Second')
            (root / '字号.kt').touch()

            args = SimpleNamespace(code_tree=None, code_dir=directory, ref='HEAD')
            self.assertEqual({'New.kt'}, coverage.load_tree(args))
            args.ref = 'comparison'
            self.assertEqual({'字号.kt'}, coverage.load_tree(args))


class EvidenceGateTest(unittest.TestCase):
    path = 'maintainer/implementation/diagram.svg'
    page = 'maintainer/implementation/example.md'

    def check_page(self, body):
        index = f'## 按目录查找全部附件\n{evidence.START}\n{evidence.END}\n'
        return evidence.check([self.path], {evidence.EVIDENCE: index, self.page: body})

    def test_inline_and_reference_images_cover_the_same_file(self):
        for body in (
            '![图](./diagram.svg)',
            '![图][Diagram Label]\n\n[diagram label]: <./diagram.svg>',
            '![图][]\n\n[图]: ./diagram.svg',
            '![图]\n\n[图]: ./diagram.svg',
        ):
            with self.subTest(body=body):
                self.assertEqual([], self.check_page(body)[0])

    def test_unused_or_example_references_do_not_cover_a_file(self):
        for body in (
            '[diagram]: ./diagram.svg',
            '\x60![图][diagram]\x60\n\n[diagram]: ./diagram.svg',
            '\x60\x60\x60markdown\n![图][diagram]\n\x60\x60\x60\n\n[diagram]: ./diagram.svg',
        ):
            with self.subTest(body=body):
                self.assertEqual(['maintainer/implementation'], self.check_page(body)[1])
