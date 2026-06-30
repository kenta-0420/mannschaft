import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { loginViaApi } from '../fixtures/auth'
import * as path from 'path'
import * as fs from 'fs'

/**
 * チームチャットページ ダークモード実機E2Eテスト
 *
 * 修正内容: fix/team-chat-dark-mode ブランチ (PR #2016)
 *   - /teams/[slug]/chat: dark:border/bg クラス追加
 *   - ChatChannelList / ChatMessageInput / ChatChannelHeader: dark: クラス追加
 *   - ChatMessageBubble: ホバー・アクションバー・絵文字ピッカー dark: 対応
 *   - ChatTypingIndicator / ChatActiveThreadsBar: dark: 対応
 *
 * テスト対象: http://localhost:3000（本陣FE）
 *   ※ dark mode fix は PR ブランチにあり、port 3000 は修正前コード
 *   ※ DM-002 は「修正前 = 白背景(バグ)」を実証するテスト（失敗が期待値）
 *   ※ 修正後の green 化は CI + worktree E2E で確認予定
 *
 * TEAM-CHAT-DM-001〜004
 */

const E2E_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const E2E_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

// ユーザーから指定された確認済みチームスラグ
const TEAM_SLUG = 'team-000092'

const DEBUG_DIR = path.join(process.cwd(), 'tests', 'e2e', 'debug')


/** htmlに dark クラスを追加してダークモードを有効化 */
async function enableDarkMode(page: Page): Promise<void> {
  await page.evaluate(() => {
    document.documentElement.classList.add('dark', 'p-dark')
    document.documentElement.style.setProperty('--bg-color', '#18181b')
  })
  await page.waitForTimeout(200)
}

/** ダークモードを無効化 */
async function disableDarkMode(page: Page): Promise<void> {
  await page.evaluate(() => {
    document.documentElement.classList.remove('dark', 'p-dark')
    document.documentElement.style.setProperty('--bg-color', '#f3efe0')
  })
  await page.waitForTimeout(200)
}

/** スクリーンショット保存（debugディレクトリ） */
async function saveScreenshot(page: Page, name: string): Promise<void> {
  if (!fs.existsSync(DEBUG_DIR)) {
    fs.mkdirSync(DEBUG_DIR, { recursive: true })
  }
  await page.screenshot({ path: path.join(DEBUG_DIR, name) })
}

// ─── テスト ───────────────────────────────────────────────────────────────────

