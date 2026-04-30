import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockAdminApis } from './helpers'

test.describe('ADMIN-020〜043: 管理画面表示確認（拡張）', () => {
  test.beforeEach(async ({ page }) => {
    await mockAdminApis(page)

    await page.route('**/api/v1/system-admin/**', async (route) => {
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

  test('ADMIN-020: 与信枠 増額申請管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/ad-credit-limit-requests')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /与信枠/ })).toBeVisible({ timeout: 10_000 })
  })

  test('ADMIN-021: 広告料金カード管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/ad-rate-cards')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '広告料金カード管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-022: アフィリエイト設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/affiliate-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アフィリエイト設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-023: 異議申立て管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/appeals')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '異議申立て管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-024: ブログ管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/blog-management')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ブログ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-025: 掲示板カテゴリ管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/bulletin-categories')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板カテゴリ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-026: 割引キャンペーンページが表示される', async ({ page }) => {
    await page.goto('/admin/campaigns')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /割引キャンペーン/ })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-027: 備品管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/equipment')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '備品管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-028: Googleカレンダー設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/google-calendar')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /Google.*カレンダー/ })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-029: LINE設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/line-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'LINE設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-030: MEMBER権限設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/member-permissions')
    await waitForHydration(page)
    await expect(
      page.getByRole('heading', { name: /MEMBER権限|権限設定/ }),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('ADMIN-031: メンバー紹介管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/member-profiles')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバー紹介管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-032: モジュール価格管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/module-pricing')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'モジュール価格管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-033: 組織数課金設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/org-billing')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '組織数課金設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-034: パッケージ管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/packages')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パッケージ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-035: 権限グループ管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/permission-groups')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '権限グループ管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-036: プロモーション管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/promotions')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロモーション管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-037: 領収書管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/receipts')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '領収書管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-038: 予約管理設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/reservation-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '予約管理設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-039: スケジュール設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/schedule-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-040: シーズナル壁紙管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/seasonal-wallpapers')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'シーズナル壁紙管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-041: SNS設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/sns-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'SNS設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-042: ストレージプラン管理ページが表示される', async ({ page }) => {
    await page.goto('/admin/storage-plans')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ストレージプラン管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-043: 消費税設定ページが表示される', async ({ page }) => {
    await page.goto('/admin/tax-settings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '消費税設定' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
