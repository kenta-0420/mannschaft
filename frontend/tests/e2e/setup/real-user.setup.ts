import { test as setup } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

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

  await loginViaApi(page, { email, password })
  await page.context().storageState({ path: 'tests/e2e/.auth/real-user.json' })
})
