import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('GLOBAL-008〜011: グローバルその他ページ', () => {
  test('GLOBAL-008: マイTODOページが表示される', async ({ page }) => {
    await page.route('**/api/v1/todos/my**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/todos')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'マイTODO' })).toBeVisible({ timeout: 10_000 })
  })

  test('GLOBAL-009: メンションページが表示される', async ({ page }) => {
    await page.route('**/api/v1/mentions**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/mentions')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'メンション' })).toBeVisible({ timeout: 10_000 })
  })

  test('GLOBAL-010: コルクボードページが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/corkboards**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/corkboard')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'コルクボード' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'ボードを作成' })).toBeVisible()
  })

  test('GLOBAL-011: ダッシュボードページが表示される', async ({ page }) => {
    await page.route('**/api/v1/teams/my**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/organizations/my**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/users/me/dashboard**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: {} }),
      })
    })

    await page.goto('/dashboard')
    await waitForHydration(page)

    // ダッシュボードは挨拶テキスト + マイチーム/マイ組織が表示される
    await expect(page.getByText('マイチーム')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('マイ組織')).toBeVisible()
  })
})
