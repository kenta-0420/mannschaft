import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-037〜038: 予約管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-037: 予約管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/reservations`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '予約管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-038: 予約管理ページが正常にロードされる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/reservations`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '予約管理' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('エラー')).not.toBeVisible()
  })
})
