/**
 * F07.6 / CMP-019: インシデントコメント一覧の実API + MySQL E2E。
 *
 * コメントPOSTがまだ提供されていないため、インシデントはAPIで作成し、
 * コメント行だけをMySQLへ投入する。添付やR2には触れない。
 */
import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { execFileSync } from 'node:child_process'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${BE}/api/v1`
const ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const REPORTER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const ASSIGNEE = {
  email: process.env.TEST_ASSIGNEE_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_ASSIGNEE_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const RUN_TAG = `CMP019_INC_COMMENTS_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

type Session = { token: string; userId: number }
type Team = { id: number; slug?: string; role?: string }
type Incident = { id: number }
type Comment = { id: number; incidentId: number; userId: number; user?: { id: number; displayName: string }; body: string; isInternal: boolean; createdAt: string }

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

function mysql(statement: string): void {
  if (!MYSQL_USER || !MYSQL_PASSWORD) throw new Error('E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  const args = [
    'exec', 'mannschaft-mysql', 'mysql',
    `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, 'mannschaft', `--execute=${statement}`,
  ]
  if (process.platform === 'win32') {
    execFileSync('wsl.exe', ['-e', 'docker', ...args], { stdio: 'pipe' })
  } else {
    execFileSync('docker', args, { stdio: 'pipe' })
  }
}

async function login(api: APIRequestContext, credentials: typeof ADMIN): Promise<Session> {
  const response = await api.post(`${API}/auth/login`, { data: credentials })
  expect(response.status(), `${credentials.email} のログイン`).toBe(200)
  const token = (await response.json() as { data: { accessToken: string } }).data.accessToken
  const me = await api.get(`${API}/users/me`, { headers: headers(token) })
  expect(me.status(), `${credentials.email} のユーザー情報`).toBe(200)
  return { token, userId: (await me.json() as { data: { id: number } }).data.id }
}

async function teams(api: APIRequestContext, token: string): Promise<Team[]> {
  const response = await api.get(`${API}/me/teams?limit=200`, { headers: headers(token) })
  expect(response.status(), '/me/teams').toBe(200)
  return (await response.json() as { data: Team[] }).data
}

async function createIncident(api: APIRequestContext, token: string, scopeId: number, title: string): Promise<Incident> {
  const response = await api.post(`${API}/incidents`, {
    headers: headers(token),
    data: { scopeType: 'TEAM', scopeId, title, description: `${RUN_TAG} description`, priority: 'MEDIUM' },
  })
  expect(response.status(), 'インシデント作成').toBe(201)
  return (await response.json() as { data: Incident }).data
}

async function listComments(api: APIRequestContext, token: string, incidentId: number): Promise<Comment[]> {
  const response = await api.get(`${API}/incidents/${incidentId}/comments`, { headers: headers(token) })
  expect(response.status(), `コメント一覧(${incidentId})`).toBe(200)
  return ((await response.json() as { data: Comment[] }).data ?? [])
}

test('CMP-019: public/internal・報告者/担当者・404秘匿・削除済み非表示・昇順・空一覧', async () => {
  test.skip(!MYSQL_USER || !MYSQL_PASSWORD, 'E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が未設定')
  const api = await pwRequest.newContext()
  const createdIncidentIds: number[] = []
  let commentBodies: string[] = []
  let generatedMembership: { userId: number; scopeId: number } | null = null
  let generatedSupporterMembership: { userId: number; scopeId: number } | null = null

  try {
    const admin = await login(api, ADMIN)
    const reporter = await login(api, REPORTER)
    const assignee = await login(api, ASSIGNEE)
    const supporter = await login(api, {
      email: process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local',
      password: process.env.TEST_SUPPORTER_PASSWORD ?? 'TestPass2026!',
    })
    const reporterTeams = await teams(api, reporter.token)
    expect(reporterTeams.length, '報告者が所属するチーム').toBeGreaterThan(0)
    const reporterTeamIds = new Set(reporterTeams.map((team) => team.id))
    const adminTeams = await teams(api, admin.token)
    const ownTeam = reporterTeams[0]!
    expect(adminTeams.some((team) => team.id === ownTeam.id), '管理者が報告者チームに所属').toBe(true)
    expect((await teams(api, assignee.token)).some((team) => team.id === ownTeam.id), 'E2E担当者は事前には報告者チームに所属しない').toBe(false)
    mysql(`INSERT INTO memberships (user_id,scope_type,scope_id,role_kind,joined_at,created_at,updated_at)
      SELECT ${assignee.userId},'TEAM',${ownTeam.id},'MEMBER',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP()
      WHERE NOT EXISTS (SELECT 1 FROM memberships WHERE user_id=${assignee.userId} AND scope_type='TEAM' AND scope_id=${ownTeam.id} AND left_at IS NULL);`)
    generatedMembership = { userId: assignee.userId, scopeId: ownTeam.id }
    expect((await teams(api, assignee.token)).some((team) => team.id === ownTeam.id), '担当者を報告者チームのMEMBERに追加').toBe(true)
    expect((await teams(api, supporter.token)).some((team) => team.id === ownTeam.id), 'E2E SUPPORTERは事前には報告者チームに所属しない').toBe(false)
    mysql(`INSERT INTO memberships (user_id,scope_type,scope_id,role_kind,joined_at,created_at,updated_at)
      VALUES (${supporter.userId},'TEAM',${ownTeam.id},'SUPPORTER',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP());`)
    generatedSupporterMembership = { userId: supporter.userId, scopeId: ownTeam.id }
    expect((await teams(api, supporter.token)).some((team) => team.id === ownTeam.id), 'SUPPORTERが報告者チームに所属').toBe(true)
    const unrelatedTeam = adminTeams.find((team) => !reporterTeamIds.has(team.id))
    expect(unrelatedTeam, '報告者が所属していない管理者チーム').toBeTruthy()

    // 報告者のインシデント。一般MEMBERをUSER担当者として割り当てる。
    const incident = await createIncident(api, reporter.token, ownTeam.id, `${RUN_TAG} visible`)
    createdIncidentIds.push(incident.id)
    mysql(`INSERT INTO incident_assignments (incident_id,assignee_type,user_id,created_at)
      VALUES (${incident.id},'USER',${assignee.userId},UTC_TIMESTAMP());`)

    const publicOld = `${RUN_TAG}_public_old`
    const internal = `${RUN_TAG}_internal`
    const publicNew = `${RUN_TAG}_public_new`
    const deleted = `${RUN_TAG}_deleted`
    commentBodies = [publicOld, internal, publicNew, deleted]
    mysql(`
      INSERT INTO incident_comments (incident_id,user_id,body,is_internal,version,created_at,updated_at,deleted_at)
      VALUES (${incident.id},${reporter.userId},'${publicOld}',0,0,UTC_TIMESTAMP()-INTERVAL 4 MINUTE,UTC_TIMESTAMP()-INTERVAL 4 MINUTE,NULL),
             (${incident.id},${admin.userId},'${internal}',1,0,UTC_TIMESTAMP()-INTERVAL 3 MINUTE,UTC_TIMESTAMP()-INTERVAL 3 MINUTE,NULL),
             (${incident.id},${reporter.userId},'${publicNew}',0,0,UTC_TIMESTAMP()-INTERVAL 2 MINUTE,UTC_TIMESTAMP()-INTERVAL 2 MINUTE,NULL),
             (${incident.id},${admin.userId},'${deleted}',0,0,UTC_TIMESTAMP()-INTERVAL 1 MINUTE,UTC_TIMESTAMP()-INTERVAL 1 MINUTE,UTC_TIMESTAMP());
    `)

    const adminComments = await listComments(api, admin.token, incident.id)
    expect(adminComments.map((comment) => comment.body)).toEqual([publicOld, internal, publicNew])
    expect(adminComments.map((comment) => comment.isInternal)).toEqual([false, true, false])
    expect(adminComments.map((comment) => Date.parse(comment.createdAt))).toEqual(
      [...adminComments.map((comment) => Date.parse(comment.createdAt))].sort((a, b) => a - b),
    )

    const reporterComments = await listComments(api, reporter.token, incident.id)
    expect(reporterComments.map((comment) => comment.body)).toEqual([publicOld, publicNew])
    expect(reporterComments.every((comment) => !comment.isInternal)).toBe(true)

    const assigneeComments = await listComments(api, assignee.token, incident.id)
    expect(assigneeComments.map((comment) => comment.body)).toEqual([publicOld, publicNew])
    expect(assigneeComments.every((comment) => !comment.isInternal)).toBe(true)

    // 同scopeのSUPPORTERでも報告者/担当者でなければ404（ロールだけでは可視化しない）。
    const supporterConcealed = await api.get(`${API}/incidents/${incident.id}/comments`, {
      headers: headers(supporter.token),
    })
    expect(supporterConcealed.status(), '無関係SUPPORTERには404').toBe(404)
    expect(adminComments[0]).toEqual(expect.objectContaining({
      id: expect.any(Number), incidentId: incident.id, userId: expect.any(Number),
      user: expect.objectContaining({ id: expect.any(Number), displayName: expect.any(String) }),
      body: publicOld, isInternal: false,
      createdAt: expect.any(String),
    }))

    // 同一スコープ所属でも、報告者/担当者でない一般MEMBERにはインシデントを秘匿する。
    const sameScopeIncident = await createIncident(api, admin.token, ownTeam.id, `${RUN_TAG} same-scope-unrelated`)
    createdIncidentIds.push(sameScopeIncident.id)
    const sameScopeConcealed = await api.get(`${API}/incidents/${sameScopeIncident.id}/comments`, {
      headers: headers(reporter.token),
    })
    expect(sameScopeConcealed.status(), '同一スコープの無関係MEMBERにも404').toBe(404)

    // 報告者が所属しない同一管理者スコープのインシデントは存在を秘匿する。
    const unrelatedIncident = await createIncident(api, admin.token, unrelatedTeam!.id, `${RUN_TAG} unrelated`)
    createdIncidentIds.push(unrelatedIncident.id)
    const concealed = await api.get(`${API}/incidents/${unrelatedIncident.id}/comments`, {
      headers: headers(reporter.token),
    })
    expect(concealed.status(), '無関係ユーザーには404').toBe(404)

    const emptyIncident = await createIncident(api, reporter.token, ownTeam.id, `${RUN_TAG} empty`)
    createdIncidentIds.push(emptyIncident.id)
    await expect(listComments(api, reporter.token, emptyIncident.id)).resolves.toEqual([])

    const missing = await api.get(`${API}/incidents/9223372036854775807/comments`, {
      headers: headers(reporter.token),
    })
    expect(missing.status(), '存在しないインシデントは404').toBe(404)
    expect((await missing.json() as { error: { code: string } }).error.code).toBe('INCIDENT_002')

    const deletedIncident = await createIncident(api, reporter.token, ownTeam.id, `${RUN_TAG} deleted-parent`)
    createdIncidentIds.push(deletedIncident.id)
    mysql(`UPDATE incidents SET deleted_at=UTC_TIMESTAMP() WHERE id=${deletedIncident.id};`)
    const deletedParentComments = await api.get(`${API}/incidents/${deletedIncident.id}/comments`, {
      headers: headers(reporter.token),
    })
    expect(deletedParentComments.status(), '削除済みインシデントは404').toBe(404)
    expect((await deletedParentComments.json() as { error: { code: string } }).error.code).toBe('INCIDENT_002')
  } finally {
    try {
      // 生成した行だけを参照順にローカルMySQLから清掃する。
      if (commentBodies.length > 0) {
        const bodies = commentBodies.map((body) => `'${body.replace(/'/g, "''")}'`).join(',')
        mysql(`DELETE FROM incident_comments WHERE body IN (${bodies});`)
      }
      if (generatedMembership) {
        mysql(`DELETE FROM memberships WHERE user_id=${generatedMembership.userId} AND scope_type='TEAM' AND scope_id=${generatedMembership.scopeId} AND role_kind='MEMBER';`)
      }
      if (generatedSupporterMembership) {
        mysql(`DELETE FROM memberships WHERE user_id=${generatedSupporterMembership.userId} AND scope_type='TEAM' AND scope_id=${generatedSupporterMembership.scopeId} AND role_kind='SUPPORTER';`)
      }
      if (createdIncidentIds.length > 0) {
        const ids = createdIncidentIds.join(',')
        mysql(`DELETE FROM incident_assignments WHERE incident_id IN (${ids});`)
        mysql(`DELETE FROM incident_comments WHERE incident_id IN (${ids});`)
        mysql(`DELETE FROM incidents WHERE id IN (${ids});`)
      }
    } finally {
      await api.dispose()
    }
  }
})
