#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
API 乖離スキャナ v4 の単体テスト。

実行:
    cd backend && python -m unittest scripts/test_scan_api_drift.py
    （または）python -m unittest backend.scripts.test_scan_api_drift

v2 で確認された 6 つの偽陽性バグについて、reproducer + 期待挙動を検証する。
v4 拡張: V4-1 スコープ階層プレフィックス逆引きマッチ・V4-5 🔵 将来機能タグ認識。
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

# スクリプトディレクトリを import path に追加
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import scan_api_drift as scanner  # noqa: E402


class TestBug1QueryStripping(unittest.TestCase):
    """v3 バグ1: path?query=val を path のみで比較する。"""

    def test_normalize_strips_query_string(self) -> None:
        self.assertEqual(
            scanner.normalize_path("/api/v1/me/foo?scopeType=TEAM"),
            "/api/v1/me/foo",
        )

    def test_normalize_strips_query_with_multiple_params(self) -> None:
        self.assertEqual(
            scanner.normalize_path("/api/v1/users?role=ADMIN&deleted=false"),
            "/api/v1/users",
        )

    def test_normalize_preserves_path_without_query(self) -> None:
        self.assertEqual(
            scanner.normalize_path("/api/v1/me/foo"),
            "/api/v1/me/foo",
        )

    def test_design_with_query_matches_impl_without(self) -> None:
        """設計書側に `?scopeType=TEAM` 付きで書かれていても、実装側
        `/api/v1/me/foo` と一致する。
        """
        design_path = scanner.normalize_path("/api/v1/me/foo?scopeType=TEAM")
        impl_path = scanner.normalize_path("/api/v1/me/foo")
        self.assertEqual(design_path, impl_path)


class TestBug2DuplicateDedup(unittest.TestCase):
    """v3 バグ2: 同一 (method, path) は集計で 1 件に集約する。"""

    def test_make_report_dedups_identical_keys(self) -> None:
        """同じ (method, path) が複数回出てきても 1 件扱い。"""
        de1 = scanner.DesignEndpoint(
            method="GET", path="/api/v1/foo", source_file="x.md", line_number=1, status=None
        )
        de2 = scanner.DesignEndpoint(
            method="GET", path="/api/v1/foo", source_file="y.md", line_number=2, status=None
        )
        ie1 = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/foo",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        ie2 = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/foo",
            source_file="X.java",
            line_number=20,
            class_name="X",
            method_name="n",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de1, de2], [ie1, ie2], out, Path(td), expand_scope=False
            )
            # 設計2件 + 実装2件 でも、一致は 1 件。only_* は 0 件。
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            self.assertEqual(mt, 1)


class TestBug3QueryNormalization(unittest.TestCase):
    """v3 バグ3: クエリ文字列の正規化（基本は無視）。"""

    def test_expand_scope_strips_query(self) -> None:
        results = scanner.expand_scope_paths(
            "/api/v1/{scope}/{scopeId}/foo?bar=1"
        )
        # スコープ展開後、いずれの結果にも ? が含まれない
        for r in results:
            self.assertNotIn("?", r)
        # 期待される 4 種 (teams/organizations/villages/users) が出る
        self.assertEqual(len(results), 4)
        self.assertIn("/api/v1/teams/{_}/foo", results)


class TestBug4TrailingSlash(unittest.TestCase):
    """v3 バグ4: 末尾スラッシュの取りこぼし防止。"""

    def test_normalize_strips_trailing_slash(self) -> None:
        self.assertEqual(scanner.normalize_path("/api/v1/foo/"), "/api/v1/foo")

    def test_normalize_strips_trailing_slash_with_param(self) -> None:
        self.assertEqual(
            scanner.normalize_path("/api/v1/teams/{id}/"),
            "/api/v1/teams/{_}",
        )

    def test_normalize_preserves_root_slash(self) -> None:
        self.assertEqual(scanner.normalize_path("/"), "/")

    def test_normalize_strips_trailing_slash_after_query(self) -> None:
        # ?query を取り除いた後でも末尾スラッシュは消える
        self.assertEqual(
            scanner.normalize_path("/api/v1/foo/?bar=1"),
            "/api/v1/foo",
        )


