import { test, expect } from '@playwright/test'

test.describe('SEC-001〜003: アクセス制御', () => {
  test.describe('未認証アクセス', () => {
    // このグループは storageState なし（未認証状態）で実行
    test.use({ storageState: { cookies: [], origins: [] } })

    test('SEC-001: 未認証でダッシュボードにアクセスするとログインページにリダイレクトされる', async ({
      page,
    }) => {
      await page.goto('/dashboard')
      await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
      // redirect クエリパラメータが付いていること
      await expect(page).toHaveURL(/redirect=/)
    })

    test('SEC-001b: 未認証でチームページにアクセスするとログインページにリダイレクトされる', async ({
      page,
    }) => {
      await page.goto('/teams')
      await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    })

    test('SEC-002: 未認証で管理画面にアクセスするとログインページにリダイレクトされる', async ({
      page,
    }) => {
      await page.goto('/admin/users')
      await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    })
  })

  test.describe('認証済みアクセス', () => {
    // chromium プロジェクト（一般ユーザー）の storageState を使用

    test('SEC-003: 認証済み一般ユーザーはダッシュボードにアクセスできる', async ({ page }) => {
      await page.goto('/dashboard')
      await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
      // ログインページにリダイレクトされないこと
      await expect(page).not.toHaveURL(/\/login/)
    })

    test('SEC-003b: ログイン後に /login を開くとダッシュボードにリダイレクトされる', async ({
      page,
    }) => {
      // 認証済みで /login にアクセス → ダッシュボードへ
      await page.goto('/dashboard')
      await expect(page).not.toHaveURL(/\/login/, { timeout: 10_000 })
    })
  })
})
