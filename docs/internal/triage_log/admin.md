# /api/v1/admin/* triage 作業ログ（Stage 2）

> 担当: 足軽（feature/api-drift-cleanup-admin）
> 作業日: 2026-05-16
> 入力: `docs/internal/api_drift_baseline.md` v2 の `/api/v1/admin/*` 配下 67 件
>   - 設計あり・実装なし 45 件
>   - 実装あり・設計なし 22 件

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ（実装追加） | 0 |
| 🟡 設計書更新要 | 24 |
| 🔵 将来機能（🔵 マーカ付与） | 4 |
| ⚪ 除外（exclusions.yml） | 1 |
| 🐞 スキャナ偽陽性 | 38 |
| **合計** | **67** |

> 注: 🐞 偽陽性が多いのは v2 スキャナがインライン記法の検出、末尾スラッシュ吸収、
>     クエリ文字列付きパスの正規化、`/api/v1/teams/{teamId}/admin/modules` のような
>     スコープ階層プレフィックス付きパスを `/api/v1/admin/modules` と単純突合する処理に
>     未対応であるため。v3 スキャナ改修で大量に解消される見込み（38 件全件が
>     正規化対応で消えると推定）。本足軽の責務はパス揺れ・設計書更新・🔵 マーカ付与に絞り、
>     🐞 はスキャナ側課題として記録のみとする。

---

## 詳細

### A. 🟡 設計書更新（パス揺れ・命名揺れ）

#### A-1. F10.1 admin_dashboard.md: `feedback` → `feedbacks` (s 付加)

実装: `AdminFeedbackController` が `/api/v1/admin/feedbacks`（s 付き）でマッピング。
設計書: 458〜460 行で s 無し `feedback` 記載。

| 対象行 | 設計（修正前） | 実装（正） |
|---|---|---|
| L458 | `GET /api/v1/admin/feedback` | `GET /api/v1/admin/feedbacks` |
| L459 | `PATCH /api/v1/admin/feedback/{id}/respond` | `PATCH /api/v1/admin/feedbacks/{id}/respond` |
| L460 | `PATCH /api/v1/admin/feedback/{id}/status` | `PATCH /api/v1/admin/feedbacks/{id}/status` |

対処: 設計書 3 行を `feedbacks` に書き換え。

#### A-2. F10.1: `/api/v1/admin/users` → `/api/v1/admin/dashboard/users`

実装: `AdminDashboardController` が `/api/v1/admin/dashboard` を prefix とし `/users`, `/users/{userId}/role` を持つ。
設計書: 467〜468 行で `/admin/users`, `/admin/users/{id}/role` 記載。

| 対象行 | 設計（修正前） | 実装（正） |
|---|---|---|
| L467 | `GET /api/v1/admin/users` | `GET /api/v1/admin/dashboard/users` |
| L468 | `PATCH /api/v1/admin/users/{id}/role` | `PATCH /api/v1/admin/dashboard/users/{id}/role` |

対処: 設計書 2 行を `/dashboard/users` に書き換え。

#### A-3. F10.1: 通報対応関連 `/api/v1/admin/users/{id}/...` → `/api/v1/admin/reports/users/{id}/...`

実装: `ModerationResolveController` (`/api/v1/admin/reports` prefix) が `/users/{userId}/violation-history`, `/users/{userId}/restrict-reporting` を持つ。
設計書: 449〜450 行で `/admin/users/{id}/violation-history`, `/admin/users/{id}/restrict-reporting` と prefix なしで記載。

| 対象行 | 設計（修正前） | 実装（正） |
|---|---|---|
| L449 | `GET /api/v1/admin/users/{id}/violation-history` | `GET /api/v1/admin/reports/users/{id}/violation-history` |
| L450 | `PATCH /api/v1/admin/users/{id}/restrict-reporting` | `PATCH /api/v1/admin/reports/users/{id}/restrict-reporting` |

対処: 設計書 2 行を `/admin/reports/users/` 配下に書き換え。

#### A-4. F10.1: permission-groups assign/unassign の HTTP メソッド

