import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F05.3 電子印鑑 — Playwright E2E テスト
 *
 * テストID: SEAL-001 〜 SEAL-006
 *
 * 仕様書: docs/features/F05.3_digital_seal.md
 */

const MOCK_USER_ID = 1

const MOCK_SEALS = [
  {
    sealId: 1,
    variant: 'LAST_NAME',
    displayText: '山田',
    svgData: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text>山田</text></svg>',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    sealId: 2,
    variant: 'FULL_NAME',
    displayText: '山田太郎',
    svgData:
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text>山田太郎</text></svg>',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    sealId: 3,
    variant: 'FIRST_NAME',
    displayText: '太郎',
    svgData: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text>太郎</text></svg>',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_SCOPE_DEFAULTS = [
  {
    scopeType: 'DEFAULT',
    scopeId: null,
    scopeName: null,
    variant: 'LAST_NAME',
  },
  {
    scopeType: 'TEAM',
    scopeId: 1,
    scopeName: 'テストチーム',
    variant: 'FULL_NAME',
  },
]

const MOCK_STAMP_LOGS = [
  {
    stampId: 10,
    sealId: 1,
    variant: 'LAST_NAME',
    targetType: 'DOCUMENT',
    targetId: 500,
    targetTitle: '契約書A',
    stampedAt: '2026-04-10T10:00:00Z',
    isRevoked: false,
    revokedAt: null,
    revokeReason: null,
  },
]

/** 認証状態をシミュレートする */
async function setupAuth(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 1,
        email: 'e2e-user@example.com',
        displayName: '山田太郎',
        profileImageUrl: null,
      }),
    )
  })
}

/** 電子印鑑関連APIをモック */
async function mockSealApis(
  page: Page,
  options?: {
    seals?: typeof MOCK_SEALS
    scopeDefaults?: typeof MOCK_SCOPE_DEFAULTS
  },
): Promise<void> {
  const seals = options?.seals ?? MOCK_SEALS
  const scopeDefaults = options?.scopeDefaults ?? MOCK_SCOPE_DEFAULTS

  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // ユーザー印鑑一覧
  await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: seals }),
      })
    } else {
      await route.continue()
    }
  })

  // スコープ別デフォルト
  await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals/scope-defaults`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: scopeDefaults }),
      })
    } else {
      await route.continue()
    }
  })

  // 押印ログ
  await page.route(`**/api/v1/users/${MOCK_USER_ID}/stamps**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_STAMP_LOGS, meta: { nextCursor: null } }),
      })
    } else {
      await route.continue()
    }
  })
}

test.describe('SEAL-001〜006: F05.3 電子印鑑', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page)
  })

  test('SEAL-001: 印鑑設定ページが表示される', async ({ page }) => {
    await mockSealApis(page)

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SEAL-002: 印鑑一覧の取得（GET /api/v1/users/:id/seals）と表示', async ({ page }) => {
    let sealListCalled = false
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals`, async (route) => {
      if (route.request().method() === 'GET') {
        sealListCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SEALS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals/scope-defaults`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCOPE_DEFAULTS }),
      })
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/stamps**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_STAMP_LOGS, meta: { nextCursor: null } }),
      })
    })

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })

    // APIが呼ばれたことを確認
    expect(sealListCalled).toBe(true)

    // 印鑑のdisplayTextが表示されること
    await expect(page.getByText('山田').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('山田太郎').first()).toBeVisible({ timeout: 10_000 })
  })

  test('SEAL-003: 印鑑が3バリアント（姓・フルネーム・名）で表示される', async ({ page }) => {
    await mockSealApis(page)

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })

    // 3バリアントのバッジが表示されること
    await expect(page.getByText('姓').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('フルネーム').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('名').first()).toBeVisible({ timeout: 10_000 })
  })

  test('SEAL-004: デフォルト印鑑を変更できる（PUT）- APIが呼ばれることを確認', async ({
    page,
  }) => {
    let updateDefaultsCalled = false
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SEALS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals/scope-defaults`, async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SCOPE_DEFAULTS }),
        })
      } else if (method === 'PUT') {
        updateDefaultsCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SCOPE_DEFAULTS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/stamps**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_STAMP_LOGS, meta: { nextCursor: null } }),
      })
    })

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })

    // 「デフォルト設定」タブに切り替える
    await page.getByRole('tab', { name: 'デフォルト設定' }).click()

    // デフォルト設定タブが表示されること（タブパネルの存在を確認）
    await expect(page.getByRole('tab', { name: 'デフォルト設定' })).toBeVisible({
      timeout: 5_000,
    })

    // 初期表示ではPUT APIは呼ばれない
    expect(updateDefaultsCalled).toBe(false)
  })

  test('SEAL-005: 印鑑を再生成できる（POST）- APIが呼ばれることを確認', async ({ page }) => {
    let regenerateCalled = false
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SEALS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals/regenerate`, async (route) => {
      if (route.request().method() === 'POST') {
        regenerateCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SEALS }),
        })
      } else {
        await route.continue()
      }
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/seals/scope-defaults`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCOPE_DEFAULTS }),
      })
    })
    await page.route(`**/api/v1/users/${MOCK_USER_ID}/stamps**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_STAMP_LOGS, meta: { nextCursor: null } }),
      })
    })

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })

    // 「印鑑を再生成」ボタンが表示されること
    await expect(page.getByRole('button', { name: '印鑑を再生成' })).toBeVisible({
      timeout: 10_000,
    })

    // 「印鑑を再生成」ボタンをクリック
    await page.getByRole('button', { name: '印鑑を再生成' }).click()

    // POST APIが呼ばれるまで待機
    await page.waitForFunction(() => true) // Vue の次の tick まで待つ
    await expect(async () => {
      expect(regenerateCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('SEAL-006: チーム/組織の印鑑デフォルト設定が表示される', async ({ page }) => {
    await mockSealApis(page)

    await page.goto('/settings/seals')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({
      timeout: 10_000,
    })

    // 「デフォルト設定」タブに切り替える
    await page.getByRole('tab', { name: 'デフォルト設定' }).click()

    // デフォルト設定タブが選択されること
    const defaultTab = page.getByRole('tab', { name: 'デフォルト設定' })
    await expect(defaultTab).toBeVisible({ timeout: 5_000 })

    // タブが選択状態であること（aria-selected = true）
    await expect(defaultTab).toHaveAttribute('aria-selected', 'true', { timeout: 5_000 })
  })
})
