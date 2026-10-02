import { test, expect } from '@playwright/test'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'
import { gotoAuthed } from './helpers'

/**
 * モバイルUX根治戦役 — C隊向け受け入れ条件（AC-8/9/16）の red テスト。
 *
 * 対象:
 * - チームチャット（pages/teams/[slug]/chat.vue）: aside(w-64固定) + メインパネルが
 *   ビューポート幅に関わらず常時2ペイン描画（isDesktop 分岐が無い）。
 * - 個人チャットハブ（pages/chat/index.vue）: isDesktop(>=768px) 判定で
 *   aside(ChatChannelList) が `v-if="isDesktop"` により390pxでは描画されず、
 *   タブ0件時は「チャンネルを選択してください」の空状態のみが表示される
 *   （チャンネル一覧へアクセスする手段が無い）。
 *
 * 実在チャンネルのモックは ChatChannelResponse のネスト構造
 * （identity/meta/settings/audit/viewer）に厳密に合わせる。
 */

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

const MOCK_CHANNEL = {
  id: 101,
  identity: { channelType: 'TEAM_PUBLIC', teamId: TEAM_ID, organizationId: null },
  meta: { name: '全体連絡', iconKey: null, description: null },
  settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
  lastMessage: null,
  source: null,
  audit: { createdBy: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  memberCount: 5,
  dmPartner: null,
  viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: 'MEMBER' },
}

async function mockTeamChat(page: import('@playwright/test').Page) {
  await mockTeam(page)
  await mockTeamFeatureApis(page)
  await page.route('**/api/v1/chat/channels?**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [MOCK_CHANNEL] }) })
  })
  await page.route('**/api/v1/chat/channels/*/messages**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [], meta: { nextCursor: null, hasMore: false } }),
    })
  })
  // その他チャンネル配下API（既読・アクティブスレッド等）は汎用成功で吸収する
  await page.route('**/api/v1/chat/channels/**', async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [], meta: { nextCursor: null, hasMore: false } }) })
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: {} }) })
    }
  })
}

