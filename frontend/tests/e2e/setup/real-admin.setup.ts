import { test as setup } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/**
 * 実機テスト用: 管理者ログイン setup
 *
 * モックなし・実バックエンド接続でログインし、
 * storageState を tests/e2e/.auth/real-admin.json に保存する。
 *
 * 事前条件:
 *   - backend/scripts/seed-e2e-data.js が実行済みであること
 *   - e2e-admin@test.mannschaft.local が DB に存在すること
 */
setup('実機: 管理者ログイン', async ({ page }) => {
  const email = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
  const password = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

  await loginViaApi(page, { email, password })
  await page.context().storageState({ path: 'tests/e2e/.auth/real-admin.json' })
})
