import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * SYSADMIN-001〜003: SYSTEM ADMIN ダッシュボード
 *
 * このファイルは chromium-admin プロジェクト（admin.json storageState）で実行される。
 * SYSADMIN-001 のみ未認証状態でログインフローを確認するため storageState を上書き。
 */

test.describe('SYSADMIN-001: SYSTEM ADMIN ログイン後リダイレクト', () => {
  // 未認証状態でログインフローをテスト
  test.use({ storageState: { cookies: [], origins: [] } })

  test('SYSADMIN-001: SYSTEM_ADMIN ユーザーはログイン後に /system-admin へ遷移する', async ({
    page,
  }) => {
    await page.goto('/login')
    await waitForHydration(page)

    // メールアドレス入力
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_ADMIN_EMAIL!, { delay: 10 })

    // パスワード入力
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(process.env.TEST_ADMIN_PASSWORD!, { delay: 10 })

    // /api/v1/users/me をモックして systemRole: SYSTEM_ADMIN を返す
    await page.route('**/api/v1/users/me', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              locale: 'ja',
              systemRole: 'SYSTEM_ADMIN',
            },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // SYSTEM_ADMIN は /system-admin へリダイレクトされる
    await expect(page).toHaveURL(/\/system-admin/, { timeout: 15_000 })
  })
})

test.describe('SYSADMIN-002〜003: SYSTEM ADMIN ダッシュボード表示', () => {
  // API レスポンスをモックして表示確認
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/system-admin/moderation/dashboard**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            pendingReports: 3,
            pendingWarnings: 1,
            pendingUnflagRequests: 0,
            pendingReReviews: 2,
            autoHiddenToday: 5,
            bannedToday: 0,
            activeBlacklistEntries: 10,
          },
        }),
      })
    })

    await page.route('**/api/v1/system-admin/errors/stats**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            totalLast24h: 12,
            unresolvedCount: 4,
            criticalCount: 1,
            topErrors: [],
          },
        }),
      })
    })

    await page.route('**/api/v1/system-admin/batch-jobs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/v1/system-admin/moderation/warnings/re-review**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/v1/system-admin/moderation/unflag-requests**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('SYSADMIN-002: /system-admin ページが表示される', async ({ page }) => {
    await page.goto('/system-admin')
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.getByText('SYSTEM ADMIN').first()).toBeVisible({ timeout: 10_000 })
  })

  test('SYSADMIN-003: モデレーション KPI が表示される', async ({ page }) => {
    await page.goto('/system-admin')
    await waitForHydration(page)

    // KPI 数値が表示される（モック値: pendingReports=3）
    await expect(page.getByText('3').first()).toBeVisible({ timeout: 10_000 })
  })
})

test.describe('SYSADMIN-004: 未認証で /system-admin にアクセスするとログインへリダイレクト', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('SYSADMIN-004: 未認証アクセスは /login にリダイレクトされる', async ({ page }) => {
    await page.goto('/system-admin')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    await expect(page).toHaveURL(/redirect=/)
  })
})

test.describe('SYSADMIN-005: バックエンドが systemRole を返すか診断', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('SYSADMIN-005: /api/v1/users/me レスポンスに systemRole フィールドが含まれる', async ({
    page,
  }) => {
    // ログインして実際のトークンを取得
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_ADMIN_EMAIL!, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(process.env.TEST_ADMIN_PASSWORD!, { delay: 10 })

    // /api/v1/users/me のレスポンスをキャプチャ
    let capturedSystemRole: string | undefined
    await page.route('**/api/v1/users/me', async (route) => {
      const response = await route.fetch()
      const json = await response.json()
      capturedSystemRole = json?.data?.systemRole
      await route.fulfill({ response })
    })

    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15_000 })

    // systemRole フィールドが存在し、SYSTEM_ADMIN であること
    expect(
      capturedSystemRole,
      `バックエンドの /api/v1/users/me が systemRole を返していません。実際の値: ${capturedSystemRole}`,
    ).toBe('SYSTEM_ADMIN')
  })
})
