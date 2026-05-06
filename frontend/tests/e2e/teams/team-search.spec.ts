import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// TEAMSEARCH テストケース用モックデータ
const SEARCH_RESULTS = [
  {
    id: 10,
    name: '検索ヒットチームX',
    nickname1: null,
    iconUrl: null,
    prefecture: '大阪府',
    city: '大阪市',
    template: 'SPORTS',
    memberCount: 12,
    supporterEnabled: false,
  },
]

test.describe('TEAMSEARCH-001〜003: チーム検索ページ', () => {
  // TEAMSEARCH-001: /teams/search にアクセスすると検索ページが表示される
  test('TEAMSEARCH-001: /teams/search にアクセスすると検索ページが表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: SEARCH_RESULTS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/teams/search')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({
      timeout: 10_000,
    })
  })

  // TEAMSEARCH-002: 検索実行後に結果が表示される
  test('TEAMSEARCH-002: 検索実行後に結果が表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: SEARCH_RESULTS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/teams/search')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('検索ヒットチームX')).toBeVisible({ timeout: 5_000 })
  })

  // TEAMSEARCH-003: 「戻る」ボタンを押すと /teams に戻る
  test('TEAMSEARCH-003: 「戻る」ボタンを押すと /teams に戻る', async ({ page }) => {
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })
    // 遷移先 /teams のAPIモック
    await page.route('**/api/v1/me/teams', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/me/scope-folders**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/teams/search')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({
      timeout: 10_000,
    })

    // BackButton コンポーネントまたは to="/teams" を持つリンクをクリック
    const backButton = page.locator('a[href="/teams"]').first()
    await expect(backButton).toBeVisible({ timeout: 5_000 })
    await backButton.click()

    await expect(page).toHaveURL(/\/teams$/, { timeout: 5_000 })
  })
})
