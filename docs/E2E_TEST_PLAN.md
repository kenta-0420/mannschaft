# Playwright E2E テスト法案（陣立て書）

> 作成日: 2026-04-06
> 対象: `frontend/tests/e2e/` 配下の Playwright E2E テスト

---

## 1. 偵察結果（現状分析）

### 1.1 既存カバレッジ

| カテゴリ | 全画面数 | テスト済 | 未テスト | カバレッジ |
|---------|---------|---------|---------|-----------|
| Auth（認証） | 9 | 7 | 2 | 78% |
| Settings（設定） | 18 | 14 | 4 | 78% |
| Global（横断機能） | 8 | 7 | 1 | 88% |
| My Pages（個人） | 6 | 6 | 0 | 100% |
| i18n | 2 | 2 | 0 | 100% |
| Security | 3 | 3 | 0 | 100% |
| Dashboard | 3 | 2 | 1 | 67% |
| Calendar | 1 | 1 | 0 | 100% |
| Contact | 3 | 3 | 0 | 100% |
| Teams（チーム） | 74 | 60 | 14 | 81% |
| Organizations（組織） | 60 | 5 | 55 | 8% |
| Admin（管理者） | 40 | 13 | 27 | 33% |
| System Admin | 2 | 1 | 1 | 50% |
| その他 | 8 | 4 | 4 | 50% |
| **合計** | **~207** | **~98** | **~109** | **47%** |

### 1.2 既存テストファイル（56 spec files）

```
tests/e2e/
├── admin/          (6) announcements, dashboard, display-pages, feature-flags, system-admin, users
├── auth/           (5) login, register, email-verify, password-reset, invite
├── calendar/       (1) calendar
├── contact/        (3) contact-handle-search, contact-invite, contacts
├── dashboard/      (2) blog-create, dashboard
├── error/          (1) network-error
├── global/         (5) chat, misc-pages, notifications, search, timeline
├── i18n/           (1) locale-switch
├── my/             (1) my-pages
├── organizations/  (4) advertiser, org-list, org-tournament, org-workflow
├── security/       (1) access-control
├── settings/       (8) contact-handle, contact-invite-tokens, contact-privacy,
│                       contact-request-blocks, display-settings, password, profile, security
└── teams/         (16) bulletin, chat, circulation, display-pages, events, files,
                        gallery, member-profiles, presence, reservations, schedule,
                        shifts, surveys, team-list, todos, voting
```

### 1.3 テスト基盤（既存インフラ）

| ファイル | 役割 |
|---------|------|
| `helpers/wait.ts` | `waitForHydration()` — Vue hydration 完了待ち |
| `fixtures/auth.ts` | `loginAs()` — 認証ユーティリティ |
| `setup/user.setup.ts` | 一般ユーザー認証状態保存 |
| `setup/admin.setup.ts` | 管理者認証状態保存 |
| `teams/helpers.ts` | `mockTeam()`, `mockTeamFeatureApis()` — チームAPIモック |

### 1.4 テストパターン

- **テストID規約**: `{AREA}-{NUMBER}`（例: `TEAM-001`, `ADMIN-005`, `SET-CNT-030`）
- **APIモック**: `page.route()` で全API応答をモック（バックエンド非依存）
- **PrimeVue対策**: `fill()` ではなく `click()` + `pressSequentially({ delay: 10 })` を使用
- **Hydration必須**: フォーム操作前に必ず `waitForHydration(page)` を呼出
- **レスポンス形式**: `{ data: T }` or `{ data: T[], meta: { page, size, totalElements, totalPages } }`

---

## 2. 陣立て（Phase 分け）

### Phase 1: 基盤強化・小規模残件（見込み: 4ファイル / 20テスト）

**目的**: テスト基盤の拡充 + カバレッジ100%に近いカテゴリの完遂

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 組織ヘルパー作成 | `organizations/helpers.ts` | — | `mockOrg()`, `mockOrgFeatureApis()` 作成 |
| 管理者ヘルパー拡充 | `admin/helpers.ts` | — | `mockAdminApis()` 共通モック |
| 認証完遂 | `auth/2fa.spec.ts` | 3 | 2FA リカバリーコード画面 |
| 設定完遂 | `settings/display-settings-extended.spec.ts` | 8 | account, calendar-sync, seals, social-profiles |
| その他残件 | `global/misc-pages-extended.spec.ts` | 5 | matching, social/[handle], system-admin/analytics |
| Blog編集 | `dashboard/blog-edit.spec.ts` | 4 | blog/posts/[id]/edit フロー |

