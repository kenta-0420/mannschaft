# F10.1.1 / 01: 管理コンソール ルート設計・既存ルート再編

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md) / [04_security_authorization.md](./04_security_authorization.md)

本書は L2/L3 管理コンソールのルート構成、各セクションの画面要件、既存の散在ルート（トップ直下の `reservations`/`budget`/`payments`/`matching`/`shifts`・`member-*`・`settings/*`）の取込・再編マッピングを定義する。

> **偵察に基づく事実確定（2026-06-17）**: ルートパラメータは全て **`[slug]`**（`[id]` ではない）。既存のトップ直下ルートは `teams/[slug]/reservations.vue` / `budget.vue` / `payments.vue` / `matching.vue` / `shifts.vue`、`organizations/[slug]/budget.vue` / `payments.vue` などが**実在**し、メンバー系は `member-cards.vue` / `member-profiles.vue` 等の**フラットファイル**（`members/` ディレクトリではない）として実在する。先行版が前提とした「member-* は members/ サブディレクトリへ移設」「budget は admin 配下にのみ新設」は実態と乖離していたため、本書で実在ルートに整合させた（§4）。

---

## 1. ルート全体像

```
/teams/[slug]/admin                          ← L2 ハブ（NEW）
  ├ /admin/reservations                      ← L3 予約確認（既存 reservations.vue を admin 配下へ再配置/リダイレクト）
  ├ /admin/budget                            ← L3 予算管理（既存 budget.vue を admin 配下へ再配置/リダイレクト）
  ├ /admin/approvals                         ← L3 横断承認待ち（NEW・集約API）
  ├ /admin/members                           ← L3 メンバー管理/統計（既存 member-* を集約・導線）
  └ /admin/settings                          ← L3 設定集約ハブ（既存 settings/* を導線集約）
      ├ shift / faq-settings / public-settings / care-overrides / todo-status-labels（既存ページへの導線）
      └ /admin/settings/modules             ← モジュール ON/OFF（admin/modules API）

/organizations/[slug]/admin                  ← L2 ハブ（index は NEW。point-cards はその配下に既存）
  ├ /admin/point-cards/*                     ← 既存（F18 Phase2）。ハブのカードから導線
  ├ /admin/budget                            ← L3 予算管理（既存 budget.vue を admin 配下へ再配置/リダイレクト）
  ├ /admin/payments                          ← L3 支払（既存 payments.vue を admin 配下へ再配置/リダイレクト）
  ├ /admin/approvals                         ← NEW・集約API（org は PAYMENT のみ集約・[03](./03_admin_action_required_api.md) §3.2）
  ├ /admin/members                           ← 既存 member-* を集約・導線
  └ /admin/settings                          ← faq-settings / notification-credits / public-settings / todo-status-labels への導線
```

> **注**: チームと組織はルート接頭辞（`/teams/[slug]` vs `/organizations/[slug]`）とレイアウト（`team` / `organization`）が異なるだけで、`/admin` 配下のセクション構成は概ね同型。ただし**スコープにより有効なセクションが異なる**（reservations/matching/shifts は team のみ、point-cards/payments の組織発行は org のみ）。コンポーネントは `scopeType` を prop で受けて共通化する。

---

## 2. L2 ハブページ設計

### 2.1 ファイル

| ルート | 新規/既存 | ファイル |
|--------|----------|---------|
| `/teams/[slug]/admin` | **新規** | `frontend/app/pages/teams/[slug]/admin/index.vue` |
| `/organizations/[slug]/admin` | **新規（インデックスのみ）** | `frontend/app/pages/organizations/[slug]/admin/index.vue` |

> `/organizations/[slug]/admin/point-cards/` は既に存在するが、`/organizations/[slug]/admin/index.vue`（ハブのトップ）は未整備のため新設する。point-cards はハブの1カードとして導線を張る。

### 2.2 ハブ UI 要件（新規標準作法 `admin-console`）【C4 根治・point-cards「踏襲」を撤回】

> **偵察結果（R4）に基づく訂正**: 先行版は「point-cards/index.vue の作法を踏襲」と書いたが、**point-cards は共通のアクセス制御作法を持たない**。実態は `definePageMeta({ layout, middleware: 'auth' })` ＋ ページ内で `orgStore.myOrganizations.find(o => String(o.id) === slug).role` を直接見て `canAccess` を算出し、非権限時は**リダイレクトも 404 もせず amber 警告ボックスを表示**するだけ（useRoleAccess も専用ミドルウェアも使っていない）。よって「point-cards 作法踏襲」は虚偽であり撤回する。

