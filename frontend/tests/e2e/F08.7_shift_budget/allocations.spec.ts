import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockFeatureFlags } from '../helpers/feature-flags'
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

type AllocationMode = 'create' | 'edit'
interface AllocationWrite {
  allocated_amount: number | null
  version?: number
}

/**
 * CMP-260910-1609 AC3。入力を model へ直接代入せず、実 PrimeVue InputNumber の
 * keypress / paste → blur → 保存を通す。API 境界の記録で符号を失った保存を検知する。
 * 権限 API まで明示し、編集導線の欠落を金額入力の red と取り違えない。
 */
test.describe('CMP-260910-1609 AC3: 配分額の入力と保存', () => {
  const browserLogs = new WeakMap<Page, string[]>()
  const apiCalls = new WeakMap<Page, Array<{ path: string; method: string; status: number }>>()
  test.beforeEach(async ({ page }) => {
    const logs: string[] = []
    browserLogs.set(page, logs)
    const calls: Array<{ path: string; method: string; status: number }> = []
    apiCalls.set(page, calls)
    page.on('response', response => {
      const path = new URL(response.url()).pathname
      if (path.startsWith('/api/v1/')) calls.push({ path, method: response.request().method(), status: response.status() })
    })
    page.on('console', message => logs.push(`${message.type()}: ${message.text()}`))
    page.on('pageerror', error => logs.push(`pageerror: ${error.message}`))
    // 現契約は currentUser で復元し、tokenExpiresAt で先回り更新を判定する。
    // トークンをlocalStorageへ置く旧helperは使わず、scopeを含め1本で初期化する。
    await page.addInitScript((organizationId) => {
      localStorage.setItem('currentUser', JSON.stringify({
        id: 1,
        email: 'budget-input@example.invalid',
        fullName: '配分額入力試験',
        profileImageUrl: null,
        systemRole: 'USER',
        timezone: 'Asia/Tokyo',
        locale: 'ja',
      }))
      localStorage.setItem('tokenExpiresAt', String(Date.now() + 60 * 60 * 1000))
      localStorage.setItem('currentScope', JSON.stringify({
        type: 'organization', id: organizationId, name: '入力試験組織',
      }))
    }, ORG_ID)
    await mockCatchAllApis(page)
    // /admin/shift-budgetはFEATURE_SHIFT_ENABLED配下。nullを返すと金額入力前に
    // 公開flagの配列契約を破ってNuxt fatalへ落ちるため、既存の正規helperを使う。
    await mockFeatureFlags(page)
    await page.route('**/api/v1/me/organizations', route => route.fulfill({
      json: { data: [{ id: ORG_ID, slug: 'budget-input-org', name: '入力試験組織' }] },
    }))
    await page.route('**/api/v1/organizations/budget-input-org/me/permissions', route => route.fulfill({
      json: { data: { roleName: 'ADMIN', permissions: ['BUDGET_VIEW', 'BUDGET_ADMIN'], scopeAdmin: true } },
    }))
    await mockAllocationsList(page, [buildAllocation({ version: 3 })])
  })

  test.afterEach(async ({ page }, testInfo) => {
    if (testInfo.status !== testInfo.expectedStatus) {
      await testInfo.attach('browser-console', {
        body: Buffer.from(JSON.stringify(browserLogs.get(page) ?? [])),
        contentType: 'application/json',
      })
      await testInfo.attach('page-api-preconditions', {
        body: Buffer.from(JSON.stringify({
          finalUrl: page.url(),
          calls: apiCalls.get(page) ?? [],
          fixture: { organizationId: ORG_ID, slug: 'budget-input-org', permissions: ['BUDGET_VIEW', 'BUDGET_ADMIN'], featureFlag: 'FEATURE_SHIFT_ENABLED' },
        })),
        contentType: 'application/json',
      })
    }
  })

  async function openAmountForm(page: Page, mode: AllocationMode) {
    const writes: AllocationWrite[] = []
    await page.route(/\/api\/v1\/shift-budget\/allocations(?:\/\d+)?$/, async (route, request) => {
      if (request.method() !== 'POST' && request.method() !== 'PUT') {
        await route.fallback()
        return
      }
      const body: AllocationWrite = request.postDataJSON()
      writes.push(body)
      await route.fulfill({
        status: request.method() === 'POST' ? 201 : 200,
        json: { data: buildAllocation({ allocated_amount: body.allocated_amount ?? 0, version: 4 }) },
      })
    })
    await page.goto('/admin/shift-budget/allocations')
    await waitForHydration(page)
    const create = page.getByRole('button', { name: '新規割当', exact: true })
    await expect(create, '所属組織slugとBUDGET_ADMINを解決してから入力試験を始める').toBeVisible()
    const row = page.getByRole('row').filter({
      has: page.getByRole('cell', { name: String(ALLOCATION_ID), exact: true }),
    })
    await expect(row).toBeVisible()
    if (mode === 'create') await create.click()
    else await row.locator('button:has(.pi-pencil)').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    if (mode === 'create') {
      await dialog.locator('.p-inputnumber-input').nth(2).fill('2026')
      await dialog.locator('.p-inputnumber-input').nth(3).fill('100')
      await dialog.locator('input[type="date"]').nth(0).fill('2026-04-01')
      await dialog.locator('input[type="date"]').nth(1).fill('2026-04-30')
    }
    const amount = dialog.locator('.p-inputnumber-input').nth(mode === 'create' ? 4 : 0)
    await amount.click()
    await amount.press('ControlOrMeta+A')
    await amount.press('Backspace')
    return { writes, dialog, amount }
  }

  for (const mode of ['create', 'edit'] as const) {
    for (const entry of ['keyboard', 'paste', 'empty'] as const) {
      test(`AC3 ${mode}/${entry}: 不正な金額を明示して保存要求を送らない`, async ({ page }, testInfo) => {
        const { writes, dialog, amount } = await openAmountForm(page, mode)
        if (entry === 'keyboard') {
          // fill('-5000') では keypress の負号黙殺を通らないため、必ず一文字ずつ打つ。
          await amount.pressSequentially('-5000')
        }
        else if (entry === 'paste') {
          // DOM value の書換えではなく、本物の InputNumber の paste ハンドラへ渡す。
          await amount.evaluate((input) => {
            const clipboardData = new DataTransfer()
            clipboardData.setData('text/plain', '-5000')
            input.dispatchEvent(new ClipboardEvent('paste', { clipboardData, bubbles: true, cancelable: true }))
          })
        }
        await amount.press('Tab')
        await dialog.getByRole('button', { name: '保存', exact: true }).click()
        // soft にして、修正前も実際の送信内容を後続のアサーションへ残す。
        await expect.soft(dialog.getByRole('alert')).toBeVisible()
        await expect.soft(dialog).toBeVisible()
        await testInfo.attach('allocation-writes', {
          body: Buffer.from(JSON.stringify(writes)), contentType: 'application/json',
        })
        expect(writes, '負値や空値を正値/0/nullへ無言変換して保存してはならない').toEqual([])
      })
    }

    for (const value of [0, 5000]) {
      test(`AC3 ${mode}/${value}: 0と正値は入力した金額のまま保存する`, async ({ page }) => {
        const { writes, dialog, amount } = await openAmountForm(page, mode)
        await amount.pressSequentially(String(value))
        await amount.press('Tab')
        await dialog.getByRole('button', { name: '保存', exact: true }).click()
        await expect.poll(() => writes.length).toBe(1)
        expect(writes[0]?.allocated_amount).toBe(value)
        if (mode === 'edit') expect(writes[0]?.version).toBe(3)
        await expect(dialog).not.toBeVisible()
      })
    }
  }
})

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
