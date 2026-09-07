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

/**
 * ShiftScheduleResponse の雛形。
 *
 * BE は `content` / `period` / `status` / `audit` にネストして返す（Wave 2-C-B / PR #3019）。
 * 呼び出し側の可読性のため overrides はフラットなキーで受け、ここでネスト形へ畳む。
 */
export function buildSchedule(
  overrides: { title?: string; status?: string; startDate?: string; endDate?: string } = {},
) {
  return {
    id: SCHEDULE_ID,
    teamId: TEAM_ID,
    content: {
      title: overrides.title ?? 'E2Eテスト用シフトスケジュール',
      periodType: 'MONTHLY',
      note: null,
    },
    period: {
      startDate: overrides.startDate ?? '2026-05-01',
      endDate: overrides.endDate ?? '2026-05-31',
      requestDeadline: '2026-04-25',
    },
    status: {
      status: overrides.status ?? 'ADJUSTING',
      publishedAt: null,
      publishedBy: null,
    },
    audit: {
      createdBy: ADMIN_USER_ID,
      createdAt: '2026-04-20T00:00:00Z',
      updatedAt: '2026-04-20T00:00:00Z',
    },
  }
}

/**
 * ShiftSlotResponse の雛形。
 *
 * BE は日時を `time`、ポジションを `position` にネストして返し、
 * `assignedUserIds` / `assignmentMasked` / `note` はトップレベルのまま（Wave 2-C-B / PR #3019）。
 */
export function buildSlot(
  id: number,
  overrides: {
    slotDate?: string
    startTime?: string
    endTime?: string
    positionId?: number | null
    positionName?: string | null
    requiredCount?: number
    assignedUserIds?: number[]
    assignmentMasked?: boolean
  } = {},
) {
  return {
    id,
    scheduleId: SCHEDULE_ID,
    time: {
      slotDate: overrides.slotDate ?? '2026-05-10',
      startTime: overrides.startTime ?? '09:00:00',
      endTime: overrides.endTime ?? '17:00:00',
    },
    position: {
      positionId: overrides.positionId ?? null,
      positionName: overrides.positionName ?? null,
      requiredCount: overrides.requiredCount ?? 2,
    },
    assignedUserIds: overrides.assignedUserIds ?? ([] as number[]),
    assignmentMasked: overrides.assignmentMasked ?? false,
    note: null,
    version: 0,
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
/**
 * ChangeRequestResponse の雛形。
 *
 * BE は `requestInfo` / `reviewInfo` / `timing` にネストして返す（Wave 2-C-B / PR #3019）。
 * 呼び出し側の可読性のため overrides はフラットなキーで受け、ここでネスト形へ畳む。
 */
export function buildChangeRequest(
  overrides: {
    requestType?: string
    status?: string
    requestedBy?: number
    reason?: string
    reviewerId?: number | null
    reviewComment?: string | null
  } = {},
) {
  return {
    id: CHANGE_REQUEST_ID,
    scheduleId: SCHEDULE_ID,
    slotId: SLOT_ID_1,
    requestInfo: {
      requestType: overrides.requestType ?? 'PRE_CONFIRM_EDIT',
      reason: overrides.reason ?? 'E2Eテスト用の変更依頼です',
      requestedBy: overrides.requestedBy ?? MEMBER_USER_ID,
    },
    reviewInfo: {
      status: overrides.status ?? 'OPEN',
      reviewerId: overrides.reviewerId ?? null,
      reviewComment: overrides.reviewComment ?? null,
      reviewedAt: null,
    },
    timing: {
      expiresAt: '2026-05-01T00:00:00Z',
      createdAt: '2026-04-25T10:00:00Z',
    },
  }
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
