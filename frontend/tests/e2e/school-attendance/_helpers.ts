import type { Page, Route } from '@playwright/test'
import type {
  DailyAttendanceResponse,
  DailyRollCallSummary,
  CandidateItem,
  PeriodAttendanceSummary,
  TransitionAlertResponse,
  TransitionAlertListResponse,
  FamilyAttendanceNoticeResponse,
  FamilyNoticeListResponse,
  MonthlyStatisticsResponse,
} from '../../../app/types/school'

/**
 * F03.13 Phase 8 学校出欠 E2E 共通ヘルパー。
 *
 * <p>役割:</p>
 * <ul>
 *   <li>教師・生徒・保護者の 3 ロールごとの認証注入（{@link loginAsTeacher} / {@link loginAsStudent} / {@link loginAsGuardian}）</li>
 *   <li>未モック API を 404 で握る catch-all（{@link mockCatchAllApis}）</li>
 *   <li>BE DTO 準拠の fixture builder（{@link buildDailyAttendanceRecord} 等）</li>
 *   <li>ジョブ別のリクエストキャプチャ付き API モック（{@link mockGetDailyAttendance} 等）</li>
 * </ul>
 *
 * <p>方針:</p>
 * <ul>
 *   <li>care-events / job-matching の {@code _helpers.ts} を踏襲（後勝ち page.route ＋ fixture 関数生成）</li>
 *   <li>後続 spec（足軽 B/C/D）は本ファイルを import して spec を実装する</li>
 *   <li>{@code captured} は {@code { lastBody: unknown }} を渡すと {@code postDataJSON()} で記録する</li>
 *   <li>未モック分は {@link mockCatchAllApis} が 404 で fail-fast する設計</li>
 * </ul>
 */

// ---------------------------------------------------------------------------
// 既定 ID 定数（spec から参照されるが、上書きしたい場合は引数で渡す）
// ---------------------------------------------------------------------------

export const DEFAULT_TEAM_ID = 1
export const TEACHER_USER_ID = 1
export const STUDENT_USER_ID_1 = 101
export const STUDENT_USER_ID_2 = 102
export const STUDENT_USER_ID_3 = 103
export const GUARDIAN_USER_ID = 201
export const DEFAULT_DATE = '2026-05-01'

// ---------------------------------------------------------------------------
// 認証注入
// ---------------------------------------------------------------------------

/**
 * 認証ペイロードを localStorage に注入する内部実装。
 *
 * <p>{@code addInitScript} で各ページロード時に確実に流し込む。
 * care-events のヘルパと同じ流儀で {@code accessToken} / {@code refreshToken} /
 * {@code currentUser} の 3 点を入れる。</p>
 */
async function injectAuth(
  page: Page,
  payload: { id: number; email: string; displayName: string; role: string },
): Promise<void> {
  await page.addInitScript((args) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: args.id,
        email: args.email,
        displayName: args.displayName,
        profileImageUrl: null,
        role: args.role,
      }),
    )
  }, payload)
}

/** 教師（ADMIN ロール）としてログイン済み状態をセットアップする。 */
export async function loginAsTeacher(
  page: Page,
  opts?: { teamId?: number },
): Promise<void> {
  await injectAuth(page, {
    id: TEACHER_USER_ID,
    email: 'e2e-teacher@example.com',
    displayName: 'e2e_teacher',
    role: 'ADMIN',
  })
  void opts?.teamId
}

/** 生徒（MEMBER ロール）としてログイン済み状態をセットアップする。 */
export async function loginAsStudent(
  page: Page,
  opts?: { userId?: number },
): Promise<void> {
  const userId = opts?.userId ?? STUDENT_USER_ID_1
  await injectAuth(page, {
    id: userId,
    email: `e2e-student-${userId}@example.com`,
    displayName: `e2e_student_${userId}`,
    role: 'MEMBER',
  })
}

/** 保護者（MEMBER ロール）としてログイン済み状態をセットアップする。 */
export async function loginAsGuardian(
  page: Page,
  opts?: { userId?: number },
): Promise<void> {
  const userId = opts?.userId ?? GUARDIAN_USER_ID
  await injectAuth(page, {
    id: userId,
    email: `e2e-guardian-${userId}@example.com`,
    displayName: `e2e_guardian_${userId}`,
    role: 'MEMBER',
  })
}

// ---------------------------------------------------------------------------
// catch-all
// ---------------------------------------------------------------------------

/**
 * すべての `/api/v1/**` を 404 で fulfill する catch-all。
 *
 * <p>spec ごとの {@code beforeEach} でまず本関数を呼び、その後に個別 mock を
 * 上書き登録する（Playwright は後勝ち）。未モックエンドポイントを叩いた場合は
 * 404 で即時失敗するため、テストの bug を握りつぶさない。</p>
 */
