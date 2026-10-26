import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-005〜006: 新規登録', () => {
  test('AUTH-005: 正しい情報で登録するとメール認証案内画面に遷移する', async ({ page }) => {
    const uniqueEmail = `e2e-reg-${Date.now()}@example.com`

    // 登録APIをモック（バックエンドの状態に依存しないようにする）
    await page.route('**/api/v1/auth/register', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: { message: 'Registration successful' } }),
      })
    })

    await page.goto('/register')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(uniqueEmail, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially('TestPass2026!', { delay: 10 })

    const postalCodeInput = page.locator('input#postalCode')
    await postalCodeInput.click()
    await postalCodeInput.pressSequentially('123-4567', { delay: 10 })

    const lastNameInput = page.locator('input#lastName')
    await lastNameInput.click()
    await lastNameInput.pressSequentially('テスト', { delay: 10 })

    const firstNameInput = page.locator('input#firstName')
    await firstNameInput.click()
    await firstNameInput.pressSequentially('太郎', { delay: 10 })

    const displayNameInput = page.locator('input#displayName')
    await displayNameInput.click()
    await displayNameInput.pressSequentially('テスト太郎', { delay: 10 })

    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(page).toHaveURL(/\/verify-email/, { timeout: 15_000 })
    await expect(page.getByRole('heading', { name: '確認メールを送信しました' })).toBeVisible()
  })

  test('AUTH-006: 必須項目が未入力だとバリデーションエラーが表示される', async ({ page }) => {
    await page.goto('/register')
    await waitForHydration(page)
    await page.getByRole('button', { name: 'アカウント作成' }).click()

    await expect(page.getByText('メールアドレスは必須です')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('姓は必須です')).toBeVisible()
    await expect(page).toHaveURL(/\/register/)
  })
})
