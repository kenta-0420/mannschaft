import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const FRONTEND_BASE = process.env.BASE_URL ?? 'http://localhost:3000'
const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}

interface TeamFixture {
  slug: string
  name: string
  surveyId: number
}

let api: APIRequestContext
let accessToken = ''
const teams: TeamFixture[] = []

function headers(): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' }
}

/** 任意ポートで起動した実機FEのXHRをNode経由でBEへ中継し、ローカルCORS制約だけを除く。 */
async function setupApiBridge(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const browserRequest = route.request()
    if (browserRequest.method() === 'OPTIONS') {
      await route.fulfill({ status: 204 })
      return
    }
    const url = browserRequest.url().replace(/^https?:\/\/[^/]+/, API_BASE)
    const forwardedHeaders = Object.fromEntries(
      Object.entries(browserRequest.headers()).filter(
        ([name]) => !['origin', 'referer', 'host'].includes(name.toLowerCase()),
      ),
    )
    const response = await fetch(url, {
      method: browserRequest.method(),
      headers: forwardedHeaders,
      body: browserRequest.postData() ?? undefined,
    })
    const responseHeaders = Object.fromEntries(response.headers.entries())
    delete responseHeaders['content-encoding']
    delete responseHeaders['content-length']
    delete responseHeaders['transfer-encoding']
    responseHeaders['access-control-allow-origin'] = FRONTEND_BASE
    responseHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({
      status: response.status,
      headers: responseHeaders,
      body: Buffer.from(await response.arrayBuffer()),
    })
  })
}

async function createTeamWithSurvey(label: string): Promise<TeamFixture> {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const name = `CMP-1210 ${label} ${suffix}`
  const teamResponse = await api.post('/api/v1/teams', {
    headers: headers(),
    data: {
      name,
      slug: `cmp1210-${label.toLowerCase()}-${suffix}`.slice(0, 30),
      visibility: 'MEMBERS_AND_ABOVE',
    },
  })
  expect(teamResponse.status(), await teamResponse.text()).toBe(201)
  const { slug } = ((await teamResponse.json()) as { data: { slug: string } }).data

  const surveyResponse = await api.post(`/api/v1/teams/${slug}/surveys`, {
    headers: headers(),
    data: {
      title: `CMP-1210 ${label} survey`,
      isAnonymous: false,
      allowMultipleSubmissions: false,
      distributionMode: 'ALL',
      resultsVisibility: 'AFTER_RESPONSE',
      unrespondedVisibility: 'CREATOR_AND_ADMIN',
      questions: [],
    },
  })
  expect(surveyResponse.status(), await surveyResponse.text()).toBe(201)
  const surveyId = ((await surveyResponse.json()) as { data: { id: number } }).data.id
  return { slug, name, surveyId }
}

test.beforeAll(async () => {
  api = await request.newContext({ baseURL: API_BASE })
  const loginResponse = await api.post('/api/v1/auth/login', { data: ADMIN })
  expect(loginResponse.status(), await loginResponse.text()).toBe(200)
  accessToken = ((await loginResponse.json()) as { data: { accessToken: string } }).data.accessToken
  teams.push(await createTeamWithSurvey('A'))
  teams.push(await createTeamWithSurvey('B'))
})

test.afterAll(async () => {
  for (const team of [...teams].reverse()) {
    await api.delete(`/api/v1/teams/${team.slug}/surveys/${team.surveyId}`, {
      headers: headers(),
    })
    await api.delete(`/api/v1/teams/${team.slug}`, { headers: headers() })
  }
  await api.dispose()
})

test('CMP-1210: 2スコープ間の遷移で管理対象名を更新し、以前の名称を残さない', async ({ page }) => {
  const [first, second] = teams
  expect(first).toBeDefined()
  expect(second).toBeDefined()
  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE })
  await setupApiBridge(page)

  await page.goto(`/surveys/${first!.surveyId}?scope=team&scopeId=${first!.slug}`, {
    waitUntil: 'commit',
  })
  await expect(page.getByTestId('survey-scope-type')).toHaveText('対象チーム', { timeout: 30_000 })
  await expect(page.getByTestId('survey-scope-name')).toHaveText(first!.name)
  await expect(page.getByTestId('survey-management-context')).toBeVisible()

  await page.evaluate(async (url) => {
    const root = document.querySelector('#__nuxt') as
      | (HTMLElement & {
          __vue_app__?: {
            config: { globalProperties: { $router?: { push: (path: string) => Promise<void> } } }
          }
        })
      | null
    const router = root?.__vue_app__?.config.globalProperties.$router
    if (!router) throw new Error('Vue router is unavailable')
    await router.push(url)
  }, `/surveys/${second!.surveyId}?scope=team&scopeId=${second!.slug}`)

  await expect(page.getByTestId('survey-scope-name')).toHaveText(second!.name, { timeout: 30_000 })
  await expect(page.getByText(first!.name, { exact: true })).toHaveCount(0)
  await expect(page.getByTestId('survey-management-context')).toBeVisible()
})