test.describe('MOBILE-CHAT: チャット 390px受け入れ条件', () => {
  test('MCH-01: 390pxでチームチャット初期表示は一覧のみ（一覧+メッセージの同時2ペインでない）', async ({ page }) => {
    await mockTeamChat(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}/chat`)
    await page.waitForTimeout(1500)

    const aside = page.locator('aside').first()
    await expect(aside, 'チャンネル一覧(aside)が見つからない').toBeVisible({ timeout: 5_000 })
    const asideBox = await aside.boundingBox()
    expect(asideBox, 'asideの座標が取得できない').not.toBeNull()

    // メッセージペイン（未選択時は「チャンネルを選択してください」の空状態）
    const emptyPane = page.getByText('チャンネルを選択してください')
    const emptyPaneVisible = await emptyPane.isVisible().catch(() => false)

    const listRatio = asideBox!.width / 390
    const singlePane = listRatio >= 0.8 || !emptyPaneVisible

    expect(
      singlePane,
      `390pxで一覧(幅比${Math.round(listRatio * 100)}%)とメッセージペイン(空状態可視=${emptyPaneVisible})が同時に半端に共存している（単一ペインに切り替わるべき）`,
    ).toBe(true)
  })

  test('MCH-02: チャンネルをタップするとメッセージビューに切替わり、composerが可視・画面内(幅>=300px)', async ({ page }) => {
    await mockTeamChat(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}/chat`)
    await page.waitForTimeout(1200)

    await page.locator(`[data-testid="chat-channel-${MOCK_CHANNEL.id}"]`).click()
    await page.waitForTimeout(1000)

    const composer = page.locator('textarea, [contenteditable="true"]').last()
    await expect(composer, 'composer(入力欄)が見つからない').toBeVisible({ timeout: 5_000 })
    const box = await composer.boundingBox()
    expect(box, 'composerの座標が取得できない').not.toBeNull()
    expect(box!.width, `composer幅(${box!.width})が300pxを下回っている`).toBeGreaterThanOrEqual(300)
    expect(
      box!.x + box!.width,
      `composer右端(${box!.x + box!.width})が画面幅390pxに収まっていない（横スクロールしないと入力できない）`,
    ).toBeLessThanOrEqual(390)
  })

  test('MCH-03: メッセージビューから一覧へ戻る導線が存在する', async ({ page }) => {
    await mockTeamChat(page)
    await gotoAuthed(page, `/teams/${TEAM_ID}/chat`)
    await page.waitForTimeout(1200)

    await page.locator(`[data-testid="chat-channel-${MOCK_CHANNEL.id}"]`).click()
    await page.waitForTimeout(1000)

    // チャット2ペイン領域（border-2 border-surface-400 のコンテナ）内に限定して探す。
    // ページ先頭の PageHeader(back-to既定=true) が持つ汎用「戻る」リンクは
    // チャンネル選択状態と無関係に常時存在するため、それを誤検知しないよう
    // コンテナ内スコープに絞る（AC-9本来の意図=メッセージビュー固有の戻る導線）。
    const chatContainer = page.locator('div.border-2.border-surface-400, div.dark\\:border-surface-500').first()
    const backToList = chatContainer.getByRole('button', { name: /一覧に戻る|チャンネル一覧/ })
    await expect(
      backToList,
      'メッセージビューから一覧へ戻るボタンが見つからない（チームチャットは常時2ペイン固定で単一ペイン遷移の概念が無く、ページ先頭のPageHeaderの汎用「戻る」とは別に、チャット領域固有の戻る導線が必要）',
    ).toBeVisible({ timeout: 5_000 })
  })

  test('MCH-04: /chat 初期表示（タブ0件）でチャンネル一覧が可視', async ({ page }) => {
    await page.route('**/api/v1/chat/channels?**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [MOCK_CHANNEL] }) })
    })
    await gotoAuthed(page, '/chat')
    await page.waitForTimeout(1500)

    const channelList = page.locator(`[data-testid="chat-channel-${MOCK_CHANNEL.id}"]`)
    await expect(
      channelList,
      '/chat 初期表示（タブ0件）でチャンネル一覧が見つからない（現状はisDesktop(>=768px)判定でasideがv-ifされ、モバイルでは「＋で開いてください」相当の空状態のみ）',
    ).toBeVisible({ timeout: 5_000 })
  })

  test('MCH-05: 768pxでは現行の2ペイン（aside w-64相当）を維持（AC-3/16）', async ({ page }) => {
    await page.route('**/api/v1/chat/channels?**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
    })
    await page.setViewportSize({ width: 768, height: 1024 })
    await gotoAuthed(page, '/chat')
    await page.waitForTimeout(1200)
    const asideAt768 = page.locator('aside').first()
    await expect(asideAt768, '768pxで個人チャットハブのasideが可視であるべき').toBeVisible({ timeout: 5_000 })

    // 390px: 同一ページのはずのaside二重マウント/単一ペイン切替を確認する（AC-16境界）。
    // 現状は teams/[slug]/chat.vue 側にこの分岐が無いため、チームチャットで検証する。
    await page.setViewportSize({ width: 390, height: 844 })
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    await page.route('**/api/v1/chat/channels/*/messages**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [], meta: { nextCursor: null, hasMore: false } }) })
    })
    await gotoAuthed(page, `/teams/${TEAM_ID}/chat`)
    await page.waitForTimeout(1200)
    const asideAt390 = page.locator('aside').first()
    await expect(
      asideAt390,
      '390pxでもチームチャットのaside(チャンネル一覧)が常時可視のまま（AC-16: モバイルでは単一ペインへ切り替わるべき）',
    ).not.toBeVisible({ timeout: 5_000 })
  })
})
