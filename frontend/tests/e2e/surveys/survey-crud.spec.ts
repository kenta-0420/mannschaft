import { test, expect, type Route } from '@playwright/test'
import type { CreateSurveyRequest, SurveyResponse } from '../../../app/types/survey'
import {
  buildQuestion,
  buildResultSummary,
  buildSurvey,
  buildSurveyDetail,
  gotoTeamSurveys,
  mockSurveyApi,
  setupAuth,
  waitForSurveyDetail,
  waitForSurveyList,
} from './_helpers'

/**
 * F05.4 アンケート画面 E2E テスト — SURVEY-001 / SURVEY-002（CRUD コアシナリオ）
 *
 * <p>テストID:</p>
 * <ul>
 *   <li>SURVEY-001: アンケート作成 → 一覧反映</li>
 *   <li>SURVEY-002: 一覧から詳細遷移 → 回答送信 → 結果表示</li>
 * </ul>
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（_helpers.ts の {@link mockSurveyApi} を基本とし、必要に応じて spec で {@link page.route} を上書き）</li>
 *   <li>data-testid ベースで要素を取得（第一陣A で付与済み）</li>
 *   <li>recruitment.spec.ts / unconfirmed-visibility.spec.ts と同じパターン</li>
 * </ul>
 */

const TEAM_ID = 1
const ADMIN_USER_ID = 1
const MEMBER_USER_ID = 2
const NEW_SURVEY_ID = 1001
const PUBLISHED_SURVEY_ID = 2001

// ---------------------------------------------------------------------------
// SURVEY-001 用ペイロード
// ---------------------------------------------------------------------------

/** 「テストアンケート」作成リクエストを受けたときに返す survey */
function buildCreatedSurvey(): SurveyResponse {
  return buildSurvey({
    id: NEW_SURVEY_ID,
    title: 'テストアンケート',
    status: 'DRAFT',
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
    isAnonymous: false,
    allowMultipleSubmissions: false,
    resultsVisibility: 'RESPONDENTS',
    unrespondedVisibility: 'CREATOR_AND_ADMIN',
    deadline: null,
    responseCount: 0,
    targetCount: null,
    hasResponded: false,
    createdBy: { id: ADMIN_USER_ID, displayName: '太郎' },
  })
}

// ---------------------------------------------------------------------------
// SURVEY-002 用ペイロード
// ---------------------------------------------------------------------------

const PUBLISHED_QUESTION_ID = 501
const PUBLISHED_OPTION_RED_ID = 9001
const PUBLISHED_OPTION_BLUE_ID = 9002

/** PUBLISHED アンケート（未回答・複数回答不可・結果は ALL_MEMBERS 公開） */
function buildPublishedSurvey(overrides: Partial<SurveyResponse> = {}): SurveyResponse {
  return buildSurvey({
    id: PUBLISHED_SURVEY_ID,
    title: '春の好みアンケート',
    description: 'チーム内の好みを集めます。',
    status: 'PUBLISHED',
    isAnonymous: false,
    allowMultipleSubmissions: false,
    resultsVisibility: 'ALL_MEMBERS',
    unrespondedVisibility: 'ALL_MEMBERS',
    deadline: null,
    responseCount: 0,
    targetCount: null,
    hasResponded: false,
    createdBy: { id: ADMIN_USER_ID, displayName: '太郎' },
    ...overrides,
  })
}

/** PUBLISHED アンケートの設問定義（SINGLE_CHOICE 1問 + 選択肢 2件） */
function buildPublishedQuestion() {
  return buildQuestion({
    id: PUBLISHED_QUESTION_ID,
    questionText: '好きな色は？',
    questionType: 'SINGLE_CHOICE',
    isRequired: true,
    sortOrder: 1,
    options: [
      { id: PUBLISHED_OPTION_RED_ID, optionText: '赤', sortOrder: 1 },
      { id: PUBLISHED_OPTION_BLUE_ID, optionText: '青', sortOrder: 2 },
    ],
  })
}

/** 集計結果モック（赤 1票 / 青 0票） */
function buildPublishedResults() {
  return [
    buildResultSummary({
      questionId: PUBLISHED_QUESTION_ID,
      questionText: '好きな色は？',
      questionType: 'SINGLE_CHOICE',
      totalResponses: 1,
      optionResults: [
        {
          optionId: PUBLISHED_OPTION_RED_ID,
          optionText: '赤',
          count: 1,
          percentage: 100,
        },
        {
          optionId: PUBLISHED_OPTION_BLUE_ID,
          optionText: '青',
          count: 0,
          percentage: 0,
        },
      ],
    }),
  ]
}

