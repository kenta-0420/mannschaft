import { expect, test } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const NON_TARGET_USER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'f087-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}

test.describe('CMP-260913-1416 ダッシュボード再読込', () => {
  test.setTimeout(420_000)

  test('認証済みの非対象ユーザーが連続再読込してもダッシュボードを描画できる', async ({
    page,
  }) => {
    await loginViaApi(page, NON_TARGET_USER, { apiBaseUrl: API_BASE_URL })

    await page.goto('/dashboard', { waitUntil: 'commit' })
    // Nuxt dev の初回だけは SSR/client bundle のオンデマンドビルドに数分かかる。
    // ここは環境 warmup とし、修正対象の再読込は下のループで 60 秒上限を検証する。
    await expect(page.getByTestId('scope-carousel')).toBeVisible({ timeout: 300_000 })

    for (let attempt = 1; attempt <= 5; attempt += 1) {
      const startedAt = Date.now()
      await page.reload({ waitUntil: 'commit' })
      await expect(page, `再読込 ${attempt} 回目も /dashboard に留まる`).toHaveURL(/\/dashboard/)
      await expect(
        page.getByTestId('scope-carousel'),
        `再読込 ${attempt} 回目もダッシュボード本体を描画する`,
      ).toBeVisible({ timeout: 60_000 })
      console.log(`[CMP-260913-1416] authenticated reload ${attempt}: ${Date.now() - startedAt}ms`)
    }
  })

  test('未認証で再読込するとログイン画面へ戻る', async ({ page }) => {
    await page.goto('/dashboard', { waitUntil: 'commit' })
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 60_000 })
    const startedAt = Date.now()
    await page.reload({ waitUntil: 'commit' })

    await expect(page).toHaveURL(/\/login(?:\?|$)/, { timeout: 60_000 })
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 60_000 })
    console.log(`[CMP-260913-1416] unauthenticated reload: ${Date.now() - startedAt}ms`)
  })
})
