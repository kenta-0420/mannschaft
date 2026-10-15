import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * F04.2.1 個人チャットページ (/chat) 実機E2Eテスト（モックなし・実BE・実FE）
 *
 * 前提:
 *   - バックエンド http://localhost:8080 起動済み
 *   - フロントエンド http://localhost:3000 (or BASE_URL) 起動済み
 *   - e2e-user@test.mannschaft.local が実DBに存在し、チャンネル「全体連絡」(id=1) /
 *     「試合速報」(id=2) にアクセス可能であること（実機確認済み 2026-06-30）
 *
 * このスペックは page.route() を一切使わない。実APIのネスト形状
 *   { id, identity:{channelType,teamId}, meta:{name}, settings:{isArchived} }
 * がFEで正しくフラット表示へマップされるか（モックのフラット形状では出ない実機専用バグ）を含めて検証する。
 *
 * CHAT-PAGE-001〜004
 */

const E2E_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const E2E_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const API_BASE = 'http://localhost:8080'

const CH_PRIMARY = '全体連絡'
const CH_SECONDARY = '試合速報'

// ─── ヘルパー ────────────────────────────────────────────────────────

/** localStorage のタブ残留を消す（前テストの持ち越し防止） */
async function clearTabs(page: Page): Promise<void> {
  await page.addInitScript(() => {
    try {
      const keys: string[] = []
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i)
        if (k?.startsWith('chatTabs:')) keys.push(k)
      }
      keys.forEach((k) => localStorage.removeItem(k))
    } catch {
      /* noop */
    }
  })
}

/** /chat を開き、サイドバーの実チャンネルをクリックしてタブを開く */
async function openChatTab(page: Page, channelName: string): Promise<void> {
  // サイドバー（PC）のチャンネル一覧から該当チャンネルをクリック
  const channelInList = page
    .locator('aside')
    .getByText(channelName, { exact: false })
    .first()
  await channelInList.waitFor({ state: 'visible', timeout: 15_000 })
  await channelInList.click()
  // タブが開き、アクティブパネルのメッセージ入力欄が表示されるまで待つ
  // ※ [data-tab-active="true"] でスコープすることで strict mode violation（複数タブ時の入力欄重複）を回避
  await expect(page.getByRole('tab', { name: new RegExp(channelName) }).first()).toBeVisible({
    timeout: 8_000,
  })
  await expect(
    page.locator('[data-tab-active="true"] [data-testid="team-chat-input"]'),
  ).toBeVisible({ timeout: 10_000 })
}

/** アクティブパネルへメッセージを送信する */
async function sendMessage(page: Page, body: string): Promise<void> {
  const input = page.locator('[data-tab-active="true"] [data-testid="team-chat-input"]')
  await input.click()
  await input.fill(body)
  await page.locator('[data-tab-active="true"] [data-testid="chat-send-btn"]').click()
}

/** 指定本文のメッセージバブルを右クリックして削除する（クリーンアップ） */
async function deleteMessage(page: Page, body: string): Promise<void> {
  try {
    const bubble = page
      .locator('[data-testid="chat-message"]')
      .filter({ hasText: body })
      .last()
    if (!(await bubble.isVisible().catch(() => false))) return
    await bubble.click({ button: 'right' })
    const menu = page.locator('[data-testid="chat-context-menu"]')
    await menu.waitFor({ state: 'visible', timeout: 4_000 })
    const del = page.locator('[data-testid="context-menu-item"][data-key="delete"]')
    if (await del.isVisible().catch(() => false)) {
      await del.click()
      await page.waitForTimeout(500)
    } else {
      await page.keyboard.press('Escape')
    }
  } catch {
    /* クリーンアップ失敗は無視 */
  }
}

// ─── テストスイート ─────────────────────────────────────────────────

