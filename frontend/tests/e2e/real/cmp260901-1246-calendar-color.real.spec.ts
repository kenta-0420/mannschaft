import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { expect, test, type BrowserContext, type Page, type Request, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ trace: 'off', locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })

/** Actions の当該サービスDBで、自分が作ったスコープだけをレイヤー未掲載にする実機試験。 */
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8080'
const run = `CMP2609011246-${process.env.GITHUB_RUN_ID ?? 'local'}-${Date.now()}`
const month = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit' }).format(new Date())
type TeamFixture = { id: number; slug: string; name: string; scheduleTitle?: string; scheduleId?: number; todoTitle?: string; todoId?: number }
type CalendarEntry = { scheduleId: number | null; content: { title: string; color: string; colorSource: string; scopeAutoColor: string }; scope: { scopeId: number; scopeName: string | null } }
type CalendarTodo = { id: number; title: string; scopeId: number; scopeName: string | null; scopeAutoColor: string; priority: string }
const teams: TeamFixture[] = []
const contexts: BrowserContext[] = []
let owner: Page
let negative: Page
let systemAdmin: Page
let ownerId: number
let isolated = false
let knownColor: string

function sql(statement: string): string {
  return execFileSync('mysql', ['--protocol=TCP', '--host=127.0.0.1', '--port=3306', '--user=mannschaft', '--database=mannschaft', '--batch', '--skip-column-names', '--execute', statement], {
    encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.E2E_DB_PASSWORD },
  }).trim()
}

function proveIsolation(): void {
  expect(process.env.GITHUB_ACTIONS, '共有開発DBでは実行しない').toBe('true')
  expect(run, 'SQL識別子はActions run IDと自生成時刻だけ').toMatch(/^CMP2609011246-\d+-\d+$/)
  expect(process.env.E2E_ISOLATED_DB).toBe('true')
  expect(apiBase).toBe('http://localhost:8080')
  expect(process.env.E2E_DB_HOST).toBe('127.0.0.1')
  expect(process.env.E2E_DB_PORT).toBe('3306')
  expect(process.env.E2E_DB_NAME).toBe('mannschaft')
  expect(process.env.E2E_DB_USER).toBe('mannschaft')
  const container = process.env.E2E_MYSQL_CONTAINER_ID ?? ''
  expect(container, '当該Actions jobのサービスコンテナID').toMatch(/^[a-f0-9]{64}$/)
  const hostname = execFileSync('docker', ['inspect', '--format', '{{.Config.Hostname}}', container], { encoding: 'utf8' }).trim()
  expect(sql('SELECT DATABASE(), @@hostname'), '実接続先が当該サービスコンテナに一致').toBe(`mannschaft\t${hostname}`)
  isolated = true
}

async function api<T = void>(page: Page, method: 'get' | 'post' | 'patch' | 'delete', path: string, data?: unknown): Promise<T> {
  const cookie = (await page.context().cookies()).find(value => value.name === 'access_token')
  expect(cookie, '同じブラウザログインのCookieをfixture操作に再利用').toBeDefined()
  const response = await page.request[method](`${apiBase}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${cookie!.value}` }, ...(data === undefined ? {} : { data }),
  })
  expect(response.ok(), `${method} ${path}: ${response.status()}`).toBe(true)
  return (response.status() === 204 ? undefined : (await response.json()).data) as T
}

