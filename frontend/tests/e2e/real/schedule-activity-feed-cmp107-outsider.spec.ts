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
let scheduleId: number | undefined
const title = `CMP107-非所属秘匿-${Date.now()}`

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

test.beforeAll(async ({ browser }) => {
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
  const update = await api.patch(
    `${API_V1}/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=THIS_AND_FOLLOWING`,
    { headers: headers(adminToken), data: { title: `${title}-更新後` } },
  )
  expect(update.status()).toBe(200)

  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  page = await context.newPage()
  await loginViaApi(page, {
    email: 'e2e-outsider@test.mannschaft.local',
    password: PASSWORD,
  }, { apiBaseUrl: API })
})

test.afterAll(async () => {
  if (scheduleId) {
    const cleanup = await api.delete(`${API_V1}/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=ALL`, {
      headers: headers(adminToken),
    })
    expect(cleanup.status(), '作成した繰り返し予定だけを削除する').toBe(204)
  }
  await page?.context().close()
  await api?.dispose()
})

test('CMP107-OUTSIDER: 他チームの一括更新予定も件数も表示されない', async () => {
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
