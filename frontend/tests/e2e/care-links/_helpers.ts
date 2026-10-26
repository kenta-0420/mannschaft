import type { Page } from '@playwright/test'

/**
 * F03.12 ケアリンク管理 Phase 6 E2E 共通モック・ヘルパー。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）</li>
 *   <li>F03.5 shifts/_helpers.ts の構成を踏襲</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>Backend DTO（CareLinkResponse / CareLinkInvitationResponse）に準拠</li>
 * </ul>
 *
 * <p>認証は各 spec の {@code beforeEach} で {@code setupAuth} を呼び、
 * localStorage に accessToken / currentUser を注入する方式（shifts/_helpers.ts と同じ）。</p>
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const WATCHER_USER_ID = 1
export const RECIPIENT_USER_ID = 2
export const CARE_LINK_ID = 100
export const INVITE_TOKEN = 'test-invite-token-abc123'

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/**
 * 指定ユーザー・ロールとしてログイン済み状態をシミュレート。
 * localStorage に accessToken / currentUser を書き込む。
 */
export async function setupAuth(
  page: Page,
  userId: number = WATCHER_USER_ID,
  role: string = 'MEMBER',
): Promise<void> {
  await page.addInitScript(
    ({ id, roleValue }: { id: number; roleValue: string }) => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
          role: roleValue,
        }),
      )
    },
    { id: userId, roleValue: role },
  )
}

// ---------------------------------------------------------------------------
// 共通 catch-all（401/500 防止のため空 data を返す）
// ---------------------------------------------------------------------------

/**
 * すべての `/api/v1/**` を空 data で fulfill する catch-all。
 * 各 spec では本関数を最初に呼び、後から個別エンドポイントを上書きモックする
 * （Playwright の page.route は後勝ち）。
 */
export async function mockCatchAllApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

// ---------------------------------------------------------------------------
// fixture ビルダ（BE DTO 準拠）
// ---------------------------------------------------------------------------

/** CareLinkResponse の雛形。 */
export function buildCareLink(overrides: Record<string, unknown> = {}) {
  return {
    id: CARE_LINK_ID,
    careRecipientUserId: RECIPIENT_USER_ID,
    careRecipientDisplayName: 'テスト太郎',
    watcherUserId: WATCHER_USER_ID,
    watcherDisplayName: 'テスト花子',
    careCategory: 'MINOR',
    relationship: 'PARENT',
    isPrimary: true,
    status: 'ACTIVE',
    invitedBy: 'WATCHER',
    confirmedAt: '2026-04-01T00:00:00Z',
    notifyOnRsvp: true,
    notifyOnCheckin: true,
    notifyOnCheckout: true,
    notifyOnAbsentAlert: true,
    notifyOnDismissal: true,
    createdAt: '2026-04-01T00:00:00Z',
    ...overrides,
  }
}

/** CareLinkInvitationResponse の雛形。 */
export function buildInvitationResponse(overrides: Record<string, unknown> = {}) {
  return {
    token: INVITE_TOKEN,
    inviterDisplayName: 'テスト花子',
    careCategory: 'MINOR',
    relationship: 'PARENT',
    expiresAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// API モック関数
// ---------------------------------------------------------------------------

/** GET /api/v1/me/care-links/watchers をモック。 */
export async function mockGetWatchers(
  page: Page,
  links: ReturnType<typeof buildCareLink>[],
): Promise<void> {
  await page.route('**/api/v1/me/care-links/watchers**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: links }),
    })
  })
}

/** GET /api/v1/me/care-links/recipients をモック。 */
export async function mockGetRecipients(
  page: Page,
  links: ReturnType<typeof buildCareLink>[],
): Promise<void> {
  await page.route('**/api/v1/me/care-links/recipients**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: links }),
    })
  })
}

/** GET /api/v1/me/care-links/invitations をモック（保留中一覧）。 */
export async function mockGetInvitations(
  page: Page,
  links: ReturnType<typeof buildCareLink>[],
): Promise<void> {
  await page.route('**/api/v1/me/care-links/invitations**', async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: links }),
      })
    } else {
      await route.continue()
    }
  })
}

/** DELETE /api/v1/me/care-links/{linkId} をモック（204 返す）。 */
export async function mockDeleteLink(page: Page, linkId: number): Promise<void> {
  await page.route(`**/api/v1/me/care-links/${linkId}`, async (route) => {
    const method = route.request().method()
    if (method === 'DELETE') {
      await route.fulfill({
        status: 204,
        contentType: 'application/json',
        body: '',
      })
    } else {
      await route.continue()
    }
  })
}

/** PATCH /api/v1/me/care-links/{linkId} をモック（通知設定更新）。 */
export async function mockUpdateSettings(
  page: Page,
  linkId: number,
  response: ReturnType<typeof buildCareLink>,
): Promise<void> {
  await page.route(`**/api/v1/me/care-links/${linkId}`, async (route) => {
    const method = route.request().method()
    if (method === 'PATCH') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: response }),
      })
    } else {
      await route.continue()
    }
  })
}

/** GET /api/v1/care-links/invitations/{token} をモック（招待情報取得）。 */
export async function mockGetInvitationByToken(
  page: Page,
  token: string,
  response: ReturnType<typeof buildInvitationResponse>,
): Promise<void> {
  await page.route(`**/api/v1/care-links/invitations/${token}`, async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: response }),
      })
    } else {
      await route.continue()
    }
  })
}

/** POST /api/v1/care-links/invitations/{token}/accept をモック（200 返す）。 */
export async function mockAcceptInvitation(page: Page, token: string): Promise<void> {
  await page.route(`**/api/v1/care-links/invitations/${token}/accept`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: buildCareLink({ status: 'ACTIVE' }) }),
    })
  })
}

/** POST /api/v1/care-links/invitations/{token}/reject をモック（204 返す）。 */
export async function mockRejectInvitation(page: Page, token: string): Promise<void> {
  await page.route(`**/api/v1/care-links/invitations/${token}/reject`, async (route) => {
    await route.fulfill({
      status: 204,
      contentType: 'application/json',
      body: '',
    })
  })
}

/** POST /api/v1/me/care-links/invite-watcher をモック。 */
export async function mockInviteWatcher(
  page: Page,
  response: ReturnType<typeof buildCareLink>,
): Promise<void> {
  await page.route('**/api/v1/me/care-links/invite-watcher', async (route) => {
    const method = route.request().method()
    if (method === 'POST') {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: response }),
      })
    } else {
      await route.continue()
    }
  })
}
