import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  FAILED_EVENT_ID,
  ORG_ID,
  buildFailedEvent,
  mockCatchAllApis,
  mockFailedEventsList,
  setupAdminAuth,
  setupOrganizationScope,
} from './_helpers'

/**
 * F08.7 Phase 11-α — admin 失敗イベント管理画面 E2E。
 *
 * <p>対象画面: {@code /admin/shift-budget/failed-events}（{@code failed-events.vue}）。
 * Phase 10-β で追加した {@code shift_budget_failed_events} の管理 UI。</p>
 *
 * <p>シナリオ（4 ケース）:</p>
 * <ol>
 *   <li>BUDGET-FAILED-01: 一覧表示（複数ステータス混在）</li>
 *   <li>BUDGET-FAILED-02: status フィルタ — PENDING に絞り込み → 再取得</li>
 *   <li>BUDGET-FAILED-03: 再実行（retry）→ 成功通知</li>
 *   <li>BUDGET-FAILED-04: 補正済マーク（resolve）→ 成功通知</li>
 * </ol>
 */

test.describe('F08.7 Phase 11-α: shift-budget/failed-events', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, ORG_ID)
    await mockCatchAllApis(page)
  })

  test('BUDGET-FAILED-01: 一覧に複数ステータスの失敗イベントが表示される', async ({ page }) => {
    const events = [
      buildFailedEvent({
        id: 3001,
        event_type: 'NOTIFICATION_FAILED',
        status: 'PENDING',
        error_message: '通知送信に失敗',
      }),
      buildFailedEvent({
        id: 3002,
        event_type: 'WORKFLOW_START_FAILED',
        status: 'EXHAUSTED',
        retry_count: 3,
        error_message: 'ワークフロー起動失敗',
      }),
      buildFailedEvent({
        id: 3003,
        event_type: 'CONSUMPTION_RECORD_FAILED',
        status: 'MANUAL_RESOLVED',
        error_message: '消化記録に失敗',
      }),
    ]
    await mockFailedEventsList(page, events)

    await page.goto('/admin/shift-budget/failed-events')
    await waitForHydration(page)

    // i18n "shiftBudget.failedEvent.title" = "失敗イベント管理"
    await expect(page.getByText('失敗イベント管理').first()).toBeVisible({ timeout: 10_000 })

    // 3 行分の event_type
    await expect(page.getByText('NOTIFICATION_FAILED')).toBeVisible()
    await expect(page.getByText('WORKFLOW_START_FAILED')).toBeVisible()
    await expect(page.getByText('CONSUMPTION_RECORD_FAILED')).toBeVisible()

    // ステータスバッジの日本語ラベル（exact:true で subtitle 文中の "手動補正済" と区別）
    // "未処理" / "再試行上限到達" / "手動補正済"
    await expect(page.getByText('未処理', { exact: true })).toBeVisible()
    await expect(page.getByText('再試行上限到達', { exact: true })).toBeVisible()
    await expect(page.getByText('手動補正済', { exact: true })).toBeVisible()
  })

  test('BUDGET-FAILED-02: ステータスフィルタで再取得される（PENDING のみ）', async ({ page }) => {
    // 初回ロードは catch-all ではなく明示モック（filter なし → 全件返す）
    let lastStatusQuery: string | null = null
    await page.route('**/api/v1/shift-budget/failed-events?**', async (route, request) => {
      if (request.method() !== 'GET') {
        await route.fallback()
        return
      }
      const url = new URL(request.url())
      lastStatusQuery = url.searchParams.get('status')
      const all = [
        buildFailedEvent({ id: 3001, status: 'PENDING' }),
        buildFailedEvent({ id: 3002, status: 'EXHAUSTED' }),
      ]
      const filtered = lastStatusQuery
        ? all.filter(e => e.status === lastStatusQuery)
        : all
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: filtered }),
      })
    })

    await page.goto('/admin/shift-budget/failed-events')
    await waitForHydration(page)

    // 初期は 2 件
    await expect(page.getByRole('cell', { name: '3001', exact: true })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('cell', { name: '3002', exact: true })).toBeVisible()

    // Select で "未処理" を選択
    // i18n "shiftBudget.failedEvent.statusValue.PENDING" = "未処理"
    await page.locator('.p-select').first().click()
    await page.getByRole('option', { name: '未処理', exact: true }).click()

    // 再取得後: 3001 のみ表示、3002 は消える
    await expect(page.getByRole('cell', { name: '3001', exact: true })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('cell', { name: '3002', exact: true })).toBeHidden()
    expect(lastStatusQuery).toBe('PENDING')
  })

  test('BUDGET-FAILED-03: retry → 成功通知 + 再取得', async ({ page }) => {
    const target = buildFailedEvent({ id: FAILED_EVENT_ID, status: 'EXHAUSTED', retry_count: 3 })
    await mockFailedEventsList(page, [target])

    let retryPosted = false
    await page.route(
      `**/api/v1/shift-budget/failed-events/${FAILED_EVENT_ID}/retry`,
      async (route, request) => {
        if (request.method() === 'POST') {
          retryPosted = true
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: buildFailedEvent({
                id: FAILED_EVENT_ID,
                status: 'RETRYING',
                retry_count: 4,
                last_retried_at: '2026-04-16T12:00:00Z',
              }),
            }),
          })
        }
        else {
          await route.fallback()
        }
      },
    )

    await page.goto('/admin/shift-budget/failed-events')
    await waitForHydration(page)

    // 行内の "再実行" ボタン（i18n "shiftBudget.failedEvent.retry"）
    await page.getByRole('row').getByRole('button', { name: '再実行' }).first().click()

    // 確認モーダル
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(
      page.getByText(`この失敗イベント (ID: ${FAILED_EVENT_ID}) を再実行します。よろしいですか？`),
    ).toBeVisible()

    // モーダル内 "再実行" ボタン
    await page.getByRole('dialog').getByRole('button', { name: '再実行' }).click()

    // 成功通知 "失敗イベントを再実行しました"
    await expect(page.getByText('失敗イベントを再実行しました').first()).toBeVisible({
      timeout: 10_000,
    })
    expect(retryPosted).toBe(true)
  })

  test('BUDGET-FAILED-04: resolve（補正済マーク）→ 成功通知', async ({ page }) => {
    const target = buildFailedEvent({ id: FAILED_EVENT_ID, status: 'EXHAUSTED' })
    await mockFailedEventsList(page, [target])

    let resolvePosted = false
    await page.route(
      `**/api/v1/shift-budget/failed-events/${FAILED_EVENT_ID}/resolve`,
      async (route, request) => {
        if (request.method() === 'POST') {
          resolvePosted = true
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: buildFailedEvent({ id: FAILED_EVENT_ID, status: 'MANUAL_RESOLVED' }),
            }),
          })
        }
        else {
          await route.fallback()
        }
      },
    )

    await page.goto('/admin/shift-budget/failed-events')
    await waitForHydration(page)

    // 行内の "補正済マーク" ボタン
    await page.getByRole('row').getByRole('button', { name: '補正済マーク' }).first().click()

    // 確認モーダル
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(
      page.getByText(
        `この失敗イベント (ID: ${FAILED_EVENT_ID}) を手動補正済としてマークします。この操作は取り消せません。`,
      ),
    ).toBeVisible()

    // モーダル内 "補正済マーク" ボタン
    await page.getByRole('dialog').getByRole('button', { name: '補正済マーク' }).click()

    // 成功通知 "失敗イベントを補正済としてマークしました"
    await expect(page.getByText('失敗イベントを補正済としてマークしました').first()).toBeVisible({
      timeout: 10_000,
    })
    expect(resolvePosted).toBe(true)
  })
})