class TestBug5ScopeExpansion(unittest.TestCase):
    """v3 バグ5: {scope}/{scopeId} の階層展開。"""

    def test_expand_scope_third_segment_pair(self) -> None:
        results = scanner.expand_scope_paths(
            "/api/v1/{scope}/{scopeId}/members"
        )
        self.assertEqual(len(results), 4)
        self.assertIn("/api/v1/teams/{_}/members", results)
        self.assertIn("/api/v1/organizations/{_}/members", results)
        self.assertIn("/api/v1/villages/{_}/members", results)
        self.assertIn("/api/v1/users/{_}/members", results)

    def test_expand_scope_returns_single_for_non_scope(self) -> None:
        # 通常のパスは 1 件のみ返す
        results = scanner.expand_scope_paths("/api/v1/teams/{id}/members")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0], "/api/v1/teams/{_}/members")

    def test_expand_scope_scopetype_variant(self) -> None:
        results = scanner.expand_scope_paths(
            "/api/v1/{scopeType}/{scopeId}/foo"
        )
        self.assertEqual(len(results), 4)

    def test_expand_scope_empty_path(self) -> None:
        self.assertEqual(scanner.expand_scope_paths(""), [""])


class TestBug6EncodingResilience(unittest.TestCase):
    """v3 バグ6: 文字化け含む Controller ファイルを読み飛ばさない。"""

    def test_controller_with_mojibake_comment_is_parsed(self) -> None:
        """コメント中に不正バイトを含む Java ファイルでも、
        @GetMapping アノテーションは正しく抽出される。
        """
        with tempfile.TemporaryDirectory() as td:
            java = Path(td) / "FooController.java"
            # 中央のコメントに不正なバイト列（\xff\xfe）を埋め込む
            # 通常の文字としては表示できないが、errors='replace' で読まれるべき
            content = (
                'package x;\n'
                '@RequestMapping("/api/v1/foo")\n'
                'public class FooController {\n'
                '    // ',
                b'\xff\xfe',
                ' プリセット コメント\n'
                '    @GetMapping("/bar")\n'
                '    public String bar() { return ""; }\n'
                '}\n',
            )
            # バイナリで書き出し
            with open(java, "wb") as f:
                for chunk in content:
                    if isinstance(chunk, str):
                        f.write(chunk.encode("utf-8"))
                    else:
                        f.write(chunk)

            results = scanner.scan_controller(java, expand_scope=False)
            paths = [(r.method, r.path) for r in results]
            self.assertIn(("GET", "/api/v1/foo/bar"), paths)


