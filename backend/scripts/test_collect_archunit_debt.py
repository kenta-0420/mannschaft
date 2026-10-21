#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L0-C の CLI 契約テスト。実行: python -m unittest scripts/test_collect_archunit_debt.py"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLI = HERE / "collect_archunit_debt.py"
REAL_STORE = HERE.parent / "src/test/resources/archunit_store"
# stored.rules の実登録を元にする。UUID と番人の対応も契約として検証する。
RULES = {
    "D-1": ("no cross-domain entity dependency (D-1)", "584c3a46-b9c1-4cc2-bf74-e0a18eab1bef"),
    "D-2b": ("entities should extend UuidV7Entity (D-2b)", "2c0ba995-682e-4f80-a5a5-f68c835b720d"),
    "D-3": ("transactional should not span other-domain repositories (D-3)", "f14374b1-655e-4df2-8e82-2d79c8df9174"),
    "D-3T": ("transactional entry should not transitively reach other-domain repositories (D-3T)", "296295dd-06cf-4f7b-bf82-315ba12ff501"),
    "D-5": ("no cross-domain repository dependency (D-5)", "427c445d-37ce-4d6e-b095-a1733efe209f"),
    "ServiceAPI": ("service API must not expose entities in signature (D-1 API boundary)", "93124b52-f328-4f09-8c4f-6d022519fae2"),
    "AUTHZ_WAVE4": ("public controller endpoints must have an authorization signal (Wave4)", "9ed4737d-c74f-4374-923e-4663d3c9e256"),
}


def registration(rule_id: str) -> str:
    description, uuid = RULES[rule_id]
    return description.replace(" ", "\\ ") + "=" + uuid


def violation(rule_id: str, source: str = "alpha", target: str = "omega") -> str:
    source_class = f"com.mannschaft.app.{source}.service.SourceService"
    target_class = f"com.mannschaft.app.{target}.entity.TargetEntity"
    if rule_id == "D-2b":
        return f"Class <{source_class}> is not assignable to com.mannschaft.app.common.entity.UuidV7Entity in (SourceService.java:0)"
    if rule_id == "D-3T":
        return f"@Transactional entry {source_class}.run() (domain '{source}') reaches other-domain repository {target_class} (domain '{target}') [D-3T]"
    if rule_id == "ServiceAPI":
        return f"{source_class}.run() exposes @Entity class {target_class} in its signature at (SourceService.java:1)"
    if rule_id == "AUTHZ_WAVE4":
        return (f"{source_class}.run() is a public Controller Mapping endpoint without an authorization signal "
                "(@PreAuthorize / AccessControlService / ContentVisibilityChecker / "
                "*AccessGuard / *AccessService call) at (SourceService.java:1)")
    kind = "entity" if rule_id == "D-1" else "repository"
    annotation = ", @Transactional" if rule_id == "D-3" else ""
    return f"{source_class} (domain '{source}'{annotation}) depends on other-domain {kind} {target_class} (domain '{target}')"


def hashes(directory: Path) -> dict[str, str]:
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.iterdir() if p.is_file()}


