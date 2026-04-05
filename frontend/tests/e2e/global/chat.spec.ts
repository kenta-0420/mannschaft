import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_CHANNELS = [
  {
    id: 1,
    name: '全体チャット',
    type: 'PUBLIC',
    description: 'チーム全体の連絡用',
    memberCount: 10,
    unreadCount: 2,
    lastMessage: {
      content: 'こんにちは',
      senderName: 'テストユーザー',
      sentAt: '2026-03-01T10:00:00Z',
    },
    createdAt: '2026-01-01T00:00:00Z',
  },
]

test.describe('GLOBAL-005: チャット', () => {
  test('GLOBAL-005: チャットページが表示されチャンネル一覧が見える', async ({ page }) => {
    await page.route('**/api/v1/chat/channels**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_CHANNELS,
          meta: { page: 0, size: 50, totalElements: 1, totalPages: 1 },
        }),
      })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('全体チャット')).toBeVisible({ timeout: 5_000 })
  })
})
