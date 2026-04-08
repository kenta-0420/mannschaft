import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

/**
 * 2fa-verify.vue は session クエリ無しでアクセスすると onMounted で /login にリダイレクトする。
 * フォーム自体は InputOtp(6桁) + 認証ボタン (totpCode.length !== 6 で disabled)
 */
test.describe('AUTH-DEEP 2fa-verify: 二要素認証フォーム深掘り', () => {
  test('DEEP-2FA-001: session クエリなしで /2fa-verify にアクセスすると /login にリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/2fa-verify')
    // onMounted の navigateTo 完了を待つ
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })

  test('DEEP-2FA-002: 6 桁未満のコードでは認証ボタンが disabled になっている', async ({
    page,
  }) => {
    await page.goto('/2fa-verify?session=test-session-token')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '二要素認証' })).toBeVisible({
      timeout: 10_000,
    })

    // 初期状態（0 桁）で認証ボタンが disabled
    const button = page.getByRole('button', { name: '認証する' })
    await expect(button).toBeDisabled()

    // 3 桁入力しても disabled のまま
    const otpInputs = page.locator('.p-inputotp input')
    for (let i = 0; i < 3; i++) {
      await otpInputs.nth(i).click()
      await otpInputs.nth(i).pressSequentially(String(i + 1), { delay: 10 })
    }
    await expect(button).toBeDisabled()
  })

  test('DEEP-2FA-003: 6 桁すべて入力すると認証ボタンが有効化される', async ({ page }) => {
    await page.goto('/2fa-verify?session=test-session-token')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '二要素認証' })).toBeVisible({
      timeout: 10_000,
    })

    const otpInputs = page.locator('.p-inputotp input')
    const code = '123456'
    for (let i = 0; i < 6; i++) {
      await otpInputs.nth(i).click()
      await otpInputs.nth(i).pressSequentially(code[i]!, { delay: 10 })
    }

    const button = page.getByRole('button', { name: '認証する' })
    await expect(button).toBeEnabled()
  })

  test('DEEP-2FA-004: 不正なコードを送信するとエラーメッセージが表示され入力欄がクリアされる', async ({
    page,
  }) => {
    // 検証 API を 401 でモック
    await page.route('**/api/v1/auth/2fa/validate', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: { message: 'Invalid TOTP code' } }),
      })
    })

    await page.goto('/2fa-verify?session=test-session-token')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '二要素認証' })).toBeVisible({
      timeout: 10_000,
    })

    const otpInputs = page.locator('.p-inputotp input')
    const code = '999999'
    for (let i = 0; i < 6; i++) {
      await otpInputs.nth(i).click()
      await otpInputs.nth(i).pressSequentially(code[i]!, { delay: 10 })
    }

    await page.getByRole('button', { name: '認証する' }).click()

    // エラー通知が表示される
    await expect(page.getByText('認証コードが正しくありません')).toBeVisible({ timeout: 10_000 })
    // catch ブロックで totpCode.value = '' されるため、入力欄はクリアされている
    await expect(otpInputs.first()).toHaveValue('')
  })
})
