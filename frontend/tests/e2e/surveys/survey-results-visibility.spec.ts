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
  // getSurvey は実 BE wire 形（行配列）の /api/v1/surveys/{id}/responses/me を叩き、
  // 行の有無から hasResponded を導出する（useSurveyApi.adaptMyResponse / getSurvey）。
  // 既定は「未回答」= 空配列の wire 形で返す。回答済みを期待するテスト
  // （RESPONDENTS × 非作成者・非管理者）は、本ヘルパー呼び出し後に同 path を
  // 非空 wire 配列で上書きすること（Playwright は後着優先）。
  await page.route('**/api/v1/surveys/*/responses/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

/**
 * 「自分は回答済み」を表す wire 行配列で responses/me を上書きする。
 * RESPONDENTS 可視性で非作成者・非管理者が結果を閲覧するシナリオで使用する
 * （hasResponded を行有無から導出する getSurvey に整合させるため）。
 */
async function mockRespondedSelf(page: Page, viewerId: number): Promise<void> {
  await page.route('**/api/v1/surveys/*/responses/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 1,
            surveyId: SURVEY_ID,
            questionId: QUESTION.id,
            userId: viewerId,
            optionId: null,
            textResponse: 'x',
            createdAt: '2026-04-20T00:00:00Z',
          },
        ],
      }),
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
    // RESPONDENTS 可視性で非作成者・非管理者が結果を見るには hasResponded=true が必須。
    // getSurvey は responses/me の行有無から導出するため、非空 wire 配列で上書きする。
    await mockRespondedSelf(page, MEMBER_ID)

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

