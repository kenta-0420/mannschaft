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
let cleanupChildId: number | undefined
let cleanupSeriesStart: number | undefined
let beforeTitle: string

type ScheduleDetail = {
  id: number
  content: { title: string; eventType: string }
  time: { startAt: string; endAt: string }
  detail: { description: string | null }
  recurrence: { recurrenceRule: Record<string, unknown> | null; parentScheduleId: number | null }
}

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function apiLogin(email: string): Promise<string> {
  const response = await api.post(`${API_V1}/auth/login`, { data: { email, password: PASSWORD } })
  expect(response.status(), `${email} のAPIログイン`).toBe(200)
  return (await response.json() as { data: { accessToken: string } }).data.accessToken
}

async function waitForAffectedCount(
  id: number,
  type: 'SCHEDULE_UPDATED' | 'SCHEDULE_RESCHEDULED' = 'SCHEDULE_UPDATED',
): Promise<number | undefined> {
  const deadline = Date.now() + 60_000
  do {
    const response = await api.get(`${API_V1}/dashboard/activity?limit=50`, {
      headers: headers(memberToken),
    })
    expect(response.status()).toBe(200)
    const items = (await response.json() as {
      data: { items: Array<{ type: string; targetId: number; detail?: { affectedCount?: number } }> }
    }).data.items
    const item = items.find(row => row.type === type && row.targetId === id)
    if (item) return item.detail?.affectedCount
    await new Promise(resolve => setTimeout(resolve, 1_000))
  } while (Date.now() < deadline)
  throw new Error(`更新フィードが現れませんでした: scheduleId=${id}`)
}

function toApiLocalDateTime(date: Date): string {
  return date.toISOString().slice(0, 19)
}

function toTokyoMillis(value: string): number {
  const offsetAware = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  return new Date(offsetAware ? value : `${value}+09:00`).getTime()
}

function asTokyoOffset(value: string): string {
  return /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : `${value}+09:00`
}

async function getScheduleDetail(id: number): Promise<ScheduleDetail> {
  const response = await api.get(`${API_V1}/teams/${TEAM_SLUG}/schedules/${id}`, {
    headers: headers(adminToken),
  })
  expect(response.status(), `予定詳細の取得: scheduleId=${id}`).toBe(200)
  return (await response.json() as { data: ScheduleDetail }).data
}

async function getRecurringDetails(title: string, start: number): Promise<ScheduleDetail[]> {
  const from = toApiLocalDateTime(new Date(start - 24 * 60 * 60 * 1000))
  const to = toApiLocalDateTime(new Date(start + 42 * 24 * 60 * 60 * 1000))
  const response = await api.get(
    `${API_V1}/teams/${TEAM_SLUG}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&size=50`,
    { headers: headers(adminToken) },
  )
  expect(response.status(), '繰り返し予定一覧の取得').toBe(200)
  const schedules = (await response.json() as {
    data: Array<{ id: number; content: { title: string } }>
  }).data
  const ids = schedules
    .filter((schedule) => schedule.content.title === title)
    .map((schedule) => schedule.id)
  expect(ids, '作成した繰り返し予定は親を含む5件').toHaveLength(5)
  return Promise.all(ids.map(getScheduleDetail))
}

function rememberCleanupChild(details: ScheduleDetail[]): void {
  const child = details.find(detail => detail.id !== scheduleId)
  expect(child, '自己作成した繰り返し予定の子を後始末用に記録する').toBeDefined()
  cleanupChildId = child?.id
}

