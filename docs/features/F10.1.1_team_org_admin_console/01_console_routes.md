# F10.1.1 / 01: 管理コンソール ルート設計・既存ルート再編

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md) / [04_security_authorization.md](./04_security_authorization.md)

本書は L2/L3 管理コンソールのルート構成、各セクションの画面要件、既存の散在ルート（`settings/*`・`member-*`）の取込・再編マッピングを定義する。

---

## 1. ルート全体像

```
/teams/[slug]/admin                         ← L2 ハブ（NEW）
  ├ /admin/reservations                     ← L3 予約確認（既存APIに新規画面）
  ├ /admin/budget                           ← L3 予算管理（既存APIに新規ハブ画面）
  ├ /admin/approvals                        ← L3 横断承認待ち（NEW・集約API）
  ├ /admin/members                          ← L3 メンバー管理/統計（既存ルート集約）
  │   ├ /admin/members/cards                ← 既存 member-cards.vue を移設
  │   ├ /admin/members/profiles             ← 既存 member-profiles.vue を移設
  │   ├ /admin/members/fields               ← 既存 member-fields.vue を移設
  │   └ /admin/members/info                 ← 既存 member-info.vue / settings/member-info.vue を統合
  └ /admin/settings                         ← L3 設定集約ハブ（既存 settings/* を内包）
      ├ /admin/settings/shift
      ├ /admin/settings/faq
      ├ /admin/settings/public
      ├ /admin/settings/care-overrides
      ├ /admin/settings/todo-status-labels
      ├ /admin/settings/notification-credits
      └ /admin/settings/modules            ← モジュール ON/OFF（admin/modules API）

/organizations/[slug]/admin                  ← L2 ハブ（既存。point-cards はその配下に既存）
  ├ /admin/point-cards/*                     ← 既存（F18 Phase2）。ハブのカードから導線
  ├ /admin/reservations
  ├ /admin/budget
  ├ /admin/approvals                         ← NEW・集約API
  ├ /admin/members/*
  └ /admin/settings/*                        ← faq-settings / notification-credits / public-settings / todo-status-labels 等を内包
```

> **注**: チームと組織はルート接頭辞（`/teams/[slug]` vs `/organizations/[slug]`）とレイアウト（`team` / `organization`）が異なるだけで、`/admin` 配下のセクション構成は同型。コンポーネントは `scopeType` を prop で受けて共通化する。

---

## 2. L2 ハブページ設計

### 2.1 ファイル

| ルート | 新規/既存 | ファイル |
|--------|----------|---------|
| `/teams/[slug]/admin` | **新規** | `frontend/app/pages/teams/[slug]/admin/index.vue` |
| `/organizations/[slug]/admin` | **新規（インデックスのみ）** | `frontend/app/pages/organizations/[slug]/admin/index.vue` |

> `/organizations/[slug]/admin/point-cards/` は既に存在するが、`/organizations/[slug]/admin/index.vue`（ハブのトップ）は未整備のため新設する。point-cards はハブの1カードとして導線を張る。

### 2.2 ハブ UI 要件（point-cards/index.vue の作法を踏襲）

`/organizations/[slug]/admin/point-cards/index.vue`（既存）と同一パターンで実装する:

- `definePageMeta({ layout: 'team' | 'organization', middleware: ['auth', 'admin-console'] })`
- アクセス権は **ミドルウェア（§5）で 404 not-found**。ページ本体では二重防御として `useRoleAccess` でカード表示を制御
- カテゴリ別アクションカードをグリッド表示（`grid gap-4 sm:grid-cols-2 lg:grid-cols-3`）
- 各カードに「要対応件数バッジ」を表示（承認待ち集約 API のサマリを使用）

```
┌─────────────────── /teams/[slug]/admin（ハブ） ──────────────────┐
│  チーム管理コンソール                                              │
│  〇〇チーム                                                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                          │
│  │ 予約      │ │ 予算      │ │ 承認待ち  ●5│  ← バッジ            │
│  │ confirm…  │ │ 会計年度… │ │ 横断…     │                          │
│  └──────────┘ └──────────┘ └──────────┘                          │
│  ┌──────────┐ ┌──────────┐                                        │
│  │ メンバー  │ │ 設定      │                                        │
│  └──────────┘ └──────────┘                                        │
└──────────────────────────────────────────────────────────────────┘
```

### 2.3 表示カードと導線

| カード | 遷移先 | バッジ | 表示条件 |
|--------|--------|--------|---------|
| 予約 | `/admin/reservations` | 承認待ち予約件数 | reservation モジュール有効 + ADMIN/DEPUTY |
| 予算 | `/admin/budget` | 超過カテゴリ数（あれば赤） | budget モジュール有効 + 予算閲覧権 |
| 承認待ち | `/admin/approvals` | `total_pending`（集約API） | ADMIN/DEPUTY |
| メンバー | `/admin/members` | 入会申請件数（承認待ちの一部） | ADMIN/DEPUTY |
| 設定 | `/admin/settings` | なし | ADMIN（DEPUTY は権限グループ依存） |
| ポイントカード（組織のみ） | `/admin/point-cards` | なし | F18 既存条件 |

