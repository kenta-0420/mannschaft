import { defineConfig, devices } from '@playwright/test'

/**
 * 甲 #1733 activity 認可 IDOR 実機 E2E 専用の API 完結 config。
 *
 * activity-authz.spec.ts は実 BE 直叩き（APIRequestContext のみ）で FE dev サーバー（BASE_URL）に
 * 依存しないため、storageState setup（FE プロキシ経由ログイン）の project 依存を持たない最小構成にする。
 * BE は BE_ORIGIN（既定 http://127.0.0.1:8081＝検証用 worktree ポート）。
 *
 * 実行: cd frontend && BE_ORIGIN=http://127.0.0.1:8081 \
 *         npx playwright test --config=playwright-activity-authz.config.ts
 *
 * 本採用は playwright-real.config.ts（chromium-real）であり、そちらでも storageState 無効化 +
 * 自前 API ログインで本 spec はそのまま動く（CI 統合時に testMatch へ追加するだけ）。
 */
export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: /activity-authz\.spec\.ts/,
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
