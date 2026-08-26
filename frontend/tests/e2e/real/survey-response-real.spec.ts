/**
 * 実機E2E（モック不使用・実BE/実FE）: アンケート回答フロー一気通貫の回帰テスト。
 *
 * 背景: FE↔BE の詳細/回答契約が複数次元で不一致（survey入れ子 / 設問content・scaleConfig入れ子 /
 * questionType SCALE・FREE_TEXT 語彙 / 送信 optionIds・textResponse / hasResponded 不在）だったため、
 * 実BEではメンバーがアンケート回答画面に到達できず「締切・結果非公開」ロックに誤分岐していた。
 * useSurveyApi の BE↔FE 翻訳層（adaptDetail / adaptSubmit / adaptMyResponse）で根治。
 * 本specは「実ブラウザが回答画面を描画し、実BEへ回答が永続化される」ことを担保する。
 *
 * 前提: 実BE(API_BASE_URL, 既定 http://localhost:8080) / 実FE(BASE_URL) が起動済み。
 *   - 実FEのオリジンはBEのCORS許可リスト（既定 :3000 / :3001）に含まれること。
 *   - WSL2 等で browser↔BE 直結が不安定な環境では、playwright config 側で
 *     localhost のプロキシバイパスを設定すること（本ファイルは環境非依存に保つ）。
 * 認証: e2e-user@test.mannschaft.local / TestPass2026!（team fc-u-18 の MEMBER）。
 */
import { test, expect, type APIRequestContext } from '@playwright/test'

const BACKEND_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const TEAM_SLUG = 'fc-u-18'

async function loginToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
  const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok()) return null
  return (await res.json())?.data?.accessToken ?? null
}

async function backendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5000 })
    return (await res.json())?.status === 'UP'
  } catch {
    return false
  }
}

test.describe('SURVEY-REAL: アンケート回答フロー実機一気通貫', () => {
  let adminToken: string
  let surveyId: number

  test.beforeAll(async ({ request }) => {
    test.skip(!(await backendAlive(request)), 'BE 未起動のためスキップ')
    const at = await loginToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    test.skip(!at, 'admin ログイン不可（認証ドリフト/ロック）のためスキップ')
    adminToken = at!
    // 公開中アンケートを作成（ALL配信・非匿名・AFTER_RESPONSE・SINGLE_CHOICE + FREE_TEXT + SCALE）
    const create = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys`, {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      data: {
        title: `【実機E2E自動】回答フロー回帰 ${Date.now()}`,
        description: '実機E2E回答フロー回帰。afterAllで削除。',
        isAnonymous: false,
        allowMultipleSubmissions: false,
        resultsVisibility: 'AFTER_RESPONSE',
        distributionMode: 'ALL',
        unrespondedVisibility: 'ALL_MEMBERS',
        questions: [
          { questionType: 'SINGLE_CHOICE', questionText: '好きな季節は？', isRequired: true, displayOrder: 1,
            options: [{ optionText: '春', displayOrder: 1 }, { optionText: '夏', displayOrder: 2 }] },
          { questionType: 'FREE_TEXT', questionText: 'ひとこと', isRequired: false, displayOrder: 2 },
          { questionType: 'SCALE', questionText: '満足度', isRequired: true, displayOrder: 3, scaleMin: 1, scaleMax: 5 },
        ],
      },
    })
    expect(create.status(), 'アンケート作成').toBe(201)
    // Issue #2635 で作成 POST のレスポンスがフラット化され data.survey の入れ子が消えた。
    surveyId = (await create.json())?.data?.id
    expect(surveyId, 'surveyId').toBeTruthy()
    const pub = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys/${surveyId}/publish`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(pub.ok(), 'アンケート公開').toBeTruthy()
  })

  test.afterAll(async ({ request }) => {
    if (surveyId && adminToken) {
      await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_SLUG}/surveys/${surveyId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      }).catch(() => {})
    }
  })

  test('SURVEY-REAL-1: メンバーが回答画面に到達→各設問入力→送信201（翻訳層根治の回帰）', async ({ page }) => {
    // UIログイン
    await page.goto('/login')
    await page.getByRole('button', { name: 'ログイン', exact: true }).waitFor({ timeout: 20000 })
    await page.locator('input#email').fill(E2E_USER.email)
    await page.locator('input[type="password"]').fill(E2E_USER.password)
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()
    await page.waitForURL(/\/my\/|\/dashboard/, { timeout: 30000 })

    // 回答ページへ直接遷移
    await page.goto(`/surveys/${surveyId}?scope=team&scopeId=${TEAM_SLUG}`)

    // AC-1: 回答フォームが表示される（締切・結果非公開ロックに誤分岐しない）
    await expect(page.getByTestId('survey-response-form')).toBeVisible({ timeout: 20000 })

    // AC-2: 各設問種別の入力欄が描画される（SINGLE_CHOICE=radio / FREE_TEXT=textarea / SCALE=rating）
    const radios = page.locator('[data-testid^="response-radio-"]')
    const ratings = page.locator('[data-testid^="response-rating-"]')
    await expect(radios.first()).toBeVisible()
    await expect(ratings.first()).toBeVisible()
    await radios.first().click()
    await ratings.nth(3).click()

    // AC-3: 送信→201（実BEへ永続化）
    const submitResp = page.waitForResponse(
      (r) => r.url().includes(`/surveys/${surveyId}/responses`) && r.request().method() === 'POST',
    )
    await page.getByTestId('survey-response-submit').click()
    const sr = await submitResp
    expect(sr.status(), '回答送信').toBe(201)
  })
})
