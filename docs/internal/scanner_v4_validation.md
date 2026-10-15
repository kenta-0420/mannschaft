# スキャナ v4 効果検証

> 作成日: 2026-05-17
> 担当: feature/api-drift-scanner-v4 足軽
> 入力: v3 ベースライン（2,320 件乖離） vs v4 再生成

---

## 0. TL;DR

- v4 全体削減は **-16 件**（2,320 → 2,304）と小幅。
- **V4-1（スコープ階層プレフィックス逆引きマッチ）の本番ヒットは 0 件**だった。
  原因は SCOPE_CORE_PATTERNS の安全ホワイトリスト（`/admin/`, `/dashboard/`,
  `/modules/`, `/visibility/`, `/settings/`）が現実の偽陽性ケースとほぼ
  噛み合わなかったため。
- **V4-5（🔵 タグ認識）は 5 件**を将来機能として分類しメイン集計から除外。
  並行効果として **新規 🟢 タグ行 53 件を追加抽出**（うち実装側マッチ 32 件・
  only_design 21 件）。
- 真の偽陽性削減は v5 で「**スコープ逆引きの対象拡張（リソース系コアパス許可）**」
  に着手するのが効果的。候補は 154 件（Section 5 参照）。

---

## 1. 全体数字比較

| 区分 | v3 | v4 | 差分 | 削減率 |
|---|---:|---:|---:|---:|
| 設計あり・実装なし | 1,214 | 1,221 | **+7** | +0.6% |
| 実装あり・設計なし | 1,106 | 1,083 | **-23** | -2.1% |
| 一致 | 1,341 | 1,364 | **+23** | +1.7% |
| **合計乖離** | **2,320** | **2,304** | **-16** | -0.7% |
| 設計ユニーク総数（main） | 2,555 | 2,585 | +30 | +1.2% |
| 実装ユニーク総数 | 2,447 | 2,447 | 0 | 0% |
| 除外（実装側） | 22 | 22 | 0 | — |
| 除外（設計側） | 4 | 4 | 0 | — |
| V4-1 スコープ逆引き準一致 | — | **0** | — | — |
| V4-5 🔵 将来機能 | — | **5** | — | — |

> 「設計あり・実装なし」が +7 件と微増したのは、**v3 まで未抽出だった
> 状態列付きテーブル行 (`| 🟢 | GET | /api/v1/... |`) を v4 で新たに認識した
> 副次効果** が主因。新規認識 53 件のうち、impl とマッチした 32 件は matched に
> 入り、impl が無かった 21 件は only_design に入ったため。

---

## 2. V4-1 効果分析（スコープ階層プレフィックス逆引きマッチ）

### 設計と実測の乖離

- **期待**: 500+ 件削減（軍議書時点の見込み）
- **実測**: 0 件（本番データで SCOPE_CORE_PATTERNS にヒットなし）

### 原因分析

SCOPE_CORE_PATTERNS は誤合体回避のため以下 5 種に限定した:

```
/api/v1/admin/**
/api/v1/dashboard/**
/api/v1/modules/**
/api/v1/visibility/**
/api/v1/settings/**
```

これに対し、現実の `only_impl` のスコープ prefix 付きパスを `extract_core_path` で
core 化したところ、上記ホワイトリストにマッチするものは **0 件** だった。
理由:

1. 既に `/api/v1/teams/{teamId}/admin/modules` は **設計書側に同じ scope prefix 付きで
   記載されている**（F16.1 等で正規記載）→ そもそも matched 側に入っていて
   only_impl/only_design には来ない。
2. `/api/v1/admin/modules` 風の「scope 抜き設計」と「scope 付き実装」のペアは
   実コードベース上では存在しない（軍議書の想定例は仮想的だった）。
3. 真の偽陽性ペアは、`/api/v1/coupons` ≡ `/api/v1/organizations/{_}/coupons` のような
   **リソース系**であり、これらは SCOPE_CORE_PATTERNS の範囲外。

### SCOPE_CORE_PATTERNS を解除した場合の候補

ホワイトリスト撤廃で逆引きを試みた場合の matching 候補は **154 件**。
ただし誤合体リスクが高い（team-scoped resource と global resource は別物の
可能性大）ため、現状の v4 では適用せず v5 候補として記録する。

候補（最初の 20 件、ドメイン横断）:

