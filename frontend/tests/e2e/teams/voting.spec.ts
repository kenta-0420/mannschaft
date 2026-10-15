import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-031〜032: 議決権行使', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-031: 議決権行使ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/voting`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-032: セッション作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/voting`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'セッション作成' })).toBeVisible({
      timeout: 5_000,
    })
  })
})