class TestCollectArchunitDebt(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Path(self.tmp.name) / "store"
        self.store.mkdir()
        self.write_fixture({})

    def write_fixture(self, entries: dict[str, list[str]], newline: str = "\n", trailing: bool = True) -> None:
        mapping = "# fixture\n" + "\n".join(registration(key) for key in RULES) + "\n"
        (self.store / "stored.rules").write_text(mapping, encoding="utf-8")
        for rule_id, (_, uuid) in RULES.items():
            lines = entries.get(rule_id, [])
            text = newline.join(lines) + (newline if lines and trailing else "")
            (self.store / uuid).write_bytes(text.encode("utf-8"))

    def run_cli(self, store: Path | None = None, cwd: Path | None = None, help_only: bool = False) -> subprocess.CompletedProcess[str]:
        args = [sys.executable, "-X", "utf8", str(CLI)]
        if help_only:
            args.append("--help")
        elif store is not None:
            args.extend(["--store-dir", str(store)])
        return subprocess.run(args, cwd=cwd or self.tmp.name, capture_output=True, text=True, encoding="utf-8", timeout=30)

    def successful_json(self, result: subprocess.CompletedProcess[str]) -> dict:
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        return json.loads(result.stdout)

    def test_ac1_ac3_all_registered_formats_count_source_not_target(self) -> None:
        """全7番人で複数FQCNから発生元だけを数える。"""
        self.write_fixture({key: [violation(key, "zeta", "alpha"), violation(key, "alpha", "omega")] for key in RULES})

        data = self.successful_json(self.run_cli(self.store))

        self.assertEqual(set(data), set(RULES))
        for key in RULES:
            with self.subTest(rule=key):
                self.assertEqual(data[key], {"total": 2, "domains": {"alpha": 1, "zeta": 1}})

    def test_ac2_empty_single_duplicate_and_newline_boundaries(self) -> None:
        """0/1/重複2行とLF/CRLF・末尾改行有無を独立に検査する。"""
        for count in (0, 1, 2):
            for newline in ("\n", "\r\n"):
                for trailing in (False, True):
                    with self.subTest(count=count, newline=repr(newline), trailing=trailing):
                        self.write_fixture({key: [violation(key)] * count for key in RULES}, newline, trailing)
                        data = self.successful_json(self.run_cli(self.store))
                        for key in RULES:
                            self.assertEqual(data[key], {"total": count, "domains": {"alpha": count} if count else {}})

    def test_ac4_rejects_bad_lines_without_partial_json(self) -> None:
        """途中まで読めた場合も空行・未知行・不明sourceを成功扱いしない。"""
        uuid = RULES["D-5"][1]
        for bad_line in ("", "unknown format", "Class <com.mannschaft.app.alpha.Source> unexpected", "com.example.Source invalid"):
            with self.subTest(line=bad_line):
                self.write_fixture({key: [violation(key)] for key in RULES})
                (self.store / uuid).write_text(violation("D-5") + "\n" + bad_line + "\n", encoding="utf-8")
                result = self.run_cli(self.store)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertIn(uuid, result.stderr)
                self.assertIn(":2", result.stderr)

    def test_ac4_rejects_missing_files(self) -> None:
        for name in ("stored.rules", RULES["D-3T"][1]):
            with self.subTest(file=name):
                self.write_fixture({})
                (self.store / name).unlink()
                result = self.run_cli(self.store)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertIn(name, result.stderr)
                self.assertNotIn("can't open file", result.stderr)

    def test_ac4_rejects_unknown_missing_duplicate_and_mismatched_registration(self) -> None:
        original = (self.store / "stored.rules").read_text(encoding="utf-8")
        invalid = (
            original + "unknown\\ rule=11111111-1111-1111-1111-111111111111\n",
            original.replace(registration("D-1") + "\n", ""),
            original + registration("D-1") + "\n",
            original.replace(RULES["D-1"][1], RULES["D-5"][1]),
            original + "malformed registration\n",
        )
        for mapping in invalid:
            with self.subTest(mapping=mapping):
                (self.store / "stored.rules").write_text(mapping, encoding="utf-8")
                result = self.run_cli(self.store)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertIn("stored.rules", result.stderr)

    def test_ac5_stable_order_and_input_hashes(self) -> None:
        self.write_fixture({key: [violation(key, "zeta"), violation(key, "alpha")] for key in RULES})
        before = hashes(self.store)

        first = self.run_cli(self.store)
        data = self.successful_json(first)
        second = self.run_cli(self.store)

        self.assertEqual(first.stdout, second.stdout)
        self.assertEqual(list(data), sorted(RULES))
        for item in data.values():
            self.assertEqual(list(item["domains"]), ["alpha", "zeta"])
        self.assertEqual(hashes(self.store), before)

    def test_ac1_ac2_ac5_real_store_default_path_from_other_cwd(self) -> None:
        """変動する件数を固定せず、実store全量・物理行数・入力非変更を検証する。"""
        before = hashes(REAL_STORE)

        data = self.successful_json(self.run_cli())

        self.assertEqual(set(data), set(RULES))
        for key, (_, uuid) in RULES.items():
            with self.subTest(rule=key):
                line_count = len((REAL_STORE / uuid).read_text(encoding="utf-8").splitlines())
                self.assertEqual(data[key]["total"], line_count)
                self.assertEqual(sum(data[key]["domains"].values()), line_count)
                if line_count == 0:
                    self.assertEqual(data[key]["domains"], {})
        self.assertEqual(hashes(REAL_STORE), before)

    def test_ac5_help_describes_usage_output_and_d2b_scope(self) -> None:
        result = self.run_cli(help_only=True)

        self.assertEqual(result.returncode, 0, result.stderr)
        for expected in ("--store-dir", "JSON", "total", "domains", "D-2b", "対象外"):
            self.assertIn(expected, result.stdout)


if __name__ == "__main__":
    unittest.main()
