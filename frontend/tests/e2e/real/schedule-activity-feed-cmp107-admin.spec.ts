/** CMP-107 管理者視点 — 実UIで「この回以降」を選択して更新する。 */
import { expect, request as pwRequest, test, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const API_V1 = `${API}/api/v1`
const TEAM_SLUG = 'fc-u-18'
const PASSWORD = 'TestPass2026!'
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD }

test.describe.configure({ mode: 'serial' })
// 実機の初回描画では大量の既存予定を読み込むため、操作と更新フィード確認を含めて待つ。
test.setTimeout(900_000)

let api: APIRequestContext
let page: Page
let adminToken: string
let memberToken: string
let scheduleId: number | undefined
let beforeTitle: string

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function apiLogin(email: string): Promise<string> {
  const response = await api.post(`${API_V1}/auth/login`, { data: { email, password: PASSWORD } })
  expect(response.status(), `${email} のAPIログイン`).toBe(200)
  return (await response.json() as { data: { accessToken: string } }).data.accessToken
}

async function waitForAffectedCount(id: number): Promise<number | undefined> {
  const deadline = Date.now() + 60_000
  do {
    const response = await api.get(`${API_V1}/dashboard/activity?limit=50`, {
      headers: headers(memberToken),
    })
    expect(response.status()).toBe(200)
    const items = (await response.json() as {
      data: { items: Array<{ type: string; targetId: number; detail?: { affectedCount?: number } }> }
    }).data.items
    const item = items.find(row => row.type === 'SCHEDULE_UPDATED' && row.targetId === id)
    if (item) return item.detail?.affectedCount
    await new Promise(resolve => setTimeout(resolve, 1_000))
  } while (Date.now() < deadline)
  throw new Error(`更新フィードが現れませんでした: scheduleId=${id}`)
}

test.beforeAll(async ({ browser }) => {
  api = await pwRequest.newContext()
  adminToken = await apiLogin(ADMIN.email)
  memberToken = await apiLogin('e2e-user@test.mannschaft.local')
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  page = await context.newPage()
  await loginViaApi(page, ADMIN, { apiBaseUrl: API })
})

test.afterEach(async () => {
  if (scheduleId) {
    const cleanup = await api.delete(`${API_V1}/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=ALL`, {
      headers: headers(adminToken),
    })
    expect(cleanup.status(), '作成した繰り返し予定だけを削除する').toBe(204)
    scheduleId = undefined
  }
})

test.afterAll(async () => {
  await page?.context().close()
  await api?.dispose()
})

test('CMP107-ADMIN: モバイル実画面でこの回以降を選択し、5件更新する', async () => {
  const startDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
  startDate.setUTCMinutes(7, 0, 0)
  const start = startDate.getTime()
  beforeTitle = `CMP107-管理者更新前-${Date.now()}`
  const create = await api.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: headers(adminToken),
    data: {
      title: beforeTitle,
      description: 'CMP107詳細-保持',
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
  expect(create.status(), '繰り返し予定作成').toBe(201)
  scheduleId = (await create.json() as { data: { id: number } }).data.id
  const after = `CMP107-管理者更新後-${Date.now()}`

  await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(page.getByTestId('schedule-list-view'), 'カレンダーの予定一覧が描画された').toBeVisible({ timeout: 120_000 })
  await waitForSpinnerGone(page)
  const row = page.getByTestId('schedule-list-row').filter({ hasText: beforeTitle }).first()
  // 開始日+7日が翌月に入る月末でも、実UIの月送りで対象を開く。
  if (new Date(start).getMonth() !== new Date().getMonth()) {
    await page.getByRole('button', { name: '次の月' }).click()
    await waitForSpinnerGone(page)
  }
  await expect(row, '対象予定が当月または翌月の一覧に現れる').toBeVisible({ timeout: 120_000 })
  await row.getByRole('button', { name: beforeTitle }).click()

  const detail = page.getByRole('dialog', { name: beforeTitle })
  await expect(detail, '予定詳細ダイアログが開く').toBeVisible({ timeout: 30_000 })
  await expect(detail.getByText('CMP107詳細-保持'), '詳細APIの説明文をモバイルに表示する').toBeVisible()
  const editButton = detail.locator('button:has(.pi-pencil)')
  await expect(editButton, '管理者には編集ボタンが見える').toBeVisible({ timeout: 30_000 })
  await editButton.click()

  const scope = page.getByTestId('schedule-update-scope-THIS_AND_FOLLOWING')
  await expect(scope).toBeVisible()
  expect(await scope.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
  await scope.click()
  await expect(scope).toHaveAttribute('aria-pressed', 'true')
  // 本番の基本入力欄には title-input testid が無い（ユニットテストのstub専用）。
  const editDialog = page.getByRole('dialog', { name: 'イベントを編集' })
  await expect(editDialog.locator('.p-select-label').filter({ hasText: /:07$/ }),
    '15分刻み以外の既存開始・終了時刻を編集画面で保持する').toHaveCount(2)
  const titleInput = editDialog.getByRole('textbox').first()
  await expect(titleInput).toHaveValue(beforeTitle)
  await titleInput.fill(after)

  const updateResponse = page.waitForResponse(response =>
    response.request().method() === 'PATCH'
    && response.url().includes(`/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=THIS_AND_FOLLOWING`),
  )
  await page.getByRole('button', { name: '更新', exact: true }).click()
  const response = await updateResponse
  expect(response.status(), 'UI更新リクエスト').toBe(200)
  const updateBody = response.request().postDataJSON() as Record<string, unknown>
  expect(updateBody.description, '編集で既存説明を維持する').toBe('CMP107詳細-保持')
  expect(updateBody).not.toHaveProperty('eventType')
  expect(updateBody).not.toHaveProperty('scheduledSurveys')
  expect(updateBody).not.toHaveProperty('scheduledAttendance')
  expect(updateBody).not.toHaveProperty('reminders')
  expect(updateBody).not.toHaveProperty('recurrenceRule')
  await expect(page.getByText(after, { exact: true }).first(), '更新後タイトルが画面へ反映される')
    .toBeVisible({ timeout: 30_000 })
  expect(await waitForAffectedCount(scheduleId!)).toBe(5)
})