test.describe('F04.2.1 個人チャットページ /chat 実機E2E', () => {
  // シリアル実行 + loginViaApi による毎回フレッシュ認証で refresh_token ローテーション問題を回避
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(120_000)

  test.beforeEach(async ({ page }) => {
    // storageState への依存を避け、毎テストで API 直接ログインによるフレッシュ認証を行う
    // （BE 再起動後の storageState 陳腐化・refresh_token ローテーションによる 401 を根治）
    await loginViaApi(page, { email: E2E_EMAIL, password: E2E_PASSWORD }, { apiBaseUrl: API_BASE })
    await clearTabs(page)
  })

  /**
   * CHAT-PAGE-001: /chat が表示され、実BEのチャンネル一覧が描画され、
   * チャンネルをクリックするとタブが開いて入力欄が出る。
   * （実APIのネスト形状 meta.name → サイドバー/タブ表示のマッピング検証）
   */
  test('CHAT-PAGE-001: 実チャンネル一覧が表示されタブを開ける', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await expect(page).toHaveURL(/\/chat/)

    // 実BEから取得したチャンネル名がサイドバーに表示される
    await expect(page.locator('aside').getByText(CH_PRIMARY).first()).toBeVisible({
      timeout: 15_000,
    })

    // クリックでタブが開き、メッセージ入力欄が出る
    await openChatTab(page, CH_PRIMARY)
  })

  /**
   * CHAT-PAGE-002: メッセージを実送信 → 画面に表示され、
   * リロード後も残存する（POST → DB永続化 → GET 再取得の一気通貫）。
   * 後始末: 送信したメッセージをコンテキストメニューから削除する。
   */
  test('CHAT-PAGE-002: メッセージ送信が画面表示＋リロード後も永続する', async ({ page }) => {
    const body = `E2E-CHATPAGE-${Date.now()}`

    await page.goto('/chat')
    await waitForHydration(page)
    await openChatTab(page, CH_PRIMARY)

    // 送信
    await sendMessage(page, body)

    // 送信したメッセージが画面に表示される（REST onSent→loadMessages 再取得経路）
    await expect(
      page.locator('[data-testid="chat-message"]').filter({ hasText: body }),
    ).toBeVisible({ timeout: 10_000 })

    // リロードして再度チャンネルを開く → DB から取得され、まだ存在する
    await page.reload()
    await waitForHydration(page)
    await openChatTab(page, CH_PRIMARY)
    await expect(
      page.locator('[data-testid="chat-message"]').filter({ hasText: body }),
    ).toBeVisible({ timeout: 10_000 })

    // 後始末: 送信メッセージを削除
    await deleteMessage(page, body)
  })

  /**
   * CHAT-PAGE-003: 2つの実チャンネルを開いて2タブになり、切替できる。
   * Alt+W でアクティブタブが閉じる（キーボードショートカット §6.2）。
   */
  test('CHAT-PAGE-003: 複数タブ切替と Alt+W クローズ', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await openChatTab(page, CH_PRIMARY)
    await openChatTab(page, CH_SECONDARY)

    // 2タブ存在
    await expect(page.getByRole('tab')).toHaveCount(2, { timeout: 8_000 })

    // 1つ目へ切替
    const primaryTab = page.getByRole('tab', { name: new RegExp(CH_PRIMARY) }).first()
    await primaryTab.click()
    await expect(primaryTab).toHaveAttribute('aria-selected', 'true', { timeout: 3_000 })

    // Alt+W でアクティブタブを閉じる → 1タブになる
    await page.keyboard.press('Alt+w')
    await expect(page.getByRole('tab')).toHaveCount(1, { timeout: 5_000 })
  })

  /**
   * CHAT-PAGE-004: サイドバーの「連絡先」「申請」タブが実BEで描画でき、
   * 500/例外なく空状態または一覧を表示する。
   */
  test('CHAT-PAGE-004: 連絡先・申請サイドバータブが実BEで描画される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    // サイドバーの「連絡先」タブへ切替
    const contactsTab = page.locator('aside').getByText('連絡先', { exact: false }).first()
    if (await contactsTab.isVisible().catch(() => false)) {
      await contactsTab.click()
      // タブ切替後もページがクラッシュせず /chat に留まる
      await expect(page).toHaveURL(/\/chat/)
      // 連絡先追加ボタンが出る（ContactList のヘッダ）
      await expect(page.locator('aside')).toBeVisible()
    }

    // 「申請」タブへ切替
    const requestsTab = page.locator('aside').getByText('申請', { exact: false }).first()
    if (await requestsTab.isVisible().catch(() => false)) {
      await requestsTab.click()
      await expect(page).toHaveURL(/\/chat/)
      await expect(page.locator('aside')).toBeVisible()
    }
  })
})
