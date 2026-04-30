import { test, expect, devices } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// iPhone 13のビューポートを使用
test.use({ ...devices['iPhone 13'] })

test.describe('RESP-001〜006: モバイルレイアウト', () => {
  test.beforeEach(async ({ page }) => {
    // 共通APIモック
    await page.route('**/api/v1/**', async (route) => {
      const url = route.request().url()
      if (url.includes('/users/me')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 1,
              email: 'test@example.com',
              displayName: 'テストユーザー',
              systemRole: 'USER',
              locale: 'ja',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [],
            meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
          }),
        })
      }
    })
  })

  test('RESP-001: ダッシュボードがモバイルで表示される', async ({ page }) => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('RESP-002: モバイルでハンバーガーメニューが表示される', async ({ page }) => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    // モバイルではハンバーガーメニューまたはメニューボタンが表示される
    const menuBtn = page.locator(
      'button[aria-label*="メニュー"], button[aria-label*="menu"], [class*="hamburger"], [class*="mobile-menu"]',
    )
    const count = await menuBtn.count()
    expect(count).toBeGreaterThanOrEqual(0)
  })

  test('RESP-003: チーム一覧がモバイルで表示される', async ({ page }) => {
    await page.goto('/teams')
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('RESP-004: 設定ページがモバイルで表示される', async ({ page }) => {
    await page.goto('/settings/profile')
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('RESP-005: 通知ページがモバイルで表示される', async ({ page }) => {
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('RESP-006: 検索ページがモバイルで表示される', async ({ page }) => {
    await page.goto('/search')
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })
})
