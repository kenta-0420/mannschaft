import type { Page } from '@playwright/test'

/**
 * F13.1 スキマバイト（求人マッチング）E2E 共通モック。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）</li>
 *   <li>F03.11（recruitment.spec.ts）の構成を踏襲</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>Backend DTO（{@code JobPostingResponse} / {@code JobApplicationResponse} /
 *       {@code JobContractResponse}）に完全準拠</li>
 * </ul>
 *
 * <p>認証は各 spec の {@code beforeEach} で {@code setupAuth} を呼び、
 * localStorage に accessToken / currentUser を注入する方式（F03.11 と同じ）。</p>
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const TEAM_ID = 1
export const REQUESTER_USER_ID = 10
export const WORKER_USER_ID = 59

export const JOB_ID_DRAFT = 2001
export const JOB_ID_OPEN = 2002
export const JOB_ID_OPEN_NO_APPS = 2003
export const JOB_ID_CLOSED = 2004
export const JOB_ID_CANCELLED = 2005

export const APPLICATION_ID_APPLIED = 3001
export const APPLICATION_ID_APPLIED_2 = 3002

export const CONTRACT_ID_MATCHED = 4001
export const CONTRACT_ID_REPORTED = 4002

// ---------------------------------------------------------------------------
// 日時ヘルパ（未来日時で Future バリデータを満たすため）
// ---------------------------------------------------------------------------

/** 現在から N 日後の ISO8601 文字列。 */
export function daysFromNow(days: number): string {
  const now = new Date()
  now.setDate(now.getDate() + days)
  return now.toISOString()
}

/** 固定の未来日時（各 fixture が同じ値を使いたいときに）。 */
export const FIXED_WORK_START_AT = daysFromNow(7)
export const FIXED_WORK_END_AT = daysFromNow(7)
export const FIXED_APPLICATION_DEADLINE_AT = daysFromNow(3)

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/**
 * Requester（求人投稿側）としてログイン済み状態をシミュレート。
 * localStorage に accessToken / currentUser を書き込む。
 * tests/e2e/.auth/user.json は .gitignore 対象のため、
 * storageState に依存せず addInitScript で直接設定する（F03.11 と同じ方針）。
 */
export async function setupRequesterAuth(page: Page): Promise<void> {
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
        email: 'e2e-requester@example.com',
        displayName: 'e2e_requester',
        profileImageUrl: null,
      }),
    )
  }, REQUESTER_USER_ID)
}

/**
 * Worker（応募側）としてログイン済み状態をシミュレート。
 */
export async function setupWorkerAuth(page: Page): Promise<void> {
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
        email: 'e2e-worker@example.com',
        displayName: 'e2e_worker',
        profileImageUrl: null,
      }),
    )
  }, WORKER_USER_ID)
}

// ---------------------------------------------------------------------------
// 共通 catch-all（401/500 防止のため空 data を返す）
// ---------------------------------------------------------------------------

/**
 * すべての `/api/v1/**` を空 data で fulfill する catch-all 。
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

/** {@code JobPostingResponse} の雛形。 */
export function buildJobPosting(overrides: Record<string, unknown> = {}) {
  return {
    id: JOB_ID_DRAFT,
    teamId: TEAM_ID,
    createdByUserId: REQUESTER_USER_ID,
    title: 'E2Eテスト用 求人タイトル',
    description: 'E2Eテスト用の業務内容説明。\n必要な持ち物等を記載する想定。',
    category: 'イベント',
    workLocationType: 'ONSITE',
    workAddress: '東京都渋谷区テスト1-2-3',
    workStartAt: FIXED_WORK_START_AT,
    workEndAt: FIXED_WORK_END_AT,
    rewardType: 'LUMP_SUM',
    baseRewardJpy: 5000,
    capacity: 1,
    applicationDeadlineAt: FIXED_APPLICATION_DEADLINE_AT,
    visibilityScope: 'TEAM_MEMBERS',
    status: 'DRAFT',
    publishAt: null,
    createdAt: '2026-04-20T00:00:00Z',
    updatedAt: '2026-04-20T00:00:00Z',
    ...overrides,
  }
}

/** {@code JobPostingSummaryResponse} の雛形（一覧用）。 */
export function buildJobPostingSummary(overrides: Record<string, unknown> = {}) {
  return {
    id: JOB_ID_OPEN,
    teamId: TEAM_ID,
    title: 'E2Eテスト用 求人タイトル',
    category: 'イベント',
    workLocationType: 'ONSITE',
    workStartAt: FIXED_WORK_START_AT,
    workEndAt: FIXED_WORK_END_AT,
    rewardType: 'LUMP_SUM',
    baseRewardJpy: 5000,
    capacity: 1,
    applicationDeadlineAt: FIXED_APPLICATION_DEADLINE_AT,
    visibilityScope: 'TEAM_MEMBERS',
    status: 'OPEN',
    publishAt: '2026-04-20T01:00:00Z',
    ...overrides,
  }
}

