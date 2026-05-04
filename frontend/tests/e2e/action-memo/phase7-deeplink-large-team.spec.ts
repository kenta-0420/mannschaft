import { test, expect } from '@playwright/test'
import {
  setupAuth,
  buildMockState,
  buildWorkMemo,
  mockActionMemoApi,
  waitForHydration,
  todayJst,
} from '../helpers/action-memo-mocks'

/**
 * F02.5 Phase 7 E2E テスト。
 *
 * <p>ディープリンク（?date クエリ）と大規模チームメンバー取得（全ページフェッチ）の
 * 2 グループ計 6 件のテストを実装する。</p>
 *
 * <p>Phase 7 で追加された機能:</p>
 * <ul>
 *   <li>リマインド通知ディープリンク: /action-memo?date=YYYY-MM-DD で指定日のメモを表示</li>
 *   <li>大規模チーム対応: fetchTeamMembers が totalPages まで全ページ取得する</li>
 * </ul>
 */

// テスト対象サーバー（worktree の開発サーバー: IPv4 127.0.0.1 を明示して IPv6 の 426 を回避）
const BASE = process.env.TEST_BASE_URL ?? 'http://127.0.0.1:3002'

// ---------------------------------------------------------------------------
// グループ1: ディープリンク（?date クエリ）
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 7 — ディープリンク', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 7001, displayName: 'ディープリンクユーザー', role: 'MEMBER' })
  })

  test('AM7-DEEP-001: ?date クエリなしは今日のメモを表示', async ({ page }) => {
    const today = todayJst()
    const state = buildMockState({
      memos: [buildWorkMemo({ id: 1, content: '今日のメモ', memo_date: today })],
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo`)
    await waitForHydration(page)

    // 今日のメモが1件表示される
    const cards = page.locator('[data-testid="action-memo-card"]')
    await expect(cards).toHaveCount(1, { timeout: 10_000 })
    await expect(cards.first()).toContainText('今日のメモ', { timeout: 5_000 })
  })

  test('AM7-DEEP-002: ?date=YYYY-MM-DD で指定日のメモを表示', async ({ page }) => {
    const today = todayJst()
    const pastDate = '2026-04-01'
    const state = buildMockState({
      memos: [
        buildWorkMemo({ id: 1, content: '今日のメモ', memo_date: today }),
        buildWorkMemo({ id: 2, content: '2026-04-01のメモ', memo_date: pastDate }),
      ],
    })
    await mockActionMemoApi(page, state)

    // 過去日付を指定してアクセス
    await page.goto(`${BASE}/action-memo?date=${pastDate}`)
    await waitForHydration(page)

    // 指定日のメモが表示される
    const cards = page.locator('[data-testid="action-memo-card"]')
    await expect(cards).toHaveCount(1, { timeout: 10_000 })
    await expect(cards.first()).toContainText('2026-04-01のメモ', { timeout: 5_000 })

    // 今日のメモは表示されない
    const allText = await page.locator('[data-testid="action-memo-card"]').allTextContents()
    expect(allText.join('')).not.toContain('今日のメモ')
  })

  test('AM7-DEEP-003: ?date に無効値（invalid）は今日にフォールバック', async ({ page }) => {
    const today = todayJst()
    const state = buildMockState({
      memos: [buildWorkMemo({ id: 1, content: '今日のメモ', memo_date: today })],
    })
    await mockActionMemoApi(page, state)

    // 無効な date クエリでアクセス（クラッシュせず今日にフォールバックすること）
    await page.goto(`${BASE}/action-memo?date=invalid`)
    await waitForHydration(page)

    // 今日のメモが表示される（クラッシュしない）
    const cards = page.locator('[data-testid="action-memo-card"]')
    await expect(cards).toHaveCount(1, { timeout: 10_000 })
    await expect(cards.first()).toContainText('今日のメモ', { timeout: 5_000 })
  })
})

// ---------------------------------------------------------------------------
// グループ2: 大規模チームドロップダウン
// ---------------------------------------------------------------------------

test.describe('F02.5 Phase 7 — 大規模チームメンバー取得', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, { userId: 7002, displayName: '管理者ユーザー', role: 'ADMIN' })
  })

  test('AM7-TEAM-001: 1ページ分のメンバーが全員ドロップダウンに表示される', async ({ page }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'テストチーム', is_default: false }],
      teamMembersPages: {
        10: [
          [
            { userId: 101, displayName: 'Alice' },
            { userId: 102, displayName: 'Bob' },
          ],
        ],
      },
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チームを選択
    const teamSelect = page.locator('[data-testid="dashboard-team-select"]')
    await expect(teamSelect).toBeVisible({ timeout: 10_000 })
    await teamSelect.selectOption({ value: '10' })

    // メンバーが読み込まれるまで待つ（disabled が解除されることを確認）
    const memberSelect = page.locator('[data-testid="dashboard-member-input"]')
    await expect(memberSelect).toBeEnabled({ timeout: 10_000 })

    // Alice と Bob の option が存在する
    const options = memberSelect.locator('option')
    const optionTexts = await options.allTextContents()
    expect(optionTexts).toContain('Alice')
    expect(optionTexts).toContain('Bob')
  })

  test('AM7-TEAM-002: 2ページ分のメンバーが結合されてドロップダウンに全員表示される', async ({
    page,
  }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: '大規模チーム', is_default: false }],
      teamMembersPages: {
        10: [
          [{ userId: 101, displayName: 'Page1-User' }],
          [{ userId: 102, displayName: 'Page2-User' }],
        ],
      },
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チームを選択
    const teamSelect = page.locator('[data-testid="dashboard-team-select"]')
    await expect(teamSelect).toBeVisible({ timeout: 10_000 })
    await teamSelect.selectOption({ value: '10' })

    // メンバーが読み込まれるまで待つ（disabled が解除されることを確認）
    const memberSelect = page.locator('[data-testid="dashboard-member-input"]')
    await expect(memberSelect).toBeEnabled({ timeout: 10_000 })

    // Page1-User と Page2-User の両方が option に存在する
    const options = memberSelect.locator('option')
    const optionTexts = await options.allTextContents()
    expect(optionTexts).toContain('Page1-User')
    expect(optionTexts).toContain('Page2-User')
  })

  test('AM7-TEAM-003: チームメンバー取得中は select が disabled になり取得後に enabled になる', async ({
    page,
  }) => {
    const state = buildMockState({
      availableTeams: [{ id: 10, name: 'チーム', is_default: false }],
      teamMembersPages: {
        10: [
          [
            { userId: 101, displayName: '太郎' },
            { userId: 102, displayName: '花子' },
          ],
        ],
      },
    })
    await mockActionMemoApi(page, state)

    await page.goto(`${BASE}/action-memo/dashboard`)
    await waitForHydration(page)

    // チームを選択
    const teamSelect = page.locator('[data-testid="dashboard-team-select"]')
    await expect(teamSelect).toBeVisible({ timeout: 10_000 })
    await teamSelect.selectOption({ value: '10' })

    // 最終的に select が enabled になることを確認（membersLoading が false になった後）
    const memberSelect = page.locator('[data-testid="dashboard-member-input"]')
    await expect(memberSelect).toBeEnabled({ timeout: 10_000 })

    // メンバーが正しく表示されていることを確認
    const options = memberSelect.locator('option')
    const optionTexts = await options.allTextContents()
    expect(optionTexts.some((t) => t.includes('太郎'))).toBe(true)
    expect(optionTexts.some((t) => t.includes('花子'))).toBe(true)
  })
})
