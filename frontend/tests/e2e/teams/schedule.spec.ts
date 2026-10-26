import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-004〜007: チームスケジュール', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('TEAM-004: スケジュールページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-005: 管理者にはイベント作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'イベント作成' })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-006: カレンダーに現在の年月が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })

    const now = new Date()
    const expectedLabel = `${now.getFullYear()}年${now.getMonth() + 1}月`
    await expect(page.getByRole('heading', { name: expectedLabel })).toBeVisible({ timeout: 5_000 })
  })

  test('TEAM-007: 次月ボタンで翌月に遷移できる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })

    const now = new Date()
    const nextMonth = now.getMonth() + 1 === 12 ? 1 : now.getMonth() + 2
    const nextYear = now.getMonth() + 1 === 12 ? now.getFullYear() + 1 : now.getFullYear()
    const nextLabel = `${nextYear}年${nextMonth}月`

    const nextBtn = page.getByRole('button').filter({ has: page.locator('.pi-chevron-right') })
    await nextBtn.click()
    await expect(page.getByRole('heading', { name: nextLabel })).toBeVisible({ timeout: 5_000 })
  })
})
