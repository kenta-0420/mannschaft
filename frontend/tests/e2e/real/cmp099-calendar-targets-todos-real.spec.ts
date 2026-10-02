/**
 * CMP-099 予定対象者・メンバー色・担当TODOカレンダー 実機E2E。
 * page.route 等のモックは使用せず、実BE・実DB・実ブラウザで検証する。
 */
import { test, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080'
const TEAM = 'fc-u-18'
const ORG = 'org-000001'
const USER_ID = 23
const ADMIN_ID = 24
const PASSWORD = 'TestPass2026!'
const USER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }
const RUN = `CMP099-${Date.now()}`

type Scope = 'personal' | 'team' | 'organization'
type Created = { scope: Scope; id: number }
type Member = { userId: number; displayName: string; calendarColor: string | null }

function jstDate(offsetDays = 0): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const value = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(p => p.type === type)?.value)
  const base = new Date(Date.UTC(value('year'), value('month') - 1, value('day') + offsetDays))
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'UTC', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(base)
}

const MONTH_PREFIX = jstDate(0).slice(0, 7)
const DATES = Array.from({ length: 15 }, (_, index) => `${MONTH_PREFIX}-${String(index + 2).padStart(2, '0')}`)
const DATE = DATES[0]!
const MONTH_FROM = `${DATE.slice(0, 7)}-01`
const MONTH_TO = `${DATE.slice(0, 7)}-31`

function headers(token: string) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function cleanupStaleFixtures(
  request: APIRequestContext,
  adminToken: string,
  userToken: string,
): Promise<void> {
  const scheduleById = new Map<number, { scopeType: string; scopeSlug: string }>()
  const todoById = new Map<number, { scopeType: string; scopeSlug: string | null }>()

  for (const tokenValue of [adminToken, userToken]) {
    const calendar = await request.get(
      `${API}/api/v1/my/calendar?from=${MONTH_FROM}T00:00:00&to=${MONTH_TO}T23:59:59`,
      { headers: headers(tokenValue) },
    )
    expect(calendar.status(), '古い予定フィクスチャの列挙').toBe(200)
    const entries = (await calendar.json() as {
      data: Array<{ id: number | null; content?: { title?: string }; scope?: { scopeType?: string; scopeSlug?: string } }>
    }).data
    for (const entry of entries) {
      if (entry.id && entry.content?.title?.startsWith('CMP099-') && !entry.content.title.startsWith(RUN)
        && entry.scope?.scopeType && entry.scope.scopeSlug) {
        scheduleById.set(entry.id, { scopeType: entry.scope.scopeType, scopeSlug: entry.scope.scopeSlug })
      }
    }

    const todos = await request.get(
      `${API}/api/v1/todos/my/calendar?from=${MONTH_FROM}&to=${MONTH_TO}`,
      { headers: headers(tokenValue) },
    )
    expect(todos.status(), '古いTODOフィクスチャの列挙').toBe(200)
    const items = (await todos.json() as {
      data: Array<{ id: number; title: string; scopeType: string; scopeSlug: string | null }>
    }).data
    for (const item of items) {
      if (item.title.startsWith('CMP099-') && !item.title.startsWith(RUN)) {
        todoById.set(item.id, { scopeType: item.scopeType, scopeSlug: item.scopeSlug })
      }
    }
  }

  for (const [id, item] of todoById) {
    const path = item.scopeType === 'PERSONAL'
      ? `todos/${id}`
      : item.scopeType === 'TEAM'
        ? `teams/${item.scopeSlug}/todos/${id}`
        : `organizations/${item.scopeSlug}/todos/${id}`
    const auth = item.scopeType === 'PERSONAL' ? userToken : adminToken
    const response = await request.delete(`${API}/api/v1/${path}`, { headers: headers(auth) })
    expect([204, 404], `古いTODO ${id} の後片付け`).toContain(response.status())
  }
  for (const [id, item] of scheduleById) {
    const path = item.scopeType === 'TEAM' ? 'teams' : 'organizations'
    const response = await request.delete(`${API}/api/v1/${path}/${item.scopeSlug}/schedules/${id}`, {
      headers: headers(adminToken),
    })
    expect([204, 404], `古い予定 ${id} の後片付け`).toContain(response.status())
  }
}

