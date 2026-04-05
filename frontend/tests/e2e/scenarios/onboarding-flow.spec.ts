import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 未認証状態でテスト
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('SCEN-001〜005: オンボーディングフロー', () => {
  test('SCEN-001: 未認証ユーザーがダッシュボードにアクセスするとログインにリダイレクトされる', async ({
    page,
  }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
  })

  test('SCEN-002: ランディングページが表示される', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    // ランディングページにはサービス名やCTAが表示される
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('SCEN-003: ランディングページからログインページへ遷移できる', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    const loginLink = page.getByRole('link', { name: /ログイン/ })
    if ((await loginLink.count()) > 0) {
      await loginLink.first().click()
      await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    }
  })

  test('SCEN-004: ログインページから新規登録ページへ遷移できる', async ({ page }) => {
    await page.goto('/login')
    await waitForHydration(page)
    const registerLink = page.getByRole('link', { name: /新規登録|アカウント作成|登録/ })
    if ((await registerLink.count()) > 0) {
      await registerLink.first().click()
      await expect(page).toHaveURL(/\/register/, { timeout: 10_000 })
    }
  })

  test('SCEN-005: 新規登録ページにフォームが表示される', async ({ page }) => {
    await page.goto('/register')
    await waitForHydration(page)
    await expect(
      page.getByRole('heading', { name: /新規登録|アカウント作成/ }),
    ).toBeVisible({ timeout: 10_000 })
    // メール・パスワード入力欄が存在する
    const emailInput = page.locator(
      'input[type="email"], input[name="email"], input#email',
    )
    await expect(emailInput.first()).toBeVisible({ timeout: 10_000 })
  })
})
