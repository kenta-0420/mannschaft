import { test, expect, type Page } from '@playwright/test'
import {
  setupAuth,
  mockSurveyApi,
  buildRespondent,
  buildSurvey,
  buildQuestion,
  buildSurveyDetail,
  gotoSurveyDetail,
  waitForSurveyDetail,
} from './_helpers'

/**
 * F05.4 アンケート画面 E2E — SURVEY-003 督促送信 + 回答者セクション開閉
 *
 * <p>本 spec は「アンケート詳細ページ ({@code /surveys/{id}?scope=...&scopeId=...})
 * 経由で回答者セクションを開閉し、督促ボタンを操作する」シナリオを検証する。
 * 第一陣 (b9b84234) で {@code SurveyRespondentsList} が survey 詳細ページに
 * 正式組み込みされたため、E2E 専用ページ（{@code pages/_test/survey-respondents.vue}）
 * を経由する旧フローは廃止し、本 spec から削除した。</p>
 *
 * <h2>テストケース</h2>
 * <ul>
 *   <li>SURVEY-003-1: 成功（200）— 成功通知 + 一覧再取得</li>
 *   <li>SURVEY-003-2: クールダウン中（400）— エラー通知 + 一覧不変</li>
 *   <li>SURVEY-003-3: 上限超過（400）— エラー通知 + 一覧不変</li>
 *   <li>SURVEY-003-4a: ADMIN+ で詳細ページから回答者セクションを開閉できる</li>
 *   <li>SURVEY-003-4b: MEMBER（作成者でない）には回答者セクションが表示されない</li>
 * </ul>
 *
 * <h2>前提</h2>
 * <p>API レスポンスは PR#165 の修正後仕様に従い camelCase で返す
 * （{@code surveyId / remindedCount / remainingRemindQuota / message}）。</p>
 */

const TEAM_ID = 1
const SURVEY_ID = 901
const ADMIN_ID = 200
const CREATOR_ID = 100
const MEMBER_ID = 300

// ---------------------------------------------------------------------------
// 共通モックデータ
// ---------------------------------------------------------------------------

/** 未回答 2 名 + 回答済み 1 名のサンプル一覧。 */
const RESPONDENTS = [
  buildRespondent({
    userId: 11,
    displayName: '山田太郎',
    hasResponded: false,
  }),
  buildRespondent({
    userId: 12,
    displayName: '佐藤花子',
    hasResponded: false,
  }),
  buildRespondent({
    userId: 13,
    displayName: '鈴木一郎',
    hasResponded: true,
    respondedAt: '2026-04-26T10:00:00Z',
  }),
]

/** ADMIN が回答者セクションを開ける状態のアンケート（PUBLISHED + 督促可）。 */
function buildPublishedSurveyForAdmin() {
  return buildSurvey({
    id: SURVEY_ID,
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
    status: 'PUBLISHED',
    resultsVisibility: 'CREATOR_ONLY',
    allowMultipleSubmissions: false,
    hasResponded: false,
    createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
  })
}

/** {@link useRoleAccess} の me/permissions モック。roleName を切替可能。 */
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
  await page.route('**/api/v1/surveys/*/responses/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { answers: [] } }),
    })
  })
}

/** 回答者セクションを展開し、SurveyRespondentsList が表示されるまで待つ。 */
async function expandRespondentsSection(page: Page): Promise<void> {
  // ADMIN+ もしくは作成者の場合のみ section が描画される。
  await expect(page.getByTestId('survey-respondents-section')).toBeVisible()

  // toggle クリックと同時に SurveyRespondentsList の onMounted が
  // 発火して GET /respondents を叩く。レスポンス到着を待つことで
  // 「タブを切り替えれば必ず項目が見える状態」を保証する。
  // 初期タブは 'responded' のため、未回答ユーザー（id=11/12）はまだ DOM に出ない点に注意。
  const respondentsResponsePromise = page.waitForResponse(
    (res) =>
      res.request().method() === 'GET' &&
      /\/api\/v1\/teams\/\d+\/surveys\/\d+\/respondents$/.test(res.url()),
    { timeout: 10_000 },
  )
  await page.getByTestId('survey-respondents-toggle').click()
  await page.waitForSelector('[data-testid="survey-respondents-list"]', { timeout: 10_000 })
  await respondentsResponsePromise
  // タブ非依存のサマリー文言が描画されていること（loading=false 確認）
  await page.waitForSelector('[data-testid="respondents-summary"]', { timeout: 5_000 })
}

