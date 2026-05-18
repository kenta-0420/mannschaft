#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ci_drift_summary.py の単体テスト（Stage 4 第六陣 / 案A段階移行）。

検証範囲:
    - STRICT_DRIFT=false (default): 常に exit 0
    - STRICT_DRIFT=true で新規発生 drift なし: exit 0
    - STRICT_DRIFT=true で missing_impl 新規発生あり: exit 1
    - STRICT_DRIFT=true で missing_design 新規発生あり: exit 1
    - STRICT_DRIFT=true で解消のみ (drift 減るだけ): exit 0
    - main baseline 不存在 (初回導入時): STRICT_DRIFT に関わらず exit 0
    - comment.md は exit 1 時も書かれる

実行:
    cd backend && python -m unittest scripts/test_ci_drift_summary.py
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ci_drift_summary as cds  # noqa: E402


# ---------------------------------------------------------------------------
# テスト用 baseline.md ヘルパ
# ---------------------------------------------------------------------------

BASELINE_TEMPLATE = """# API Drift Baseline (test fixture)

## サマリ

- 設計あり・実装なし: **{missing_impl_count} 件**
- 実装あり・設計なし: **{missing_design_count} 件**
- 一致: **{matched_count} 件**

## 1. 🔴 設計あり・実装なし

| Method | Path | Source | Line |
|---|---|---|---|
{missing_impl_rows}

## 2. 🟡 実装あり・設計なし

| Method | Path | Source | Line |
|---|---|---|---|
{missing_design_rows}

## 3. ✅ 一致

（省略）
"""


def make_row(method: str, path: str) -> str:
    return f"| {method} | `{path}` | `docs/foo.md` | 1 |"


def write_baseline(
    path: Path,
    missing_impl: list[tuple[str, str]],
    missing_design: list[tuple[str, str]],
    matched: int = 0,
) -> None:
    rows_impl = "\n".join(make_row(m, p) for m, p in missing_impl) or "| - | - | - | - |"
    rows_design = "\n".join(make_row(m, p) for m, p in missing_design) or "| - | - | - | - |"
    text = BASELINE_TEMPLATE.format(
        missing_impl_count=len(missing_impl),
        missing_design_count=len(missing_design),
        matched_count=matched,
        missing_impl_rows=rows_impl,
        missing_design_rows=rows_design,
    )
    path.write_text(text, encoding="utf-8")


def run_main(
    main_baseline: Path | None,
    pr_baseline: Path,
    output: Path,
    strict_drift: str | None,
) -> int:
    """ci_drift_summary.main() を環境変数 + argv 差し替えで実行する。"""
    env = os.environ.copy()
    if strict_drift is None:
        env.pop("STRICT_DRIFT", None)
    else:
        env["STRICT_DRIFT"] = strict_drift

    argv = [
        "ci_drift_summary.py",
        "--main", str(main_baseline) if main_baseline else "/nonexistent/main.md",
        "--pr", str(pr_baseline),
        "--output", str(output),
    ]
    with mock.patch.dict(os.environ, env, clear=True), \
         mock.patch.object(sys, "argv", argv):
        return cds.main()


# ---------------------------------------------------------------------------
# is_strict_mode 単体
# ---------------------------------------------------------------------------

class TestIsStrictMode(unittest.TestCase):
    def test_unset_is_false(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertFalse(cds.is_strict_mode())

    def test_false_string_is_false(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "false"}, clear=True):
            self.assertFalse(cds.is_strict_mode())

    def test_empty_is_false(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": ""}, clear=True):
            self.assertFalse(cds.is_strict_mode())

    def test_true_string_is_true(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "true"}, clear=True):
            self.assertTrue(cds.is_strict_mode())

    def test_true_uppercase_is_true(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "TRUE"}, clear=True):
            self.assertTrue(cds.is_strict_mode())

    def test_one_is_true(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "1"}, clear=True):
            self.assertTrue(cds.is_strict_mode())

    def test_yes_is_true(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "yes"}, clear=True):
            self.assertTrue(cds.is_strict_mode())

    def test_on_is_true(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "on"}, clear=True):
            self.assertTrue(cds.is_strict_mode())

    def test_garbage_is_false(self) -> None:
        with mock.patch.dict(os.environ, {"STRICT_DRIFT": "maybe"}, clear=True):
            self.assertFalse(cds.is_strict_mode())


