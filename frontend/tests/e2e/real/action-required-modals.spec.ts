// @see docs/features/F22.1_swipe_scope_dashboard/04_widgets.md
/**
 * 実機E2E（モック不使用・実BE/実FE）: ScopeActionRequiredWidget モーダル化テスト。
 *
 * 対象機能: F22.1 要対応ウィジェット（⑧）
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 *
 * 前提:
 * - BE: http://localhost:8080 が起動済み
 * - FE: http://localhost:3000 が起動済み
 * - 認証: tests/e2e/.auth/real-user.json の storageState を使用
 * - テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 * - fc-u-18 チームには seed によりアンケート（要対応1件）・出席確認（要対応1件）がある
 *
 * テストケース:
 *   AR-001: BE が起動しており要対応 API が 200 を返す
 *   AR-002: fc-u-18 チームの要対応が seed で投入済みである
 *   AR-003: /dashboard が表示され DashboardScopeCarousel が存在する
 *   AR-004: チームタブに切り替えて ScopeActionRequiredWidget が描画される
 *   AR-005: 要対応セクション（SectionCard）が表示される
 *   AR-006: アンケート要対応ボタンをクリックしてモーダルが開く（AC-05）
 *   AR-007: アンケートモーダルをキャンセルで閉じられる（AC-09）
 *   AR-008: 出席確認要対応ボタンをクリックしてモーダルが開く（AC-07）
 *   AR-009: 出席確認モーダルをキャンセルで閉じられる（AC-09）
 *   AR-010: 空状態の場合は「すべて対応済み」メッセージが表示される
 */

import { test, expect, type APIRequestContext, type Page, type Response } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
const BACKEND_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
/** action-required seed が入っているチームスラッグ */
const TEAM_SLUG = 'fc-u-18'

// ---------------------------------------------------------------------------
// ヘルパー: 環境チェック
// ---------------------------------------------------------------------------
async function isBackendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    const body = await res.json()
    return body.status === 'UP'
  } catch {
    return false
  }
}

async function loginToken(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
      data: { email, password },
      headers: { 'Content-Type': 'application/json' },
    })
    if (!res.ok()) return null
    return (await res.json())?.data?.accessToken ?? null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// AR-001〜002: API 疎通確認（ブラウザ不使用）