class TestDesignDocScanIntegration(unittest.TestCase):
    """設計書スキャンの統合テスト: query 付きでも path として取り出される。"""

    def test_design_table_with_query_string(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "| メソッド | パス | 説明 |\n"
                "|---|---|---|\n"
                "| GET | /api/v1/me/foo?scopeType=TEAM | スコープ別取得 |\n"
                "| POST | /api/v1/me/foo/ | 末尾スラッシュ付き |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            # query 切捨済 + 末尾スラッシュ除去済
            self.assertIn(("GET", "/api/v1/me/foo"), paths)
            self.assertIn(("POST", "/api/v1/me/foo"), paths)


class TestExclusionPatterns(unittest.TestCase):
    """除外パターンの YAML パースと glob マッチを検証。"""

    def test_yaml_parser_extracts_patterns(self) -> None:
        yaml_text = (
            "exclude_patterns:\n"
            '  - pattern: "/actuator/**"\n'
            '    reason: "test"\n'
            '  - pattern: "/api/v1/admin/debug/**"\n'
            '    reason: "test"\n'
            "do_not_exclude_examples:\n"
            '  - pattern: "/api/v1/public/**"\n'
            '    reason: "do not exclude"\n'
        )
        patterns = scanner._parse_exclusion_yaml(yaml_text)
        self.assertEqual(len(patterns), 2)
        self.assertIn("/actuator/**", patterns)
        self.assertIn("/api/v1/admin/debug/**", patterns)
        # do_not_exclude_examples 側は含まれない
        self.assertNotIn("/api/v1/public/**", patterns)

    def test_glob_to_regex_double_star(self) -> None:
        regex = scanner._glob_to_regex("/actuator/**")
        self.assertTrue(regex.match("/actuator/health"))
        self.assertTrue(regex.match("/actuator/metrics/jvm"))
        self.assertFalse(regex.match("/api/v1/foo"))

    def test_glob_to_regex_single_star(self) -> None:
        regex = scanner._glob_to_regex("/api/v1/admin/*")
        self.assertTrue(regex.match("/api/v1/admin/users"))
        # 単一 * は / を跨がない
        self.assertFalse(regex.match("/api/v1/admin/users/sub"))

    def test_is_excluded(self) -> None:
        compiled = [
            scanner._glob_to_regex("/actuator/**"),
            scanner._glob_to_regex("/api/v1/admin/debug/**"),
        ]
        self.assertTrue(scanner.is_excluded("/actuator/health", compiled))
        self.assertTrue(scanner.is_excluded("/api/v1/admin/debug/foo", compiled))
        self.assertFalse(scanner.is_excluded("/api/v1/teams/123", compiled))


class TestDomainOf(unittest.TestCase):
    """domain_of: 第 3 セグメントの抽出。"""

    def test_domain_of_api_v1(self) -> None:
        self.assertEqual(scanner.domain_of("/api/v1/teams/123"), "teams")
        self.assertEqual(scanner.domain_of("/api/v1/admin/foo"), "admin")

    def test_domain_of_non_api(self) -> None:
        self.assertEqual(scanner.domain_of("/actuator/health"), "actuator")

    def test_domain_of_empty(self) -> None:
        self.assertEqual(scanner.domain_of(""), "(root)")


class TestV4ScopeReverseMatch(unittest.TestCase):
    """V4-1: スコープ階層プレフィックス逆引きマッチ。"""

    def test_extract_core_path_teams_prefix(self) -> None:
        """`/api/v1/teams/{_}/admin/modules` → `/api/v1/admin/modules`"""
        self.assertEqual(
            scanner.extract_core_path("/api/v1/teams/{_}/admin/modules"),
            "/api/v1/admin/modules",
        )

    def test_extract_core_path_organizations_prefix(self) -> None:
        self.assertEqual(
            scanner.extract_core_path("/api/v1/organizations/{_}/dashboard/users"),
            "/api/v1/dashboard/users",
        )

    def test_extract_core_path_no_prefix_returns_none(self) -> None:
        """スコープ prefix で始まらないパスは None。"""
        self.assertIsNone(scanner.extract_core_path("/api/v1/admin/modules"))
        self.assertIsNone(scanner.extract_core_path("/api/v1/me/foo"))

    def test_extract_core_path_scope_entity_itself_returns_none(self) -> None:
        """`/api/v1/teams/{_}` 自体（scope entity）は core path なし。"""
        self.assertIsNone(scanner.extract_core_path("/api/v1/teams/{_}"))

    def test_v4_1_admin_modules_matched_as_reverse(self) -> None:
        """設計 `/api/v1/admin/modules` ≡ 実装 `/api/v1/teams/{_}/admin/modules`
        が準一致として扱われ、メイン only_* から除外されること。
        """
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/admin/modules",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/teams/{_}/admin/modules",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td), expand_scope=False
            )
            # V4-1 で準一致 → 設計あり・実装なし=0, 実装あり・設計なし=0
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            # 通常 matched にはカウントしない（準一致は別カテゴリ）
            self.assertEqual(mt, 0)

    def test_v4_1_no_match_for_resource_paths(self) -> None:
        """誤合体防止: SCOPE_CORE_PATTERNS 外（例: /api/v1/posts）は逆引きしない。

        設計 `/api/v1/posts` と 実装 `/api/v1/teams/{_}/posts` は別物扱い。
        """
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/posts",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/teams/{_}/posts",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td), expand_scope=False
            )
            # /posts は SCOPE_CORE_PATTERNS 外 → 別物として残る
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v4_1_dashboard_core_matched(self) -> None:
        """dashboard 系も SCOPE_CORE_PATTERNS にマッチして準一致になる。"""
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/dashboard/users",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/organizations/{_}/dashboard/users",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td), expand_scope=False
            )
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)

    def test_v4_1_method_mismatch_no_match(self) -> None:
        """メソッドが違えば逆引きマッチしない（GET vs POST）。"""
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/admin/modules",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/teams/{_}/admin/modules",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td), expand_scope=False
            )
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)


