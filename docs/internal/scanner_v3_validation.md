# スキャナ v3 効果検証

> 作成日: 2026-05-17
> 担当: feature/api-drift-scanner-v3 足軽
> 入力: v2 ベースライン（2,403 件乖離） vs v3 再生成

---

## 1. 全体数字比較

| 区分 | v2 | v3 | 差分 | 削減率 |
|---|---:|---:|---:|---:|
| 設計あり・実装なし | 1,256 | 1,214 | **-42** | -3.3% |
| 実装あり・設計なし | 1,147 | 1,106 | **-41** | -3.6% |
| 一致 | 1,322 | 1,341 | **+19** | +1.4% |
| **合計乖離** | **2,403** | **2,320** | **-83** | **-3.5%** |
| 設計ユニーク総数 | 2,578 | 2,555 | -23 | -0.9% |
| 実装ユニーク総数 | 2,469 | 2,447 | -22 | -0.9% |
| 除外（実装側） | — | 22 | — | — |
| 除外（設計側） | — | 4 | — | — |

> **重要**: 全体削減率 -3.5% に留まったのは、v2 偽陽性の大宗が「6 バグ」より
> **設計書側のパス命名揺れ（s 有無・dashboard prefix 抜け・/me/scope/{id} 表記揺れ等）**
> に起因しており、スキャナ側では機械的に解消できないため。スキャナ v3 で
> 機械的に解消可能な範囲（query 切捨・末尾スラッシュ・スコープ展開）は
> ほぼ拾い切れている。残る 96% はドメイン別 triage で人力解消が必要。

---

## 2. バグ別効果（推定）

### バグ1: path?query を path のみで比較
- **削減推定**: 約 15〜25 件（設計書側で `?scopeType=TEAM` 等を末尾に付けて
  記載していたエンドポイントが、実装側 path と一致するようになった）
- **実測**: `/api/v1/me/*` ドメインの一致が +5 (65→70)、only_design -11 (42→31)。
  これは v2 で query 付きが分離されていたものが v3 で path 単独に正規化された効果。

### バグ2: 同一 (method, path) 重複の Set 排除
- **削減推定**: 数件（v2 でも dict キーで自然に集約されていたため、影響は限定的）
- **効果**: バグ修正というより堅牢性向上。今後の集計安定化に貢献。

### バグ3: クエリ文字列正規化
- バグ1 と一体で実装。`expand_scope_paths` の入口でも query 切捨を行うように
  したため、`/api/v1/{scope}/{scopeId}/foo?bar=1` のような設計書記述も
  正規化される。

### バグ4: 末尾スラッシュ取りこぼし
- **削減推定**: 数件（v2 で既に基本対応済。v3 で query 切捨後の再 rstrip 適用）
- **効果**: 設計書側で `/api/v1/foo/` と記載されていたケースが完全に救済された。

### バグ5: {scope} 階層展開
- **削減推定**: `/api/v1/{_}/*` ドメインで -6 (49→43)、`/api/v1/teams/*` 一致 +10
  (477→487)。expand_scope_paths が query 込み入力にも対応した副次効果。

### バグ6: 文字化け Controller の読み飛ばし防止
- **削減推定**: 0 件（v2 既に `errors='replace'` 適用済を確認）
- **効果**: 念押しのため、将来の Java ファイル新規追加時の安全弁として有効。
  `OnboardingPresetAdminController.java` も実測では strict utf-8 で読めることを確認済。

### 除外パターン適用（v3 新規機能）
- **削減推定**: 実装側 22 件・設計側 4 件
- **効果**: `/api/v1/admin/system-flag/*`, `/api/v1/admin/debug/*`,
  `/api/v1/admin/stripe/*` ほか 20 パターンを `api_drift_exclusions.yml` から
  ロードして両側除外。

---

## 3. ドメイン別偽陽性率の変化