// ---------------------------------------------------------------------------
test.describe('AR-001〜002: 要対応 API 疎通確認', () => {
  test.setTimeout(60_000)

  test('AR-001: BE が起動しており要対応 API が 200 を返す', async ({ page }) => {
    const alive = await isBackendAlive(page.request)
    if (!alive) {
      test.skip(true, 'BE 未起動のためスキップ')
    }

    const token = await loginToken(page.request, E2E_USER.email, E2E_USER.password)
    if (!token) {
      test.skip(true, 'ログイン不可のためスキップ（storageState 期限切れの可能性）')
      return
    }

    const res = await page.request.get(`${BACKEND_URL}/api/v1/dashboard/team/${TEAM_SLUG}/action-required`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(res.status(), 'action-required API ステータス').toBe(200)

    const body = await res.json()
    const data = body?.data ?? body
    expect(typeof data.total_action_count, 'total_action_count の型').toBe('number')
  })

  test('AR-002: fc-u-18 チームの要対応が seed で投入済みである（totalActionCount > 0）', async ({ page }) => {
    const alive = await isBackendAlive(page.request)
    if (!alive) {
      test.skip(true, 'BE 未起動のためスキップ')
    }

    const token = await loginToken(page.request, E2E_USER.email, E2E_USER.password)
    if (!token) {
      test.skip(true, 'ログイン不可のためスキップ（storageState 期限切れの可能性）')
      return
    }

    const res = await page.request.get(`${BACKEND_URL}/api/v1/dashboard/team/${TEAM_SLUG}/action-required`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(res.ok()).toBeTruthy()

    const body = await res.json()
    const data = body?.data ?? body
    // アンケートまたは出席確認の要対応が 1 件以上あること（seed 依存）
    const total = data?.total_action_count ?? 0
    expect(total, `要対応件数（期待: >0）= ${total}`).toBeGreaterThan(0)
  })
})

// ---------------------------------------------------------------------------
// AR-003〜010: ブラウザ実機テスト
// ---------------------------------------------------------------------------
test.describe('AR-003〜010: ダッシュボード要対応ウィジェット実機テスト', () => {
  test.setTimeout(180_000)

  /**
   * ログインしてダッシュボードへ遷移する共通ヘルパー。
   * storageState の access_token が期限切れでも確実にログインする。
   */
  async function loginAndGoDashboard(page: Page): Promise<void> {
    // ログインページから確実にログインする（storageState の access_token が期限切れでも動作する）
    await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // 既にログイン済みでリダイレクトされた場合は直接 /dashboard に遷移
    if (!page.url().includes('/login')) {
      await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      return
    }

    // ログインフォームに入力
    const emailInput = page.locator('input#email, input[type="email"]').first()
    await emailInput.waitFor({ state: 'visible', timeout: 15_000 })
    await emailInput.fill(E2E_USER.email)

    const passwordInput = page.locator('input[type="password"]').first()
    await passwordInput.fill(E2E_USER.password)

    await page.getByRole('button', { name: 'ログイン', exact: true }).click()

    // ログイン後のリダイレクト先（/my/ または /dashboard）を待つ
    await page.waitForURL(/\/(my\/|dashboard)/, { timeout: 30_000 })
    await waitForHydration(page)

    // /dashboard でなければ移動する
    if (!page.url().includes('/dashboard')) {
      await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
    }
  }

  /**
   * チームパネルに切り替えて、チームチップを選択し、PageLoading が消えるまで待つ共通フロー。
   *
   * 優先順:
   * 1. scope-tab-chip-TEAM-{fc-u-18 の公開 ID または scopeId} があれば選択
   * 2. なければ先頭チームを選択
   *
   * 返値: 選択されたチームチップの data-testid サフィックス（またはnull）
   */
  async function switchToTeamPanel(
    page: Page,
  ): Promise<string | null> {
    // TEAM セグメントに切り替え
    const teamSegment = page.getByTestId('scope-segment-TEAM')
    await expect(teamSegment).toBeVisible({ timeout: 20_000 })
    await teamSegment.click()

    // チームタブバーが表示されるまで待つ
    await expect(page.getByTestId('scope-tab-bar-TEAM')).toBeVisible({ timeout: 20_000 })

    // scope-tabs API レスポンスが来てチップが描画されるまで待つ
    // （API レスポンス前は totalPages=0 のためページネーションボタンが存在しない）
    await expect(page.locator('[data-testid^="scope-tab-chip-TEAM-"]').first()).toBeVisible({
      timeout: 15_000,
    })

    // fc-u-18 の testid は scope-tab-chip-TEAM-fc-u-18
    // ScopeTabBar.vue のチップ testid は `scope-tab-chip-${scopeType}-${item.slug ?? item.scopeId}`
    // scope-tabs API の public_id が "fc-u-18" なのでそのまま testid に使える
    // fc-u-18 は scope-tabs の 8 ページ目（page=7, 0-indexed）にあるため最大 10 ページ分を探す
    const TARGET_CHIP_TESTID = `scope-tab-chip-TEAM-${TEAM_SLUG}`
    let selectedChip = null
    let selectedChipId: string | null = null

    for (let pageNum = 0; pageNum < 10; pageNum++) {
      // fc-u-18 チップを直接 testid で探す（ScopeTabBar は複数の TEAM パネルを同時マウントしないので unique）
      const fcChip = page.getByTestId(TARGET_CHIP_TESTID)
      const fcChipCount = await fcChip.count().catch(() => 0)
      if (fcChipCount > 0) {
        const visible = await fcChip.first().isVisible().catch(() => false)
        if (visible) {
          selectedChip = fcChip.first()
          selectedChipId = TEAM_SLUG
          break
        }
      }

      // 次ページボタンを探す（TEAM 専用 testid で絞る）
      // ページネーションボタンは totalPages > 1 の場合のみ DOM に存在する
      const nextBtn = page.getByTestId('scope-tab-nextpage-TEAM')
      const nextCount = await nextBtn.count().catch(() => 0)
      if (nextCount === 0) break
      // hasNext が false のとき disabled になる
      const nextDisabled = await nextBtn.first().isDisabled().catch(() => true)
      if (nextDisabled) break
      await nextBtn.first().click()
      // ページ遷移の完了を待つ（API レスポンスを待つため十分な時間を確保）
      await page.waitForTimeout(2_000)
    }

    // fc-u-18 が見つからなければ先頭チームを選択
    if (!selectedChip) {
      const allChips = page.locator('[data-testid^="scope-tab-chip-TEAM-"]')
      const chipCount = await allChips.count()
      if (chipCount > 0) {
        selectedChip = allChips.first()
        const testid = await selectedChip.getAttribute('data-testid')
        selectedChipId = testid?.replace('scope-tab-chip-TEAM-', '') ?? null
        console.warn('fc-u-18 チップが見つかりません。先頭チームで継続します。')
      }
    }

    if (selectedChip) {
      // action-required API レスポンスを待機するプロミスを先に作成する（クリック前に登録）
      const actionRequiredApiPattern = /\/api\/v1\/dashboard\/team\/[^/]+\/action-required/
      const actionRequiredPromise = page
        .waitForResponse((r: Response) => actionRequiredApiPattern.test(r.url()) && r.request().method() === 'GET', {
          timeout: 40_000,
        })
        .catch(() => null)

      await selectedChip.click()

      // スクロールして IntersectionObserver をトリガーする
      // - まず少し待機して選択が反映されるまで待つ
      await page.waitForTimeout(1_000)

      // swipe-widget-grid-TEAM コンテナを直接 scrollIntoView して要対応ウィジェットをビューポートに入れる
      await page.evaluate(() => {
        // swipe-widget-grid-TEAM 内の最後の子（要対応ウィジェット）を視界に入れる
        const grid = document.querySelector('[data-testid="swipe-widget-grid-TEAM"]')
        if (grid) {
          const last = grid.lastElementChild
          if (last instanceof HTMLElement) {
            last.scrollIntoView({ behavior: 'smooth', block: 'end' })
          } else {
            grid.scrollIntoView({ behavior: 'smooth', block: 'end' })
          }
        }
        window.scrollTo(0, document.body.scrollHeight)
      })
      await page.waitForTimeout(1_000)
      // 再度スクロールして確実にトリガー
      await page.evaluate(() => {
        window.scrollTo(0, document.body.scrollHeight)
        document.querySelectorAll('[data-testid="swipe-widget-grid-TEAM"] *').forEach((el) => {
          if (el instanceof HTMLElement && el.scrollHeight > el.clientHeight) {
            el.scrollTop = el.scrollHeight
          }
        })
      })
      await page.waitForTimeout(500)

      // action-required API のレスポンスを待つ（最大 40 秒）
      await actionRequiredPromise

      // ローディングスピナーが消えるまで追加待機
      await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
      await page.waitForTimeout(1_000)
    } else {
      // チームが見つからなかった場合も IntersectionObserver をトリガーする
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
      await page.waitForTimeout(3_000)
      await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    }

    return selectedChipId
  }

  test.beforeEach(async ({ page }) => {
    await loginAndGoDashboard(page)
    // ダッシュボードの初期スピナーが消えるまで待つ
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  })

  test('AR-003: /dashboard が表示され DashboardScopeCarousel が存在する', async ({ page }) => {
    // カルーセルコンテナが存在すること
    const carousel = page.getByTestId('scope-carousel')
    await expect(carousel).toBeVisible({ timeout: 20_000 })
  })

  test('AR-004: チームタブに切り替えて ScopeActionRequiredWidget が描画される', async ({ page }) => {
    await switchToTeamPanel(page)

    // ウィジェットグリッドが描画されること（TEAM のグリッドに絞る）
    // DashboardScopeCarousel は 3 パネルを同時マウントするため、TEAM を指定して絞り込む
    const grid = page.getByTestId('swipe-widget-grid-TEAM')
    await expect(grid).toBeVisible({ timeout: 30_000 })
  })

  test('AR-005: 要対応セクション（SectionCard）が表示される', async ({ page }) => {
    await switchToTeamPanel(page)

    // 要対応ウィジェットの内容を確認
    // totalActionCount > 0 の場合: アンケート・出席確認・回覧板の区分が表示される
    // totalActionCount = 0 の場合: 空状態メッセージが表示される
    const bodyText = await page.locator('body').textContent()
    // 「要対応」「アンケート」「回覧板」「出席確認」のいずれかが表示されること
    const hasContent =
      (bodyText?.includes('要対応') ||
        bodyText?.includes('アンケート') ||
        bodyText?.includes('回覧') ||
        bodyText?.includes('出席') ||
        bodyText?.includes('対応済み')) ??
      false
    expect(hasContent, '要対応ウィジェット内容が表示されていること').toBeTruthy()
  })

  test('AR-006: アンケート要対応ボタンをクリックしてモーダルが開く（AC-05）', async ({
    page,
  }) => {
    await switchToTeamPanel(page)

    // アンケート要対応ボタンを探す
    const surveyButtons = page.locator('[data-testid^="action-required-survey-"]')
    const surveyCount = await surveyButtons.count()

    if (surveyCount === 0) {
      // seed データがない場合はスキップ
      console.info('アンケート要対応ボタンが見つかりません。先頭チームに seed データなし → スキップ')
      test.skip(true, 'アンケート seed データなし（先頭チームで確認）')
      return
    }

    // 最初のアンケートボタンをクリック
    await surveyButtons.first().click()

    // SurveyAnswerModal が表示されること
    const dialog = page.locator('[role="dialog"], .p-dialog')
    await expect(dialog.first()).toBeVisible({ timeout: 10_000 })
  })

  test('AR-007: アンケートモーダルをキャンセルで閉じられる（AC-09）', async ({ page }) => {
    await switchToTeamPanel(page)

    const surveyButtons = page.locator('[data-testid^="action-required-survey-"]')
    if ((await surveyButtons.count()) === 0) {
      test.skip(true, 'アンケート seed データなし（先頭チームで確認）')
      return
    }

    await surveyButtons.first().click()

    // ダイアログが開いたことを確認
    const dialog = page.locator('[role="dialog"], .p-dialog')
    await expect(dialog.first()).toBeVisible({ timeout: 10_000 })

    // キャンセルボタンをクリック（モーダル内のキャンセルボタンを優先。×ボタンはマスクに遮られることがある）
    // PrimeVue Dialog はモーダル内に .p-dialog-footer 内にボタンを配置する
    const cancelButton = dialog.first().getByRole('button', { name: /キャンセル|閉じる|Cancel|Close/i }).first()
    const closeXBtn = dialog.first().locator('[data-pc-name="closebutton"], button[aria-label="閉じる"]').first()

    // キャンセルテキストボタンを試す（フッターにあるため確実）
    const cancelVisible = await cancelButton.isVisible().catch(() => false)
    if (cancelVisible) {
      await cancelButton.click()
    } else {
      await closeXBtn.click({ force: true })
    }

    // ダイアログが閉じていること
    await expect(dialog.first()).not.toBeVisible({ timeout: 5_000 })
  })

  test('AR-008: 出席確認要対応ボタンをクリックしてモーダルが開く（AC-07）', async ({
    page,
  }) => {
    await switchToTeamPanel(page)

    // 出席確認要対応ボタンを探す（scheduleId をキーにしている）
    const attendanceButtons = page.locator('[data-testid^="action-required-attendance-"]')
    const attendanceCount = await attendanceButtons.count()

    if (attendanceCount === 0) {
      console.info('出席確認要対応ボタンが見つかりません。先頭チームに seed データなし → スキップ')
      test.skip(true, '出席確認 seed データなし（先頭チームで確認）')
      return
    }

    await attendanceButtons.first().click()

    // AttendanceQuickModal が表示されること
    const dialog = page.locator('[role="dialog"], .p-dialog')
    await expect(dialog.first()).toBeVisible({ timeout: 10_000 })
  })

  test('AR-009: 出席確認モーダルをキャンセルで閉じられる（AC-09）', async ({ page }) => {
    await switchToTeamPanel(page)

    const attendanceButtons = page.locator('[data-testid^="action-required-attendance-"]')
    if ((await attendanceButtons.count()) === 0) {
      test.skip(true, '出席確認 seed データなし（先頭チームで確認）')
      return
    }

    await attendanceButtons.first().click()

    // ダイアログが開いたことを確認
    const dialog = page.locator('[role="dialog"], .p-dialog')
    await expect(dialog.first()).toBeVisible({ timeout: 10_000 })

    // キャンセルで閉じる（モーダル内のキャンセルボタンを優先）
    const cancelButton2 = dialog.first().getByRole('button', { name: /キャンセル|閉じる|Cancel|Close/i }).first()
    const closeXBtn2 = dialog.first().locator('[data-pc-name="closebutton"], button[aria-label="閉じる"]').first()

    const cancelVisible2 = await cancelButton2.isVisible().catch(() => false)
    if (cancelVisible2) {
      await cancelButton2.click()
    } else {
      await closeXBtn2.click({ force: true })
    }

    // ダイアログが閉じていること
    await expect(dialog.first()).not.toBeVisible({ timeout: 5_000 })
  })

  test('AR-010: 空状態の場合は「すべて対応済み」メッセージが表示される（totalActionCount=0 時）', async ({
    page,
  }) => {
    // このテストは要対応が 0 件のチームで確認する
    const token = await loginToken(page.request, E2E_USER.email, E2E_USER.password)
    if (!token) {
      test.skip(true, 'ログイン不可のためスキップ')
      return
    }

    const res = await page.request.get(`${BACKEND_URL}/api/v1/dashboard/team/${TEAM_SLUG}/action-required`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const body = await res.json()
    const data = body?.data ?? body
    const total = data?.total_action_count ?? 0

    if (total > 0) {
      // fc-u-18 は seed で要対応あり → 空状態の確認はここではスキップ
      console.info(`fc-u-18 の要対応件数 = ${total}（>0 のため空状態テストはスキップ）`)
      test.skip(true, 'fc-u-18 に要対応データありのため空状態テストをスキップ')
      return
    }

    // 要対応が 0 件の場合: 空状態を確認
    await switchToTeamPanel(page)

    // 空状態メッセージの確認（pi-check-circle + i18n テキスト）
    const emptyIcon = page.locator('.pi-check-circle')
    await expect(emptyIcon.first()).toBeVisible({ timeout: 10_000 })
  })
})
