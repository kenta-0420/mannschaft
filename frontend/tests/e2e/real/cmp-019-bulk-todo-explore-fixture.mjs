/** Wave11 アリシゼーション専用 fixture。create / cleanup の明示実行以外では DB を変更しない。 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8081'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const evidenceDir = resolve('../docs/prototypes/evidence/cmp019-wave11')
const manifestPath = resolve(evidenceDir, 'fixture.json')
const scopes = [
  { type: 'team', slug: 'fc-u-18', numericId: 1, editor: 'e2e-dummy-1@test.mannschaft.local' },
  { type: 'organization', slug: 'org-000009', numericId: 9, editor: 'e2e-admin@test.mannschaft.local' },
]

function save(manifest) {
  mkdirSync(evidenceDir, { recursive: true })
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
}

function base(scope) {
  return `/api/v1/${scope.type === 'team' ? 'teams' : 'organizations'}/${scope.slug}`
}

async function login(email) {
  const response = await fetch(`${apiBase}/api/v1/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw new Error(`${email} login ${response.status}`)
  return (await response.json()).data.accessToken
}

async function call(method, path, token, body, expected) {
  const response = await fetch(`${apiBase}${path}`, {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!expected.includes(response.status)) {
    throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`)
  }
  const text = await response.text()
  return text ? JSON.parse(text) : undefined
}

function mysql(sql) {
  const jar = process.env.E2E_MYSQL_JDBC_JAR
  if (!jar || !process.env.E2E_MYSQL_USER || !process.env.E2E_MYSQL_PASSWORD) {
    throw new Error('E2E_MYSQL_JDBC_JAR / E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
  }
  return execFileSync('java', ['--class-path', jar, 'tests/e2e/real/MysqlExec.java', sql], {
    cwd: process.cwd(), env: process.env, encoding: 'utf8',
  }).trim()
}

async function create() {
  if (existsSync(manifestPath)) {
    const prior = JSON.parse(readFileSync(manifestPath, 'utf8'))
    if (!prior.cleanedAt) throw new Error(`未清掃 fixture が残っています: ${manifestPath}`)
  }
  const runTag = `CMP019_W11_EXPLORE_${Date.now()}`
  const manifest = { runTag, createdAt: new Date().toISOString(), scopes: [] }
  save(manifest)
  try {
    for (const scope of scopes) {
      const item = { ...scope, projectId: null, milestoneIds: [], todos: {} }
      manifest.scopes.push(item)
      save(manifest)
      const token = await login(scope.editor)
      const root = base(scope)
      item.projectId = (await call('POST', `${root}/projects`, token,
        { title: `${runTag}_${scope.type}_project`, description: 'Wave11 住民探索専用' }, [201])).data.id
      save(manifest)
      for (const [name, order] of [['先行', 0], ['後続', 1]]) {
        const id = (await call('POST', `${root}/projects/${item.projectId}/milestones`, token,
          { title: `${runTag}_${scope.type}_${name}`, sortOrder: order }, [201])).data.id
        item.milestoneIds.push(id)
        save(manifest)
      }
      for (const [key, milestoneId] of [
        ['open', item.milestoneIds[0]], ['guard', item.milestoneIds[0]],
        ['locked1', item.milestoneIds[1]], ['locked2', item.milestoneIds[1]],
      ]) {
        const title = `${runTag}_${scope.type}_${key}`
        const id = (await call('POST', `${root}/todos`, token,
          { title, projectId: item.projectId, milestoneId }, [201])).data.id
        item.todos[key] = { id, title }
        save(manifest)
      }
      if (scope.type === 'team') {
        await call('PATCH', `/api/v1/teams/${scope.numericId}/projects/${item.projectId}/milestones/${item.milestoneIds[1]}/initialize-gate`, token, undefined, [200])
      }
      else {
        const ids = [item.todos.locked1.id, item.todos.locked2.id]
        mysql(`UPDATE todos SET milestone_locked=TRUE WHERE id IN (${ids.join(',')}) AND scope_type='ORGANIZATION' AND scope_id=${scope.numericId} AND project_id=${item.projectId} AND milestone_id=${item.milestoneIds[1]}`)
      }
      const ids = Object.values(item.todos).map(todo => todo.id)
      const actual = mysql(`SELECT CONCAT(id, ':', milestone_locked) FROM todos WHERE id IN (${ids.join(',')}) ORDER BY id`)
      const state = new Map(actual.split(/\r?\n/).map(line => line.split(':').map(Number)))
      if (state.size !== 4 || state.get(item.todos.open.id) !== 0 || state.get(item.todos.guard.id) !== 0
        || state.get(item.todos.locked1.id) !== 1 || state.get(item.todos.locked2.id) !== 1) {
        throw new Error(`${scope.type} lock fixture 不整合: ${actual}`)
      }
      item.lockVerifiedAt = new Date().toISOString()
      save(manifest)
    }
  }
  catch (error) {
    console.error(`fixture 作成失敗。作成済み ID は ${manifestPath} に保存済みです。cleanup を実行してください。`)
    throw error
  }
  console.log(`fixture 作成完了: ${manifestPath}`)
}

async function cleanup() {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  const failures = []
  for (const item of manifest.scopes) {
    let token
    try { token = await login(item.editor) }
    catch (error) { failures.push(`${item.type} login: ${error}`); continue }
    const root = base(item)
    async function remove(path) {
      try { await call('DELETE', path, token, undefined, [204, 404]) }
      catch (error) { failures.push(String(error)) }
    }
    for (const todo of Object.values(item.todos).reverse()) await remove(`${root}/todos/${todo.id}`)
    for (const id of [...item.milestoneIds].reverse()) {
      await remove(`${root}/projects/${item.projectId}/milestones/${id}`)
    }
    if (item.projectId) await remove(`${root}/projects/${item.projectId}`)
  }
  if (failures.length > 0) throw new Error(`fixture cleanup 失敗: ${failures.join(' / ')}`)
  const todoIds = manifest.scopes.flatMap(item => Object.values(item.todos).map(todo => todo.id))
  const projectIds = manifest.scopes.map(item => item.projectId).filter(Boolean)
  if (todoIds.length > 0 && mysql(`SELECT COUNT(*) FROM todos WHERE id IN (${todoIds.join(',')}) AND deleted_at IS NULL`) !== '0') {
    throw new Error('fixture cleanup 後も active TODO が残っています')
  }
  if (projectIds.length > 0 && mysql(`SELECT COUNT(*) FROM projects WHERE id IN (${projectIds.join(',')}) AND deleted_at IS NULL`) !== '0') {
    throw new Error('fixture cleanup 後も active project が残っています')
  }
  manifest.cleanedAt = new Date().toISOString()
  save(manifest)
  console.log(`fixture cleanup 完了: ${manifestPath}`)
}

const mode = process.argv[2]
if (mode === 'create') await create()
else if (mode === 'cleanup') await cleanup()
else throw new Error('create または cleanup を指定してください')
