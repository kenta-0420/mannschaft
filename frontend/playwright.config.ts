import { defineConfig, devices } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') })

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8081'

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
    // この環境は WSL2 mirrored networking (hostAddressLoopback=true) の影響で
    // http://localhost:<port> への接続が IPv6(::1) 側のゴーストソケット
    // （426 Upgrade Required を返す別リスナー）に落ちる既知の罠がある。
    // Chromium のホスト解決ルールで localhost を 127.0.0.1 へマップして回避する。
    // BE が発行する Cookie の domain=localhost 要件は hostname 文字列としては
    // localhost のまま維持されるため崩れない。
    launchOptions: {
      args: ['--host-resolver-rules=MAP localhost 127.0.0.1'],
    },
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
    // Setup: 実機テスト用ユーザー認証状態を保存
    {
      name: 'setup-real-user',
      testMatch: /.*real-user\.setup\.ts/,
    },
    // メインテスト: 一般ユーザー setup 完了後に実行
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'tests/e2e/.auth/user.json',
      },
      // dependencies: ['setup-user'], // 既存 storageState を使うため一時的にコメントアウト
      // tests/e2e/real/** は chromium-real 専用（毎テストで実ログインする実機テストのため）。
      // testMatch 制限が無いとここでも二重実行され、ログイン試行回数の上限（1分あたり10回）に
      // 引っかかって偽の赤を生む。chromium はそれ以外の（モック中心の）spec のみ対象とする。
      testIgnore: '**/real/**/*.spec.ts',
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
  // NUXT_IGNORE_LOCK=1: 既存 dev サーバーのロックファイルを無視して reuseExistingServer が
  // 正しく動作するよう設定。既存サーバーが起動中の場合は URL チェックで reuse される。
  webServer: {
    command: `npm run dev -- --port ${new URL(BASE_URL).port || '8081'}`,
    // readiness チェックは Node の http クライアントで行われ Chromium の
    // host-resolver-rules の恩恵を受けないため、ここだけ 127.0.0.1 で疎通確認する
    // （use.baseURL は BASE_URL のまま維持し、Cookie domain 要件は崩さない）。
    url: BASE_URL.replace('localhost', '127.0.0.1'),
    reuseExistingServer: true,
    timeout: 240_000,
    env: {
      ...process.env,
      NUXT_IGNORE_LOCK: '1',
      NUXT_API_PROXY: 'true',
      // /api/v1/** → http://127.0.0.1:8080 へのプロキシを有効化
      // reuseExistingServer=true で既存サーバーを再利用する場合は
      // そのサーバーも NUXT_API_PROXY=true で起動されている必要がある
    },
  },
})