---

## 3. L3 セクション設計

### 3.1 予約確認 `/admin/reservations`

- **データソース（既存流用）**: `GET /api/v1/teams/{teamId}/reservations`（`?status=PENDING` で承認待ち抽出）/ `/organizations/{orgId}/reservations`
- **操作（既存流用）**: `POST .../confirm` `/cancel` `/complete` `/no-show` `/reschedule` `PATCH .../admin-note`
- **画面**: 承認待ち（PENDING）を上部に強調、確定済み・完了・キャンセルをタブ切替。各予約に confirm/cancel/complete/no-show/reschedule のアクションボタン。reschedule は日時ピッカーのダイアログ。
- **新規 API は不要**（既存 `TeamReservationController` / `OrgReservationController` で充足）。

### 3.2 予算管理 `/admin/budget`

- **データソース（既存流用）**: budget ドメインの既存 Controller 群
  - 会計年度: `GET/PATCH /api/v1/budget/fiscal-years/{id}`、`POST .../close` `/reopen`、`Team/OrgBudgetFiscalYearController`
  - 配分: `GET/PUT /api/v1/budget/fiscal-years/{id}/allocations`
  - カテゴリ: `GET/POST .../categories`、`PATCH/DELETE /categories/{id}`
  - 取引: `Team/OrgBudgetTransactionController`
  - サマリ（超過アラート含む）: `GET .../summary`、`GET .../categories/{id}/summary`
  - レポート/CSV: `BudgetReportController` / `BudgetCsvController`
  - 設定: `Team/OrgBudgetConfigController`
- **画面**: 会計年度セレクタ → サマリ（配分・実績・残・超過カテゴリを赤表示）→ カテゴリツリー → 取引一覧。超過アラートはサマリ API の超過フラグをそのまま表示（独自の閾値判定を FE/新APIで作らない）。
- **新規 API は不要**。

### 3.3 横断承認待ち `/admin/approvals`

- **データソース（新規）**: `GET /api/v1/dashboard/{team|organization}/{id}/admin-action-required`（[03](./03_admin_action_required_api.md)）
- **操作（既存流用）**: 各ドメインの承認/却下 API（予約 confirm/cancel、シフトリクエスト承認、マッチング承認、支払承認、入会/入村申請承認）
- **画面**: ドメイン別セクション（予約・シフト・マッチング・支払・入会申請）。各セクションは件数＋直近数件のプレビュー＋「すべて見る」で当該ドメインの一覧ページへ遷移。承認待ち集約 API は**件数と導線**を返すのが主目的で、承認実行は各ドメインの既存 API を呼ぶ（[03](./03_admin_action_required_api.md) §3）。

### 3.4 メンバー管理/統計 `/admin/members`

- **データソース（既存流用）**:
  - 一覧: `GET /api/v1/admin/dashboard/users?scope_type&scope_id`（F10.1 母体既存）
  - ロール変更: `PATCH /api/v1/admin/dashboard/users/{id}/role`（最後の ADMIN 自己降格防止は F10.1 母体既存ロジック）
  - 招待: 既存の招待 API（チーム/組織の invitation 系）
  - 会員証/プロフィール/フィールド/情報: 既存 `member-cards`・`member-profiles`・`member-fields`・`member-info` 系
  - 統計: `GET /api/v1/admin/dashboard` の `member_stats`（total/active/new_this_month）
- **画面**: 上部に member_stats サマリカード。下部にメンバー一覧（ロールバッジ・ロール変更・招待ボタン）。会員証/プロフィール/フィールド/情報はサブタブまたはサブルート。
- **PII 配慮**: 退会済み（匿名化済み）メンバーは匿名化後の表示名（`anonymize()` 済み）で表示し、原 PII を復元表示しない（[04](./04_security_authorization.md) §6）。
- **新規 API は不要**。

### 3.5 設定集約 `/admin/settings`

- 既存の `settings/*` 個別ルートを内包するハブ。各設定は既存ページをそのまま L3 として配置し直す（§4 再編マッピング）。
- DEPUTY_ADMIN の可否はセクションごとに権限グループで分岐（[04](./04_security_authorization.md) §4）。

---

## 4. 既存ルート再編マッピング表

既存の散在ルートを `/admin` ハブ配下に**論理的に集約**する。物理移設（ファイル移動）とリダイレクトの2方式を機能ごとに使い分ける。

### 4.1 チーム

