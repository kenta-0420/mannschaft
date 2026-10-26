import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_FEED = {
  data: {
    pinned: [],
    posts: [
      {
        id: 1,
        scopeType: 'PUBLIC',
        scopeId: 0,
        user: { id: 1, displayName: 'テストユーザー', avatarUrl: null, handle: null },
        postedAs: null,
        parentId: null,
        content: '今日の練習お疲れ様でした！',
        isPinned: false,
        isBookmarked: false,
        isEdited: false,
        isTruncated: false,
        reactionCount: 3,
        replyCount: 1,
        attachmentCount: 0,
        repostCount: 0,
        attachments: [],
        myReactions: [],
        reactionSummary: {},
        repostOf: null,
        poll: null,
        status: 'PUBLISHED',
        scheduledAt: null,
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ],
  },
  meta: { nextCursor: null, limit: 20, hasNext: false },
}

test.describe('GLOBAL-006〜007: タイムライン', () => {
  test('GLOBAL-006: タイムラインページが表示される', async ({ page }) => {
    await page.route('**/api/v1/timeline/feed**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FEED),
      })
    })

    await page.goto('/timeline')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'タイムライン' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('GLOBAL-007: タイムラインに投稿が表示される', async ({ page }) => {
    await page.route('**/api/v1/timeline/feed**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FEED),
      })
    })

    await page.goto('/timeline')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'タイムライン' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('今日の練習お疲れ様でした！')).toBeVisible({ timeout: 8_000 })
  })
})
