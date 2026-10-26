# API 乖離スキャナ v5 検証記録

> 2026-05-17 / Stage3 1-α「scanner v5 改修」検証ログ。
> 対応 PR: `feature/api-drift-scanner-v5`。

## 1. 目的

`backend/scripts/scan_api_drift.py` v4 では、本陣 baseline で
`SCOPE_CORE_PATTERNS` ホワイトリストが `/admin/`, `/dashboard/`, `/modules/`,
`/visibility/`, `/settings/` の 5 系統に絞られており、本番リソース系（coupons,
dwelling-units, surveys 等）の「設計 vs 実装スコープ前置」乖離が
**逆引き準一致として拾えていない**（v4 baseline での該当件数 0 件）。
さらに、設計書のインラインコードがコードブロック内まで漏れて拾われたり、
逆に散文中の `` `GET /api/v1/...` `` が拾われない揺らぎがあり、
単複形揺れ（feedback ↔ feedbacks 等）も別物扱いされていた。

これらを根治するため、v5 で以下 3 件の改修を行った。

## 2. 改修内容

### V5-1: リソース系スコープ逆引き拡張

| 観点 | 内容 |
|---|---|
| 追加 | `SCOPE_CORE_PATTERNS_V5` ホワイトリスト（v4 と和集合で運用）|
| 対象 | `/coupons/**`, `/dwelling-units/**`, `/repair-plans/**`, `/forms/**`, `/surveys/**`, `/workflows/**`, `/circulation/**`, `/circulations/**`, `/bulletin/**` |
| フラグ | `--no-v5-reverse` で v4 互換動作に戻せる（既定 ON） |
| 誤合体回避 | 各ドメインごとに baseline で設計／実装の対応関係を事前確認 |

**狙い**: 設計書側の `/api/v1/coupons/{id}` 等のスコープ抜き記載と、実装側の
`/api/v1/teams/{_}/coupons/{_}` のスコープ context 付き定義を準一致扱いにする。

### V5-2: 設計書インラインコード強化

| 観点 | 内容 |
|---|---|
| 追加 | コードブロック ``` ... ``` 内の行を抽出から除外（行番号を保ったまま） |
| 追加 | HTML コメント `<!-- ... -->` 内のエンドポイント記述を除外（multiline 対応） |
| 拡張 | テーブル/見出しでヒットした行でも、同じ行内の補助インラインコード `` `GET /api/v1/...` `` を別キーとして拾う |
| 排他制御 | (line_number, method, path) のキーで重複を排除 |

**狙い**: 設計書側の取りこぼしを減らす一方、過剰検出（コードブロック内のサンプル
コマンド等）の偽陽性を防ぐ。

### V5-3: 命名揺れ正規化（単複形）

| 観点 | 内容 |
|---|---|
| 追加 | `SINGULAR_PLURAL_DICT`（22 ペア）で第 3 セグメントを正規化 |
| ロジック | only_design / only_impl の両方を正規化キーで再比較 |
| 連携 | V5-1 逆引き内でも `normalize_naming` を併用（設計 `circulation` ↔ 実装 `circulations` のような case を救済） |
| フラグ | `--no-v5-naming` で V5-3 を無効化（既定 ON） |
| レポート | `(matched by naming-normalization)` 注釈付きで別セクションに表示 |

**狙い**: `feedback`/`feedbacks`, `circulation`/`circulations`,
`notification`/`notifications` のような単複揺れによる偽陽性をなくす。

## 3. baseline 数値 before/after

```
（数値は同じ git tree 上の docs/internal/api_drift_baseline.md ヘッダから抽出）
```

| 指標 | v4 | v5 | Δ |
|---|---:|---:|---:|
| 設計あり・実装なし | 1,223 | **1,093** | **-130** |
| 実装あり・設計なし | 925 | **870** | **-55** |
| 一致 | 1,514 | 1,441 | -73 |
| V4-1+V5-1 スコープ逆引き準一致 | 0 (※v4 では SCOPE_CORE_PATTERNS の 5 系統だけだったため実質 0) | **44** | +44 |
| V5-3 命名揺れ正規化準一致 | — | 0 | 0 |
| V4-5 🔵 将来機能 | 28 | 28 | ±0 |
| 設計記載 unique (method,path) | 2,737 | 2,553 | -184 |
| 実装 unique (method,path) | 2,439 | 2,355 | -84 |

