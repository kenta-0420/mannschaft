import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F04.2.1 チャットマルチタブUI — E2E テスト
 *
 * タブの追加・切替・重複・上限10個ガード・URLクエリ自動オープン・
 * スマホ幅表示・キーボードショートカット（Alt+W）を検証する。
 *
 * API はすべて page.route() でモックし、バックエンド未起動環境でも実行可能。
 */

test.use({ storageState: 'tests/e2e/.auth/user.json' })

// ─── テスト用モックデータ ────────────────────────────────────────────

function buildChannel(id: number, name: string) {
  return {
    id,
    channelType: 'TEAM',
    team: { id: 10, name: 'テストチーム' },
    organization: null,
    name,
    iconUrl: null,
    description: null,
    isPrivate: false,
    isArchived: false,
    lastMessageAt: null,
    lastMessagePreview: null,
    unreadCount: 0,
    isMuted: false,
    isPinned: false,
    memberCount: 5,
    dmPartner: null,
    sourceType: null,
    sourceId: null,
  }
}

const MOCK_CHANNEL_1 = buildChannel(1, '全体連絡')
const MOCK_CHANNEL_2 = buildChannel(2, '開発チーム')
const MOCK_CHANNEL_3 = buildChannel(3, '雑談')

const MOCK_CHANNELS_LIST = {
  data: [MOCK_CHANNEL_1, MOCK_CHANNEL_2, MOCK_CHANNEL_3],
  meta: { nextCursor: null, hasMore: false },
}

const MOCK_MESSAGES = {
  data: [],
  meta: { nextCursor: null, hasMore: false },
}

const MOCK_CONTACTS = {
  data: [],
  meta: { nextCursor: null, total: 0 },
}

const MOCK_CONTACT_REQUESTS = { data: [] }

// ─── 共通セットアップ ───────────────────────────────────────────────

/**
 * チャットページが必要とする周辺 API をまとめてモックする。
 * チャンネル詳細（/api/v1/chat/channels/{id}）は個別に上書き可能。
 */
