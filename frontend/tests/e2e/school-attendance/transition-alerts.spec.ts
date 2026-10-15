import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/school.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  STUDENT_USER_ID_1,
  STUDENT_USER_ID_2,
  buildTransitionAlertResponse,
  buildTransitionAlertListResponse,
  loginAsTeacher,
  mockCatchAllApis,
  mockGetTransitionAlerts,
  mockResolveTransitionAlert,
} from './_helpers'

/**
 * F03.13 Phase 8 §SCHOOL-ALERTS 移動検知アラート E2E テスト群（SCHOOL-ALERTS-001〜003）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>SCHOOL-ALERTS-001: アラート一覧ロード（2件、未解決1件）→ unresolvedCount=1 表示</li>
 *   <li>SCHOOL-ALERTS-002: 未解決フィルタ ON → 解決済みアラートが非表示になる</li>
 *   <li>SCHOOL-ALERTS-003: アラート解決 → note を POST → リスト更新</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.13_school_attendance.md §TRANSITION_ALERT</p>
 */

const TRANSITION_ALERTS_URL = `/teams/${DEFAULT_TEAM_ID}/school-attendance/transition-alerts`

/** 2件（未解決1件＋解決済み1件）の標準アラートセット。 */
function buildStandardAlerts() {
  const unresolvedAlert = buildTransitionAlertResponse({
    id: 1,
    studentUserId: STUDENT_USER_ID_1,
    alertLevel: 'NORMAL',
    resolved: false,
  })
  const resolvedAlert = buildTransitionAlertResponse({
    id: 2,
    studentUserId: STUDENT_USER_ID_2,
    alertLevel: 'URGENT',
    resolved: true,
    resolvedAt: '2026-05-01T09:00:00Z',
    resolutionNote: '早退対応済み',
  })
  return { unresolvedAlert, resolvedAlert }
}