// ---------------------------------------------------------------------------
// 詳細ページ用 周辺 API モック（permissions / 未マッチ catch-all）
// ---------------------------------------------------------------------------

/**
 * 詳細ページが `useRoleAccess` 経由で叩く `/me/permissions` をモックする。
 * SURVEY-002 では MEMBER 視点のため roleName = 'MEMBER' を返す。
 */
async function mockMemberPermissions(page: import('@playwright/test').Page): Promise<void> {
  const handler = async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          roleName: 'MEMBER',
          permissions: [],
        },
      }),
    })
  }
  await page.route('**/api/v1/teams/*/me/permissions', handler)
  await page.route('**/api/v1/organizations/*/me/permissions', handler)
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('SURVEY-001 / 002: アンケート CRUD', () => {
  test('SURVEY-001: アンケート作成 → 一覧反映', async ({ page }) => {
    // ADMIN 認証注入（作成権限あり）
    await setupAuth(page, {
      userId: ADMIN_USER_ID,
      displayName: '太郎',
      role: 'ADMIN',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })

    // 動的に切り替わる一覧データ。作成完了後に refresh() で再取得されたとき
    // 1件返したいので、配列を let で持つ。
    let currentSurveys: SurveyResponse[] = []
    const created = buildCreatedSurvey()

    // _helpers.mockSurveyApi の onCreate を使うと作成 POST に 201 を返してくれる。
    // 同時に「未マッチで 404」を防ぐため detailById / resultsById は空の {} で良い。
    await mockSurveyApi(page, {
      surveys: [], // ※ 後で page.route を上書きするので初期値のみ意味あり
      detailById: {},
      resultsById: {},
      onCreate: (body): SurveyResponse => {
        // body には CreateSurveyRequest 相当が入る
        const req = body as CreateSurveyRequest
        const merged = buildCreatedSurvey()
        if (req?.title) merged.title = req.title
        currentSurveys = [merged]
        return merged
      },
    })

    // 一覧 GET を spec 側で「動的配列を返す」ハンドラに上書き（後勝ち）。
    // 作成 POST は同 path のため method 分岐は再現する。
    const listOverride = async (route: Route): Promise<void> => {
      const method = route.request().method()
      if (method === 'POST') {
        // POST は _helpers の onCreate ハンドラに任せたいが、後勝ちでこちらが拾うため
        // 同等の処理を再実装する
        let postBody: unknown = null
        try {
          postBody = route.request().postDataJSON() as unknown
        } catch {
          postBody = null
        }
        const req = (postBody ?? {}) as CreateSurveyRequest
        const responseSurvey = buildCreatedSurvey()
        if (req?.title) responseSurvey.title = req.title
        currentSurveys = [responseSurvey]
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: responseSurvey }),
        })
        return
      }
      // GET
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: currentSurveys,
          meta: {
            page: 0,
            size: 50,
            totalElements: currentSurveys.length,
            totalPages: currentSurveys.length === 0 ? 0 : 1,
          },
        }),
      })
    }
    await page.route('**/api/v1/teams/*/surveys', listOverride)
    await page.route('**/api/v1/teams/*/surveys?*', listOverride)

    // ページ遷移
    await gotoTeamSurveys(page, TEAM_ID)
    await waitForSurveyList(page)

    // 初期は空。作成ボタンが表示される
    const createBtn = page.getByTestId('survey-create-button')
    await expect(createBtn).toBeVisible({ timeout: 10_000 })

    // 「アンケート作成」ボタンをクリック → ダイアログが表示される
    await createBtn.click()
    await expect(page.getByTestId('survey-create-dialog')).toBeVisible({ timeout: 10_000 })

    // タイトル入力
    await page.getByTestId('survey-create-title').fill('テストアンケート')

    // 設問追加（SINGLE_CHOICE がデフォルト・選択肢2件が初期生成される）
    await page.getByTestId('question-add').click()
    await expect(page.getByTestId('question-card-0')).toBeVisible({ timeout: 5_000 })

    // 設問テキスト入力
    await page.getByTestId('question-text-0').fill('Q1: 好きな色')

    // 選択肢2つ（初期で空欄2つ生成済み）に「赤」「青」を入力
    await page.getByTestId('question-option-0-0').fill('赤')
    await page.getByTestId('question-option-0-1').fill('青')

    // 保存ボタンクリック
    await page.getByTestId('survey-create-submit').click()

    // ダイアログが閉じる
    await expect(page.getByTestId('survey-create-dialog')).toBeHidden({ timeout: 10_000 })

    // 一覧再取得モックで作成された survey が表示される
    // SurveyList.refresh() が呼ばれて GET が currentSurveys（=1件）を返す
    await expect(page.getByTestId(`survey-item-${created.id}`)).toBeVisible({ timeout: 10_000 })

    // 一覧に「テストアンケート」と表示される
    await expect(page.getByText('テストアンケート')).toBeVisible({ timeout: 10_000 })
  })

  test('SURVEY-002: 一覧から詳細遷移 → 回答送信 → 結果表示', async ({ page }) => {
    // MEMBER 認証注入
    await setupAuth(page, {
      userId: MEMBER_USER_ID,
      displayName: '次郎',
      role: 'MEMBER',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })

    // /me/permissions（詳細ページの useRoleAccess）モック
    await mockMemberPermissions(page)

    // 初期状態: 1件の PUBLISHED アンケート（未回答）
    const survey = buildPublishedSurvey({ hasResponded: false })
    const question = buildPublishedQuestion()

    // 詳細レスポンスと結果は「回答前後で変わる」ためミュータブルに保持
    let currentDetail = buildSurveyDetail(
      buildPublishedSurvey({ hasResponded: false }),
      [question],
    )
    const results = buildPublishedResults()

    // 共通モック登録（一覧/詳細/結果）
    await mockSurveyApi(page, {
      surveys: [survey],
      detailById: { [PUBLISHED_SURVEY_ID]: currentDetail },
      resultsById: { [PUBLISHED_SURVEY_ID]: results },
    })

    // 詳細 GET を spec 側で動的に上書き（後勝ち）。
    // submitResponse 成功後の再 fetch では hasResponded=true を返す必要があるため。
    const detailOverride = async (route: Route): Promise<void> => {
      if (route.request().method() !== 'GET') {
        await route.continue()
        return
      }
      const url = new URL(route.request().url())
      const match = url.pathname.match(/\/surveys\/(\d+)$/)
      if (!match) {
        await route.continue()
        return
      }
      const id = Number(match[1])
      if (id !== PUBLISHED_SURVEY_ID) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'SURVEY_NOT_FOUND', message: 'survey not found' },
          }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(currentDetail),
      })
    }
    await page.route('**/api/v1/teams/*/surveys/*', detailOverride)

    // 回答送信 API（POST /api/v1/surveys/{id}/responses）モック
    // 成功したら currentDetail を hasResponded=true 版に差し替える
    await page.route('**/api/v1/surveys/*/responses', async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      // 成功 → 詳細を hasResponded=true に差し替える
      currentDetail = buildSurveyDetail(
        buildPublishedSurvey({
          hasResponded: true,
          responseCount: 1,
        }),
        [question],
      )
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 8001,
            surveyId: PUBLISHED_SURVEY_ID,
            respondentId: MEMBER_USER_ID,
            submittedAt: '2026-04-27T00:00:00Z',
          },
        }),
      })
    })

    // 一覧ページへ遷移
    await gotoTeamSurveys(page, TEAM_ID)
    await waitForSurveyList(page)

    // アンケート行クリック → 詳細へ遷移
    await page.getByTestId(`survey-item-${PUBLISHED_SURVEY_ID}`).click()

    // 詳細ページ表示完了
    await waitForSurveyDetail(page, PUBLISHED_SURVEY_ID)

    // URL が /surveys/{id}?scope=team&scopeId={teamId} 形式になっている
    await expect(page).toHaveURL(
      new RegExp(`/surveys/${PUBLISHED_SURVEY_ID}\\?scope=team&scopeId=${TEAM_ID}`),
    )

    // hasResponded=false のため回答モードが表示される
    await expect(page.getByTestId('survey-mode-response')).toBeVisible({ timeout: 10_000 })

    // SINGLE_CHOICE の選択肢「赤」を選択
    const radioRed = page.getByTestId(`response-radio-${PUBLISHED_QUESTION_ID}-${PUBLISHED_OPTION_RED_ID}`)
    await radioRed.click()

    // 送信ボタンクリック
    await page.getByTestId('survey-response-submit').click()

    // 詳細再 fetch 後 hasResponded=true・resultsVisibility=ALL_MEMBERS のため
    // 結果モードに切り替わる（response モードは消える）
    await expect(page.getByTestId('survey-mode-results')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('survey-mode-response')).toBeHidden({ timeout: 10_000 })

    // 結果パネル本体（SurveyResultsPanel）が描画され、設問テキストが表示される
    await expect(page.getByTestId('survey-results-panel')).toBeVisible({ timeout: 10_000 })
    await expect(
      page.getByTestId(`result-question-${PUBLISHED_QUESTION_ID}`),
    ).toBeVisible({ timeout: 10_000 })

    // 結果サマリーに設問テキスト「好きな色は？」が見える
    await expect(page.getByText('好きな色は？')).toBeVisible({ timeout: 10_000 })
  })
})
