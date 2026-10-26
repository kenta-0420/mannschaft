import { test, expect } from '@playwright/test'
import {
  setupAuth,
  buildMockState,
  buildWorkMemo,
  mockActionMemoApi,
  waitForHydration,
} from '../helpers/action-memo-mocks'

/**
 * F02.5 Phase 4-β E2E テスト。
 *
 * <p>リマインド設定（設定画面）とダッシュボード（管理職向け WORK メモ閲覧）の
 * 2 グループ計 9 件のテストを実装する。</p>
 */

// テスト対象サーバー（worktree の開発サーバー: IPv4 127.0.0.1 を明示して IPv6 の 426 を回避）
const BASE = process.env.TEST_BASE_URL ?? 'http://127.0.0.1:3002'

// ---------------------------------------------------------------------------
// リマインド設定（settings ページ）
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 4-β — リマインド設定 (settings page)', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 4521, displayName: 'テスト管理者', role: 'ADMIN' })
  })

  test('AM4B-SETTINGS-001: reminder OFF 時は時刻入力フィールドが非表示', async ({ page }) => {
    const state = buildMockState({
      settings: { reminder_enabled: false, reminder_time: null },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/settings`)
    await waitForHydration(page)

    // reminder セクションが表示される
    const reminderSection = page.locator('[data-testid="settings-reminder-section"]')
    await expect(reminderSection).toBeVisible({ timeout: 10_000 })

    // チェックボックスは OFF（未チェック）
    const checkbox = page.locator('[data-testid="reminder-enabled-checkbox"]')
    await expect(checkbox).not.toBeChecked()

    // 時刻入力は非表示
    const timeInput = page.locator('[data-testid="reminder-time-input"]')
    await expect(timeInput).toBeHidden()
  })

  test('AM4B-SETTINGS-002: reminder トグル ON → 時刻フィールド表示 + PATCH に reminder_enabled:true', async ({
    page,
  }) => {
    const state = buildMockState({
      settings: { reminder_enabled: false, reminder_time: null },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/settings`)
    await waitForHydration(page)

    const reminderSection = page.locator('[data-testid="settings-reminder-section"]')
    await expect(reminderSection).toBeVisible({ timeout: 10_000 })

    // PATCH リクエストをキャプチャ
    const patchPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/action-memo-settings') && req.method() === 'PATCH',
    )

    // チェックボックスの @change を直接トリガー（sr-only で Playwright から操作できないため evaluate 使用）
    await page.evaluate(() => {
      const checkbox = document.querySelector('[data-testid="reminder-enabled-checkbox"]') as HTMLInputElement
      if (!checkbox) throw new Error('checkbox not found')
      checkbox.checked = true
      checkbox.dispatchEvent(new Event('change', { bubbles: true }))
    })

    // PATCH が発行されることを確認
    const patchReq = await patchPromise
    const body = JSON.parse(patchReq.postData() ?? '{}') as Record<string, unknown>
    expect(body.reminder_enabled).toBe(true)

    // 時刻入力フィールドが表示される
    const timeInput = page.locator('[data-testid="reminder-time-input"]')
    await expect(timeInput).toBeVisible({ timeout: 5_000 })

    // mock 状態も更新される
    expect(state.settings.reminder_enabled).toBe(true)
  })

  test('AM4B-SETTINGS-003: 時刻入力 → PATCH に reminder_time が含まれる', async ({ page }) => {
    const state = buildMockState({
      settings: { reminder_enabled: true, reminder_time: null },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/settings`)
    await waitForHydration(page)

    // reminder ON 状態なので時刻フィールドが見えているはず
    const timeInput = page.locator('[data-testid="reminder-time-input"]')
    await expect(timeInput).toBeVisible({ timeout: 10_000 })

    // PATCH リクエストをキャプチャ
    const patchPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/action-memo-settings') && req.method() === 'PATCH',
    )

    // 時刻を設定し change イベントを発火（fill の後 Tab でフォーカスを外して @change を起動）
    await timeInput.fill('09:00')
    await timeInput.evaluate((el: HTMLInputElement, val: string) => {
      el.value = val
      el.dispatchEvent(new Event('change', { bubbles: true }))
    }, '09:00')

    // PATCH に reminder_time が含まれる
    const patchReq = await patchPromise
    const body = JSON.parse(patchReq.postData() ?? '{}') as Record<string, unknown>
    expect(body.reminder_time).toBe('09:00')

    // mock 状態も更新される
    expect(state.settings.reminder_time).toBe('09:00')
  })

  test('AM4B-SETTINGS-004: reminder トグル OFF → PATCH に reminder_enabled:false + 時刻フィールドが消える', async ({
    page,
  }) => {
    const state = buildMockState({
      settings: { reminder_enabled: true, reminder_time: '08:30' },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/settings`)
    await waitForHydration(page)

    // ON 状態なので時刻フィールドが見えているはず
    const timeInput = page.locator('[data-testid="reminder-time-input"]')
    await expect(timeInput).toBeVisible({ timeout: 10_000 })

    // PATCH リクエストをキャプチャ
    const patchPromise = page.waitForRequest(
      (req) =>
        req.url().includes('/action-memo-settings') && req.method() === 'PATCH',
    )

    // チェックボックスの @change を直接トリガー（sr-only で Playwright から操作できないため evaluate 使用）
    await page.evaluate(() => {
      const checkbox = document.querySelector('[data-testid="reminder-enabled-checkbox"]') as HTMLInputElement
      if (!checkbox) throw new Error('checkbox not found')
      checkbox.checked = false
      checkbox.dispatchEvent(new Event('change', { bubbles: true }))
    })

    // PATCH に reminder_enabled:false が含まれる
    const patchReq = await patchPromise
    const body = JSON.parse(patchReq.postData() ?? '{}') as Record<string, unknown>
    expect(body.reminder_enabled).toBe(false)

    // 時刻フィールドが非表示になる
    await expect(timeInput).toBeHidden({ timeout: 5_000 })

    // mock 状態も更新される
    expect(state.settings.reminder_enabled).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// 管理職ダッシュボード
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 4-β — 管理職ダッシュボード', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 4521, displayName: 'テスト管理者', role: 'ADMIN' })
  })

  test('AM4B-DASH-001: ページが描画される（チーム選択・メンバーID入力・検索ボタンが表示）', async ({
    page,
  }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チーム選択・メンバーID入力・検索ボタンが表示される
    await expect(page.locator('[data-testid="dashboard-team-select"]')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('[data-testid="dashboard-member-input"]')).toBeVisible()
    await expect(page.locator('[data-testid="dashboard-search-btn"]')).toBeVisible()
  })

  test('AM4B-DASH-002: 検索でメモ一覧が表示される', async ({ page }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
    })
    // ダッシュボード用メモを設定
    state.dashboardMemos = [
      buildWorkMemo({ id: 1001, content: '朝会の議事録作成', category: 'WORK' }),
      buildWorkMemo({ id: 1002, content: 'タスクA 完了', category: 'WORK' }),
    ]
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チームを選択
    await page.locator('[data-testid="dashboard-team-select"]').selectOption({ value: '10' })

    // メンバーID を入力
    await page.locator('[data-testid="dashboard-member-input"]').fill('20')

    // 検索ボタンをクリック
    await page.locator('[data-testid="dashboard-search-btn"]').click()

    // メモ一覧が表示される
    const memoItems = page.locator('[data-testid="dashboard-memo-item"]')
    await expect(memoItems).toHaveCount(2, { timeout: 10_000 })
    await expect(memoItems.first()).toContainText('朝会の議事録作成')
    await expect(memoItems.nth(1)).toContainText('タスクA 完了')
  })

  test('AM4B-DASH-003: next_cursor がある場合「もっと読み込む」→ 追加取得', async ({ page }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
    })
    // 1 ページ目のメモ（cursor なし）
    state.dashboardMemos = [
      buildWorkMemo({ id: 2001, content: 'ページ1メモ1', category: 'WORK' }),
    ]
    state.dashboardNextCursor = 'cursor-page-2'

    // 2 ページ目のメモを cursor ありで返すように上書きモックを準備
    await mockActionMemoApi(page, state)

    // cursor=cursor-page-2 のリクエストは追加メモを返す
    await page.route(/.*\/api\/v1\/teams\/\d+\/members\/\d+\/action-memos.*/, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      const url = route.request().url()
      const cursor = new URL(url).searchParams.get('cursor')
      if (cursor === 'cursor-page-2') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [buildWorkMemo({ id: 2002, content: 'ページ2メモ1', category: 'WORK' })],
            next_cursor: null,
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: state.dashboardMemos,
            next_cursor: state.dashboardNextCursor,
          }),
        })
      }
    })

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チーム + メンバーID + 検索
    await page.locator('[data-testid="dashboard-team-select"]').selectOption({ value: '10' })
    await page.locator('[data-testid="dashboard-member-input"]').fill('30')
    await page.locator('[data-testid="dashboard-search-btn"]').click()

    // 1 件表示後に「もっと読み込む」ボタンが表示される
    await expect(page.locator('[data-testid="dashboard-memo-item"]')).toHaveCount(1, {
      timeout: 10_000,
    })
    const loadMoreBtn = page.locator('[data-testid="dashboard-load-more-btn"]')
    await expect(loadMoreBtn).toBeVisible()

    // 「もっと読み込む」をクリック
    await loadMoreBtn.click()

    // 合計 2 件になる
    await expect(page.locator('[data-testid="dashboard-memo-item"]')).toHaveCount(2, {
      timeout: 10_000,
    })

    // 「もっと読み込む」ボタンは消える（next_cursor=null）
    await expect(loadMoreBtn).toBeHidden()
  })

  test('AM4B-DASH-004: 結果 0 件 → データなしメッセージ', async ({ page }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
    })
    // dashboardMemos は空のまま
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チーム + メンバーID + 検索
    await page.locator('[data-testid="dashboard-team-select"]').selectOption({ value: '10' })
    await page.locator('[data-testid="dashboard-member-input"]').fill('40')
    await page.locator('[data-testid="dashboard-search-btn"]').click()

    // 「データなし」メッセージが表示される
    await expect(page.locator('[data-testid="dashboard-no-data"]')).toBeVisible({ timeout: 10_000 })
  })

  test('AM4B-DASH-005: 403 → エラーメッセージ表示', async ({ page }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
    })
    await mockActionMemoApi(page, state)

    // ダッシュボード API を 403 で上書き
    await page.route(/.*\/api\/v1\/teams\/\d+\/members\/\d+\/action-memos.*/, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Forbidden' }),
      })
    })

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チーム + メンバーID + 検索
    await page.locator('[data-testid="dashboard-team-select"]').selectOption({ value: '10' })
    await page.locator('[data-testid="dashboard-member-input"]').fill('50')
    await page.locator('[data-testid="dashboard-search-btn"]').click()

    // エラーメッセージが表示される
    await expect(page.locator('[data-testid="dashboard-error"]')).toBeVisible({ timeout: 10_000 })
  })
})
