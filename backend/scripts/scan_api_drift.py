#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
API 乖離スキャナ（試作版）

設計書 (docs/features/F*.md) と実装 (backend/src/main/java/**/controller/*.java)
の API エンドポイント差分を抽出し、`docs/internal/api_drift_baseline.md` に
Markdown レポートを生成する。

実行:
    cd backend && python scripts/scan_api_drift.py

注意:
    本スクリプトは「殿様判断資料」を作るための使い捨て試作。
    Phase A 本実装で置き換える前提。標準ライブラリのみ使用。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict, namedtuple
from datetime import date
from pathlib import Path

HTTP_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")

# ---------------------------------------------------------------------------
# データ構造
# ---------------------------------------------------------------------------
DesignEndpoint = namedtuple(
    "DesignEndpoint", ["method", "path", "source_file", "line_number"]
)
ImplEndpoint = namedtuple(
    "ImplEndpoint",
    ["method", "path", "source_file", "line_number", "class_name", "method_name"],
)


# ---------------------------------------------------------------------------
# パス正規化
# ---------------------------------------------------------------------------
_PATH_PARAM_RE = re.compile(r"\{[^/}]+\}")


def normalize_path(path: str) -> str:
    """パスパラメータを {_} に統一し、末尾スラッシュを除去する。"""
    if path is None:
        return ""
    # 余分なホワイトスペース・改行除去
    path = path.strip()
    # 末尾スラッシュ除去（ただし "/" のみは残す）
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    # {anyName} → {_}
    path = _PATH_PARAM_RE.sub("{_}", path)
    return path


def domain_of(path: str) -> str:
    """`/api/v1/teams/...` から `teams` を取り出す。第 3 セグメント基準。"""
    parts = [p for p in path.split("/") if p]
    # parts = ["api", "v1", "teams", ...]
    if len(parts) >= 3 and parts[0] == "api":
        return parts[2]
    if len(parts) >= 1:
        return parts[0]
    return "(root)"


# ---------------------------------------------------------------------------
# 設計書スキャン
# ---------------------------------------------------------------------------
# Markdown テーブル行: `| GET | /api/v1/... | ... |`
#   メソッド・パスはバッククォートで囲まれる場合とそうでない場合の両方あり
_DESIGN_TABLE_RE = re.compile(
    r"^\s*\|\s*`?(GET|POST|PUT|PATCH|DELETE)`?\s*\|"
    r"\s*`?(/api/v\d+/[^\s`|]+)`?\s*\|",
    re.IGNORECASE,
)
# 見出し: `### GET /api/v1/...`
_DESIGN_HEADING_RE = re.compile(
    r"^\s*#{1,6}\s+(GET|POST|PUT|PATCH|DELETE)\s+(/api/v\d+/\S+)",
    re.IGNORECASE,
)


def scan_design_docs(docs_dir: Path) -> list[DesignEndpoint]:
    """`docs/features/F*.md` を全て走査し設計記載エンドポイントを集める。"""
    results: list[DesignEndpoint] = []
    if not docs_dir.is_dir():
        print(f"[WARN] features dir not found: {docs_dir}", file=sys.stderr)
        return results

    for md in sorted(docs_dir.glob("F*.md")):
        try:
            text = md.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"[WARN] cannot read {md}: {exc}", file=sys.stderr)
            continue

        for i, line in enumerate(text.splitlines(), start=1):
            m = _DESIGN_TABLE_RE.match(line)
            if m:
                method = m.group(1).upper()
                path = m.group(2).rstrip(".,;`")
                results.append(
                    DesignEndpoint(
                        method=method,
                        path=normalize_path(path),
                        source_file=str(md.as_posix()),
                        line_number=i,
                    )
                )
                continue
            m2 = _DESIGN_HEADING_RE.match(line)
            if m2:
                method = m2.group(1).upper()
                path = m2.group(2).rstrip(".,;`)")
                results.append(
                    DesignEndpoint(
                        method=method,
                        path=normalize_path(path),
                        source_file=str(md.as_posix()),
                        line_number=i,
                    )
                )
    return results


# ---------------------------------------------------------------------------
# 実装スキャン
# ---------------------------------------------------------------------------
# クラスレベル: @RequestMapping("/api/v1/...")
_CLASS_REQ_MAPPING_RE = re.compile(
    r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"'
)
# メソッドレベル: @GetMapping("..."), @PostMapping("..."), 引数なしの @GetMapping も対応
_METHOD_MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Patch|Delete)Mapping\s*'
    r'(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"[^)]*\)|\(\s*\)|(?=\s))',
)
# 旧形式: @RequestMapping(value="...", method = RequestMethod.GET)
_OLD_REQ_MAPPING_RE = re.compile(
    r'@RequestMapping\s*\(([^)]*method\s*=\s*RequestMethod\.[^)]*)\)',
    re.DOTALL,
)
_OLD_VALUE_RE = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"')
_OLD_METHOD_RE = re.compile(r'RequestMethod\.(GET|POST|PUT|PATCH|DELETE)')

_CLASS_DECL_RE = re.compile(r'\bclass\s+(\w+)')
_METHOD_DECL_RE = re.compile(
    r'\b(?:public|protected|private)\s+(?:[\w<>,\s\?\[\]]+)\s+(\w+)\s*\('
)


def _join(class_path: str, method_path: str) -> str:
    """クラスパスとメソッドパスを結合（重複スラッシュ抑止）。"""
    if not class_path:
        class_path = ""
    if not method_path:
        method_path = ""
    if class_path.endswith("/") and method_path.startswith("/"):
        return class_path[:-1] + method_path
    if not class_path.endswith("/") and method_path and not method_path.startswith("/"):
        return class_path + "/" + method_path
    return class_path + method_path


def scan_controller(java_file: Path) -> list[ImplEndpoint]:
    """単一 Controller ファイルからエンドポイントを抽出する。"""
    try:
        text = java_file.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"[WARN] cannot read {java_file}: {exc}", file=sys.stderr)
        return []

    lines = text.splitlines()

    # クラスレベル @RequestMapping を探す（最初のクラス宣言の直前まで）
    class_path = ""
    class_name = "?"
    class_line_idx = None
    for i, line in enumerate(lines):
        # クラス宣言を検出
        cm = re.search(r'\b(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s+(\w+)', line)
        if cm and "@" not in line:
            class_name = cm.group(1)
            class_line_idx = i
            break

    # クラスレベル @RequestMapping は class 宣言より上の領域で探す
    head = "\n".join(lines[: class_line_idx if class_line_idx is not None else len(lines)])
    cm = _CLASS_REQ_MAPPING_RE.search(head)
    if cm:
        class_path = cm.group(1)

    results: list[ImplEndpoint] = []

    # 全行スキャン: メソッドアノテーション
    for i, line in enumerate(lines, start=1):
        # 新形式
        for mm in _METHOD_MAPPING_RE.finditer(line):
            verb = mm.group(1).upper()  # GET / POST / ...
            sub_path = mm.group(2) if mm.group(2) is not None else ""
            full = _join(class_path, sub_path)
            method_name = _find_method_name(lines, i - 1)
            results.append(
                ImplEndpoint(
                    method=verb,
                    path=normalize_path(full),
                    source_file=str(java_file.as_posix()),
                    line_number=i,
                    class_name=class_name,
                    method_name=method_name,
                )
            )

    # 旧形式 @RequestMapping(value="...", method=RequestMethod.X)
    # multiline 対応のため text 全体に対して検索
    for om in _OLD_REQ_MAPPING_RE.finditer(text):
        body = om.group(0)
        val_m = _OLD_VALUE_RE.search(body)
        met_m = _OLD_METHOD_RE.search(body)
        if not val_m or not met_m:
            continue
        verb = met_m.group(1).upper()
        sub_path = val_m.group(1)
        # ヒット位置から行番号算出
        line_no = text[: om.start()].count("\n") + 1
        full = _join(class_path, sub_path)
        method_name = _find_method_name(lines, line_no - 1)
        results.append(
            ImplEndpoint(
                method=verb,
                path=normalize_path(full),
                source_file=str(java_file.as_posix()),
                line_number=line_no,
                class_name=class_name,
                method_name=method_name,
            )
        )

    return results


def _find_method_name(lines: list[str], anno_idx: int) -> str:
    """アノテーション行の次以降から最初のメソッド宣言名を取り出す。"""
    for j in range(anno_idx + 1, min(anno_idx + 15, len(lines))):
        line = lines[j]
        if line.lstrip().startswith("@"):
            continue
        mm = _METHOD_DECL_RE.search(line)
        if mm:
            return mm.group(1)
    return "?"


def scan_implementations(controllers_root: Path) -> list[ImplEndpoint]:
    """`backend/src/main/java/**/controller/*Controller.java` を全件走査。"""
    results: list[ImplEndpoint] = []
    if not controllers_root.is_dir():
        print(f"[WARN] backend src root not found: {controllers_root}", file=sys.stderr)
        return results
    for java in sorted(controllers_root.rglob("*Controller.java")):
        results.extend(scan_controller(java))
    return results


# ---------------------------------------------------------------------------
# 突合・レポート生成
# ---------------------------------------------------------------------------
def make_report(
    designs: list[DesignEndpoint],
    impls: list[ImplEndpoint],
    out_file: Path,
    repo_root: Path,
) -> tuple[int, int, int]:
    """突合してレポートを書き出す。戻り値: (missing_impl, missing_design, matched)。"""
    design_keys: dict[tuple[str, str], list[DesignEndpoint]] = defaultdict(list)
    for d in designs:
        design_keys[(d.method, d.path)].append(d)

    impl_keys: dict[tuple[str, str], list[ImplEndpoint]] = defaultdict(list)
    for i in impls:
        impl_keys[(i.method, i.path)].append(i)

    only_design = sorted(set(design_keys) - set(impl_keys))
    only_impl = sorted(set(impl_keys) - set(design_keys))
    matched = sorted(set(design_keys) & set(impl_keys))

    def rel(p: str) -> str:
        try:
            return str(Path(p).resolve().relative_to(repo_root.resolve()).as_posix())
        except (ValueError, OSError):
            return p

    today = date.today().isoformat()
    lines: list[str] = []
    lines.append(f"# API 乖離ベースライン報告書（{today} 時点）")
    lines.append("")
    lines.append("> 本報告書は `backend/scripts/scan_api_drift.py` により自動生成された。")
    lines.append("> 設計書 `docs/features/F*.md` のテーブル/見出し記載と、")
    lines.append("> 実装 `backend/src/main/java/**/controller/*Controller.java` の")
    lines.append("> Spring MVC アノテーションを突合した結果である。")
    lines.append("")
    lines.append("## サマリ")
    lines.append("")
    lines.append(f"- 設計あり・実装なし: **{len(only_design)} 件**")
    lines.append(f"- 実装あり・設計なし: **{len(only_impl)} 件**")
    lines.append(f"- 一致: **{len(matched)} 件**")
    lines.append(f"- 設計記載 ユニーク (method, path) 総数: {len(design_keys)}")
    lines.append(f"- 実装 ユニーク (method, path) 総数: {len(impl_keys)}")
    lines.append("")
    lines.append("---")
    lines.append("")

    # 1. 設計あり・実装なし
    lines.append("## 1. 🔴 設計あり・実装なし（Phase 1 漏れ系）")
    lines.append("")
    if not only_design:
        lines.append("_該当なし。_")
    else:
        # ドメイン別グルーピング
        by_domain: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for key in only_design:
            by_domain[domain_of(key[1])].append(key)
        for dom in sorted(by_domain):
            keys = by_domain[dom]
            lines.append(f"### /api/v1/{dom}/* ({len(keys)} 件)")
            lines.append("")
            lines.append("| メソッド | パス | 設計書 | 行 |")
            lines.append("|---|---|---|---|")
            for key in sorted(keys):
                method, path = key
                for d in design_keys[key]:
                    lines.append(
                        f"| {method} | `{path}` | `{rel(d.source_file)}` | {d.line_number} |"
                    )
            lines.append("")
    lines.append("---")
    lines.append("")

    # 2. 実装あり・設計なし
    lines.append("## 2. 🟡 実装あり・設計なし（設計書整備候補）")
    lines.append("")
    if not only_impl:
        lines.append("_該当なし。_")
    else:
        by_domain2: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for key in only_impl:
            by_domain2[domain_of(key[1])].append(key)
        # ドメイン件数の多い順
        for dom in sorted(by_domain2, key=lambda d: (-len(by_domain2[d]), d)):
            keys = by_domain2[dom]
            lines.append(f"#### /api/v1/{dom}/* ({len(keys)} 件)")
            lines.append("")
            lines.append("| メソッド | パス | Controller | 行 |")
            lines.append("|---|---|---|---|")
            for key in sorted(keys):
                method, path = key
                for i in impl_keys[key]:
                    lines.append(
                        f"| {method} | `{path}` | `{i.class_name}#{i.method_name}` "
                        f"({rel(i.source_file)}) | {i.line_number} |"
                    )
            lines.append("")
    lines.append("---")
    lines.append("")

    # 3. 一致
    lines.append("## 3. ✅ 一致（正常）")
    lines.append("")
    lines.append(f"一致したエンドポイント: **{len(matched)} 件**（詳細リストは省略）")
    lines.append("")

    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(only_design), len(only_impl), len(matched)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main() -> int:
    # repo root: scripts/ の 2 階層上 = リポジトリルート
    script_path = Path(__file__).resolve()
    repo_root = script_path.parent.parent.parent  # scripts -> backend -> repo
    docs_features = repo_root / "docs" / "features"
    controllers_root = repo_root / "backend" / "src" / "main" / "java"
    out_file = repo_root / "docs" / "internal" / "api_drift_baseline.md"

    print(f"[INFO] repo root        : {repo_root}")
    print(f"[INFO] design docs dir  : {docs_features}")
    print(f"[INFO] controllers root : {controllers_root}")
    print(f"[INFO] output           : {out_file}")

    designs = scan_design_docs(docs_features)
    impls = scan_implementations(controllers_root)

    print(f"[INFO] design endpoints (raw): {len(designs)}")
    print(f"[INFO] impl  endpoints (raw): {len(impls)}")

    missing_impl, missing_design, matched = make_report(
        designs, impls, out_file, repo_root
    )
    print(
        f"[DONE] missing_impl={missing_impl} "
        f"missing_design={missing_design} matched={matched}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
