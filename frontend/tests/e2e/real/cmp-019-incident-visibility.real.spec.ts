/**
 * CMP-019 Wave 6: チーム事故報告の一覧・詳細遷移と可視性を、実BE/実MySQL/実ブラウザで確認する。
 *
 * テスト対象の操作（一覧から詳細を開く、認可されない詳細URLを開く）はすべてブラウザUIで行う。
 * API/SQLは RUN_TAG 付きの前提データ作成と finally での清掃にだけ用いる。
 */
import {
  expect,
  request as pwRequest,
  test,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { execFileSync } from 'node:child_process'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(600_000)

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${BE}/api/v1`
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const PASSWORD = 'TestPass2026!'
const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? PASSWORD,
}
const REPORTER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? PASSWORD,
}
const MEMBER = {
  email: process.env.TEST_ASSIGNEE_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_ASSIGNEE_PASSWORD ?? PASSWORD,
}
const SUPPORTER = {
  email: process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local',
  password: process.env.TEST_SUPPORTER_PASSWORD ?? PASSWORD,
}
const RUN_TAG = `CMP019_VISIBILITY_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

type Credentials = typeof ADMIN
type Session = { token: string; userId: number }
type Team = { id: number; slug?: string }
type Incident = { id: number }

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

function mysql(statement: string): void {
  if (!MYSQL_USER || !MYSQL_PASSWORD) {
    throw new Error('CMP-019 real E2Eには E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  }
  const jdbcJar = process.env.E2E_MYSQL_JDBC_JAR
  if (jdbcJar) {
    execFileSync('java', ['--class-path', jdbcJar, 'tests/e2e/real/MysqlExec.java', statement], {
      cwd: process.cwd(),
      env: process.env,
      stdio: 'pipe',
    })
    return
  }
  const args = [
    'exec',
    'mannschaft-mysql',
    'mysql',
    `-u${MYSQL_USER}`,
    `-p${MYSQL_PASSWORD}`,
    'mannschaft',
    `--execute=${statement}`,
  ]
  if (process.platform === 'win32') {
    execFileSync('wsl.exe', ['-e', 'docker', ...args], { stdio: 'pipe' })
  } else {
    execFileSync('docker', args, { stdio: 'pipe' })
  }
}

async function loginApi(api: APIRequestContext, credentials: Credentials): Promise<Session> {
  const response = await api.post(`${API}/auth/login`, { data: credentials })
  expect(response.status(), `${credentials.email} のAPIログイン`).toBe(200)
  const token = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
  const me = await api.get(`${API}/users/me`, { headers: headers(token) })
  expect(me.status(), `${credentials.email} のユーザー情報`).toBe(200)
  return { token, userId: ((await me.json()) as { data: { id: number } }).data.id }
}

async function listTeams(api: APIRequestContext, token: string): Promise<Team[]> {
  const response = await api.get(`${API}/me/teams?limit=200`, { headers: headers(token) })
  expect(response.status(), '/me/teams').toBe(200)
  return ((await response.json()) as { data: Team[] }).data
}

async function createIncident(
  api: APIRequestContext,
  token: string,
  scopeId: number,
  title: string,
): Promise<Incident> {
  const response = await api.post(`${API}/incidents`, {
    headers: headers(token),
    data: {
      scopeType: 'TEAM',
      scopeId,
      title,
      description: `${RUN_TAG} fixture`,
      priority: 'MEDIUM',
    },
  })
  expect(response.status(), `前提インシデント作成: ${title}`).toBe(201)
  return ((await response.json()) as { data: Incident }).data
}

async function loginUi(page: Page, credentials: Credentials): Promise<void> {
  await page.goto('/login')
  await expect(page.locator('input#email')).toBeVisible({ timeout: 30_000 })
  await page.locator('input#email').fill(credentials.email)
  await page.locator('input[type="password"]').fill(credentials.password)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

async function withLoggedInPage<T>(
  browser: Browser,
  credentials: Credentials,
  action: (page: Page) => Promise<T>,
): Promise<T> {
  const context = await browser.newContext()
  try {
    const page = await context.newPage()
    await loginUi(page, credentials)
    return await action(page)
  } finally {
    await context.close()
  }
}

async function openConcealedDetail(
  page: Page,
  teamSlug: string,
  incidentId: number,
): Promise<void> {
  await page.goto(`/teams/${teamSlug}/incidents?incidentId=${incidentId}`, {
    waitUntil: 'domcontentloaded',
  })
  await expect(page.getByText('見つからないか閲覧権限がありません')).toBeVisible({
    timeout: 20_000,
  })
}

async function openIncidentList(page: Page, teamSlug: string, scopeId: number): Promise<number> {
  const responsePromise = page.waitForResponse(
    (response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/incidents' && url.searchParams.get('scopeId') === String(scopeId)
      )
    },
    { timeout: 20_000 },
  )
  await page.goto(`/teams/${teamSlug}/incidents`, { waitUntil: 'domcontentloaded' })
  return (await responsePromise).status()
}

test('CMP-019: チーム事故報告は一覧から詳細へ遷移し、MEMBER/SUPPORTER/別scope/匿名には秘匿される', async ({
  browser,
}) => {
  const api = await pwRequest.newContext()
  const createdIncidentIds: number[] = []
  let insertedMember: { userId: number; scopeId: number } | null = null
  let insertedSupporter: { userId: number; scopeId: number } | null = null
  let testFailure: unknown
  const cleanupErrors: string[] = []

  try {
    const admin = await loginApi(api, ADMIN)
    const reporter = await loginApi(api, REPORTER)
    const member = await loginApi(api, MEMBER)
    const supporter = await loginApi(api, SUPPORTER)
    const reporterTeams = await listTeams(api, reporter.token)
    const adminTeams = await listTeams(api, admin.token)
    const ownTeam = reporterTeams[0]
    expect(ownTeam, '報告者が所属するチーム').toBeTruthy()
    expect(ownTeam?.slug, 'チームURLに使うslug').toBeTruthy()
    const team = ownTeam!
    const teamSlug = team.slug!

    expect(
      (await listTeams(api, member.token)).some((item) => item.id === team.id),
      '無関係MEMBERは事前に対象チームへ未所属',
    ).toBe(false)
    expect(
      (await listTeams(api, supporter.token)).some((item) => item.id === team.id),
      'SUPPORTERは事前に対象チームへ未所属',
    ).toBe(false)
    mysql(`INSERT INTO memberships (user_id,scope_type,scope_id,role_kind,joined_at,created_at,updated_at)
      VALUES (${member.userId},'TEAM',${team.id},'MEMBER',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())`)
    insertedMember = { userId: member.userId, scopeId: team.id }
    mysql(`INSERT INTO memberships (user_id,scope_type,scope_id,role_kind,joined_at,created_at,updated_at)
      VALUES (${supporter.userId},'TEAM',${team.id},'SUPPORTER',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())`)
    insertedSupporter = { userId: supporter.userId, scopeId: team.id }

    const visibleTitle = `${RUN_TAG} reporter-visible`
    const visibleIncident = await createIncident(api, reporter.token, team.id, visibleTitle)
    createdIncidentIds.push(visibleIncident.id)
    const reporterTeamIds = new Set(reporterTeams.map((candidate) => candidate.id))
    const unrelatedTeam = adminTeams.find((candidate) => !reporterTeamIds.has(candidate.id))
    expect(unrelatedTeam, '報告者が所属しない管理者の別scopeチーム').toBeTruthy()
    const otherScopeIncident = await createIncident(
      api,
      admin.token,
      unrelatedTeam!.id,
      `${RUN_TAG} other-scope`,
    )
    createdIncidentIds.push(otherScopeIncident.id)

    await withLoggedInPage(browser, REPORTER, async (page) => {
      expect(await openIncidentList(page, teamSlug, team.id), '報告者の一覧取得').toBe(200)
      const incidentRow = page.getByRole('button', { name: new RegExp(visibleTitle) })
      await expect(incidentRow, '報告者の一覧に自身の事故報告が出る').toBeVisible({
        timeout: 20_000,
      })
      await incidentRow.click()
      await expect(page).toHaveURL(new RegExp(`\\?incidentId=${visibleIncident.id}$`))
      await expect(page.getByRole('heading', { name: visibleTitle })).toBeVisible()
    })

    await withLoggedInPage(browser, ADMIN, async (page) => {
      expect(await openIncidentList(page, teamSlug, team.id), '管理者の一覧取得').toBe(200)
      const incidentRow = page.getByRole('button', { name: new RegExp(visibleTitle) })
      await expect(incidentRow, '管理者は同scopeの事故報告を一覧で見られる').toBeVisible({
        timeout: 20_000,
      })
      await incidentRow.click()
      await expect(page).toHaveURL(new RegExp(`\\?incidentId=${visibleIncident.id}$`))
      await expect(page.getByRole('button', { name: 'ステータス変更' }), '管理者操作').toBeVisible()
    })

    await withLoggedInPage(browser, MEMBER, async (page) => {
      expect(await openIncidentList(page, teamSlug, team.id), '同scope MEMBERの一覧取得').toBe(200)
      await expect(page.getByText(visibleTitle), '同scope無関係MEMBERの一覧には出ない').toHaveCount(
        0,
      )
      await openConcealedDetail(page, teamSlug, visibleIncident.id)
    })

    await withLoggedInPage(browser, SUPPORTER, async (page) => {
      expect(await openIncidentList(page, teamSlug, team.id), 'SUPPORTERの一覧取得は403').toBe(403)
      await expect(page.getByText('インシデント一覧の取得に失敗しました')).toBeVisible()
      await expect(page.getByText(visibleTitle), 'SUPPORTERの一覧には出ない').toHaveCount(0)
      await openConcealedDetail(page, teamSlug, visibleIncident.id)
    })

    await withLoggedInPage(browser, REPORTER, async (page) => {
      await openConcealedDetail(page, teamSlug, otherScopeIncident.id)
    })

    const anonymous: BrowserContext = await browser.newContext()
    try {
      const page = await anonymous.newPage()
      await page.goto(`/teams/${teamSlug}/incidents?incidentId=${visibleIncident.id}`, {
        waitUntil: 'domcontentloaded',
      })
      await expect(page).toHaveURL(/\/login/)
    } finally {
      await anonymous.close()
    }
  } catch (error) {
    testFailure = error
  } finally {
    const clean = (label: string, action: () => void): void => {
      try {
        action()
      } catch (error) {
        cleanupErrors.push(`${label}: ${error instanceof Error ? error.message : String(error)}`)
      }
    }
    if (createdIncidentIds.length > 0) {
      const ids = createdIncidentIds.join(',')
      clean('incident_assignments', () =>
        mysql(`DELETE FROM incident_assignments WHERE incident_id IN (${ids})`),
      )
      clean('incident_comments', () =>
        mysql(`DELETE FROM incident_comments WHERE incident_id IN (${ids})`),
      )
      clean('incident_status_histories', () =>
        mysql(`DELETE FROM incident_status_histories WHERE incident_id IN (${ids})`),
      )
      clean('incidents', () => mysql(`DELETE FROM incidents WHERE id IN (${ids})`))
    }
    if (insertedSupporter) {
      const supporterMembership = insertedSupporter
      clean('SUPPORTER membership', () =>
        mysql(
          `DELETE FROM memberships WHERE user_id=${supporterMembership.userId} AND scope_type='TEAM' AND scope_id=${supporterMembership.scopeId} AND role_kind='SUPPORTER'`,
        ),
      )
    }
    if (insertedMember) {
      const memberMembership = insertedMember
      clean('MEMBER membership', () =>
        mysql(
          `DELETE FROM memberships WHERE user_id=${memberMembership.userId} AND scope_type='TEAM' AND scope_id=${memberMembership.scopeId} AND role_kind='MEMBER'`,
        ),
      )
    }
    try {
      await api.dispose()
    } catch (error) {
      cleanupErrors.push(`API context: ${error instanceof Error ? error.message : String(error)}`)
    }
  }
  if (testFailure) throw testFailure
  if (cleanupErrors.length > 0)
    throw new Error(`CMP-019 fixture cleanup failed\n${cleanupErrors.join('\n')}`)
})
