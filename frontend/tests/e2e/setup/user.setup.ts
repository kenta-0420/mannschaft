import { test as setup } from '@playwright/test'
import path from 'path'
import { loginViaApi } from '../fixtures/auth'

const USER_AUTH_FILE = path.join('tests/e2e/.auth', 'user.json')

/**
 * 一般ユーザー認証セットアップ。
 *
 * TEST_USER_EMAIL / TEST_USER_PASSWORD 環境変数（または .env.test）が設定されて
 * いない場合はログインをスキップし、global.setup.ts が作成した空の storageState を
 * 維持する。モックテストはこの空ステートで動作し、認証必須の実機テストは別途
 * loginIfNeeded() 等でフォールバックする。
 */
setup('一般ユーザーでログイン', async ({ page }) => {
  const email = process.env.TEST_USER_EMAIL
  const password = process.env.TEST_USER_PASSWORD

  if (!email || !password) {
    console.log('[setup-user] 認証情報未設定のためログインをスキップします（モックテスト用空ステートで継続）')
    return
  }

  await loginViaApi(page, { email, password })
  await page.context().storageState({ path: USER_AUTH_FILE })
})
