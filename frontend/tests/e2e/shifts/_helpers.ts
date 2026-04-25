import type { Page } from '@playwright/test'

/**
 * F03.5 シフト管理 Phase 2 E2E 共通モック・ヘルパー。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）</li>
 *   <li>F13.1（_helpers.ts）の構成を踏襲</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>Backend DTO（ShiftScheduleResponse / AssignmentRun / ChangeRequest）に準拠</li>
 * </ul>
 *
 * <p>認証は各 spec の {@code beforeEach} で {@code setupAdminAuth} を呼び、
 * localStorage に accessToken / currentUser を注入する方式（F13.1 と同じ）。</p>
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const TEAM_ID = 1
export const SCHEDULE_ID = 100
export const ADMIN_USER_ID = 1
export const MEMBER_USER_ID = 2
export const MEMBER2_USER_ID = 3

export const SLOT_ID_1 = 501
export const SLOT_ID_2 = 502
export const SLOT_ID_3 = 503

export const RUN_ID = 701
export const CHANGE_REQUEST_ID = 801

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/**
 * 管理者（ADMIN ロール）としてログイン済み状態をシミュレート。
 * localStorage に accessToken / currentUser を書き込む。
 */
export async function setupAdminAuth(page: Page): Promise<void> {
  await page.addInitScript((userId) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: userId,
        email: 'e2e-admin@example.com',
        displayName: 'e2e_admin',
        profileImageUrl: null,
        role: 'ADMIN',
      }),
    )
  }, ADMIN_USER_ID)
}

/**
 * 一般メンバー（MEMBER ロール）としてログイン済み状態をシミュレート。
 */
export async function setupMemberAuth(page: Page): Promise<void> {
  await page.addInitScript((userId) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: userId,
        email: 'e2e-member@example.com',
        displayName: 'e2e_member',
        profileImageUrl: null,
        role: 'MEMBER',
      }),
    )
  }, MEMBER_USER_ID)
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

/** ShiftScheduleResponse の雛形。 */
export function buildSchedule(overrides: Record<string, unknown> = {}) {
  return {
    id: SCHEDULE_ID,
    teamId: TEAM_ID,
    title: 'E2Eテスト用シフトスケジュール',
    periodType: 'MONTHLY',
    startDate: '2026-05-01',
    endDate: '2026-05-31',
    status: 'ADJUSTING',
    requestDeadline: '2026-04-25',
    note: null,
    createdBy: ADMIN_USER_ID,
    publishedAt: null,
    publishedBy: null,
    createdAt: '2026-04-20T00:00:00Z',
    updatedAt: '2026-04-20T00:00:00Z',
    ...overrides,
  }
}

/** ShiftSlotResponse の雛形（バックエンド DTO 準拠: assignedUserIds は number[]）。 */
export function buildSlot(id: number, overrides: Record<string, unknown> = {}) {
  return {
    id,
    scheduleId: SCHEDULE_ID,
    slotDate: '2026-05-10',
    startTime: '09:00:00',
    endTime: '17:00:00',
    positionId: null,
    positionName: null,
    requiredCount: 2,
    assignedUserIds: [] as number[],
    note: null,
    version: 0,
    ...overrides,
  }
}

/** AssignmentRun の雛形（SUCCEEDED 状態）。 */
export function buildAssignmentRun(overrides: Record<string, unknown> = {}) {
  return {
    id: RUN_ID,
    scheduleId: SCHEDULE_ID,
    strategy: 'GREEDY_V1',
    status: 'SUCCEEDED',
    triggeredBy: ADMIN_USER_ID,
    slotsTotal: 3,
    slotsFilled: 2,
    warnings: [],
    parameters: {
      preferenceWeight: 1.0,
      fairnessWeight: 0.5,
      respectWorkConstraints: true,
      overwriteExisting: false,
    },
    errorMessage: null,
    visualReviewConfirmedBy: null,
    visualReviewConfirmedAt: null,
    visualReviewNote: null,
    startedAt: '2026-04-25T10:00:00Z',
    completedAt: '2026-04-25T10:00:05Z',
    assignments: [
      { id: 9001, slotId: SLOT_ID_1, userId: MEMBER_USER_ID, status: 'PROPOSED', score: 0.9 },
      { id: 9002, slotId: SLOT_ID_2, userId: MEMBER2_USER_ID, status: 'PROPOSED', score: 0.8 },
    ],
    ...overrides,
  }
}