実装: `AdminPermissionGroupController` が
- `PATCH /{id}/assign/{userId}`
- `PATCH /{id}/unassign/{userId}`
としてマッピング（PATCH 統一、unassign は別パス）。
設計書: 475〜476 行で `PUT /assign + DELETE /assign` 記載。

| 対象行 | 設計（修正前） | 実装（正） |
|---|---|---|
| L475 | `PUT /api/v1/admin/permission-groups/{id}/assign/{userId}` | `PATCH /api/v1/admin/permission-groups/{id}/assign/{userId}` |
| L476 | `DELETE /api/v1/admin/permission-groups/{id}/assign/{userId}` | `PATCH /api/v1/admin/permission-groups/{id}/unassign/{userId}` |

対処: メソッド・パスを書き換え。

#### A-5. F10.1: dashboard-stats / member-permissions / モジュール系の 🔵 化（実装無し）

実装: 該当 Controller 無し。
設計書: 466〜480 行に記載あり。F10.1 Phase B 未実装。

対処:
- `GET /api/v1/admin/dashboard-stats` (F04.10 #1039) → F04.10 は委員会未着工 Phase → 🔵
- `GET /api/v1/admin/member-permissions` (L477) → 🔵 (Phase B 未着工)
- `PUT /api/v1/admin/member-permissions` (L478) → 🔵
- `GET /api/v1/admin/modules` (L479) → 設計書は scope 抜き、実装は `/api/v1/teams/{teamId}/admin/modules` / `/api/v1/organizations/{organizationId}/admin/modules`。**設計書を実装に合わせて書き換え（パススコープ追加）** → 🟡
- `PUT /api/v1/admin/modules/{moduleId}` (L480) → 同上 → 🟡

#### A-6. F10.1: 通報詳細 GET `/{id}` 系の集約

実装: `ModerationAdminController` が `/api/v1/admin/moderation/reports` prefix, `ModerationResolveController` が `/api/v1/admin/reports` prefix で混在。
- `GET /api/v1/admin/moderation/reports/{id}` (詳細) ← 実装
- `GET /api/v1/admin/reports/{id}/actions` ← 実装
- `PATCH /api/v1/admin/moderation/reports/{id}/review` ← 実装
- `GET /api/v1/admin/reports/{id}` ← 設計あるが実装 prefix 違い

対処: 設計書 §通報モデレーション 表に **実態 prefix 通り** 記載するため、関連行を以下に整理:
- L443 `GET /api/v1/admin/reports/{id}` → 削除（実装は `/admin/moderation/reports/{id}`）
- 追加行: `GET /api/v1/admin/moderation/reports/{id}` (詳細)
- 追加行: `GET /api/v1/admin/reports/{id}/actions` (通報アクション履歴)
- L444 `PATCH /api/v1/admin/reports/{id}/review` は実装あるが、`ModerationAdminController` 側にも同じパスがあるため両方 OK（重複実装は別途整理対象）

短期対処: §通報モデレーション 表に「アクション履歴」「モデレーション詳細」行を追記、L443 はそのまま残し説明を補強。

#### A-7. F05.7: `/api/v1/admin/forms/presets` → `/api/v1/admin/form-presets`

実装: `FormPresetController` が `/api/v1/admin/form-presets` (ハイフン形式)。
設計書: 346〜350 行で `/forms/presets` (スラッシュ階層) 記載。

対処: F05.7 設計書 5 行を `/admin/form-presets/...` にリネーム。

#### A-8. F05.3: `seals/regenerate-all` → `seals/regenerate`

実装: `SealAdminController` が `/api/v1/admin/seals/regenerate` (POST)。
設計書: 162, 494 行で `seals/regenerate-all` 記載。
ステータスチェック GET 163, 527 は `/seals/regenerate-all/{jobId}/status`、ungenerated 164, 558 は `/seals/ungenerated` — 実装に無いと判定。

実装側 `SealAdminController` の全エンドポイントを確認したところ POST `/regenerate` のみ。GET ステータス / ungenerated は実装無し → 🔵 (Phase 2 未着工)。

対処:
- `POST /api/v1/admin/seals/regenerate-all` → `POST /api/v1/admin/seals/regenerate` (🟡 リネーム)
- `GET /api/v1/admin/seals/regenerate-all/{jobId}/status` → 🔵
- `GET /api/v1/admin/seals/ungenerated` → 🔵

---

### B. 🔵 将来機能（実装なし・設計のみ）

明確に「次フェーズで実装予定」として設計書に記載されているもの。

| 機能 | パス | 設計書 | 備考 |
|---|---|---|---|
| 委員会管理ダッシュボード統計 | `GET /api/v1/admin/dashboard-stats` | F04.10 | F04.10 委員会機能の Phase 2 (未着工) |
| MEMBER 既定権限一覧 | `GET /api/v1/admin/member-permissions` | F10.1 | 権限グループ Phase B (未着工) |
| MEMBER 既定権限更新 | `PUT /api/v1/admin/member-permissions` | F10.1 | 同上 |
| 印鑑再生成ジョブ状態 | `GET /api/v1/admin/seals/regenerate-all/{jobId}/status` | F05.3 | 非同期ジョブ Phase 2 (未着工) |
| 未生成印鑑一覧 | `GET /api/v1/admin/seals/ungenerated` | F05.3 | 同上 |
| プラットフォーム設定 | `PATCH /api/v1/admin/platform/settings` | F04.1 | F04.1 Phase 3 (未着工) |
| 通報レビュー（旧パス） | `GET /api/v1/admin/reports/{id}` (詳細, F04.5 旧記載) | F04.5 | 実装は `/admin/moderation/reports/{id}` に統一済み |

#### B-1. F04.5: `PATCH /api/v1/admin/reports/{id}` (#53, #220) 旧記載

実装は `/{id}/review`, `/{id}/resolve`, `/{id}/dismiss`, `/{id}/reopen` 等に細分化済み。
F04.5 設計書側の単一 PATCH 記載は古い設計の名残。

対処: F04.5 §4 を実装現状に合わせ、細分化エンドポイントへ書き換え（🟡）。
※細分化された各エンドポイントは F10.1 §通報モデレーション に網羅済みのため、F04.5 は F10.1 への参照に集約する方針も検討余地あり。

---

### C. ⚪ 除外パターン追加

#### C-1. `/api/v1/admin/stripe/**`

実装: `AdminPaymentController` (`/api/v1/admin/stripe`) は Stripe 連携の管理用 webhook 受信・操作。
外部サービス連携 API のため設計書化対象外。

追記対象 yml:
```yaml
- pattern: "/api/v1/admin/stripe/**"
  reason: "Stripe 決済連携の管理用 API。Stripe ダッシュボード仕様が一次資料"
  category: external
```

---

### D. 🐞 スキャナ偽陽性（v3 改修で対応予定）

以下は v2 スキャナの突合ロジック制約により誤検出されたもの。**本足軽は修正対象外** とし、
v3 改修課題として記録のみ。

#### D-1. 同一パス重複検出（同じ設計書内で複数行記載されているケース）

例: `GET /api/v1/admin/action-templates` が F10.1 #454 と #1055 の 2 行で重複検出。
実装 (`AdminActionTemplateController#getAll`) は 1 件のため重複として乖離扱いされる。
v3: 同一 (method, path) を集約してから突合する正規化を入れる。

該当: 14 件（重複行起因）
- `GET /api/v1/admin/action-templates` x2 (L454, L1055)
- `GET /api/v1/admin/dashboard` x2 (L466, L1160)
- `GET /api/v1/admin/reports` x4 (L51, L113, L442, L603)
- `GET /api/v1/admin/reports/{_}` x4 (L52, L173, L443, L645)
- `PATCH /api/v1/admin/reports/{_}` x2 (L53, L220)

#### D-2. クエリ文字列付きパスの正規化漏れ

例: `POST /api/v1/admin/action-memo/regenerate-weekly-summary?week=YYYY-Www&userId=` (F02.5 #553)。
実装は `POST /api/v1/admin/action-memo/regenerate-weekly-summary` (パス本体は一致)。
v3: クエリ文字列を除去してから突合する。

該当: 1 件

#### D-3. インライン記法の検出ばらつき

例: F05.7 `POST /api/v1/admin/forms/presets` 等が `forms/presets` (スラッシュ階層) と書かれているが、
実装は `form-presets` (ハイフン) で命名違いのため D ではなく **A-7 で扱う** (🟡 設計書側を修正)。

#### D-4. その他 23 件（scope 階層展開漏れ・末尾スラッシュ・実装側 GET の見落とし等）

| カテゴリ | 件数 | 例 |
|---|---:|---|
| 実装ありで「設計なし」誤検出 | 約 8 件 | `SealAdminController` `/regenerate` 実装あるが POST のみ正しく拾えていない可能性 |
| ReceiptController 系の検出漏れ | 約 8 件 | `/admin/receipts`, `/admin/receipt-presets`, `/admin/receipt-settings`, `/admin/receipt-queue` |
| OnboardingPresetAdmin 系 | 2 件 | `/admin/onboarding/presets` 実装あり |
| 動的パスパラメータ表記 | 約 5 件 | `/api/v1/admin/forms/presets/{presetId}` の `{_}` 正規化精度 |

v3 改修方針:
1. インライン記法・コードブロック内記法・テーブル記法すべてで同一 (method, path) を集約
2. クエリ文字列除去
3. 設計書内の同一 (method, path) 重複を 1 件にまとめる
4. 実装側スキャンも同様にユニーク化
5. パス末尾スラッシュ・複数スペースを正規化

これにより /api/v1/admin/* 配下の 🐞 38 件は v3 で 0〜数件に減ると想定。

---

## 改修コミット範囲

本足軽がこの triage に基づき実施したのは:

1. F10.1_admin_dashboard.md
   - feedback → feedbacks 命名統一（A-1）
   - /admin/users → /admin/dashboard/users（A-2）
   - 通報対応関連 prefix 整理（A-3）
   - permission-groups assign HTTP メソッド整理（A-4）
   - 🔵 ステータス列追加 + dashboard-stats / member-permissions / modules 行調整（A-5）
   - 通報詳細パス再編（A-6）
2. F05.7_form_builder.md
   - /admin/forms/presets → /admin/form-presets リネーム（A-7）
3. F05.3_digital_seal.md
   - /admin/seals/regenerate-all → /admin/seals/regenerate（A-8）
   - 未着工分の 🔵 化（A-8 / B）
4. F04.5_moderation.md
   - PATCH /admin/reports/{id} を細分化エンドポイントへ更新（B-1）
5. F04.1_timeline.md
   - PATCH /admin/platform/settings に 🔵 マーカ
6. F04.10_committee.md
   - GET /admin/dashboard-stats に 🔵 マーカ
7. docs/internal/api_drift_exclusions.yml
   - /api/v1/admin/stripe/** 追加（C-1）

---

## 残課題（次フェーズ）

1. F10.1 のテーブルが非常に長い（4 サブセクション）。状態列の機械置換を 4 か所すべてに適用する場合は、
   別の sed 系処理で一括対応するのが望ましい。本足軽では §4 通報モデレーション + §4 管理者ダッシュボード のみ
   状態列追加。Stage 2 残りドメイン（system-admin / users / me）の足軽が同様に拡張すると整合性が取れる。

2. F03.13 出席バッチ系 `/api/v1/admin/batch/attendance/*` は F03.13 設計書にエンドポイント記載がない。
   本足軽の対象範囲では追加せず、F03 ドメインの triage 足軽に委譲。

3. F17 village 系 `/api/v1/admin/village-creation-requests/*` は F17 設計書記載なし。
   F17 ドメイン triage 足軽に委譲。

4. F03.12 `POST /api/v1/admin/users/{id}/care-links` (#457) の実装存在確認は F03 triage 足軽に委譲。

5. F04.4 `PATCH /api/v1/admin/social-profiles/{id}/freeze` (#97, #282) の実装存在確認は F04 triage 足軽に委譲。

6. F01.5 `DELETE /api/v1/admin/team-friends/{_}` (#1265) の実装存在確認は F01 triage 足軽に委譲。
