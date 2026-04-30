import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-027〜028: 回覧板', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-027: 回覧板ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-028: 回覧板ページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
    // エラー表示がないこと
    await expect(page.getByText('エラー', { exact: true })).not.toBeVisible()
  })
})