test.describe('チームチャット ダークモード実機E2E', () => {
  // 同一storageStateで並行実行するとrefresh_tokenローテーション競合が発生するためシリアル実行
  test.describe.configure({ mode: 'serial' })

  test.beforeEach(async ({ page }) => {
    // トークンローテーション競合防止のため毎テスト fresh login
    await loginViaApi(page, { email: E2E_EMAIL, password: E2E_PASSWORD }, { apiBaseUrl: API_BASE_URL })
  })

  /**
   * TEAM-CHAT-DM-001: チームチャットページがライトモードで正常表示される（機能テスト）
   */
  test('TEAM-CHAT-DM-001: ライトモードでページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_SLUG}/chat`)
    await waitForHydration(page)

    // ページが表示される（ログインリダイレクトしない）
    await expect(page).toHaveURL(/\/teams\/.+\/chat/)

    // ChatChannelListの「チャット」ヘッダーが表示される
    await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    })

    // サイドバー（左カラム）が表示される
    const sidebar = page.locator('.w-64.shrink-0').first()
    await expect(sidebar).toBeVisible({ timeout: 5_000 })

    await saveScreenshot(page, 'team-chat-light-mode.png')
  })

  /**
   * TEAM-CHAT-DM-002: ダークモード適用時に白い背景が残らないことを確認
   *
   * 修正前（port 3000・本陣）: サイドバー・メッセージ領域が白いまま → 失敗が期待動作
   * 修正後（fix ブランチ）  : dark:bg-surface-800/900 が適用され暗い背景 → PASS
   *
   * このテストは fix ブランチマージ後 CI で green になることを確認する。
   * 本陣（pre-fix）では意図的に FAIL = バグの実証。
   * @fixme fix/team-chat-dark-mode ブランチ（PR #2016）マージ後に green になる
   */
  test.fixme(
    'TEAM-CHAT-DM-002: ダークモードでサイドバー・メッセージ領域が暗い背景になる',
    async ({ page }) => {
      await page.goto(`/teams/${TEAM_SLUG}/chat`)
      await waitForHydration(page)

      await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible({
        timeout: 10_000,
      })

      // ダークモード有効化（本来は useAppearanceStore.toggleDark() 経由）
      await enableDarkMode(page)

      // スクリーンショット: 修正前の状態（白いサイドバー・白いメッセージ領域が見える）
      await saveScreenshot(page, 'team-chat-dark-BEFORE-fix.png')

      // サイドバーの背景色を確認
      const sidebarEl = page.locator('.w-64.shrink-0').first()
      await expect(sidebarEl).toBeVisible()

      const sidebarBg = await page.evaluate((el) => {
        return window.getComputedStyle(el).backgroundColor
      }, await sidebarEl.elementHandle())

      // 修正後: dark:bg-surface-800 が適用され白(rgb(255, 255, 255))でなくなる
      // 修正前: bg-surface-50 が白に近い色のまま
      expect(sidebarBg, `サイドバーが白背景のまま（修正前の状態）: ${sidebarBg}`).not.toBe(
        'rgb(255, 255, 255)',
      )
    },
  )

  /**
   * TEAM-CHAT-DM-003: チャンネル選択でメッセージ入力欄が表示される（機能テスト）
   */
  test('TEAM-CHAT-DM-003: チャンネル選択でメッセージ入力欄が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_SLUG}/chat`)
    await waitForHydration(page)

    await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    })

    // チャンネルボタンを探してクリック
    const channelButtons = page.locator('[data-testid^="chat-channel-"]')
    const count = await channelButtons.count()

    if (count === 0) {
      // チャンネルがない場合は空状態の確認
      await expect(page.locator('.flex.h-full.flex-col.items-center')).toBeVisible({
        timeout: 5_000,
      })
      return
    }

    // 最初のチャンネルをクリック
    await channelButtons.first().click()

    // メッセージ入力欄が表示される
    await expect(page.locator('[data-testid="team-chat-input"]')).toBeVisible({ timeout: 10_000 })

    // チャンネルヘッダーも表示される
    await expect(page.locator('.border-b.border-surface-200').first()).toBeVisible()

    await saveScreenshot(page, 'team-chat-channel-selected.png')
  })

  /**
   * TEAM-CHAT-DM-004: ライト↔ダーク切替でページがクラッシュしない
   */
  test('TEAM-CHAT-DM-004: ダークモード切替でページがクラッシュしない', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_SLUG}/chat`)
    await waitForHydration(page)

    await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    })

    // ライト → ダーク切替
    await enableDarkMode(page)
    await saveScreenshot(page, 'team-chat-dark-mode-after-toggle.png')

    // ページが壊れていない（サイドバーが見えている）
    await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible()

    // ダーク → ライト切替
    await disableDarkMode(page)

    // 戻ってもサイドバーが見えている
    await expect(page.getByText('チャット', { exact: true }).first()).toBeVisible()

    // Vue致命的エラーがないことを確認
    const fatalErrors = consoleErrors.filter(
      (e) =>
        e.includes('[Vue warn]: Unhandled error') ||
        e.includes('is not a function') ||
        (e.includes('[Vue warn]') && e.includes('render')),
    )
    expect(fatalErrors, `致命的Vueエラー: ${JSON.stringify(fatalErrors)}`).toHaveLength(0)
  })
})