/** ChangeRequest の雛形。 */
export function buildChangeRequest(overrides: Record<string, unknown> = {}) {
  return {
    id: CHANGE_REQUEST_ID,
    scheduleId: SCHEDULE_ID,
    slotId: SLOT_ID_1,
    requestType: 'PRE_CONFIRM_EDIT',
    status: 'OPEN',
    requestedBy: MEMBER_USER_ID,
    reason: 'E2Eテスト用の変更依頼です',
    reviewerId: null,
    reviewComment: null,
    reviewedAt: null,
    expiresAt: '2026-05-01T00:00:00Z',
    createdAt: '2026-04-25T10:00:00Z',
    ...overrides,
  }
}

/** チームメンバー一覧のモック。 */
export async function mockTeamMembers(page: Page): Promise<void> {
  await page.route(`**/api/v1/teams/${TEAM_ID}/members**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            userId: ADMIN_USER_ID,
            displayName: 'e2e_admin',
            avatarUrl: null,
            role: 'ADMIN',
          },
          {
            userId: MEMBER_USER_ID,
            displayName: 'e2e_member',
            avatarUrl: null,
            role: 'MEMBER',
          },
          {
            userId: MEMBER2_USER_ID,
            displayName: 'e2e_member2',
            avatarUrl: null,
            role: 'MEMBER',
          },
        ],
      }),
    })
  })
}

/** チームメンバー一覧 API のモック（board.vue の loadMembers() が呼ぶ）。 */
export async function mockTeamMembersApi(page: Page): Promise<void> {
  await page.route(`**/api/v1/teams/${TEAM_ID}/members**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            userId: ADMIN_USER_ID,
            displayName: 'e2e_admin',
            avatarUrl: null,
            roleName: 'ADMIN',
            joinedAt: '2026-04-01T00:00:00Z',
          },
          {
            userId: MEMBER_USER_ID,
            displayName: 'e2e_member',
            avatarUrl: null,
            roleName: 'MEMBER',
            joinedAt: '2026-04-01T00:00:00Z',
          },
          {
            userId: MEMBER2_USER_ID,
            displayName: 'e2e_member2',
            avatarUrl: null,
            roleName: 'MEMBER',
            joinedAt: '2026-04-01T00:00:00Z',
          },
        ],
        meta: { page: 0, size: 200, totalElements: 3, totalPages: 1 },
      }),
    })
  })
}

/** シフトスケジュール取得モック。 */
export async function mockSchedule(
  page: Page,
  schedule: ReturnType<typeof buildSchedule>,
): Promise<void> {
  await page.route(`**/api/v1/shifts/schedules/${SCHEDULE_ID}`, async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: schedule }),
      })
    } else {
      await route.continue()
    }
  })
}

/** シフトスロット一覧取得モック。 */
export async function mockSlots(
  page: Page,
  slots: ReturnType<typeof buildSlot>[],
): Promise<void> {
  await page.route(`**/api/v1/shifts/schedules/${SCHEDULE_ID}/slots**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: slots }),
    })
  })
}

/** 自動割当実行履歴モック。 */
export async function mockAssignmentRuns(
  page: Page,
  runs: ReturnType<typeof buildAssignmentRun>[],
): Promise<void> {
  await page.route(`**/api/v1/shifts/schedules/${SCHEDULE_ID}/assignment-runs**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: runs }),
    })
  })
}

/** 変更依頼一覧モック。 */
export async function mockChangeRequests(
  page: Page,
  requests: ReturnType<typeof buildChangeRequest>[],
): Promise<void> {
  await page.route(`**/api/v1/shifts/schedules/change-requests**`, async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: requests }),
      })
    } else {
      await route.continue()
    }
  })
}
