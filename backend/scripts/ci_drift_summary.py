#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""API drift CI サマリ生成スクリプト（Stage 4 第六陣 / 案 A fail-on-new-drift 昇格）。

`scan_api_drift.py` は出力先が固定 (`docs/internal/api_drift_baseline.md`) のため、
このスクリプトは「main の baseline」と「PR で生成された baseline」を **読み込んで**
PR コメント用の Markdown 差分サマリを生成する。

入力:
    --main <PATH>   main ブランチで生成済みの baseline.md（CI が `git show` で取得）
    --pr   <PATH>   PR ブランチで生成し直した baseline.md
    --output <PATH> 出力先 Markdown（PR コメント本文）

出力:
    docs/internal/api_drift_baseline.md と同じ Markdown を parse して、
    `missing_impl` / `missing_design` / `matched` の件数差分と、
    新規発生／解消された (method, path) の一覧を Markdown で書き出す。

主要設計:
    - パースは緩く、サマリ行を正規表現でひっかける
    - パス一覧は `## 1. 🔴 設計あり・実装なし` と `## 2. 🟡 実装あり・設計なし`
      セクション内の `| METHOD | \`/api/...\` | ...` テーブル行から抽出
    - 主キーは (method, normalized_path) の組
    - 表示数が多いと PR コメントが肥大するので、上位 30 件＋件数だけ出す
    - 環境変数 ``STRICT_DRIFT=true`` のとき、**PR で新規発生**した missing_impl /
      missing_design があれば exit 1 で PR を fail させる（案A昇格）
      - main baseline に存在しない (method, path) が PR baseline に出現したものだけが対象
      - 既存 drift（main にも PR にも同じく載っている）は無視 → chip-away 運用
      - 解消 drift（main にあり PR で消えた）は当然 fail しない
    - ``STRICT_DRIFT`` 未設定 / ``false`` のときは従来通り常に exit 0（warning-only）
    - exit 1 でも ``comment.md`` は必ず書き出す（CI のコメント投稿ステップが走るため）
    - main baseline が無い（初回導入時）は STRICT_DRIFT に関わらず exit 0
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# サマリ行（例:「- 設計あり・実装なし: **1223 件**（v3: 1,214 件 / v2: 1,256 件 / v1: 1,187 件）」）を拾う
SUMMARY_PATTERNS = {
    "missing_impl": re.compile(r"^- 設計あり・実装なし: \*\*(\d+)\s*件\*\*"),
    "missing_design": re.compile(r"^- 実装あり・設計なし: \*\*(\d+)\s*件\*\*"),
    "matched": re.compile(r"^- 一致: \*\*(\d+)\s*件\*\*"),
}

# テーブル行例: | GET | `/api/v1/teams/{teamId}/foo` | `docs/...` | 123 |
TABLE_ROW = re.compile(
    r"^\|\s*(GET|POST|PUT|PATCH|DELETE)\s*\|\s*`([^`]+)`\s*\|"
)

# Section header heuristics
SECTION_MISSING_IMPL = re.compile(r"^##\s+1\.\s+.*設計あり・実装なし")
SECTION_MISSING_DESIGN = re.compile(r"^##\s+2\.\s+.*実装あり・設計なし")
SECTION_BREAK = re.compile(r"^##\s+\d+\.\s+")

TOP_N = 30


def parse_baseline(path: Path) -> dict[str, object]:
    """baseline.md を読み込んでカウントとパス集合を返す。"""
    if not path.exists():
        return {
            "exists": False,
            "missing_impl": 0,
            "missing_design": 0,
            "matched": 0,
            "missing_impl_paths": set(),
            "missing_design_paths": set(),
        }

    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    counts = {"missing_impl": 0, "missing_design": 0, "matched": 0}
    for ln in lines:
        for key, pat in SUMMARY_PATTERNS.items():
            m = pat.match(ln)
            if m:
                counts[key] = int(m.group(1))

    # セクション内テーブル行を拾う
    missing_impl_paths: set[tuple[str, str]] = set()
    missing_design_paths: set[tuple[str, str]] = set()

    mode: str | None = None
    for ln in lines:
        if SECTION_MISSING_IMPL.match(ln):
            mode = "missing_impl"
            continue
        if SECTION_MISSING_DESIGN.match(ln):
            mode = "missing_design"
            continue
        if SECTION_BREAK.match(ln) and mode is not None:
            # 別 section に入った → 抽出停止
            if not (
                SECTION_MISSING_IMPL.match(ln) or SECTION_MISSING_DESIGN.match(ln)
            ):
                mode = None
                continue

        if mode is None:
            continue

        m = TABLE_ROW.match(ln)
        if not m:
            continue
        method, path = m.group(1), m.group(2)
        # `…` の中身に空白が混じることがあるので strip
        path = path.strip()
        if mode == "missing_impl":
            missing_impl_paths.add((method, path))
        elif mode == "missing_design":
            missing_design_paths.add((method, path))

    return {
        "exists": True,
        "missing_impl": counts["missing_impl"],
        "missing_design": counts["missing_design"],
        "matched": counts["matched"],
        "missing_impl_paths": missing_impl_paths,
        "missing_design_paths": missing_design_paths,
    }


