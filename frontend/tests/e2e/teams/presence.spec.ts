import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-017〜018: 在席管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-017: 在席管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-018: 外出する・帰宅するボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '外出する' })).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: '帰宅する' })).toBeVisible({ timeout: 5_000 })
  })
})
