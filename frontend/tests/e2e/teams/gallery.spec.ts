import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-035〜036: ギャラリー', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    await page.route('**/api/v1/gallery/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('TEAM-035: ギャラリーページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/gallery`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ギャラリー', level: 1 })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-036: ギャラリーページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/gallery`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ギャラリー', level: 1 })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('エラー')).not.toBeVisible()
  })
})
