import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-014〜016: シフト管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    // シフトAPIはチーム配下ではなく /api/v1/shifts 配下
    await page.route('**/api/v1/shifts/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })
  })

  test('TEAM-014: シフト管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shifts`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-015: シフト管理ページにタブが存在する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shifts`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible({ timeout: 10_000 })
    // シフト表タブなどが存在すること
    const tabs = page.getByRole('tab')
    const tabCount = await tabs.count()
    expect(tabCount).toBeGreaterThanOrEqual(1)
  })

  test('TEAM-016: シフト作成ボタンが管理者に表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shifts`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'シフト管理' })).toBeVisible({ timeout: 10_000 })
    // シフト作成ボタンまたはシフト追加ボタンが存在すること
    const createBtn = page.getByRole('button', { name: /作成|追加|新規/ })
    await expect(createBtn.first()).toBeVisible({ timeout: 5_000 })
  })
})
