import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ALERT_ID,
  ORG_ID,
  OTHER_ORG_ID,
  buildAlert,
  mockAlertsList,
  mockCatchAllApis,
  setupAdminAuth,
  setupOrganizationScope,
} from './_helpers'

/**
 * F08.7 Phase 11-α — admin 警告履歴画面 E2E。
 *
 * <p>対象画面: {@code /admin/shift-budget/alerts}（{@code alerts.vue}）。
 * 設計書 §6.2.5 / §7.5。</p>
 *
 * <p>シナリオ（4 ケース）:</p>
 * <ol>
 *   <li>BUDGET-ALERT-01: 警告一覧表示（未承認 + 承認済混在）</li>
 *   <li>BUDGET-ALERT-02: 承認応答 → コメント入力 → 成功通知</li>
 *   <li>BUDGET-ALERT-03: 承認応答 403（権限不足）→ エラー通知</li>
 *   <li>BUDGET-ALERT-04: 多テナント分離 — X-Organization-Id ヘッダ送出検証</li>
 * </ol>
 */

test.describe('F08.7 Phase 11-α: shift-budget/alerts', () => {
  test.beforeEach(async ({ page }) => {
    await setupAdminAuth(page)
    await setupOrganizationScope(page, ORG_ID)
    await mockCatchAllApis(page)
  })

  test('BUDGET-ALERT-01: 未承認 + 承認済の警告が一覧表示される', async ({ page }) => {
    const alerts = [
      buildAlert({ id: 2001, threshold_percent: 80 }), // 未承認
      buildAlert({
        id: 2002,
        threshold_percent: 100,
        acknowledged_at: '2026-04-15T15:00:00Z',
        acknowledged_by: 1,
      }),
    ]
    await mockAlertsList(page, alerts)

    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)

    // ヘッダ "予算警告履歴"
    await expect(page.getByText('予算警告履歴').first()).toBeVisible({ timeout: 10_000 })

    // 行: 80% / 100%
    await expect(page.getByText('80%')).toBeVisible()
    await expect(page.getByText('100%')).toBeVisible()

    // 未承認バッジ "未承認"
    await expect(page.getByText('未承認')).toBeVisible()
    // 承認済バッジ "承認済"
    await expect(page.getByText('承認済')).toBeVisible()
  })

  test('BUDGET-ALERT-02: 承認応答 → コメント入力 → 成功通知', async ({ page }) => {
    const target = buildAlert({ id: ALERT_ID })
    await mockAlertsList(page, [target])

    let ackPosted = false
    let ackBody: unknown = null
    await page.route(
      `**/api/v1/shift-budget/alerts/${ALERT_ID}/acknowledge`,
      async (route, request) => {
        if (request.method() === 'POST') {
          ackPosted = true
          ackBody = request.postDataJSON()
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: buildAlert({
                id: ALERT_ID,
                acknowledged_at: '2026-04-16T10:00:00Z',
                acknowledged_by: 1,
              }),
            }),
          })
        }
        else {
          await route.fallback()
        }
      },
    )

    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)

    // 行内の "承認" ボタン（DataTable 内）
    await page.getByRole('row').getByRole('button', { name: '承認' }).first().click()

    // 確認モーダル "承認"
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(
      page.getByText(`この警告 (ID: ${ALERT_ID}) を承認します。よろしいですか？`),
    ).toBeVisible()

    // コメント入力
    const commentTextarea = page.getByRole('dialog').locator('textarea')
    await commentTextarea.fill('E2E テスト承認コメント')

    // モーダル内 "承認" ボタン
    await page.getByRole('dialog').getByRole('button', { name: '承認' }).click()

    // 成功通知 "警告を承認しました"
    await expect(page.getByText('警告を承認しました').first()).toBeVisible({ timeout: 10_000 })
    expect(ackPosted).toBe(true)
    expect((ackBody as { comment: string }).comment).toBe('E2E テスト承認コメント')
  })

  test('BUDGET-ALERT-03: 承認応答 403（権限不足）でエラー通知', async ({ page }) => {
    const target = buildAlert({ id: ALERT_ID })
    await mockAlertsList(page, [target])

    await page.route(
      `**/api/v1/shift-budget/alerts/${ALERT_ID}/acknowledge`,
      async (route, request) => {
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
      },
    )

    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)

    await page.getByRole('row').getByRole('button', { name: '承認' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('dialog').getByRole('button', { name: '承認' }).click()

    // i18n "shiftBudget.alert.acknowledgeError" = "警告の承認に失敗しました"
    await expect(page.getByText('警告の承認に失敗しました').first()).toBeVisible({ timeout: 10_000 })
  })

  test('BUDGET-ALERT-04: 多テナント分離 — X-Organization-Id ヘッダ送出検証', async ({ page }) => {
    await setupOrganizationScope(page, OTHER_ORG_ID, '別組織')

    let receivedOrgIdHeader: string | null = null
    await page.route('**/api/v1/shift-budget/alerts?**', async (route, request) => {
      if (request.method() === 'GET') {
        receivedOrgIdHeader = request.headers()['x-organization-id'] ?? null
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      }
      else {
        await route.fallback()
      }
    })

    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)

    // i18n "shiftBudget.alert.empty" = "警告はありません"
    await expect(page.getByText('警告はありません')).toBeVisible({ timeout: 10_000 })
    expect(receivedOrgIdHeader).toBe(String(OTHER_ORG_ID))
  })
})