/** 未回答タブへ切り替え、督促ボタンの出現まで待つ。 */
async function switchToUnrespondedTab(page: Page): Promise<void> {
  const filter = page.locator('[data-testid="respondents-filter"]')
  await filter.getByText(/未回答/).click()
  // タブ切替後、未回答ユーザー（id=11）がリストに出ることでタブ切替完了を判定する。
  await page.waitForSelector('[data-testid="respondent-item-11"]', { timeout: 5_000 })
  await page.waitForSelector('[data-testid="respondents-remind-button"]', { timeout: 5_000 })
}

// ---------------------------------------------------------------------------
// SURVEY-003: 督促送信（実ページ経由）
// ---------------------------------------------------------------------------

test.describe('SURVEY-003: 督促送信（詳細ページ → 回答者セクション展開）', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, {
      userId: ADMIN_ID,
      displayName: 'admin-user',
      role: 'ADMIN',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'ADMIN')
    await mockSideApis(page)
  })

  test('SURVEY-003-1: 成功 — showSuccess + 一覧再取得', async ({ page }) => {
    const survey = buildPublishedSurveyForAdmin()
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [buildQuestion({ id: 1, questionText: 'Q1', questionType: 'SINGLE_CHOICE' })]) },
      respondentsById: { [SURVEY_ID]: RESPONDENTS },
      remindResponse: {
        ok: true,
        status: 200,
        body: {
          data: {
            surveyId: SURVEY_ID,
            remindedCount: 2,
            remainingRemindQuota: 2,
            message: '督促を送信しました',
          },
        },
      },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)
    await expandRespondentsSection(page)
    await switchToUnrespondedTab(page)

    // 督促送信ボタン押下と並行して、再取得 GET をキャッチする。
    // 「ボタン押下後」のリクエストを拾うため、押下前に waitForRequest を準備する。
    const refreshRequestPromise = page.waitForRequest(
      (req) =>
        req.method() === 'GET' &&
        /\/api\/v1\/teams\/\d+\/surveys\/\d+\/respondents$/.test(req.url()),
      { timeout: 10_000 },
    )

    await page.locator('[data-testid="respondents-remind-button"]').click()

    // 督促送信 POST のレスポンスを待つ → showSuccess → loadRespondents（再取得）の順
    await refreshRequestPromise

    // 成功通知トーストが表示される（remindSuccess の本文）
    await expect(page.getByText(/2名にリマインドを送信しました/)).toBeVisible()
  })

  test('SURVEY-003-2: クールダウン中 — showError + 一覧不変', async ({ page }) => {
    const survey = buildPublishedSurveyForAdmin()
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [buildQuestion({ id: 1, questionText: 'Q1', questionType: 'SINGLE_CHOICE' })]) },
    })

    // GET /respondents 回数を計測しつつ常に同じ一覧を返す。
    // mockSurveyApi の respondentsHandler を後勝ちで上書きする。
    let respondentsGetCount = 0
    await page.route('**/api/v1/teams/*/surveys/*/respondents', async (route) => {
      if (route.request().method() === 'GET') respondentsGetCount++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: RESPONDENTS }),
      })
    })

    // 督促 API: 400（COOLDOWN）
    await page.route('**/api/v1/surveys/*/remind', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'COOLDOWN',
            message: '前回の督促から24時間経過していません',
          },
        }),
      })
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)
    await expandRespondentsSection(page)
    await switchToUnrespondedTab(page)

    // セクション展開時の初回 GET が走った後を起点にする
    expect(respondentsGetCount).toBeGreaterThanOrEqual(1)
    const beforeCount = respondentsGetCount

    await page.locator('[data-testid="respondents-remind-button"]').click()

    // エラートーストが表示されるのを待つ
    await expect(page.getByText(/督促の送信に失敗しました/)).toBeVisible()

    // 失敗時は再取得しない仕様（一覧不変）
    expect(respondentsGetCount).toBe(beforeCount)

    // バッジ（未回答 2 件）も変化なし
    await expect(page.locator('[data-testid="respondent-item-11"]')).toBeVisible()
    await expect(page.locator('[data-testid="respondent-item-12"]')).toBeVisible()
  })

  test('SURVEY-003-3: 上限超過 — showError + 一覧不変', async ({ page }) => {
    const survey = buildPublishedSurveyForAdmin()
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [buildQuestion({ id: 1, questionText: 'Q1', questionType: 'SINGLE_CHOICE' })]) },
    })

    let respondentsGetCount = 0
    await page.route('**/api/v1/teams/*/surveys/*/respondents', async (route) => {
      if (route.request().method() === 'GET') respondentsGetCount++
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: RESPONDENTS }),
      })
    })

    await page.route('**/api/v1/surveys/*/remind', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'QUOTA_EXCEEDED',
            message: '督促の上限回数に達しました',
          },
        }),
      })
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)
    await expandRespondentsSection(page)
    await switchToUnrespondedTab(page)

    expect(respondentsGetCount).toBeGreaterThanOrEqual(1)
    const beforeCount = respondentsGetCount

    await page.locator('[data-testid="respondents-remind-button"]').click()

    await expect(page.getByText(/督促の送信に失敗しました/)).toBeVisible()

    expect(respondentsGetCount).toBe(beforeCount)

    await expect(page.locator('[data-testid="respondent-item-11"]')).toBeVisible()
    await expect(page.locator('[data-testid="respondent-item-12"]')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// SURVEY-003-4: 詳細ページで回答者セクションを開閉
// ---------------------------------------------------------------------------

test.describe('SURVEY-003-4: 詳細ページで回答者セクション開閉', () => {
  test.beforeEach(async ({ page }) => {
    await mockSideApis(page)
  })

  test('SURVEY-003-4a: ADMIN+ は section 表示 + toggle で開閉できる', async ({ page }) => {
    await setupAuth(page, {
      userId: ADMIN_ID,
      displayName: 'admin-user',
      role: 'ADMIN',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
    await mockMePermissions(page, 'ADMIN')

    const survey = buildPublishedSurveyForAdmin()
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [buildQuestion({ id: 1, questionText: 'Q1', questionType: 'SINGLE_CHOICE' })]) },
      respondentsById: { [SURVEY_ID]: RESPONDENTS },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    // 1. セクションは可視
    await expect(page.getByTestId('survey-respondents-section')).toBeVisible()

    // 2. 初期状態では SurveyRespondentsList はマウントされていない
    await expect(page.locator('[data-testid="survey-respondents-list"]')).toHaveCount(0)

    // 3. toggle クリック → リスト表示
    await page.getByTestId('survey-respondents-toggle').click()
    await expect(page.getByTestId('survey-respondents-list')).toBeVisible()
    // 一覧データが届いていることを「回答済みタブ（初期表示）に出る userId=13」で確認する。
    // 未回答ユーザー（11/12）は未回答タブに切り替えないと描画されない仕様。
    await expect(page.locator('[data-testid="respondent-item-13"]')).toBeVisible()

    // 4. 再度 toggle クリック → リスト非表示
    await page.getByTestId('survey-respondents-toggle').click()
    await expect(page.locator('[data-testid="survey-respondents-list"]')).toHaveCount(0)
  })

  test('SURVEY-003-4b: MEMBER（作成者でない）には section が表示されない', async ({ page }) => {
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
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
      status: 'PUBLISHED',
      resultsVisibility: 'ALL_MEMBERS',
      allowMultipleSubmissions: false,
      hasResponded: false,
      // 作成者は MEMBER_ID 以外
      createdBy: { id: CREATOR_ID, displayName: 'creator-user' },
    })
    await mockSurveyApi(page, {
      detailById: { [SURVEY_ID]: buildSurveyDetail(survey, [buildQuestion({ id: 1, questionText: 'Q1', questionType: 'SINGLE_CHOICE' })]) },
      respondentsById: { [SURVEY_ID]: RESPONDENTS },
    })

    await gotoSurveyDetail(page, SURVEY_ID, 'team', TEAM_ID)
    await waitForSurveyDetail(page, SURVEY_ID)

    // ページ自体は描画される
    await expect(page.getByTestId('survey-detail-page')).toBeVisible()

    // 回答者セクションは作成者でも ADMIN+ でもないため非表示
    await expect(page.locator('[data-testid="survey-respondents-section"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="survey-respondents-toggle"]')).toHaveCount(0)
  })
})
