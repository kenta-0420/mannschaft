import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('ERR-001: ネットワークエラー処理', () => {
  test('ERR-001: APIエラー時にエラーメッセージが表示される', async ({ page }) => {
    // ログインAPIを認証エラー(422)で返す
    await page.route('**/api/v1/auth/login**', async (route) => {
      await route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Invalid credentials', code: 'AUTH_001' }),
      })
    })

    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially('test@example.com', { delay: 10 })

    const passwordInput = page.locator('input[type="password"]').first()
    await passwordInput.click()
    await passwordInput.pressSequentially('wrongpassword', { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // エラートーストが表示されることを確認 (PrimeVue Toast の summary 要素)
    await expect(
      page.locator('.p-toast-summary').filter({ hasText: 'ログインに失敗しました' }),
    ).toBeVisible({ timeout: 10_000 })
  })
})