| 既存ルート | 再編後の位置 | 方式 | 根拠 |
|-----------|-------------|------|------|
| `teams/[slug]/member-cards.vue` | `/admin/members/cards` | リダイレクト（旧ルートは残し `navigateTo` で転送）| 既存被リンク・ブックマーク保護 |
| `teams/[slug]/member-profiles.vue` | `/admin/members/profiles` | リダイレクト | 同上 |
| `teams/[slug]/member-fields.vue` | `/admin/members/fields` | リダイレクト | 同上 |
| `teams/[slug]/member-info.vue` | `/admin/members/info` | リダイレクト | 同上 |
| `teams/[slug]/settings/member-info.vue` | `/admin/members/info` に統合 | リダイレクト（重複解消） | member-info の二重ルートを1本化 |
| `teams/[slug]/settings/shift`（既存があれば） | `/admin/settings/shift` | ハブからの導線追加（物理移設はしない） | 設定群はハブの index からリンク集約 |

### 4.2 組織

| 既存ルート | 再編後の位置 | 方式 | 根拠 |
|-----------|-------------|------|------|
| `organizations/[slug]/member-cards.vue` | `/admin/members/cards` | リダイレクト | 被リンク保護 |
| `organizations/[slug]/member-profiles.vue` | `/admin/members/profiles` | リダイレクト | 同上 |
| `organizations/[slug]/admin/point-cards/*` | 現状維持（`/admin` ハブに導線追加のみ） | ハブからの導線追加 | 既に `/admin` 配下にあり再編不要 |
| `organizations/[slug]/settings/faq-settings.vue` | `/admin/settings/faq` | ハブからの導線追加 | 設定群集約 |
| `organizations/[slug]/settings/notification-credits.vue` | `/admin/settings/notification-credits` | ハブからの導線追加 | 同上 |
| `organizations/[slug]/settings/public-settings.vue` | `/admin/settings/public` | ハブからの導線追加 | 同上 |
| `organizations/[slug]/settings/todo-status-labels.vue` | `/admin/settings/todo-status-labels` | ハブからの導線追加 | 同上 |

### 4.3 再編方式の決定理由

- **リダイレクト方式（member-*）**: 既存の被リンク・ブックマーク・他機能からのディープリンクが多いルートは、旧パスを残して `definePageMeta` の `redirect` または `middleware` で新パスへ転送する。旧パスを物理削除すると 404 が発生し既存 E2E・通知 URL を壊すため。
- **導線追加方式（settings/*）**: 設定群は被リンクが少なく、ハブの index ページからリンクを集約するだけで「散在解消」の目的を達成できる。物理移設はディレクトリ階層変更による import パス破壊リスクが大きいため避ける。
- いずれの方式も**既存ページの中身（実装）は変更しない**。本機能の主眼は「導線の統一」であり、各設定機能の挙動は不変。

> **段階適用（P2 → P4）**: P2 ではハブの index と導線追加（settings 系）を先に出し、P4 で member-* のリダイレクト整理＋E2E を行う。リダイレクトは既存 E2E を壊しうるため、E2E 修正とセットで後段に回す。

---

## 5. アクセス制御ミドルウェア `admin-console`

### 5.1 方針

- 新規ミドルウェア `frontend/app/middleware/admin-console.ts` を追加し、`/admin` 配下の全ページに適用する。
- 当該スコープで ADMIN / DEPUTY_ADMIN でないユーザーには **404 not-found（`abortNavigation` / `throw createError({ statusCode: 404 })`）** を返す（存在秘匿）。403 ではなく 404 とすることで、非管理者に「管理ページの存在」自体を漏らさない。
- ミドルウェアはスコープのロールを `useRoleAccess(scopeType, slug).loadPermissions()` で解決し、`isAdminOrDeputy` を判定する。ただし**これは UX（誤遷移の早期遮断）のための表示制御**であり、認可の最終判断ではない。BE は各 API で必ず `checkAdminOrAbove` を通す（[04](./04_security_authorization.md) §2）。

### 5.2 擬似コード

```typescript
// frontend/app/middleware/admin-console.ts
export default defineNuxtRouteMiddleware(async (to) => {
  const slug = String(to.params.slug)
  const scopeType = to.path.startsWith('/organizations/') ? 'organization' : 'team'
  const access = useRoleAccess(scopeType, slug)
  await access.loadPermissions()
  if (!access.isAdminOrDeputy.value) {
    // 存在秘匿: 403 ではなく 404
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
})
```

> `useRoleAccess` は `/api/v1/{teams|organizations}/{id}/me/permissions` を呼ぶ（実在 EP）。当該 EP は BE で `resolveEffectiveRoleName` を用いて両系統統合のロールを返すため、memberships 専属の DEPUTY も正しく判定される。

---

## 6. E2E テスト方針（P4）

- **ガード**: 非管理者（MEMBER）で `/teams/[slug]/admin` にアクセス → 404。ADMIN → ハブ表示。
- **導線**: ハブの各カード押下 → 対応 L3 へ遷移。
- **承認待ち**: `/admin/approvals` で集約 API のドメイン別件数が描画される（モックではなく実 BE で件数 0 でないことを確認 — メモリ `feedback_e2e_real_full_crud` 準拠）。
- **リダイレクト**: 旧 `member-cards` → 新 `/admin/members/cards` への転送。
- **IDOR**: 別組織の slug で `/organizations/{other}/admin` → 404。
