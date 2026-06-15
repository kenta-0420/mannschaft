import { defineConfig, devices } from '@playwright/test'

/**
 * F08.10 多競技 API 完結 E2E 専用の一時 config（足軽 E2E 検証用）。
 *
 * f0810-basketball-continuous / f0810-volleyball-sets は API 完結（実 BE 直叩き）で
 * FE dev サーバー（BASE_URL）に依存しないため、storageState setup（FE プロキシ経由ログイン）の
 * project 依存を持たない最小構成にする。BE は BE_ORIGIN（既定 http://localhost:8080）。
 *
 * 実行: cd frontend && npx playwright test --config=playwright-f0810-api.config.ts
 *
 * 注意: これは検証用の一時 config。本採用は playwright-real.config.ts（chromium-real）であり、
 *   そちらでも storageState 無効化 + 自前ログインで本 spec はそのまま動く（CI 統合時に追加するだけ）。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: /f0810-(basketball-continuous|volleyball-sets|shogi-turn|go-turn|position-photo|team-match-board)\.spec\.ts/,
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [
    {
      name: 'chromium-api',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  timeout: 60_000,
  expect: { timeout: 8_000 },
})