async function findOwnedCleanupChild(): Promise<number | undefined> {
  if (!scheduleId || !cleanupSeriesStart) return undefined
  const from = toApiLocalDateTime(new Date(cleanupSeriesStart - 24 * 60 * 60 * 1000))
  const to = toApiLocalDateTime(new Date(cleanupSeriesStart + 42 * 24 * 60 * 60 * 1000))
  try {
    const response = await api.get(
      `${API_V1}/teams/${TEAM_SLUG}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&size=50`,
      { headers: headers(adminToken) },
    )
    if (response.status() !== 200) return undefined
    const schedules = (await response.json() as {
      data: Array<{ id: number; content: { title: string } }>
    }).data
    const candidates = schedules.filter(schedule => schedule.content.title === beforeTitle)
    for (const candidate of candidates) {
      const detail = await getScheduleDetail(candidate.id)
      if (detail.recurrence.parentScheduleId === scheduleId) return detail.id
    }
  } catch (error) {
    console.warn('CMP107 cleanup child verification failed', error)
    return undefined
  }
  return undefined
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(300_000)
  api = await pwRequest.newContext()
  adminToken = await apiLogin(ADMIN.email)
  memberToken = await apiLogin('e2e-user@test.mannschaft.local')
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  page = await context.newPage()
  await loginViaApi(page, ADMIN, { apiBaseUrl: API, deferNavigation: true })
})

test.afterEach(async () => {
  const cleanupId = cleanupChildId ?? await findOwnedCleanupChild()
  if (cleanupId) {
    const cleanup = await api.delete(`${API_V1}/teams/${TEAM_SLUG}/schedules/${cleanupId}?updateScope=ALL`, {
      headers: headers(adminToken),
    })
    expect(cleanup.status(), '作成した繰り返し予定だけを削除する').toBe(204)
    scheduleId = undefined
    cleanupChildId = undefined
    cleanupSeriesStart = undefined
  }
  if (!cleanupId && scheduleId) {
    const parentId = scheduleId
    const cleanup = await api.delete(
      `${API_V1}/teams/${TEAM_SLUG}/schedules/${parentId}?updateScope=THIS_ONLY`,
      { headers: headers(adminToken) },
    )
    scheduleId = undefined
    cleanupChildId = undefined
    cleanupSeriesStart = undefined
    expect(cleanup.status()).toBe(204)
    throw new Error('CMP107 cleanup failed: self-created child could not be verified for ALL deletion')
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
      recurrenceRule: {
        type: 'WEEKLY',
        interval: 1,
        daysOfWeek: ['MONDAY'],
        endType: 'COUNT',
        count: 4,
      },
    },
  })
  expect(create.status(), '繰り返し予定作成').toBe(201)
  scheduleId = (await create.json() as { data: { id: number } }).data.id
  cleanupSeriesStart = start
  rememberCleanupChild(await getRecurringDetails(beforeTitle, start))
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
  expect(await page.evaluate(() => matchMedia('(max-width: 767px)').matches), '実ブラウザーのモバイル表示条件').toBe(true)
  const openedDetail = page.waitForResponse(response =>
    response.request().method() === 'GET'
    && /\/schedules\/\d+(?:\?|$)/.test(response.url()),
  { timeout: 90_000 })
  await row.getByRole('button', { name: beforeTitle }).click()
  const detailResponse = await openedDetail
  const detailFailure = detailResponse.status() === 200 ? '' : (await detailResponse.text()).slice(0, 300)
  expect(detailResponse.status(), `モバイル行の予定詳細取得: ${detailResponse.url()} ${detailFailure}`).toBe(200)

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
  const failure = response.status() === 200 ? '' : (await response.text()).slice(0, 300)
  expect(response.status(), `UI更新リクエスト: ${failure}`).toBe(200)
  const updateBody = response.request().postDataJSON() as Record<string, unknown>
  expect(updateBody.description, '編集で既存説明を維持する').toBe('CMP107詳細-保持')
  expect(updateBody).not.toHaveProperty('eventType')
  expect(updateBody).not.toHaveProperty('scheduledSurveys')
  expect(updateBody).not.toHaveProperty('scheduledAttendance')
  expect(updateBody).not.toHaveProperty('reminders')
  expect(updateBody).not.toHaveProperty('recurrenceRule')
  expect(updateBody).not.toHaveProperty('startAt')
  expect(updateBody).not.toHaveProperty('endAt')
  await expect(page.getByText(after, { exact: true }).first(), '更新後タイトルが画面へ反映される')
    .toBeVisible({ timeout: 30_000 })
  expect(await waitForAffectedCount(scheduleId!)).toBe(5)
})

