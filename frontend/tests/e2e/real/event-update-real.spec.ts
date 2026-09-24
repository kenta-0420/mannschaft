/**
 * CMP-007: チームイベント更新の実機E2E・認可境界試験。
 * AWS/R2 を含む外部サービスには接続せず、ローカルの API・ブラウザ・MySQL のみを使う。
 */
import {
  expect,
  request,
  test,
  type APIRequestContext,
  type APIResponse,
  type Response,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'
const PASSWORD = 'TestPass2026!'
const ADMIN = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const MEMBER = { email: 'e2e-supporter@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

interface LoginResult {
  accessToken: string
  userId: number
}

interface TeamFixture {
  id: number
  slug: string
}

interface EventFixture {
  id: number
  subtitle: string
}

interface EventDetail {
  id: number
  content?: { subtitle?: string }
}

interface TimetableFixture {
  id: number
  eventId: number
  title: string
}

interface EventListResponse {
  data: Array<{ id: number }>
  meta: { total: number }
}

let adminApi: APIRequestContext
let memberApi: APIRequestContext
let outsiderApi: APIRequestContext
let unauthenticatedApi: APIRequestContext
let adminLogin: LoginResult
let memberLogin: LoginResult
let outsiderLogin: LoginResult
let targetTeam: TeamFixture
let foreignTeam: TeamFixture
let targetEvent: EventFixture
let foreignEvent: EventFixture
let targetTimetable: TimetableFixture
let foreignTimetable: TimetableFixture

function authorization(loginResult: LoginResult): Record<string, string> {
  return {
    Authorization: `Bearer ${loginResult.accessToken}`,
    'Content-Type': 'application/json',
  }
}

async function login(api: APIRequestContext, credentials: typeof ADMIN): Promise<LoginResult> {
  const response = await api.post('/api/v1/auth/login', { data: credentials })
  expect(response.status(), `${credentials.email} のログイン`).toBe(200)
  return ((await response.json()) as { data: LoginResult }).data
}

async function createTeam(
  api: APIRequestContext,
  loginResult: LoginResult,
  prefix: string,
): Promise<TeamFixture> {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const response = await api.post('/api/v1/teams', {
    headers: authorization(loginResult),
    data: {
      name: `${prefix}-${suffix}`,
      slug: `${prefix}-${suffix}`.toLowerCase().slice(0, 30),
      visibility: 'MEMBERS_AND_ABOVE',
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  const body = (await response.json()) as { data: { numericId: number; slug: string } }
  return { id: body.data.numericId, slug: body.data.slug }
}

async function createEvent(
  api: APIRequestContext,
  loginResult: LoginResult,
  team: TeamFixture,
  subtitle: string,
): Promise<EventFixture> {
  const response = await api.post(`/api/v1/teams/${team.slug}/events`, {
    headers: authorization(loginResult),
    data: { subtitle, attendanceMode: 'NONE' },
  })
  expect(response.status(), await response.text()).toBe(201)
  const body = (await response.json()) as { data: EventDetail }
  return { id: body.data.id, subtitle }
}

async function getEvent(
  api: APIRequestContext,
  loginResult: LoginResult,
  team: TeamFixture,
  eventId: number,
): Promise<EventDetail> {
  const response = await api.get(`/api/v1/teams/${team.slug}/events/${eventId}`, {
    headers: authorization(loginResult),
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()) as { data: EventDetail }).data
}

async function listEvents(
  api: APIRequestContext,
  loginResult: LoginResult,
  team: TeamFixture,
): Promise<EventListResponse> {
  const response = await api.get(`/api/v1/teams/${team.slug}/events?page=0&size=100`, {
    headers: authorization(loginResult),
  })
  expect(response.status(), await response.text()).toBe(200)
  return await response.json() as EventListResponse
}

async function createTimetableItem(
  api: APIRequestContext,
  loginResult: LoginResult,
  event: EventFixture,
  title: string,
): Promise<TimetableFixture> {
  const response = await api.post(`/api/v1/events/${event.id}/timetable`, {
    headers: authorization(loginResult),
    data: { title, sortOrder: 0 },
  })
  expect(response.status(), await response.text()).toBe(201)
  return ((await response.json()) as { data: TimetableFixture }).data
}

async function listTimetableItems(
  api: APIRequestContext,
  loginResult: LoginResult,
  event: EventFixture,
): Promise<TimetableFixture[]> {
  const response = await api.get(`/api/v1/events/${event.id}/timetable`, {
    headers: authorization(loginResult),
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()) as { data: TimetableFixture[] }).data
}

async function expectStatus(response: APIResponse | Response, expected: number, label: string): Promise<void> {
  expect(response.status(), `${label}: ${await response.text()}`).toBe(expected)
}

async function expectUnchangedTarget(beforeTotal: number): Promise<void> {
  const detail = await getEvent(adminApi, adminLogin, targetTeam, targetEvent.id)
  expect(detail.id, '拒否後も対象イベントIDが変わらないこと').toBe(targetEvent.id)
  expect(detail.content?.subtitle, '拒否後も対象subtitleが変わらないこと').toBe(targetEvent.subtitle)

  const list = await listEvents(adminApi, adminLogin, targetTeam)
  expect(list.meta.total, '拒否後も一覧総件数が変わらないこと').toBe(beforeTotal)
  expect(list.data.filter(event => event.id === targetEvent.id), '対象IDが一覧に1件だけあること').toHaveLength(1)
}

async function expectUnchangedForeign(): Promise<void> {
  const detail = await getEvent(outsiderApi, outsiderLogin, foreignTeam, foreignEvent.id)
  expect(detail.id, '拒否後も別チームイベントIDが変わらないこと').toBe(foreignEvent.id)
  expect(detail.content?.subtitle, '拒否後も別チームsubtitleが変わらないこと').toBe(foreignEvent.subtitle)
}

test.beforeAll(async () => {
  adminApi = await request.newContext({ baseURL: API_BASE })
  memberApi = await request.newContext({ baseURL: API_BASE })
  outsiderApi = await request.newContext({ baseURL: API_BASE })
  unauthenticatedApi = await request.newContext({
    baseURL: API_BASE,
    storageState: { cookies: [], origins: [] },
  })

  adminLogin = await login(adminApi, ADMIN)
  memberLogin = await login(memberApi, MEMBER)
  outsiderLogin = await login(outsiderApi, OUTSIDER)

  targetTeam = await createTeam(adminApi, adminLogin, 'cmp007-event')
  foreignTeam = await createTeam(outsiderApi, outsiderLogin, 'cmp007-foreign')

  const invite = await adminApi.post(`/api/v1/teams/${targetTeam.slug}/invite-tokens`, {
    headers: authorization(adminLogin),
    data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
  })
  expect(invite.status(), await invite.text()).toBe(201)
  const inviteToken = ((await invite.json()) as { data: { token: string } }).data.token
  const join = await memberApi.post(`/api/v1/invite/${inviteToken}/join`, {
    headers: authorization(memberLogin),
  })
  expect(join.status(), await join.text()).toBe(200)

  targetEvent = await createEvent(adminApi, adminLogin, targetTeam, `CMP007 更新前 ${Date.now()}`)
  foreignEvent = await createEvent(outsiderApi, outsiderLogin, foreignTeam, `CMP007 別チーム ${Date.now()}`)
  targetTimetable = await createTimetableItem(
    adminApi,
    adminLogin,
    targetEvent,
    `CMP007 第三波更新前 ${Date.now()}`,
  )
  foreignTimetable = await createTimetableItem(
    outsiderApi,
    outsiderLogin,
    foreignEvent,
    `CMP007 第三波別チーム ${Date.now()}`,
  )
})

test.afterAll(async () => {
  const cleanupErrors: unknown[] = []
  const cleanup = async (label: string, action: () => Promise<void>) => {
    try {
      await action()
    } catch (error) {
      cleanupErrors.push(new Error(`${label}: ${error instanceof Error ? error.message : String(error)}`))
    }
  }

  await cleanup('対象タイムテーブル項目の削除', async () => {
    const response = await adminApi.delete(
      `/api/v1/events/${targetEvent.id}/timetable/${targetTimetable.id}`,
      { headers: authorization(adminLogin) },
    )
    await expectStatus(response, 204, '対象タイムテーブル項目の削除')
  })
  await cleanup('別チームタイムテーブル項目の削除', async () => {
    const response = await outsiderApi.delete(
      `/api/v1/events/${foreignEvent.id}/timetable/${foreignTimetable.id}`,
      { headers: authorization(outsiderLogin) },
    )
    await expectStatus(response, 204, '別チームタイムテーブル項目の削除')
  })

  await cleanup('対象イベントの削除', async () => {
    const response = await adminApi.delete(`/api/v1/teams/${targetTeam.slug}/events/${targetEvent.id}`, {
      headers: authorization(adminLogin),
    })
    await expectStatus(response, 204, '対象イベントの削除')
  })
  await cleanup('別チームイベントの削除', async () => {
    const response = await outsiderApi.delete(`/api/v1/teams/${foreignTeam.slug}/events/${foreignEvent.id}`, {
      headers: authorization(outsiderLogin),
    })
    await expectStatus(response, 204, '別チームイベントの削除')
  })
  await cleanup('対象チームの削除', async () => {
    adminLogin = await login(adminApi, ADMIN)
    const response = await adminApi.delete(`/api/v1/teams/${targetTeam.slug}`, {
      headers: authorization(adminLogin),
    })
    await expectStatus(response, 204, '対象チームの削除')
  })
  await cleanup('別チームの削除', async () => {
    outsiderLogin = await login(outsiderApi, OUTSIDER)
    const response = await outsiderApi.delete(`/api/v1/teams/${foreignTeam.slug}`, {
      headers: authorization(outsiderLogin),
    })
    await expectStatus(response, 204, '別チームの削除')
  })

  await Promise.all([
    adminApi?.dispose(),
    memberApi?.dispose(),
    outsiderApi?.dispose(),
    unauthenticatedApi?.dispose(),
  ])
  if (cleanupErrors.length > 0) {
    throw new AggregateError(cleanupErrors, 'CMP-007 実機E2Eのクリーンアップに失敗しました')
  }
})

test('EVENT-UPDATE-REAL-001: 管理者が詳細画面から更新しても同じID・一覧件数を維持する', async ({ page }) => {
  const path = `/api/v1/teams/${targetTeam.slug}/events/${targetEvent.id}`
  const baseline = await listEvents(adminApi, adminLogin, targetTeam)
  const beforeTotal = baseline.meta.total
  expect(baseline.data.filter(event => event.id === targetEvent.id), '更新前に対象IDが1件だけあること').toHaveLength(1)

  await expectStatus(
    await unauthenticatedApi.patch(path, { data: { subtitle: '未認証更新' } }),
    401,
    '未認証更新',
  )
  await expectUnchangedTarget(beforeTotal)

  await expectStatus(
    await memberApi.patch(path, {
      headers: authorization(memberLogin),
      data: { subtitle: 'MEMBER更新' },
    }),
    403,
    '同一チームMEMBERの更新',
  )
  await expectUnchangedTarget(beforeTotal)

  await expectStatus(
    await adminApi.patch(`/api/v1/teams/${targetTeam.slug}/events/${foreignEvent.id}`, {
      headers: authorization(adminLogin),
      data: { subtitle: '別チームID更新' },
    }),
    404,
    '別チームイベントIDの更新',
  )
  await expectUnchangedTarget(beforeTotal)
  await expectUnchangedForeign()

  const updatedSubtitle = `${targetEvent.subtitle} 更新済み`
  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE })
  await page.goto(`/teams/${targetTeam.slug}/events/${targetEvent.id}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)

  const editButton = page.getByTestId('team-event-edit')
  await expect(editButton, '詳細画面に更新ボタンが表示されること').toBeVisible({ timeout: 30_000 })
  await editButton.click()

  const subtitleInput = page.getByTestId('team-event-title-input')
  await expect(subtitleInput, '編集フォームが開くこと').toBeVisible({ timeout: 15_000 })
  await expect(subtitleInput, '既存イベントの読込完了後に編集すること').toHaveValue(targetEvent.subtitle, {
    timeout: 15_000,
  })
  await subtitleInput.fill(updatedSubtitle)
  const updateResponsePromise = page.waitForResponse(
    response => response.request().method() === 'PATCH'
      && response.url().endsWith(`/api/v1/teams/${targetTeam.slug}/events/${targetEvent.id}`),
  )
  await page.getByTestId('team-event-form-submit').click()
  const updateResponse = await updateResponsePromise
  await expectStatus(updateResponse, 200, '画面からのイベント更新')
  const updateBody = (await updateResponse.json()) as { data: EventDetail }
  expect(updateBody.data.id, '画面更新APIが同じイベントIDを返すこと').toBe(targetEvent.id)
  expect(updateBody.data.content?.subtitle, '画面更新APIが更新後subtitleを返すこと').toBe(updatedSubtitle)
  await expect(page.getByText(updatedSubtitle).first(), '更新後subtitleが詳細画面に表示されること').toBeVisible({ timeout: 30_000 })

  const updated = await getEvent(adminApi, adminLogin, targetTeam, targetEvent.id)
  expect(updated.id, '更新後もイベントIDが同一であること').toBe(targetEvent.id)
  expect(updated.content?.subtitle, '更新後subtitleがAPIでも一致すること').toBe(updatedSubtitle)

  const after = await listEvents(adminApi, adminLogin, targetTeam)
  expect(after.meta.total, '更新後も一覧総件数が増えないこと').toBe(beforeTotal)
  expect(after.data.filter(event => event.id === targetEvent.id), '更新後も対象IDが一覧に1件だけあること').toHaveLength(1)
})

test('EVENT-UPDATE-REAL-002: 第三波のタイムテーブル更新が同じIDで永続化され画面に表示される', async ({ page }) => {
  const path = `/api/v1/events/${targetEvent.id}/timetable/${targetTimetable.id}`
  const baseline = await listTimetableItems(adminApi, adminLogin, targetEvent)
  expect(baseline.filter(item => item.id === targetTimetable.id), '更新前に対象項目が1件だけあること').toHaveLength(1)

  await expectStatus(
    await unauthenticatedApi.patch(path, { data: { title: '未認証第三波更新' } }),
    401,
    '未認証タイムテーブル更新',
  )
  await expectStatus(
    await memberApi.patch(path, {
      headers: authorization(memberLogin),
      data: { title: 'MEMBER第三波更新' },
    }),
    403,
    '同一チームMEMBERのタイムテーブル更新',
  )
  await expectStatus(
    await adminApi.patch(
      `/api/v1/events/${targetEvent.id}/timetable/${foreignTimetable.id}`,
      {
        headers: authorization(adminLogin),
        data: { title: '別チームID第三波更新' },
      },
    ),
    404,
    '別チームタイムテーブル項目IDの更新',
  )

  const rejectedTarget = await listTimetableItems(adminApi, adminLogin, targetEvent)
  expect(rejectedTarget).toHaveLength(baseline.length)
  expect(rejectedTarget.find(item => item.id === targetTimetable.id)?.title).toBe(targetTimetable.title)
  const rejectedForeign = await listTimetableItems(outsiderApi, outsiderLogin, foreignEvent)
  expect(rejectedForeign.find(item => item.id === foreignTimetable.id)?.title).toBe(foreignTimetable.title)

  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE })
  const updatedTitle = `${targetTimetable.title} 更新済み`
  const updateResponse = await page.request.patch(`${API_BASE}${path}`, {
    data: { title: updatedTitle },
  })
  await expectStatus(updateResponse, 200, '実ブラウザセッションからのタイムテーブル更新')
  const updateBody = (await updateResponse.json()) as { data: TimetableFixture }
  expect(updateBody.data.id).toBe(targetTimetable.id)
  expect(updateBody.data.eventId).toBe(targetEvent.id)
  expect(updateBody.data.title).toBe(updatedTitle)

  const persisted = await listTimetableItems(adminApi, adminLogin, targetEvent)
  expect(persisted).toHaveLength(baseline.length)
  expect(persisted.filter(item => item.id === targetTimetable.id)).toHaveLength(1)
  expect(persisted.find(item => item.id === targetTimetable.id)?.title).toBe(updatedTitle)

  await page.goto(`/teams/${targetTeam.slug}/events/${targetEvent.id}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.getByRole('tab', { name: 'タイムテーブル' }).click()
  await expect(page.getByText(updatedTitle, { exact: true }), '永続化した第三波更新が詳細画面に表示されること').toBeVisible({
    timeout: 30_000,
  })
})