/** {@code JobApplicationResponse} の雛形。 */
export function buildJobApplication(overrides: Record<string, unknown> = {}) {
  return {
    id: APPLICATION_ID_APPLIED,
    jobPostingId: JOB_ID_OPEN,
    applicantUserId: WORKER_USER_ID,
    selfPr: '全力で頑張ります！',
    status: 'APPLIED',
    appliedAt: '2026-04-20T10:00:00Z',
    decidedAt: null,
    decidedByUserId: null,
    createdAt: '2026-04-20T10:00:00Z',
    updatedAt: '2026-04-20T10:00:00Z',
    ...overrides,
  }
}

/** {@code JobContractResponse} の雛形（MATCHED）。 */
export function buildJobContract(overrides: Record<string, unknown> = {}) {
  return {
    id: CONTRACT_ID_MATCHED,
    jobPostingId: JOB_ID_OPEN,
    jobApplicationId: APPLICATION_ID_APPLIED,
    requesterUserId: REQUESTER_USER_ID,
    workerUserId: WORKER_USER_ID,
    chatRoomId: null,
    baseRewardJpy: 5000,
    workStartAt: FIXED_WORK_START_AT,
    workEndAt: FIXED_WORK_END_AT,
    status: 'MATCHED',
    matchedAt: '2026-04-20T11:00:00Z',
    completionReportedAt: null,
    completionApprovedAt: null,
    cancelledAt: null,
    rejectionCount: 0,
    lastRejectionReason: null,
    createdAt: '2026-04-20T11:00:00Z',
    updatedAt: '2026-04-20T11:00:00Z',
    ...overrides,
  }
}

/** {@code FeePreviewResponse} の雛形。基本報酬 5000 円のプレビュー。 */
export function buildFeePreview(baseRewardJpy: number) {
  // 発注者: base + (base * 10% + 100) + その消費税(10%)
  // 受注者: base - (base * 2% + 100)
  const requesterFee = Math.floor(baseRewardJpy * 0.1) + 100
  const requesterFeeTax = Math.floor(requesterFee * 0.1)
  const workerFee = Math.floor(baseRewardJpy * 0.02) + 100
  return {
    baseRewardJpy,
    requesterFeeJpy: requesterFee,
    requesterFeeTaxJpy: requesterFeeTax,
    requesterTotalJpy: baseRewardJpy + requesterFee + requesterFeeTax,
    workerFeeJpy: workerFee,
    workerReceiptJpy: baseRewardJpy - workerFee,
  }
}

/**
 * F13.1 Phase 13.1.2 QR トークンレスポンス雛形。
 * BE: QrTokenResponse。shortCode は 6 桁英数・紛らわしい文字除外。
 */
export function buildQrTokenResponse(overrides: Record<string, unknown> = {}) {
  const issuedAtMs = Date.now()
  const ttlMs = 60_000
  return {
    token: 'eyJhbGciOiJIUzM4NCJ9.e2V4YW1wbGVfanddGh9.placeholder',
    shortCode: 'ABC123',
    type: 'IN',
    issuedAt: new Date(issuedAtMs).toISOString(),
    expiresAt: new Date(issuedAtMs + ttlMs).toISOString(),
    kid: 'test-kid',
    ...overrides,
  }
}

/**
 * F13.1 Phase 13.1.2 CheckInResponse 雛形。
 * BE: CheckInResponse（POST /api/v1/jobs/check-ins）。
 */
export function buildCheckInResponse(overrides: Record<string, unknown> = {}) {
  return {
    checkInId: 9001,
    contractId: CONTRACT_ID_MATCHED,
    type: 'IN',
    newStatus: 'IN_PROGRESS',
    workDurationMinutes: null,
    geoAnomaly: false,
    ...overrides,
  }
}

/** PagedResponse 形式で包む。 */
export function pagedOf<T>(items: T[]) {
  return {
    data: items,
    meta: {
      total: items.length,
      page: 0,
      size: 20,
      totalPages: items.length === 0 ? 0 : 1,
    },
  }
}

// ---------------------------------------------------------------------------
// 所属チームのモック（Worker 側 /jobs 画面で使用）
// ---------------------------------------------------------------------------

/** 自分の所属チーム一覧（/api/v1/me/teams）のモック。 */
export async function mockMyTeams(page: Page): Promise<void> {
  await page.route('**/api/v1/me/teams', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: TEAM_ID,
            name: 'E2Eテストチーム',
            nickname1: 'E2Eテスト',
            iconUrl: null,
            role: 'MEMBER',
            template: 'DEFAULT',
          },
        ],
      }),
    })
  })
}
