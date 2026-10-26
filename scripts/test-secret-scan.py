#!/usr/bin/env python3
"""固定版gitleaksと実Gitでworkflowのmerge差分・履歴・除外境界を検証する。"""
from collections import Counter
import json
import math
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
BINARY = Path(sys.argv.pop(1)).resolve() if len(sys.argv) > 1 else ROOT / 'gitleaks'
WORKFLOW = (ROOT / '.github/workflows/secret-scan.yml').read_text(encoding='utf-8')
CONFIG = ROOT / '.gitleaks.toml'
TARGET = 'docs/evidence/CMP-261021-0647/root-historical-roundtrip-applicability.json'
STARTED = time.monotonic()


def command(args, cwd, expected=(0,)):
    if time.monotonic() - STARTED > 300:
        raise AssertionError('fixture suite exceeded 300 seconds')
    env = os.environ.copy()
    env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1', GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, timeout=20)
    if result.returncode not in expected:
        # 子processの出力や引数には合成値が入りうるため例外へ含めない。
        raise AssertionError(f'fixture command failed with exit {result.returncode}')
    return result


def scanner_commands(job, scan_range, report):
    section = re.search(r'(?ms)^  ' + re.escape(job) + r':\n(.*?)(?=^  [a-z][\w-]*:|\Z)', WORKFLOW)
    if section is None:
        raise AssertionError('workflow job missing')
    scan = re.search(r'(?m)^          \./gitleaks detect \\\n(?:          .*\n?)+', section.group(1))
    if scan is None:
        raise AssertionError('workflow scanner command missing')
    text = scan.group(0).replace('\\\n', ' ').replace('${{ steps.range.outputs.log_opts }}', scan_range)
    argv = shlex.split(text)
    if argv[0:2] != ['./gitleaks', 'detect'] or '--redact' not in argv:
        raise AssertionError('unexpected scanner template')
    argv[0] = str(BINARY)
    for key, value in [('--source', '.'), ('--config', str(CONFIG)), ('--report-path', str(report))]:
        argv[argv.index(key) + 1] = value
    # 実workflowの限定したGit preflightのみを扱い、任意shellの実行基盤にしない。
    preflight = []
    expected = '          git log --format= --max-count=0 "${{ steps.range.outputs.log_opts }}" > /dev/null'
    if expected in section.group(1):
        preflight = ['git', 'log', '--format=', '--max-count=0', scan_range]
    return preflight, argv


class SecretScanRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        actual = command([str(BINARY), 'version'], ROOT).stdout.decode().strip()
        configured = re.search(r'GITLEAKS_VERSION: "([^"]+)"', WORKFLOW).group(1)
        if actual != configured or actual != '8.28.0':
            raise AssertionError('scanner must match pinned workflow version 8.28.0')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='secret-scan-regression-')
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / 'repo'
        self.repo.mkdir()
        self.values = []
        self.git('init', '-b', 'main')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Secret scan fixture')
        self.write('README.md', 'Synthetic fixture only.\n')
        self.commit('base')
        self.base = self.sha()

    def git(self, *args):
        return command(['git', *args], self.repo).stdout.decode().strip()

    def sha(self):
        return self.git('rev-parse', 'HEAD')

    def write(self, relative, contents):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')

    def token(self):
        # 16進各文字を3個ずつ含め、検出閾値3.5を超えるentropy 4を保証する。
        characters = list('0123456789abcdef' * 3)
        secrets.SystemRandom().shuffle(characters)
        value = ''.join(characters)
        self.values.append(value)
        return value

    def write_detection(self, relative=TARGET, value=None):
        self.write(relative, 'api_key = "' + (value if value is not None else self.token()) + '"\n')

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-m', message)

    def make_merge(self, octopus=False):
        branches = ['side-one', 'side-two'] if octopus else ['side-one']
        for branch in branches:
            self.git('checkout', '-b', branch, self.base)
            self.write(branch + '.md', 'non-secret branch marker\n')
            self.commit(branch)
        self.git('checkout', 'main')
        self.write('main.md', 'non-secret main marker\n')
        self.commit('main advance')
        self.git('merge', '--no-ff', '--no-commit', *branches)
        self.write_detection()
        self.commit('merge-only addition')
        parents = self.git('show', '-s', '--format=%P', 'HEAD').split()
        self.assertEqual(len(parents), len(branches) + 1, 'fixture must be a real multi-parent merge')

    def scan(self, job='diff-scan', scan_range=None, expected_exit=1, expected_count=1):
        report = Path(self.temporary.name) / 'report.sarif'
        scan_range = scan_range if scan_range is not None else self.base + '..' + self.sha()
        report.unlink(missing_ok=True)
        preflight, argv = scanner_commands(job, scan_range, report)
        result = command(preflight, self.repo, expected=(0, 128)) if preflight else None
        if result is None or result.returncode == 0:
            result = command(argv, self.repo, expected=(0, 1))
        raw_report = report.read_bytes() if report.exists() else b''
        for value in self.values:
            encoded = value.encode()
            self.assertFalse(encoded in result.stdout or encoded in result.stderr or encoded in raw_report,
                             'unredacted synthetic value escaped the scanner')
        count = sum(len(run.get('results', [])) for run in json.loads(raw_report).get('runs', [])) if raw_report else 0
        self.assertEqual(result.returncode, expected_exit, 'scanner exit must match gate contract')
        self.assertEqual(count, expected_count, 'scanner finding count must match fixture contract')

    def test_generated_token_always_exceeds_detection_entropy_threshold(self):
        with patch('secrets.token_hex', return_value='0' * 48):
            value = self.token()

        entropy = -sum((count / len(value)) * math.log2(count / len(value))
                       for count in Counter(value).values())
        self.assertGreaterEqual(entropy, 3.5, 'synthetic detection value must exceed scanner entropy threshold')

    def test_merge_only_addition_in_pr_and_push_range(self):
        self.make_merge()
        self.scan()

    def test_merge_only_addition_in_full_history(self):
        self.make_merge()
        self.scan('full-history-scan')

    def test_ordinary_commit_detection_is_preserved(self):
        self.write_detection()
        self.commit('ordinary addition')
        self.scan()

    def test_side_branch_added_then_removed_value_remains_detectable(self):
        self.git('checkout', '-b', 'side')
        self.write_detection()
        self.commit('side addition')
        (self.repo / TARGET).unlink()
        self.commit('side removal')
        self.git('checkout', 'main')
        self.git('merge', '--no-ff', '--no-edit', 'side')
        self.scan()

    def test_no_detection_and_empty_range_are_successful(self):
        self.write('safe.md', 'ordinary content\n')
        self.commit('safe addition')
        self.scan(expected_exit=0, expected_count=0)
        self.scan(scan_range=self.sha() + '..' + self.sha(), expected_exit=0, expected_count=0)

    def test_exact_allowed_path_and_value_are_excluded(self):
        self.write_detection(value='useApi/ofetch')
        self.commit('exact non-secret identifier')
        self.scan(expected_exit=0, expected_count=0)

    def test_allowed_value_in_other_path_is_detected(self):
        self.write_detection(relative='docs/evidence/CMP-261021-0647/neighbor.json', value='useApi/ofetch')
        self.commit('identifier outside allowed path')
        self.scan()

    def test_other_value_in_allowed_path_is_detected(self):
        self.write_detection()
        self.commit('other synthetic value')
        self.scan()

    def test_invalid_revision_fails_closed_without_value_leak(self):
        self.write_detection()
        self.commit('synthetic addition')
        self.scan(scan_range='not-a-real-revision..HEAD', expected_exit=128, expected_count=0)

    def test_octopus_merge_addition_in_full_history(self):
        self.make_merge(octopus=True)
        self.scan('full-history-scan')


if __name__ == '__main__':
    unittest.main(verbosity=2)
