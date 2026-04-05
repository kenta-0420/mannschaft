import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.describe('ADMIN-009〜017: 管理画面表示確認', () => {
  test('ADMIN-009: モジュール管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/modules**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/modules')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'モジュール管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-010: テンプレート管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/templates')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'テンプレート管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-011: 組織管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/dashboard/organizations**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { content: [], totalElements: 0, totalPages: 0 } }),
      })
    })

    await page.goto('/admin/organizations')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '組織管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('ADMIN-012: フィードバック管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/feedbacks**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/feedbacks')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フィードバック管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-013: メンテナンス管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/maintenance-schedules**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/maintenance')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'メンテナンス予定管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-014: 通報・モデレーションページが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/moderation/reports**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/moderation')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '通報・モデレーション' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-015: 監査ログページが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/audit-logs**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: { totalElements: 0 } }),
      })
    })

    await page.goto('/admin/audit-logs')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '監査ログ' })).toBeVisible({ timeout: 10_000 })
  })

  test('ADMIN-016: レポート管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/admin/reports/stats**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: {} }),
      })
    })
    await page.route('**/api/v1/system-admin/reports**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/reports')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'レポート管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-017: 広告主アカウント管理ページが表示される', async ({ page }) => {
    await page.route('**/api/v1/system-admin/**advertiser**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/admin/advertiser-accounts')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '広告主アカウント管理' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