def _delta(pr_val: int, main_val: int) -> str:
    diff = pr_val - main_val
    if diff > 0:
        return f"+{diff}"
    if diff < 0:
        return f"{diff}"
    return "±0"


def _format_list(items: list[tuple[str, str]], limit: int = TOP_N) -> list[str]:
    """(method, path) のリストを Markdown 箇条書きに整形する。"""
    out: list[str] = []
    for method, path in items[:limit]:
        out.append(f"- `{method} {path}`")
    if len(items) > limit:
        out.append(f"- _… 他 {len(items) - limit} 件（詳細は baseline.md を参照）_")
    return out


def is_strict_mode() -> bool:
    """環境変数 STRICT_DRIFT を読み、案A (fail-on-new-drift) を有効化するかを返す。

    判定:
        - 文字列 ``true`` / ``1`` / ``yes`` / ``on`` （大文字小文字無視）のとき True
        - それ以外（未設定含む）は False
    """
    raw = os.environ.get("STRICT_DRIFT", "")
    return raw.strip().lower() in {"true", "1", "yes", "on"}


def compute_new_drift(
    main_data: dict[str, object], pr_data: dict[str, object]
) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """PR で新規発生した missing_impl / missing_design を返す。

    Returns:
        (new_missing_impl, new_missing_design) のタプル。
        いずれも sorted list of (method, path)。
    """
    main_impl = set(main_data["missing_impl_paths"])  # type: ignore[arg-type]
    pr_impl = set(pr_data["missing_impl_paths"])  # type: ignore[arg-type]
    main_design = set(main_data["missing_design_paths"])  # type: ignore[arg-type]
    pr_design = set(pr_data["missing_design_paths"])  # type: ignore[arg-type]
    return sorted(pr_impl - main_impl), sorted(pr_design - main_design)


