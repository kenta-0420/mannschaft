import type { Page } from '@playwright/test'
import type {
  AnnualReviewResponse,
  MonitoringVisitResponse,
  ResidenceStatusDashboard,
} from '../../../app/types/residenceStatus'

/**
 * F09.16 居住実態管理・見守り E2E テスト — 共通ヘルパー。
 *
 * repair-plan/helpers.ts のパターンを踏襲し、以下を提供する:
 * - 認証情報を localStorage に注入する setupAuth
 * - レイアウト共通 API をモックする setupLayoutMocks
 * - residence-status 固有 API をモックする setupResidenceStatusMocks
 * - fixture builder（ダッシュボード・年次更新・訪問記録）
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const DEFAULT_ORG_ID = 1
export const ADMIN_USER = { userId: 1, displayName: 'E2E Admin', role: 'ADMIN' } as const

// ---------------------------------------------------------------------------
// 型定義
// ---------------------------------------------------------------------------

export interface AuthRole {
  userId: number
  displayName: string
  role: 'ADMIN' | 'MEMBER' | 'GUEST'
}

// ---------------------------------------------------------------------------
// 認証注入
// ---------------------------------------------------------------------------

/**
 * localStorage に認証情報を注入する（token refresh をバイパス）。
 * repair-plan/helpers.ts の setupRepairPlanAuth と同じ方式。
 */
export async function setupAuth(page: Page, opts: AuthRole): Promise<void> {
  await page.addInitScript((args) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: args.userId,
        email: `e2e-${args.role.toLowerCase()}@example.com`,
        displayName: args.displayName,
        profileImageUrl: null,
        role: args.role,
      }),
    )
  }, opts)
}

// ---------------------------------------------------------------------------
// レイアウト共通モック
// ---------------------------------------------------------------------------

/**
 * レイアウト共通 API と認証 API をモックする。
 * auth.middleware.ts が呼び出す /api/v1/auth/me をモックして
 * ログインページへのリダイレクトを防ぐ。
 */
export async function setupLayoutMocks(page: Page, opts: AuthRole): Promise<void> {
  // OPTIONS プリフライトを一括処理
  await page.route('**/api/v1/**', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
          'Access-Control-Max-Age': '86400',
        },
      })
      return
    }
    await route.fallback()
  })

  // 認証確認（auth middleware が呼ぶ）
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          id: opts.userId,
          email: `e2e-${opts.role.toLowerCase()}@example.com`,
          displayName: opts.displayName,
          avatarUrl: null,
        },
      },
    })
  })

  // token refresh
  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          accessToken: 'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
          refreshToken: 'e2e-refresh-token-placeholder',
        },
      },
    })
  })

  // 未読通知数
  await page.route('**/api/v1/notifications/unread-count', async (route) => {
    await route.fulfill({ status: 200, json: { data: { total: 0 } } })
  })

  // チャットチャンネル
  await page.route('**/api/v1/chat/channels**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // メンション
  await page.route('**/api/v1/mentions**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // ロール・権限確認
  await page.route('**/api/v1/role-accesses**', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          role: opts.role,
          permissions: opts.role === 'ADMIN' ? ['READ', 'WRITE', 'MANAGE'] : ['READ'],
        },
      },
    })
  })

  // 通知一覧
  await page.route('**/api/v1/notifications**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // 組織情報
  await page.route(`**/api/v1/organizations/${DEFAULT_ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          id: DEFAULT_ORG_ID,
          name: 'E2Eテスト管理組合',
          description: 'E2Eテスト用組織',
        },
      },
    })
  })

  // 組織権限
  await page.route(`**/api/v1/organizations/${DEFAULT_ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          roleName: opts.role,
          permissions: opts.role === 'ADMIN' ? ['member.manage', 'READ', 'WRITE'] : ['READ'],
        },
      },
    })
  })
}

// ---------------------------------------------------------------------------
// residence-status 固有モックセットアップ
// ---------------------------------------------------------------------------

/**
 * residence-status ページ共通 API をモックする。
 * setupLayoutMocks の後に呼び出すこと。
 */
export async function setupResidenceStatusMocks(
  page: Page,
  orgId: number,
  opts: {
    dashboard?: Partial<ResidenceStatusDashboard>
    reviews?: AnnualReviewResponse[]
    visits?: MonitoringVisitResponse[]
  } = {},
): Promise<void> {
  const dashboard = buildDashboard(opts.dashboard)
  const reviews = opts.reviews ?? []
  const visits = opts.visits ?? []

  // ダッシュボード統計
  await page.route(`**/api/v1/organizations/${orgId}/residence-status/dashboard**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: dashboard } })
    } else {
      await route.fallback()
    }
  })

  // 年次更新一覧
  await page.route(`**/api/v1/organizations/${orgId}/residence-status/annual-reviews**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: reviews } })
    } else if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        json: {
          data: buildAnnualReview({ organizationId: orgId }),
        },
      })
    } else {
      await route.fallback()
    }
  })

  // 訪問記録一覧（委員会スコープ・居住者スコープ両方）
  await page.route(`**/api/v1/organizations/${orgId}/residence-status/monitoring-visits**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: visits } })
    } else if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        json: { data: buildMonitoringVisit({ organizationId: orgId }) },
      })
    } else {
      await route.fallback()
    }
  })

  // 横展開安否確認発動
  await page.route(`**/api/v1/organizations/${orgId}/residence-status/safety-checks**`, async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        json: {
          data: {
            id: 'safety-check-uuid-001',
            organizationId: orgId,
            safetyCheckId: null,
            triggeredBy: 1,
            triggeredAt: new Date().toISOString(),
            triggerReason: 'E2Eテスト',
            closedAt: null,
            createdAt: new Date().toISOString(),
          },
        },
      })
    } else {
      await route.fallback()
    }
  })
}

// ---------------------------------------------------------------------------
// fixture builder
// ---------------------------------------------------------------------------

/** ResidenceStatusDashboard の雛形。 */
export function buildDashboard(
  overrides: Partial<ResidenceStatusDashboard> = {},
): ResidenceStatusDashboard {
  return {
    organizationId: DEFAULT_ORG_ID,
    totalResidents: 120,
    highRiskCount: 5,
    midRiskCount: 18,
    lowRiskCount: 97,
    unresponsiveCount: 8,
    openAnnualReviewCount: 2,
    generatedAt: '2026-05-15T09:00:00Z',
    ...overrides,
  }
}

/** AnnualReviewResponse の雛形。 */
export function buildAnnualReview(
  overrides: Partial<AnnualReviewResponse> = {},
): AnnualReviewResponse {
  return {
    id: 'review-uuid-001',
    organizationId: DEFAULT_ORG_ID,
    targetYear: 2026,
    title: '2026年度 居住実態調査',
    deadlineDate: '2026-06-30',
    responseCount: 45,
    status: 'OPEN',
    closedAt: null,
    createdAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

/** MonitoringVisitResponse の雛形。 */
export function buildMonitoringVisit(
  overrides: Partial<MonitoringVisitResponse> = {},
): MonitoringVisitResponse {
  return {
    id: 'visit-uuid-001',
    organizationId: DEFAULT_ORG_ID,
    committeeId: 10,
    residentRegistryId: 1001,
    dwellingUnitId: 201,
    subjectUserId: 50,
    visitorUserId: 1,
    visitedAt: '2026-05-10T14:00:00Z',
    contactResult: 'MET',
    considerationMemo: '元気そうでした',
    nextVisitRecommendedAt: '2026-08-10T00:00:00Z',
    consentCovenantId: null,
    createdAt: '2026-05-10T14:30:00Z',
    ...overrides,
  }
}