# ---------------------------------------------------------------------------
# main() 統合テスト
# ---------------------------------------------------------------------------

class TestMainExitCodes(unittest.TestCase):
    """STRICT_DRIFT の有無と drift 差分の組み合わせで exit code を検証する。"""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.tmp_path = Path(self.tmp.name)
        self.main_baseline = self.tmp_path / "main_baseline.md"
        self.pr_baseline = self.tmp_path / "pr_baseline.md"
        self.output = self.tmp_path / "comment.md"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    # ----- STRICT_DRIFT=false / default -----

    def test_default_warning_only_with_new_drift_returns_0(self) -> None:
        """STRICT_DRIFT 未設定 → 新規 drift があっても exit 0。"""
        write_baseline(self.main_baseline, [], [], matched=10)
        write_baseline(
            self.pr_baseline,
            missing_impl=[("GET", "/api/v1/new/impl")],
            missing_design=[("POST", "/api/v1/new/design")],
            matched=10,
        )
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift=None)
        self.assertEqual(rc, 0)
        self.assertTrue(self.output.exists())

    def test_explicit_false_warning_only_with_new_drift_returns_0(self) -> None:
        """STRICT_DRIFT=false → 新規 drift があっても exit 0。"""
        write_baseline(self.main_baseline, [], [], matched=10)
        write_baseline(
            self.pr_baseline,
            missing_impl=[("GET", "/api/v1/new/impl")],
            missing_design=[],
            matched=10,
        )
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="false")
        self.assertEqual(rc, 0)
        self.assertTrue(self.output.exists())

    # ----- STRICT_DRIFT=true で新規発生なし -----

    def test_strict_no_change_returns_0(self) -> None:
        """STRICT_DRIFT=true・main と PR が同一なら exit 0。"""
        impl = [("GET", "/api/v1/existing/impl")]
        design = [("POST", "/api/v1/existing/design")]
        write_baseline(self.main_baseline, impl, design, matched=10)
        write_baseline(self.pr_baseline, impl, design, matched=10)
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 0)

    # ----- STRICT_DRIFT=true で missing_impl 新規発生 -----

    def test_strict_new_missing_impl_returns_1(self) -> None:
        """STRICT_DRIFT=true・PR で missing_impl が増えたら exit 1。"""
        write_baseline(self.main_baseline, [], [], matched=10)
        write_baseline(
            self.pr_baseline,
            missing_impl=[("GET", "/api/v1/new/impl")],
            missing_design=[],
            matched=10,
        )
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 1)
        self.assertTrue(self.output.exists(), "comment.md は exit 1 でも書かれる必要がある")
        # comment.md に fail メッセージが入っていること
        body = self.output.read_text(encoding="utf-8")
        self.assertIn("Strict mode", body)
        self.assertIn("fail", body)

    # ----- STRICT_DRIFT=true で missing_design 新規発生 -----

    def test_strict_new_missing_design_returns_1(self) -> None:
        """STRICT_DRIFT=true・PR で missing_design が増えたら exit 1。"""
        write_baseline(self.main_baseline, [], [], matched=10)
        write_baseline(
            self.pr_baseline,
            missing_impl=[],
            missing_design=[("POST", "/api/v1/new/design")],
            matched=10,
        )
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 1)
        self.assertTrue(self.output.exists())

    # ----- STRICT_DRIFT=true で解消のみ -----

    def test_strict_only_fixed_returns_0(self) -> None:
        """STRICT_DRIFT=true・PR で drift が減っただけなら exit 0。"""
        write_baseline(
            self.main_baseline,
            missing_impl=[("GET", "/api/v1/old/impl")],
            missing_design=[("POST", "/api/v1/old/design")],
            matched=10,
        )
        write_baseline(self.pr_baseline, missing_impl=[], missing_design=[], matched=12)
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 0)
        body = self.output.read_text(encoding="utf-8")
        # 解消セクションが出ていることを軽く確認
        self.assertIn("解消された drift", body)

    # ----- main baseline が無い場合 -----

    def test_strict_no_main_baseline_returns_0(self) -> None:
        """初回導入時 (main に baseline が無い) は STRICT でも exit 0。"""
        write_baseline(
            self.pr_baseline,
            missing_impl=[("GET", "/api/v1/whatever")],
            missing_design=[],
            matched=10,
        )
        rc = run_main(
            main_baseline=self.tmp_path / "no_such_file.md",
            pr_baseline=self.pr_baseline,
            output=self.output,
            strict_drift="true",
        )
        self.assertEqual(rc, 0)
        self.assertTrue(self.output.exists())
        body = self.output.read_text(encoding="utf-8")
        # 初回導入時の説明文が入っている
        self.assertIn("baseline が見つかりません", body)

    # ----- 解消と新規が混在 -----

    def test_strict_mixed_new_and_fixed_returns_1(self) -> None:
        """新規発生 1 件 + 解消 5 件でも、新規があれば exit 1。"""
        old_drift = [(f"GET", f"/api/v1/old/{i}") for i in range(5)]
        write_baseline(self.main_baseline, missing_impl=old_drift, missing_design=[], matched=10)
        write_baseline(
            self.pr_baseline,
            missing_impl=[("DELETE", "/api/v1/brand-new")],
            missing_design=[],
            matched=15,
        )
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 1)

    # ----- 既存 drift が main と PR に同じく載っている (chip-away) -----

    def test_strict_existing_drift_carried_over_returns_0(self) -> None:
        """既知 baseline drift が main と PR に同じくあるだけなら exit 0。"""
        carried = [
            ("GET", "/api/v1/legacy/a"),
            ("POST", "/api/v1/legacy/b"),
            ("PUT", "/api/v1/legacy/c"),
        ]
        write_baseline(self.main_baseline, missing_impl=carried, missing_design=[], matched=10)
        write_baseline(self.pr_baseline, missing_impl=carried, missing_design=[], matched=10)
        rc = run_main(self.main_baseline, self.pr_baseline, self.output, strict_drift="true")
        self.assertEqual(rc, 0)