test('CMP107-ADMIN-SHIFT: モバイル実画面で時刻を:07から:15へ変更し、5件を+8分する', async () => {
  const startDate = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000)
  startDate.setUTCMinutes(7, 0, 0)
  const start = startDate.getTime()
  beforeTitle = `CMP107-時刻変更-${Date.now()}`
  const description = 'CMP107時刻変更-詳細保持'
  const create = await api.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: headers(adminToken),
    data: {
      title: beforeTitle,
      description,
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
  expect(create.status(), '時刻変更用の繰り返し予定作成').toBe(201)
  scheduleId = (await create.json() as { data: { id: number } }).data.id
  cleanupSeriesStart = start
  const beforeDetails = await getRecurringDetails(beforeTitle, start)
  rememberCleanupChild(beforeDetails)
  const beforeById = new Map(beforeDetails.map((detail) => [detail.id, detail]))
  const rootBefore = beforeById.get(scheduleId)
  expect(rootBefore, '親予定の詳細').toBeDefined()

  await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(page.getByTestId('schedule-list-view')).toBeVisible({ timeout: 120_000 })
  await waitForSpinnerGone(page)
  if (new Date(start).getMonth() !== new Date().getMonth()) {
    await page.getByRole('button', { name: '次の月' }).click()
    await waitForSpinnerGone(page)
  }
  const row = page.getByTestId('schedule-list-row').filter({ hasText: beforeTitle }).first()
  await expect(row, '時刻変更対象の予定がモバイル一覧にある').toBeVisible({
    timeout: 120_000,
  })
  await row.getByRole('button', { name: beforeTitle }).click()
  const detail = page.getByRole('dialog', { name: beforeTitle })
  await expect(detail).toBeVisible({ timeout: 30_000 })
  await detail.locator('button:has(.pi-pencil)').click()

  const scope = page.getByTestId('schedule-update-scope-THIS_AND_FOLLOWING')
  await expect(scope).toBeVisible()
  await scope.click()
  const editDialog = page.getByRole('dialog', { name: 'イベントを編集' })
  const selects = editDialog.locator('.p-select')
  const startLabel = await selects.nth(0).locator('.p-select-label').textContent()
  const endLabel = await selects.nth(1).locator('.p-select-label').textContent()
  expect(startLabel, '開始時刻は:07で読み込まれる').toMatch(/:07$/)
  expect(endLabel, '終了時刻は:07で読み込まれる').toMatch(/:07$/)
  const updatedStart = startLabel!.replace(/:07$/, ':15')
  const updatedEnd = endLabel!.replace(/:07$/, ':15')
  await selects.nth(0).click()
  await page.getByRole('option', { name: updatedStart, exact: true }).click()
  await expect(selects.nth(1).locator('.p-select-label'), '開始時刻に連動して終了時刻も+8分').toHaveText(updatedEnd)

  const updateResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' &&
      response
        .url()
        .includes(`/teams/${TEAM_SLUG}/schedules/${scheduleId}?updateScope=THIS_AND_FOLLOWING`),
  )
  await page.getByRole('button', { name: '更新', exact: true }).click()
  const response = await updateResponse
  const failure = response.status() === 200 ? '' : (await response.text()).slice(0, 300)
  expect(response.status(), `時刻変更のUI更新リクエスト ${failure}`).toBe(200)
  const updateBody = response.request().postDataJSON() as Record<string, string>
  expect(
    toTokyoMillis(updateBody.startAt) - toTokyoMillis(rootBefore!.time.startAt),
    'PATCH開始日時は+8分',
  ).toBe(8 * 60 * 1000)
  expect(
    toTokyoMillis(updateBody.endAt) - toTokyoMillis(rootBefore!.time.endAt),
    'PATCH終了日時は+8分',
  ).toBe(8 * 60 * 1000)

  const afterDetails = await getRecurringDetails(beforeTitle, start)
  for (const after of afterDetails) {
    const before = beforeById.get(after.id)
    expect(before, `更新前の子予定が対応する: scheduleId=${after.id}`).toBeDefined()
    expect(
      toTokyoMillis(after.time.startAt) - toTokyoMillis(before!.time.startAt),
      `開始日時が+8分: scheduleId=${after.id}`,
    ).toBe(8 * 60 * 1000)
    expect(
      toTokyoMillis(after.time.endAt) - toTokyoMillis(before!.time.endAt),
      `終了日時が+8分: scheduleId=${after.id}`,
    ).toBe(8 * 60 * 1000)
    expect(after.detail.description, `説明を維持: scheduleId=${after.id}`).toBe(
      before!.detail.description,
    )
    expect(after.content.eventType, `種別を維持: scheduleId=${after.id}`).toBe(
      before!.content.eventType,
    )
    expect(after.recurrence.recurrenceRule, `繰り返しルールを維持: scheduleId=${after.id}`).toEqual(
      before!.recurrence.recurrenceRule,
    )
  }
  expect(await waitForAffectedCount(scheduleId!, 'SCHEDULE_RESCHEDULED')).toBe(5)
})

