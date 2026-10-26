import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ADMIN_USER,
  DEFAULT_ORG_ID,
  setupAuth,
  setupLayoutMocks,
  setupResidenceStatusMocks,
  buildDashboard,
  buildAnnualReview,
  buildMonitoringVisit,
} from './helpers'

/**
 * F09.16 居住実態管理・見守り E2E テスト
 *
 * テストID: RS-001〜RS-014
 *
 * 方針:
 * - API モックを使用してバックエンドへの依存を排除（page.route() を使用）
 * - repair-plan の E2E テストパターンに倣った構成
 * - dashboard / monitoring-visit-form / monitoring-visits-review / committee-management
 *   の 4 ページを網羅する
 *
 * 仕様書: docs/features/F09.16_residence_status_management.md
 */

const ORG_ID = DEFAULT_ORG_ID

test.describe('RS: F09.16 居住実態管理・見守り', () => {
  // ==========================================================================
  // ダッシュボードページ（dashboard.vue）
  // ==========================================================================

  test.describe('ダッシュボード', () => {
    // ------------------------------------------------------------------------
    // RS-001: ダッシュボードページに遷移できる
    // ------------------------------------------------------------------------
    test('RS-001: ダッシュボードページが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // ページタイトルが表示される
      await expect(page.getByRole('heading', { name: '居住実態管理' })).toBeVisible({
        timeout: 10_000,
      })
    })

    // ------------------------------------------------------------------------
    // RS-002: ダッシュボードタブ — 統計カード（高リスク・中リスク・低リスク）が表示される
    // ------------------------------------------------------------------------
    test('RS-002: ダッシュボードタブに統計カードが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID, {
        dashboard: buildDashboard({
          highRiskCount: 5,
          midRiskCount: 18,
          lowRiskCount: 97,
          unresponsiveCount: 8,
        }),
      })

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 高リスク・中リスク・低リスクのラベルが表示される
      await expect(page.getByText('高リスク').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('中リスク').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('低リスク').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('未応答').first()).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-003: ダッシュボードタブ — タブ（ダッシュボード・年次更新・訪問記録）が表示される
    // ------------------------------------------------------------------------
    test('RS-003: ダッシュボード・年次更新・訪問記録の 3 タブが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 3 つのタブが表示される
      await expect(page.getByText('ダッシュボード').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('年次更新').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('訪問記録').first()).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-004: ダッシュボードタブ — ダッシュボード API が呼ばれる
    // ------------------------------------------------------------------------
    test('RS-004: ページ表示時にダッシュボード API が呼ばれる', async ({ page }) => {
      let dashboardApiCalled = false

      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)

      // ダッシュボード API のみ個別に上書き登録してコール確認する
      await page.route(
        `**/api/v1/organizations/${ORG_ID}/residence-status/dashboard**`,
        async (route) => {
          dashboardApiCalled = true
          await route.fulfill({
            status: 200,
            json: { data: buildDashboard() },
          })
        },
      )
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // ページが描画されたら API が呼ばれているはず
      await expect(page.getByRole('heading', { name: '居住実態管理' })).toBeVisible({
        timeout: 10_000,
      })
      expect(dashboardApiCalled).toBe(true)
    })
  })

  // ==========================================================================
  // 年次更新タブ（dashboard.vue 内）
  // ==========================================================================

  test.describe('年次更新タブ', () => {
    // ------------------------------------------------------------------------
    // RS-005: 年次更新タブをクリックするとフォームが表示される
    // ------------------------------------------------------------------------
    test('RS-005: 年次更新タブをクリックすると一覧と新規作成ボタンが表示される', async ({
      page,
    }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID, {
        reviews: [buildAnnualReview()],
      })

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 年次更新タブをクリック
      await page.getByText('年次更新').first().click()

      // 新規作成ボタンが表示される
      await expect(page.getByRole('button', { name: '新規作成' })).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-006: 年次更新タブ — 年次更新一覧が表示される
    // ------------------------------------------------------------------------
    test('RS-006: 年次更新タブに一覧データが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID, {
        reviews: [
          buildAnnualReview({
            targetYear: 2026,
            title: '2026年度 居住実態調査',
            status: 'OPEN',
          }),
        ],
      })

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 年次更新タブをクリック
      await page.getByText('年次更新').first().click()

      // 一覧データが表示される
      await expect(page.getByText('2026年度 居住実態調査')).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-007: 年次更新タブ — 新規作成ダイアログが開く
    // ------------------------------------------------------------------------
    test('RS-007: 新規作成ボタンをクリックするとダイアログが開く', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 年次更新タブをクリック
      await page.getByText('年次更新').first().click()

      // 新規作成ボタンをクリック
      await page.getByRole('button', { name: '新規作成' }).click()

      // ダイアログが開く（タイトル・締切日フィールドが表示される）
      await expect(page.getByText('年次更新を新規作成')).toBeVisible({ timeout: 10_000 })
    })
  })

  // ==========================================================================
  // 訪問記録タブ（dashboard.vue 内）
  // ==========================================================================

  test.describe('訪問記録タブ', () => {
    // ------------------------------------------------------------------------
    // RS-008: 訪問記録タブをクリックするとフィルタUIが表示される
    // ------------------------------------------------------------------------
    test('RS-008: 訪問記録タブをクリックするとフィルタUIが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 訪問記録タブをクリック
      await page.getByText('訪問記録').first().click()

      // 委員会ID の入力フィールドと検索ボタンが表示される
      await expect(page.getByText('委員会ID').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByRole('button', { name: '検索' })).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-009: 訪問記録タブ — 横展開安否確認ボタンが表示される
    // ------------------------------------------------------------------------
    test('RS-009: 訪問記録タブに横展開安否確認ボタンが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/dashboard`)
      await waitForHydration(page)

      // 訪問記録タブをクリック
      await page.getByText('訪問記録').first().click()

      // 横展開安否確認ボタンが表示される
      await expect(page.getByRole('button', { name: '横展開安否確認を発動' })).toBeVisible({
        timeout: 10_000,
      })
    })
  })

  // ==========================================================================
  // 訪問記録入力フォームページ（monitoring-visit-form.vue）
  // ==========================================================================

  test.describe('訪問記録フォームページ', () => {
    // ------------------------------------------------------------------------
    // RS-010: 訪問記録フォームページに遷移できる
    // ------------------------------------------------------------------------
    test('RS-010: 訪問記録フォームページが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visit-form`)
      await waitForHydration(page)

      // ページタイトルが表示される
      await expect(page.getByRole('heading', { name: '訪問記録の新規登録' })).toBeVisible({
        timeout: 10_000,
      })
    })

    // ------------------------------------------------------------------------
    // RS-011: 訪問記録フォーム — 訪問日時・接触結果・メモのフィールドがある
    // ------------------------------------------------------------------------
    test('RS-011: 訪問記録フォームに必須フィールドが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visit-form`)
      await waitForHydration(page)

      // 訪問日時ラベルが表示される
      await expect(page.getByText('訪問日時').first()).toBeVisible({ timeout: 10_000 })
      // 接触結果ラベルが表示される
      await expect(page.getByText('接触結果').first()).toBeVisible({ timeout: 10_000 })
      // 考慮メモラベルが表示される
      await expect(page.getByText('考慮メモ').first()).toBeVisible({ timeout: 10_000 })
      // 居住者台帳ID・住戸ユニットID・委員会IDのフィールドが表示される
      await expect(page.getByText('居住者台帳ID').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('住戸ユニットID').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('委員会ID').first()).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-012: 訪問記録フォーム — 登録ボタンは必須項目未入力時に無効
    // ------------------------------------------------------------------------
    test('RS-012: 必須項目が未入力の場合は登録ボタンが無効になる', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visit-form`)
      await waitForHydration(page)

      // 登録ボタンが表示される（初期状態では必須項目未入力なので disabled）
      const submitButton = page.getByRole('button', { name: '登録する' })
      await expect(submitButton).toBeVisible({ timeout: 10_000 })
      await expect(submitButton).toBeDisabled()
    })
  })

  // ==========================================================================
  // 訪問記録レビューページ（monitoring-visits-review.vue）
  // ==========================================================================

  test.describe('訪問記録レビューページ（管理者）', () => {
    // ------------------------------------------------------------------------
    // RS-013: 訪問記録レビューページに遷移できる
    // ------------------------------------------------------------------------
    test('RS-013: 訪問記録レビューページが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visits-review`)
      await waitForHydration(page)

      // ページタイトルが表示される
      await expect(
        page.getByRole('heading', { name: '訪問履歴レビュー（管理者）' }),
      ).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-014: 訪問記録レビューページ — フィルター（委員会ID/居住者ID）切り替えができる
    // ------------------------------------------------------------------------
    test('RS-014: 委員会ID・居住者ID の検索モード切り替えができる', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visits-review`)
      await waitForHydration(page)

      // 委員会ID モードボタンが表示される
      await expect(page.getByRole('button', { name: '委員会ID' })).toBeVisible({
        timeout: 10_000,
      })
      // 居住者ID モードボタンが表示される
      await expect(page.getByRole('button', { name: '居住者ID' })).toBeVisible({
        timeout: 10_000,
      })

      // 居住者ID モードに切り替える
      await page.getByRole('button', { name: '居住者ID' }).click()

      // 居住者台帳ID のラベルが表示される
      await expect(page.getByText('居住者台帳ID').first()).toBeVisible({ timeout: 10_000 })
    })
  })

  // ==========================================================================
  // 委員会管理ページ（committee-management.vue）
  // ==========================================================================

  test.describe('委員会管理ページ', () => {
    // ------------------------------------------------------------------------
    // RS-015: 委員会管理ページに遷移できる
    // ------------------------------------------------------------------------
    test('RS-015: 委員会管理ページが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/committee-management`)
      await waitForHydration(page)

      // ページタイトルが表示される
      await expect(page.getByRole('heading', { name: '見守り委員会管理' })).toBeVisible({
        timeout: 10_000,
      })
    })

    // ------------------------------------------------------------------------
    // RS-016: 委員会管理ページ — リスク分布サマリが表示される
    // ------------------------------------------------------------------------
    test('RS-016: 委員会管理ページにリスク分布サマリが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID, {
        dashboard: buildDashboard({
          highRiskCount: 5,
          midRiskCount: 18,
          lowRiskCount: 97,
          totalResidents: 120,
        }),
      })

      await page.goto(`/organizations/${ORG_ID}/residence-status/committee-management`)
      await waitForHydration(page)

      // リスク分布サマリのラベルが表示される
      await expect(page.getByText('リスク分布サマリ')).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('高リスク住民数').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('中リスク住民数').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText('低リスク住民数').first()).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-017: 委員会管理ページ — 訪問記録作成ボタンが表示される
    // ------------------------------------------------------------------------
    test('RS-017: 委員会管理ページに新規訪問記録作成ボタンが表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/committee-management`)
      await waitForHydration(page)

      // 新規訪問記録作成ボタンが表示される
      await expect(page.getByRole('button', { name: '新規訪問記録を作成' })).toBeVisible({
        timeout: 10_000,
      })
    })

    // ------------------------------------------------------------------------
    // RS-018: 委員会管理ページ — ActivityScoreCard が表示される
    // ------------------------------------------------------------------------
    test('RS-018: 委員会管理ページに ActivityScoreCard が表示される', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/committee-management`)
      await waitForHydration(page)

      // リスクスコア表示例のセクションが表示される
      await expect(page.getByText('リスクスコア表示例')).toBeVisible({ timeout: 10_000 })
      // ActivityScoreCard に表示されるデモデータ（見本 太郎）が表示される
      await expect(page.getByText('見本 太郎').first()).toBeVisible({ timeout: 10_000 })
    })

    // ------------------------------------------------------------------------
    // RS-019: 委員会管理ページ — 委員会IDを入力して訪問履歴ボタンが有効になる
    // ------------------------------------------------------------------------
    test('RS-019: 委員会ID を入力すると訪問履歴を見るボタンが有効になる', async ({ page }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID)

      await page.goto(`/organizations/${ORG_ID}/residence-status/committee-management`)
      await waitForHydration(page)

      // 訪問履歴を見るボタンは初期状態で無効
      const visitHistoryButton = page.getByRole('button', { name: '訪問履歴を見る' })
      await expect(visitHistoryButton).toBeVisible({ timeout: 10_000 })
      await expect(visitHistoryButton).toBeDisabled()

      // 委員会IDを入力する
      await page.getByPlaceholder('委員会IDを入力').fill('10')

      // 入力後はボタンが有効になる
      await expect(visitHistoryButton).toBeEnabled()
    })
  })

  // ==========================================================================
  // 訪問記録データ表示（dashboard.vue 訪問記録タブ）
  // ==========================================================================

  test.describe('訪問記録データ表示', () => {
    // ------------------------------------------------------------------------
    // RS-020: 訪問記録タブ — 検索結果が表示される
    // ------------------------------------------------------------------------
    test('RS-020: 訪問記録タブで検索すると結果が表示される（レビューページ）', async ({
      page,
    }) => {
      await setupAuth(page, ADMIN_USER)
      await setupLayoutMocks(page, ADMIN_USER)
      await setupResidenceStatusMocks(page, ORG_ID, {
        visits: [
          buildMonitoringVisit({
            contactResult: 'MET',
            considerationMemo: '元気そうでした',
          }),
        ],
      })

      await page.goto(`/organizations/${ORG_ID}/residence-status/monitoring-visits-review`)
      await waitForHydration(page)

      // 委員会ID を入力して検索
      await page.getByPlaceholder('委員会IDを入力').fill('10')
      await page.getByRole('button', { name: '検索' }).click()

      // 訪問記録が表示される（対面確認タグ）
      await expect(page.getByText('対面確認').first()).toBeVisible({ timeout: 10_000 })
    })
  })
})
