/** CMP-107 非所属者視点 — 対象予定とフィード件数を実画面へ露出しない。 */
import { expect, request as pwRequest, test, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const API_V1 = `${API}/api/v1`
const TEAM_SLUG = 'fc-u-18'
const PASSWORD = 'TestPass2026!'

test.setTimeout(360_000)

let api: APIRequestContext
let page: Page
let adminToken: string
let outsiderToken: string
let scheduleId: number | undefined
let cleanupChildId: number | undefined
const title = `CMP107-非所属秘匿-${Date.now()}`

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(300_000)
  api = await pwRequest.newContext()
  const login = await api.post(`${API_V1}/auth/login`, {
    data: { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD },
  })
  expect(login.status()).toBe(200)
  adminToken = (await login.json() as { data: { accessToken: string } }).data.accessToken

  const start = Date.now() + 7 * 24 * 60 * 60 * 1000
  const create = await api.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: headers(adminToken),
    data: {
      title,
      startAt: new Date(start).toISOString(),
      endAt: new Date(start + 3_600_000).toISOString(),
      allDay: false,
      eventType: 'PRACTICE',
      visibility: 'MEMBERS_ONLY',
      minViewRole: 'ANYONE',
      attendanceRequired: false,
      recurrenceRule: { type: 'WEEKLY', interval: 1, daysOfWeek: ['MONDAY'], endType: 'COUNT', count: 4 },
    },
  })
  expect(create.status()).toBe(201)
  scheduleId = (await create.json() as { data: { id: number } }).data.id
  const from = new Date(start - 24 * 60 * 60 * 1000).toISOString().slice(0, 19)
  const to = new Date(start + 35 * 24 * 60 * 60 * 1000).toISOString().slice(0, 19)
  const list = await api.get(`${API_V1}/teams/${TEAM_SLUG}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, {
    headers: headers(adminToken),
  })
  expect(list.status(), '自作予定の子回確認').toBe(200)
  const siblings = (await list.json() as {
    data: Array<{ id: number; content: { title: string } }>
  }).data.filter(row => row.content.title === title && row.id !== scheduleId)
  expect(siblings, '片付け対象の自作子回').toHaveLength(4)
  cleanupChildId = siblings[0]?.id
  const update = await api.patch(
    `${API_V1}/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=THIS_AND_FOLLOWING`,
    { headers: headers(adminToken), data: { title: `${title}-更新後` } },
  )
  expect(update.status()).toBe(200)

  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  page = await context.newPage()
  const outsiderLogin = await api.post(`${API_V1}/auth/login`, {
    data: { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD },
  })
  expect(outsiderLogin.status(), '非所属ユーザー認証').toBe(200)
  outsiderToken = (await outsiderLogin.json() as { data: { accessToken: string } }).data.accessToken
  await loginViaApi(page, {
    email: 'e2e-outsider@test.mannschaft.local',
    password: PASSWORD,
  }, { apiBaseUrl: API, deferNavigation: true })
})

test.afterAll(async () => {
  if (cleanupChildId) {
    const cleanup = await api.delete(`${API_V1}/teams/${TEAM_SLUG}/schedules/${cleanupChildId}?updateScope=ALL`, {
      headers: headers(adminToken),
    })
    expect(cleanup.status(), '作成した繰り返し予定だけを削除する').toBe(204)
  }
  await page?.context().close()
  await api?.dispose()
})

test('CMP107-OUTSIDER: 他チームの一括更新予定も件数も表示されない', async () => {
  const feed = await api.get(`${API_V1}/dashboard/activity?limit=50`, {
    headers: headers(outsiderToken),
  })
  expect(feed.status(), '非所属ユーザーのダッシュボードAPIが正常に動作する').toBe(200)
  const activity = (await feed.json() as {
    data: { items: Array<{ targetId: number }> }
  }).data.items
  expect(activity.some(item => item.targetId === scheduleId), '対象予定の更新履歴はAPIにも漏れない').toBe(false)
  const directSchedule = await api.get(`${API_V1}/teams/${TEAM_SLUG}/schedules/${scheduleId}`, {
    headers: headers(outsiderToken),
  })
  expect([403, 404], '非所属ユーザーは対象予定の詳細を取得できない').toContain(directSchedule.status())
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByTestId('scope-next'), 'ダッシュボード本体が描画された').toBeVisible({ timeout: 120_000 })
  await expect(page.getByText(title, { exact: false })).toHaveCount(0)

  await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await expect(page).toHaveURL(/\/calendar/)
  await expect(page.getByTestId('schedule-list-view'), 'カレンダー本体が描画された').toBeVisible({ timeout: 120_000 })
  await expect(page.getByText(title, { exact: false })).toHaveCount(0)
})
