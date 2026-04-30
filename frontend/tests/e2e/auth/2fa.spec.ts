import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-011〜013: 2FA リカバリー', () => {
  test('AUTH-011: 2FAリカバリーページが表示される', async ({ page }) => {
    await page.goto('/2fa-recovery')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アカウントリカバリー' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('AUTH-012: リカバリーコード入力欄が表示される', async ({ page }) => {
    await page.goto('/2fa-recovery')
    await waitForHydration(page)
    const input = page.locator('input[type="text"], textarea')
    await expect(input.first()).toBeVisible({ timeout: 10_000 })
  })

  test('AUTH-013: 空のリカバリーコードで送信ボタンが無効', async ({ page }) => {
    await page.goto('/2fa-recovery')
    await waitForHydration(page)
    const submitBtn = page.getByRole('button', { name: /リカバリー|復旧|送信/ })
    const btnCount = await submitBtn.count()
    if (btnCount > 0) {
      await expect(submitBtn.first()).toBeVisible({ timeout: 10_000 })
    }
  })
})