**未テスト画面（対象）**:
- `/2fa-recovery`
- `/settings/account`
- `/settings/calendar-sync`
- `/settings/seals`
- `/settings/social-profiles`
- `/matching`
- `/social/[handle]`
- `/system-admin/analytics`
- `/blog/posts/[id]/edit`
- `/timeline/[postId]`

---

### Phase 2: 組織 表示テスト（見込み: 2ファイル / 50テスト）

**目的**: 組織画面の表示テストを一括作成（`teams/display-pages.spec.ts` と同パターン）

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 組織コア画面 | `organizations/display-pages.spec.ts` | 35 | 全組織画面の表示・見出し確認 |
| 組織広告主 | `organizations/advertiser-pages.spec.ts` | 6 | campaigns, invoices, rate-sim 等 |
| 組織ワークフロー詳細 | `organizations/org-workflow-detail.spec.ts` | 4 | workflows/[requestId], templates |
| 組織イベント | `organizations/org-events.spec.ts` | 5 | events/index, events/[eventId] |

**未テスト画面（対象 — 55画面）**:

<details>
<summary>全リスト展開</summary>

| # | パス | テストID |
|---|------|---------|
| 1 | `/organizations/[id]/activities` | ORG-DISP-001 |
| 2 | `/organizations/[id]/analytics` | ORG-DISP-002 |
| 3 | `/organizations/[id]/annual-plan` | ORG-DISP-003 |
| 4 | `/organizations/[id]/audit-logs` | ORG-DISP-004 |
| 5 | `/organizations/[id]/blog` | ORG-DISP-005 |
| 6 | `/organizations/[id]/budget` | ORG-DISP-006 |
| 7 | `/organizations/[id]/bulletin` | ORG-DISP-007 |
| 8 | `/organizations/[id]/chat` | ORG-DISP-008 |
| 9 | `/organizations/[id]/circulation` | ORG-DISP-009 |
| 10 | `/organizations/[id]/direct-mail` | ORG-DISP-010 |
| 11 | `/organizations/[id]/equipment` | ORG-DISP-011 |
| 12 | `/organizations/[id]/events` | ORG-DISP-012 |
| 13 | `/organizations/[id]/events/[eventId]` | ORG-DISP-013 |
| 14 | `/organizations/[id]/facilities` | ORG-DISP-014 |
| 15 | `/organizations/[id]/files` | ORG-DISP-015 |
| 16 | `/organizations/[id]/forms` | ORG-DISP-016 |
| 17 | `/organizations/[id]/forms/templates` | ORG-DISP-017 |
| 18 | `/organizations/[id]/gallery` | ORG-DISP-018 |
| 19 | `/organizations/[id]/gamification` | ORG-DISP-019 |
| 20 | `/organizations/[id]/incidents` | ORG-DISP-020 |
| 21 | `/organizations/[id]/kb` | ORG-DISP-021 |
| 22 | `/organizations/[id]/member-cards` | ORG-DISP-022 |
| 23 | `/organizations/[id]/member-profiles` | ORG-DISP-023 |
| 24 | `/organizations/[id]/onboarding` | ORG-DISP-024 |
| 25 | `/organizations/[id]/parking` | ORG-DISP-025 |
| 26 | `/organizations/[id]/payments` | ORG-DISP-026 |
| 27 | `/organizations/[id]/queue` | ORG-DISP-027 |
| 28 | `/organizations/[id]/residents` | ORG-DISP-028 |
| 29 | `/organizations/[id]/safety` | ORG-DISP-029 |
| 30 | `/organizations/[id]/schedule` | ORG-DISP-030 |
| 31 | `/organizations/[id]/signage` | ORG-DISP-031 |
| 32 | `/organizations/[id]/surveys` | ORG-DISP-032 |
| 33 | `/organizations/[id]/timeline` | ORG-DISP-033 |
| 34 | `/organizations/[id]/timeline-digest` | ORG-DISP-034 |
| 35 | `/organizations/[id]/timetable` | ORG-DISP-035 |
| 36 | `/organizations/[id]/todos` | ORG-DISP-036 |
| 37 | `/organizations/[id]/translations` | ORG-DISP-037 |
| 38 | `/organizations/[id]/voting` | ORG-DISP-038 |
| 39 | `/organizations/[id]/webhooks` | ORG-DISP-039 |
| 40 | `/organizations/[id]/workflows/[requestId]` | ORG-DISP-040 |
| 41 | `/organizations/[id]/workflows/templates` | ORG-DISP-041 |
| 42 | `/organizations/[id]/advertiser/campaigns/[campaignId]` | ORG-ADV-001 |
| 43 | `/organizations/[id]/advertiser/credit-limit-requests` | ORG-ADV-002 |
| 44 | `/organizations/[id]/advertiser/invoices` | ORG-ADV-003 |
| 45 | `/organizations/[id]/advertiser/rate-simulator` | ORG-ADV-004 |
| 46 | `/organizations/[id]/advertiser/report-schedules` | ORG-ADV-005 |

