import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('SET-020〜023: 設定画面 追加表示テスト', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1, email: 'test@example.com', displayName: 'テストユーザー',
            avatarUrl: null, systemRole: 'USER', locale: 'ja',
          },
        }),
      })
    })
    await page.route('**/api/v1/**', async (route) => {
      const url = route.request().url()
      if (url.includes('/users/me')) {
        await route.continue()
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
        })
      }
    })
  })

  test('SET-020: アカウント設定ページが表示される', async ({ page }) => {
    await page.goto('/settings/account')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アカウント設定' })).toBeVisible({ timeout: 10_000 })
  })

  test('SET-021: Google Calendar 連携ページが表示される', async ({ page }) => {
    await page.goto('/settings/calendar-sync')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /Google Calendar|カレンダー連携/ })).toBeVisible({ timeout: 10_000 })
  })

  test('SET-022: 電子印鑑ページが表示される', async ({ page }) => {
    await page.goto('/settings/seals')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '電子印鑑' })).toBeVisible({ timeout: 10_000 })
  })

  test('SET-023: ソーシャルプロフィールページが表示される', async ({ page }) => {
    await page.goto('/settings/social-profiles')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ソーシャルプロフィール' })).toBeVisible({ timeout: 10_000 })
  })
})
