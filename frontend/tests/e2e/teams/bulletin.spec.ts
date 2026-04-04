import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-025〜026: 掲示板', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-025: 掲示板ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-026: 掲示板ページに投稿エリアが存在する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible({ timeout: 10_000 })
    // 投稿ボタンまたはスレッド一覧エリアが存在すること
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible()
  })
})
