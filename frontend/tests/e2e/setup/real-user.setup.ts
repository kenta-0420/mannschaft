import { test as setup } from '@playwright/test'

const AUTH_FILE = 'tests/e2e/.auth/real-user.json'

setup('実機テスト用ユーザー認証セットアップ', async ({ page }) => {
  // ログインページへ遷移し、ゲストミドルウェアのリダイレクトを待つ
  await page.goto('/login')
  // hydration 後に guest middleware が認証済みユーザーを /dashboard へリダイレクトする
  // または /login のままログインフォームが表示される
  await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500) // CSR リダイレクト完了を待つ

  const currentUrl = page.url()

  if (!currentUrl.includes('/login')) {
    // すでに認証済み（guest middleware が /dashboard 等へリダイレクト済み）
    // storageState をそのまま保存して完了
    await page.context().storageState({ path: AUTH_FILE })
    return
  }

  // ログインページが表示されている → 未認証なのでログインする
  const emailInput = page.locator('input#email, input[type="email"]').first()
  await emailInput.waitFor({ state: 'visible', timeout: 15_000 })
  await emailInput.fill('e2e-user@test.mannschaft.local')

  const passwordInput = page.locator('input[type="password"]').first()
  await passwordInput.fill('TestPass2026!')

  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL(/\/(my|teams|dashboard)/, { timeout: 30_000 })

  await page.context().storageState({ path: AUTH_FILE })
})