本機能は**新しい標準作法 `admin-console`** を定義し、`/admin` 配下の全ページに適用する（point-cards の既存実装は据え置く・[05](./05_decisions.md) §6）:

- `definePageMeta({ layout: 'team' | 'organization', middleware: ['auth', 'admin-console'] })`
- アクセス権は **ミドルウェア `admin-console`（§5）**で判定し、`useRoleAccess(scopeType, slug)` を使う。
- ページ本体では二重防御として `useRoleAccess` の `isAdminOrDeputy` でカード表示を制御。
- カテゴリ別アクションカードをグリッド表示（`grid gap-4 sm:grid-cols-2 lg:grid-cols-3`）。
- 各カードに「要対応件数バッジ」を表示（承認待ち集約 API のサマリを使用）。

> point-cards 配下を新作法へ寄せるか据え置くかは [05](./05_decisions.md) §6 で「**据え置く**（基盤改修を本機能に持ち込まない。新規ページのみ `admin-console` で統一）」と決定した。

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
| 予約（team のみ） | `/admin/reservations` | 承認待ち予約件数 | reservation モジュール有効 + ADMIN/DEPUTY |
| 予算 | `/admin/budget` | 超過カテゴリ数（あれば赤） | budget モジュール有効 + 予算閲覧権（ADMIN 無条件・DEPUTY は `BUDGET_VIEW`） |
| 承認待ち | `/admin/approvals` | `total_pending`（集約API・スコープ別ドメイン） | ADMIN/DEPUTY |
| メンバー | `/admin/members` | （入会申請バッジは team/org に該当機能が無いため出さない・[03](./03_admin_action_required_api.md) §3.2） | ADMIN/DEPUTY |
| 設定 | `/admin/settings` | なし | ADMIN（DEPUTY は権限グループ依存） |
| 支払（org のみ） | `/admin/payments` | 未収請求件数 | ADMIN/DEPUTY |
| ポイントカード（組織のみ） | `/admin/point-cards` | なし | F18 既存条件（`orgStore.myOrganizations.role`・据え置き） |

---

## 3. L3 セクション設計

### 3.1 予約確認 `/admin/reservations`（team のみ）

- **データソース（既存流用）**: `GET /api/v1/teams/{teamId}/reservations`（`?status=PENDING` で承認待ち抽出）。`TeamReservationController` が実在。**組織スコープの予約 API は存在しない**（`ReservationEntity` に `organization_id` 無し）ため、組織ハブに予約カードは出さない。
- **操作（既存流用）**: `POST .../confirm` `/cancel` `/complete` `/no-show` `/reschedule` `PATCH .../admin-note`。
- **画面**: 承認待ち（PENDING）を上部に強調、確定済み・完了・キャンセルをタブ切替。各予約に confirm/cancel/complete/no-show/reschedule のアクションボタン。
- **既存ルート**: `teams/[slug]/reservations.vue` が実在。これを admin 配下へリダイレクト（§4.1）。新規 API は不要。

### 3.2 予算管理 `/admin/budget`【budget 二重定義の解消】

- **データソース（既存流用）**: budget ドメインの既存 Controller 群（会計年度・配分・カテゴリ・取引・サマリ・レポート/CSV・設定）。
- **既存ルート**: `teams/[slug]/budget.vue` および `organizations/[slug]/budget.vue` が**実在**する。先行版は「admin/budget を新規ハブ画面として新設」とし既存 budget.vue を無視していた（二重定義）。**これを解消する**: 既存 `budget.vue` を正本（実装）として温存し、`/admin/budget` は**既存 budget.vue へのリダイレクト**とする（§4）。admin ハブのカードは `/admin/budget` を指し、リダイレクトで既存ページに着地する。新規の予算画面は作らない（実装重複を避ける）。
- **超過アラート**: サマリ API の超過フラグをそのまま表示（独自の閾値判定を FE/新APIで作らない）。
- **DEPUTY 解放ゲート**: 予算閲覧は ADMIN 無条件・DEPUTY は `BUDGET_VIEW` 保有時のみ（[04](./04_security_authorization.md) §4）。新規 API は不要。

