import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_BLOCKS = {
  data: [
    {
      id: 1,
      blockedUser: {
        id: 99,
        displayName: '拒否テストユーザー',
        contactHandle: 'blocked_user',
        avatarUrl: null,
      },
      createdAt: '2026-04-01T00:00:00Z',
    },
  ],
}

test.describe('SET-CNT-020〜023: 申請事前拒否リスト', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/contact-request-blocks**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_BLOCKS),
        })
      } else if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 })
      }
    })
  })

  test('SET-CNT-020: 申請事前拒否リストページが表示される', async ({ page }) => {
    await page.goto('/settings/contact-request-blocks')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '申請事前拒否リスト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-CNT-021: 拒否リストのユーザーが表示される', async ({ page }) => {
    await page.goto('/settings/contact-request-blocks')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '申請事前拒否リスト' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('拒否テストユーザー')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('@blocked_user')).toBeVisible()
  })

  test('SET-CNT-022: 拒否リストが空の場合は空状態メッセージが表示される', async ({ page }) => {
    await page.route('**/api/v1/contact-request-blocks**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/settings/contact-request-blocks')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '申請事前拒否リスト' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('申請を事前拒否しているユーザーはいません')).toBeVisible({
      timeout: 5_000,
    })
  })

  test('SET-CNT-023: 解除ボタンでDELETEリクエストが送信される', async ({ page }) => {
    let deleteCalled = false
    await page.route('**/api/v1/contact-request-blocks/**', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      }
    })

    await page.goto('/settings/contact-request-blocks')
    await waitForHydration(page)

    await expect(page.getByText('拒否テストユーザー')).toBeVisible({ timeout: 10_000 })
    await page.getByRole('button', { name: '解除' }).click()

    await expect(async () => {
      expect(deleteCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })
})