def build_comment(
    main_data: dict[str, object],
    pr_data: dict[str, object],
    strict_mode: bool = False,
    strict_fail: bool = False,
) -> str:
    """PR コメント本文を組み立てる。

    Args:
        main_data: main baseline のパース結果
        pr_data: PR baseline のパース結果
        strict_mode: STRICT_DRIFT=true で起動されているか
        strict_fail: strict_mode かつ新規発生 drift により fail させる予定か
    """
    lines: list[str] = []
    lines.append("## 🔍 API Drift Check")
    lines.append("")

    if not main_data["exists"]:
        lines.append(
            "_main の baseline が見つかりません（初回導入時のみ発生）。"
            "差分計算をスキップし、PR の生成結果のみ表示します。_"
        )
        lines.append("")

    lines.append("このPRで検出された API 乖離の差分サマリ:")
    lines.append("")
    lines.append("| 区分 | main baseline | この PR | 差分 |")
    lines.append("|---|---:|---:|---:|")
    lines.append(
        f"| missing_impl（設計あり・実装なし） | "
        f"{main_data['missing_impl']} | {pr_data['missing_impl']} | "
        f"{_delta(int(pr_data['missing_impl']), int(main_data['missing_impl']))} |"
    )
    lines.append(
        f"| missing_design（実装あり・設計なし） | "
        f"{main_data['missing_design']} | {pr_data['missing_design']} | "
        f"{_delta(int(pr_data['missing_design']), int(main_data['missing_design']))} |"
    )
    lines.append(
        f"| matched（一致） | "
        f"{main_data['matched']} | {pr_data['matched']} | "
        f"{_delta(int(pr_data['matched']), int(main_data['matched']))} |"
    )
    lines.append("")

    main_impl = set(main_data["missing_impl_paths"])  # type: ignore[arg-type]
    pr_impl = set(pr_data["missing_impl_paths"])  # type: ignore[arg-type]
    main_design = set(main_data["missing_design_paths"])  # type: ignore[arg-type]
    pr_design = set(pr_data["missing_design_paths"])  # type: ignore[arg-type]

    new_impl = sorted(pr_impl - main_impl)
    fixed_impl = sorted(main_impl - pr_impl)
    new_design = sorted(pr_design - main_design)
    fixed_design = sorted(main_design - pr_design)

    has_any_change = bool(new_impl or fixed_impl or new_design or fixed_design)

    lines.append("### 新規発生した drift（この PR で増えた分）")
    lines.append("")
    if not new_impl and not new_design:
        lines.append("_該当なし。_")
    else:
        if new_impl:
            lines.append(f"#### missing_impl ({len(new_impl)} 件)")
            lines.append("")
            lines.extend(_format_list(new_impl))
            lines.append("")
        if new_design:
            lines.append(f"#### missing_design ({len(new_design)} 件)")
            lines.append("")
            lines.extend(_format_list(new_design))
            lines.append("")
    lines.append("")

    lines.append("### 解消された drift（この PR で減った分）")
    lines.append("")
    if not fixed_impl and not fixed_design:
        lines.append("_該当なし。_")
    else:
        if fixed_impl:
            lines.append(f"#### missing_impl ({len(fixed_impl)} 件)")
            lines.append("")
            lines.extend(_format_list(fixed_impl))
            lines.append("")
        if fixed_design:
            lines.append(f"#### missing_design ({len(fixed_design)} 件)")
            lines.append("")
            lines.extend(_format_list(fixed_design))
            lines.append("")
    lines.append("")

    if not has_any_change:
        lines.append("_このPRでは API 乖離リストに増減はありません（件数差分が出ている場合は、"
                     "順序入れ替えや表記揺れに起因する可能性があります）。_")
        lines.append("")

    # Strict mode のステータス表示
    if strict_mode:
        if strict_fail:
            new_total = len(new_impl) + len(new_design)
            lines.append(
                f"### ❌ Strict mode: 新規発生 drift が {new_total} 件あるためこの PR は fail します"
            )
            lines.append("")
            lines.append(
                "対応方法:"
            )
            lines.append("")
            lines.append(
                "1. 新規発生した drift を解消する（設計書または実装を修正）"
            )
            lines.append(
                "2. 既知のスキャナ偽陽性であれば `scan_api_drift.py` の改修 (v6+) を検討する"
            )
            lines.append(
                "3. 正当な除外であれば `docs/internal/api_drift_exclusions.yml` に追加する"
            )
            lines.append("")
            lines.append(
                "_既存の baseline drift は無視されており、この PR で **新たに増えた** 分だけが対象です。_"
            )
        else:
            lines.append(
                "_Strict mode 有効。この PR では新規発生 drift がないため CI は pass します。_"
            )
        lines.append("")
        lines.append(
            "_詳細は `docs/internal/api_drift_baseline.md` および "
            "`docs/internal/api_drift_ci_integration.md` を参照。_"
        )
    else:
        lines.append(
            "_このチェックは警告のみで PR をブロックしません（`STRICT_DRIFT=false`）。"
            "詳細は `docs/internal/api_drift_baseline.md` を参照。_"
        )
    lines.append("")
    # GitHub Actions 側で `actions/github-script` がコメント update 判定に使うマーカー
    lines.append("<!-- api-drift-check-marker -->")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="API drift CI 差分サマリ生成（main vs PR）"
    )
    parser.add_argument("--main", required=True, type=Path, help="main ブランチの baseline.md")
    parser.add_argument("--pr", required=True, type=Path, help="PR ブランチで再生成した baseline.md")
    parser.add_argument(
        "--output", required=True, type=Path, help="PR コメント本文 (Markdown) 書き出し先"
    )
    args = parser.parse_args()

    main_data = parse_baseline(args.main)
    pr_data = parse_baseline(args.pr)
    strict_mode = is_strict_mode()

    if not pr_data["exists"]:
        print(f"[ERROR] PR baseline not found: {args.pr}", file=sys.stderr)
        # PR 生成失敗は scanner 側の問題なので、ここで PR を fail させても直せない。
        # strict_mode でも exit 0 とし、コメントで通知のみ。
        body = (
            "## 🔍 API Drift Check\n\n"
            "_PR baseline の生成に失敗しました。CI ログを確認してください。_\n\n"
            "<!-- api-drift-check-marker -->\n"
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(body, encoding="utf-8")
        return 0

    # main baseline が無い（初回導入時）は strict_mode でも fail させない
    new_impl, new_design = compute_new_drift(main_data, pr_data)
    has_new_drift = bool(new_impl or new_design)
    strict_fail = (
        strict_mode
        and bool(main_data["exists"])
        and has_new_drift
    )

    body = build_comment(main_data, pr_data, strict_mode=strict_mode, strict_fail=strict_fail)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(body, encoding="utf-8")

    print(
        f"[DONE] main: impl={main_data['missing_impl']} design={main_data['missing_design']} matched={main_data['matched']}"
    )
    print(
        f"[DONE] pr  : impl={pr_data['missing_impl']} design={pr_data['missing_design']} matched={pr_data['matched']}"
    )
    print(
        f"[DONE] strict_mode={strict_mode} new_impl={len(new_impl)} new_design={len(new_design)}"
    )
    print(f"[DONE] output -> {args.output}")

    if strict_fail:
        new_total = len(new_impl) + len(new_design)
        print(
            f"[STRICT-FAIL] Detected {new_total} newly-introduced drift "
            f"(missing_impl={len(new_impl)}, missing_design={len(new_design)}). "
            f"Failing CI (STRICT_DRIFT=true).",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
