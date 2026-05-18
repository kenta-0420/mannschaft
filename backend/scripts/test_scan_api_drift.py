#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
API 乖離スキャナ v6 の単体テスト。

実行:
    cd backend && python -m unittest scripts/test_scan_api_drift.py
    （または）python -m unittest backend.scripts.test_scan_api_drift

v2 で確認された 6 つの偽陽性バグについて、reproducer + 期待挙動を検証する。
v4 拡張: V4-1 スコープ階層プレフィックス逆引きマッチ・V4-5 🔵 将来機能タグ認識。
v6 拡張: V6-1 同一設計書内 (method, path) 重複排除（デフォルト ON）、
         V6-2 末尾セグメントリネーム辞書による準一致（デフォルト OFF）。
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
        # v4 デフォルト（v5_reverse=False）ではリソース系は許可されない
        self.assertFalse(scanner._core_pattern_matches("/api/v1/posts", v5_reverse=False))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/coupons/{_}", v5_reverse=False))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/me/foo", v5_reverse=False))
        # v5 デフォルト (v5_reverse=True) でも /posts や /me は許可されない
        self.assertFalse(scanner._core_pattern_matches("/api/v1/posts"))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/me/foo"))


class TestV5ReverseExpansion(unittest.TestCase):
    """V5-1: リソース系スコープ逆引き拡張。"""

    def test_v5_core_pattern_matches_resource_paths(self) -> None:
        """v5_reverse=True のとき /coupons/**, /surveys/** 等が許可される。"""
        self.assertTrue(scanner._core_pattern_matches("/api/v1/coupons/{_}"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/dwelling-units/{_}"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/repair-plans/{_}/stats"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/forms/templates"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/surveys/{_}/publish"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/workflows/templates"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/circulation/{_}/stamp"))
        self.assertTrue(scanner._core_pattern_matches("/api/v1/bulletin/threads"))

    def test_v5_core_pattern_disabled_when_flag_off(self) -> None:
        """v5_reverse=False のとき V5 リソース系は許可されない（v4 互換）。"""
        self.assertFalse(scanner._core_pattern_matches("/api/v1/coupons/{_}", v5_reverse=False))
        self.assertFalse(scanner._core_pattern_matches("/api/v1/surveys/{_}", v5_reverse=False))

    def test_v5_reverse_match_coupons(self) -> None:
        """設計 /api/v1/coupons/{_} ≡ 実装 /api/v1/teams/{_}/coupons/{_}
        が V5-1 で準一致になる。
        """
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/coupons/{_}",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/teams/{_}/coupons/{_}",
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
            # V5-1 で準一致 → 両方 0 件
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            content = out.read_text(encoding="utf-8")
            # 準一致セクションに記載される
            self.assertIn("/api/v1/teams/{_}/coupons/{_}", content)

    def test_v5_reverse_disabled_keeps_resource_paths_unmatched(self) -> None:
        """--no-v5-reverse のときリソース系は別物のまま。"""
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/coupons/{_}",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/teams/{_}/coupons/{_}",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v5_reverse=False,
            )
            # v5_reverse=False のとき準一致しない（別物）
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v5_reverse_dwelling_units_orgs_prefix(self) -> None:
        """設計 /api/v1/dwelling-units/{_} ≡ 実装 /api/v1/organizations/{_}/dwelling-units/{_}"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/dwelling-units/{_}/invite",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/organizations/{_}/dwelling-units/{_}/invite",
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


class TestV5InlineCodeScan(unittest.TestCase):
    """V5-2: 設計書インラインコード強化。"""

    def test_inline_code_in_table_description_column(self) -> None:
        """テーブルの説明列に書かれたインラインコード `GET /api/v1/...` を拾う。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "| 機能 | 説明 |\n"
                "|---|---|\n"
                "| クーポン作成 | 内部で `POST /api/v1/internal/coupons` を呼び出す |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            self.assertIn(("POST", "/api/v1/internal/coupons"), paths)

    def test_inline_code_in_bullet_list(self) -> None:
        """箇条書きの中のインラインコードを拾う。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "## API 一覧\n\n"
                "- `GET /api/v1/foo/bar` でデータ取得\n"
                "- `POST /api/v1/foo/baz` で作成\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            self.assertIn(("GET", "/api/v1/foo/bar"), paths)
            self.assertIn(("POST", "/api/v1/foo/baz"), paths)

    def test_html_comment_excluded(self) -> None:
        """HTML コメント内のインラインコードは抽出しない。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "<!-- TODO: 後日 `GET /api/v1/legacy/foo` を削除 -->\n"
                "本文: `POST /api/v1/main/bar`\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            # コメント内のものは含まれない
            self.assertNotIn(("GET", "/api/v1/legacy/foo"), paths)
            # 本文のものは含まれる
            self.assertIn(("POST", "/api/v1/main/bar"), paths)

    def test_code_block_excluded(self) -> None:
        """コードブロック ``` ... ``` 内の path は抽出しない。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "```bash\n"
                "curl -X GET /api/v1/in-code/foo\n"
                "```\n"
                "\n"
                "本文: `POST /api/v1/main/bar`\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            self.assertNotIn(("GET", "/api/v1/in-code/foo"), paths)
            self.assertIn(("POST", "/api/v1/main/bar"), paths)

    def test_inline_code_multiline_html_comment_excluded(self) -> None:
        """複数行にわたる HTML コメントもまるごと除外される。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "<!--\n"
                "TODO リスト:\n"
                "- `DELETE /api/v1/old/foo` を削除\n"
                "- `PUT /api/v1/old/bar` を移行\n"
                "-->\n"
                "\n"
                "現役: `GET /api/v1/current/list`\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            paths = {(r.method, r.path) for r in results}
            self.assertNotIn(("DELETE", "/api/v1/old/foo"), paths)
            self.assertNotIn(("PUT", "/api/v1/old/bar"), paths)
            self.assertIn(("GET", "/api/v1/current/list"), paths)


