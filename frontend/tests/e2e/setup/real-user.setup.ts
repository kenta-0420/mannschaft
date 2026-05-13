import { test as setup } from '@playwright/test'

/**
 * 実機テスト用: 一般ユーザーログイン setup
 *
 * モックなし・実バックエンド接続でログインし、
 * storageState を tests/e2e/.auth/real-user.json に保存する。
 *
 * 事前条件:
 *   - backend/scripts/seed-e2e-data.js が実行済みであること
 *   - e2e-user@test.mannschaft.local が DB に存在すること
 */
setup('実機: 一般ユーザーログイン', async ({ page }) => {
  const email = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
  const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

  await page.goto('/login')

  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })

  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })

  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15_000 })

  await page.context().storageState({ path: 'tests/e2e/.auth/real-user.json' })
})