async function token(request: APIRequestContext, credentials: typeof USER): Promise<string> {
  const response = await request.post(`${API}/api/v1/auth/login`, { data: credentials })
  expect(response.status(), `${credentials.email} の実ログイン`).toBe(200)
  const body = await response.json() as { data: { accessToken: string } }
  return body.data.accessToken
}

async function members(request: APIRequestContext, tokenValue: string, scope: 'teams' | 'organizations', id: string): Promise<Member[]> {
  const response = await request.get(`${API}/api/v1/${scope}/${id}/members?page=0&size=500`, {
    headers: headers(tokenValue),
  })
  expect(response.status(), `${scope}/${id} のメンバー一覧`).toBe(200)
  return (await response.json() as { data: Member[] }).data
}

async function createSchedule(
  request: APIRequestContext,
  tokenValue: string,
  scope: 'team' | 'organization',
  title: string,
  targetUserIds: readonly number[] | null,
  date = DATE,
): Promise<number> {
  const base = scope === 'team' ? `teams/${TEAM}` : `organizations/${ORG}`
  const response = await request.post(`${API}/api/v1/${base}/schedules`, {
    headers: headers(tokenValue),
    data: {
      title,
      description: `${RUN} 実機確認`,
      startAt: `${date}T10:00:00+09:00`,
      endAt: `${date}T11:00:00+09:00`,
      allDay: false,
      eventType: 'OTHER',
      targetMode: targetUserIds === null ? 'ALL_MEMBERS' : 'SELECTED_MEMBERS',
      targetUserIds: targetUserIds ?? [],
      attendanceRequired: false,
    },
  })
  expect(response.status(), `${title} の実予定作成`).toBe(201)
  return (await response.json() as { data: { id: number } }).data.id
}

async function createTodo(
  request: APIRequestContext,
  tokenValue: string,
  scope: Scope,
  title: string,
  options: { assignees?: number[]; startDate?: string | null; dueDate?: string | null; linkedScheduleId?: number } = {},
): Promise<number> {
  const path = scope === 'personal'
    ? 'todos'
    : scope === 'team' ? `teams/${TEAM}/todos` : `organizations/${ORG}/todos`
  const response = await request.post(`${API}/api/v1/${path}`, {
    headers: headers(tokenValue),
    data: {
      title,
      description: `${RUN} 実機確認`,
      priority: 'MEDIUM',
      assigneeIds: options.assignees ?? [USER_ID],
      startDate: options.startDate === undefined ? null : options.startDate,
      dueDate: options.dueDate === undefined ? DATE : options.dueDate,
      linkedScheduleId: options.linkedScheduleId ?? null,
      createLinkedSchedule: false,
    },
  })
  expect(response.status(), `${title} の実TODO作成`).toBe(201)
  return (await response.json() as { data: { id: number } }).data.id
}

async function loggedPage(browser: Browser, credentials: typeof USER): Promise<Page> {
  const page = await browser.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API })
  return page
}

async function openCalendar(page: Page, route = '/calendar'): Promise<void> {
  await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 30_000 })
  await waitForHydration(page)
  const spinner = page.locator('.pi-spin')
  if (await spinner.count()) await spinner.waitFor({ state: 'detached', timeout: 30_000 })
}

async function expectVisibleTitle(page: Page, title: string): Promise<void> {
  // 時刻あり予定は CalendarGrid が「10:00」とタイトルを同一spanへ描く。
  // CalendarGrid はレスポンシブ表示用に同じイベント要素を複数描くため、可視側を明示する。
  await expect(page.getByText(title, { exact: false }).filter({ visible: true }).first())
    .toBeVisible({ timeout: 30_000 })
}

