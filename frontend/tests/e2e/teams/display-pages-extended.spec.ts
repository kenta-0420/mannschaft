import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-080〜093: チーム未テスト画面14ページ表示確認', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    // シフト系
    await page.route('**/api/v1/shifts/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    // プロジェクト系
    await page.route('**/api/v1/projects/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('TEAM-080: 活動統計ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/activity-stats`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '活動統計' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-081: 活動テンプレート管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/activity-templates`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '活動テンプレート管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-082: イベント詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/1/events/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            title: 'テストイベント',
            startAt: '2026-04-01T10:00:00Z',
            endAt: '2026-04-01T12:00:00Z',
          },
        }),
      })
    })
    await page.goto(`/teams/${TEAM_ID}/events/1`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-083: インシデント管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'インシデント管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-084: ナレッジベースページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/kb`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ナレッジベース' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-085: メンバーフィールド管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/member-fields`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバーフィールド管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-086: プロジェクト詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/projects/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            title: 'テストプロジェクト',
            status: 'IN_PROGRESS',
            description: '',
          },
        }),
      })
    })
    await page.goto(`/teams/${TEAM_ID}/projects/1`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-087: デジタルサイネージページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'デジタルサイネージ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-088: スキル・資格ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スキル・資格' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-089: タイムラインダイジェストページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/timeline-digest`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'タイムラインダイジェスト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-090: TODO詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/1/todos/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { id: 1, title: 'テストTODO', status: 'OPEN', assignee: null },
        }),
      })
    })
    await page.goto(`/teams/${TEAM_ID}/todos/1`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-091: 参加大会・リーグページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/tournaments`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '参加大会・リーグ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-092: Webhookページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/webhooks`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /Webhook/ })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-093: ワークフロー申請詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/1/workflow-requests/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            templateName: 'テスト申請',
            status: 'PENDING',
            applicantName: 'テストユーザー',
          },
        }),
      })
    })
    await page.goto(`/teams/${TEAM_ID}/workflows/1`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })
})