</details>

---

### Phase 3: 管理者 画面テスト（見込み: 3ファイル / 35テスト）

**目的**: 未テストの管理者画面27ページを網羅

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 管理者 表示テスト | `admin/display-pages-extended.spec.ts` | 27 | 全未テスト管理者画面の表示確認 |
| 課金・モジュール管理 | `admin/billing.spec.ts` | 4 | module-pricing, packages, org-billing, storage-plans |
| 外部連携設定 | `admin/integrations.spec.ts` | 4 | google-calendar, line-settings, sns-settings, affiliate |

**未テスト画面（対象 — 27画面）**:

| # | パス | テストID |
|---|------|---------|
| 1 | `/admin/ad-credit-limit-requests` | ADMIN-020 |
| 2 | `/admin/ad-rate-cards` | ADMIN-021 |
| 3 | `/admin/affiliate-settings` | ADMIN-022 |
| 4 | `/admin/appeals` | ADMIN-023 |
| 5 | `/admin/blog-management` | ADMIN-024 |
| 6 | `/admin/bulletin-categories` | ADMIN-025 |
| 7 | `/admin/campaigns` | ADMIN-026 |
| 8 | `/admin/equipment` | ADMIN-027 |
| 9 | `/admin/google-calendar` | ADMIN-028 |
| 10 | `/admin/line-settings` | ADMIN-029 |
| 11 | `/admin/member-permissions` | ADMIN-030 |
| 12 | `/admin/member-profiles` | ADMIN-031 |
| 13 | `/admin/module-pricing` | ADMIN-032 |
| 14 | `/admin/org-billing` | ADMIN-033 |
| 15 | `/admin/packages` | ADMIN-034 |
| 16 | `/admin/permission-groups` | ADMIN-035 |
| 17 | `/admin/promotions` | ADMIN-036 |
| 18 | `/admin/receipts` | ADMIN-037 |
| 19 | `/admin/reservation-settings` | ADMIN-038 |
| 20 | `/admin/schedule-settings` | ADMIN-039 |
| 21 | `/admin/seasonal-wallpapers` | ADMIN-040 |
| 22 | `/admin/sns-settings` | ADMIN-041 |
| 23 | `/admin/storage-plans` | ADMIN-042 |
| 24 | `/admin/tax-settings` | ADMIN-043 |

---

### Phase 4: チーム 動的ルート深掘り（見込み: 4ファイル / 30テスト）

**目的**: チームの動的パラメータ画面（`[eventId]`, `[projectId]`等）と未テスト画面

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| イベント詳細 | `teams/event-detail.spec.ts` | 5 | events/[eventId] 表示・参加・編集 |
| プロジェクト詳細 | `teams/project-detail.spec.ts` | 5 | projects/[projectId] タスク表示・更新 |
| TODO詳細 | `teams/todo-detail.spec.ts` | 4 | todos/[todoId] 詳細表示・ステータス変更 |
| チーム残件 | `teams/display-pages-extended.spec.ts` | 16 | 未テストチーム画面の表示確認 |

**未テスト画面（対象 — 14画面）**:

| # | パス | テストID |
|---|------|---------|
| 1 | `/teams/[id]/activity-stats` | TEAM-080 |
| 2 | `/teams/[id]/activity-templates` | TEAM-081 |
| 3 | `/teams/[id]/events/[eventId]` | TEAM-082 |
| 4 | `/teams/[id]/incidents` | TEAM-083 |
| 5 | `/teams/[id]/kb` | TEAM-084 |
| 6 | `/teams/[id]/member-fields` | TEAM-085 |
| 7 | `/teams/[id]/projects/[projectId]` | TEAM-086 |
| 8 | `/teams/[id]/signage` | TEAM-087 |
| 9 | `/teams/[id]/skills` | TEAM-088 |
| 10 | `/teams/[id]/timeline-digest` | TEAM-089 |
| 11 | `/teams/[id]/todos/[todoId]` | TEAM-090 |
| 12 | `/teams/[id]/tournaments` | TEAM-091 |
| 13 | `/teams/[id]/webhooks` | TEAM-092 |
| 14 | `/teams/[id]/workflows/[requestId]` | TEAM-093 |

