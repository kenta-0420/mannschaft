import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ORG_ID,
  OTHER_ORG_ID,
  mockCatchAllApis,
  setupAdminAuth,
  setupOrganizationScope,
  setupPersonalScope,
} from './_helpers'

/**
 * F08.7 Phase 11-α — admin 4 画面 共通シナリオ E2E。
 *
 * <p>シナリオ（3 ケース）:</p>
 * <ol>
 *   <li>BUDGET-COMMON-01: バックエンド 503 でロード失敗のエラー通知が表示される</li>
 *   <li>BUDGET-COMMON-02: 多テナント分離 — 別組織スコープ時に X-Organization-Id が反映される
 *       （4 画面横断 / dashboard で代表検証）</li>
 *   <li>BUDGET-COMMON-03: 個人スコープ時は 4 画面いずれも誘導メッセージを表示し API 呼び出しが発生しない</li>
 * </ol>
 */

test.describe('F08.7 Phase 11-α: shift-budget/common', () => {
  test('BUDGET-COMMON-01: 503 エラーでロード失敗エラー通知が表示される', async ({ page }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, ORG_ID)
    await mockCatchAllApis(page)

    // 一覧 GET だけ 503 を返す
    await page.route('**/api/v1/shift-budget/allocations?**', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'SERVICE_UNAVAILABLE' } }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // i18n "shiftBudget.allocation.loadError" = "割当一覧の取得に失敗しました"
    await expect(page.getByText('割当一覧の取得に失敗しました').first()).toBeVisible({
      timeout: 10_000,
    })
  })

  test('BUDGET-COMMON-02: 別組織スコープ時、X-Organization-Id が API ヘッダに反映される', async ({
    page,
  }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, OTHER_ORG_ID, '別組織')
    await mockCatchAllApis(page)

    let receivedOrgIdHeader: string | null = null
    await page.route('**/api/v1/shift-budget/allocations?**', async (route, request) => {
      if (request.method() === 'GET') {
        receivedOrgIdHeader = request.headers()['x-organization-id'] ?? null
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { items: [], page: 0, size: 20, total: 0 },
          }),
        })
      }
      else {
        await route.fallback()
      }
    })

    // dashboard は listAllocations(size=100) を呼ぶ
    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    // KPI が描画される（empty でも OK / WARN / ... 4 ラベルが表示される）
    await expect(page.getByText('予算消化ダッシュボード').first()).toBeVisible({ timeout: 10_000 })

    expect(receivedOrgIdHeader).toBe(String(OTHER_ORG_ID))
  })

  test('BUDGET-COMMON-03: 個人スコープでは誘導メッセージが表示され API は呼ばれない', async ({
    page,
  }) => {
    await setupAdminAuth(page)
    await setupPersonalScope(page)
    await mockCatchAllApis(page)

    let allocationApiCalled = false
    await page.route('**/api/v1/shift-budget/allocations?**', async (route) => {
      allocationApiCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { items: [], page: 0, size: 20, total: 0 } }),
      })
    })

    // 4 画面それぞれで誘導メッセージが表示されることを確認
    const pages = [
      '/admin/shift-budget/allocations',
      '/admin/shift-budget/dashboard',
      '/admin/shift-budget/alerts',
      '/admin/shift-budget/failed-events',
    ]

    for (const path of pages) {
      await page.goto(path)
      await waitForHydration(page)

      // i18n "shiftBudget.scope.selectOrganization"
      await expect(
        page.getByText('シフト予算管理は組織スコープでのみ利用できます', { exact: false }),
      ).toBeVisible({ timeout: 10_000 })
    }

    // 個人スコープでは API は呼ばれていない（早期 return）
    expect(allocationApiCalled).toBe(false)
  })
})
