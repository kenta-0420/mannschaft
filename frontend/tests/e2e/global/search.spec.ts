import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_SEARCH_RESULTS = {
  data: {
    results: [
      {
        type: 'TEAM',
        id: 1,
        title: 'テストチームA',
        snippet: 'スポーツチーム',
        url: '/teams/1',
      },
    ],
    typeCounts: { TEAM: 1 },
    timedOutTypes: [],
    zeroResultsHelp: null,
  },
  meta: { page: 0, totalPages: 1, totalElements: 1 },
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
  })
})