---

### Phase 5: 組織 機能テスト（見込み: 8ファイル / 40テスト）

**目的**: Phase 2 の表示テストを補完し、主要な組織機能の操作テストを追加

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 組織スケジュール | `organizations/org-schedule.spec.ts` | 5 | 予定作成・編集・削除 |
| 組織掲示板 | `organizations/org-bulletin.spec.ts` | 4 | 投稿作成・固定・削除 |
| 組織チャット | `organizations/org-chat.spec.ts` | 4 | メッセージ送信・スレッド |
| 組織イベント | `organizations/org-events.spec.ts` | 5 | イベントCRUD・参加管理 |
| 組織タイムライン | `organizations/org-timeline.spec.ts` | 4 | 投稿・いいね・コメント |
| 組織フォーム | `organizations/org-forms.spec.ts` | 4 | テンプレート選択・回答 |
| 組織ファイル | `organizations/org-files.spec.ts` | 4 | アップロード・共有 |
| 組織投票 | `organizations/org-voting.spec.ts` | 4 | 投票作成・集計表示 |
| 組織アンケート | `organizations/org-surveys.spec.ts` | 4 | アンケート作成・回答 |
| 組織回覧板 | `organizations/org-circulation.spec.ts` | 4 | 回覧作成・確認 |

---

### Phase 6: 横断シナリオ・品質強化（見込み: 5ファイル / 25テスト）

**目的**: 複数画面を跨ぐユーザーシナリオテスト + エッジケース

| 作業 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 登録→ログイン→チーム作成 | `scenarios/onboarding-flow.spec.ts` | 5 | 新規ユーザーのオンボーディング全体 |
| 権限変更→画面制御 | `scenarios/role-access-flow.spec.ts` | 4 | ロール変更後のUI制御確認 |
| 多言語フロー | `scenarios/i18n-flow.spec.ts` | 4 | 言語切替→各画面の翻訳確認 |
| エラーハンドリング | `error/api-errors.spec.ts` | 6 | 403/404/500 各パターン |
| モバイル表示 | `responsive/mobile-layout.spec.ts` | 6 | 主要画面のモバイル表示確認 |

---

## 3. 進軍の順序

```
Phase 1 ─── 基盤強化・小規模残件 ──────── 4ファイル / 20テスト
  │          helpers作成 + 認証/設定完遂
  │          ※ 全後続Phaseの土台
  ▼
Phase 2 ─── 組織 表示テスト ────────────── 2ファイル / 50テスト  ←★最大インパクト
  │          display-pages パターンで一括作成
  │          カバレッジ 47% → 72% へ跳躍
  │
  ├──────── Phase 3 ── 管理者画面テスト ── 3ファイル / 35テスト  （Phase 2と並列可）
  │          admin display-pages 拡張
  │
  ▼
Phase 4 ─── チーム 動的ルート深掘り ────── 4ファイル / 30テスト
  │          [eventId], [projectId] 等の詳細画面
  │
  ▼
Phase 5 ─── 組織 機能テスト ────────────── 8ファイル / 40テスト
  │          Phase 2の表示テストを深掘り
  │          主要操作（CRUD）の検証
  │
  ▼
Phase 6 ─── 横断シナリオ・品質強化 ────── 5ファイル / 25テスト
             複数画面を跨ぐE2Eフロー
             エラーハンドリング・レスポンシブ
```

### 並列実行可能な組み合わせ

| 並列グループ | Phase | 理由 |
|-------------|-------|------|
| A | Phase 2 + Phase 3 | 組織テストと管理者テストは独立 |
| B | Phase 4 + Phase 5 | チーム深掘りと組織機能は独立 |

### 大名システムでの実行方法

各Phaseの実行時は以下の手順:

1. **軍議**: 対象Phaseの仕様確認（本ドキュメント参照）
2. **出陣**: 足軽（並列エージェント）にファイル単位で実装を指示
   - 足軽A: `display-pages.spec.ts` 系（表示テスト一括）
   - 足軽B: 個別機能テスト
   - 足軽C: ヘルパー・フィクスチャ
3. **検分**: 作成されたテストの品質レビュー
4. **巡回**: `npx playwright test` で全テスト実行・結果確認

---

## 4. テスト作成規約

### 4.1 ファイル配置

```
tests/e2e/
├── {category}/
│   ├── helpers.ts              # カテゴリ固有ヘルパー
│   ├── {feature}.spec.ts       # 機能テスト
│   └── display-pages.spec.ts   # 表示一括テスト
├── scenarios/                  # 横断シナリオ（Phase 6）
├── responsive/                 # レスポンシブテスト（Phase 6）
├── helpers/
│   └── wait.ts                 # 共通ヘルパー
├── fixtures/
│   └── auth.ts                 # 認証フィクスチャ
└── setup/
    ├── user.setup.ts
    └── admin.setup.ts
```

