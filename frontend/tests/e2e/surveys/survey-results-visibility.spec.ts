import { test, expect, type Page } from '@playwright/test'
import {
  setupAuth,
  mockSurveyApi,
  buildSurvey,
  buildQuestion,
  buildSurveyDetail,
  buildResultSummary,
  waitForSurveyDetail,
  gotoSurveyDetail,
} from './_helpers'

/**
 * F05.4 アンケート画面 E2E — SURVEY-005 結果可視性 (resultsVisibility)
 *
 * <p>3 シナリオ:</p>
 * <ul>
 *   <li>SURVEY-005a: {@code resultsVisibility = 'CREATOR_ONLY'}
 *       — 作成者・ADMIN+ のみ結果見える、それ以外は不可</li>
 *   <li>SURVEY-005b: {@code resultsVisibility = 'RESPONDENTS'}
 *       — 回答済み MEMBER は見える、未回答 MEMBER は見えない</li>
 *   <li>SURVEY-005c: {@code resultsVisibility = 'ALL_MEMBERS'}
 *       — 全員結果見える（ただし表示モードは現状実装の優先順位に従う）</li>
 * </ul>
 *
 * <h2>現状実装 ({@code pages/surveys/[surveyId].vue}) の判定ロジック</h2>
 *
 * <pre>
 * canViewResults:
 *   - isCreator (createdBy.id === currentUser.id) → true
 *   - isAdminPlus (RoleAccess の roleName が ADMIN/SYSTEM_ADMIN) → true
 *   - resultsVisibility:
 *     - CREATOR_ONLY  → false
 *     - RESPONDENTS   → hasResponded === true で true
 *     - ALL_MEMBERS   → true
 *
 * displayMode (status === 'PUBLISHED' の場合):
 *   1) !hasResponded || allowMultipleSubmissions  → 'response' (最優先)
 *   2) canViewResults  → 'results'
 *   3) それ以外          → 'response' (回答済み表示)
 *
 * displayMode (status === 'CLOSED' の場合):
 *   - canViewResults  → 'results'
 *   - それ以外          → 'closed-no-permission'
 * </pre>
 *
 * <p>従って、PUBLISHED + 未回答時は結果可視性に関わらず常に 'response' になる
 * （最優先ルール）。'results' / 'closed-no-permission' を確実に検証するために
 * 一部サブケースは {@code status: 'CLOSED'} で組む。</p>
 */

const TEAM_ID = 1
const SURVEY_ID = 501

const CREATOR_ID = 100
const ADMIN_ID = 200
const MEMBER_ID = 300

// ---------------------------------------------------------------------------
// 共通モックデータ
// ---------------------------------------------------------------------------

const QUESTION = buildQuestion({
  id: 1,
  questionText: 'お気に入りの曜日は？',
  questionType: 'SINGLE_CHOICE',
  isRequired: true,
  sortOrder: 1,
  options: [
    { id: 11, optionText: '月曜', sortOrder: 1 },
    { id: 12, optionText: '火曜', sortOrder: 2 },
  ],
})

const RESULT_SUMMARY = buildResultSummary({
  questionId: 1,
  questionText: 'お気に入りの曜日は？',
  questionType: 'SINGLE_CHOICE',
  totalResponses: 3,
  optionResults: [
    { optionId: 11, optionText: '月曜', count: 2, percentage: 66.7 },
    { optionId: 12, optionText: '火曜', count: 1, percentage: 33.3 },
  ],
})

/** RoleAccess の me/permissions モック (roleName を切替可能) */
async function mockMePermissions(
  page: Page,
  roleName: 'SYSTEM_ADMIN' | 'ADMIN' | 'MEMBER',
): Promise<void> {
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          roleName,
          permissions: [],
        },
      }),
    })
  })
}

/** 詳細ページ周辺の「叩かれうるが本テストで関係しない」API を空応答で潰す。 */
async function mockSideApis(page: Page): Promise<void> {
  // SurveyResponseForm は alreadyResponded && allowMultiple の組合せでのみ
  // /api/v1/surveys/{id}/responses/me を呼ぶが、保険で空 200 を用意。
  await page.route('**/api/v1/surveys/*/responses/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { answers: [] } }),
    })
  })
}

// ---------------------------------------------------------------------------
// SURVEY-005a: CREATOR_ONLY
// ---------------------------------------------------------------------------