| 実装側パス | 設計コア |
|---|---|
| GET /api/v1/organizations/{_}/coupons | GET /api/v1/coupons |
| GET /api/v1/teams/{_}/coupons | GET /api/v1/coupons |
| POST /api/v1/users/{_}/repair-plan/quote-kanbans/{_}/cards | POST /api/v1/repair-plan/quote-kanbans/{_}/cards |
| DELETE /api/v1/teams/{_}/segment-presets/{_} | DELETE /api/v1/segment-presets/{_} |
| GET /api/v1/organizations/{_}/property-listings | GET /api/v1/property-listings |
| PUT /api/v1/teams/{_}/property-listings/{_} | PUT /api/v1/property-listings/{_} |
| PUT /api/v1/organizations/{_}/bulletin/categories/{_} | PUT /api/v1/bulletin/categories/{_} |
| GET /api/v1/teams/{_}/property-listings | GET /api/v1/property-listings |
| POST /api/v1/villages/{_}/repair-plan/quote-kanbans/{_}/cards | POST /api/v1/repair-plan/quote-kanbans/{_}/cards |
| PUT /api/v1/teams/{_}/dwelling-units/{_} | PUT /api/v1/dwelling-units/{_} |
| POST /api/v1/organizations/{_}/succession/unseal-requests/{_}/second-approve | POST /api/v1/succession/unseal-requests/{_}/second-approve |
| GET /api/v1/users/{_}/surveys/{_} | GET /api/v1/surveys/{_} |
| GET /api/v1/teams/{_}/repair-plan/handover-packs/{_}/download | GET /api/v1/repair-plan/handover-packs/{_}/download |
| POST /api/v1/users/{_}/repair-plan/scenarios/{_}/publish-as-announcement | POST /api/v1/repair-plan/scenarios/{_}/publish-as-announcement |
| DELETE /api/v1/organizations/{_}/property-listings/{_} | DELETE /api/v1/property-listings/{_} |
| POST /api/v1/teams/{_}/repair-plan/quote-cards/{_}/move | POST /api/v1/repair-plan/quote-cards/{_}/move |
| POST /api/v1/organizations/{_}/promotions | POST /api/v1/promotions |
| GET /api/v1/teams/{_}/dwelling-units | GET /api/v1/dwelling-units |
| POST /api/v1/villages/{_}/repair-plan/quote-cards/{_}/move | POST /api/v1/repair-plan/quote-cards/{_}/move |
| GET /api/v1/villages/{_}/surveys/{_} | GET /api/v1/surveys/{_} |

> 上記の大半は `expand_scope_paths` が `{scope}/{scopeId}` を 4 種スコープに
> 展開した結果として生まれる「重複的な only_impl」である。設計書側が
> scope-agnostic で書かれているなら、これらは誤合体ではなく真の準一致になる。
> v5 での扱いは要設計（後述）。

---

## 3. V4-5 効果分析（🔵 タグ認識）

### 認識件数

| 区分 | 件数 |
|---|---:|
| 🔵 ユニーク (method, path) | **5** |
| うち実装あり・設計のみ 🔵 | 0 |
| 🟢 として新規認識 | 53 |
| その他状態（🟡/❌） | 0 |

### 🔵 検出エンドポイント一覧

| 状態 | メソッド | パス | 設計書 |
|---|---|---|---|
| 🔵 | GET | /api/v1/admin/member-permissions | F10.1_admin_dashboard.md:480 |
| 🔵 | PUT | /api/v1/admin/member-permissions | F10.1_admin_dashboard.md:481 |
| 🔵 | GET | /api/v1/admin/permission-groups/{_} | F10.1_admin_dashboard.md:474 |
| 🔵 | GET | /api/v1/admin/seals/regenerate-all/{_}/status | F05.3_digital_seal.md:163 |
| 🔵 | GET | /api/v1/admin/seals/ungenerated | F05.3_digital_seal.md:164 |

5 件はいずれも実装側に存在しない（only_design 候補だったもの）。v4 で **メイン
集計から除外** したため、only_design は -5 件される効果がある（実数値は他の
変動と相殺）。

### 影響範囲（🔵 タグが付与されている設計書）

- `F05.3_digital_seal.md`: 2 件
- `F10.1_admin_dashboard.md`: 3 件

Stage 2 で他足軽が `F02.9`, `F03.5`, `F03.11`, `F03.12`, `F03.15` 等に
🔵 を追加する余地あり。今後タグ拡充が進めば v4 の効果は線形に増える。

### 状態列付きテーブル（🟢 含む）の副次効果

新たに状態列付きテーブルを認識できるようになったことで、v3 まで完全に
取りこぼされていた **53 件の 🟢 タグ付き設計エンドポイント** が追加抽出された。
内訳:

- 実装側マッチ → matched +32 件
- 実装側に該当なし → only_design +21 件

このため only_design は見かけ上 +7 件増加した（=21-14、ただし -14 は V4-5 と
無関係の他の経路での減少分推定）。**「設計書の網羅性が実態に追いついた」**
ポジティブな副次効果として解釈すべき。

