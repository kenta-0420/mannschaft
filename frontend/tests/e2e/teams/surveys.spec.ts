import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-029〜030: アンケート・投票', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-029: アンケート・投票ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/surveys`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アンケート・投票' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-030: アンケート・投票ページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/surveys`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アンケート・投票' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('エラー')).not.toBeVisible()
  })
})
