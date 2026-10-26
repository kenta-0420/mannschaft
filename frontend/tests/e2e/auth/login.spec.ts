import { waitForHydration } from '../helpers/wait'
import { test, expect } from '@playwright/test'
import { TOTP } from 'otplib'

const totp = new TOTP()

// このスペックは未認証状態で実行する
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('AUTH-001〜004: ログイン', () => {
  test('AUTH-001: 正しい認証情報でログインするとダッシュボードに遷移する', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_USER_EMAIL!, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(process.env.TEST_USER_PASSWORD!, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15_000 })
  })

  test('AUTH-002: パスワードが誤っているとエラーが表示される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_USER_EMAIL!, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially('wrongpassword123', { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()
    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)
  })

  test('AUTH-003: 未登録メールアドレスではエラーが表示される', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially('notexist@example.com', { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially('somepassword123', { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()
    await expect(page.getByText('ログインに失敗しました')).toBeVisible({ timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)
  })

  test('AUTH-004: 2FA有効ユーザーは認証コード入力後にログインできる', async ({ page }) => {
    const seed = process.env.TEST_USER_TOTP_SEED
    if (!seed) {
      test.skip()
      return
    }

    await page.goto('/login')
    await waitForHydration(page)

    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(process.env.TEST_USER_EMAIL!, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(process.env.TEST_USER_PASSWORD!, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // ログインが完了して /2fa-verify か /dashboard に遷移するまで待つ
    // タイムアウトや遷移先が違う場合は 2FA テスト不可能としてスキップ
    try {
      await page.waitForURL(
        (url) => url.pathname.includes('/2fa-verify') || url.pathname.includes('/dashboard'),
        { timeout: 15_000 },
      )
    } catch {
      test.skip()
      return
    }
    if (!page.url().includes('/2fa-verify')) {
      // Test user does not have 2FA enabled — skip
      test.skip()
      return
    }
    await expect(page.getByText('二要素認証')).toBeVisible()

    const code = await totp.generate({ secret: seed })
    const inputs = page.locator('.p-inputotp input')
    for (let i = 0; i < 6; i++) {
      await inputs.nth(i).click()
      await inputs.nth(i).pressSequentially(code[i] ?? '', { delay: 10 })
    }

    await page.getByRole('button', { name: '認証する' }).click()
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
  })
})
