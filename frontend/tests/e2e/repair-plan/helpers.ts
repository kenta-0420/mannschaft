import type { Page } from '@playwright/test'

/**
 * F08.8 Phase 6 E2E テスト — 共通モックヘルパー。
 *
 * repair-plan テストで使う認証・レイアウト API をまとめてセットアップする。
 *
 * Playwright では後から登録した route が優先されるため、
 * 本ヘルパーを先に呼び出してから spec 固有の route を追加すること。
 */

export interface AuthRole {
  userId: number
  displayName: string
  role: 'ADMIN' | 'MEMBER' | 'GUEST'
}

/**
 * localStorage に認証情報を注入する（token refresh をバイパス）。
 * action-memo-mocks.ts の setupAuth と同じ方式。
 */
export async function setupRepairPlanAuth(page: Page, opts: AuthRole): Promise<void> {
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

  // レイアウト周辺 API
  await page.route('**/api/v1/notifications/unread-count', async (route) => {
    await route.fulfill({ status: 200, json: { data: { total: 0 } } })
  })

  await page.route('**/api/v1/chat/channels**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

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

  // 未読通知
  await page.route('**/api/v1/notifications**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })
}

/**
 * repair-plan ページで必要な共通 API をモックする。
 * setupLayoutMocks と組み合わせて使う。
 */
export async function setupRepairPlanPageMocks(
  page: Page,
  teamId: number,
  opts: {
    organizationId?: number
    role?: 'ADMIN' | 'MEMBER' | 'GUEST'
    kanbans?: unknown[]
    terms?: unknown[]
    timelineData?: unknown
  } = {},
): Promise<void> {
  const orgId = opts.organizationId ?? 100
  const role = opts.role ?? 'ADMIN'
  const kanbans = opts.kanbans ?? []
  const terms = opts.terms ?? []
  const timelineData = opts.timelineData ?? {
    scopeType: 'teams',
    scopeId: teamId,
    yearFrom: 2005,
    yearTo: 2030,
    labels: [],
    categories: [],
    amountByYearAndCategory: {},
    totalByYear: {},
    chairpersonByYear: {},
    cpiTrendByYear: {},
  }

  // チーム所属組織（organizationId 取得に必須）
  await page.route(`**/api/v1/teams/${teamId}/organizations`, async (route) => {
    await route.fulfill({
      status: 200,
      json: { data: [{ id: orgId, name: 'テスト管理組合' }] },
    })
  })

  // チーム権限
  await page.route(`**/api/v1/teams/${teamId}/members/me`, async (route) => {
    await route.fulfill({ status: 200, json: { data: { role, userId: 1 } } })
  })

  // useRoleAccess が呼び出す権限 API
  await page.route(`**/api/v1/teams/${teamId}/me/permissions`, async (route) => {
    const permissions =
      role === 'ADMIN'
        ? ['READ', 'WRITE', 'MANAGE', 'ADMIN']
        : role === 'MEMBER'
          ? ['READ']
          : []
    await route.fulfill({
      status: 200,
      json: { data: { roleName: role, permissions } },
    })
  })

  // タイムライン
  await page.route(`**/api/v1/teams/${teamId}/repair-plan/timeline**`, async (route) => {
    await route.fulfill({ status: 200, json: { data: timelineData } })
  })

  // カンバン一覧（GET のみ、POST は個別 spec で追加）
  await page.route(`**/api/v1/teams/${teamId}/repair-plan/quote-kanbans`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: kanbans } })
    } else {
      await route.fallback()
    }
  })

  // 任期一覧
  await page.route(`**/api/v1/teams/${teamId}/member-terms`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: terms } })
    } else {
      await route.fallback()
    }
  })

  // 申し送りパック一覧
  await page.route(`**/api/v1/teams/${teamId}/repair-plan/handover-packs`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // チームメンバー
  await page.route(`**/api/v1/teams/${teamId}/members**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        json: { data: [], meta: { total: 0, page: 0, size: 200 } },
      })
    } else {
      await route.fallback()
    }
  })
}
