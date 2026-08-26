import { defineConfig, devices } from '@playwright/test'

/**
 * F03.18（予定アクティビティフィード）実機 E2E 専用の Playwright 設定。
 *
 * 既定の playwright-real.config.ts を使わない理由:
 *   - setup プロジェクト（storageState 生成）へ依存させたくない。本戦役の spec は
 *     ファイル内で自前ログインする（1ファイル=1ログイン）ため、setup が落ちると
 *     テストが全 skip になり「偽の緑」を生む。
 *   - BE/FE の向き先を検証用（BE=8081 / FE=検証用 dev）へ環境変数で明示的に指すため。
 *
 * 実行例:
 *   BASE_URL=http://localhost:3007 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test --config=playwright-f0318.config.ts
 *
 * 注意: API_BASE_URL 未設定で走らせると FE 相対 URL へログインを投げて落ちるため、
 * 既定値を明示している（未設定による静かな全 skip を避ける）。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/schedule-activity-feed*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3007',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    ...devices['Desktop Chrome'],
  },
  timeout: 180_000,
  expect: { timeout: 10_000 },
})
