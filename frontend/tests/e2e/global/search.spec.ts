import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_SEARCH_RESULTS = {
  data: {
    query: 'テスト', executionTimeMs: 1,
    results: { schedules: [], events: [], reservations: [], shifts: [], safetyChecks: [], queues: [], teams: Array.from({ length: 10 }, (_, index) => ({ id: index + 1, name: index === 0 ? 'テストチームA' : 'テストチーム' + (index + 1) })), organizations: [], users: [] },
    counts: { schedules: 0, events: 0, reservations: 0, shifts: 0, safetyChecks: 0, queues: 0, teams: 11, organizations: 0, users: 0 },
  },
}

test.describe('GLOBAL-001: 検索機能', () => {
  test('GLOBAL-001: 検索ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/search/recent**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SEARCH_RESULTS),
      })
    })

    await page.goto('/search')
    await waitForHydration(page)

    // 検索ページが表示される
    await expect(
      page.locator('input[type="search"], input[placeholder*="検索"]').first(),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('GLOBAL-002: クエリパラメータ付きで検索結果が表示される', async ({ page }) => {
    await page.route('**/api/v1/search/recent**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SEARCH_RESULTS),
      })
    })

    await page.goto('/search?q=テスト')
    await waitForHydration(page)

    await expect(page.getByText('テストチームA')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'チーム (11)', exact: true })).toBeVisible()
    await expect(page.getByText('全11件中10件を表示')).toBeVisible()
  })
})
