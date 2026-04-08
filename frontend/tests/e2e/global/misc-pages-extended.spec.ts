import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('GLOBAL-012〜013: グローバルページ 追加表示テスト', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })
  })

  test('GLOBAL-012: マッチングページが表示される', async ({ page }) => {
    await page.goto('/matching')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /対戦・交流|マッチング/ }).first()).toBeVisible({ timeout: 10_000 })
  })

  test('GLOBAL-013: タイムライン投稿詳細ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/timeline/posts/1**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1, body: 'テスト投稿', authorName: 'テストユーザー',
            createdAt: '2026-01-01T00:00:00Z', likeCount: 0, commentCount: 0,
          },
        }),
      })
    })
    await page.goto('/timeline/1')
    await waitForHydration(page)
    await expect(page.getByText('テスト投稿')).toBeVisible({ timeout: 10_000 })
  })
})
