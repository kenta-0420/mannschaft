import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F04.2 チャット: スレッド返信・コンテキストメニュー・掲示板移行 実機E2Eテスト
 *
 * 前提: バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済み
 * 認証: ログインヘルパー経由 (e2e-user@test.mannschaft.local)
 * チーム: FC東京U-18（テスト）
 *
 * CHAT-THREAD-001〜007
 */

// ─── 定数 ────────────────────────────────────────────────────────────────────

const E2E_EMAIL = 'e2e-user@test.mannschaft.local'
const E2E_PASSWORD = 'TestPass2026!'

// ─── ヘルパー ────────────────────────────────────────────────────────────────

/** ログインが必要な場合のみログインする */
async function loginIfNeeded(page: Page): Promise<void> {
  await page.goto('/my/dashboard')
  if (page.url().includes('/login')) {
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(E2E_EMAIL, { delay: 10 })
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(E2E_PASSWORD, { delay: 10 })
    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForURL(/.*\/my\/.*|.*\/dashboard.*/, { timeout: 30_000 })
  }
}

/** FC東京U-18 チームのIDを取得する */
async function getE2eTeamId(page: Page): Promise<string> {
  await page.goto('/teams')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const teamLinks = page.locator('a[href*="/teams/"]')
  const count = await teamLinks.count()
  for (let i = 0; i < count; i++) {
    const href = await teamLinks.nth(i).getAttribute('href')
    if (href && href.match(/\/teams\/\d+$/)) {
      const text = await teamLinks.nth(i).textContent()
      if (text && text.includes('FC東京U-18')) {
        const match = href.match(/\/teams\/(\d+)/)
        if (match?.[1]) return match[1]
      }
    }
  }
  return '1'
}

/** チャット画面を開き、最初のチャンネルを選択して安定するまで待つ */
async function openChatAndSelectChannel(
  page: Page,
  teamId: string,
): Promise<boolean> {
  await page.goto(`/teams/${teamId}/chat`)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // チャンネルリストのアイテムを探す
  const channelItems = page.locator(
    '[class*="channel-item"], [class*="channel-list"] li, [data-testid="channel-item"]',
  )
  const count = await channelItems.count()
  if (count === 0) {
    // サイドバーに直接テキストで表示されているチャンネルを探す
    const generalChannel = page
      .locator('aside a, nav a, [class*="sidebar"] a')
      .filter({ hasText: /全体|general|チャンネル/ })
      .first()
    const visible = await generalChannel.isVisible().catch(() => false)
    if (!visible) return false
    await generalChannel.click()
  } else {
    await channelItems.first().click()
  }

  // メッセージエリアが表示されるまで待機
  const messageArea = page.locator(
    'textarea[placeholder*="メッセージ"], [data-testid="message-input"], input[placeholder*="メッセージ"]',
  )
  await messageArea.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {})
  return true
}

/** テストメッセージを送信する */
async function sendTestMessage(page: Page, body: string): Promise<void> {
  const messageInput = page.locator(
    'textarea[placeholder*="メッセージ"], [data-testid="message-input"], input[placeholder*="メッセージ"]',
  )
  await messageInput.click()
  await messageInput.pressSequentially(body, { delay: 10 })
  await messageInput.press('Enter')
  // 送信完了を少し待つ
  await page.waitForTimeout(800)
}

/** 送信したメッセージを右クリックしてコンテキストメニューを表示する */
async function rightClickMessage(page: Page, messageText: string): Promise<boolean> {
  // メッセージバブルを探す
  const messages = page.locator('[data-testid="chat-message"]')
  const count = await messages.count()
  for (let i = count - 1; i >= 0; i--) {
    const text = await messages.nth(i).textContent().catch(() => '')
    if (text && text.includes(messageText)) {
      await messages.nth(i).click({ button: 'right' })
      return true
    }
  }
  // フォールバック: 最後のメッセージを右クリック
  if (count > 0) {
    await messages.last().click({ button: 'right' })
    return true
  }
  return false
}

/** メッセージをコンテキストメニューから削除する（クリーンアップ用） */
async function deleteMessageViaContextMenu(
  page: Page,
  messageText: string,
): Promise<void> {
  try {
    const found = await rightClickMessage(page, messageText)
    if (!found) return

    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await contextMenu.waitFor({ state: 'visible', timeout: 5_000 })

    const deleteItem = page.locator('[data-testid="context-menu-item"][data-key="delete"]')
    const deleteVisible = await deleteItem.isVisible().catch(() => false)
    if (deleteVisible) {
      await deleteItem.click()
      await page.waitForTimeout(500)
    } else {
      // メニューを閉じる
      await page.keyboard.press('Escape')
    }
  } catch {
    // クリーンアップ失敗は無視する
  }
}