export async function mockCatchAllApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({
        error: { code: 'NOT_MOCKED', message: 'No mock registered for this endpoint' },
      }),
    })
  })
}

// ---------------------------------------------------------------------------
// リクエストボディキャプチャ
// ---------------------------------------------------------------------------

/**
 * 共通: リクエスト本文を {@code captured.lastBody} に格納する小道具。
 *
 * <p>Playwright の {@code postDataJSON()} が型 {@code unknown} を返すので、
 * 受け取り側が {@code as} で narrowing する前提で {@code unknown} のまま保持する。</p>
 */
async function captureBody(
  route: Route,
  captured: { lastBody: unknown } | undefined,
): Promise<void> {
  if (!captured) return
  try {
    captured.lastBody = route.request().postDataJSON() as unknown
  } catch {
    captured.lastBody = null
  }
}

// ---------------------------------------------------------------------------
// fixture builder（BE DTO 準拠）
// ---------------------------------------------------------------------------

/** DailyAttendanceResponse（DailyAttendanceRecord）の雛形。 */
export function buildDailyAttendanceRecord(
  overrides: Partial<DailyAttendanceResponse> = {},
): DailyAttendanceResponse {
  return {
    id: 1,
    teamId: DEFAULT_TEAM_ID,
    studentUserId: STUDENT_USER_ID_1,
    attendanceDate: DEFAULT_DATE,
    status: 'UNDECIDED',
    absenceReason: undefined,
    arrivalTime: undefined,
    leaveTime: undefined,
    comment: undefined,
    familyNoticeId: undefined,
    recordedAt: `${DEFAULT_DATE}T09:00:00.000Z`,
    createdAt: `${DEFAULT_DATE}T09:00:00.000Z`,
    updatedAt: `${DEFAULT_DATE}T09:00:00.000Z`,
    ...overrides,
  }
}

/** DailyRollCallSummary の雛形。 */
export function buildDailyRollCallSummary(
  overrides: Partial<DailyRollCallSummary> = {},
): DailyRollCallSummary {
  return {
    date: DEFAULT_DATE,
    teamId: DEFAULT_TEAM_ID,
    total: 3,
    attending: 3,
    partial: 0,
    absent: 0,
    undecided: 0,
    ...overrides,
  }
}

/** CandidateItem の雛形。 */
export function buildCandidateItem(
  overrides: Partial<CandidateItem> = {},
): CandidateItem {
  return {
    studentUserId: STUDENT_USER_ID_1,
    displayName: '山田太郎',
    dailyStatus: 'UNDECIDED',
    previousPeriodStatus: undefined,
    ...overrides,
  }
}

/** PeriodAttendanceSummary の雛形。 */
export function buildPeriodAttendanceSummary(
  overrides: Partial<PeriodAttendanceSummary> = {},
): PeriodAttendanceSummary {
  return {
    date: DEFAULT_DATE,
    teamId: DEFAULT_TEAM_ID,
    periodNumber: 1,
    total: 3,
    attending: 3,
    partial: 0,
    absent: 0,
    undecided: 0,
    ...overrides,
  }
}

/** TransitionAlertResponse の雛形。 */
export function buildTransitionAlertResponse(
  overrides: Partial<TransitionAlertResponse> = {},
): TransitionAlertResponse {
  return {
    id: 1,
    teamId: DEFAULT_TEAM_ID,
    studentUserId: STUDENT_USER_ID_1,
    attendanceDate: DEFAULT_DATE,
    previousPeriodNumber: 2,
    currentPeriodNumber: 3,
    previousPeriodStatus: 'ATTENDING',
    currentPeriodStatus: 'ABSENT',
    alertLevel: 'NORMAL',
    resolved: false,
    resolvedAt: undefined,
    resolvedBy: undefined,
    resolutionNote: undefined,
    createdAt: `${DEFAULT_DATE}T10:00:00.000Z`,
    ...overrides,
  }
}

/** TransitionAlertListResponse の雛形。 */
export function buildTransitionAlertListResponse(
  overrides: Partial<TransitionAlertListResponse> = {},
): TransitionAlertListResponse {
  return {
    teamId: DEFAULT_TEAM_ID,
    attendanceDate: DEFAULT_DATE,
    alerts: [],
    totalCount: 0,
    unresolvedCount: 0,
    ...overrides,
  }
}

