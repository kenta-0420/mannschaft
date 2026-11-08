import { defineConfig, devices } from '@playwright/test'
import { F0121_CONFIG_MARK, F0121_DEFAULT_API, F0121_DEFAULT_FE } from './tests/e2e/real/helpers/f0121-endpoints'

/**
 * F01.2.1（組織がチームをグループ化）実機E2E 専用の設定（CMP-261011-0035）。
 *
 * playwright-real.config.ts と同じ実機前提（ただし通常設定では f0121 の spec を testIgnore で除外している）（検証 BE・検証 FE を起動済み）で、F01.2.1 の 3 spec だけを流す。
 * 作成した組織・チームは globalTeardown がテストの成否にかかわらず最後に削除し、204 を検証する。
 *
 * 実行例: BASE_URL=http://localhost:3021 API_BASE_URL=http://localhost:8081 \
 *   node node_modules/playwright/cli.js test -c playwright-f0121.config.ts
 */
// 接続先の既定値は helpers/f0121-endpoints.ts に集約（spec の土台と共有する）
if (!process.env.API_BASE_URL) process.env.API_BASE_URL = F0121_DEFAULT_API
if (!process.env.BASE_URL) process.env.BASE_URL = F0121_DEFAULT_FE
// ワーカーへ引き継がれる印。土台はこれが無いと（＝後始末の無い設定から）データを作らずに止まる
process.env[F0121_CONFIG_MARK] = '1'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: /f0121-.*\.real\.spec\.ts$/,
  globalTeardown: './tests/e2e/real/helpers/f0121-global-teardown.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 600_000,
  expect: { timeout: 8_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    trace: 'off',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: {
      args: ['--host-resolver-rules=MAP localhost 127.0.0.1'],
    },
  },
})
