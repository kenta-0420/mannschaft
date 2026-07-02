import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * PR #2067: チームダッシュボード TeamSidebar Drawer 実機E2Eテスト
 *
 * 変更内容:
 *   - チームダッシュボード (/teams/fc-u-18) のタブバーにハンバーガーアイコンを追加
 *   - ボタン押下で PrimeVue Drawer (左スライド) が開き TeamSidebar（チームナビ）を表示
 *   - チャットページ (/teams/fc-u-18/chat) は2カラムレイアウト（ChatChannelList + MessagePanel）を維持
 *
 * 前提:
 *   - BE http://localhost:8080、FE http://localhost:3000 起動済み
 *   - e2e-user@test.mannschaft.local が fc-u-18 チームのメンバー
 *
 * TEAM-NAV-DRW-001〜003
 */

const E2E_EMAIL = 'e2e-user@test.mannschaft.local'
const E2E_PASSWORD = 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'
const API_BASE = 'http://localhost:8080'

test.describe('PR#2067 チームダッシュボード TeamSidebar Drawer 実機E2E', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(120_000)

  test.beforeEach(async ({ page }) => {
    await loginViaApi(page, { email: E2E_EMAIL, password: E2E_PASSWORD }, { apiBaseUrl: API_BASE })
  })

  /**
   * TEAM-NAV-DRW-001: ダッシュボードのタブバーにハンバーガーアイコンが表示される
   */
  test('TEAM-NAV-DRW-001: チームダッシュボードにメニューボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_SLUG}`)
    await waitForHydration(page)

    const menuBtn = page.locator('button[aria-label="メニュー"]')
    await expect(menuBtn).toBeVisible({ timeout: 15_000 })
  })

  /**
   * TEAM-NAV-DRW-002: メニューボタン押下でDrawerが開きTeamSidebar（チームナビ）が表示される
   */
  test('TEAM-NAV-DRW-002: Drawerが開きチームナビゲーションが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_SLUG}`)
    await waitForHydration(page)

    const menuBtn = page.locator('button[aria-label="メニュー"]')
    await menuBtn.waitFor({ state: 'visible', timeout: 15_000 })
    await menuBtn.click()

    const drawer = page.locator('[role="dialog"]').first()
    await expect(drawer).toBeVisible({ timeout: 8_000 })

    // TeamSidebar にはスケジュールやダッシュボードなどのナビゲーション項目が含まれる
    await expect(
      drawer.getByText('スケジュール', { exact: false })
        .or(drawer.getByText('メニュー', { exact: false })),
    ).toBeVisible({ timeout: 10_000 })
  })

  /**
   * TEAM-NAV-DRW-003: チャットページは2カラムレイアウト（ChatChannelList サイドバー + メッセージエリア）
   */
  test('TEAM-NAV-DRW-003: チャットページが2カラムレイアウトで表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_SLUG}/chat`)
    await waitForHydration(page)

    // 左サイドバー（w-64 のChatChannelList）が存在する
    const sidebar = page.locator('aside.w-64')
    await expect(sidebar).toBeVisible({ timeout: 15_000 })

    // ページタイトル「チャット」が表示される
    await expect(
      page.getByRole('heading').filter({ hasText: 'チャット' }),
    ).toBeVisible({ timeout: 10_000 })
  })
})
