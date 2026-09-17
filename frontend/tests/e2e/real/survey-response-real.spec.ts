/**
 * アンケート回答フォームの実アプリ E2E。
 *
 * chromium-real の setup-real-user が作る保存済み認証を使う。spec 内で UI ログインを
 * 重ねない。未回答用と回答済み用のアンケートを別々に作り、後者だけ API で同じユーザーの
 * 回答を事前投入するため、各表示状態を手補正なしで再現できる。
 */
import { expect, test, type APIRequestContext } from '@playwright/test'

const BACKEND_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const E2E_USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const E2E_ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const TEAM_SLUG = 'fc-u-18'

type QuestionWire = {
  id: number
  questionType: string
  options?: Array<{ id: number }>
}

async function loginToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
  const response = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok()) return null
  return (await response.json())?.data?.accessToken ?? null
}

async function backendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const response = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    return (await response.json())?.status === 'UP'
  } catch {
    return false
  }
}

test.describe('SURVEY-REAL: アンケート回答フォーム', () => {
  let adminToken: string
  let unansweredSurveyId: number
  let answeredSurveyId: number

  async function createPublishedSurvey(request: APIRequestContext, state: '未回答' | '回答済み'): Promise<number> {
    const create = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys`, {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      data: {
        title: `実機E2E ${state} アンケート ${Date.now()}`,
        description: 'CMP-260917-1209 の実機E2E。afterAll で削除する。',
        isAnonymous: false,
        allowMultipleSubmissions: false,
        // 一般メンバーは結果を見られないため、回答後も response mode の
        // survey-already-responded を検証できる。
        resultsVisibility: 'ADMINS_ONLY',
        distributionMode: 'ALL',
        unrespondedVisibility: 'ALL_MEMBERS',
        questions: [
          {
            questionType: 'SINGLE_CHOICE',
            questionText: '好きな競技は？',
            isRequired: true,
            displayOrder: 1,
            options: [
              { optionText: '野球', displayOrder: 1 },
              { optionText: 'サッカー', displayOrder: 2 },
            ],
          },
          {
            questionType: 'FREE_TEXT',
            questionText: 'ひとこと',
            isRequired: false,
            displayOrder: 2,
          },
          {
            questionType: 'SCALE',
            questionText: '満足度',
            isRequired: true,
            displayOrder: 3,
            scaleMin: 1,
            scaleMax: 5,
          },
        ],
      },
    })
    expect(create.status(), `${state}アンケートの作成`).toBe(201)
    const surveyId = (await create.json())?.data?.id as number | undefined
    expect(surveyId, `${state} surveyId`).toBeTruthy()

    const publish = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys/${surveyId}/publish`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(publish.ok(), `${state}アンケートの公開`).toBeTruthy()
    return surveyId!
  }

  test.beforeAll(async ({ request }) => {
    test.skip(!(await backendAlive(request)), 'バックエンドが起動していない')
    const token = await loginToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    test.skip(!token, '管理者のAPIログインに失敗した')
    adminToken = token!
    unansweredSurveyId = await createPublishedSurvey(request, '未回答')
    answeredSurveyId = await createPublishedSurvey(request, '回答済み')

    // 保存済み browser auth を変更しないため、状態作成用の API token は別に取得する。
    const userToken = await loginToken(request, E2E_USER.email, E2E_USER.password)
    test.skip(!userToken, '回答済み状態を作るユーザーのAPIログインに失敗した')
    const detail = await request.get(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys/${answeredSurveyId}`, {
      headers: { Authorization: `Bearer ${userToken}` },
    })
    expect(detail.ok(), '回答済み用アンケート詳細の取得').toBeTruthy()
    const questions = (await detail.json())?.data?.questions as QuestionWire[]
    const choice = questions.find((question) => question.questionType === 'SINGLE_CHOICE')
    const scale = questions.find((question) => question.questionType === 'SCALE')
    const choiceOptionId = choice?.options?.[0]?.id
    expect(choiceOptionId, '単一選択肢').toBeTruthy()
    expect(scale?.id, '尺度設問').toBeTruthy()

    const answer = await request.post(`${BACKEND_URL}/api/v1/surveys/${answeredSurveyId}/responses`, {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userToken}` },
      data: {
        answers: [
          { questionId: choice!.id, optionIds: [choiceOptionId!] },
          { questionId: scale!.id, textResponse: '5' },
        ],
      },
    })
    expect(answer.status(), '回答済み状態の作成').toBe(201)
  })

  test.afterAll(async ({ request }) => {
    if (!adminToken) return
    for (const surveyId of [unansweredSurveyId, answeredSurveyId]) {
      if (!surveyId) continue
      await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys/${surveyId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      }).catch(() => {})
    }
  })

  test('SURVEY-REAL-1: 未回答状態でフォームを表示して回答する', async ({ page }) => {
    // chromium-real の保存済み storageState を使う。UI の再ログインは行わない。
    await page.goto(`/surveys/${unansweredSurveyId}?scope=team&scopeId=${TEAM_SLUG}`)

    await expect(page.getByTestId('survey-mode-response')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('survey-response-form')).toBeVisible()

    const radios = page.locator('[data-testid^="response-radio-"]')
    const ratings = page.locator('[data-testid^="response-rating-"]')
    await expect(radios.first()).toBeVisible()
    await expect(ratings.first()).toBeVisible()
    await radios.first().click()
    await ratings.nth(3).click()

    const submitResponse = page.waitForResponse(
      (response) => response.url().includes(`/surveys/${unansweredSurveyId}/responses`) && response.request().method() === 'POST',
    )
    await page.getByTestId('survey-response-submit').click()
    expect((await submitResponse).status(), '回答送信').toBe(201)

    // 回答後の表示も同じ testid 契約で確認する。
    await expect(page.getByTestId('survey-already-responded')).toBeVisible({ timeout: 20_000 })
  })

  test('SURVEY-REAL-2: 事前回答済み状態ではフォームを出さず回答済み表示を出す', async ({ page }) => {
    await page.goto(`/surveys/${answeredSurveyId}?scope=team&scopeId=${TEAM_SLUG}`)

    await expect(page.getByTestId('survey-mode-response')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('survey-already-responded')).toBeVisible()
    await expect(page.getByTestId('survey-response-form')).toHaveCount(0)
  })
})
