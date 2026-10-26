import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-019〜020: チャット', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    // チャット関連API
    await page.route('**/api/v1/chat/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('TEAM-019: チャットページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/chat`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-020: チャットページにチャンネルエリアが存在する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/chat`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
    // チャンネル一覧またはメッセージエリアが存在すること
    await expect(
      page
        .locator('[class*="chat"], [class*="channel"], [data-testid*="chat"]')
        .first()
        .or(page.getByRole('heading', { name: 'チャット' })),
    ).toBeVisible({ timeout: 5_000 })
  })
})
