import { expect, test } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const NON_TARGET_USER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'f087-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}

test.describe('CMP-260913-1416 ダッシュボード再読込', () => {
  test.setTimeout(90_000)

  test('認証済みの非対象ユーザーが連続再読込してもダッシュボードを描画できる', async ({
    page,
  }) => {
    await loginViaApi(page, NON_TARGET_USER, { apiBaseUrl: API_BASE_URL })

    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('scope-carousel')).toBeVisible({ timeout: 15_000 })

    for (let attempt = 1; attempt <= 5; attempt += 1) {
      await page.reload({ waitUntil: 'domcontentloaded' })
      await expect(page, `再読込 ${attempt} 回目も /dashboard に留まる`).toHaveURL(/\/dashboard/)
      await expect(
        page.getByTestId('scope-carousel'),
        `再読込 ${attempt} 回目もダッシュボード本体を描画する`,
      ).toBeVisible({ timeout: 15_000 })
    }
  })

  test('未認証で再読込するとログイン画面へ戻る', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await page.reload({ waitUntil: 'domcontentloaded' })

    await expect(page).toHaveURL(/\/login(?:\?|$)/, { timeout: 15_000 })
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 15_000 })
  })
})