async function mockChatPageApis(page: Page): Promise<void> {
  // チャンネル一覧
  await page.route('**/api/v1/chat/channels**', async (route) => {
    const url = route.request().url()
    // チャンネル詳細 URL（/api/v1/chat/channels/1 など）はスキップして個別ルートに委ねる
    if (/\/api\/v1\/chat\/channels\/\d+$/.test(url)) {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CHANNELS_LIST),
    })
  })

  // メッセージ一覧（各チャンネル）
  await page.route('**/api/v1/chat/channels/*/messages**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_MESSAGES),
    })
  })

  // 既読マーク
  await page.route('**/api/v1/chat/channels/*/read', async (route) => {
    await route.fulfill({ status: 204, contentType: 'application/json', body: '' })
  })

  // 連絡先
  await page.route('**/api/v1/contacts**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CONTACTS),
    })
  })

  // 申請
  await page.route('**/api/v1/contact-requests/received**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CONTACT_REQUESTS),
    })
  })
  await page.route('**/api/v1/contact-requests/sent**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CONTACT_REQUESTS),
    })
  })

  // チャンネル詳細（個別）
  for (const ch of [MOCK_CHANNEL_1, MOCK_CHANNEL_2, MOCK_CHANNEL_3]) {
    await page.route(`**/api/v1/chat/channels/${ch.id}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: ch }),
      })
    })
  }
}

// ─── テストスイート ─────────────────────────────────────────────────

test.describe('CHAT-TAB-001〜006: チャットマルチタブUI（F04.2.1）', () => {
  /**
   * CHAT-TAB-001
   * チャンネル選択 → タブ追加確認 → 別チャンネルを追加 → タブ切替確認
   */
  test('CHAT-TAB-001: タブ追加・切替・タブ間状態保持', async ({ page }) => {
    await mockChatPageApis(page)

    // localStorage をリセット（前回テストのタブ残留を防止）
    await page.addInitScript(() => {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith('chatTabs:')) localStorage.removeItem(key)
      }
    })

    await page.goto('/chat')
    await waitForHydration(page)

    // チャンネル一覧が表示されるまで待つ（サイドバー）
    await expect(page.getByText('全体連絡')).toBeVisible({ timeout: 10_000 })

    // チャンネル1を選択してタブ追加
    await page.getByText('全体連絡').click()

    // タブバーにチャンネル名が表示されることを確認
    await expect(page.getByRole('tab', { name: /全体連絡/ })).toBeVisible({ timeout: 5_000 })

    // チャンネル2を選択して2つ目のタブ追加
    await page.getByText('開発チーム').click()
    await expect(page.getByRole('tab', { name: /開発チーム/ })).toBeVisible({ timeout: 5_000 })

    // 2つのタブが存在することを確認
    const tabs = page.getByRole('tab')
    await expect(tabs).toHaveCount(2, { timeout: 5_000 })

    // 最初のタブをクリックして切り替え確認
    await page.getByRole('tab', { name: /全体連絡/ }).click()
    // アクティブタブが切り替わっていることを確認（aria-selected）
    await expect(page.getByRole('tab', { name: /全体連絡/ })).toHaveAttribute(
      'aria-selected',
      'true',
      { timeout: 3_000 },
    )
    await expect(page.getByRole('tab', { name: /開発チーム/ })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  /**
   * CHAT-TAB-002
   * 同一チャンネルを2回開いて2つのタブが存在することを確認（VSCode 方式）
   */
  test('CHAT-TAB-002: 重複タブを開ける（同一チャンネル2回）', async ({ page }) => {
    await mockChatPageApis(page)

    await page.addInitScript(() => {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith('chatTabs:')) localStorage.removeItem(key)
      }
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await expect(page.getByText('全体連絡')).toBeVisible({ timeout: 10_000 })

    // 同じチャンネルを2回クリック
    await page.getByText('全体連絡').click()
    await expect(page.getByRole('tab', { name: /全体連絡/ }).first()).toBeVisible({
      timeout: 5_000,
    })

    // 2回目のクリック（+ ボタンからも可能だが、サイドバーから再クリックが最も簡単）
    await page.getByText('全体連絡').click()

    // 2つのタブが存在することを確認
    const tabs = page.getByRole('tab', { name: /全体連絡/ })
    await expect(tabs).toHaveCount(2, { timeout: 5_000 })
  })

  /**
   * CHAT-TAB-003
   * 10個タブを開いた後、+ ボタンが disabled になること
   */
  test('CHAT-TAB-003: 上限10個ガード — + ボタンが disabled になる', async ({ page }) => {
    await mockChatPageApis(page)

    // 10個分のチャンネルモックを追加
    for (let i = 10; i <= 15; i++) {
      const ch = buildChannel(i, `チャンネル${i}`)
      await page.route(`**/api/v1/chat/channels/${i}`, async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ch }),
        })
      })
    }

    // 10個タブをlocalStorageに事前セット（UI操作より高速）
    const tenTabs = Array.from({ length: 10 }, (_, i) => ({
      id: `tab-id-${i + 1}`,
      channelId: i + 1,
    }))
    const tenChannels = Array.from({ length: 10 }, (_, i) => buildChannel(i + 1, `チャンネル${i + 1}`))

    // チャンネル1〜10のAPIモック
    for (let i = 1; i <= 9; i++) {
      const ch = buildChannel(i, `チャンネル${i}`)
      await page.route(`**/api/v1/chat/channels/${i}`, async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ch }),
        })
      })
    }

    await page.addInitScript((data: { tabs: { id: string; channelId: number }[]; activeTabId: string | null }) => {
      // 直接 localStorage にタブデータをセット（ユーザーID=1 を想定）
      const key = 'chatTabs:1'
      localStorage.setItem(key, JSON.stringify(data))
    }, {
      tabs: tenTabs,
      activeTabId: 'tab-id-1',
    })

    // restore() でAPIが呼ばれるのでチャンネル詳細をモック
    for (const ch of tenChannels) {
      await page.route(`**/api/v1/chat/channels/${ch.id}`, async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: ch }),
        })
      })
    }

    await page.goto('/chat')
    await waitForHydration(page)

    // タブが10個表示されていることを確認
    await expect(page.getByRole('tab')).toHaveCount(10, { timeout: 10_000 })

    // + ボタンを aria-disabled 属性で探す
    await expect(page.locator('button[aria-disabled="true"]')).toBeVisible({ timeout: 5_000 })
  })

  /**
   * CHAT-TAB-004
   * /chat?channel=1 に遷移してタブが自動追加されること（URLクエリ自動オープン §3.8）
   */
  test('CHAT-TAB-004: URL クエリ ?channel={id} からの自動オープン', async ({ page }) => {
    await mockChatPageApis(page)

    await page.addInitScript(() => {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith('chatTabs:')) localStorage.removeItem(key)
      }
    })

    // チャンネルID=1 のAPIモック
    await page.route('**/api/v1/chat/channels/1', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_CHANNEL_1 }),
      })
    })

    // ?channel=1 クエリ付きで遷移
    await page.goto('/chat?channel=1')
    await waitForHydration(page)

    // タブが自動追加されることを確認
    await expect(page.getByRole('tab', { name: /全体連絡/ })).toBeVisible({ timeout: 10_000 })

    // URLクエリが消費されてクリアされることを確認
    await expect(page).toHaveURL('/chat', { timeout: 5_000 })
  })

  /**
   * CHAT-TAB-005
   * スマホ幅（375px）でタブバー + ボタンが表示されること
   */
  test('CHAT-TAB-005: スマホ幅（375px）でタブバー表示確認', async ({ browser }) => {
    const context = await browser.newContext({
      viewport: { width: 375, height: 812 },
      storageState: 'tests/e2e/.auth/user.json',
    })
    const page = await context.newPage()

    await mockChatPageApis(page)

    await page.addInitScript(() => {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith('chatTabs:')) localStorage.removeItem(key)
      }
    })

    await page.goto('/chat')
    await waitForHydration(page)

    // スマホ幅でも + ボタン（タブ追加）が表示されることを確認
    // button[aria-label] の中でタブ追加用のもの（aria-disabled が存在するもの）を探す
    await expect(page.locator('[role="tablist"]')).toBeVisible({ timeout: 10_000 })

    // + ボタンが visible であることを確認（スマホでも非表示にならない仕様）
    const addButton = page.locator('[role="tablist"] button[aria-disabled]')
    await expect(addButton).toBeVisible({ timeout: 5_000 })

    await context.close()
  })

  /**
   * CHAT-TAB-006
   * Alt+W でアクティブタブが閉じること（キーボードショートカット §6.2）
   */
  test('CHAT-TAB-006: キーボードショートカット Alt+W でアクティブタブを閉じる', async ({
    page,
  }) => {
    await mockChatPageApis(page)

    await page.addInitScript(() => {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith('chatTabs:')) localStorage.removeItem(key)
      }
    })

    await page.goto('/chat')
    await waitForHydration(page)

    // チャンネルを選択してタブを開く
    await expect(page.getByText('全体連絡')).toBeVisible({ timeout: 10_000 })
    await page.getByText('全体連絡').click()
    await expect(page.getByRole('tab', { name: /全体連絡/ })).toBeVisible({ timeout: 5_000 })

    // 2つ目のタブも開く
    await page.getByText('開発チーム').click()
    await expect(page.getByRole('tab')).toHaveCount(2, { timeout: 5_000 })

    // Alt+W でアクティブタブ（開発チーム）を閉じる
    await page.keyboard.press('Alt+w')

    // タブが1個になることを確認
    await expect(page.getByRole('tab')).toHaveCount(1, { timeout: 5_000 })
    await expect(page.getByRole('tab', { name: /全体連絡/ })).toBeVisible()
  })
})
