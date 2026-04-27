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
 *       — 全員結果見える（未回答 MEMBER でも結果画面に直接遷移）</li>
 * </ul>
 *
 * <h2>現状実装 ({@code pages/surveys/[surveyId].vue}) の判定ロジック</h2>
 *
 * <p>設計書 docs/features/F05.4_survey_vote.md L1377〜「結果閲覧権限の判定」に準拠。</p>
 *
 * <pre>
 * canViewResults:
 *   - isCreator (createdBy.id === currentUser.id) → true
 *   - isAdminPlus (RoleAccess の roleName が ADMIN/SYSTEM_ADMIN) → true
 *   - resultsVisibility:
 *     - CREATOR_ONLY  → false
 *     - RESPONDENTS   → hasResponded === true で true
 *     - ALL_MEMBERS   → true
 *     - AFTER_CLOSE   → status === 'CLOSED' で true
 *
 * displayMode:
 *   1) status === 'DRAFT'              → 'draft'
 *   2) canViewResults                  → 'results' (最優先)
 *   3) status === 'PUBLISHED'          → 'response'
 *   4) status === 'CLOSED' & 権限なし  → 'closed-no-permission'
 * </pre>
 *
 * <p>結果閲覧権限を回答画面より優先するため、ALL_MEMBERS では未回答 MEMBER も
 * 直接 'results' に遷移する。CREATOR_ONLY / RESPONDENTS で結果権限を持たない
 * 未回答ユーザーは従来通り 'response' に誘導される。</p>
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

  test('MEMBER (未回答・PUBLISHED) は ALL_MEMBERS なので結果画面に直接遷移', async ({ page }) => {
    // 設計書 docs/features/F05.4_survey_vote.md L1377〜:
    //   resultsVisibility = ALWAYS (実装は ALL_MEMBERS) は誰でも閲覧可。
    //   結果閲覧権限が回答可否より優先されるため、未回答 MEMBER でも 'results'。
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

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toHaveCount(0)
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