class TestV5NamingNormalization(unittest.TestCase):
    """V5-3: 単複形揺れの命名揺れ正規化。"""

    def test_normalize_naming_feedback_to_feedbacks(self) -> None:
        self.assertEqual(
            scanner.normalize_naming("/api/v1/feedback/{_}/vote"),
            "/api/v1/feedbacks/{_}/vote",
        )

    def test_normalize_naming_circulation_to_circulations(self) -> None:
        self.assertEqual(
            scanner.normalize_naming("/api/v1/circulation/{_}/stamp"),
            "/api/v1/circulations/{_}/stamp",
        )

    def test_normalize_naming_already_plural_unchanged(self) -> None:
        # 複数形は不変
        self.assertEqual(
            scanner.normalize_naming("/api/v1/feedbacks/me"),
            "/api/v1/feedbacks/me",
        )

    def test_normalize_naming_outside_dict_unchanged(self) -> None:
        # 辞書外は不変
        self.assertEqual(
            scanner.normalize_naming("/api/v1/coupons/{_}"),
            "/api/v1/coupons/{_}",
        )

    def test_normalize_naming_non_api_path_unchanged(self) -> None:
        self.assertEqual(scanner.normalize_naming("/foo/feedback"), "/foo/feedback")

    def test_v5_3_design_singular_matches_impl_plural(self) -> None:
        """設計 /api/v1/circulation/{_}/stamp ≡ 実装 /api/v1/circulations/{_}/stamp"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/circulation/{_}/stamp",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/stamp",
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
            # V5-3 で準一致 → 両方 0 件
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            content = out.read_text(encoding="utf-8")
            self.assertIn("命名揺れ正規化", content)
            self.assertIn("matched by naming-normalization", content)

    def test_v5_3_naming_normalization_disabled(self) -> None:
        """--no-v5-naming で命名揺れマッチが無効化される。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/circulation/{_}/stamp",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/stamp",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v5_naming=False,
            )
            # 命名揺れ無効化 → 別物として残る
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v5_3_feedback_to_feedbacks(self) -> None:
        """feedback (単数) ↔ feedbacks (複数) の正規化。"""
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/feedback/me",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/feedbacks/me",
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


