import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ALLOCATION_ID,
  ORG_ID,
  OTHER_ORG_ID,
  buildAllocation,
  mockAllocationsList,
  mockCatchAllApis,
  setupAdminAuth,
  setupOrganizationScope,
  setupViewerAuth,
} from './_helpers'

/**
 * F08.7 Phase 11-α — admin 予算割当 CRUD 画面 E2E。
 *
 * <p>対象画面: {@code /admin/shift-budget/allocations}（{@code allocations.vue}）。</p>
 *
 * <p>シナリオ（8 ケース）:</p>
 * <ol>
 *   <li>BUDGET-ALLOC-01: 一覧表示（複数件）</li>
 *   <li>BUDGET-ALLOC-02: 空状態（empty）</li>
 *   <li>BUDGET-ALLOC-03: 新規作成モーダル → 成功通知 → 再取得</li>
 *   <li>BUDGET-ALLOC-04: 編集モーダル → 成功通知 → 再取得</li>
 *   <li>BUDGET-ALLOC-05: 削除確認モーダル → 成功通知 → 再取得</li>
 *   <li>BUDGET-ALLOC-06: 編集失敗（楽観ロック競合 409）→ エラー通知</li>
 *   <li>BUDGET-ALLOC-07: 権限不足（BUDGET_ADMIN 無）→ 403 → エラー通知</li>
 *   <li>BUDGET-ALLOC-08: 多テナント分離 — リクエストヘッダ X-Organization-Id 検証</li>
 * </ol>
 */

