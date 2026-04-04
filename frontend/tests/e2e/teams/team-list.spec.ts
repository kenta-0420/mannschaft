import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const TEAM_LIST = [
  {
    id: 1,
    name: 'テストチームA',
    nickname1: null,
    iconUrl: null,
    prefecture: '東京都',
    city: '渋谷区',
    template: 'SPORTS',
    memberCount: 5,
    supporterEnabled: false,
  },
]

test.describe('TEAM-001〜003: チーム一覧', () => {
  test('TEAM-001: チーム検索ページが表示され基本要素が存在する', async ({ page }) => {
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: TEAM_LIST,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/teams')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'チームを作成' })).toBeVisible()
  })

  test('TEAM-002: チーム検索結果にチームカードが表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/search**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: TEAM_LIST,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/teams')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('テストチームA')).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-003: 検索結果が空の場合は空状態メッセージが表示される', async ({ page }) => {
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

    await page.goto('/teams')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チーム検索' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('該当するチームが見つかりませんでした')).toBeVisible({
      timeout: 5_000,
    })
  })
})
