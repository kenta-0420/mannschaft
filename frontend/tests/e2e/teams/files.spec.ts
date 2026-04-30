import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-033〜034: ファイル共有', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-033: ファイル共有ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ファイル共有' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-034: ファイル共有ページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ファイル共有' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('エラー', { exact: true })).not.toBeVisible()
  })
})
