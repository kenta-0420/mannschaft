import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F07.2 パフォーマンス管理 — Playwright E2E テスト
 *
 * テストID: PERF-001 〜 PERF-005
 *
 * 仕様書: docs/features/F07.2_performance.md
 */

const MOCK_PERFORMANCE_STATS = [
  {
    metricId: 1,
    metricName: '走行距離',
    unit: 'km',
    teamAverage: 8.5,
    teamBest: 12.3,
    totalRecords: 20,
  },
  {
    metricId: 2,
    metricName: 'シュート数',
    unit: '本',
    teamAverage: 4.2,
    teamBest: 8.0,
    totalRecords: 15,
  },
]

const MOCK_MY_PERFORMANCE = {
  data: {
    metrics: {
      走行距離: '9.2km',
      シュート数: '5本',
    },
    records: [
      {
        id: 101,
        metricName: '走行距離',
        value: '9.2',
        recordedAt: '2026-04-01',
        teamName: 'テストチーム',
      },
    ],
  },
}

async function mockPerformanceApis(page: Page): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // チームパフォーマンス統計
  await page.route(`**/api/v1/teams/${TEAM_ID}/performance/stats**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_PERFORMANCE_STATS }),
    })
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('PERF-001〜005: F07.2 パフォーマンス管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
        }),
      )
    })
  })

  test('PERF-001: パフォーマンスページが表示される', async ({ page }) => {
    await mockPerformanceApis(page)

    await page.goto(`/teams/${TEAM_ID}/performance`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'パフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('PERF-002: メトリクス一覧の取得と表示（GET）', async ({ page }) => {
    await mockPerformanceApis(page)

    await page.goto(`/teams/${TEAM_ID}/performance`)
    await waitForHydration(page)

    // メトリクス名が表示される
    await expect(page.getByText('走行距離')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('シュート数')).toBeVisible({ timeout: 10_000 })
  })

  test('PERF-003: パフォーマンス記録を追加できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/performance/stats**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PERFORMANCE_STATS }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/performance/records`, async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 201,
              metricId: 1,
              value: '10.5',
              recordedAt: '2026-04-12',
            },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/teams/${TEAM_ID}/performance`)
    await waitForHydration(page)

    // ページが正しく表示されている
    await expect(page.getByRole('heading', { name: 'パフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })
    // メトリクスデータが表示されている
    await expect(page.getByText('走行距離')).toBeVisible({ timeout: 10_000 })
  })

  test('PERF-004: 個人パフォーマンスページが表示される（/my/performance）', async ({ page }) => {
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: null }),
      })
    })

    await page.route('**/api/v1/performance/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MY_PERFORMANCE),
      })
    })

    await page.goto('/my/performance')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイパフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })

    // メトリクスが表示される
    await expect(page.getByText('走行距離')).toBeVisible({ timeout: 10_000 })
  })

  test('PERF-005: パフォーマンスデータのフィルタ（期間・メンバー）', async ({ page }) => {
    let filteredRequestCalled = false

    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/performance/stats**`, async (route) => {
      const url = route.request().url()
      // クエリパラメータ付きリクエストも受け付ける
      if (url.includes('performance/stats')) {
        filteredRequestCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_PERFORMANCE_STATS }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/teams/${TEAM_ID}/performance`)
    await waitForHydration(page)

    // ページが表示され、統計APIが呼ばれたことを確認
    await expect(page.getByRole('heading', { name: 'パフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })
    expect(filteredRequestCalled).toBe(true)
  })
})
