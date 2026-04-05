import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_NOTIFICATIONS = [
  {
    id: 1,
    notificationType: 'MENTION',
    priority: 'NORMAL',
    title: 'メンションされました',
    body: null,
    sourceType: 'TIMELINE_POST',
    sourceId: 1,
    scopeType: 'PUBLIC',
    scopeId: null,
    scopeName: null,
    actionUrl: '/timeline/1',
    actor: { id: 2, displayName: 'テストユーザー', avatarUrl: null },
    isRead: false,
    readAt: null,
    snoozedUntil: null,
    createdAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 2,
    notificationType: 'SYSTEM',
    priority: 'NORMAL',
    title: 'システム通知',
    body: 'メンテナンスのお知らせ',
    sourceType: 'SYSTEM',
    sourceId: null,
    scopeType: 'PUBLIC',
    scopeId: null,
    scopeName: null,
    actionUrl: null,
    actor: null,
    isRead: true,
    readAt: '2026-02-28T10:00:00Z',
    snoozedUntil: null,
    createdAt: '2026-02-28T09:00:00Z',
  },
]

test.describe('GLOBAL-003〜004: 通知', () => {
  test('GLOBAL-003: 通知一覧ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/notifications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_NOTIFICATIONS,
          meta: { nextCursor: null, limit: 20, hasNext: false },
        }),
      })
    })

    await page.goto('/notifications')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '通知' })).toBeVisible({ timeout: 10_000 })
  })

  test('GLOBAL-004: 通知ページに通知アイテムが表示される', async ({ page }) => {
    await page.route('**/api/v1/notifications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_NOTIFICATIONS,
          meta: { nextCursor: null, limit: 20, hasNext: false },
        }),
      })
    })

    await page.goto('/notifications')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '通知' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('メンションされました')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('システム通知')).toBeVisible()
  })
})