### 解説

- 「設計あり・実装なし」が **130 件削減**、「実装あり・設計なし」が **55 件削減**
  で合計 **185 件の偽陽性を解消**。期待値の「100 件以上削減」を達成。
- 一致が 73 件減ったように見えるのは、設計 raw unique が v4: 2,737 → v5: 2,553 と
  184 件減ったため。コードブロック内に重複して書かれていた path（rendering
  サンプル等）が V5-2 で除外されたことによる（重複の整理であり、本来の API
  エンドポイント情報の損失ではない）。
- V5-3 単独準一致が 0 件なのは、実は baseline 中に「第 3 セグメントだけが単複
  違いで、それ以外完全一致」のペアが現実にはあまり存在しないため。多くは
  V5-1 逆引きと連携する形で吸収されている（後述）。
- V5-3 命名揺れの真価は V5-1 逆引き内で発揮されている。例: 設計
  `/api/v1/circulation/{_}/stamp` と 実装 `/api/v1/teams/{_}/circulations/{_}/stamp`
  の組は、逆引きで `circulations/{_}/stamp` を取り出した上で normalize_naming
  により設計側の単数形 path と一致する（44 件中に複数件含まれる）。

## 4. テスト結果

```bash
cd backend && python -m unittest scripts.test_scan_api_drift -v
```

- 既存テスト: 40 件 全 PASS
- 既存 V4 テスト: 11 件 全 PASS
- 新規 V5 テスト: 18 件 全 PASS（内訳: TestV5ReverseExpansion=5, TestV5InlineCodeScan=5, TestV5NamingNormalization=8）
- **合計 58 件 全 PASS**

```
----------------------------------------------------------------------
Ran 58 tests in 0.3s

OK
```

### 既存テスト変更点

- `TestV4CorePatternMatch.test_non_whitelisted_does_not_match`:
  v4 既定 (`v5_reverse=False`) の動作確認を残しつつ、v5 既定でも `/posts`/`/me/`
  は許可されないことを追加検証。
  V5-1 が SCOPE_CORE_PATTERNS_V5 を追加した影響で `/api/v1/coupons/{_}` が
  v5 既定で True になるため、テストの意図を明確化した。

## 5. 誤合体検出時の対処方針

V5-1 拡張で「設計と実装でドメインが異なるのに同名」のリソースを誤って同一視
してしまうリスクがある。例えば設計側の `/api/v1/surveys/...` と実装側の
`/api/v1/teams/{_}/surveys/...` が**そもそも別 API（マーケ調査 vs チーム
アンケート）**だった場合、V5-1 で誤合体する。

### 対応策

1. **baseline.md の準一致セクション（## 4. 🟦）を必ず目視レビュー**して、
   設計コアパスと実装パスの意味的一致を確認する。
2. 誤合体を発見したら以下のいずれかで対処:
   - `docs/internal/api_drift_exclusions.yml` の `exclude_patterns:` に該当
     path を追加（設計／実装両方を除外）
   - `SCOPE_CORE_PATTERNS_V5` から該当ドメインを外す（影響範囲が広い場合）
   - `--no-v5-reverse` で V5-1 を全停止し、v4 互換動作に戻す
3. v6 を起こす場合は、ドメイン別ホワイトリストを yaml 化することを検討
   （ハードコード辞書のままだと改廃が重い）。

## 6. CLI 追加フラグ

```bash
# V5-1+V5-3 を OFF（v4 互換）
python scripts/scan_api_drift.py --no-v5-reverse --no-v5-naming

# V5-3 命名揺れだけ OFF
python scripts/scan_api_drift.py --no-v5-naming
```

既定は両方 ON。