# ---------------------------------------------------------------------------
# compute_new_drift 単体
# ---------------------------------------------------------------------------

class TestComputeNewDrift(unittest.TestCase):
    def test_subset_returns_diff_only(self) -> None:
        main_data = {
            "missing_impl_paths": {("GET", "/api/v1/a"), ("POST", "/api/v1/b")},
            "missing_design_paths": {("GET", "/api/v1/c")},
        }
        pr_data = {
            "missing_impl_paths": {
                ("GET", "/api/v1/a"),       # 既存
                ("POST", "/api/v1/b"),      # 既存
                ("DELETE", "/api/v1/new"),  # 新規
            },
            "missing_design_paths": {
                ("GET", "/api/v1/c"),       # 既存
                ("PATCH", "/api/v1/newd"),  # 新規
            },
        }
        new_impl, new_design = cds.compute_new_drift(main_data, pr_data)
        self.assertEqual(new_impl, [("DELETE", "/api/v1/new")])
        self.assertEqual(new_design, [("PATCH", "/api/v1/newd")])

    def test_fixed_only_returns_empty(self) -> None:
        main_data = {
            "missing_impl_paths": {("GET", "/api/v1/a")},
            "missing_design_paths": set(),
        }
        pr_data = {
            "missing_impl_paths": set(),
            "missing_design_paths": set(),
        }
        new_impl, new_design = cds.compute_new_drift(main_data, pr_data)
        self.assertEqual(new_impl, [])
        self.assertEqual(new_design, [])


if __name__ == "__main__":
    unittest.main()
