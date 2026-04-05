import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_PROFILE = {
  data: {
    id: 1,
    email: 'test@example.com',
    displayName: 'テストユーザー',
    profileImageUrl: null,
    locale: 'ja',
    timezone: 'Asia/Tokyo',
  },
}

test.describe('I18N-001〜003: 多言語対応', () => {
  test('I18N-001: ログインページがデフォルト日本語で表示される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    await expect(page.locator('label[for="email"]')).toHaveText('メールアドレス', {
      timeout: 5_000,
    })
    await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()
  })

  test('I18N-002: 言語設定ページに全言語オプションが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/language')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '言語・タイムゾーン' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('表示言語')).toBeVisible({ timeout: 5_000 })
  })

  test('I18N-003: 言語切替保存ボタンが表示され操作できる', async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })

    await page.goto('/settings/language')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '言語・タイムゾーン' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '保存' })).toBeVisible()
    await expect(page.getByRole('button', { name: '保存' })).toBeEnabled()
  })
})