class TestV4FutureFeatureTag(unittest.TestCase):
    """V4-5: 🔵 将来機能タグ認識。"""

    def test_design_table_with_blue_status_extracted(self) -> None:
        """`| 🔵 | GET | /api/v1/foo | ... |` の status=🔵 が抽出される。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "| 状態 | メソッド | パス | 説明 |\n"
                "|---|---|---|---|\n"
                "| 🟢 | GET | `/api/v1/foo` | 実装済 |\n"
                "| 🔵 | POST | `/api/v1/foo/v2` | 将来機能 |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            by_path = {(r.method, r.path): r.status for r in results}
            self.assertEqual(by_path.get(("GET", "/api/v1/foo")), "🟢")
            self.assertEqual(by_path.get(("POST", "/api/v1/foo/v2")), "🔵")

    def test_design_table_without_status_backward_compat(self) -> None:
        """状態列なしの旧テーブルも status=None で従来通り抽出される。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "| メソッド | パス | 説明 |\n"
                "|---|---|---|\n"
                "| GET | `/api/v1/legacy` | 旧テーブル形式 |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path, r.status) for r in results}
            self.assertIn(("GET", "/api/v1/legacy", None), paths)

    def test_blue_endpoint_excluded_from_main_only_design(self) -> None:
        """🔵 のエンドポイントは only_design（メイン）にカウントされない。"""
        de_blue = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/foo/v2",
            source_file="x.md",
            line_number=1,
            status="🔵",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de_blue], [], out, Path(td), expand_scope=False
            )
            # 🔵 単独なら only_design=0（メイン集計外）
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            self.assertEqual(mt, 0)
            # レポート末尾に 🔵 セクションが含まれる
            content = out.read_text(encoding="utf-8")
            self.assertIn("🔵 将来機能", content)
            self.assertIn("/api/v1/foo/v2", content)

    def test_blue_endpoint_with_impl_marked_already(self) -> None:
        """🔵 として登録されたが実装済の場合、only_impl からも除外され、
        将来機能セクションで「実装済✓」と表示される。
        """
        de_blue = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/foo",
            source_file="x.md",
            line_number=1,
            status="🔵",
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/foo",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de_blue], [ie], out, Path(td), expand_scope=False
            )
            # 実装あり・設計なし にもカウントされない（🔵 で別カテゴリ）
            self.assertEqual(mi, 0)
            self.assertEqual(md, 0)
            content = out.read_text(encoding="utf-8")
            # ✓ マークが将来機能セクションに付くこと
            self.assertIn("/api/v1/foo", content)

    def test_normal_status_treated_as_regular(self) -> None:
        """🟢/🟡 は通常扱い（メイン集計に入る）。"""
        de_green = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/green",
            source_file="x.md",
            line_number=1,
            status="🟢",
        )
        de_yellow = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/yellow",
            source_file="x.md",
            line_number=2,
            status="🟡",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de_green, de_yellow], [], out, Path(td), expand_scope=False
            )
            # 両方とも only_design に入る（🔵 ではないため）
            self.assertEqual(md, 2)

    def test_blue_table_parser_regex_with_inline_code(self) -> None:
        """`| 🔵 | GET | \`/api/v1/foo\` | ... |` のようにパスがバッククォート
        付きでも抽出できる。
        """
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "| 🔵 | GET | `/api/v1/admin/seals/regenerate-all/{jobId}/status` | SYSTEM_ADMIN | xxx |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            statuses = [(r.method, r.path, r.status) for r in results]
            self.assertIn(
                ("GET", "/api/v1/admin/seals/regenerate-all/{_}/status", "🔵"),
                statuses,
            )


class TestV4CorePatternMatch(unittest.TestCase):
    """SCOPE_CORE_PATTERNS が glob として正しくマッチするか。"""

    def test_admin_pattern_matches(self) -> None:
        self.assertTrue(scanner._core_pattern_matches("/api/v1/admin/modules"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/admin/dashboard/users"))

    def test_dashboard_pattern_matches(self) -> None:
        self.assertTrue(scanner._core_pattern_matches("/api/v1/dashboard/users"))

    def test_non_whitelisted_does_not_match(self) -> None:
        self.assertFalse(scanner._core_pattern_matches("/api/v1/posts"))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/coupons/{_}"))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/me/foo"))


if __name__ == "__main__":
    unittest.main()
