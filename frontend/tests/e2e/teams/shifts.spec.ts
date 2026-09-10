import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockFeatureFlags } from '../helpers/feature-flags'
import { setupAdminAuth } from '../shifts/_helpers'
import { MOCK_PERMISSIONS, MOCK_TEAM, TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

async function mockSlugTeam(page: Parameters<typeof mockTeam>[0], slug: string, numericId: number) {
  await page.route(`**/api/v1/teams/${slug}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { ...MOCK_TEAM, id: slug, numericId } }),
    })
  })
  await page.route(`**/api/v1/teams/${slug}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_PERMISSIONS }),
    })
  })
}

test.describe('TEAM-014〜016: シフト管理', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    // catch-all 相当のチーム配下モックより後に個別登録し、feature-gate の差し戻しを防ぐ
    await mockFeatureFlags(page)
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

  test('通常slugを数値IDへ解決してシフト表APIへ渡す', async ({ page }) => {
    const slug = 'fc-u-18'
    const numericId = 42
    let requestedTeamId: string | null = null
    await mockSlugTeam(page, slug, numericId)
    await page.route('**/api/v1/shifts/schedules**', async (route) => {
      requestedTeamId = new URL(route.request().url()).searchParams.get('teamId')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${slug}/shifts`)
    await waitForHydration(page)

    await expect.poll(() => requestedTeamId).toBe(String(numericId))
  })

  test('不存在チームは404画面へ遷移する', async ({ page }) => {
    const slug = 'missing-team'
    await page.route(`**/api/v1/teams/${slug}`, async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'TEAM_001', message: 'Team not found' } }),
      })
    })

    await page.goto(`/teams/${slug}/shifts`)
    await waitForHydration(page)

    await expect(page.getByText('404', { exact: true })).toBeVisible({ timeout: 10_000 })
  })

  test('他テナントの403を空一覧にせず再試行可能なエラーとして表示する', async ({ page }) => {
    const slug = 'other-tenant'
    const numericId = 77
    let requestedTeamId: string | null = null
    await mockSlugTeam(page, slug, numericId)
    await page.route('**/api/v1/shifts/schedules**', async (route) => {
      requestedTeamId = new URL(route.request().url()).searchParams.get('teamId')
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'COMMON_002', message: 'Forbidden' } }),
      })
    })

    await page.goto(`/teams/${slug}/shifts`)
    await waitForHydration(page)

    await expect.poll(() => requestedTeamId).toBe(String(numericId))
    await expect(page.getByRole('button', { name: /再試行|Retry/ })).toBeVisible({
      timeout: 10_000,
    })
  })
})
