import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-007〜008: パスワードリセット', () => {
  test('AUTH-007: メールアドレスを入力して送信すると完了画面が表示される', async ({ page }) => {
    await page.goto('/forgot-password')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_USER_EMAIL!, { delay: 10 })

    await page.getByRole('button', { name: 'リセットメールを送信' }).click()
    await expect(page.getByText('パスワードリセットのメールを送信しました')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('AUTH-007b: 未登録メールでも完了画面が表示される（情報漏洩防止）', async ({ page }) => {
    await page.goto('/forgot-password')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially('notexist@example.com', { delay: 10 })

    await page.getByRole('button', { name: 'リセットメールを送信' }).click()
    await expect(page.getByText('パスワードリセットのメールを送信しました')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('AUTH-008: tokenなしでリセットページにアクセスすると forgot-password にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/reset-password')
    await expect(page).toHaveURL(/\/forgot-password/, { timeout: 5_000 })
  })

  test('AUTH-008b: 無効なtokenでリセットを試みるとエラーが表示される', async ({ page }) => {
    await page.goto('/reset-password?token=invalidtoken')
    await waitForHydration(page)
    await expect(page.getByText('新しいパスワードを入力してください')).toBeVisible()

    const passwordInputs = page.locator('input[type="password"]')
    await passwordInputs.first().click()
    await passwordInputs.first().pressSequentially('NewPass2026!', { delay: 10 })
    // Tab away to close the PrimeVue Password strength overlay before clicking next field
    await page.keyboard.press('Tab')
    await passwordInputs.last().click()
    await passwordInputs.last().pressSequentially('NewPass2026!', { delay: 10 })

    await page.getByRole('button', { name: 'パスワードを変更' }).click()

    await expect(page.getByText('パスワードのリセットに失敗しました')).toBeVisible({
      timeout: 10_000,
    })
  })
})