function visibleTitle(page: Page, title: string) {
  return page.getByText(title, { exact: false }).filter({ visible: true }).first()
}

test.describe('CMP-099-REAL: 予定対象者・色・担当TODOカレンダー', () => {
  test.describe.configure({ mode: 'serial', timeout: 90_000 })

  let adminToken = ''
  let userToken = ''
  let outsiderToken = ''
  let userName = ''
  let adminName = ''
  let originalTeamColor: string | null = null
  let originalOrgColor: string | null = null
  const createdSchedules: Created[] = []
  const createdTodos: Created[] = []
  const titles = {
    all: `${RUN} 全員予定`,
    selectedUser: `${RUN} 母のみ予定`,
    selectedAdmin: `${RUN} 父のみ予定`,
    selectedBoth: `${RUN} 父母予定`,
    orgSelected: `${RUN} 組織対象予定`,
    linkedSchedule: `${RUN} 連携予定`,
    personalTodo: `${RUN} 個人期限TODO`,
    teamTodo: `${RUN} チーム期限TODO`,
    orgTodo: `${RUN} 組織期限TODO`,
    rangeTodo: `${RUN} 共同担当期間TODO`,
    otherTodo: `${RUN} 他人担当TODO`,
    completedTodo: `${RUN} 完了TODO`,
    removedTodo: `${RUN} 担当解除TODO`,
    linkedTodo: `${RUN} 連携重複TODO`,
  }

  test.beforeAll(async ({ request }) => {
    const health = await request.get(`${API}/actuator/health`)
    expect(health.status(), '実バックエンドが起動している').toBe(200)
    adminToken = await token(request, ADMIN)
    userToken = await token(request, USER)
    outsiderToken = await token(request, OUTSIDER)
    await cleanupStaleFixtures(request, adminToken, userToken)

    const teamMembers = await members(request, adminToken, 'teams', TEAM)
    const orgMembers = await members(request, adminToken, 'organizations', ORG)
    const teamUser = teamMembers.find(member => member.userId === USER_ID)
    const teamAdmin = teamMembers.find(member => member.userId === ADMIN_ID)
    const orgUser = orgMembers.find(member => member.userId === USER_ID)
    expect(teamUser, '一般ユーザーが対象チームの現役メンバー').toBeTruthy()
    expect(teamAdmin, '管理者が対象チームの現役メンバー').toBeTruthy()
    expect(orgUser, '一般ユーザーが対象組織の現役メンバー').toBeTruthy()
    userName = teamUser!.displayName
    adminName = teamAdmin!.displayName
    originalTeamColor = teamUser!.calendarColor
    originalOrgColor = orgUser!.calendarColor

    for (const [scope, title, targets, date] of [
      ['team', titles.all, null, DATES[0]],
      ['team', titles.selectedUser, [USER_ID], DATES[1]],
      ['team', titles.selectedAdmin, [ADMIN_ID], DATES[2]],
      ['team', titles.selectedBoth, [USER_ID, ADMIN_ID], DATES[3]],
      ['organization', titles.orgSelected, [USER_ID], DATES[4]],
    ] as const) {
      createdSchedules.push({ scope, id: await createSchedule(request, adminToken, scope, title, targets, date) })
    }
    const linkedScheduleId = await createSchedule(request, adminToken, 'team', titles.linkedSchedule, [USER_ID], DATES[5])
    createdSchedules.push({ scope: 'team', id: linkedScheduleId })

    createdTodos.push({ scope: 'personal', id: await createTodo(request, userToken, 'personal', titles.personalTodo, { dueDate: DATES[6] }) })
    createdTodos.push({ scope: 'team', id: await createTodo(request, adminToken, 'team', titles.teamTodo, { dueDate: DATES[7] }) })
    createdTodos.push({ scope: 'organization', id: await createTodo(request, adminToken, 'organization', titles.orgTodo, { dueDate: DATES[8] }) })
    createdTodos.push({ scope: 'team', id: await createTodo(request, adminToken, 'team', titles.rangeTodo, {
      assignees: [USER_ID, ADMIN_ID], startDate: DATES[9], dueDate: DATES[10],
    }) })
    createdTodos.push({ scope: 'team', id: await createTodo(request, adminToken, 'team', titles.otherTodo, {
      assignees: [ADMIN_ID], dueDate: DATES[11],
    }) })
    const completedId = await createTodo(request, adminToken, 'team', titles.completedTodo, { dueDate: DATES[12] })
    createdTodos.push({ scope: 'team', id: completedId })
    const completedResponse = await request.patch(`${API}/api/v1/teams/${TEAM}/todos/${completedId}/status`, {
      headers: headers(adminToken), data: { status: 'COMPLETED' },
    })
    expect(completedResponse.status(), '完了TODOの状態変更').toBe(200)
    const removedId = await createTodo(request, adminToken, 'team', titles.removedTodo, { dueDate: DATES[13] })
    createdTodos.push({ scope: 'team', id: removedId })
    const removeResponse = await request.delete(`${API}/api/v1/teams/${TEAM}/todos/${removedId}/assignees/${USER_ID}`, {
      headers: headers(adminToken),
    })
    expect(removeResponse.status(), '担当解除').toBe(204)
    const linkedTodoId = await createTodo(request, adminToken, 'team', titles.linkedTodo, { dueDate: DATES[5] })
    createdTodos.push({ scope: 'team', id: linkedTodoId })
    const linkResponse = await request.post(`${API}/api/v1/teams/${TEAM}/todos/${linkedTodoId}/link-schedule`, {
      headers: headers(adminToken), data: { scheduleId: linkedScheduleId, parentId: null },
    })
    expect(linkResponse.status(), 'TODOと予定の双方向連携').toBe(200)
  })

  test.afterAll(async ({ request }) => {
    const restore = async (scope: 'teams' | 'organizations', id: string, color: string | null) => {
      const url = `${API}/api/v1/${scope}/${id}/members/${USER_ID}/calendar-color`
      if (color) await request.patch(url, { headers: headers(adminToken), data: { calendarColor: color } })
      else await request.delete(url, { headers: headers(adminToken) })
    }
    await restore('teams', TEAM, originalTeamColor)
    await restore('organizations', ORG, originalOrgColor)
    for (const todo of createdTodos.reverse()) {
      const path = todo.scope === 'personal' ? 'todos' : todo.scope === 'team' ? `teams/${TEAM}/todos` : `organizations/${ORG}/todos`
      const auth = todo.scope === 'personal' ? userToken : adminToken
      const response = await request.delete(`${API}/api/v1/${path}/${todo.id}`, { headers: headers(auth) })
      expect([204, 404], `TODO ${todo.id} の後片付け`).toContain(response.status())
    }
    for (const schedule of createdSchedules.reverse()) {
      const path = schedule.scope === 'team' ? `teams/${TEAM}` : `organizations/${ORG}`
      const response = await request.delete(`${API}/api/v1/${path}/schedules/${schedule.id}`, { headers: headers(adminToken) })
      expect([204, 404], `予定 ${schedule.id} の後片付け`).toContain(response.status())
    }
  })

  test('CMP099-REAL-001: 作成画面で全員／選択モードと色付き対象候補を操作できる', async ({ browser }) => {
    const page = await loggedPage(browser, ADMIN)
    try {
      await page.goto(`/teams/${TEAM}/schedule`)
      await waitForHydration(page)
      await page.getByRole('button', { name: '予定を追加' }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByText('予定の対象者')).toBeVisible()
      await expect(dialog.getByLabel('全員')).toBeChecked()
      await dialog.getByLabel('メンバーを選択').check()
      const picker = dialog.getByLabel('対象メンバーを選択')
      // PrimeVue MultiSelect の aria-label は readonly hidden input に付くため、可視の親を操作する。
      await picker.locator('../..').click()
      const userOption = page.getByRole('option').filter({ hasText: userName })
      const adminOption = page.getByRole('option').filter({ hasText: adminName })
      await expect(userOption).toBeVisible()
      await expect(adminOption).toBeVisible()
      await expect(userOption.locator('span[style*="background-color"]')).toBeVisible()
      await userOption.click()
      await expect(picker.locator('../..')).toContainText(userName)
    } finally { await page.close() }
  })

  test('CMP099-REAL-002: 管理者が色を変更し、再読込・対象pickerへ反映後、UIで自動色へ戻せる', async ({ browser }) => {
    const page = await loggedPage(browser, ADMIN)
    try {
      await page.goto(`/teams/${TEAM}/members`)
      await waitForHydration(page)
      const row = page.getByRole('row').filter({ hasText: userName })
      await expect(row).toBeVisible()
      await row.getByRole('combobox').click()
      const responsePromise = page.waitForResponse(response => response.url().includes(`/members/${USER_ID}/calendar-color`) && response.request().method() === 'PATCH')
      await page.getByRole('option', { name: '#DC2626' }).click()
      expect((await responsePromise).status()).toBe(200)
      await page.reload()
      await waitForHydration(page)
      const reloadedRow = page.getByRole('row').filter({ hasText: userName })
      await expect(reloadedRow.locator('[aria-label*="予定の色"]')).toHaveCSS('background-color', 'rgb(220, 38, 38)')

      await page.goto(`/teams/${TEAM}/schedule`)
      await waitForHydration(page)
      await page.getByRole('button', { name: '予定を追加' }).click()
      await page.getByRole('dialog').getByLabel('メンバーを選択').check()
      await page.getByRole('dialog').getByLabel('対象メンバーを選択').locator('../..').click()
      await expect(page.getByRole('option').filter({ hasText: userName }).locator('span[style*="background-color"]')).toHaveCSS('background-color', 'rgb(220, 38, 38)')
      await page.keyboard.press('Escape')
      await page.keyboard.press('Escape')

      await page.goto(`/teams/${TEAM}/members`)
      await waitForHydration(page)
      const resetResponse = page.waitForResponse(response => response.url().includes(`/members/${USER_ID}/calendar-color`) && response.request().method() === 'DELETE')
      await page.getByRole('row').filter({ hasText: userName }).getByRole('button', { name: '自動色に戻す' }).click()
      expect((await resetResponse).status()).toBe(200)
      await page.reload()
      await expect(page.getByRole('row').filter({ hasText: userName }).locator('[aria-label*="予定の色"]')).not.toHaveCSS('background-color', 'rgb(220, 38, 38)')
    } finally { await page.close() }
  })

  test('CMP099-REAL-003: 一般メンバーには色編集UIがなく、色更新APIも拒否される', async ({ browser, request }) => {
    const page = await loggedPage(browser, USER)
    try {
      await page.goto(`/teams/${TEAM}/members`)
      await waitForHydration(page)
      const row = page.getByRole('row').filter({ hasText: userName })
      await expect(row).toBeVisible()
      await expect(row.getByRole('combobox')).toHaveCount(0)
      await expect(row.getByRole('button', { name: '自動色に戻す' })).toHaveCount(0)
      const response = await request.patch(`${API}/api/v1/teams/${TEAM}/members/${USER_ID}/calendar-color`, {
        headers: headers(userToken), data: { calendarColor: '#DC2626' },
      })
      expect([403, 404], '一般メンバーの色変更を拒否').toContain(response.status())
    } finally { await page.close() }
  })

  test('CMP099-REAL-004: 全員予定は一般メンバーに表示され、詳細は「全員」になる', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expectVisibleTitle(page, titles.all)
      await visibleTitle(page, titles.all).click()
      await expect(page.getByText('予定の対象者')).toBeVisible()
      await expect(page.getByText('全員', { exact: true }).last()).toBeVisible()
    } finally { await page.close() }
  })

  test('CMP099-REAL-005: 単独対象予定は対象者名と色を表示し、対象外予定は漏洩しない', async ({ browser }) => {
    const userPage = await loggedPage(browser, USER)
    const adminPage = await loggedPage(browser, ADMIN)
    try {
      await openCalendar(userPage)
      await expectVisibleTitle(userPage, titles.selectedUser)
      await expect(userPage.getByText(titles.selectedAdmin, { exact: false })).toHaveCount(0)
      await visibleTitle(userPage, titles.selectedUser).click()
      await expect(userPage.getByTitle(userName).last()).toBeVisible()
      await expect(userPage.getByTitle(userName).last()).toHaveCSS('background-color', /rgb\(/)

      await openCalendar(adminPage)
      await expectVisibleTitle(adminPage, titles.selectedAdmin)
    } finally { await userPage.close(); await adminPage.close() }
  })

  test('CMP099-REAL-006: 複数対象予定は父・母の両名を表示する', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expectVisibleTitle(page, titles.selectedBoth)
      await visibleTitle(page, titles.selectedBoth).click()
      await expect(page.getByTitle(userName).last()).toBeVisible()
      await expect(page.getByTitle(adminName).last()).toBeVisible()
    } finally { await page.close() }
  })

  test('CMP099-REAL-007: 組織予定も対象者名を保ってマイカレンダーへ表示される', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expectVisibleTitle(page, titles.orgSelected)
      await visibleTitle(page, titles.orgSelected).click()
      await expect(page.getByTitle(userName).last()).toBeVisible()
    } finally { await page.close() }
  })

  test('CMP099-REAL-008: 個人・チーム・組織の期限のみ担当TODOが同じ月に表示される', async ({ browser, request }) => {
    const response = await request.get(`${API}/api/v1/todos/my/calendar?from=${MONTH_FROM}&to=${MONTH_TO}`, { headers: headers(userToken) })
    expect(response.status()).toBe(200)
    const data = (await response.json() as { data: Array<{ title: string; scopeType: string }> }).data
    expect(data).toEqual(expect.arrayContaining([
      expect.objectContaining({ title: titles.personalTodo, scopeType: 'PERSONAL' }),
      expect.objectContaining({ title: titles.teamTodo, scopeType: 'TEAM' }),
      expect.objectContaining({ title: titles.orgTodo, scopeType: 'ORGANIZATION' }),
    ]))
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expectVisibleTitle(page, titles.personalTodo)
      await expectVisibleTitle(page, titles.teamTodo)
      await expectVisibleTitle(page, titles.orgTodo)
    } finally { await page.close() }
  })

  test('CMP099-REAL-009: 共同担当の期間TODOは1件だけ表示され、クリックでTODO詳細へ遷移する', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      const entries = page.getByText(titles.rangeTodo, { exact: false })
      await expect(entries).toHaveCount(1)
      await entries.click()
      await expect(page).toHaveURL(/\/todos\/\d+$/)
      await expect(page.getByText(titles.rangeTodo, { exact: false }).first()).toBeVisible()
    } finally { await page.close() }
  })

  test('CMP099-REAL-010: 他人担当・完了・担当解除済みTODOはAPIにも画面にも出ない', async ({ browser, request }) => {
    const response = await request.get(`${API}/api/v1/todos/my/calendar?from=${MONTH_FROM}&to=${MONTH_TO}`, { headers: headers(userToken) })
    const titlesFromApi = (await response.json() as { data: Array<{ title: string }> }).data.map(item => item.title)
    expect(titlesFromApi).not.toEqual(expect.arrayContaining([titles.otherTodo, titles.completedTodo, titles.removedTodo]))
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expect(page.getByText(titles.otherTodo, { exact: false })).toHaveCount(0)
      await expect(page.getByText(titles.completedTodo, { exact: false })).toHaveCount(0)
      await expect(page.getByText(titles.removedTodo, { exact: false })).toHaveCount(0)
    } finally { await page.close() }
  })

  test('CMP099-REAL-011: 連携済みTODOは予定を正本として1件表示し、TODO側を重複表示しない', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await expect(page.getByText(titles.linkedSchedule, { exact: false })).toHaveCount(1)
      await expect(page.getByText(titles.linkedTodo, { exact: false })).toHaveCount(0)
    } finally { await page.close() }
  })

  test('CMP099-REAL-012: ダッシュボードとマイカレンダーで予定・担当TODOの表示が一致する', async ({ browser }) => {
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page, '/dashboard')
      for (const title of [titles.selectedUser, titles.teamTodo, titles.orgTodo]) await expectVisibleTitle(page, title)
      await openCalendar(page, '/calendar')
      for (const title of [titles.selectedUser, titles.teamTodo, titles.orgTodo]) await expectVisibleTitle(page, title)
    } finally { await page.close() }
  })

  test('CMP099-REAL-013: 更新後も対象者設定が再取得で保持される', async ({ request, browser }) => {
    const target = createdSchedules.find(item => item.scope === 'team' && item.id !== undefined)!
    const update = await request.patch(`${API}/api/v1/teams/${TEAM}/schedules/${target.id}`, {
      headers: headers(adminToken),
      data: {
        title: titles.all,
        startAt: `${DATE}T10:00:00+09:00`, endAt: `${DATE}T11:00:00+09:00`,
        allDay: false, eventType: 'OTHER', attendanceRequired: false,
        targetMode: 'SELECTED_MEMBERS', targetUserIds: [USER_ID, ADMIN_ID],
      },
    })
    expect(update.status()).toBe(200)
    const detail = await request.get(`${API}/api/v1/teams/${TEAM}/schedules/${target.id}`, { headers: headers(userToken) })
    const body = (await detail.json() as { data: { targetMode: string; targetCount: number; targets: Member[] } }).data
    expect(body.targetMode).toBe('SELECTED_MEMBERS')
    expect(body.targetCount).toBe(2)
    expect(body.targets.map(item => item.userId).sort()).toEqual([USER_ID, ADMIN_ID])
    const page = await loggedPage(browser, USER)
    try {
      await openCalendar(page)
      await visibleTitle(page, titles.all).click()
      await expect(page.getByTitle(userName).last()).toBeVisible()
      await expect(page.getByTitle(adminName).last()).toBeVisible()
    } finally { await page.close() }
  })

  test('CMP099-REAL-014: 未認証は担当TODO・色更新APIとも401になる', async ({ request }) => {
    const calendar = await request.get(`${API}/api/v1/todos/my/calendar?from=${MONTH_FROM}&to=${MONTH_TO}`)
    expect(calendar.status()).toBe(401)
    const color = await request.patch(`${API}/api/v1/teams/${TEAM}/members/${USER_ID}/calendar-color`, {
      data: { calendarColor: '#DC2626' },
    })
    expect(color.status()).toBe(401)
  })

  test('CMP099-REAL-015: 非所属ユーザーへ対象割当できず、非所属カレンダーにも漏洩しない', async ({ request, browser }) => {
    const create = await request.post(`${API}/api/v1/teams/${TEAM}/schedules`, {
      headers: headers(adminToken),
      data: {
        title: `${RUN} 非所属対象`, startAt: `${DATE}T14:00:00+09:00`, endAt: `${DATE}T15:00:00+09:00`,
        allDay: false, eventType: 'OTHER', targetMode: 'SELECTED_MEMBERS', targetUserIds: [90245], attendanceRequired: false,
      },
    })
    expect([400, 404], '非所属ユーザーの対象指定を拒否').toContain(create.status())
    const outsiderPage = await loggedPage(browser, OUTSIDER)
    try {
      await openCalendar(outsiderPage)
      for (const title of [titles.all, titles.selectedUser, titles.teamTodo, titles.orgTodo]) {
        await expect(outsiderPage.getByText(title, { exact: false })).toHaveCount(0)
      }
    } finally { await outsiderPage.close() }
    expect(outsiderToken).toBeTruthy()
  })
})