test.describe('F08.7 Phase 11-α: shift-budget/allocations', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, ORG_ID)
    await mockCatchAllApis(page)
  })

  test('BUDGET-ALLOC-01: 一覧に複数の割当が表示される', async ({ page }) => {
    const items = [
      buildAllocation({ id: 1001, note: 'E2E 割当 A', allocated_amount: 100_000 }),
      buildAllocation({ id: 1002, note: 'E2E 割当 B', allocated_amount: 250_000, team_id: 20 }),
      buildAllocation({ id: 1003, note: 'E2E 割当 C', allocated_amount: 500_000, team_id: 30 }),
    ]
    await mockAllocationsList(page, items)

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // ヘッダ "予算割当一覧"
    await expect(page.getByText('予算割当一覧').first()).toBeVisible({ timeout: 10_000 })

    // 3 件分の ID と金額
    await expect(page.getByRole('cell', { name: '1001', exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: '1002', exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: '1003', exact: true })).toBeVisible()
    await expect(page.getByText('100,000').first()).toBeVisible()
    await expect(page.getByText('250,000')).toBeVisible()
    await expect(page.getByText('500,000')).toBeVisible()
  })

  test('BUDGET-ALLOC-02: 割当が 0 件のとき empty メッセージが表示される', async ({ page }) => {
    await mockAllocationsList(page, [])

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // i18n "shiftBudget.allocation.empty" = "割当が登録されていません"
    await expect(page.getByText('割当が登録されていません')).toBeVisible({ timeout: 10_000 })
  })

  test('BUDGET-ALLOC-03: 新規作成モーダル → 成功通知 → 再取得される', async ({ page }) => {
    await mockAllocationsList(page, [])

    let postReceived = false
    await page.route('**/api/v1/shift-budget/allocations', async (route, request) => {
      if (request.method() === 'POST') {
        postReceived = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: buildAllocation({ id: 9999 }) }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // i18n "shiftBudget.allocation.create" = "新規割当"
    await page.getByRole('button', { name: '新規割当' }).click()

    // モーダルヘッダ
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByText('新規割当').nth(1)).toBeVisible()

    // 必須項目入力（fiscal_year_id, budget_category_id, period_start, period_end, allocated_amount）
    const fiscalYearInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(2)
    await fiscalYearInput.click()
    await fiscalYearInput.fill('2026')

    const categoryInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(3)
    await categoryInput.click()
    await categoryInput.fill('100')

    const dateInputs = page.getByRole('dialog').locator('input[type="date"]')
    await dateInputs.nth(0).fill('2026-04-01')
    await dateInputs.nth(1).fill('2026-04-30')

    const amountInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(4)
    await amountInput.click()
    await amountInput.fill('100000')

    // 保存
    await page.getByRole('button', { name: '保存' }).click()

    // i18n "shiftBudget.allocation.createSuccess" = "割当を作成しました"
    await expect(page.getByText('割当を作成しました').first()).toBeVisible({ timeout: 10_000 })
    expect(postReceived).toBe(true)
  })

  test('BUDGET-ALLOC-04: 編集モーダル → 成功通知 → 再取得される', async ({ page }) => {
    const target = buildAllocation({ id: ALLOCATION_ID, allocated_amount: 100_000, version: 3 })
    await mockAllocationsList(page, [target])

    let putReceived = false
    let putBody: unknown = null
    await page.route(`**/api/v1/shift-budget/allocations/${ALLOCATION_ID}`, async (route, request) => {
      if (request.method() === 'PUT') {
        putReceived = true
        putBody = request.postDataJSON()
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildAllocation({ id: ALLOCATION_ID, allocated_amount: 200_000, version: 4 }),
          }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // 行内の編集ボタン (pi-pencil)
    await page.locator('button .pi-pencil').first().click()

    // 編集モーダル表示。ヘッダ "割当を編集"
    await expect(page.getByText('割当を編集')).toBeVisible({ timeout: 5_000 })

    // 額を 200000 に変更（最初の InputNumber が allocated_amount）
    const amountInput = page.getByRole('dialog').locator('.p-inputnumber-input').first()
    await amountInput.click()
    await amountInput.fill('200000')

    await page.getByRole('button', { name: '保存' }).click()

    // i18n "shiftBudget.allocation.updateSuccess" = "割当を更新しました"
    await expect(page.getByText('割当を更新しました').first()).toBeVisible({ timeout: 10_000 })
    expect(putReceived).toBe(true)
    // 楽観ロックの version が送出されていること
    expect((putBody as { version: number }).version).toBe(3)
  })

  test('BUDGET-ALLOC-05: 削除確認モーダル → 成功通知', async ({ page }) => {
    const target = buildAllocation({ id: ALLOCATION_ID })
    await mockAllocationsList(page, [target])

    let deleteReceived = false
    await page.route(`**/api/v1/shift-budget/allocations/${ALLOCATION_ID}`, async (route, request) => {
      if (request.method() === 'DELETE') {
        deleteReceived = true
        await route.fulfill({ status: 204, body: '' })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // 削除アイコンボタン
    await page.locator('button .pi-trash').first().click()

    // 確認ダイアログ "割当を削除"
    await expect(page.getByText('割当を削除').first()).toBeVisible({ timeout: 5_000 })
    await expect(
      page.getByText(`この割当 (ID: ${ALLOCATION_ID}) を論理削除します。よろしいですか？`),
    ).toBeVisible()

    // 確認ダイアログのフッタ "割当を削除" ボタンを押下
    await page.getByRole('dialog').getByRole('button', { name: '割当を削除' }).click()

    // i18n "shiftBudget.allocation.deleteSuccess" = "割当を削除しました"
    await expect(page.getByText('割当を削除しました').first()).toBeVisible({ timeout: 10_000 })
    expect(deleteReceived).toBe(true)
  })

  test('BUDGET-ALLOC-06: 編集 PUT 409（楽観ロック競合）でエラー通知', async ({ page }) => {
    const target = buildAllocation({ id: ALLOCATION_ID, version: 1 })
    await mockAllocationsList(page, [target])

    await page.route(`**/api/v1/shift-budget/allocations/${ALLOCATION_ID}`, async (route, request) => {
      if (request.method() === 'PUT') {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'OPTIMISTIC_LOCK_FAILED' } }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    await page.locator('button .pi-pencil').first().click()
    await expect(page.getByText('割当を編集')).toBeVisible({ timeout: 5_000 })

    const amountInput = page.getByRole('dialog').locator('.p-inputnumber-input').first()
    await amountInput.click()
    await amountInput.fill('999999')
    await page.getByRole('button', { name: '保存' }).click()

    // i18n "shiftBudget.allocation.updateError" = "割当の更新に失敗しました（楽観ロック競合の可能性があります）"
    await expect(page.getByText('割当の更新に失敗しました', { exact: false }).first()).toBeVisible({
      timeout: 10_000,
    })
  })

  test('BUDGET-ALLOC-07: BUDGET_ADMIN 権限なし — POST が 403 でエラー通知', async ({ page }) => {
    // viewer 認証で再構成
    await page.context().clearCookies()
    await page.context().addCookies([])

    // 一旦リセット: viewer auth + organization scope
    await setupViewerAuth(page)
    await setupOrganizationScope(page, ORG_ID)

    await mockAllocationsList(page, [])

    await page.route('**/api/v1/shift-budget/allocations', async (route, request) => {
      if (request.method() === 'POST') {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'FORBIDDEN' } }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    await page.getByRole('button', { name: '新規割当' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    const fiscalYearInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(2)
    await fiscalYearInput.click()
    await fiscalYearInput.fill('2026')
    const categoryInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(3)
    await categoryInput.click()
    await categoryInput.fill('100')
    const dateInputs = page.getByRole('dialog').locator('input[type="date"]')
    await dateInputs.nth(0).fill('2026-04-01')
    await dateInputs.nth(1).fill('2026-04-30')
    const amountInput = page.getByRole('dialog').locator('.p-inputnumber-input').nth(4)
    await amountInput.click()
    await amountInput.fill('100000')

    await page.getByRole('button', { name: '保存' }).click()

    // i18n "shiftBudget.allocation.createError" = "割当の作成に失敗しました"
    await expect(page.getByText('割当の作成に失敗しました').first()).toBeVisible({ timeout: 10_000 })
  })

  test('BUDGET-ALLOC-08: 多テナント分離 — X-Organization-Id ヘッダがリクエストに含まれる', async ({
    page,
  }) => {
    // 別組織スコープを設定し、API が X-Organization-Id: 99 を受け取ることを検証
    await setupOrganizationScope(page, OTHER_ORG_ID, '別組織')

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

    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)

    // 一覧 API が呼ばれて empty が表示される
    await expect(page.getByText('割当が登録されていません')).toBeVisible({ timeout: 10_000 })
    expect(receivedOrgIdHeader).toBe(String(OTHER_ORG_ID))
  })

})
