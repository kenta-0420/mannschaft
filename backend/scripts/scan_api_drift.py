#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
API 乖離スキャナ（v4）

設計書 (docs/features/F*.md) と実装 (backend/src/main/java/**/controller/*.java)
の API エンドポイント差分を抽出し、`docs/internal/api_drift_baseline.md` に
Markdown レポートを生成する。

実行:
    cd backend && python scripts/scan_api_drift.py
    （または）python backend/scripts/scan_api_drift.py [--no-expand-scope]

注意:
    本スクリプトは「殿様判断資料」を作るための試作 v4。
    Phase A 本実装で置き換える前提。標準ライブラリのみ使用。

# CHANGELOG
# v1 (2026-05-16): 初回試作
# v2 (2026-05-17):
#   - {scope}/{scopeId} 展開 (--expand-scope, デフォルト ON)
#   - 旧 @RequestMapping(value=, method=) 形式対応の強化（multiline・配列method対応）
#   - 末尾スラッシュ正規化（v1 から踏襲、v2 で抽出後にも再正規化）
#   - 設計書インラインコード形式 (`GET /api/v1/...`) の補助対応
#   - ドメイン別サマリ表追加（設計あり・実装なし / 実装あり・設計なし / 一致 を 3 列で）
# v3 (2026-05-17 緊急):
#   v2 偽陽性 6 バグ集合根治により偽陽性率 ~54% → 目標 5% 以下
#   バグ1: path?query を path のみで比較（query 部分を切り捨て）
#   バグ2: 同一 (method, path) 重複の Set 排除（集計時の二重カウント防止）
#   バグ3: クエリ文字列正規化（既定で query 部分は無視）
#   バグ4: 末尾スラッシュ正規化の取りこぼし修正（全抽出パスに rstrip 適用、root '/' のみ例外）
#   バグ5: {scope} 階層展開を第 3 セグメント以外でも対応（汎用パラメータ展開）
#   バグ6: 文字化け Controller の読み飛ばし防止（encoding='utf-8', errors='replace' 既に v2 で対応済、v3 で念押し）
#   除外パターン: docs/internal/api_drift_exclusions.yml をロードして実装側からマッチ分を除外
# v4 (2026-05-17 緊急):
#   V4-1: スコープ階層プレフィックス逆引きマッチ
#       - 実装側 /api/v1/teams/{_}/admin/modules を /api/v1/admin/modules （設計側）と同一視
#       - 誤合体回避のため SCOPE_CORE_PATTERNS ホワイトリスト方式
#       - マッチした (method, impl_path, design_path) は Set で記録し二重カウント防止
#   V4-5: 🔵 将来機能タグ認識
#       - Markdown テーブル行の状態列 🔵/🟢/🟡/❌ を抽出
#       - 🔵 のエンドポイントは future_features に分類してメイン集計から除外
#       - 状態列無しテーブル行（後方互換）は status=None で従来通り集計
#       - レポート末尾に「🔵 将来機能（N 件）」セクションを追加
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from collections import defaultdict, namedtuple
from datetime import date
from pathlib import Path

HTTP_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")

# {scope}/{scopeId} を展開する対象スコープ
SCOPE_EXPANSIONS = ("teams", "organizations", "villages", "users")

# 「汎用スコープ」と見なすパスパラメータ名
GENERIC_SCOPE_NAMES = {"scope", "scopeType", "type", "scopetype"}
GENERIC_SCOPE_ID_NAMES = {"scopeId", "id", "scopeid"}

# V4-1: スコープ階層プレフィックス（実装側のパスがこれで始まる場合、
# 除去して「コアパス」を取り出す対象）
SCOPE_PREFIXES_FOR_REVERSE = (
    "/api/v1/teams/{_}",
    "/api/v1/organizations/{_}",
    "/api/v1/villages/{_}",
    "/api/v1/users/{_}",
)

# V4-1: コアパスとしてスコープ逆引きを許可するパターン（誤合体回避のホワイトリスト）
# `/admin/`, `/dashboard/`, `/modules/`, `/visibility/`, `/settings/` 系のみ。
# これ以外（例: /posts, /surveys 等のリソース系）は scope context の有無で
# 意味が変わる可能性が高いため、逆引きマッチを行わない。
SCOPE_CORE_PATTERNS = (
    "/api/v1/admin/**",
    "/api/v1/dashboard/**",
    "/api/v1/modules/**",
    "/api/v1/visibility/**",
    "/api/v1/settings/**",
)


# ---------------------------------------------------------------------------
# データ構造
# ---------------------------------------------------------------------------
DesignEndpoint = namedtuple(
    "DesignEndpoint", ["method", "path", "source_file", "line_number", "status"]
)
ImplEndpoint = namedtuple(
    "ImplEndpoint",
    ["method", "path", "source_file", "line_number", "class_name", "method_name"],
)


# ---------------------------------------------------------------------------
# パス正規化
# ---------------------------------------------------------------------------
_PATH_PARAM_RE = re.compile(r"\{[^/}]+\}")
# 正規化前にスコープ展開判定するため、パラメータ名を保ったままの抽出用 RE
_NAMED_PATH_PARAM_RE = re.compile(r"\{([^/}]+)\}")


def _strip_query(path: str) -> str:
    """v3 バグ1根治: クエリ文字列 (?...) を切り捨てる。

    設計書側で `GET /api/v1/me/foo?scopeType=TEAM` のように query 込みで
    書かれているケースが多数あり、実装側 `/api/v1/me/foo` と一致しなくなる
    ため、比較時は path のみを使う。
    """
    if path is None:
        return ""
    q = path.find("?")
    if q >= 0:
        return path[:q]
    return path


def _strip_trailing_slash(path: str) -> str:
    """v3 バグ4根治: 末尾スラッシュ除去（"/" のみは残す）。

    全抽出パスに必ず適用するため、後続の normalize_path から呼ぶだけでなく
    expand 直後にも再適用する。
    """
    if len(path) > 1 and path.endswith("/"):
        return path[:-1]
    return path


def normalize_path(path: str) -> str:
    """パスパラメータを {_} に統一し、query 切り捨て・末尾スラッシュ除去を行う。

    v3: バグ1（query 除去）+ バグ4（末尾スラッシュ）を一括で適用。
    """
    if path is None:
        return ""
    path = path.strip()
    path = _strip_query(path)        # v3 バグ1
    path = _strip_trailing_slash(path)  # v3 バグ4
    path = _PATH_PARAM_RE.sub("{_}", path)
    path = _strip_trailing_slash(path)  # 正規化後の取りこぼし防止
    return path


def expand_scope_paths(path: str) -> list[str]:
    """`/api/v1/{scope}/{scopeId}/...` パターンを実スコープで展開する。

    v3 バグ5根治: 第 3 セグメント以外にも汎用スコープが現れたケースを
    可能な範囲で展開する。基本対象は従来通り第 3+4 セグメントペア。

    対象判定（第 3+4 ペア）:
        - 第 3 セグメントが汎用スコープ名 ({scope}, {scopeType}, {type}) のパスパラメータ
        - 第 4 セグメントが汎用 ID 名 ({scopeId}, {id}) のパスパラメータ
    展開後はパスパラメータを {_} に正規化する。
    対象でなければ [normalize_path(path)] を 1 要素で返す。
    """
    if path is None or not path:
        return [normalize_path(path)]

    raw = _strip_trailing_slash(_strip_query(path.strip()))  # v3 バグ1+4
    parts = raw.split("/")
    # parts = ["", "api", "v1", "{scope}", "{scopeId}", ...]
    if len(parts) >= 5 and parts[1] == "api" and parts[2].startswith("v"):
        seg3 = parts[3]
        seg4 = parts[4]
        m3 = _NAMED_PATH_PARAM_RE.fullmatch(seg3)
        m4 = _NAMED_PATH_PARAM_RE.fullmatch(seg4)
        if (
            m3
            and m4
            and m3.group(1) in GENERIC_SCOPE_NAMES
            and m4.group(1) in GENERIC_SCOPE_ID_NAMES
        ):
            results: list[str] = []
            tail = "/".join(parts[5:])
            tail_segment = ("/" + tail) if tail else ""
            for scope in SCOPE_EXPANSIONS:
                expanded = f"/{parts[1]}/{parts[2]}/{scope}/{{_}}" + tail_segment
                results.append(normalize_path(expanded))
            return results

    return [normalize_path(raw)]


def domain_of(path: str) -> str:
    """`/api/v1/teams/...` から `teams` を取り出す。第 3 セグメント基準。"""
    parts = [p for p in path.split("/") if p]
    if len(parts) >= 3 and parts[0] == "api":
        return parts[2]
    if len(parts) >= 1:
        return parts[0]
    return "(root)"


# ---------------------------------------------------------------------------
# V4-1: スコープ階層プレフィックス逆引き
# ---------------------------------------------------------------------------
def extract_core_path(path: str) -> str | None:
    """実装側パスから scope prefix を除去してコアパスを返す。

    例: `/api/v1/teams/{_}/admin/modules` → `/api/v1/admin/modules`

    対象外（scope prefix で始まらない / 末尾が prefix と完全一致）の場合は None。
    """
    if not path:
        return None
    for prefix in SCOPE_PREFIXES_FOR_REVERSE:
        if path == prefix:
            # スコープエンティティ自身（例: GET /api/v1/teams/{id}）はコアパスなし
            return None
        if path.startswith(prefix + "/"):
            tail = path[len(prefix):]  # "/admin/modules"
            return "/api/v1" + tail
    return None


def _core_pattern_matches(core_path: str) -> bool:
    """コアパスが SCOPE_CORE_PATTERNS のいずれかにマッチするか。

    誤合体回避のホワイトリスト。`/admin/`, `/dashboard/`, `/modules/`,
    `/visibility/`, `/settings/` 系のみマッチ許可。
    """
    for pattern in SCOPE_CORE_PATTERNS:
        regex = _glob_to_regex(pattern)
        if regex.match(core_path):
            return True
    return False


# ---------------------------------------------------------------------------
# 除外パターンのロード（v3 新規）
# ---------------------------------------------------------------------------
def _parse_exclusion_yaml(text: str) -> list[str]:
    """簡易 YAML パーサ (標準ライブラリ縛りのため自前)。

    docs/internal/api_drift_exclusions.yml の `exclude_patterns:` 配下の
    `- pattern: "..."` 行のみ抽出する。`do_not_exclude_examples:` 配下は
    無視する。
    """
    patterns: list[str] = []
    in_exclude = False
    in_donot = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped == "exclude_patterns:":
            in_exclude = True
            in_donot = False
            continue
        if stripped == "do_not_exclude_examples:":
            in_exclude = False
            in_donot = True
            continue
        if not in_exclude:
            continue
        m = re.match(r'-\s*pattern:\s*"([^"]+)"', stripped)
        if m:
            patterns.append(m.group(1))
            continue
        m = re.match(r"-\s*pattern:\s*'([^']+)'", stripped)
        if m:
            patterns.append(m.group(1))
            continue
    return patterns


def load_exclusions(exclusions_file: Path) -> list[str]:
    """除外パターン YAML を読み込む。ファイルが無ければ空リスト。"""
    if not exclusions_file.is_file():
        return []
    try:
        text = exclusions_file.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"[WARN] cannot read exclusions: {exc}", file=sys.stderr)
        return []
    return _parse_exclusion_yaml(text)


def _glob_to_regex(pattern: str) -> re.Pattern[str]:
    """glob 風 (** = 任意階層 / * = 任意 1 セグメント) を正規表現に変換する。

    `fnmatch.translate` は `**` を `*` と同じ扱い (任意文字) にしてしまうので、
    自前で `**` → `.*`, `*` → `[^/]*` に展開する。
    """
    # 先に ** を sentinel に置換 → * を [^/]* → sentinel を .*
    sentinel = "\x00DOUBLESTAR\x00"
    p = pattern.replace("**", sentinel)
    p = re.escape(p)
    # re.escape 後は sentinel が \x00DOUBLESTAR\x00 のままだが、エスケープされた
    # `\*` を [^/]* に戻す（\\\* がエスケープ表現）。`\\\*` → `[^/]*`
    p = p.replace(r"\*", "[^/]*")
    # sentinel を .* に戻す
    p = p.replace(re.escape(sentinel), ".*")
    return re.compile("^" + p + "$")


def is_excluded(path: str, compiled_patterns: list[re.Pattern[str]]) -> bool:
    """path がいずれかの除外パターンにマッチするか。"""
    for cp in compiled_patterns:
        if cp.match(path):
            return True
    return False


# ---------------------------------------------------------------------------
# 設計書スキャン
# ---------------------------------------------------------------------------
# V4-5: 状態列付き Markdown テーブル行: `| 🔵 | GET | /api/v1/foo | ... |`
# 状態列はオプション。先頭の `|` の直後に絵文字（🟢/🔵/🟡/❌）が来た場合、状態として抽出。
_DESIGN_TABLE_WITH_STATUS_RE = re.compile(
    r"^\s*\|\s*(🟢|🔵|🟡|❌)\s*\|"
    r"\s*`?(GET|POST|PUT|PATCH|DELETE)`?\s*\|"
    r"\s*`?(/api/v\d+/[^\s`|]+)`?\s*\|",
    re.IGNORECASE,
)
# 旧来形式（状態列なし）: `| GET | /api/v1/... | ... |`
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
# インラインコード: `\`GET /api/v1/...\``  または  `\`POST /api/v1/foo\``
# 散文中・箇条書き中など、テーブル/見出しに該当しない行を補助的に拾う
_DESIGN_INLINE_RE = re.compile(
    r"`(GET|POST|PUT|PATCH|DELETE)\s+(/api/v\d+/[^\s`]+)`",
    re.IGNORECASE,
)


def scan_design_docs(docs_dir: Path) -> list[DesignEndpoint]:
    """`docs/features/F*.md` を全て走査し設計記載エンドポイントを集める。

    v4: 状態列付きテーブル行 (`| 🔵 | GET | ... |`) を優先抽出し、
    状態列が無い場合は従来通り status=None で抽出する（後方互換）。
    """
    results: list[DesignEndpoint] = []
    if not docs_dir.is_dir():
        print(f"[WARN] features dir not found: {docs_dir}", file=sys.stderr)
        return results

    for md in sorted(docs_dir.glob("F*.md")):
        try:
            # v3 バグ6念押し: errors='replace' で文字化けでも読み飛ばさない
            text = md.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"[WARN] cannot read {md}: {exc}", file=sys.stderr)
            continue

        for i, line in enumerate(text.splitlines(), start=1):
            matched_in_line = False

            # V4-5: 状態列付きテーブル行を先に試行
            m_status = _DESIGN_TABLE_WITH_STATUS_RE.match(line)
            if m_status:
                status = m_status.group(1)
                method = m_status.group(2).upper()
                path = m_status.group(3).rstrip(".,;`")
                results.append(
                    DesignEndpoint(
                        method=method,
                        path=normalize_path(path),
                        source_file=str(md.as_posix()),
                        line_number=i,
                        status=status,
                    )
                )
                matched_in_line = True

            if not matched_in_line:
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
                            status=None,
                        )
                    )
                    matched_in_line = True

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
                        status=None,
                    )
                )
                matched_in_line = True

            # インラインコード形式は、テーブル/見出しでヒットしていない行のみ補助対応
            if not matched_in_line:
                for mi in _DESIGN_INLINE_RE.finditer(line):
                    method = mi.group(1).upper()
                    path = mi.group(2).rstrip(".,;`)")
                    results.append(
                        DesignEndpoint(
                            method=method,
                            path=normalize_path(path),
                            source_file=str(md.as_posix()),
                            line_number=i,
                            status=None,
                        )
                    )
    return results


# ---------------------------------------------------------------------------
# 実装スキャン
# ---------------------------------------------------------------------------
# クラスレベル: @RequestMapping("/api/v1/...")
_CLASS_REQ_MAPPING_RE = re.compile(
    r'@RequestMapping\s*\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]+)"'
)
# メソッドレベル: @GetMapping("..."), @PostMapping("..."), 引数なしの @GetMapping も対応
_METHOD_MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Patch|Delete)Mapping\s*'
    r'(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"[^)]*\)|\(\s*\)|(?=\s))',
)
# 旧形式: @RequestMapping(value="...", method = RequestMethod.GET) — multiline 許容
# method が単一の場合と配列 ({RequestMethod.GET, RequestMethod.POST}) の場合の両対応
_OLD_REQ_MAPPING_RE = re.compile(
    r'@RequestMapping\s*\(([^)]*method\s*=\s*(?:RequestMethod\.[A-Z]+|\{[^}]*\})[^)]*)\)',
    re.DOTALL,
)
_OLD_VALUE_RE = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"')
_OLD_METHOD_RE = re.compile(r'RequestMethod\.(GET|POST|PUT|PATCH|DELETE)')

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


def _emit_impl(
    verb: str,
    raw_path: str,
    java_file: Path,
    line_no: int,
    class_name: str,
    method_name: str,
    expand_scope: bool,
) -> list[ImplEndpoint]:
    """単一の検出結果から、(必要なら) スコープ展開して複数の ImplEndpoint を返す。"""
    paths = expand_scope_paths(raw_path) if expand_scope else [normalize_path(raw_path)]
    return [
        ImplEndpoint(
            method=verb,
            path=p,
            source_file=str(java_file.as_posix()),
            line_number=line_no,
            class_name=class_name,
            method_name=method_name,
        )
        for p in paths
    ]


def scan_controller(java_file: Path, expand_scope: bool = True) -> list[ImplEndpoint]:
    """単一 Controller ファイルからエンドポイントを抽出する。"""
    try:
        # v3 バグ6: 文字化けで全体を読み飛ばさないよう errors='replace'
        text = java_file.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"[WARN] cannot read {java_file}: {exc}", file=sys.stderr)
        return []

    lines = text.splitlines()

    # クラスレベル @RequestMapping を探す（最初のクラス宣言の直前まで）
    class_name = "?"
    class_line_idx = None
    for i, line in enumerate(lines):
        cm = re.search(
            r'\b(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s+(\w+)', line
        )
        if cm and "@" not in line:
            class_name = cm.group(1)
            class_line_idx = i
            break

    head = "\n".join(
        lines[: class_line_idx if class_line_idx is not None else len(lines)]
    )
    class_path = ""
    cm = _CLASS_REQ_MAPPING_RE.search(head)
    if cm:
        class_path = cm.group(1)

    results: list[ImplEndpoint] = []

    # 新形式: 行単位スキャン
    for i, line in enumerate(lines, start=1):
        for mm in _METHOD_MAPPING_RE.finditer(line):
            verb = mm.group(1).upper()
            sub_path = mm.group(2) if mm.group(2) is not None else ""
            full = _join(class_path, sub_path)
            method_name = _find_method_name(lines, i - 1)
            results.extend(
                _emit_impl(verb, full, java_file, i, class_name, method_name, expand_scope)
            )

    # 旧形式: @RequestMapping(value="...", method=RequestMethod.X) — multiline 対応で text 全体
    for om in _OLD_REQ_MAPPING_RE.finditer(text):
        body = om.group(0)
        val_m = _OLD_VALUE_RE.search(body)
        # 配列指定 {RequestMethod.GET, RequestMethod.POST} は複数発行
        verbs = [m.group(1).upper() for m in _OLD_METHOD_RE.finditer(body)]
        if not val_m or not verbs:
            continue
        sub_path = val_m.group(1)
        line_no = text[: om.start()].count("\n") + 1
        full = _join(class_path, sub_path)
        method_name = _find_method_name(lines, line_no - 1)
        for verb in verbs:
            results.extend(
                _emit_impl(
                    verb, full, java_file, line_no, class_name, method_name, expand_scope
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


def scan_implementations(
    controllers_root: Path, expand_scope: bool = True
) -> list[ImplEndpoint]:
    """`backend/src/main/java/**/controller/*Controller.java` を全件走査。"""
    results: list[ImplEndpoint] = []
    if not controllers_root.is_dir():
        print(f"[WARN] backend src root not found: {controllers_root}", file=sys.stderr)
        return results
    for java in sorted(controllers_root.rglob("*Controller.java")):
        results.extend(scan_controller(java, expand_scope=expand_scope))
    return results


# ---------------------------------------------------------------------------
# 突合・レポート生成
# ---------------------------------------------------------------------------
def make_report(
    designs: list[DesignEndpoint],
    impls: list[ImplEndpoint],
    out_file: Path,
    repo_root: Path,
    expand_scope: bool,
    exclusion_patterns: list[str] | None = None,
) -> tuple[int, int, int]:
    """突合してレポートを書き出す。戻り値: (missing_impl, missing_design, matched)。

    v3 バグ2根治: 集計時に Set で重複排除（design_keys/impl_keys は dict だが
    キーが既に集約されているため、`set(design_keys)` で自然に重複排除される）。

    v4:
        V4-1 スコープ階層逆引きマッチで「設計あり・実装なし」+「実装あり・設計なし」
        の組を「準一致」として一致側に繰り入れる。
        V4-5 🔵 タグ付きエンドポイントは future_features に分類して集計対象外。
    """
    # 除外パターンを compile
    compiled = []
    if exclusion_patterns:
        compiled = [_glob_to_regex(p) for p in exclusion_patterns]

    # V4-5: 🔵 将来機能を分離
    future_designs: list[DesignEndpoint] = []
    main_designs: list[DesignEndpoint] = []
    for d in designs:
        if d.status == "🔵":
            future_designs.append(d)
        else:
            main_designs.append(d)

    design_keys: dict[tuple[str, str], list[DesignEndpoint]] = defaultdict(list)
    for d in main_designs:
        design_keys[(d.method, d.path)].append(d)

    # 🔵 として登録されたキーは future_keys に集約
    future_keys: dict[tuple[str, str], list[DesignEndpoint]] = defaultdict(list)
    for d in future_designs:
        future_keys[(d.method, d.path)].append(d)

    # 🔵 と通常 (🟢 等) の両方に同じキーが登場した場合、通常側を優先（メインに残す）
    # → 何もしなくても OK。design_keys に既に含まれているならそのまま比較対象。

    impl_keys: dict[tuple[str, str], list[ImplEndpoint]] = defaultdict(list)
    excluded_count = 0
    for i in impls:
        if compiled and is_excluded(i.path, compiled):
            excluded_count += 1
            continue
        impl_keys[(i.method, i.path)].append(i)

    # 設計側も除外パターンを適用
    design_excluded = 0
    if compiled:
        filtered_design: dict[tuple[str, str], list[DesignEndpoint]] = defaultdict(list)
        for key, ds in design_keys.items():
            if is_excluded(key[1], compiled):
                design_excluded += 1
                continue
            filtered_design[key] = ds
        design_keys = filtered_design
        filtered_future: dict[tuple[str, str], list[DesignEndpoint]] = defaultdict(list)
        for key, ds in future_keys.items():
            if is_excluded(key[1], compiled):
                continue
            filtered_future[key] = ds
        future_keys = filtered_future

    # V4-5: 🔵 として実装側に存在するエンドポイントは「将来機能だが既に実装あり」
    # として、実装側の only_impl からは除外する（一致でも・only_impl でもない別カテゴリ）。
    # ただし通常 (🟢 等) としても登録されていれば、そちらの一致判定が優先される。
    future_already_impl: list[tuple[str, str]] = []
    for fkey in list(future_keys.keys()):
        if fkey in impl_keys and fkey not in design_keys:
            future_already_impl.append(fkey)

    # v3 バグ2: set 化で重複排除
    only_design = set(design_keys.keys()) - set(impl_keys.keys())
    only_impl = set(impl_keys.keys()) - set(design_keys.keys())
    matched = set(design_keys.keys()) & set(impl_keys.keys())

    # V4-5: 🔵 として実装済みのキーは only_impl からも除外
    only_impl -= set(future_already_impl)

    # V4-1: スコープ階層逆引きマッチ
    # 実装側 only_impl のうち scope prefix で始まり、core path が only_design に
    # 存在し、かつ core path が SCOPE_CORE_PATTERNS にマッチするものは「準一致」
    # としてメイン集計から除外する。
    scope_reverse_matches: set[tuple[str, str, str]] = set()  # (method, impl_path, design_path)
    only_impl_to_remove: set[tuple[str, str]] = set()
    only_design_to_remove: set[tuple[str, str]] = set()

    for (method, impl_path) in list(only_impl):
        core = extract_core_path(impl_path)
        if core is None:
            continue
        if (method, core) not in only_design:
            continue
        # 同じスコープ prefix で設計側にも書かれていれば、そちらを優先（誤合体防止）
        # → 既に matched 側に入っているなら only_impl/only_design には来ないので OK
        if not _core_pattern_matches(core):
            continue
        scope_reverse_matches.add((method, impl_path, core))
        only_impl_to_remove.add((method, impl_path))
        only_design_to_remove.add((method, core))

    only_impl -= only_impl_to_remove
    only_design -= only_design_to_remove

    only_design_sorted = sorted(only_design)
    only_impl_sorted = sorted(only_impl)
    matched_sorted = sorted(matched)

    def rel(p: str) -> str:
        try:
            return str(Path(p).resolve().relative_to(repo_root.resolve()).as_posix())
        except (ValueError, OSError):
            return p

    today = date.today().isoformat()
    lines: list[str] = []
    lines.append(f"# API 乖離ベースライン報告書（{today} 時点・v4 スキャナ）")
    lines.append("")
    lines.append("> 本報告書は `backend/scripts/scan_api_drift.py` (v4) により自動生成された。")
    lines.append("> 設計書 `docs/features/F*.md` のテーブル/見出し/インラインコード記載と、")
    lines.append("> 実装 `backend/src/main/java/**/controller/*Controller.java` の")
    lines.append("> Spring MVC アノテーション（新形式 + 旧 @RequestMapping(method=) 形式）を突合した結果である。")
    lines.append("")
    lines.append("## 改訂履歴")
    lines.append("")
    lines.append("- v1 (2026-05-16): 初回ベースライン")
    lines.append(
        "- v2 (2026-05-17): {scope}/{scopeId} 展開・旧 RequestMapping 強化・末尾スラッシュ吸収・インラインコード補助対応・ドメイン別サマリ表追加"
    )
    lines.append(
        "- v3 (2026-05-17): 6 バグ集合根治（query 切捨・重複排除・末尾スラッシュ取りこぼし・スコープ展開拡張・文字化け read 念押し・除外パターン適用）"
    )
    lines.append(
        f"- v4 ({today}): V4-1 スコープ階層プレフィックス逆引きマッチ + V4-5 🔵 将来機能タグ認識"
    )
    lines.append("")
    lines.append("## サマリ")
    lines.append("")
    lines.append(
        f"- 設計あり・実装なし: **{len(only_design_sorted)} 件**（v3: 1,214 件 / v2: 1,256 件 / v1: 1,187 件）"
    )
    lines.append(
        f"- 実装あり・設計なし: **{len(only_impl_sorted)} 件**（v3: 1,106 件 / v2: 1,147 件 / v1: 931 件）"
    )
    lines.append(
        f"- 一致: **{len(matched_sorted)} 件**（v3: 1,341 件 / v2: 1,322 件 / v1: 1,310 件）"
    )
    lines.append(
        f"- V4-1 スコープ逆引き準一致: **{len(scope_reverse_matches)} 件**（一致側に繰入）"
    )
    lines.append(
        f"- V4-5 🔵 将来機能: **{len(future_keys)} 件**（メイン集計外）／うち実装済: {len(future_already_impl)} 件"
    )
    lines.append(f"- 設計記載 ユニーク (method, path) 総数（main）: {len(design_keys)}")
    lines.append(f"- 実装 ユニーク (method, path) 総数: {len(impl_keys)}")
    lines.append(
        f"- 除外（実装側）: {excluded_count} 件 / 除外（設計側）: {design_excluded} 件 / パターン数: {len(exclusion_patterns or [])}"
    )
    lines.append(f"- スコープ展開: {'ON' if expand_scope else 'OFF'}")
    lines.append("")
    lines.append("---")
    lines.append("")

    # ドメイン別サマリ表
    lines.append("## ドメイン別サマリ表")
    lines.append("")
    all_domains: set[str] = set()
    only_design_by_domain: dict[str, int] = defaultdict(int)
    only_impl_by_domain: dict[str, int] = defaultdict(int)
    matched_by_domain: dict[str, int] = defaultdict(int)
    for key in only_design_sorted:
        d = domain_of(key[1])
        only_design_by_domain[d] += 1
        all_domains.add(d)
    for key in only_impl_sorted:
        d = domain_of(key[1])
        only_impl_by_domain[d] += 1
        all_domains.add(d)
    for key in matched_sorted:
        d = domain_of(key[1])
        matched_by_domain[d] += 1
        all_domains.add(d)

    def _drift_total(d: str) -> int:
        return only_design_by_domain[d] + only_impl_by_domain[d]

    sorted_domains = sorted(all_domains, key=lambda d: (-_drift_total(d), d))

    lines.append("| ドメイン | 設計あり・実装なし | 実装あり・設計なし | 一致 | 合計乖離 |")
    lines.append("|---|---:|---:|---:|---:|")
    for d in sorted_domains:
        od = only_design_by_domain[d]
        oi = only_impl_by_domain[d]
        mt = matched_by_domain[d]
        lines.append(f"| /api/v1/{d}/* | {od} | {oi} | {mt} | {od + oi} |")
    lines.append(
        f"| **合計** | **{len(only_design_sorted)}** | **{len(only_impl_sorted)}** | **{len(matched_sorted)}** | **{len(only_design_sorted) + len(only_impl_sorted)}** |"
    )
    lines.append("")
    lines.append("---")
    lines.append("")

    # 1. 設計あり・実装なし
    lines.append("## 1. 🔴 設計あり・実装なし（Phase 1 漏れ系）")
    lines.append("")
    if not only_design_sorted:
        lines.append("_該当なし。_")
    else:
        by_domain: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for key in only_design_sorted:
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
    if not only_impl_sorted:
        lines.append("_該当なし。_")
    else:
        by_domain2: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for key in only_impl_sorted:
            by_domain2[domain_of(key[1])].append(key)
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

    # 3. 一致（件数のみ）
    lines.append("## 3. ✅ 一致（件数のみ）")
    lines.append("")
    lines.append(f"一致したエンドポイント: **{len(matched_sorted)} 件**（詳細リストは省略）")
    lines.append("")
    lines.append("---")
    lines.append("")

    # V4-1: スコープ逆引き準一致セクション
    lines.append("## 4. 🟦 スコープ階層プレフィックス逆引き準一致（V4-1）")
    lines.append("")
    lines.append(
        "> 実装側が `/api/v1/teams/{_}/...` 等のスコープ context 付きで定義されているが、"
        "設計書側ではコアパス（scope 抜き）で記載されているケース。"
        "意味的に同一とみなし、メイン集計の「設計あり・実装なし」「実装あり・設計なし」"
        "両方から除外している。"
    )
    lines.append("")
    if not scope_reverse_matches:
        lines.append("_該当なし。_")
    else:
        lines.append(f"準一致件数: **{len(scope_reverse_matches)} 件**")
        lines.append("")
        lines.append("| メソッド | 実装パス | 設計コアパス |")
        lines.append("|---|---|---|")
        for method, impl_path, design_path in sorted(scope_reverse_matches):
            lines.append(f"| {method} | `{impl_path}` | `{design_path}` |")
        lines.append("")
    lines.append("---")
    lines.append("")

    # V4-5: 🔵 将来機能セクション
    lines.append("## 5. 🔵 将来機能（実装ステータス明示）")
    lines.append("")
    lines.append(
        "> 設計書テーブル行で状態列が `🔵`（Phase X 未着工等）と明示されているエンドポイント。"
        "意図的に未実装のため、メインの「設計あり・実装なし」には含めない。"
    )
    lines.append("")
    if not future_keys:
        lines.append("_該当なし。_")
    else:
        lines.append(f"将来機能件数: **{len(future_keys)} 件**")
        lines.append("")
        lines.append("| 状態 | メソッド | パス | 設計書 | 行 | 実装済 |")
        lines.append("|---|---|---|---|---:|:---:|")
        for key in sorted(future_keys.keys()):
            method, path = key
            already = "✓" if key in impl_keys else ""
            for d in future_keys[key]:
                lines.append(
                    f"| 🔵 | {method} | `{path}` | `{rel(d.source_file)}` | {d.line_number} | {already} |"
                )
        lines.append("")

    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(only_design_sorted), len(only_impl_sorted), len(matched_sorted)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="API 乖離スキャナ v4（設計書 vs Controller 実装）"
    )
    parser.add_argument(
        "--no-expand-scope",
        dest="expand_scope",
        action="store_false",
        help="{scope}/{scopeId} 展開を無効化する（既定: 有効）",
    )
    parser.add_argument(
        "--no-exclusions",
        dest="apply_exclusions",
        action="store_false",
        help="除外パターン（api_drift_exclusions.yml）の適用を無効化する",
    )
    parser.set_defaults(expand_scope=True, apply_exclusions=True)
    args = parser.parse_args()

    script_path = Path(__file__).resolve()
    repo_root = script_path.parent.parent.parent  # scripts -> backend -> repo
    docs_features = repo_root / "docs" / "features"
    controllers_root = repo_root / "backend" / "src" / "main" / "java"
    out_file = repo_root / "docs" / "internal" / "api_drift_baseline.md"
    exclusions_file = repo_root / "docs" / "internal" / "api_drift_exclusions.yml"

    print(f"[INFO] repo root        : {repo_root}")
    print(f"[INFO] design docs dir  : {docs_features}")
    print(f"[INFO] controllers root : {controllers_root}")
    print(f"[INFO] output           : {out_file}")
    print(f"[INFO] exclusions       : {exclusions_file}")
    print(f"[INFO] expand_scope     : {args.expand_scope}")
    print(f"[INFO] apply_exclusions : {args.apply_exclusions}")

    exclusion_patterns: list[str] = []
    if args.apply_exclusions:
        exclusion_patterns = load_exclusions(exclusions_file)
        print(f"[INFO] exclusion patterns loaded: {len(exclusion_patterns)}")

    designs = scan_design_docs(docs_features)
    impls = scan_implementations(controllers_root, expand_scope=args.expand_scope)

    print(f"[INFO] design endpoints (raw): {len(designs)}")
    print(f"[INFO] impl  endpoints (raw): {len(impls)}")

    missing_impl, missing_design, matched = make_report(
        designs,
        impls,
        out_file,
        repo_root,
        expand_scope=args.expand_scope,
        exclusion_patterns=exclusion_patterns,
    )
    print(
        f"[DONE] missing_impl={missing_impl} "
        f"missing_design={missing_design} matched={matched}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
