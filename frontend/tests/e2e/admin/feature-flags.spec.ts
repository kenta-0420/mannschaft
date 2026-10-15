import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_FLAGS = {
  data: [
    {
      flagKey: 'FEATURE_CHAT',
      isEnabled: true,
      description: 'チャット機能',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      flagKey: 'FEATURE_MATCHING',
      isEnabled: false,
      description: 'マッチング機能',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ],
}

test.describe('ADMIN-007〜008: 機能フラグ管理', () => {
  test('ADMIN-007: 機能フラグ管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/feature-flags**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FLAGS),
      })
    })

    await page.goto('/admin/feature-flags')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '機能フラグ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-008: 機能フラグ一覧が表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/feature-flags**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FLAGS),
      })
    })

    await page.goto('/admin/feature-flags')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '機能フラグ管理' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('FEATURE_CHAT')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('FEATURE_MATCHING')).toBeVisible()
  })
})
