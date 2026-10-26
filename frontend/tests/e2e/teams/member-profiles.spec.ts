import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-011〜013: メンバー紹介', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-011: メンバー紹介ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/member-profiles`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバー紹介' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-012: 管理者にはメンバー追加ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/member-profiles`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバー紹介' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'メンバー追加' })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-013: メンバー追加ボタンを押すとダイアログが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/member-profiles`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバー紹介' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: 'メンバー追加' }).click()
    await expect(page.getByRole('dialog', { name: 'メンバー追加' })).toBeVisible({ timeout: 5_000 })
  })
})