class TestV6DedupWithinFile(unittest.TestCase):
    """V6-1: 同一設計書ファイル内で (method, path) を重複排除する。

    Stage 3 第三陣以降で繰り返し報告された偽陽性パターン:
        §4 一覧表 (`| GET | /api/v1/foo | ... |`) と §4.x 詳細ヘッダ
        (`#### GET /api/v1/foo`) で同じ (method, path) が 2 度抽出される。
    """

    def test_dedup_table_and_heading_in_same_file(self) -> None:
        """同一ファイル内で 一覧表 と 詳細ヘッダ で同じ (method, path) が
        2 回出てきても 1 件に集約される。
        """
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "## 4. API 一覧\n\n"
                "| メソッド | パス | 説明 |\n"
                "|---|---|---|\n"
                "| GET | /api/v1/foo | 取得 |\n"
                "\n"
                "## 4.1 詳細\n\n"
                "#### GET /api/v1/foo\n"
                "\n"
                "詳細説明...\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            keys = [(r.method, r.path) for r in results]
            # 1 件のみに集約
            self.assertEqual(keys.count(("GET", "/api/v1/foo")), 1)

    def test_dedup_preserves_first_occurrence(self) -> None:
        """重複時、最初の出現（一覧表）の行番号・source_file を保持する。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "# テスト設計書\n\n"
                "| メソッド | パス | 説明 |\n"
                "|---|---|---|\n"
                "| GET | /api/v1/foo | 取得 |\n"
                "\n"
                "#### GET /api/v1/foo\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            foos = [r for r in results if (r.method, r.path) == ("GET", "/api/v1/foo")]
            self.assertEqual(len(foos), 1)
            # 一覧表（行 5）が最初の出現
            self.assertEqual(foos[0].line_number, 5)

    def test_dedup_does_not_merge_across_files(self) -> None:
        """異なるファイル間の同一 (method, path) は集約しない（正当な相互参照）。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            (features / "F01.test.md").write_text(
                "| GET | /api/v1/shared | A |\n", encoding="utf-8"
            )
            (features / "F02.test.md").write_text(
                "| GET | /api/v1/shared | B |\n", encoding="utf-8"
            )
            results = scanner.scan_design_docs(features)
            shared = [r for r in results if (r.method, r.path) == ("GET", "/api/v1/shared")]
            # 2 ファイルから 1 件ずつ、計 2 件残る
            self.assertEqual(len(shared), 2)
            sources = {r.source_file for r in shared}
            self.assertEqual(len(sources), 2)

    def test_no_v6_dedup_flag_disables_within_file_dedup(self) -> None:
        """v6_dedup=False で従来挙動（重複そのまま）に戻る。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "| メソッド | パス | 説明 |\n"
                "|---|---|---|\n"
                "| GET | /api/v1/foo | 取得 |\n"
                "\n"
                "#### GET /api/v1/foo\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features, v6_dedup=False)
            keys = [(r.method, r.path) for r in results]
            # フラグ OFF なら 2 件残る
            self.assertEqual(keys.count(("GET", "/api/v1/foo")), 2)

    def test_dedup_does_not_affect_distinct_methods(self) -> None:
        """method が違えば重複と見なさない。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            md = features / "F99.test.md"
            md.write_text(
                "| GET | /api/v1/foo | 取得 |\n"
                "| POST | /api/v1/foo | 作成 |\n",
                encoding="utf-8",
            )
            results = scanner.scan_design_docs(features)
            keys = {(r.method, r.path) for r in results}
            self.assertIn(("GET", "/api/v1/foo"), keys)
            self.assertIn(("POST", "/api/v1/foo"), keys)
            self.assertEqual(len(keys), 2)

    def test_dedup_helper_preserves_order(self) -> None:
        """dedup_design_within_file は入力順を保持する。"""
        eps = [
            scanner.DesignEndpoint("GET", "/api/v1/a", "x.md", 1, None),
            scanner.DesignEndpoint("GET", "/api/v1/b", "x.md", 2, None),
            scanner.DesignEndpoint("GET", "/api/v1/a", "x.md", 3, None),  # dup
            scanner.DesignEndpoint("GET", "/api/v1/a", "y.md", 4, None),  # 別ファイル
        ]
        out = scanner.dedup_design_within_file(eps)
        # x.md/GET /a は 1 件、b は 1 件、y.md/GET /a は 1 件 → 計 3 件
        self.assertEqual(len(out), 3)
        # 最初の出現（行 1, x.md）が保持される
        self.assertEqual(out[0].line_number, 1)
        self.assertEqual(out[0].source_file, "x.md")

    def test_dedup_reduces_only_design_count(self) -> None:
        """重複設計記載 + 実装なし の場合、v6_dedup により only_design は 1 件に。"""
        with tempfile.TemporaryDirectory() as td:
            features = Path(td) / "docs" / "features"
            features.mkdir(parents=True)
            (features / "F99.test.md").write_text(
                "| GET | /api/v1/foo | 取得 |\n"
                "\n"
                "#### GET /api/v1/foo\n",
                encoding="utf-8",
            )
            designs = scanner.scan_design_docs(features)
            with tempfile.TemporaryDirectory() as td2:
                out = Path(td2) / "out.md"
                md, mi, mt = scanner.make_report(
                    designs, [], out, Path(td2), expand_scope=False
                )
                # 同一ファイル内 dedup により 1 件
                self.assertEqual(md, 1)


