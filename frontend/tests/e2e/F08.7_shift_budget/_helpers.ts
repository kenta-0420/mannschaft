import type { Page } from '@playwright/test'
import type {
  AlertResponse,
  AllocationResponse,
  FailedEventResponse,
  FailedEventStatus,
} from '../../../app/types/shiftBudget'

/**
 * F08.7 シフト予算管理 admin 画面 E2E 共通モック（Phase 11-α）。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）。F13.1 流踏襲。</li>
 *   <li>fixture builder で snake_case の BE DTO に完全準拠（{@code AllocationResponse} /
 *       {@code AlertResponse} / {@code FailedEventResponse}）</li>
 *   <li>auth は localStorage に accessToken/currentUser を addInitScript で注入</li>
 *   <li>scope は localStorage の {@code currentScope} に組織スコープを注入
 *       （{@code useScopeStore.loadFromStorage} 経由で復元される）</li>
 *   <li>多テナント分離は API リクエストヘッダ {@code X-Organization-Id} 送出を検証</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F08.7_shift_budget_integration.md (v1.3) §6.2 / §7</p>
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const ORG_ID = 1
export const ALLOCATION_ID = 1001
export const ALERT_ID = 2001
export const FAILED_EVENT_ID = 3001

// 多テナント検証用の別組織
export const OTHER_ORG_ID = 99

// ---------------------------------------------------------------------------
// 認証セットアップ（管理者 / BUDGET_ADMIN 想定）
// ---------------------------------------------------------------------------

/**
 * Admin としてログイン済み状態をシミュレート。
 * tests/e2e/.auth/admin.json に依存せず、addInitScript で直接 localStorage に注入する。
 */
export async function setupAdminAuth(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV9hZG1pbn0.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 1,
        email: 'e2e-admin@example.com',
        displayName: 'E2E 管理者',
        profileImageUrl: null,
        systemRole: 'SYSTEM_ADMIN',
      }),
    )
  })
}

/**
 * 一般ユーザー（BUDGET_VIEW のみ、BUDGET_ADMIN なし）想定の認証。
 * バックエンドが 403 を返すケースを模擬する権限分岐テストで使用。
 */
export async function setupViewerAuth(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV92aWV3ZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 2,
        email: 'e2e-viewer@example.com',
        displayName: 'E2E 閲覧者',
        profileImageUrl: null,
      }),
    )
  })
}

// ---------------------------------------------------------------------------
// スコープセットアップ
// ---------------------------------------------------------------------------

/**
 * 組織スコープを localStorage に注入する。
 *
 * <p>useScopeStore は plugin {@code scope.client.ts} で起動時に
 * {@code loadFromStorage} を呼ぶため、addInitScript で localStorage に書き込めば
 * Pinia store に復元される。</p>
 *
 * <p>万一 plugin 経由の復元が間に合わない場合に備え、
 * {@link gotoWithScope} ヘルパで goto 後に store を直接設定するフォールバックも提供する。</p>
 */
export async function setupOrganizationScope(
  page: Page,
  orgId: number = ORG_ID,
  name = 'E2Eテスト組織',
): Promise<void> {
  await page.addInitScript((data) => {
    localStorage.setItem(
      'currentScope',
      JSON.stringify({ type: 'organization', id: data.orgId, name: data.name }),
    )
  }, { orgId, name })
}

/**
 * 個人スコープを注入する（組織スコープ未選択時の誘導メッセージ確認用）。
 */
export async function setupPersonalScope(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentScope',
      JSON.stringify({ type: 'personal', id: null, name: '個人' }),
    )
  })
}


// ---------------------------------------------------------------------------
// 共通 catch-all（未指定エンドポイントの 401/500 を防ぐ）
// ---------------------------------------------------------------------------

/**
 * すべての `/api/v1/**` を空 data で fulfill する catch-all。
 * 各 spec では本関数を最初に呼び、後から個別エンドポイントを上書きモックする
 * （Playwright の page.route は後勝ち）。
 */
export async function mockCatchAllApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: null }),
    })
  })
}

// ---------------------------------------------------------------------------
// fixture ビルダ（BE DTO snake_case に完全準拠）
// ---------------------------------------------------------------------------

/** {@code AllocationResponse} の雛形。 */
export function buildAllocation(
  overrides: Partial<AllocationResponse> = {},
): AllocationResponse {
  return {
    id: ALLOCATION_ID,
    organization_id: ORG_ID,
    team_id: 10,
    project_id: null,
    fiscal_year_id: 2026,
    budget_category_id: 100,
    period_start: '2026-04-01',
    period_end: '2026-04-30',
    allocated_amount: 100_000,
    consumed_amount: 30_000,
    confirmed_amount: 0,
    currency: 'JPY',
    note: 'E2E テスト用割当',
    created_by: 1,
    version: 1,
    created_at: '2026-04-01T00:00:00Z',
    updated_at: '2026-04-01T00:00:00Z',
    ...overrides,
  }
}

/** {@code AlertResponse} の雛形（未承認）。 */
export function buildAlert(overrides: Partial<AlertResponse> = {}): AlertResponse {
  return {
    id: ALERT_ID,
    allocation_id: ALLOCATION_ID,
    threshold_percent: 80,
    triggered_at: '2026-04-15T10:00:00Z',
    consumed_amount_at_trigger: 80_000,
    workflow_request_id: null,
    acknowledged_at: null,
    acknowledged_by: null,
    ...overrides,
  }
}

/** {@code FailedEventResponse} の雛形（PENDING）。 */
export function buildFailedEvent(
  overrides: Partial<FailedEventResponse> = {},
): FailedEventResponse {
  return {
    id: FAILED_EVENT_ID,
    organization_id: ORG_ID,
    event_type: 'NOTIFICATION_FAILED',
    source_id: ALLOCATION_ID,
    error_message: 'E2E テスト用 失敗イベント',
    retry_count: 0,
    last_retried_at: null,
    status: 'PENDING' as FailedEventStatus,
    created_at: '2026-04-15T10:00:00Z',
    updated_at: '2026-04-15T10:00:00Z',
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// 個別エンドポイントの便利モックヘルパ
// ---------------------------------------------------------------------------

/**
 * `/api/v1/shift-budget/allocations` (GET 一覧) をモックする。
 */
export async function mockAllocationsList(
  page: Page,
  items: AllocationResponse[],
  opts: { page?: number; size?: number; total?: number } = {},
): Promise<void> {
  await page.route('**/api/v1/shift-budget/allocations?**', async (route, request) => {
    if (request.method() !== 'GET') {
      await route.fallback()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          items,
          page: opts.page ?? 0,
          size: opts.size ?? 20,
          total: opts.total ?? items.length,
        },
      }),
    })
  })
}

/**
 * `/api/v1/shift-budget/alerts` (GET 一覧) をモックする。
 */
export async function mockAlertsList(
  page: Page,
  alerts: AlertResponse[],
): Promise<void> {
  await page.route('**/api/v1/shift-budget/alerts?**', async (route, request) => {
    if (request.method() !== 'GET') {
      await route.fallback()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: alerts }),
    })
  })
}

/**
 * `/api/v1/shift-budget/failed-events` (GET 一覧) をモックする。
 * status クエリで切り替えたい場合は呼び出し側で再モックする（page.route は後勝ち）。
 */
export async function mockFailedEventsList(
  page: Page,
  events: FailedEventResponse[],
): Promise<void> {
  await page.route('**/api/v1/shift-budget/failed-events?**', async (route, request) => {
    if (request.method() !== 'GET') {
      await route.fallback()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: events }),
    })
  })
}
