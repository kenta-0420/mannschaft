import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_ANNOUNCEMENTS = {
  data: [
    {
      id: 1,
      title: 'テストお知らせ',
      content: 'テスト内容です',
      status: 'PUBLISHED',
      createdAt: '2026-03-01T10:00:00Z',
      publishedAt: '2026-03-01T10:00:00Z',
    },
  ],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
}

test.describe('ADMIN-005〜006: お知らせ管理', () => {
  test('ADMIN-005: お知らせ管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/announcements**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ANNOUNCEMENTS),
      })
    })

    await page.goto('/admin/announcements')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お知らせ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-006: お知らせ一覧が表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/announcements**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ANNOUNCEMENTS),
      })
    })

    await page.goto('/admin/announcements')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お知らせ管理' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('テストお知らせ')).toBeVisible({ timeout: 5_000 })
  })
})
