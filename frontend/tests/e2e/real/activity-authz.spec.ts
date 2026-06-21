/**
 * 甲 #1733 activity ドメイン認可 IDOR 根治 ― 実機 E2E（API 完結・モックなし）。
 *
 * 【検証対象】activity ドメインの認可欠落（IDOR）を塞いだ #1733 の実挙動を、実バックエンド直叩きで
 *   「他スコープ会員 → 403 COMMON_002 / 自スコープ会員 → 従来通り成功」の対で一気通貫実証する。
 *   対象エンドポイント（いずれもスコープ会員のみ実行可・非会員は 403=COMMON_002）:
 *     - GET  /api/v1/activities/stats           （AC-8 統計）
 *     - POST /api/v1/activities/{id}/duplicate   （AC-1/AC-2 活動複製・origin スコープで認可）
 *     - GET  /api/v1/activity-templates          （AC-7 テンプレ一覧）
 *     - POST /api/v1/activity-templates/{id}/duplicate（AC-6/AC-7 テンプレ複製・origin スコープで認可）
 *
 * 【非会員スコープの作り方（seed 汚染対策）】
 *   memberships テーブルは度重なる seed 再実行で e2e-user(id=23) が大半のチームに在籍している。
 *   そこで「非会員スコープ」は固定 seed に頼らず、admin がテスト内で使い捨てチームを新規作成する
 *   （作成者=admin のみ会員。e2e-user は確実に非会員）。この上にテンプレ・活動を admin が用意し、
 *   e2e-user が複製/一覧/統計を試みて 403 になることを確認する。これにより環境非依存で頑健。
 *
 * 【構成】API 完結（APIRequestContext のみ・browser/page 不使用）。FE dev サーバー（BASE_URL）には依存しない。
 *   WSL2 の FE↔BE 到達/CORS の影響を受けず、認可ゲートの本物の挙動だけを検証する。
 *   BE は BE_ORIGIN（既定 http://127.0.0.1:8081＝検証用 worktree ポート）。
 *
 * 実行: cd frontend && BE_ORIGIN=http://127.0.0.1:8081 \
 *         npx playwright test --config=playwright-f0810-api.config.ts tests/e2e/real/activity-authz.spec.ts
 *
 * 前提データ: backend/scripts/seed-e2e-data.js 実行済み（e2e-user/e2e-admin・fc-u-18 が存在）。
 * 戦役台帳: .claude/campaigns/2026-06-20-record-audit-fixes.md（AC-1〜9）
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'

// storageState に依存せず、テスト内で API ログインする（f0810 api spec と同作法）。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.BE_ORIGIN ?? process.env.BASE_URL ?? 'http://127.0.0.1:8081'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// ── テスト状態 ──────────────────────────────────────────────────
let api: APIRequestContext
let adminToken: string
let userToken: string

// 非会員スコープ（admin が新規作成。e2e-user は非会員）
let otherTeamId: number
let otherTemplateId: number
let otherActivityId: number

// 自スコープ（e2e-user が会員）
let ownTeamId: number
let ownActivityId: number

// ── ヘルパー ────────────────────────────────────────────────────
async function login(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

function h(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function myTeams(token: string): Promise<Array<{ id: number; slug: string; role: string }>> {
  const res = await api.get(`${BE_API}/me/teams?limit=200`, { headers: h(token) })
  expect(res.status(), '/me/teams は 200').toBe(200)
  return (await res.json() as { data: Array<{ id: number; slug: string; role: string }> }).data
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(ADMIN_EMAIL, ADMIN_PASSWORD)
  userToken = await login(USER_EMAIL, USER_PASSWORD)

  // ── 自スコープ解決: e2e-user が会員のチーム（fc-u-18 優先・無ければ先頭） ──
  const userTeams = await myTeams(userToken)
  expect(userTeams.length, 'e2e-user は最低 1 チームに所属（seed 前提）').toBeGreaterThan(0)
  const ownTeam = userTeams.find((t) => t.slug === 'fc-u-18') ?? userTeams[0]
  ownTeamId = ownTeam.id

  // 自スコープの既存活動 1 件（seed が fc-u-18 に活動を投入済み）。複製 200 検証の元。
  const ownList = await api.get(
    `${BE_API}/activities?scope_type=TEAM&scope_id=${ownTeamId}&limit=1`,
    { headers: h(userToken) },
  )
  expect(ownList.status(), '自スコープ活動一覧は 200').toBe(200)
  const ownActs = (await ownList.json() as { data: Array<{ id: number }> }).data
  expect(ownActs.length, '自スコープに活動が最低 1 件（seed 前提）').toBeGreaterThan(0)
  ownActivityId = ownActs[0].id

  // ── 非会員スコープを admin が新規作成（seed 汚染に依存しない使い捨てチーム） ──
  const uniqueName = `IDOR-E2E-Other-${Date.now()}`
  const createTeam = await api.post(`${BE_API}/teams`, {
    headers: h(adminToken),
    data: { name: uniqueName, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(createTeam.status(), '使い捨てチーム作成は 201').toBe(201)
  const createdSlug = (await createTeam.json() as { data: { slug: string } }).data.slug

  // 数値 ID は作成応答に出ない（id=slug）ため admin の /me/teams から解決。
  const adminTeams = await myTeams(adminToken)
  const other = adminTeams.find((t) => t.slug === createdSlug)
  expect(other, '作成した使い捨てチームが admin の /me/teams に存在').toBeTruthy()
  otherTeamId = other!.id

  // e2e-user が当該チームの非会員であることを保証（作成者 admin のみ会員のはず）。
  const userTeamsAfter = await myTeams(userToken)
  expect(
    userTeamsAfter.some((t) => t.id === otherTeamId),
    'e2e-user は使い捨てチームの非会員（IDOR 検証の前提）',
  ).toBe(false)

  // 非会員スコープにテンプレ + 活動を admin が用意（複製/一覧の origin スコープになる）。
  const createTpl = await api.post(
    `${BE_API}/activity-templates?scope_type=TEAM&scope_id=${otherTeamId}`,
    { headers: h(adminToken), data: { name: 'IDOR-E2E-Tpl' } },
  )
  expect(createTpl.status(), '非会員スコープのテンプレ作成（admin）は 200/201').toBeLessThan(300)
  otherTemplateId = (await createTpl.json() as { data: { id: number } }).data.id

  const createAct = await api.post(
    `${BE_API}/activities?scope_type=TEAM&scope_id=${otherTeamId}`,
    {
      headers: h(adminToken),
      data: { templateId: otherTemplateId, title: 'IDOR-E2E-Act', activityDate: '2026-04-01' },
    },
  )
  expect(createAct.status(), '非会員スコープの活動作成（admin）は 201').toBe(201)
  otherActivityId = (await createAct.json() as { data: { id: number } }).data.id
})

test.afterAll(async () => {
  await api?.dispose()
})

// ── AC-8: 統計 ─────────────────────────────────────────────────
test('AC-8 自スコープ会員の活動統計は 200', async () => {
  const res = await api.get(
    `${BE_API}/activities/stats?scope_type=TEAM&scope_id=${ownTeamId}`,
    { headers: h(userToken) },
  )
  expect(res.status(), '自スコープ統計は 200').toBe(200)
})

test('AC-8 他スコープ非会員の活動統計は 403 COMMON_002', async () => {
  const res = await api.get(
    `${BE_API}/activities/stats?scope_type=TEAM&scope_id=${otherTeamId}`,
    { headers: h(userToken) },
  )
  expect(res.status(), '他スコープ統計は 403').toBe(403)
  const body = await res.json() as { error: { code: string } }
  expect(body.error.code, 'エラーコードは COMMON_002').toBe('COMMON_002')
})

// ── AC-1 / AC-2: 活動複製（origin スコープで認可） ───────────────
test('AC-2 自スコープ会員の活動複製は成功（201）', async () => {
  const res = await api.post(`${BE_API}/activities/${ownActivityId}/duplicate`, {
    headers: h(userToken),
    data: {},
  })
  expect(res.status(), '自スコープ複製は 201').toBe(201)
})

test('AC-1 他スコープ非会員の活動複製は 403 COMMON_002', async () => {
  const res = await api.post(`${BE_API}/activities/${otherActivityId}/duplicate`, {
    headers: h(userToken),
    data: {},
  })
  expect(res.status(), '他スコープ複製は 403').toBe(403)
  const body = await res.json() as { error: { code: string } }
  expect(body.error.code, 'エラーコードは COMMON_002').toBe('COMMON_002')
})

// ── AC-7: テンプレ一覧 ─────────────────────────────────────────
test('AC-7 他スコープ非会員のテンプレ一覧は 403 COMMON_002', async () => {
  const res = await api.get(
    `${BE_API}/activity-templates?scope_type=TEAM&scope_id=${otherTeamId}`,
    { headers: h(userToken) },
  )
  expect(res.status(), '他スコープのテンプレ一覧は 403').toBe(403)
  const body = await res.json() as { error: { code: string } }
  expect(body.error.code, 'エラーコードは COMMON_002').toBe('COMMON_002')
})

// ── AC-6 / AC-7: テンプレ複製（origin スコープで認可） ───────────
test('AC-6 他スコープ非会員のテンプレ複製は 403 COMMON_002', async () => {
  const res = await api.post(`${BE_API}/activity-templates/${otherTemplateId}/duplicate`, {
    headers: h(userToken),
    // 複製先は自スコープを指定するが、origin（他スコープ）の会員判定で 403 になる。
    data: { targetScopeType: 'TEAM', targetScopeId: ownTeamId, name: 'IDOR-E2E-TplDup' },
  })
  expect(res.status(), 'origin が他スコープのテンプレ複製は 403').toBe(403)
  const body = await res.json() as { error: { code: string } }
  expect(body.error.code, 'エラーコードは COMMON_002').toBe('COMMON_002')
})