### 3.3 横断承認待ち `/admin/approvals`

- **データソース（新規）**: `GET /api/v1/dashboard/{team|organization}/{id}/admin-action-required`（[03](./03_admin_action_required_api.md)）。**team は RESERVATION/SHIFT_REQUEST/MATCHING、org は PAYMENT のみ**を集約（スコープ別動的ドメイン・[03](./03_admin_action_required_api.md) §3.2）。
- **操作（既存流用）**: 各ドメインの承認/却下 API（予約 confirm/cancel、シフトリクエスト承認、マッチング承認）。
- **画面**: レスポンスの `domains` 配列をそのまま順に描画（固定枠を仮定しない）。各セクションは件数＋直近数件のプレビュー＋「すべて見る」で当該ドメインの一覧ページへ遷移。`degraded: true` のドメインは「集計失敗（再試行）」を 0 件と区別して表示する（[03](./03_admin_action_required_api.md) §4.3）。承認実行は各ドメインの既存 API を呼ぶ。

### 3.4 メンバー管理/統計 `/admin/members`

- **データソース（既存流用）**:
  - 一覧: `GET /api/v1/admin/dashboard/users?scope_type&scope_id`（F10.1 母体既存）
  - ロール変更: `PATCH /api/v1/admin/dashboard/users/{id}/role`（最後の ADMIN 自己降格防止は F10.1 母体既存ロジック）
  - 招待: 既存の招待 API（`InviteService`。team/org は招待方式のみで「入会申請」ドメインは存在しない・[03](./03_admin_action_required_api.md) §3.2）
  - 会員証/プロフィール/フィールド/情報: 既存 `member-cards`・`member-profiles`・`member-fields`・`member-info` 系ページ
  - 統計: `GET /api/v1/admin/dashboard` の `member_stats`（total/active/new_this_month）
- **画面**: 上部に member_stats サマリカード。下部にメンバー一覧（ロールバッジ・ロール変更・招待ボタン）。会員証/プロフィール/フィールド/情報は既存ページへの導線（サブタブまたはリンク）。
- **PII・退会中メンバーの扱い**: [04](./04_security_authorization.md) §6 に従う（匿名化済みは匿名表示・退会申請中（撤回可能）の区別含む）。
- **新規 API は不要**。

### 3.5 設定集約 `/admin/settings`

- 既存の `settings/*` 個別ルートを内包するハブ。各設定は既存ページをそのまま導線集約する（§4 再編マッピング）。
- DEPUTY_ADMIN の可否はセクションごとに権限グループで分岐（[04](./04_security_authorization.md) §4）。

---

## 4. 既存ルート再編マッピング表

既存の散在ルートを `/admin` ハブ配下に**論理的に集約**する。物理移設（ファイル移動）とリダイレクト（旧パス温存・転送）の2方式を機能ごとに使い分ける。実在ルートを全件反映した（R3 偵察）。

### 4.1 チーム（`teams/[slug]/`）

| 既存ルート（実在） | 再編後の位置 | 方式 | 根拠 |
|-----------|-------------|------|------|
| `reservations.vue` | `/admin/reservations` | リダイレクト（旧パス温存・`navigateTo` 転送） | 被リンク・ブックマーク保護 |
| `budget.vue` | `/admin/budget` | リダイレクト | 同上。実装は既存 budget.vue を正本に温存（§3.2・二重定義解消） |
| `payments.vue` | `/admin/payments` | リダイレクト | 同上 |
| `matching.vue` | `/admin/matching` | リダイレクト | 同上 |
| `shifts.vue` | `/admin/shifts` | リダイレクト | 同上 |
| `member-cards.vue` | `/admin/members`（会員証サブ導線） | リダイレクト | 被リンク保護 |
| `member-profiles.vue` | `/admin/members`（プロフィールサブ導線） | リダイレクト | 同上 |
| `member-fields.vue` | `/admin/members`（フィールドサブ導線） | リダイレクト | 同上 |
| `member-info.vue` | `/admin/members`（情報サブ導線） | リダイレクト | 同上 |
| `settings/shift.vue` | `/admin/settings`（導線） | 導線追加（物理移設なし） | 設定群はハブ index からリンク集約 |
| `settings/faq-settings.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |
| `settings/member-info.vue` | `/admin/members/info` への重複案内（導線） | 導線追加（重複は案内のみ。物理統合しない） | member-info の二重ルートはリンクで一本化案内 |
| `settings/public-settings.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |
| `settings/care-overrides.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |
| `settings/todo-status-labels.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |

### 4.2 組織（`organizations/[slug]/`）

| 既存ルート（実在） | 再編後の位置 | 方式 | 根拠 |
|-----------|-------------|------|------|
| `budget.vue` | `/admin/budget` | リダイレクト | 被リンク保護。実装は既存 budget.vue を正本に温存 |
| `payments.vue` | `/admin/payments` | リダイレクト | 同上 |
| `member-cards.vue` | `/admin/members`（会員証サブ導線） | リダイレクト | 被リンク保護。組織側は `member-cards.vue` / `member-profiles.vue` のみ実在（member-fields/info は組織側に無い） |
| `member-profiles.vue` | `/admin/members`（プロフィールサブ導線） | リダイレクト | 同上 |
| `admin/point-cards/*` | 現状維持（`/admin` ハブに導線追加のみ） | 導線追加 | 既に `/admin` 配下にあり再編不要。認可作法も据え置き（[05](./05_decisions.md) §6） |
| `settings/faq-settings.vue` | `/admin/settings`（導線） | 導線追加 | 設定群集約 |
| `settings/notification-credits.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |
| `settings/public-settings.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |
| `settings/todo-status-labels.vue` | `/admin/settings`（導線） | 導線追加 | 同上 |

> **組織側に存在しないルート（断定）**: `organizations/[slug]/reservations.vue` / `matching.vue` / `shifts.vue` / `member-fields.vue` / `member-info.vue` は**実在しない**。reservation/matching/shift は team 専用ドメインのため組織ハブにカードを出さない（[03](./03_admin_action_required_api.md) §3.2）。

### 4.3 再編方式の決定理由

