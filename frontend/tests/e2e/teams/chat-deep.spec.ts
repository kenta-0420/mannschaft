import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput } from '../helpers/form'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * チャット送信操作の深掘りテスト。
 * チャットAPIは team-scoped ではなく /api/v1/chat/* に存在するため、
 * 個別にチャンネル一覧・メッセージ一覧・送信エンドポイントをモックする必要がある。
 */

const MOCK_CHANNEL_ID = 42

const MOCK_CHANNEL = {
  id: MOCK_CHANNEL_ID,
  channelType: 'TEAM',
  team: { id: TEAM_ID, name: 'テストチーム' },
  organization: null,
  name: '雑談部屋',
  iconUrl: null,
  description: null,
  isPrivate: false,
  isArchived: false,
  lastMessageAt: '2026-04-07T00:00:00Z',
  lastMessagePreview: 'こんにちは',
  unreadCount: 0,
  isMuted: false,
  isPinned: false,
  memberCount: 5,
  dmPartner: null,
  sourceType: null,
  sourceId: null,
}

const MOCK_MESSAGES = [
  {
    id: 1,
    channelId: MOCK_CHANNEL_ID,
    sender: { id: 10, displayName: 'アリス', avatarUrl: null },
    parentId: null,
    body: '最初のメッセージです',
    isEdited: false,
    isSystem: false,
    isPinned: false,
    replyCount: 0,
    reactionCount: 0,
    reactionSummary: {},
    myReactions: [],
    attachments: [],
    isBookmarked: false,
    forwardedFrom: null,
    isDeleted: false,
    createdAt: '2026-04-07T00:00:00Z',
    updatedAt: '2026-04-07T00:00:00Z',
  },
  {
    id: 2,
    channelId: MOCK_CHANNEL_ID,
    sender: { id: 11, displayName: 'ボブ', avatarUrl: null },
    parentId: null,
    body: 'こんにちは！',
    isEdited: false,
    isSystem: false,
    isPinned: false,
    replyCount: 0,
    reactionCount: 0,
    reactionSummary: {},
    myReactions: [],
    attachments: [],
    isBookmarked: false,
    forwardedFrom: null,
    isDeleted: false,
    createdAt: '2026-04-07T00:01:00Z',
    updatedAt: '2026-04-07T00:01:00Z',
  },
]

/** チャンネル一覧 + メッセージ取得 + 既読 を共通でモックする */
async function mockChatBase(page: Page) {
  await page.route('**/api/v1/chat/channels?**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [MOCK_CHANNEL],
          meta: { nextCursor: null, hasMore: false },
        }),
      })
    } else {
      await route.fallback()
    }
  })
  await page.route(
    `**/api/v1/chat/channels/${MOCK_CHANNEL_ID}/messages?**`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: MOCK_MESSAGES,
            meta: { nextCursor: null, hasMore: false },
          }),
        })
      } else {
        await route.fallback()
      }
    },
  )
  // 既読マーキング（POST）
  await page.route(`**/api/v1/chat/channels/${MOCK_CHANNEL_ID}/read`, async (route) => {
    await route.fulfill({ status: 204, body: '' })
  })
  // グローバルAPIをモック: バックエンドが起動していない場合にエラーレポートダイアログが
  // 表示されてボタンクリックを遮らないように空レスポンスで返す
  await page.route('**/api/v1/active-incidents', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ incidents: [] }),
    })
  })
  await page.route('**/api/v1/notifications/unread-count', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { total: 0 } }),
    })
  })
  await page.route('**/api/v1/mentions', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

/** チャンネルを選択してメッセージパネルを表示状態にする */
async function openChannel(page: Page) {
  await page.goto(`/teams/${TEAM_ID}/chat`)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })

  // ChatChannelList の中からモックチャンネルをクリック
  await page.getByText('雑談部屋', { exact: false }).first().click()

  // メッセージ入力欄が表示されるまで待つ
  await expect(page.getByPlaceholder('メッセージを入力...')).toBeVisible({ timeout: 10_000 })
}

test.describe('TEAM-DEEP-chat: チャット送信操作の深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    await mockChatBase(page)
  })

  test('TEAM-DEEP-chat-001: チャンネル選択後にメッセージ入力欄と送信ボタンが表示される', async ({
    page,
  }) => {
    await openChannel(page)
    const input = page.getByPlaceholder('メッセージを入力...')
    await expect(input).toBeVisible()

    // 送信ボタン（data-testid="chat-send-btn"）
    const sendBtn = page.getByTestId('chat-send-btn')
    await expect(sendBtn).toBeVisible()
  })

  test('TEAM-DEEP-chat-002: メッセージ未入力時は送信ボタンが disabled', async ({ page }) => {
    await openChannel(page)
    const sendBtn = page.getByTestId('chat-send-btn')
    // body が空のため :disabled="!body.trim() || disabled" で無効化
    await expect(sendBtn).toBeDisabled()
  })

  test('TEAM-DEEP-chat-003: メッセージを入力すると送信ボタンが enabled になる', async ({
    page,
  }) => {
    await openChannel(page)
    const input = page.getByPlaceholder('メッセージを入力...')
    const sendBtn = page.getByTestId('chat-send-btn')

    await expect(sendBtn).toBeDisabled()
    await fillInput(input, 'テストメッセージ')
    await expect(sendBtn).toBeEnabled()
  })

  test('TEAM-DEEP-chat-004: 送信ボタンクリックで POST API が呼ばれる', async ({ page }) => {
    let postBody: { body?: string } | null = null
    await page.route(
      `**/api/v1/chat/channels/${MOCK_CHANNEL_ID}/messages`,
      async (route) => {
        if (route.request().method() === 'POST') {
          postBody = route.request().postDataJSON() as { body?: string }
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              data: {
                ...MOCK_MESSAGES[0]!,
                id: 999,
                body: postBody.body,
                createdAt: '2026-04-07T00:05:00Z',
              },
            }),
          })
        } else {
          await route.fallback()
        }
      },
    )

    await openChannel(page)
    const input = page.getByPlaceholder('メッセージを入力...')
    await fillInput(input, '送信ボタンから送るメッセージ')

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/chat/channels/${MOCK_CHANNEL_ID}/messages`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    const sendBtn = page.getByTestId('chat-send-btn')
    await sendBtn.click()
    await respPromise

    expect(postBody).not.toBeNull()
    expect(postBody!.body).toBe('送信ボタンから送るメッセージ')
  })

  test('TEAM-DEEP-chat-005: Enter キー押下で送信される（Shift+Enter ではない）', async ({
    page,
  }) => {
    let postCalled = false
    await page.route(
      `**/api/v1/chat/channels/${MOCK_CHANNEL_ID}/messages`,
      async (route) => {
        if (route.request().method() === 'POST') {
          postCalled = true
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              data: { ...MOCK_MESSAGES[0], id: 1000, body: 'Enter送信' },
            }),
          })
        } else {
          await route.fallback()
        }
      },
    )

    await openChannel(page)
    const input = page.getByPlaceholder('メッセージを入力...')
    await fillInput(input, 'Enterキー送信テスト')

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/chat/channels/${MOCK_CHANNEL_ID}/messages`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await input.press('Enter')
    await respPromise
    expect(postCalled).toBe(true)
  })

  test('TEAM-DEEP-chat-006: モックされたメッセージ履歴がメッセージ一覧に表示される', async ({
    page,
  }) => {
    await openChannel(page)
    // モックメッセージの本文が表示されること
    await expect(page.getByText('最初のメッセージです')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('こんにちは！')).toBeVisible()
  })
})
