import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('SET-009〜016: 設定表示確認', () => {
  test('SET-009: 設定インデックスページが表示される', async ({ page }) => {
    await page.goto('/settings')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '設定' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('アカウント設定')).toBeVisible()
    await expect(page.locator('input[placeholder*="設定を検索"]')).toBeVisible()
  })

  test('SET-010: 外観設定ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/settings/appearance**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            theme: 'light',
            backgroundColor: null,
            backgroundImageUrl: null,
            sidebarStyle: 'full',
          },
        }),
      })
    })

    await page.goto('/settings/appearance')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '外観設定' })).toBeVisible({ timeout: 10_000 })
  })

  test('SET-011: メールアドレス変更ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { id: 1, displayName: 'テスト', email: 'test@example.com' } }),
      })
    })

    await page.goto('/settings/email')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'メールアドレス変更' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-012: アカウント連携ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/oauth**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/users/me/line/status**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { linked: false } }),
      })
    })

    await page.goto('/settings/linked-accounts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'アカウント連携' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-013: ログイン履歴ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/login-history**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              id: 1,
              ipAddress: '192.168.1.1',
              userAgent: 'Chrome on Windows',
              loginAt: '2026-04-04T10:00:00Z',
              success: true,
            },
          ],
          meta: { nextCursor: null, hasNext: false },
        }),
      })
    })

    await page.goto('/settings/login-history')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ログイン履歴' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('Chrome on Windows')).toBeVisible({ timeout: 5_000 })
  })

  test('SET-014: 通知設定ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/notification-preferences**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/notification-type-preferences**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/settings/notifications')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '通知設定' })).toBeVisible({ timeout: 10_000 })
  })

  test('SET-015: 言語・タイムゾーン設定ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { id: 1, locale: 'ja', timezone: 'Asia/Tokyo' } }),
      })
    })

    await page.goto('/settings/language')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '言語・タイムゾーン' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-016: QR会員証ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/member-cards/my**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/settings/member-cards')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'QR会員証' })).toBeVisible({ timeout: 10_000 })
  })
})
