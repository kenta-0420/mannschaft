import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'on-failure' }]],

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },

  projects: [
    // Setup: 一般ユーザー認証状態を保存
    {
      name: 'setup-user',
      testMatch: /.*user\.setup\.ts/,
    },
    // Setup: 管理者認証状態を保存
    {
      name: 'setup-admin',
      testMatch: /.*admin\.setup\.ts/,
    },
    // メインテスト: 一般ユーザー setup 完了後に実行
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/user.json',
      },
      // dependencies: ['setup-user'], // 既存 storageState を使うため一時的にコメントアウト
    },
    // 管理者権限テスト（admin/ 配下のみ実行）
    {
      name: 'chromium-admin',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/admin.json',
      },
      // setup-admin は .env.test の認証情報が必要。既存 storageState を使うため一時的にコメントアウト
      // dependencies: ['setup-admin'],
      testMatch: '**/admin/**/*.spec.ts',
    },

    // ===== 実機テスト用プロジェクト（API モックなし・実バックエンド接続） =====
    // Setup: 実機テスト用一般ユーザー認証状態を保存
    {
      name: 'setup-real-user',
      testMatch: /.*real-user\.setup\.ts/,
    },
    // Setup: 実機テスト用管理者認証状態を保存
    {
      name: 'setup-real-admin',
      testMatch: /.*real-admin\.setup\.ts/,
    },
    // 実機テスト: 一般ユーザー（real/ 配下のみ実行）
    // storageState が存在しない場合は各テスト内の loginIfNeeded() でフォールバックする
    {
      name: 'chromium-real',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/real-user.json',
      },
      testMatch: '**/real/**/*.spec.ts',
      dependencies: ['setup-real-user'],
    },
    // 実機テスト: 管理者（real/admin/ 配下のみ実行）
    {
      name: 'chromium-real-admin',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/real-admin.json',
      },
      testMatch: '**/real/admin/**/*.spec.ts',
      dependencies: ['setup-real-admin'],
    },
  ],

  timeout: 60_000,
  expect: {
    timeout: 5_000,
  },

  // テスト実行前に dev サーバーを起動する場合は有効化
  webServer: {
    command: 'npm run dev',
    // ヘルスチェック URL: 3000 or 3002 のどちらかが応答すれば OK。
    // worktree サーバーは 127.0.0.1:3002 で起動済みの場合が多い。
    url: 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
