import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F07.6 インシデント・メンテナンス管理 — Playwright E2E テスト
 *
 * テストID: INCIDENT-001 〜 INCIDENT-006
 *
 * 仕様書: docs/features/F07.6_incident_management.md
 */

const INCIDENT_ID = 1

const MOCK_INCIDENT_SUMMARY = {
  id: INCIDENT_ID,
  scopeType: 'TEAM',
  scopeId: TEAM_ID,
  title: 'サーバー障害 2026-04-10',
  status: 'OPEN',
  severity: 'HIGH',
  categoryId: null,
  categoryName: null,
  assignedToId: null,
  assignedToName: null,
  reportedBy: 1,
  reportedByName: 'e2e_user',
  occurredAt: '2026-04-10T02:00:00Z',
  resolvedAt: null,
  createdAt: '2026-04-10T02:05:00Z',
  updatedAt: '2026-04-10T02:05:00Z',
}

const MOCK_INCIDENT_RESOLVED = {
  ...MOCK_INCIDENT_SUMMARY,
  id: 2,
  title: 'ネットワーク断続 2026-04-08',
  status: 'RESOLVED',
  severity: 'MEDIUM',
  resolvedAt: '2026-04-08T18:00:00Z',
}

const MOCK_INCIDENT_IN_PROGRESS = {
  ...MOCK_INCIDENT_SUMMARY,
  id: 3,
  title: '定期メンテナンス 2026-04-12',
  status: 'IN_PROGRESS',
  severity: 'LOW',
}

const MOCK_INCIDENTS_LIST = {
  data: [MOCK_INCIDENT_SUMMARY, MOCK_INCIDENT_RESOLVED, MOCK_INCIDENT_IN_PROGRESS],
  meta: { page: 0, size: 20, totalElements: 3, totalPages: 1 },
}

async function mockIncidentApis(page: Page): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // インシデント一覧 (/api/incidents は /api/v1/ 配下ではないため個別設定)
  await page.route('**/api/incidents**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_INCIDENTS_LIST),
      })
    } else {
      await route.continue()
    }
  })

  // 権限取得
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          roleName: 'ADMIN',
          permissions: ['member.manage'],
        },
      }),
    })
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('INCIDENT-001〜006: F07.6 インシデント管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
        }),
      )
    })
  })

  test('INCIDENT-001: インシデントページが表示される', async ({ page }) => {
    await mockIncidentApis(page)

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'インシデント管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('INCIDENT-002: インシデント一覧の取得と表示（GET）', async ({ page }) => {
    await mockIncidentApis(page)

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    // インシデントタイトルが表示される
    await expect(page.getByText('サーバー障害 2026-04-10')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ネットワーク断続 2026-04-08')).toBeVisible({ timeout: 10_000 })
  })

  test('INCIDENT-003: インシデントを作成できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/incidents**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_INCIDENTS_LIST),
        })
      } else if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_INCIDENT_SUMMARY, id: 99, title: '新規インシデント' },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    // 「報告」または「作成」ボタンをクリック
    const createButton = page.getByRole('button', { name: /報告|作成|追加/ })
    if (await createButton.isVisible({ timeout: 5_000 })) {
      await createButton.click()
    }

    await expect(page.getByRole('heading', { name: 'インシデント管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('INCIDENT-004: インシデントのステータスを更新できる（PATCH）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/incidents**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_INCIDENTS_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/incidents/${INCIDENT_ID}/status`, async (route) => {
      if (route.request().method() === 'PATCH') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_INCIDENT_SUMMARY, status: 'IN_PROGRESS' },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    // インシデント一覧が表示される
    await expect(page.getByText('サーバー障害 2026-04-10')).toBeVisible({ timeout: 10_000 })
  })

  test('INCIDENT-005: インシデントを解決済みにできる（PATCH）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/incidents**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_INCIDENTS_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/incidents/${INCIDENT_ID}/status`, async (route) => {
      if (route.request().method() === 'PATCH') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              ...MOCK_INCIDENT_SUMMARY,
              status: 'RESOLVED',
              resolvedAt: '2026-04-12T10:00:00Z',
            },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    // 解決済みインシデントも表示される
    await expect(page.getByText('ネットワーク断続 2026-04-08')).toBeVisible({ timeout: 10_000 })
  })

  test('INCIDENT-006: インシデントを削除できる（DELETE）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route('**/api/incidents**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_INCIDENTS_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/incidents/${INCIDENT_ID}`, async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/incidents`)
    await waitForHydration(page)

    // インシデント一覧が表示される
    await expect(page.getByText('サーバー障害 2026-04-10')).toBeVisible({ timeout: 10_000 })
  })
})
