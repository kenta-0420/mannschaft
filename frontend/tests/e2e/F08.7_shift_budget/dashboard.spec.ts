import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ORG_ID,
  buildAllocation,
  mockAllocationsList,
  mockCatchAllApis,
  setupAdminAuth,
  setupOrganizationScope,
} from './_helpers'

/**
 * F08.7 Phase 11-α — admin 予算ダッシュボード画面 E2E。
 *
 * <p>対象画面: {@code /admin/shift-budget/dashboard}（{@code dashboard.vue}）。
 * 設計書 §7.4。</p>
 *
 * <p>シナリオ（5 ケース）:</p>
 * <ol>
 *   <li>BUDGET-DASH-01: KPI 4 段階（OK/WARN/EXCEEDED/SEVERE_EXCEEDED）が正しく集計される</li>
 *   <li>BUDGET-DASH-02: 月次締め成功 → 成功通知</li>
 *   <li>BUDGET-DASH-03: 月次締め重複（既締め）→ info 通知</li>
 *   <li>BUDGET-DASH-04: 月次締めバリデーション（YYYY-MM 形式違反）→ エラー通知</li>
 *   <li>BUDGET-DASH-05: 関連画面リンク（割当/警告/失敗イベント）が遷移する</li>
 * </ol>
 */

