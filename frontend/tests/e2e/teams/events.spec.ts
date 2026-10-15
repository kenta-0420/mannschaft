import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-008〜010: チームイベント', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-008: イベント一覧ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-009: イベント作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: 'イベント作成' })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-010: イベント作成ボタンを押すとダイアログが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })
    await page.getByRole('button', { name: 'イベント作成' }).click()
    // ダイアログが開く（URL は /events のまま）
    await expect(page).toHaveURL(/\/events$/, { timeout: 3_000 })
    await expect(page.getByText('イベントを作成')).toBeVisible({ timeout: 8_000 })
  })
})