- **リダイレクト方式（トップ直下の reservations/budget/payments/matching/shifts・member-*）**: 既存の被リンク・ブックマーク・他機能からのディープリンク・既存 E2E が多いルートは、旧パスを温存して `middleware`（または `definePageMeta` の `redirect`）で新パスへ転送する。旧パスを物理削除すると 404 が発生し既存 E2E・通知 URL を壊すため。
- **導線追加方式（settings/*）**: 設定群は被リンクが少なく、ハブの index ページからリンクを集約するだけで「散在解消」の目的を達成できる。物理移設はディレクトリ階層変更による import パス破壊リスクが大きいため避ける。
- いずれの方式も**既存ページの中身（実装）は変更しない**。本機能の主眼は「導線の統一」であり、各機能の挙動は不変。budget.vue は二重実装を作らず既存を正本とする（§3.2）。

> **段階適用（P2 → P4）**: P2 ではハブの index と導線追加（settings 系）を先に出し、P4 でトップ直下ルート・member-* のリダイレクト整理＋E2E を行う。リダイレクトは既存 E2E を壊しうるため、E2E 修正とセットで後段に回す。

---

## 5. アクセス制御ミドルウェア `admin-console`

### 5.1 方針

- 新規ミドルウェア `frontend/app/middleware/admin-console.ts` を追加し、`/admin` 配下の**新規ページ**に適用する（既存 point-cards は据え置き・[05](./05_decisions.md) §6）。
- ミドルウェアはスコープのロールを `useRoleAccess(scopeType, slug)` で解決し（**第2引数は slug 文字列**・R8 偵察で確定）、`isAdminOrDeputy` を判定する。
- **「権限不足（正常に false）」と「取得失敗（例外・タイムアウト）」を区別する**【取得失敗の握りつぶし解消】:
  - `useRoleAccess.loadPermissions()` は現状、内部で例外を**無言 catch して `roleName=null`** にする実装（R8 偵察で確認）。これをそのまま使うと「BE 障害でロールが取れなかった」場合に「権限なし」と区別できず、管理者を誤って弾く。
  - 本機能では `loadPermissions` が**読み込み成否を返す**よう拡張する（`{ ok: boolean }` 等。無言 catch を廃し、失敗時は `ok:false` を返す）。ミドルウェアは「`ok:true` かつ `isAdminOrDeputy=false`」→ アクセス拒否（§5.3）、「`ok:false`（取得失敗）」→ エラー画面/再試行へ誘導（握りつぶさない・[05](./05_decisions.md) §5）。
- これは UX（誤遷移の早期遮断）のための表示制御であり、認可の最終判断ではない。BE は各 API で必ず `checkAdminOrAbove` を通す（[04](./04_security_authorization.md) §2）。

### 5.2 アクセス拒否時の挙動（R9 プロジェクト慣習に整合）

> **偵察結果（R9）に基づく確定**: プロジェクトの管理ページは、権限不足時に **404 でブラウザを弾く慣習を持たない**。point-cards 系は `canAccess=false` で **amber 警告ボックスを表示**（リダイレクト・404 なし）、公開リソースの不在のみ `createError(404)`、システム管理は BE が 403。先行版の「非管理者に 404（存在秘匿）」はこの慣習と矛盾するため**撤回**する。

- `admin-console` ミドルウェアは、権限不足（`ok:true` かつ `isAdminOrDeputy=false`）の場合、**当該スコープのトップ（`/teams/[slug]` / `/organizations/[slug]`）へリダイレクトし、`notification.error` で「管理者権限が必要です」を表示**する（point-cards の amber 表示と同等の「正直に弾く」UX を、ページ遷移前のミドルウェアで実現）。**404 による存在秘匿はしない**（プロジェクト慣習に無く、BE 認可で実害が無いため。列挙防止の本丸は BE の scope 絞り込み＋F00 認可）。
- 取得失敗（`ok:false`）の場合は、エラー画面（再試行ボタン付き）を表示する（`createError({ statusCode: 503 })` 相当、または専用エラーコンポーネント）。「権限なし」へ倒さない。

### 5.3 擬似コード

```typescript
// frontend/app/middleware/admin-console.ts
export default defineNuxtRouteMiddleware(async (to) => {
  const slug = String(to.params.slug)
  const scopeType = to.path.startsWith('/organizations/') ? 'organization' : 'team'
  const access = useRoleAccess(scopeType, slug)   // 第2引数は slug 文字列（R8）
  const result = await access.loadPermissions()    // 拡張: { ok: boolean } を返す（無言 catch 廃止）

  if (!result.ok) {
    // 取得失敗（BE 障害等）。権限なしに倒さず、再試行へ
    throw createError({ statusCode: 503, statusMessage: 'permission_fetch_failed' })
  }
  if (!access.isAdminOrDeputy.value) {
    // 権限不足: プロジェクト慣習に従いスコープトップへ戻す（404 にしない）
    const { notify } = useNotification()
    notify.error(/* i18n: admin_required */)
    return navigateTo(scopeType === 'organization' ? `/organizations/${slug}` : `/teams/${slug}`)
  }
})
```

> `useRoleAccess` は `/api/v1/{teams|organizations}/{slug}/me/permissions` を呼ぶ（実在 EP・パスは **slug**）。当該 EP は BE で `resolveEffectiveRoleName` を用いて両系統統合のロールを返すため、memberships 専属の DEPUTY も正しく判定される。`isAdminOrDeputy` は SYSTEM_ADMIN でも true（R8）。SYSTEM_ADMIN の扱いは [04](./04_security_authorization.md) §2.1 を参照。

---

## 6. E2E テスト方針（P4）

- **ガード**: 非管理者（MEMBER）で `/teams/[slug]/admin` にアクセス → スコープトップへリダイレクト＋エラートースト（404 ではない）。ADMIN → ハブ表示。
- **取得失敗**: `me/permissions` が 503 を返すようモックし、`/admin` アクセス → エラー画面（再試行）。権限なし扱いに倒れないこと。
- **導線**: ハブの各カード押下 → 対応 L3 へ遷移。リダイレクト系（reservations/budget 等）は旧パスからの転送も検証。
- **承認待ち**: team の `/admin/approvals` で `RESERVATION`/`SHIFT_REQUEST`/`MATCHING` のドメイン別件数が描画される。org では `PAYMENT` のみ描画される（モックではなく実 BE で件数 0 でないことを確認 — メモリ `feedback_e2e_real_full_crud` 準拠）。
- **degraded**: 1ドメインの集計を一時障害にして `degraded` バッジが 0 件と区別表示されることを確認。
- **IDOR**: 別組織の slug で `/organizations/{other}/admin` → BE API が 403、FE はスコープトップへリダイレクト。
