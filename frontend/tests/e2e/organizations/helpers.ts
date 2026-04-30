import type { Page } from '@playwright/test'

export const ORG_ID = 1

export const MOCK_ORG = {
  id: ORG_ID,
  name: 'テスト組織',
  description: 'E2Eテスト用組織',
  visibility: 'PUBLIC',
  memberCount: 10,
  createdAt: '2026-01-01T00:00:00Z',
}

export const MOCK_ORG_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'schedule.create', 'schedule.edit', 'schedule.delete',
    'todo.create', 'todo.edit', 'todo.delete',
    'event.create', 'event.edit', 'event.delete',
    'member.manage', 'bulletin.create', 'bulletin.edit',
    'form.create', 'form.edit', 'survey.create', 'survey.edit',
  ],
}

export async function mockOrg(page: Page) {
  await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG }),
    })
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG_PERMISSIONS }),
    })
  })
}

export async function mockOrgFeatureApis(page: Page) {
  await page.route(`**/api/v1/organizations/${ORG_ID}/**`, async (route) => {
    const url = route.request().url()
    if (url.includes('/me/permissions')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ORG_PERMISSIONS }),
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
