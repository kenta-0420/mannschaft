import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

/**
 * F03.7 キュー管理 E2E テスト
 *
 * テストID: QUEUE-001〜005
 *
 * 方針:
 * - API モックを使用（page.route）
 * - 認証: storageState（tests/e2e/.auth/user.json）を使用
 * - 各テストは beforeEach で独立したモックを設定
 *
 * 仕様書: docs/features/F03.7_queue_management.md
 */

const MOCK_QUEUE_STATUS = {
  isOpen: true,
  totalWaiting: 3,
  counters: [
    { id: 1, name: '窓口1', status: 'OPEN', currentTicketNumber: 'A003', waitingCount: 3 },
  ],
}

const MOCK_CATEGORIES = [
  { id: 1, name: '一般受付', queueMode: 'FIFO', prefix: 'A', maxQueueSize: 50 },
]

const MOCK_TICKETS = [
  { id: 1, ticketNumber: 'A001', status: 'CALLED', queuePosition: 1, categoryId: 1 },
  { id: 2, ticketNumber: 'A002', status: 'WAITING', queuePosition: 2, categoryId: 1 },
  { id: 3, ticketNumber: 'A003', status: 'WAITING', queuePosition: 3, categoryId: 1 },
]

const MOCK_MY_TICKETS = [
  { id: 3, ticketNumber: 'A003', status: 'WAITING', queuePosition: 3, categoryId: 1 },
]

test.describe('QUEUE-001〜005: F03.7 キュー管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    // キューステータスAPIをモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/status`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_QUEUE_STATUS }),
      })
    })

    // カテゴリ一覧APIをモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/categories`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_CATEGORIES }),
      })
    })

    // チケット一覧APIをモック（カテゴリ別）
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/categories/*/tickets`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TICKETS }),
      })
    })

    // カウンター一覧APIをモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/counters`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_QUEUE_STATUS.counters }),
      })
    })

    // 自分のチケットAPIをモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/tickets/me`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_MY_TICKETS }),
      })
    })

    // チケット個別操作APIをモック
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/tickets/*`, async (route) => {
      const method = route.request().method()
      if (method === 'DELETE') {
        await route.fulfill({ status: 204, body: '' })
      } else if (method === 'PATCH') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { id: 3, status: 'CANCELLED' } }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_MY_TICKETS[0] }),
        })
      }
    })

    // カウンタートークン発行APIをモック
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/queue/counters/*/tickets`,
      async (route) => {
        if (route.request().method() === 'POST') {
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              data: { id: 99, ticketNumber: 'A099', status: 'WAITING', queuePosition: 4 },
            }),
          })
        } else {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ data: MOCK_TICKETS }),
          })
        }
      },
    )
  })

  test('QUEUE-001: キュー管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('QUEUE-002: キュー一覧の取得と表示（GET）', async ({ page }) => {
    let statusApiCalled = false
    await page.route(`**/api/v1/teams/${TEAM_ID}/queue/status`, async (route) => {
      statusApiCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_QUEUE_STATUS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({
      timeout: 10_000,
    })

    expect(statusApiCalled).toBe(true)
  })

  test('QUEUE-003: キューに参加できる（受付ボタンが表示される）', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({
      timeout: 10_000,
    })

    // 「受付する」ボタンが表示されていることを確認
    await expect(page.getByRole('button', { name: '受付する' })).toBeVisible({ timeout: 10_000 })
  })

  test('QUEUE-004: 受付ボタンをクリックするとQueueTicketFormダイアログが開く', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({
      timeout: 10_000,
    })

    // 「受付する」ボタンをクリック
    await page.getByRole('button', { name: '受付する' }).click()

    // QueueTicketForm ダイアログが表示されることを確認
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
  })

  test('QUEUE-005: キュータブが表示され順番待ち状況タブが存在する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({
      timeout: 10_000,
    })

    // タブが表示されていることを確認
    await expect(page.getByText('待ち状況')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('窓口操作')).toBeVisible({ timeout: 10_000 })
  })
})
