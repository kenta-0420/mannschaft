import { test as setup } from '@playwright/test'
import path from 'path'
import { loginViaApi } from '../fixtures/auth'

const ADMIN_AUTH_FILE = path.join('tests/e2e/.auth', 'admin.json')

/**
 * 管理者認証セットアップ。
 *
 * TEST_ADMIN_EMAIL / TEST_ADMIN_PASSWORD 環境変数（または .env.test）が設定されて
 * いない場合はログインをスキップし、global.setup.ts が作成した空の storageState を
 * 維持する。モックテストはこの空ステートで動作し、認証必須の実機テストは別途
 * loginIfNeeded() 等でフォールバックする。
 */
setup('管理者でログイン', async ({ page }) => {
  const email = process.env.TEST_ADMIN_EMAIL
  const password = process.env.TEST_ADMIN_PASSWORD

  if (!email || !password) {
    console.log('[setup-admin] 認証情報未設定のためログインをスキップします（モックテスト用空ステートで継続）')
    return
  }

  await loginViaApi(page, { email, password })
  await page.context().storageState({ path: ADMIN_AUTH_FILE })
})