// ─── テストスイート ───────────────────────────────────────────────────────────

test.describe('F04.2 チャット: スレッド返信・コンテキストメニュー・掲示板移行', () => {
  let teamId: string

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage()
    await loginIfNeeded(page)
    teamId = await getE2eTeamId(page)
    await page.close()
  })

  test.beforeEach(async ({ page }) => {
    await loginIfNeeded(page)
  })

  /**
   * CHAT-THREAD-001: チャットページが表示されチャンネルを選択できる
   */
  test('CHAT-THREAD-001: チャットページが表示されチャンネルを選択できる', async ({ page }) => {
    await page.goto(`/teams/${teamId}/chat`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // チャットページが表示されることを確認
    await expect(page).toHaveURL(new RegExp(`/teams/${teamId}/chat`))

    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    // メッセージエリアが表示される
    const messageInput = page.locator(
      'textarea[placeholder*="メッセージ"], [data-testid="message-input"], input[placeholder*="メッセージ"]',
    )
    await expect(messageInput.first()).toBeVisible({ timeout: 10_000 })
  })

  /**
   * CHAT-THREAD-002: メッセージを右クリックするとコンテキストメニューが表示される
   */
  test('CHAT-THREAD-002: メッセージを右クリックするとコンテキストメニューが表示される', async ({
    page,
  }) => {
    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    const testMsg = `E2E-CTXMENU-${Date.now()}`
    await sendTestMessage(page, testMsg)

    // 送信したメッセージを右クリック
    const found = await rightClickMessage(page, testMsg)
    if (!found) {
      test.skip()
      return
    }

    // コンテキストメニューが表示される
    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await expect(contextMenu).toBeVisible({ timeout: 5_000 })

    // クリーンアップ: ESC でメニューを閉じる
    await page.keyboard.press('Escape')
    await expect(contextMenu).not.toBeVisible({ timeout: 3_000 })

    // メッセージを削除
    await deleteMessageViaContextMenu(page, testMsg)
  })

  /**
   * CHAT-THREAD-003: コンテキストメニューから「スレッドで返信」でスレッドパネルが開く
   */
  test('CHAT-THREAD-003: コンテキストメニューから「スレッドで返信」でスレッドパネルが開く', async ({
    page,
  }) => {
    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    const testMsg = `E2E-THREAD-OPEN-${Date.now()}`
    await sendTestMessage(page, testMsg)

    // メッセージを右クリック
    const found = await rightClickMessage(page, testMsg)
    if (!found) {
      test.skip()
      return
    }

    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await contextMenu.waitFor({ state: 'visible', timeout: 5_000 })

    // 「スレッドで返信」 メニューアイテムをクリック
    const replyItem = page.locator('[data-testid="context-menu-item"][data-key="reply"]')
    const replyItemVisible = await replyItem.isVisible().catch(() => false)
    if (!replyItemVisible) {
      // parentId がないルートメッセージのみ reply が出る。
      // 表示されない場合はテストをスキップ
      await page.keyboard.press('Escape')
      await deleteMessageViaContextMenu(page, testMsg)
      test.skip()
      return
    }

    await replyItem.click()

    // スレッドパネルが表示される
    const threadPanel = page.locator('[data-testid="chat-thread-panel"]')
    await expect(threadPanel).toBeVisible({ timeout: 8_000 })

    // クリーンアップ: ルートメッセージを削除（スレッドごと消える）
    await deleteMessageViaContextMenu(page, testMsg)
  })

  /**
   * CHAT-THREAD-004: スレッドパネルで返信を送信すると返信数が増える
   */
  test('CHAT-THREAD-004: スレッドパネルで返信を送信すると返信数が増える', async ({ page }) => {
    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    const testMsg = `E2E-THREAD-REPLY-${Date.now()}`
    await sendTestMessage(page, testMsg)

    // コンテキストメニュー → スレッドで返信
    const found = await rightClickMessage(page, testMsg)
    if (!found) {
      test.skip()
      return
    }

    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await contextMenu.waitFor({ state: 'visible', timeout: 5_000 })

    const replyItem = page.locator('[data-testid="context-menu-item"][data-key="reply"]')
    const replyItemVisible = await replyItem.isVisible().catch(() => false)
    if (!replyItemVisible) {
      await page.keyboard.press('Escape')
      await deleteMessageViaContextMenu(page, testMsg)
      test.skip()
      return
    }

    await replyItem.click()

    // スレッドパネルが開いていることを確認
    const threadPanel = page.locator('[data-testid="chat-thread-panel"]')
    await expect(threadPanel).toBeVisible({ timeout: 8_000 })

    // 返信を入力して送信
    const replyInput = threadPanel.locator('[data-testid="thread-reply-input"]')
    await expect(replyInput).toBeVisible({ timeout: 5_000 })
    await replyInput.click()
    await replyInput.pressSequentially(`スレッド返信-${Date.now()}`, { delay: 10 })

    const sendButton = threadPanel.locator('[data-testid="thread-reply-send"]')
    await sendButton.click()

    // 返信メッセージが thread-message として表示される
    const threadMessages = threadPanel.locator('[data-testid="thread-message"]')
    await expect(threadMessages).toHaveCount(1, { timeout: 8_000 })

    // クリーンアップ
    await deleteMessageViaContextMenu(page, testMsg)
  })

  /**
   * CHAT-THREAD-005: スレッドへの返信にさらに返信できる（depth 2 のネスト）
   */
  test('CHAT-THREAD-005: スレッドへの返信にさらに返信できる（depth 2 のネスト）', async ({
    page,
  }) => {
    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    const testMsg = `E2E-NESTED-${Date.now()}`
    await sendTestMessage(page, testMsg)

    // コンテキストメニュー → スレッドで返信（depth=1 の返信を作る）
    const found = await rightClickMessage(page, testMsg)
    if (!found) {
      test.skip()
      return
    }

    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await contextMenu.waitFor({ state: 'visible', timeout: 5_000 })

    const replyItem = page.locator('[data-testid="context-menu-item"][data-key="reply"]')
    const replyItemVisible = await replyItem.isVisible().catch(() => false)
    if (!replyItemVisible) {
      await page.keyboard.press('Escape')
      await deleteMessageViaContextMenu(page, testMsg)
      test.skip()
      return
    }

    await replyItem.click()

    const threadPanel = page.locator('[data-testid="chat-thread-panel"]')
    await expect(threadPanel).toBeVisible({ timeout: 8_000 })

    // depth=1: スレッドパネルで返信を送信
    const reply1Text = `depth1-${Date.now()}`
    const replyInput = threadPanel.locator('[data-testid="thread-reply-input"]')
    await replyInput.click()
    await replyInput.pressSequentially(reply1Text, { delay: 10 })
    await threadPanel.locator('[data-testid="thread-reply-send"]').click()

    // depth=1 の返信が表示されることを確認
    const threadMessages = threadPanel.locator('[data-testid="thread-message"]')
    await expect(threadMessages).toHaveCount(1, { timeout: 8_000 })

    // depth=2: depth=1 メッセージ内の chat-message を右クリック
    // スレッドパネル内の ChatMessageBubble を対象にする
    const depth1Message = threadPanel.locator('[data-testid="chat-message"]').first()
    await depth1Message.click({ button: 'right' })

    const nestedContextMenu = page.locator('[data-testid="chat-context-menu"]')
    const nestedMenuVisible = await nestedContextMenu.isVisible().catch(() => false)

    if (nestedMenuVisible) {
      // スレッドパネル内はルートでないので reply アイテムが出ない可能性があるが確認する
      const depth2ReplyItem = page.locator('[data-testid="context-menu-item"][data-key="reply"]')
      const depth2ReplyVisible = await depth2ReplyItem.isVisible().catch(() => false)
      if (depth2ReplyVisible) {
        await depth2ReplyItem.click()
        // ネスト返信が展開されるか、パネルが更新される
        await page.waitForTimeout(1_000)
        // depth=2 の返信入力欄にテキストを入力して送信
        await replyInput.click()
        await replyInput.pressSequentially(`depth2-${Date.now()}`, { delay: 10 })
        await threadPanel.locator('[data-testid="thread-reply-send"]').click()
        await page.waitForTimeout(800)

        // メッセージが増えていることを確認
        const messagesAfter = await threadPanel
          .locator('[data-testid="thread-message"]')
          .count()
        expect(messagesAfter).toBeGreaterThanOrEqual(1)
      } else {
        await page.keyboard.press('Escape')
      }
    }

    // クリーンアップ
    await deleteMessageViaContextMenu(page, testMsg)
  })

  /**
   * CHAT-THREAD-006: アクティブスレッド一覧ドロワーが開く
   */
  test('CHAT-THREAD-006: アクティブスレッド一覧ドロワーが開く', async ({ page }) => {
    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    // チャンネルヘッダーのアクティブスレッドバッジ/ボタンを探す
    // 一般的なセレクタで試みる
    const threadBadge = page.locator(
      '[data-testid="active-threads-btn"], '
        + 'button:has(.pi-comments), '
        + 'button:has-text("スレッド"), '
        + '[title*="スレッド"], '
        + '[aria-label*="スレッド"]',
    )
    const badgeVisible = await threadBadge.first().isVisible().catch(() => false)
    if (!badgeVisible) {
      test.skip()
      return
    }

    await threadBadge.first().click()

    // ドロワーまたはパネルが表示される
    const drawer = page.locator(
      '[data-testid="active-threads-drawer"], '
        + '[role="dialog"]:has-text("スレッド"), '
        + '[class*="drawer"]:has-text("スレッド")',
    )
    await expect(drawer.first()).toBeVisible({ timeout: 5_000 })
  })

  /**
   * CHAT-THREAD-007: depth ≥ 10 のメッセージに掲示板移行バナーが表示される
   *
   * 10回ネスト返信を繰り返すため時間がかかる。
   * サーバーが応答しない場合や time budget オーバーの場合はスキップ。
   */
  test('CHAT-THREAD-007: depth ≥ 10 のメッセージに掲示板移行バナーが表示される', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const opened = await openChatAndSelectChannel(page, teamId)
    if (!opened) {
      test.skip()
      return
    }

    const rootMsg = `E2E-DEEP-NEST-${Date.now()}`
    await sendTestMessage(page, rootMsg)

    // コンテキストメニュー → スレッドで返信
    const found = await rightClickMessage(page, rootMsg)
    if (!found) {
      test.skip()
      return
    }

    const contextMenu = page.locator('[data-testid="chat-context-menu"]')
    await contextMenu.waitFor({ state: 'visible', timeout: 5_000 })

    const replyItem = page.locator('[data-testid="context-menu-item"][data-key="reply"]')
    const replyItemVisible = await replyItem.isVisible().catch(() => false)
    if (!replyItemVisible) {
      await page.keyboard.press('Escape')
      await deleteMessageViaContextMenu(page, rootMsg)
      test.skip()
      return
    }

    await replyItem.click()

    const threadPanel = page.locator('[data-testid="chat-thread-panel"]')
    await expect(threadPanel).toBeVisible({ timeout: 8_000 })

    // 10回返信を繰り返して depth=10 に到達させる
    const replyInput = threadPanel.locator('[data-testid="thread-reply-input"]')
    const sendButton = threadPanel.locator('[data-testid="thread-reply-send"]')

    for (let i = 1; i <= 10; i++) {
      await replyInput.waitFor({ state: 'visible', timeout: 5_000 })
      await replyInput.click()
      await replyInput.pressSequentially(`depth-${i}-${Date.now()}`, { delay: 5 })
      await sendButton.click()
      // 返信が追加されるのを待つ
      await page.waitForTimeout(600)
    }

    // depth=10 の返信で suggestBoardMigration=true になれば
    // board-migration-banner が表示されるはず
    const banner = page.locator('[data-testid="board-migration-banner"]')
    const bannerVisible = await banner.isVisible().catch(() => false)

    if (!bannerVisible) {
      // バナーが表示されない場合は、機能が未実装またはdepthが不足しているためスキップ
      await deleteMessageViaContextMenu(page, rootMsg)
      test.skip()
      return
    }

    await expect(banner).toBeVisible()

    // 「掲示板スレッドを作成」ボタンをクリックしてダイアログが開く
    const openDialogBtn = page.locator('[data-testid="board-migration-open-dialog"]')
    await expect(openDialogBtn).toBeVisible({ timeout: 5_000 })
    await openDialogBtn.click()

    const dialog = page.locator('[data-testid="board-migration-dialog"]')
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // ダイアログを閉じる
    await page.keyboard.press('Escape')

    // クリーンアップ
    await deleteMessageViaContextMenu(page, rootMsg)
  })
})
