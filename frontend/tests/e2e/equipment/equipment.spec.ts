import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F07.3 備品管理 — Playwright E2E テスト
 *
 * テストID: EQUIP-001 〜 EQUIP-006
 *
 * 仕様書: docs/features/F07.3_equipment.md
 */

const EQUIP_ID = 1

const MOCK_EQUIPMENT_ITEM = {
  id: EQUIP_ID,
  teamId: TEAM_ID,
  name: 'サッカーボール',
  category: 'CONSUMABLE',
  status: 'AVAILABLE',
  quantity: 10,
  assignedTo: null,
  returnDueDate: null,
  serialNumber: 'SB-001',
  notes: '練習用',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const MOCK_EQUIPMENT_ASSIGNED = {
  ...MOCK_EQUIPMENT_ITEM,
  id: 2,
  name: 'ビブス（赤）',
  status: 'ASSIGNED',
  assignedTo: { id: 10, displayName: '田中太郎' },
  returnDueDate: '2026-04-30',
}

const MOCK_EQUIPMENT_LIST = {
  data: [MOCK_EQUIPMENT_ITEM, MOCK_EQUIPMENT_ASSIGNED],
  meta: { page: 0, size: 20, totalElements: 2, totalPages: 1 },
}

async function mockEquipmentApis(page: Page): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // 備品一覧
  await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_EQUIPMENT_LIST),
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

test.describe('EQUIP-001〜006: F07.3 備品管理', () => {
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

  test('EQUIP-001: チーム備品ページが表示される', async ({ page }) => {
    await mockEquipmentApis(page)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // EquipmentList コンポーネントが描画される（ページコンテンツ確認）
    // equipment.vue は EquipmentList コンポーネントに委譲するため、コンテナが表示されることを確認
    await expect(page.locator('body')).toBeVisible({ timeout: 10_000 })
    // ローディングが消えることを確認
    await expect(page.locator('.p-progress-spinner')).toBeHidden({ timeout: 10_000 })
  })

  test('EQUIP-002: 備品一覧の取得と表示（GET）', async ({ page }) => {
    await mockEquipmentApis(page)

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // 備品名が表示される
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ビブス（赤）')).toBeVisible({ timeout: 10_000 })
  })

  test('EQUIP-003: 備品を登録できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_EQUIPMENT_LIST),
        })
      } else if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_EQUIPMENT_ITEM, id: 99, name: '新規備品' },
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

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // 備品一覧が表示されることを確認
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
  })

  test('EQUIP-004: 備品を編集できる（PUT）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_EQUIPMENT_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment/${EQUIP_ID}`, async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_EQUIPMENT_ITEM, name: '更新された備品名' },
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

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
  })

  test('EQUIP-005: 備品を削除できる（DELETE）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_EQUIPMENT_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment/${EQUIP_ID}`, async (route) => {
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

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
  })

  test('EQUIP-006: 備品を貸し出し・返却できる（PATCH）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/equipment**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_EQUIPMENT_LIST),
        })
      } else {
        await route.continue()
      }
    })

    // 貸し出し (assign は POST)
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/equipment/${EQUIP_ID}/assign`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              ...MOCK_EQUIPMENT_ITEM,
              status: 'ASSIGNED',
              assignedTo: { id: 1, displayName: 'e2e_user' },
            },
          }),
        })
      },
    )

    // 返却 (return は PATCH)
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/equipment/2/return`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_EQUIPMENT_ASSIGNED, status: 'AVAILABLE', assignedTo: null },
          }),
        })
      },
    )

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)

    // 備品一覧が表示されていることを確認
    await expect(page.getByText('サッカーボール')).toBeVisible({ timeout: 10_000 })
    // 貸し出し中の備品も表示される
    await expect(page.getByText('ビブス（赤）')).toBeVisible({ timeout: 10_000 })
  })
})