---

## 4. ドメイン別変化（抜粋）

| ドメイン | v3 合計乖離 | v4 合計乖離 | 差分 | v3 一致 | v4 一致 | 一致差 |
|---|---:|---:|---:|---:|---:|---:|
| /api/v1/admin/* | 48 | 28 | **-20** | 42 | 73 | **+31** |
| /api/v1/teams/* | 507 | 508 | +1 | 487 | 488 | +1 |
| /api/v1/organizations/* | 480 | 480 | 0 | 148 | 148 | 0 |
| /api/v1/system-admin/* | 130 | 130 | 0 | 77 | 77 | 0 |
| /api/v1/me/* | 51 | 51 | 0 | 70 | 70 | 0 |
| /api/v1/users/* | 122 | 122 | 0 | 48 | 48 | 0 |

> `/api/v1/admin/*` ドメインで **合計乖離 -20 件 / 一致 +31 件** と最大の改善。
> これは F10.1_admin_dashboard.md と F05.3_digital_seal.md の状態列付き
> テーブル（53 件中 大半が admin 系）が v4 で初めて認識された効果。
> 既存実装と新規認識の matched 化が大量に発生した。

---

## 5. v5 候補（優先度順）

### 🥇 V5-1: スコープ逆引き対象拡張（リソース系コアパス許可）

- **期待効果**: 約 100〜154 件削減
- **内容**: SCOPE_CORE_PATTERNS を撤廃または大幅拡張し、リソース系
  （`/api/v1/coupons`, `/api/v1/surveys`, `/api/v1/repair-plan/...`,
  `/api/v1/dwelling-units` 等）も逆引き対象に含める。
- **リスク**: 誤合体（global resource と scope-scoped resource が
  別ハンドラかもしれない）。設計書側に明示マーカ（例:
  `<!-- scope-agnostic -->`）を許可するか、ホワイトリスト方式で
  ドメイン別に拡張するのが安全策。
- **代替案**: `expand_scope_paths` の挙動を変更し、スコープ展開しないモードを
  デフォルトにする。設計書側に「scope 抜きで記載」を許す代わりに、
  実装側からは scope を加味しない比較に統一する。

### 🥈 V5-2: 命名揺れ自動検出（s 有無・kebab/camel）

- **期待効果**: 約 50〜80 件削減
- v3 検証書の V4-2 候補から再選定。`feedback` vs `feedbacks`,
  `dwelling-unit` vs `dwelling_units` 等の正規化辞書を導入。

### 🥉 V5-3: 設計書側の機能 ID 付与

- **期待効果**: 件数削減ゼロだが、人力 triage 効率を大きく改善
- 設計書ファイル名から `F0X.X` を抽出して only_design リストに付与。

### V5-4: 設計書側の重複記載検出

- 同じエンドポイントが複数の設計書に書かれているケースをレポート。
  重複保守の起点に。

### V5-5: 🔵 タグの設計書側普及促進

- Stage 2 各足軽の作業対象設計書（F02.9 / F03.5 / F03.11 / F03.12 / F03.15
  ほか）への 🔵 タグ追加を待つ。v4 はその基盤として完成済。

---

## 6. 結論

- v4 は **基盤整備フェーズ**として位置づけられる。V4-1 本体の即効性は
  ホワイトリスト戦略により限定的だったが、V4-5（🔵 タグ認識）に伴う
  **状態列付きテーブル全般の認識能力獲得** が大きな副次効果として
  53 件の新規 design を取り込めた（うち 32 件が matched）。
- 次の打ち手は **V5-1 スコープ逆引き対象拡張** で、軍議書で当初期待された
  500+ 件削減の大宗はここで初めて実現できる見込み。
- 🔵 タグ運用は今後 Stage 2 で広がる予定であり、v4 の枠組みは将来効果も
  確保している。

---

## 7. 検証手順（再現用）

```bash
# v4 単体テスト（40 件 PASS）
cd backend && python -m unittest scripts.test_scan_api_drift -v

# v4 ベースライン生成（除外あり・既定）
cd backend && python scripts/scan_api_drift.py

# v3 と v4 の生数字比較
git checkout feature/api-drift-scanner-v3 -- backend/scripts/scan_api_drift.py
cd backend && python scripts/scan_api_drift.py
# → [DONE] missing_impl=1214 missing_design=1106 matched=1341
git checkout feature/api-drift-scanner-v4 -- backend/scripts/scan_api_drift.py
cd backend && python scripts/scan_api_drift.py
# → [DONE] missing_impl=1221 missing_design=1083 matched=1364
```
