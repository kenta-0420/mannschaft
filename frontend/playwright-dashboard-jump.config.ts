import { defineConfig, devices } from '@playwright/test'

/**
 * ダッシュボードのウィジェット並び順ジャンプ根治の実機検証専用 config。
 *
 * - FE: 検証用 worktree の dev サーバー（:3007）
 * - BE: 本陣の実バックエンド（:8080・起動禁止）
 * - 認証/通信は spec 内の apibridge（page.route → node fetch で :8080 中継、
 *   応答に ACAO を付与）で CORS を回避する（BE は :3007 を許可していないため）。
 *
 * storageState には依存せず、各テストで loginViaApi により fresh login する
 * （トークンローテーション由来の stale を避けるため。feedback_e2e_real_single_session_token_rotation）。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: '**/dashboard-widget-order-jump.spec.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: './test-results/dashboard-jump',
  use: {
    baseURL: process.env.BASE_URL ?? 'http://127.0.0.1:3007',
    trace: 'off',
    screenshot: 'off',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [
    {
      name: 'chromium-dashboard-jump',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  timeout: 120_000,
  expect: { timeout: 10_000 },
})