test.describe('F08.7 Phase 11-α: shift-budget/dashboard', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, ORG_ID)
    await mockCatchAllApis(page)
  })

  test('BUDGET-DASH-01: KPI 4 段階が正しく集計表示される', async ({ page }) => {
    // 消化率: OK<0.8 / WARN[0.8,1.0) / EXCEEDED[1.0,1.2) / SEVERE_EXCEEDED>=1.2
    const items = [
      // OK x2
      buildAllocation({ id: 1, allocated_amount: 100, consumed_amount: 50 }), // 0.5
      buildAllocation({ id: 2, allocated_amount: 100, consumed_amount: 70 }), // 0.7
      // WARN x1
      buildAllocation({ id: 3, allocated_amount: 100, consumed_amount: 90 }), // 0.9
      // EXCEEDED x1
      buildAllocation({ id: 4, allocated_amount: 100, consumed_amount: 110 }), // 1.1
      // SEVERE_EXCEEDED x1
      buildAllocation({ id: 5, allocated_amount: 100, consumed_amount: 150 }), // 1.5
    ]
    await mockAllocationsList(page, items, { size: 100 })

    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    // ヘッダ "予算消化ダッシュボード"
    await expect(page.getByText('予算消化ダッシュボード').first()).toBeVisible({ timeout: 10_000 })

    // KPI ラベル + 数値
    // i18n "shiftBudget.dashboard.kpi.ok" = "正常"
    await expect(page.getByText('正常', { exact: true }).first()).toBeVisible()
    // i18n "shiftBudget.dashboard.kpi.warn" = "警告 (80% 以上)"
    await expect(page.getByText('警告 (80% 以上)')).toBeVisible()
    // i18n "shiftBudget.dashboard.kpi.exceeded" = "超過 (100% 以上)"
    await expect(page.getByText('超過 (100% 以上)')).toBeVisible()
    // i18n "shiftBudget.dashboard.kpi.severeExceeded" = "重大超過 (120% 以上)"
    await expect(page.getByText('重大超過 (120% 以上)')).toBeVisible()

    // 数値 (OK=2, WARN=1, EXCEEDED=1, SEVERE_EXCEEDED=1)
    // 各 Card に <p class="text-3xl font-bold ..."> が KPI 値として表示される
    const kpiNumbers = page.locator('p.text-3xl.font-bold')
    await expect(kpiNumbers.nth(0)).toHaveText('2')
    await expect(kpiNumbers.nth(1)).toHaveText('1')
    await expect(kpiNumbers.nth(2)).toHaveText('1')
    await expect(kpiNumbers.nth(3)).toHaveText('1')
  })

  test('BUDGET-DASH-02: 月次締め実行で成功通知 + 再取得', async ({ page }) => {
    await mockAllocationsList(page, [buildAllocation()], { size: 100 })

    let monthlyClosePosted = false
    await page.route('**/api/v1/shift-budget/monthly-close', async (route, request) => {
      if (request.method() === 'POST') {
        monthlyClosePosted = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              organization_id: ORG_ID,
              year_month: '2026-04',
              closed_allocations: 5,
              already_closed_allocations: 0,
              closed_consumptions: 42,
              processed_organization_ids: [ORG_ID],
              failed_organization_ids: [],
              already_closed_organization_ids: [],
            },
          }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    // i18n "shiftBudget.monthlyClose.button" = "月次締め実行"
    await page.getByRole('button', { name: '月次締め実行' }).click()

    // モーダル表示。yearMonth は前月が初期値
    await expect(page.getByRole('dialog')).toBeVisible()

    // 入力欄を 2026-04 に上書き
    const ymInput = page.getByPlaceholder('YYYY-MM')
    await ymInput.fill('2026-04')

    // 実行
    // i18n "shiftBudget.monthlyClose.execute" = "実行"
    await page.getByRole('button', { name: '実行', exact: true }).click()

    // 成功通知 "月次締め完了: 2026-04（消化レコード 42 件を確定）"
    await expect(page.getByText('月次締め完了', { exact: false }).first()).toBeVisible({
      timeout: 10_000,
    })
    expect(monthlyClosePosted).toBe(true)
  })

  test('BUDGET-DASH-03: 月次締め重複（既締め）で info 通知', async ({ page }) => {
    await mockAllocationsList(page, [buildAllocation()], { size: 100 })

    await page.route('**/api/v1/shift-budget/monthly-close', async (route, request) => {
      if (request.method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              organization_id: ORG_ID,
              year_month: '2026-04',
              closed_allocations: 0,
              already_closed_allocations: 5,
              closed_consumptions: 0,
              processed_organization_ids: [],
              failed_organization_ids: [],
              already_closed_organization_ids: [ORG_ID],
            },
          }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    await page.getByRole('button', { name: '月次締め実行' }).click()
    const ymInput = page.getByPlaceholder('YYYY-MM')
    await ymInput.fill('2026-04')
    await page.getByRole('button', { name: '実行', exact: true }).click()

    // i18n "shiftBudget.monthlyClose.alreadyClosed" = "該当月は既に締め済みです"
    await expect(page.getByText('該当月は既に締め済みです').first()).toBeVisible({
      timeout: 10_000,
    })
  })

  test('BUDGET-DASH-04: 月次締めの YYYY-MM 形式違反 → エラー通知（POST されない）', async ({
    page,
  }) => {
    await mockAllocationsList(page, [buildAllocation()], { size: 100 })

    let monthlyClosePosted = false
    await page.route('**/api/v1/shift-budget/monthly-close', async (route, request) => {
      if (request.method() === 'POST') {
        monthlyClosePosted = true
      }
      await route.fallback()
    })

    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    await page.getByRole('button', { name: '月次締め実行' }).click()
    const ymInput = page.getByPlaceholder('YYYY-MM')
    await ymInput.fill('invalid')
    await page.getByRole('button', { name: '実行', exact: true }).click()

    // i18n "shiftBudget.monthlyClose.error" = "月次締めに失敗しました"
    await expect(page.getByText('月次締めに失敗しました').first()).toBeVisible({ timeout: 5_000 })
    expect(monthlyClosePosted).toBe(false)
  })

  test('BUDGET-DASH-05: 関連画面リンクが各 admin 画面へ遷移する', async ({ page }) => {
    await mockAllocationsList(page, [], { size: 100 })

    await page.goto('/admin/shift-budget/dashboard')
    await waitForHydration(page)

    // 割当一覧へ
    // i18n "shiftBudget.dashboard.viewAllocations" = "割当一覧を見る"
    await page.getByText('割当一覧を見る').click()
    await page.waitForURL('**/admin/shift-budget/allocations', { timeout: 10_000 })

    await page.goBack()
    await waitForHydration(page)

    // 警告履歴へ
    // i18n "shiftBudget.dashboard.viewAlerts" = "警告履歴を見る"
    await page.getByText('警告履歴を見る').click()
    await page.waitForURL('**/admin/shift-budget/alerts', { timeout: 10_000 })

    await page.goBack()
    await waitForHydration(page)

    // 失敗イベントへ
    // i18n "shiftBudget.dashboard.viewFailedEvents" = "失敗イベントを見る"
    await page.getByText('失敗イベントを見る').click()
    await page.waitForURL('**/admin/shift-budget/failed-events', { timeout: 10_000 })
  })
})