### 4.2 テストID採番ルール

| カテゴリ | Prefix | 開始番号 | 例 |
|---------|--------|---------|-----|
| Auth | `AUTH-` | 001 | `AUTH-011`（既存: 001-010） |
| Team | `TEAM-` | 080 | `TEAM-080`（既存: 001-074） |
| Org display | `ORG-DISP-` | 001 | `ORG-DISP-001` |
| Org feature | `ORG-FEAT-` | 001 | `ORG-FEAT-001` |
| Admin | `ADMIN-` | 020 | `ADMIN-020`（既存: 001-017） |
| Settings | `SET-` | 020 | `SET-020`（既存: 001-016） |
| Global | `GLOBAL-` | 012 | `GLOBAL-012`（既存: 001-011） |
| Scenario | `SCEN-` | 001 | `SCEN-001` |
| Responsive | `RESP-` | 001 | `RESP-001` |

### 4.3 テストテンプレート

#### 表示テスト（display-pages パターン）

```typescript
import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-DISP-001〜041: 組織画面 表示テスト', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
  })

  test('ORG-DISP-001: 組織アクティビティ画面が表示される', async ({ page }) => {
    await page.goto('/organizations/1/activities')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /アクティビティ/ }))
      .toBeVisible({ timeout: 10_000 })
  })
  // ... 以下同パターンで全画面
})
```

#### 機能テスト（操作テストパターン）

```typescript
import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-FEAT-001〜005: 組織スケジュール', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    // 個別のAPIモック
    await page.route('**/api/v1/organizations/1/schedules**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [...], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 } }),
        })
      } else {
        await route.fulfill({ status: 201, body: JSON.stringify({ data: { id: 1 } }) })
      }
    })
  })

  test('ORG-FEAT-001: スケジュール一覧が表示される', async ({ page }) => {
    await page.goto('/organizations/1/schedule')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /スケジュール/ })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-FEAT-002: 新規予定作成ダイアログが開く', async ({ page }) => {
    await page.goto('/organizations/1/schedule')
    await waitForHydration(page)
    await page.getByRole('button', { name: /作成|追加/ }).click()
    await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 5_000 })
  })
})
```

---

## 5. カバレッジ目標

| Phase完了時 | 推定カバレッジ | テスト総数 |
|------------|-------------|-----------|
| 現状 | 47% (98/207) | 56 spec |
| Phase 1 完了 | 52% (108/207) | 62 spec |
| Phase 2 完了 | 72% (150/207) | 64 spec |
| Phase 3 完了 | 85% (176/207) | 67 spec |
| Phase 4 完了 | 92% (190/207) | 71 spec |
| Phase 5 完了 | 92%（深度向上） | 81 spec |
| Phase 6 完了 | 95%+ (197/207) | 86 spec |

---

## 6. 懸念・確認事項

1. **バックエンド非依存**: 全テストがAPIモックで動作する前提。実APIとの乖離リスクあり
2. **PrimeVue互換**: `fill()` が使えない制約は継続。新コンポーネント追加時に注意
3. **Hydration待ち**: Nuxt SSR/SPA切替ページで `waitForHydration` が効かない場合あり
4. **テスト実行時間**: 全86 spec実行時のCI所要時間を要監視（現在30秒timeout × 56ファイル）
5. **組織ヘルパー**: `teams/helpers.ts` と同等の `organizations/helpers.ts` が未作成 → Phase 1 で対応

---

## 7. 進捗記録

| Phase | 状態 | 完了日 | spec数 | カバレッジ |
|-------|------|-------|--------|-----------|
| Phase 1: 基盤強化・小規模残件 | 完了 | 2026-04-06 | +6 (62 spec) | 52% |
| Phase 2: 組織 表示テスト | 完了 | 2026-04-06 | +2 (64 spec) | 72% |
| Phase 3: 管理者 画面テスト | 完了 | 2026-04-06 | +2 (66 spec) | 85% |
| Phase 4: チーム 動的ルート深掘り | 完了 | 2026-04-06 | +1 (67 spec) | 92% |
| Phase 5: 組織 機能テスト | 完了 | 2026-04-06 | +10 (77 spec) | 92%(深度↑) |
| Phase 6: 横断シナリオ・品質強化 | 完了 | 2026-04-06 | +3 (80 spec) | 95%+ |