test('CMP107-ADMIN-CONFLICT: 削除済み子回と重なるこの回以降の時刻変更を409で拒否する', async () => {
  const startDate = new Date(Date.now() + 21 * 24 * 60 * 60 * 1000)
  startDate.setUTCMinutes(7, 0, 0)
  const start = startDate.getTime()
  beforeTitle = `CMP107-時刻衝突-${Date.now()}`
  const create = await api.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: headers(adminToken),
    data: {
      title: beforeTitle,
      description: 'CMP107時刻衝突-詳細保持',
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
  expect(create.status(), '時刻衝突用の繰り返し予定作成').toBe(201)
  scheduleId = (await create.json() as { data: { id: number } }).data.id
  cleanupSeriesStart = start
  const beforeDetails = await getRecurringDetails(beforeTitle, start)
  rememberCleanupChild(beforeDetails)
  const children = beforeDetails
    .filter(detail => detail.id !== scheduleId)
    .sort((left, right) => toTokyoMillis(left.time.startAt) - toTokyoMillis(right.time.startAt))
  expect(children, '親以外の子回は4件').toHaveLength(4)
  const precedingChild = children[0]!
  const deletedChild = children[1]!

  const deleteResponse = await api.delete(
    `${API_V1}/teams/${TEAM_SLUG}/schedules/${deletedChild.id}?updateScope=THIS_ONLY`,
    { headers: headers(adminToken) },
  )
  expect(deleteResponse.status(), '子回のみを論理削除する').toBe(204)

  const conflictResponse = await api.patch(
    `${API_V1}/teams/${TEAM_SLUG}/schedules/${precedingChild.id}?updateScope=THIS_AND_FOLLOWING`,
    {
      headers: headers(adminToken),
      data: {
        title: precedingChild.content.title,
        startAt: asTokyoOffset(deletedChild.time.startAt),
        endAt: asTokyoOffset(deletedChild.time.endAt),
      },
    },
  )
  const conflictBody = await conflictResponse.json() as { error?: { code?: string } }
  expect(conflictResponse.status(), '削除済み子回と重なる一括時刻更新は409').toBe(409)
  expect(conflictBody.error?.code, '繰り返し開始日時の重複コード').toBe('SCHEDULE_023')

  for (const before of beforeDetails.filter(detail => detail.id !== deletedChild.id)) {
    const after = await getScheduleDetail(before.id)
    expect(after.content.title, `衝突拒否後もタイトル不変: scheduleId=${before.id}`).toBe(before.content.title)
    expect(after.time.startAt, `衝突拒否後も開始日時不変: scheduleId=${before.id}`).toBe(before.time.startAt)
    expect(after.time.endAt, `衝突拒否後も終了日時不変: scheduleId=${before.id}`).toBe(before.time.endAt)
  }
})
