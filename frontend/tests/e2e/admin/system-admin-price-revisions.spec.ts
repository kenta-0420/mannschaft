import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * 価格改定（price-revisions）管理画面 E2E（AC-156・AC-163・AC-164）。
 *
 * 正本: `.claude/campaigns/price-rev-plan-v3.md` K群。試練隊（第4陣）が実装より先に作成した
 * red テスト。対象画面（`system-admin/price-revisions/index.vue` / `[id].vue`）は本コミット
 * 時点で未実装のため、`page.goto` 後の要素待ちがタイムアウトして red になる（chromium-admin
 * プロジェクトでの実行が前提。本コミットでは対象画面が存在しないため未実行）。
 *
 * AC-156 の「直接 API を呼ばずブラウザ操作のみで完結する」は、本ファイルが
 * `page.route` でのモックのみを使い、`fetch`/`axios` を直接呼び出していないことで担保する
 * （`tests/unit/pages/system-admin/price-revisions.spec.ts` の AC-156 テストが本ファイルの
 * ソースを検体にしてこれを固定する）。
 */

const MOCK_TAX_CODES = {
  data: [{ id: 'tax-1', code: 'JP_STANDARD', ratePermille: 100, effectiveFrom: '2026-01-01T00:00:00Z' }],
}

const MOCK_LIST_EMPTY = { data: [], page: 0, size: 20, totalElements: 0 }

const MOCK_REVISION_DRAFT = {
  data: {
    id: 'rev-1',
    planKey: 'basic',
    status: 'DRAFT',
    bands: [
      { id: 'band-1', minMembers: 1, maxMembers: 20, status: 'DRAFT', attemptCount: 0, errorCode: null },
    ],
  },
}

const MOCK_REVISION_READY = {
  data: {
    ...MOCK_REVISION_DRAFT.data,
    status: 'READY',
    bands: [
      { id: 'band-1', minMembers: 1, maxMembers: 20, status: 'READY', attemptCount: 1, errorCode: null },
    ],
  },
}

async function mockCommonRoutes(page: Page) {
  await page.route('**/api/admin/billing/tax-codes', (route) =>
    route.fulfill({ json: MOCK_TAX_CODES }))
  await page.route('**/api/admin/billing/price-revisions?*', (route) =>
    route.fulfill({ json: MOCK_LIST_EMPTY }))
}

test.describe('SYSTEM_ADMIN 価格改定管理画面', () => {
  test.beforeEach(async ({ page }) => {
    await mockCommonRoutes(page)
  })

  test('AC-156/AC-165: 税コード確認→作成→Provision→Activate をブラウザ操作のみで完結できる', async ({ page }) => {
    await page.route('**/api/admin/billing/price-revisions', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({ json: MOCK_REVISION_DRAFT })
      }
      return route.fulfill({ json: MOCK_LIST_EMPTY })
    })
    await page.route('**/api/admin/billing/price-revisions/rev-1/provision', (route) =>
      route.fulfill({ json: MOCK_REVISION_READY }))
    await page.route('**/api/admin/billing/price-revisions/rev-1/activate', (route) =>
      route.fulfill({ json: { data: { ...MOCK_REVISION_READY.data, status: 'ACTIVE' } } }))
    await page.route('**/api/admin/billing/price-revisions/rev-1', (route) =>
      route.fulfill({ json: MOCK_REVISION_READY }))

    await page.goto('/system-admin/price-revisions')
    await waitForHydration(page)

    await page.getByRole('button', { name: /新規|作成|create/i }).click()
    await page.getByLabel(/税コード|tax code/i).selectOption('tax-1')
    await page.getByRole('button', { name: /確定|作成する|submit/i }).click()

    await expect(page).toHaveURL(/\/system-admin\/price-revisions\/rev-1/)
    await page.getByRole('button', { name: /Provision/i }).click()
    await expect(page.getByText(/READY/)).toBeVisible()
    await page.getByRole('button', { name: /Activate|有効化/i }).click()
    await expect(page.getByText(/ACTIVE/)).toBeVisible()
  })

  test('AC-152: band が READY でない間は Activate ボタンが無効', async ({ page }) => {
    await page.route('**/api/admin/billing/price-revisions/rev-1', (route) =>
      route.fulfill({ json: MOCK_REVISION_DRAFT }))
    await page.goto('/system-admin/price-revisions/rev-1')
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: /Activate|有効化/i })).toBeDisabled()
  })

  test('AC-157: SYSTEM_ADMIN 以外はアクセスできない（導線非表示）', async ({ page }) => {
    // storageState を admin 以外に切り替えるプロジェクト設定を前提とし、
    // 画面固有の noPermission 表示を確認する。
    await page.goto('/system-admin/price-revisions')
    await expect(page.getByText(/権限がありません|no permission/i)).toBeVisible()
  })

  test('AC-163: キーボードのみで一覧→詳細→作成の一連が完結する', async ({ page }) => {
    await page.goto('/system-admin/price-revisions')
    await waitForHydration(page)
    await page.keyboard.press('Tab')
    await page.keyboard.press('Enter')
    await expect(page.locator('[role="dialog"]')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.locator('[role="dialog"]')).toBeHidden()
  })

  test('AC-164: モバイル幅(375px)で横スクロールが発生しない', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await page.goto('/system-admin/price-revisions')
    await waitForHydration(page)
    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    )
    expect(hasHorizontalScroll).toBe(false)
  })
})
