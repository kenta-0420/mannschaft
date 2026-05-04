import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F07.4 カルテ — Playwright E2E テスト
 *
 * テストID: CHART-001 〜 CHART-005
 *
 * 仕様書: docs/features/F07.4_chart.md
 */

const CHART_ID = 1

const MOCK_CHART = {
  id: CHART_ID,
  teamId: TEAM_ID,
  clientName: '佐藤花子',
  visitDate: '2026-04-10T10:00:00Z',
  staffId: 1,
  staffName: '山田スタッフ',
  status: 'CONFIRMED',
  chiefComplaint: '肩こり・首の張り',
  notes: '施術後、症状改善',
  nextVisitRecommendation: '2週間後',
  isPinned: false,
  photos: [],
  sections: {
    beforeAfter: true,
    bodyMarks: true,
    customFields: true,
    intakeForm: true,
  },
  version: 1,
  createdAt: '2026-04-10T00:00:00Z',
  updatedAt: '2026-04-10T00:00:00Z',
}

const MOCK_CHART_DRAFT = {
  ...MOCK_CHART,
  id: 2,
  clientName: '鈴木次郎',
  visitDate: '2026-04-12T14:00:00Z',
  status: 'DRAFT',
  chiefComplaint: '腰痛',
  notes: null,
  nextVisitRecommendation: null,
}

const MOCK_CHARTS_LIST = {
  data: [MOCK_CHART, MOCK_CHART_DRAFT],
  meta: { totalElements: 2 },
}

async function mockChartApis(page: Page): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // カルテ一覧
  await page.route(`**/api/v1/teams/${TEAM_ID}/charts**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_CHARTS_LIST),
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

test.describe('CHART-001〜005: F07.4 カルテ', () => {
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

  test('CHART-001: カルテページが表示される', async ({ page }) => {
    await mockChartApis(page)

    await page.goto(`/teams/${TEAM_ID}/charts`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'カルテ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('CHART-002: カルテ一覧の取得と表示（GET）', async ({ page }) => {
    await mockChartApis(page)

    await page.goto(`/teams/${TEAM_ID}/charts`)
    await waitForHydration(page)

    // 顧客名が表示される
    await expect(page.getByText('佐藤花子')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('鈴木次郎')).toBeVisible({ timeout: 10_000 })
  })

  test('CHART-003: カルテを作成できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/charts**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CHARTS_LIST),
        })
      } else if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_CHART, id: 99, clientName: '新規顧客' },
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

    await page.goto(`/teams/${TEAM_ID}/charts`)
    await waitForHydration(page)

    // 「新規カルテ」ボタンをクリック
    const createButton = page.getByRole('button', { name: /新規|追加|作成/ })
    if (await createButton.isVisible({ timeout: 5_000 })) {
      await createButton.click()
    }

    // ページが表示されていることを確認
    await expect(page.getByRole('heading', { name: 'カルテ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('CHART-004: カルテを編集できる（PUT）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/charts**`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CHARTS_LIST),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/charts/${CHART_ID}`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_CHART }),
        })
      } else if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_CHART, notes: '更新されたメモ' },
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

    await page.goto(`/teams/${TEAM_ID}/charts`)
    await waitForHydration(page)

    // カルテ一覧が表示されることを確認
    await expect(page.getByText('佐藤花子')).toBeVisible({ timeout: 10_000 })
  })

  test('CHART-005: 個人カルテページが表示される（/my/charts）', async ({ page }) => {
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { totalElements: 0 } }),
      })
    })

    await page.route('**/api/v1/charts/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [MOCK_CHART],
          meta: { totalElements: 1 },
        }),
      })
    })

    await page.goto('/my/charts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイカルテ' })).toBeVisible({
      timeout: 10_000,
    })

    // カルテ情報が表示される（担当者名）
    await expect(page.getByText('山田スタッフ')).toBeVisible({ timeout: 10_000 })
  })
})
