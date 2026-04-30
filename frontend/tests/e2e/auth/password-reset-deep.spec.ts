import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, fillPassword } from '../helpers/form'

test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-DEEP password-reset: パスワードリセットフォーム深掘り', () => {
  test('DEEP-PWRESET-001: forgot-password で空送信すると「メールアドレスは必須です」エラーが表示される', async ({
    page,
  }) => {
    await page.goto('/forgot-password')
    await waitForHydration(page)
    await page.getByRole('button', { name: 'リセットメールを送信' }).click()

    await expect(page.getByText('メールアドレスは必須です')).toBeVisible({ timeout: 5_000 })
    await expect(page).toHaveURL(/\/forgot-password/)
  })

  test('DEEP-PWRESET-002: forgot-password で不正な email 形式を送信するとフォーマットエラーが表示される', async ({
    page,
  }) => {
    await page.goto('/forgot-password')
    await waitForHydration(page)

    await fillInput(page.locator('input#email'), 'not-valid-email')
    await page.getByRole('button', { name: 'リセットメールを送信' }).click()

    await expect(page.getByText('有効なメールアドレスを入力してください')).toBeVisible({
      timeout: 5_000,
    })
  })

  test('DEEP-PWRESET-003: reset-password で 7 文字以下のパスワードはエラーが表示される', async ({
    page,
  }) => {
    await page.goto('/reset-password?token=anytoken123')
    await waitForHydration(page)

    const passwordInputs = page.locator('input[type="password"]')
    await fillPassword(passwordInputs.nth(0), 'Short1!', { closeFeedback: true }) // 7 文字
    await fillPassword(passwordInputs.nth(1), 'Short1!')

    await page.getByRole('button', { name: 'パスワードを変更' }).click()

    await expect(page.getByText('パスワードは8文字以上で入力してください')).toBeVisible({
      timeout: 5_000,
    })
  })

  test('DEEP-PWRESET-004: reset-password でパスワードと確認用が不一致だとエラーが表示される', async ({
    page,
  }) => {
    await page.goto('/reset-password?token=anytoken123')
    await waitForHydration(page)

    const passwordInputs = page.locator('input[type="password"]')
    await fillPassword(passwordInputs.nth(0), 'NewPassword2026!', { closeFeedback: true })
    await fillPassword(passwordInputs.nth(1), 'DifferentPass2026!')

    await page.getByRole('button', { name: 'パスワードを変更' }).click()

    await expect(page.getByText('パスワードが一致しません')).toBeVisible({ timeout: 5_000 })
  })

  test('DEEP-PWRESET-005: reset-password 成功時は完了画面と「ログインへ」ボタンが表示される', async ({
    page,
  }) => {
    // パスワードリセット API をモックして成功扱いにする
    await page.route('**/api/v1/auth/password-reset/confirm', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { success: true } }),
      })
    })

    await page.goto('/reset-password?token=mocktoken123')
    await waitForHydration(page)

    const passwordInputs = page.locator('input[type="password"]')
    await fillPassword(passwordInputs.nth(0), 'BrandNewPass2026!', { closeFeedback: true })
    await fillPassword(passwordInputs.nth(1), 'BrandNewPass2026!')

    await page.getByRole('button', { name: 'パスワードを変更' }).click()

    await expect(page.getByText('パスワードが変更されました。')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'ログインへ' })).toBeVisible()
  })
})
