import type { Page } from '@playwright/test'

export const TEAM_ID = 1

export const MOCK_TEAM = {
  id: TEAM_ID,
  name: 'テストチーム',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  template: 'SPORTS',
  prefecture: '東京都',
  city: '渋谷区',
  description: 'E2Eテスト用チーム',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 5,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
}

export const MOCK_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'schedule.create',
    'schedule.edit',
    'schedule.delete',
    'todo.create',
    'todo.edit',
    'todo.delete',
    'event.create',
    'event.edit',
    'event.delete',
    'member.manage',
  ],
}

/** チーム基本情報と権限をモックする */
export async function mockTeam(page: Page) {
  await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_TEAM }),
    })
  })
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_PERMISSIONS }),
    })
  })
}

/** チーム配下のフィーチャーAPIを空レスポンスでまとめてモックする */
export async function mockTeamFeatureApis(page: Page) {
  await page.route(`**/api/v1/teams/${TEAM_ID}/**`, async (route) => {
    // 権限エンドポイントはmockTeam()が処理するが念のため空ではなく適切なデータを返す
    const url = route.request().url()
    if (url.includes('/me/permissions')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PERMISSIONS }),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    }
  })
}
