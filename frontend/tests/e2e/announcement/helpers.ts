/**
 * F02.8 ダッシュボード告知ウィザード Phase 3 — E2E 共通モックヘルパー（チームスコープ）
 *
 * page.route() でバックエンド API をモックする関数群を提供する。
 * spec ファイルが beforeEach でこれらを呼び出すことで、
 * バックエンドサーバーが起動していない状態でも E2E テストを実行できる。
 */

import type { Page } from '@playwright/test'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

/** テスト用チーム ID */
export const TEAM_ID = 1

/** テスト用組織 ID */
export const ORG_ID = 1

// ---------------------------------------------------------------------------
// モックレスポンスデータ定義
// ---------------------------------------------------------------------------

const MOCK_TEAM_RESPONSE = {
  data: {
    id: TEAM_ID,
    name: 'テストチーム',
    nickname1: 'テストチーム',
    nickname2: null,
    template: 'GENERAL',
    prefecture: '東京都',
    city: '渋谷区',
    visibility: 'PUBLIC',
    memberCount: 5,
    teamFriendCount: 0,
    supporterCount: 0,
    supporterEnabled: false,
    iconUrl: null,
    bannerUrl: null,
    description: 'テスト用チームです',
    nameKana: null,
  },
}

const MOCK_MEMBERSHIPS_RESPONSE = {
  data: {
    userId: 1,
    teamId: TEAM_ID,
    role: 'ADMIN',
    roleName: 'ADMIN',
    joinedAt: '2026-01-01T00:00:00+09:00',
  },
}

const MOCK_BROADCAST_RESPONSE = {
  data: {
    announcementFeedId: 101,
    channel: 'BULLETIN_THREAD',
    contentId: 55,
    contentUrl: `/teams/${TEAM_ID}/bulletin/threads/55`,
    targetRole: 'MEMBERS_ONLY',
    targetTeamIds: null,
    priority: 'NORMAL',
    createdAt: '2026-05-07T10:00:00+09:00',
  },
}

const MOCK_TEMPLATE_RESPONSE = {
  data: [
    {
      id: 1,
      name: '全メンバー告知',
      targetRole: 'MEMBERS_ONLY',
      targetTeamIds: null,
      preferredChannel: 'BULLETIN_THREAD',
      isDefault: true,
    },
  ],
}

const MOCK_ANNOUNCEMENT_FEED_RESPONSE = {
  data: [
    {
      id: 1,
      title: 'テストお知らせ',
      contentUrl: `/teams/${TEAM_ID}/bulletin/threads/1`,
      channel: 'BULLETIN_THREAD',
      targetRole: 'MEMBERS_ONLY',
      priority: 'NORMAL',
      isPinned: false,
      isRead: false,
      expiresAt: null,
      createdAt: '2026-05-07T09:00:00+09:00',
    },
  ],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
}

// ---------------------------------------------------------------------------
// API モック関数
// ---------------------------------------------------------------------------

/**
 * チームブロードキャスト API をモックする。
 * POST /api/v1/teams/{teamId}/broadcast → 201
 *
 * @param page Playwright Page オブジェクト
 * @param teamId チーム ID（省略時は TEAM_ID）
 * @param overrideResponseData レスポンスデータの上書き（任意）
 */
export async function mockBroadcastApi(
  page: Page,
  teamId = TEAM_ID,
  overrideResponseData?: Partial<typeof MOCK_BROADCAST_RESPONSE['data']>,
): Promise<void> {
  const responseData = {
    data: {
      ...MOCK_BROADCAST_RESPONSE.data,
      ...overrideResponseData,
    },
  }

  await page.route(`**/api/v1/teams/${teamId}/broadcast`, async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(responseData),
      })
    } else {
      await route.continue()
    }
  })
}

/**
 * テンプレート取得 API をモックする。
 * GET /api/v1/teams/{teamId}/announcement-templates → テンプレート1件
 *
 * @param page Playwright Page オブジェクト
 * @param teamId チーム ID（省略時は TEAM_ID）
 */
export async function mockTemplateApi(page: Page, teamId = TEAM_ID): Promise<void> {
  await page.route(`**/api/v1/teams/${teamId}/announcement-templates**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TEMPLATE_RESPONSE),
    })
  })
}

/**
 * お知らせフィード取得 API をモックする。
 * GET /api/v1/teams/{teamId}/announcements → 1件のお知らせ
 *
 * @param page Playwright Page オブジェクト
 * @param teamId チーム ID（省略時は TEAM_ID）
 */
export async function mockAnnouncementFeedApi(page: Page, teamId = TEAM_ID): Promise<void> {
  await page.route(`**/api/v1/teams/${teamId}/announcements**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_ANNOUNCEMENT_FEED_RESPONSE),
    })
  })
}

/**
 * チームダッシュボードページで必要な基本 API 群をまとめてモックする。
 *
 * 対象:
 * - GET /api/v1/teams/{teamId}  → チーム詳細
 * - GET /api/v1/teams/{teamId}/memberships/my  → ログインユーザーのメンバーシップ（ADMIN）
 * - GET /api/v1/teams/{teamId}/memberships/**  → メンバーシップ汎用
 * - GET /api/v1/teams/{teamId}/dashboard/**  → ダッシュボードウィジェット群（空レスポンス）
 * - GET /api/v1/users/me  → 現在ユーザー情報
 *
 * @param page Playwright Page オブジェクト
 * @param teamId チーム ID（省略時は TEAM_ID）
 */
export async function mockTeamApis(page: Page, teamId = TEAM_ID): Promise<void> {
  // チーム詳細
  await page.route(`**/api/v1/teams/${teamId}`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_TEAM_RESPONSE),
      })
    } else {
      await route.continue()
    }
  })

  // 自分のメンバーシップ（権限確認に使用）
  await page.route(`**/api/v1/teams/${teamId}/memberships/my**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_MEMBERSHIPS_RESPONSE),
    })
  })

  // メンバーシップ汎用
  await page.route(`**/api/v1/teams/${teamId}/memberships**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [MOCK_MEMBERSHIPS_RESPONSE.data], meta: {} }),
    })
  })

  // ダッシュボードウィジェット・フィード等（空レスポンスで 500 を防ぐ）
  await page.route(`**/api/v1/teams/${teamId}/dashboard/**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // お知らせフィード
  await mockAnnouncementFeedApi(page, teamId)

  // テンプレート（空で OK）
  await page.route(`**/api/v1/teams/${teamId}/announcement-templates**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // スコープダッシュボード全般（他のウィジェット API）
  await page.route(`**/api/v1/teams/${teamId}/**`, async (route) => {
    // 既にモック済みのルートは優先されるため、ここではフォールバックとして空を返す
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // ユーザー自身の情報
  await page.route('**/api/v1/users/me**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 1,
          email: 'admin@example.com',
          displayName: 'テスト管理者',
          profileImageUrl: null,
        },
      }),
    })
  })
}