// ---------------------------------------------------------------------------
// SURVEY-005d: 匿名 + リアルタイム結果のプライバシーガード
//
// 設計書 docs/features/F05.4_survey_vote.md §6 セキュリティ考慮事項:
//   is_anonymous = TRUE かつ results_visibility = ALWAYS（FE の ALL_MEMBERS）のとき、
//   回答者数が5名未満の間は集計結果を表示しない（回答直後の集計の変化から個人の回答が
//   推測されるのを防ぐ）。API は結果を返すが FE で伏せる。
//
// 権限（canViewResults）とは別軸のガードであることに注意。権限があっても伏せる。
// ---------------------------------------------------------------------------
test.describe('SURVEY-005d: 匿名＋リアルタイム結果のプライバシーガード', () => {
  test.beforeEach(async ({ page }) => {
    await mockSideApis(page)
  })

  /** 「自分の回答」1 行（これが返ると詳細画面の hasResponded が true になる）。 */
  const MY_RESPONSE_ROW = {
    id: 9001,
    surveyId: SURVEY_ID,
    questionId: 1,
    userId: MEMBER_ID,
    optionId: 1,
    textResponse: null,
  }

  /**
   * 匿名 + ALL_MEMBERS のアンケートを回答者数だけ変えて組み立てる。
   *
   * 「自分が回答済みか」はここでは指定しない。詳細画面の hasResponded は
   * 「自分の回答」API から導出されるため、mockSurveyApi の myResponseById で与える。
   */
  function buildAnonymousRealtimeSurvey(responseCount: number, isAnonymous = true) {
    return buildSurvey({
      id: SURVEY_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'ALL_MEMBERS',
      isAnonymous,
      responseCount,
      allowMultipleSubmissions: false,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
  }

  async function loginAsMember(page: Parameters<typeof mockSideApis>[0]) {
    await setupAuth(page, {
      userId: MEMBER_ID,
      displayName: 'member-user',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'MEMBER')
  }

  test('匿名 + ALL_MEMBERS + 回答0件 + 未回答 → 回答フォームに到達できる（詰みの再発防止）', async ({
    page,
  }) => {
    // 公開直後の匿名リアルタイムアンケートは必ず回答0件。ここで説明画面を先に出すと
    // 誰も回答できず、回答数が閾値に達しないためガードが永久に解除されない。
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(0)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
    await expect(
      page.locator('[data-testid="survey-mode-results-withheld-privacy"]'),
    ).toHaveCount(0)
  })

  test('匿名 + ALL_MEMBERS + 回答者4名 + 回答済み → 結果を伏せ、理由を表示する', async ({
    page,
  }) => {
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(4)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
      // hasResponded は「自分の回答」API から導出されるため、ここで回答済みにする
      myResponseById: { [SURVEY_ID]: [MY_RESPONSE_ROW] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    const withheld = page.locator('[data-testid="survey-mode-results-withheld-privacy"]')
    await expect(withheld).toBeVisible()
    // 集計そのものは出さない
    await expect(page.locator('[data-testid="survey-results-panel"]')).toHaveCount(0)
    // 黙って空にせず、なぜ見えないのかを説明していること
    await expect(withheld).toContainText('5')
  })

  test('匿名 + ALL_MEMBERS + 回答者5名 → 結果を表示する（境界値・ちょうど5で開く）', async ({
    page,
  }) => {
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(5)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(
      page.locator('[data-testid="survey-mode-results-withheld-privacy"]'),
    ).toHaveCount(0)
  })

  test('非匿名 + ALL_MEMBERS + 回答者1名 → 結果を表示する（巻き添えで塞いでいない）', async ({
    page,
  }) => {
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(1, false)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(
      page.locator('[data-testid="survey-mode-results-withheld-privacy"]'),
    ).toHaveCount(0)
  })

  test('非匿名 + ALL_MEMBERS + PUBLISHED + 未回答 → 結果画面に回答導線がある（詰みの再発防止）', async ({
    page,
  }) => {
    // 非匿名の ALWAYS は公開直後から全員が結果画面へ落ちる。ここに回答導線が無いと
    // 未回答者が結果画面に固定され、UI から回答を一件も集められない。
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(3, false)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    const cta = page.locator('[data-testid="survey-respond-cta"]')
    await expect(cta).toBeVisible()

    // 押すと回答フォームへ移れること（導線が実際に機能する）
    await cta.click()
    await expect(page.locator('[data-testid="survey-mode-response"]')).toBeVisible()
  })

  test('匿名 + ALL_MEMBERS + 回答5件 + 未回答 → 結果画面に回答導線がある（ガード解除後も詰まない）', async ({
    page,
  }) => {
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(5)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-respond-cta"]')).toBeVisible()
  })

  test('回答済み + 複数回答不可 → 結果画面に回答導線は出ない', async ({ page }) => {
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(6, false)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
      myResponseById: { [SURVEY_ID]: [MY_RESPONSE_ROW] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-respond-cta"]')).toHaveCount(0)
  })

  test('CLOSED では結果画面に回答導線が出ない（境界）', async ({ page }) => {
    await loginAsMember(page)
    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'CLOSED',
      resultsVisibility: 'ALL_MEMBERS',
      isAnonymous: false,
      responseCount: 3,
      allowMultipleSubmissions: false,
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-respond-cta"]')).toHaveCount(0)
  })

  test('配信対象外（結果 API が 403）→ 結果パネルも回答導線も出さず理由を示す', async ({
    page,
  }) => {
    // TARGETED の名簿外 / includeSupporters=false で除外された SUPPORTER のケース。
    // FE の楽観判定で結果パネルと CTA を出すと「押せるのに必ず失敗する」導線になる。
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(3, false)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsForbiddenIds: [SURVEY_ID],
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    const forbidden = page.locator('[data-testid="survey-mode-results-forbidden"]')
    await expect(forbidden).toBeVisible()
    // 集計も回答導線も出さない
    await expect(page.locator('[data-testid="survey-results-panel"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-respond-cta"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-mode-results"]')).toHaveCount(0)
  })

  test('配信対象なら従来どおり結果と回答導線が出る（陽性対照）', async ({ page }) => {
    // 過剰に塞いでいないことの裏取り。403 を返さなければ従来どおり。
    await loginAsMember(page)
    const survey = buildAnonymousRealtimeSurvey(3, false)
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [QUESTION]) },
      resultsById: { [SURVEY_ID]: [RESULT_SUMMARY] },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    await expect(page.locator('[data-testid="survey-mode-results"]')).toBeVisible()
    await expect(page.locator('[data-testid="survey-respond-cta"]')).toBeVisible()
    await expect(
      page.locator('[data-testid="survey-mode-results-forbidden"]'),
    ).toHaveCount(0)
  })

  test('匿名 + AFTER_CLOSE + 回答者1名 → 従来どおり（他の可視性を塞いでいない）', async ({
    page,
  }) => {
    await loginAsMember(page)
    const survey = buildSurvey({
      id: SURVEY_ID,
      status: 'CLOSED',
      resultsVisibility: 'AFTER_CLOSE',
      isAnonymous: true,
      responseCount: 1,
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
    await expect(
      page.locator('[data-testid="survey-mode-results-withheld-privacy"]'),
    ).toHaveCount(0)
  })
})