/** FamilyAttendanceNoticeResponse の雛形。 */
export function buildFamilyAttendanceNoticeResponse(
  overrides: Partial<FamilyAttendanceNoticeResponse> = {},
): FamilyAttendanceNoticeResponse {
  return {
    id: 1,
    teamId: DEFAULT_TEAM_ID,
    studentUserId: STUDENT_USER_ID_1,
    submitterUserId: GUARDIAN_USER_ID,
    attendanceDate: DEFAULT_DATE,
    noticeType: 'ABSENCE',
    reason: 'SICK',
    reasonDetail: undefined,
    expectedArrivalTime: undefined,
    expectedLeaveTime: undefined,
    attachedDownloadUrls: [],
    status: 'PENDING',
    acknowledgedBy: undefined,
    acknowledgedAt: undefined,
    appliedToRecord: false,
    createdAt: `${DEFAULT_DATE}T08:00:00.000Z`,
    updatedAt: `${DEFAULT_DATE}T08:00:00.000Z`,
    ...overrides,
  }
}

/** FamilyNoticeListResponse の雛形。 */
export function buildFamilyNoticeListResponse(
  overrides: Partial<FamilyNoticeListResponse> = {},
): FamilyNoticeListResponse {
  return {
    teamId: DEFAULT_TEAM_ID,
    attendanceDate: DEFAULT_DATE,
    records: [],
    totalCount: 0,
    unacknowledgedCount: 0,
    ...overrides,
  }
}

/** MonthlyStatisticsResponse の雛形。 */
export function buildMonthlyStatisticsResponse(
  overrides: Partial<MonthlyStatisticsResponse> = {},
): MonthlyStatisticsResponse {
  return {
    year: 2026,
    month: 5,
    teamId: DEFAULT_TEAM_ID,
    totalSchoolDays: 20,
    totalStudents: 30,
    presentCount: 25,
    absentCount: 5,
    undecidedCount: 0,
    attendanceRate: 0.833,
    studentBreakdown: [],
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// API モック
// ---------------------------------------------------------------------------

/**
 * GET /api/v1/teams/{teamId}/attendance/daily をモック。
 *
 * <p>日次出欠一覧を返す。クエリパラメータ {@code ?date=...} は glob で無視される。</p>
 */
export async function mockGetDailyAttendance(
  page: Page,
  records: DailyAttendanceResponse[],
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/daily**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { records } }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/attendance/daily/roll-call をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockSubmitDailyRollCall(
  page: Page,
  summary: DailyRollCallSummary,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/daily/roll-call', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ data: summary }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/attendance/periods/{periodNumber}/candidates をモック。 */
export async function mockGetPeriodCandidates(
  page: Page,
  candidates: CandidateItem[],
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/periods/*/candidates', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          teamId: DEFAULT_TEAM_ID,
          periodNumber: 1,
          date: DEFAULT_DATE,
          candidates,
        },
      }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/attendance/periods/{periodNumber} をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockSubmitPeriodAttendance(
  page: Page,
  summary: PeriodAttendanceSummary,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/periods/*', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ data: summary }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/attendance/transition-alerts をモック。 */
export async function mockGetTransitionAlerts(
  page: Page,
  response: TransitionAlertListResponse,
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/transition-alerts**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: response }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/attendance/transition-alerts/{id}/resolve をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockResolveTransitionAlert(
  page: Page,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/transition-alerts/*/resolve', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: null }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/attendance/notices をモック（担任側：連絡一覧）。 */
export async function mockGetFamilyNotices(
  page: Page,
  response: FamilyNoticeListResponse,
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/notices**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: response }),
    })
  })
}

/**
 * POST /api/v1/me/attendance/notices をモック（保護者側：連絡送信）。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockSubmitFamilyNotice(
  page: Page,
  response?: FamilyAttendanceNoticeResponse,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/me/attendance/notices', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ data: response ?? buildFamilyAttendanceNoticeResponse() }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/attendance/notices/{id}/acknowledge をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockAcknowledgeFamilyNotice(
  page: Page,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/notices/*/acknowledge', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: buildFamilyAttendanceNoticeResponse({ status: 'ACKNOWLEDGED' }),
      }),
    })
  })
}

/**
 * POST /api/v1/teams/{teamId}/attendance/notices/{id}/apply をモック。
 *
 * <p>{@code captured} を渡すと {@code lastBody} にリクエスト本文が記録される。</p>
 */
export async function mockApplyFamilyNotice(
  page: Page,
  captured?: { lastBody: unknown },
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/notices/*/apply', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await captureBody(route, captured)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: buildFamilyAttendanceNoticeResponse({ status: 'APPLIED', appliedToRecord: true }),
      }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/attendance/statistics/monthly をモック。 */
export async function mockGetMonthlyStatistics(
  page: Page,
  response: MonthlyStatisticsResponse,
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/statistics/monthly**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: response }),
    })
  })
}

/** GET /api/v1/teams/{teamId}/attendance/statistics/term をモック。 */
export async function mockGetTermStatistics(
  page: Page,
  response: unknown,
): Promise<void> {
  await page.route('**/api/v1/teams/*/attendance/statistics/term**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: response }),
    })
  })
}