| ドメイン | v2 合計乖離 | v3 合計乖離 | 差分 | v2 一致 | v3 一致 | 一致差 |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/teams/* | 530 | 507 | **-23** | 477 | 487 | +10 |
| /api/v1/organizations/* | 482 | 480 | -2 | 147 | 148 | +1 |
| /api/v1/system-admin/* | 131 | 130 | -1 | 77 | 77 | 0 |
| /api/v1/users/* | 123 | 122 | -1 | 48 | 48 | 0 |
| /api/v1/villages/* | 122 | 122 | 0 | 1 | 1 | 0 |
| /api/v1/me/* | 67 | **51** | **-16** | 65 | 70 | **+5** |
| /api/v1/admin/* | 50 | 48 | -2 | 42 | 42 | 0 |
| /api/v1/shifts/* | 48 | 47 | -1 | 25 | 25 | 0 |
| /api/v1/{_}/* | 49 | 43 | -6 | 0 | 0 | 0 |
| /api/v1/recruitment-listings/* | 9 | 7 | -2 | 8 | 9 | +1 |
| /api/v1/scopes/* | 7 | 0 | **-7** | 0 | 0 | 0 |
| /api/v1/embed/* | 3 | 0 | **-3** | 0 | 0 | 0 |

> `/api/v1/scopes/*` `/api/v1/embed/*` は除外パターンか命名修正によって
> 全消化された可能性（要 v3 baseline 検証）。

---

## 4. admin ドメイン triage_log との対応

`docs/internal/triage_log/admin.md` で v2 偽陽性 38 件（admin 67 件中 57%）と分類されていた。
v3 では admin 合計乖離が 50 → 48（-2）なので、**38 件中ほぼ全件は未解消**。

理由分析:
- triage_log admin.md で「v3 スキャナ改修で大量に解消される見込み」とされた 38 件の
  うち、実際には大半が **「スコープ階層プレフィックス付きパスを単純突合」** が
  原因とされている。例: `/api/v1/teams/{teamId}/admin/modules` という実装に対して
  設計書が `/api/v1/admin/modules` と書かれているケース。
- これは「設計書が短縮形で書かれている」という設計書側の表記揺れであり、
  スキャナ側で機械的に対応するには
  「`/admin/` セグメントを path 末尾から逆検索して一致を試みる」という
  fuzzy match が必要となる。v3 の責務範囲を超える（誤マッチを増やすリスク）ため、
  v4 候補として記録。

---

## 5. v3 後の真の乖離規模（推定）

| 観点 | 推定値 | 根拠 |
|---|---:|---|
| 機械的に救済可能だった偽陽性（v3 で解消） | 約 83 件 | 実測差分 |
| 設計書側のパス命名揺れ（人力 triage 必要） | 約 800〜1,000 件 | 残 only_design 1,214 の 65〜80%が triage_log admin.md の傾向通りなら |
| 設計書未記載で要追記な未文書化エンドポイント | 約 200〜300 件 | only_impl 1,106 のうち admin 系・internal 系除外後 |
| 真の漏れ（実装すべきだが未着手） | 約 100 件未満 | triage_log admin.md では 0 件 → 全体でも稀と推定 |

---

## 6. 新たに発見した課題（v4 候補）

### V4-1: スコープ階層プレフィックスの逆引きマッチ
- 例: 設計 `/api/v1/admin/modules` ≡ 実装 `/api/v1/teams/{teamId}/admin/modules`
- 対応案: 設計側パスが `/admin/` 等のサブパスを含む場合、実装側を末尾一致で
  fuzzy 検索し、候補リストを「準一致」として別カテゴリ表示する

### V4-2: 命名揺れ自動検出（s 有無・kebab/camel 等）
- 例: `feedback` vs `feedbacks`, `dwelling-unit` vs `dwelling_units`
- 対応案: 単数複数変換 + 区切り文字統一の正規化辞書を導入。
  設計書側パスにヒューリスティック正規化を適用してから突合

### V4-3: 設計書側の重複記載検出
- 同じエンドポイントが複数の設計書に書かれているケース（F10.1 と F02.x の
  両方など）。レポートで「N 個の設計書から参照」と表示すると重複保守の
  きっかけになる

### V4-4: 機能ID（F0X.X）の付与
- 設計書ファイル名から機能 ID を抜き出し、only_design リストに付与すると
  「どの機能の漏れか」が即座に分かる

### V4-5: 設計書記載の「将来機能」マーカ認識
- 設計書側で「🔵 v2.0 で実装予定」のように明示されたエンドポイントは
  only_design からも除外したい。マーカ規約を triage_rules.md と
  連携させる

---

## 7. 結論

- v3 で**機械的に救済可能な偽陽性（query/末尾スラッシュ/スコープ展開）は
  ほぼ完全に解消**した（推定削減 83 件 / 3.5%）。
- v2 偽陽性 ~54% という当初推定は、admin ドメインの triage_log を元に
  外挿した楽観的見込みであり、実際の偽陽性の大宗は
  **「設計書側パスの命名揺れ・短縮形表記」** に起因することが判明。
- **次の効果的な打ち手は v4 のスコープ階層 fuzzy match と命名揺れ正規化**
  であり、これらを実装すれば追加で 500〜800 件削減できる見込み。
- 当面は v3 ベースラインをもとに、admin で確立した triage 手順を
  他ドメイン（teams, organizations, system-admin, me）に水平展開して
  人力 triage を進めるのが現実的。

---

## 8. 検証手順（再現用）

```bash
# v3 単体テスト（23 件 PASS）
cd backend && python -m unittest scripts.test_scan_api_drift -v

# v3 ベースライン生成（除外あり・既定）
cd backend && python scripts/scan_api_drift.py

# v3 ベースライン生成（除外なし・効果切り分け用）
cd backend && python scripts/scan_api_drift.py --no-exclusions

# v2 と v3 の生数字比較
git checkout feature/api-drift-cleanup-admin -- backend/scripts/scan_api_drift.py
cd backend && python scripts/scan_api_drift.py
# → [DONE] missing_impl=1256 missing_design=1147 matched=1322
git checkout feature/api-drift-scanner-v3 -- backend/scripts/scan_api_drift.py
cd backend && python scripts/scan_api_drift.py
# → [DONE] missing_impl=1214 missing_design=1106 matched=1341
```