test.describe('SURVEY-005a: 結果可視性 CREATOR_ONLY', () => {
  test.beforeEach(async ({ page }) => {
    await mockSideApis(page)
  })

  test('作成者本人 (MEMBER ロール) は結果が見える', async ({ page }) => {
    await setupAuth(page, {
      userId: CREATOR_ID,
      displayName: 'creator-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'CREATOR_ONLY',
      allowMultipleSubmissions: false,
      hasResponded: true, // PUBLISHED + 結果表示には回答済み (or CLOSED) が必須
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-mode-closed-no-permission"]')).toHaveCount(0)
  })

  test('ADMIN ロール (作成者でない) は結果が見える', async ({ page }) => {
    await setupAuth(page, {
      userId: ADMIN_ID,
      displayName: 'admin-user',
      role: 'ADMIN',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'ADMIN')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'CREATOR_ONLY',
      allowMultipleSubmissions: false,
      hasResponded: true,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
  })

  test('一般 MEMBER (未回答) は回答画面のみ — 結果は見えない', async ({ page }) => {
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'CREATOR_ONLY',
      allowMultipleSubmissions: false,
      hasResponded: false, // 未回答
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
  })

  test('一般 MEMBER (回答済み・複数回答不可・PUBLISHED) は回答画面にフォールバック', async ({
    page,
  }) => {
    // 現状実装: PUBLISHED + hasResponded + !allowMultipleSubmissions
    //   → canViewResults=false ゆえ displayMode='response'。
    //   SurveyResponseForm 側で「既に回答済み」表示が出る想定。
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'CREATOR_ONLY',
      allowMultipleSubmissions: false,
      hasResponded: true,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-mode-closed-no-permission"]')).toHaveCount(0)
  })

  test('一般 MEMBER (回答済み・CLOSED) は結果非公開メッセージが表示される', async ({ page }) => {
    // CLOSED + canViewResults=false → 'closed-no-permission'
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'CLOSED',
      resultsVisibility: 'CREATOR_ONLY',
      allowMultipleSubmissions: false,
      hasResponded: true,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-closed-no-permission"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
  })
})

// ---------------------------------------------------------------------------
// SURVEY-005b: RESPONDENTS
// ---------------------------------------------------------------------------

test.describe('SURVEY-005b: 結果可視性 RESPONDENTS', () => {
  test.beforeEach(async ({ page }) => {
    await mockSideApis(page)
  })

  test('MEMBER (回答済み・複数回答不可) は結果が見える', async ({ page }) => {
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'RESPONDENTS',
      allowMultipleSubmissions: false,
      hasResponded: true,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
  })

  test('MEMBER (未回答) は回答画面 — 結果は見えない', async ({ page }) => {
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'RESPONDENTS',
      allowMultipleSubmissions: false,
      hasResponded: false,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
  })
})

// ---------------------------------------------------------------------------
// SURVEY-005c: ALL_MEMBERS
// ---------------------------------------------------------------------------

test.describe('SURVEY-005c: 結果可視性 ALL_MEMBERS', () => {
  test.beforeEach(async ({ page }) => {
    await mockSideApis(page)
  })

  test('MEMBER (未回答) は PUBLISHED では回答画面が優先される', async ({ page }) => {
    // 現状実装: PUBLISHED かつ !hasResponded のときは displayMode='response' が
    // 最優先で適用される。設計書では ALL_MEMBERS なら未回答でも結果閲覧可だが
    // PUBLISHED 中はまず回答画面に誘導する仕様。
    // TODO: 設計書では結果閲覧可だが現状実装は 'response' フォールバック優先。
    //       仕様変更が決まったらここを 'results' へ更新すること。
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'ALL_MEMBERS',
      allowMultipleSubmissions: false,
      hasResponded: false,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
  })

  test('MEMBER (回答済み・複数回答不可) は結果が見える', async ({ page }) => {
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'ALL_MEMBERS',
      allowMultipleSubmissions: false,
      hasResponded: true,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
  })

  test('MEMBER (未回答・CLOSED) は ALL_MEMBERS なので結果が見える', async ({ page }) => {
    // CLOSED + canViewResults=true (ALL_MEMBERS) → 'results'
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')

    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'CLOSED',
      resultsVisibility: 'ALL_MEMBERS',
      allowMultipleSubmissions: false,
      hasResponded: false,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-closed-no-permission"]')).toHaveCount(0)
  })
})
