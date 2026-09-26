/** CMP-019 Wave11: TEAM/ORG の一括 TODO 完了でロック中をスキップする実機検証。 */
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, request as pwRequest, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(420_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const OUTSIDER = 'e2e-outsider@test.mannschaft.local'
const RUN_TAG = `CMP019_W11_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`

type Scope = {
  type: 'team' | 'organization'
  slug: string
  numericId: number
  editor: string
}
type Created = { projectId?: number; milestoneIds: number[]; todoIds: number[] }
type TodoDto = { id: number; status: string | { status: string } }
type BulkResult = { data: Array<{ id: number; status: string }>; skippedLockedIds: number[] }

const SCOPES: Scope[] = [
  { type: 'team', slug: 'fc-u-18', numericId: 1, editor: 'e2e-dummy-1@test.mannschaft.local' },
  { type: 'organization', slug: 'org-000009', numericId: 9, editor: 'e2e-admin@test.mannschaft.local' },
]

function base(scope: Scope): string {
  return `/api/v1/${scope.type === 'team' ? 'teams' : 'organizations'}/${scope.slug}`
}

function pagePath(scope: Scope): string {
  return `/${scope.type === 'team' ? 'teams' : 'organizations'}/${scope.slug}/todos`
}

function auth(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` }
}

async function login(api: APIRequestContext, email: string): Promise<string> {
  const response = await api.post('/api/v1/auth/login', { data: { email, password: PASSWORD } })
  expect(response.status(), `${email} ログイン`).toBe(200)
  return ((await response.json()) as { data: { accessToken: string } }).data.accessToken
}

async function create(api: APIRequestContext, path: string, token: string, data: object): Promise<number> {
  const response = await api.post(path, { headers: auth(token), data })
  expect(response.status(), `${path}: ${await response.text()}`).toBe(201)
  return ((await response.json()) as { data: { id: number } }).data.id
}

function mysql(sql: string): string {
  const jar = process.env.E2E_MYSQL_JDBC_JAR
  if (!jar || !process.env.E2E_MYSQL_USER || !process.env.E2E_MYSQL_PASSWORD) {
    throw new Error('E2E_MYSQL_JDBC_JAR / E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
  }
  return execFileSync('java', ['--class-path', jar, 'tests/e2e/real/MysqlExec.java', sql], {
    cwd: process.cwd(), env: process.env, encoding: 'utf8',
  }).trim()
}

async function todo(api: APIRequestContext, scope: Scope, token: string, id: number): Promise<TodoDto> {
  const response = await api.get(`${base(scope)}/todos/${id}`, { headers: auth(token) })
  expect(response.status()).toBe(200)
  return ((await response.json()) as { data: TodoDto }).data
}

function statusOf(item: TodoDto): string {
  return typeof item.status === 'string' ? item.status : item.status.status
}

async function cleanup(api: APIRequestContext, scope: Scope, token: string, created: Created): Promise<void> {
  // 新規作成 ID だけを API で削除する。共有 seed の TODO / project には触れない。
  const failures: string[] = []
  async function deleteId(path: string): Promise<void> {
    try {
      const response = await api.delete(path, { headers: auth(token) })
      if (![204, 404].includes(response.status())) failures.push(`${path}: ${response.status()}`)
    }
    catch (error) {
      failures.push(`${path}: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
  for (const id of [...created.todoIds].reverse()) {
    await deleteId(`${base(scope)}/todos/${id}`)
  }
  if (created.projectId) {
    for (const id of [...created.milestoneIds].reverse()) {
      await deleteId(`${base(scope)}/projects/${created.projectId}/milestones/${id}`)
    }
    await deleteId(`${base(scope)}/projects/${created.projectId}`)
  }
  if (failures.length > 0) throw new Error(`fixture cleanup 失敗: ${failures.join(' / ')}`)
}

function row(page: Page, id: number) {
  return page.getByTestId(`team-todo-row-${id}`).locator('xpath=ancestor::tr')
}

async function select(page: Page, id: number): Promise<void> {
  const checkbox = row(page, id).getByRole('checkbox')
  await checkbox.check()
  await expect(checkbox).toBeChecked()
}

async function runScenario(browser: Browser, scope: Scope): Promise<void> {
  const api = await pwRequest.newContext({ baseURL: API_BASE })
  const created: Created = { milestoneIds: [], todoIds: [] }
  let token = ''
  let context: Awaited<ReturnType<Browser['newContext']>> | undefined
  try {
    token = await login(api, scope.editor)
    const outsiderToken = await login(api, OUTSIDER)
    const membershipCount = (email: string) => Number(mysql(`SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.email='${email}' AND m.scope_type='${scope.type.toUpperCase()}' AND m.scope_id=${scope.numericId} AND m.left_at IS NULL`))
    expect(membershipCount(scope.editor), `${scope.editor} の現役所属`).toBe(1)
    expect(membershipCount(OUTSIDER), `${OUTSIDER} はスコープ非所属`).toBe(0)
    const root = base(scope)
    const projectId = await create(api, `${root}/projects`, token, {
      title: `${RUN_TAG}_${scope.type}_project`, description: 'Wave11 実機専用',
    })
    created.projectId = projectId
    const firstId = await create(api, `${root}/projects/${projectId}/milestones`, token, {
      title: `${RUN_TAG}_先行`, sortOrder: 0,
    })
    created.milestoneIds.push(firstId)
    const secondId = await create(api, `${root}/projects/${projectId}/milestones`, token, {
      title: `${RUN_TAG}_後続`, sortOrder: 1,
    })
    created.milestoneIds.push(secondId)
    const titles = {
      open: `${RUN_TAG}_${scope.type}_変更対象`,
      guard: `${RUN_TAG}_${scope.type}_先行未完了維持`,
      locked1: `${RUN_TAG}_${scope.type}_ロックA`,
      locked2: `${RUN_TAG}_${scope.type}_ロックB`,
    }
    const openId = await create(api, `${root}/todos`, token, { title: titles.open, projectId, milestoneId: firstId })
    created.todoIds.push(openId)
    const guardId = await create(api, `${root}/todos`, token, { title: titles.guard, projectId, milestoneId: firstId })
    created.todoIds.push(guardId)
    const locked1 = await create(api, `${root}/todos`, token, { title: titles.locked1, projectId, milestoneId: secondId })
    created.todoIds.push(locked1)
    const locked2 = await create(api, `${root}/todos`, token, { title: titles.locked2, projectId, milestoneId: secondId })
    created.todoIds.push(locked2)

    if (scope.type === 'team') {
      const gate = await api.patch(
        `/api/v1/teams/${scope.numericId}/projects/${projectId}/milestones/${secondId}/initialize-gate`,
        { headers: auth(token) },
      )
      expect(gate.status(), 'TEAM 正規ゲート初期化').toBe(200)
    }
    else {
      // ORG にゲート初期化 API がないため、新規作成した2件だけに限定して fixture を作る。
      mysql(`UPDATE todos SET milestone_locked=TRUE WHERE id IN (${locked1},${locked2}) AND scope_type='ORGANIZATION' AND scope_id=${scope.numericId} AND project_id=${projectId} AND milestone_id=${secondId}`)
    }
    const lockRows = mysql(`SELECT CONCAT(id, ':', milestone_locked) FROM todos WHERE id IN (${openId},${guardId},${locked1},${locked2}) AND scope_type='${scope.type.toUpperCase()}' AND scope_id=${scope.numericId} ORDER BY id`)
    const locks = new Map(lockRows.split(/\r?\n/).map(line => {
      const [id, locked] = line.split(':')
      return [Number(id), locked] as const
    }))
    expect(locks.get(openId)).toBe('0')
    expect(locks.get(guardId)).toBe('0')
    expect(locks.get(locked1)).toBe('1')
    expect(locks.get(locked2)).toBe('1')

    // スコープ非所属者は一覧・一括 API とも拒否され、タイトルを取得できない。
    const deniedList = await api.get(`${root}/todos`, { headers: auth(outsiderToken) })
    expect(deniedList.status()).toBe(403)
    const deniedBulk = await api.patch(`${root}/todos/bulk-status`, {
      headers: auth(outsiderToken), data: { todoIds: [openId, locked1], status: 'COMPLETED' },
    })
    expect(deniedBulk.status()).toBe(403)
    const outsiderContext = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    try {
      const outsiderPage = await outsiderContext.newPage()
      await loginViaApi(outsiderPage, { email: OUTSIDER, password: PASSWORD }, { apiBaseUrl: API_BASE })
      await outsiderPage.goto(pagePath(scope))
      await waitForHydration(outsiderPage)
      await expect(outsiderPage.getByText(titles.open, { exact: true })).toHaveCount(0)
    }
    finally {
      await outsiderContext.close()
    }

    context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    const page = await context.newPage()
    await loginViaApi(page, { email: scope.editor, password: PASSWORD }, { apiBaseUrl: API_BASE })
    await page.goto(pagePath(scope))
    await waitForHydration(page)
    await expect(row(page, openId)).toBeVisible({ timeout: 30_000 })
    await expect(row(page, locked1)).toBeVisible()
    await select(page, openId)
    await select(page, locked1)
    const mixedResponse = page.waitForResponse(response => response.request().method() === 'PATCH'
      && new URL(response.url()).pathname === `${root}/todos/bulk-status`)
    await page.getByRole('button', { name: '完了にする' }).click()
    const mixed = await mixedResponse
    expect(mixed.status()).toBe(200)
    const mixedData = await mixed.json() as BulkResult
    expect(mixedData.data.map(item => item.id)).toEqual([openId])
    expect(mixedData.skippedLockedIds).toEqual([locked1])
    await expect(page.getByText(/1件を変更し、ロック中の1件はスキップ/)).toBeVisible()
    await expect(row(page, locked1).getByRole('checkbox')).toBeChecked()
    await expect(row(page, openId).getByRole('checkbox')).not.toBeChecked()
    const evidenceDir = process.env.E2E_EVIDENCE_DIR
    if (evidenceDir) {
      mkdirSync(evidenceDir, { recursive: true })
      await page.screenshot({ path: join(evidenceDir, `${scope.type}-mixed.png`) })
    }
    expect(statusOf(await todo(api, scope, token, openId))).toBe('COMPLETED')
    expect(statusOf(await todo(api, scope, token, locked1))).toBe('OPEN')

    await select(page, locked2)
    const allResponse = page.waitForResponse(response => response.request().method() === 'PATCH'
      && new URL(response.url()).pathname === `${root}/todos/bulk-status`)
    await page.getByRole('button', { name: '完了にする' }).click()
    const all = await allResponse
    expect(all.status()).toBe(200)
    const allData = await all.json() as BulkResult
    expect(allData.data).toHaveLength(0)
    expect([...allData.skippedLockedIds].sort((a, b) => a - b)).toEqual([locked1, locked2].sort((a, b) => a - b))
    await expect(page.getByText(/すべてロック中のため変更できませんでした（2件）/)).toBeVisible()
    await expect(row(page, locked1).getByRole('checkbox')).toBeChecked()
    await expect(row(page, locked2).getByRole('checkbox')).toBeChecked()
    if (evidenceDir) {
      await page.screenshot({ path: join(evidenceDir, `${scope.type}-all-locked.png`) })
    }
    expect(statusOf(await todo(api, scope, token, locked2))).toBe('OPEN')
  }
  finally {
    try {
      await context?.close()
    }
    finally {
      try {
        if (token) await cleanup(api, scope, token, created)
      }
      finally {
        await api.dispose()
      }
    }
  }
}

for (const scope of SCOPES) {
  test(`${scope.type.toUpperCase()}: 一括 TODO で混在ロックと全件ロックを確認する`, async ({ browser }) => {
    await runScenario(browser, scope)
  })
}