class TestV6RenamePairs(unittest.TestCase):
    """V6-2: 末尾セグメントリネーム辞書による準一致。"""

    def test_rename_lookup_is_bidirectional(self) -> None:
        """_RENAME_LOOKUP_V6 は双方向（a→{b}, b→{a}）。"""
        self.assertIn("approve", scanner._RENAME_LOOKUP_V6.get("first-approve", set()))
        self.assertIn("first-approve", scanner._RENAME_LOOKUP_V6.get("approve", set()))
        self.assertIn(
            "evidence-package", scanner._RENAME_LOOKUP_V6.get("evidence-zip", set())
        )
        self.assertIn(
            "evidence-zip", scanner._RENAME_LOOKUP_V6.get("evidence-package", set())
        )

    def test_find_rename_match_basic(self) -> None:
        """末尾セグメントが辞書ペア、それ以外完全一致 → 候補から見つけて返す。"""
        candidates = {"/api/v1/foo/{_}/approve"}
        self.assertEqual(
            scanner.find_rename_match("/api/v1/foo/{_}/first-approve", candidates),
            "/api/v1/foo/{_}/approve",
        )

    def test_find_rename_match_returns_none_if_other_segments_differ(self) -> None:
        """末尾セグメント以外が違う → マッチしない（誤合体回避）。"""
        candidates = {"/api/v1/bar/{_}/approve"}
        self.assertIsNone(
            scanner.find_rename_match("/api/v1/foo/{_}/first-approve", candidates)
        )

    def test_find_rename_match_returns_none_for_non_whitelisted(self) -> None:
        """ホワイトリストにない言い換え (delete↔remove 等) はマッチしない。"""
        candidates = {"/api/v1/foo/{_}/remove"}
        self.assertIsNone(
            scanner.find_rename_match("/api/v1/foo/{_}/delete", candidates)
        )

    def test_v6_rename_off_by_default(self) -> None:
        """デフォルトでは V6-2 は OFF → 設計と実装が別物のまま残る。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/first-approve",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/approve",
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
            # v6_rename=False（既定）→ 別物として残る
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v6_rename_on_matches_first_approve_approve(self) -> None:
        """--v6-rename ON で first-approve ↔ approve が準一致になる。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/first-approve",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/circulations/{_}/approve",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)
            content = out.read_text(encoding="utf-8")
            self.assertIn("リネーム辞書", content)
            self.assertIn("matched by rename normalization", content)

    def test_v6_rename_on_matches_evidence_zip_package(self) -> None:
        """evidence-zip ↔ evidence-package も準一致。"""
        de = scanner.DesignEndpoint(
            method="GET",
            path="/api/v1/foo/{_}/evidence-zip",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/foo/{_}/evidence-package",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)

    def test_v6_rename_does_not_match_arbitrary_pair(self) -> None:
        """ホワイトリスト外（例: delete ↔ remove）は ON でもマッチしない。"""
        de = scanner.DesignEndpoint(
            method="DELETE",
            path="/api/v1/foo/{_}/delete",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="DELETE",
            path="/api/v1/foo/{_}/remove",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            # ホワイトリスト外 → 別物のまま
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v6_rename_does_not_match_when_other_segments_differ(self) -> None:
        """末尾は辞書ペアでも、他セグメントが違えば合体しない（誤合体回避）。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/foo/{_}/first-approve",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/bar/{_}/approve",  # foo vs bar
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            # 別物として残る
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v6_rename_method_must_match(self) -> None:
        """method が違えば V6-2 でもマッチしない。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/foo/{_}/first-approve",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="GET",
            path="/api/v1/foo/{_}/approve",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            self.assertEqual(md, 1)
            self.assertEqual(mi, 1)

    def test_v6_rename_bidirectional_design_plain_impl_first(self) -> None:
        """逆向き: 設計 approve, 実装 first-approve でもマッチする（双方向）。"""
        de = scanner.DesignEndpoint(
            method="POST",
            path="/api/v1/foo/{_}/approve",
            source_file="x.md",
            line_number=1,
            status=None,
        )
        ie = scanner.ImplEndpoint(
            method="POST",
            path="/api/v1/foo/{_}/first-approve",
            source_file="X.java",
            line_number=10,
            class_name="X",
            method_name="m",
        )
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            md, mi, mt = scanner.make_report(
                [de], [ie], out, Path(td),
                expand_scope=False, v6_rename=True,
            )
            self.assertEqual(md, 0)
            self.assertEqual(mi, 0)

    def test_v6_rename_section_shows_disabled_message_when_off(self) -> None:
        """v6_rename=OFF のときレポートに「無効化されている」旨が記載される。"""
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "out.md"
            scanner.make_report(
                [], [], out, Path(td), expand_scope=False, v6_rename=False
            )
            content = out.read_text(encoding="utf-8")
            self.assertIn("リネーム辞書", content)
            self.assertIn("無効化されている", content)


if __name__ == "__main__":
    unittest.main()