/** Cの専用DB金型を再利用し、APIが提供しない保存色だけを今回の予定に用意する。 */
async function saveFixtureScheduleColor(team: TeamFixture, date: string, color: string): Promise<void> {
  expect(isolated, '接続先コンテナとDBの照合完了後だけ変更する').toBe(true)
  expect(Number.isSafeInteger(team.scheduleId) && team.scheduleId! > 0).toBe(true)
  expect(Number.isSafeInteger(ownerId) && ownerId > 0).toBe(true)
  expect(team.scheduleTitle).toBe(`${run}-予定-${teams.indexOf(team)}`)
  const require = createRequire(new URL('../../../../backend/scripts/package.json', import.meta.url))
  const mysql = require('mysql2/promise') as {
    createConnection(options: Record<string, unknown>): Promise<{
      execute<T>(statement: string, params: unknown[]): Promise<[T, unknown]>
      end(): Promise<void>
    }>
  }
  const db = await mysql.createConnection({ host: process.env.E2E_DB_HOST, port: Number(process.env.E2E_DB_PORT), user: process.env.E2E_DB_USER, password: process.env.E2E_DB_PASSWORD, database: process.env.E2E_DB_NAME })
  try {
    const [updated] = await db.execute<{ affectedRows: number }>('UPDATE schedules SET color = ? WHERE id = ? AND title = ? AND created_by = ?', [color, team.scheduleId, team.scheduleTitle, ownerId])
    expect(updated.affectedRows, '今回API作成の予定1行だけに色を保存する').toBe(1)
  }
  finally {
    await db.end()
  }
  const query = `from=${encodeURIComponent(`${date}T00:00:00`)}&to=${encodeURIComponent(`${date}T23:59:59`)}`
  const entries = await api<CalendarEntry[]>(owner, 'get', `/my/calendar?${query}`)
  const entry = entries.find(value => value.scheduleId === team.scheduleId)
  expect(entry?.scope.scopeId).toBe(team.id)
  expect(entry?.content).toMatchObject({ title: team.scheduleTitle, color, colorSource: 'SCHEDULE' })
}
async function evidence(page: Page, info: TestInfo, name: string): Promise<void> {
  await info.attach(name, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
}

function chip(page: Page, team: TeamFixture) {
  return page.getByTestId(`layer-chip-TEAM:${team.id}`)
}

function rgb(hex: string): string {
  return `rgb(${Number.parseInt(hex.slice(1, 3), 16)}, ${Number.parseInt(hex.slice(3, 5), 16)}, ${Number.parseInt(hex.slice(5, 7), 16)})`
}

async function calendar(page: Page, viaLink = false): Promise<{ schedules: CalendarEntry[]; todos: CalendarTodo[] }> {
  const schedules = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/my/calendar' && response.request().method() === 'GET')
  const todos = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/todos/my/calendar' && response.request().method() === 'GET')
  if (viaLink) {
    // モバイルの既存Drawerを実操作で開き、可視ナビから移動する。
    if ((page.viewportSize()?.width ?? 1280) < 768) await page.getByRole('button', { name: 'メニューを開く', exact: true }).click()
    await page.locator('a[href="/calendar"]:visible').first().click()
  }
  else await page.goto('/calendar')
  await waitForHydration(page)
  const responses = await Promise.all([schedules, todos])
  for (const response of responses) expect(response.status(), '画面が呼び出した実API応答').toBe(200)
  return { schedules: (await responses[0]!.json()).data, todos: (await responses[1]!.json()).data }
}

test.describe('CMP-260901-1246 スコープ自動色の実機', () => {
  test.setTimeout(180_000)
  test.describe.configure({ mode: 'serial' })

  test.beforeAll(async ({ browser }) => {
    proveIsolation()
    for (const email of ['e2e-outsider', 'e2e-user', 'e2e-admin']) {
      const context = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:8081', storageState: { cookies: [], origins: [] }, viewport: { width: email === 'e2e-admin' ? 375 : 1280, height: 812 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
      contexts.push(context)
      const page = await context.newPage()
      await loginViaApi(page, { email: `${email}@test.mannschaft.local`, password: 'TestPass2026!' }, { apiBaseUrl: apiBase })
    }
    owner = contexts[0]!.pages()[0]!
    negative = contexts[1]!.pages()[0]!
    systemAdmin = contexts[2]!.pages()[0]!
    ownerId = (await api<{ id: number }>(owner, 'get', '/users/me')).id
    for (const [index, kind] of ['予定', 'TODOのみ', '混在', '既知色'].entries()) {
      const name = `${run}-${kind}`
      const created = await api<{ numericId: number; slug: string }>(owner, 'post', '/teams', { name, sportType: 'SOCCER', description: run })
      expect(Number.isSafeInteger(created.numericId) && created.numericId > 0).toBe(true)
      expect(created.slug).toMatch(/^[a-z0-9-]+$/)
      const team: TeamFixture = { id: created.numericId, slug: created.slug, name }
      expect(sql(`SELECT COUNT(*) FROM teams WHERE id=${team.id} AND slug='${team.slug}' AND name='${name}'`), '今回作成した未使用IDの所有由来').toBe('1')
      teams.push(team)
      const date = `${month}-${10 + index * 2}`
      if (index !== 1) {
        team.scheduleTitle = `${run}-予定-${index}`
        team.scheduleId = (await api<{ id: number }>(owner, 'post', `/teams/${team.slug}/schedules`, { title: team.scheduleTitle, startAt: `${date}T10:00:00+09:00`, endAt: `${date}T11:00:00+09:00`, allDay: false, eventType: 'OTHER', targetMode: 'ALL_MEMBERS', targetUserIds: [], attendanceRequired: false })).id
        await saveFixtureScheduleColor(team, date, index === 2 ? '#234567' : '#123456')
      }
      if (index !== 0) {
        team.todoTitle = `${run}-TODO-${index}`
        team.todoId = (await api<{ id: number }>(owner, 'post', `/teams/${team.slug}/todos`, { title: team.todoTitle, description: run, priority: 'HIGH', assigneeIds: [ownerId], startDate: null, dueDate: date, linkedScheduleId: null, createLinkedSchedule: false })).id
      }
    }
    const known = teams[3]!
    for (const [page, expectedSystemAdmin] of [[negative, false], [systemAdmin, true]] as const) {
      const actorId = (await api<{ id: number }>(page, 'get', '/users/me')).id
      expect(Number.isSafeInteger(actorId) && actorId > 0).toBe(true)
      expect(Number(sql(`SELECT COUNT(*) FROM user_roles WHERE user_id=${actorId} AND role_id=1`)) > 0, '通常所属者とSYSTEM_ADMINの実ロールを区別する').toBe(expectedSystemAdmin)
      expect(sql(`SELECT COUNT(*) FROM memberships WHERE user_id=${actorId} AND scope_type='TEAM' AND scope_id=${known.id} AND left_at IS NULL`), '両actorは今回チームに直接所属しない').toBe('0')
    }
    const layers = await api<Array<{ scopeType: string; scopeId: number; color: string }>>(owner, 'get', '/me/calendar-layers')
    const knownLayer = layers.find(layer => layer.scopeType === 'TEAM' && layer.scopeId === known.id)
    expect(knownLayer).toBeDefined()
    knownColor = knownLayer!.color === '#DC2626' ? '#2563EB' : '#DC2626'
    await api(owner, 'patch', `/me/calendar-layers/TEAM/${known.id}`, { color: knownColor })
    // 削除対象は上で所有由来を確認した今回の3チームのみ。共有DBではpreflightで停止する。
    for (const team of teams.slice(0, 3)) await api(owner, 'delete', `/teams/${team.slug}`)
  })

  test.afterAll(async () => {
    try {
      if (isolated) {
        for (const team of teams) {
          // 今回の識別子が全て一致する行だけ復元し、既存APIで関連fixtureごと削除する。
          sql(`UPDATE teams SET deleted_at=NULL WHERE id=${team.id} AND slug='${team.slug}' AND name='${team.name}'`)
          if (team.todoId !== undefined) await api(owner, 'delete', `/teams/${team.slug}/todos/${team.todoId}`)
          if (team.scheduleId !== undefined) await api(owner, 'delete', `/teams/${team.slug}/schedules/${team.scheduleId}`)
          await api(owner, 'delete', `/teams/${team.slug}`)
        }
      }
    }
    finally {
      for (const context of contexts) await context.close()
    }
  })

  test('本人: 未知予定・TODOのみ・混在と既知利用者色を実画面で確認する', async ({ browserName }, info) => {
    expect(browserName).toBe('chromium')
    const data = await calendar(owner)
    // 凡例はdesktop専用。実際にリストへ切り替え、同じ画面でチップとagenda行の色を照合する。
    await owner.getByTestId('calendar-view-agenda').click()
    await expect(owner.getByTestId('calendar-view-agenda')).toHaveAttribute('aria-pressed', 'true')
    const agenda = owner.getByTestId('agenda-list')
    await expect(agenda).toBeVisible()
    const layers = await api<Array<{ scopeType: string; scopeId: number }>>(owner, 'get', '/me/calendar-layers')
    for (const team of teams.slice(0, 3)) {
      expect(layers.filter(layer => layer.scopeType === 'TEAM' && layer.scopeId === team.id), '今回の未知スコープは実レイヤー一覧に存在しない').toHaveLength(0)
      const schedule = data.schedules.find(entry => entry.scope.scopeId === team.id)
      const todo = data.todos.find(entry => entry.scopeId === team.id)
      const auto = schedule?.content.scopeAutoColor ?? todo?.scopeAutoColor
      expect(auto, 'BE由来の独立した自動色').toMatch(/^#[0-9A-F]{6}$/i)
      // 予定の単体名解決は既存fallback文字列、TODOのbatch名解決は欠落時nullを返す。
      if (team.scheduleTitle) expect(schedule?.scope.scopeName).toBe('不明なチーム')
      if (team.todoTitle) expect(todo?.scopeName).toBeNull()
      await expect(chip(owner, team)).toHaveCount(1)
      await expect(chip(owner, team)).toBeVisible()
      await expect(chip(owner, team)).not.toContainText(team.name)
      await expect(chip(owner, team).getByTestId('layer-chip-dot')).toHaveCSS('background-color', rgb(auto!))
      await expect(owner.getByTestId(`layer-chip-more-TEAM:${team.id}`)).toHaveCount(0)
      if (team.todoTitle) {
        expect(todo!.priority).toBe('HIGH')
        const row = agenda.getByTestId('agenda-row-wrap').filter({ hasText: team.todoTitle, visible: true }).first()
        await expect(row).toBeVisible()
        await expect(row.getByTestId('agenda-row-color-bar')).toHaveCSS('background-color', rgb('#f97316'))
      }
      if (team.scheduleTitle) {
        expect(schedule!.content.colorSource).toBe('SCHEDULE')
        const row = agenda.getByTestId('agenda-row-wrap').filter({ hasText: team.scheduleTitle, visible: true }).first()
        await expect(row).toBeVisible()
        await expect(row.getByTestId('agenda-row-color-bar')).toHaveCSS('background-color', rgb(schedule!.content.color))
      }
    }
    await expect(chip(owner, teams[3]!).getByTestId('layer-chip-dot')).toHaveCSS('background-color', rgb(knownColor))
    await expect(owner.getByTestId(`layer-chip-more-TEAM:${teams[3]!.id}`)).toBeVisible()
    await evidence(owner, info, '本人-スコープ自動色と予定TODOの色')
    const todoOnly = teams[1]!
    await chip(owner, todoOnly).click()
    await expect(chip(owner, todoOnly)).toHaveAttribute('aria-pressed', 'false')
    await expect(agenda.getByTestId('agenda-row-wrap').filter({ hasText: todoOnly.todoTitle! })).toHaveCount(0)
    await owner.reload()
    await waitForHydration(owner)
    await owner.getByTestId('calendar-view-agenda').click()
    await expect(agenda).toBeVisible()
    await expect(chip(owner, todoOnly)).toHaveAttribute('aria-pressed', 'false')
    await chip(owner, todoOnly).click()
    await expect(agenda.getByText(todoOnly.todoTitle!, { exact: true }).filter({ visible: true })).toBeVisible()
    await owner.getByTestId('agenda-next').click()
    await expect(chip(owner, teams[0]!)).toHaveCount(0)
    await owner.getByTestId('agenda-prev').click()
    await expect(chip(owner, teams[0]!).getByTestId('layer-chip-dot')).toHaveCSS('background-color', rgb(data.schedules.find(entry => entry.scope.scopeId === teams[0]!.id)!.content.scopeAutoColor))
    await evidence(owner, info, '本人-選択保持と月移動')
    await info.attach('実API色の照合', { body: JSON.stringify({ schedules: data.schedules.filter(entry => entry.content.title.startsWith(run)).map(entry => ({ scopeId: entry.scope.scopeId, scopeName: entry.scope.scopeName, title: entry.content.title, color: entry.content.color, colorSource: entry.content.colorSource, scopeAutoColor: entry.content.scopeAutoColor })), todos: data.todos.filter(entry => entry.title.startsWith(run)).map(entry => ({ scopeId: entry.scopeId, scopeName: entry.scopeName, priority: entry.priority, scopeAutoColor: entry.scopeAutoColor })) }), contentType: 'application/json' })
    const known = teams[3]!
    const allowed = owner.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/teams/${known.slug}/todos/${known.todoId}`)
    await owner.goto(`/teams/${known.slug}/todos/${known.todoId}`)
    await waitForHydration(owner)
    expect((await allowed).status(), '本人の直接URL取得').toBe(200)
    await expect(owner.getByText(known.todoTitle!, { exact: true }).filter({ visible: true }).first()).toBeVisible()
    await evidence(owner, info, '本人-直接URL表示')
  })

  test('通常非所属者とSYSTEM_ADMIN: 別導線の非表示とTODO直接URL拒否を確認する', async ({ browserName }, info) => {
    expect(browserName).toBe('chromium')
    for (const [index, page] of [negative, systemAdmin].entries()) {
      const actor = index === 0 ? '通常非所属者' : '非直接所属SYSTEM_ADMIN'
      await page.goto(index === 0 ? '/dashboard' : '/todos')
      await waitForHydration(page)
      const data = await calendar(page, true)
      expect(data.schedules.filter(entry => entry.content.title.startsWith(run))).toHaveLength(0)
      expect(data.todos.filter(entry => entry.title.startsWith(run))).toHaveLength(0)
      for (const team of teams) {
        await expect(chip(page, team)).toHaveCount(0)
        await expect(page.getByText(team.name, { exact: true })).toHaveCount(0)
        if (team.scheduleTitle) await expect(page.getByText(team.scheduleTitle, { exact: true })).toHaveCount(0)
        if (team.todoTitle) await expect(page.getByText(team.todoTitle, { exact: true })).toHaveCount(0)
      }
      await evidence(page, info, `${actor}-カレンダー非表示`)
      const known = teams[3]!
      const teamPath = `/api/v1/teams/${known.slug}`
      const todoPath = `${teamPath}/todos/${known.todoId}`
      const childRequests: string[] = []
      const observeChild = (request: Request) => {
        if (request.method() === 'GET' && new URL(request.url()).pathname === todoPath) childRequests.push(todoPath)
      }
      page.on('request', observeChild)
      try {
        // 親visibilityのSYS許可と、TODOの直接membership必須は別契約。
        const denied = page.waitForResponse(response => response.request().method() === 'GET' && new URL(response.url()).pathname === teamPath)
        const childDenied = index === 1
          ? page.waitForResponse(response => response.request().method() === 'GET' && new URL(response.url()).pathname === todoPath) : undefined
        const permissions = index === 1
          ? page.waitForResponse(response => response.request().method() === 'GET' && new URL(response.url()).pathname === `${teamPath}/me/permissions`) : undefined
        await page.goto(`/teams/${known.slug}/todos/${known.todoId}`)
        await waitForHydration(page)
        const parentResponse = await denied
        let childUiStatus: number | null = null
        if (childDenied) {
          expect(parentResponse.status(), 'SYSTEM_ADMINは親チームvisibilityを閲覧できる').toBe(200)
          const childResponse = await childDenied
          childUiStatus = childResponse.status()
          expect(childUiStatus, 'SYSでも画面のTODO GETは直接membership必須').toBe(403)
          await expect(page.getByText('TODOの取得に失敗しました', { exact: true })).toBeVisible()
          expect(childRequests.length, '親閲覧許可後に子TODO GETが実発行される').toBeGreaterThan(0)
          expect(await childResponse.finished()).toBeNull()
          const permissionResponse = await permissions!
          expect(permissionResponse.status(), 'SYSの既存権限取得は成功する').toBe(200)
          expect(await permissionResponse.finished()).toBeNull()
          await expect(page.locator('.p-skeleton').filter({ visible: true })).toHaveCount(0)
        }
        else {
          expect([403, 404], '通常非所属者は親チーム取得が拒否される').toContain(parentResponse.status())
          await expect(page.getByText('情報を取得できませんでした', { exact: true })).toBeVisible()
          await expect(page.getByText('時間をおいて再度お試しください。権限がない場合は表示できないことがあります。', { exact: true })).toBeVisible()
          await expect(page.locator('body')).not.toContainText(run)
          expect(childRequests, '親取得拒否により子TODO GETは発行されない').toHaveLength(0)
        }
        await expect(page.locator('body > div[class~="z-[9998]"]')).toHaveCount(0)
        await expect(page.getByText(known.todoTitle!, { exact: true })).toHaveCount(0)
        await expect(page.getByText('担当者', { exact: true })).toHaveCount(0)
        await evidence(page, info, `${actor}-直接URLの拒否境界`)
        const cookie = (await page.context().cookies()).find(value => value.name === 'access_token')
        expect(cookie).toBeDefined()
        const todoResponse = await page.request.get(`${apiBase}${todoPath}`, { headers: { Authorization: `Bearer ${cookie!.value}` } })
        expect(todoResponse.status(), '同じログインでもTODO APIは非所属者を403で拒否する').toBe(403)
        await info.attach(`${actor}-拒否通信metadata`, { body: JSON.stringify({ actor, uiUrl: page.url(), parent: { method: 'GET', path: teamPath, status: parentResponse.status() }, childUiGetCount: childRequests.length, childUiStatus, directApi: { method: 'GET', path: todoPath, status: todoResponse.status() } }), contentType: 'application/json' })
      }
      finally {
        page.off('request', observeChild)
      }
    }
  })
})