test.describe('SCHOOL-ALERTS-001〜003: F03.13 §TRANSITION_ALERT 移動検知アラート', () => {
  test.beforeEach(async ({ page }) => {
    // 教員として認証注入し、catch-all で未モック API を 404 化する
    await loginAsTeacher(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
  })

  test('SCHOOL-ALERTS-001: アラート一覧ロード（2件、未解決1件）→ unresolvedCount=1 表示', async ({ page }) => {
    const { unresolvedAlert, resolvedAlert } = buildStandardAlerts()
    await mockGetTransitionAlerts(
      page,
      buildTransitionAlertListResponse({
        alerts: [unresolvedAlert, resolvedAlert],
        totalCount: 2,
        unresolvedCount: 1,
      }),
    )

    await page.goto(TRANSITION_ALERTS_URL)
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.getByRole('heading', { name: JA.school.transitionAlert.title })).toBeVisible({
      timeout: 10_000,
    })

    // アラートリストが表示される
    await expect(page.getByTestId('transition-alert-list')).toBeVisible({ timeout: 10_000 })

    // 2件のアラートアイテムが表示される
    await expect(page.getByTestId('transition-alert-item-1')).toBeVisible()
    await expect(page.getByTestId('transition-alert-item-2')).toBeVisible()

    // 未解決件数バッジが 1件を示す
    await expect(page.getByTestId('transition-alert-unresolved-count')).toBeVisible()
    await expect(page.getByTestId('transition-alert-unresolved-count')).toContainText('1')

    // 未解決アラートには解決ボタンが表示される
    await expect(page.getByTestId('transition-alert-resolve-1')).toBeVisible()

    // 解決済みアラートには解決ボタンが表示されない
    await expect(page.getByTestId('transition-alert-resolve-2')).toHaveCount(0)
  })

  test('SCHOOL-ALERTS-002: 未解決フィルタ ON → 未解決アラートのみ返すよう再リクエスト', async ({ page }) => {
    const { unresolvedAlert, resolvedAlert } = buildStandardAlerts()

    // 最初のロード：2件全部返す
    let getCallCount = 0
    await page.route('**/api/v1/teams/*/attendance/transition-alerts**', async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      getCallCount++
      const url = new URL(route.request().url())
      const unresolvedOnly = url.searchParams.get('unresolvedOnly') === 'true'
      if (unresolvedOnly) {
        // フィルタ ON: 未解決のみ返す
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildTransitionAlertListResponse({
              alerts: [unresolvedAlert],
              totalCount: 1,
              unresolvedCount: 1,
            }),
          }),
        })
      } else {
        // フィルタ OFF: 2件返す
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildTransitionAlertListResponse({
              alerts: [unresolvedAlert, resolvedAlert],
              totalCount: 2,
              unresolvedCount: 1,
            }),
          }),
        })
      }
    })

    await page.goto(TRANSITION_ALERTS_URL)
    await waitForHydration(page)

    // 初期状態で2件表示される
    await expect(page.getByTestId('transition-alert-item-1')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('transition-alert-item-2')).toBeVisible()

    // 未解決フィルタをONにする（PrimeVue Checkbox: input[type="checkbox"] を探してクリック）
    const filterCheckbox = page.getByTestId('transition-alert-unresolved-filter')
    await filterCheckbox.locator('input[type="checkbox"]').click()

    // フィルタ適用後、未解決アラート(1件)のみが表示される
    await expect(page.getByTestId('transition-alert-item-1')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('transition-alert-item-2')).toHaveCount(0)

    // GET が2回呼ばれた（初回ロード + フィルタ変更後）
    await expect.poll(() => getCallCount, { timeout: 10_000 }).toBeGreaterThanOrEqual(2)
  })

  test('SCHOOL-ALERTS-003: アラート解決 → note を POST → リスト更新', async ({ page }) => {
    const { unresolvedAlert, resolvedAlert } = buildStandardAlerts()

    // 解決後の更新済みアラート
    const resolvedAfterAction = { ...unresolvedAlert, resolved: true, resolvedAt: '2026-05-01T11:00:00Z', resolutionNote: '確認済み' }

    // GET: 最初は未解決1件＋解決済み1件、解決後は全件解決済みを返す
    let getCallCount = 0
    await page.route('**/api/v1/teams/*/attendance/transition-alerts**', async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      getCallCount++
      if (getCallCount === 1) {
        // 初回ロード
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildTransitionAlertListResponse({
              alerts: [unresolvedAlert, resolvedAlert],
              totalCount: 2,
              unresolvedCount: 1,
            }),
          }),
        })
      } else {
        // 解決後の再ロード（全件解決済み）
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: buildTransitionAlertListResponse({
              alerts: [resolvedAfterAction, resolvedAlert],
              totalCount: 2,
              unresolvedCount: 0,
            }),
          }),
        })
      }
    })

    // POST resolve モック
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockResolveTransitionAlert(page, captured)

    await page.goto(TRANSITION_ALERTS_URL)
    await waitForHydration(page)

    // 初期状態で2件表示
    await expect(page.getByTestId('transition-alert-item-1')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('transition-alert-resolve-1')).toBeVisible()

    // アラート1の解決ボタンをクリック → ResolveModal が開く
    await page.getByTestId('transition-alert-resolve-1').click()
    await expect(page.getByTestId('transition-alert-resolve-modal')).toBeVisible({ timeout: 10_000 })

    // note を入力
    await page.getByTestId('transition-alert-resolve-note').fill('確認済み')

    // 解決ボタンをクリック → POST が送られる
    await page.getByTestId('transition-alert-resolve-submit').click()

    // POST が実行され、captured に note が含まれることを確認
    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as { note: string }
    expect(body.note).toBe('確認済み')

    // 解決後にリストが更新される（未解決件数バッジが消える）
    await expect(page.getByTestId('transition-alert-unresolved-count')).toHaveCount(0, { timeout: 10_000 })
  })
})
