import { test, expect, request, type APIRequestContext, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/** CMP-261012-2338: 現役の管理UI経路・取得consumer・管理権限を実物で確認する。 */
test.use({ storageState: { cookies: [], origins: [] }, trace: 'off', video: 'off', screenshot: 'off' })
test.describe.configure({ mode: 'serial' })

const API = `${process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'}/api/v1`
const admin = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}
const member = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const outsider = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}

type Credentials = { email: string; password: string }
type Actor = { api: APIRequestContext; id: number }
type OwnedTeam = { scope: 'TEAM'; id: number; slug: string; name: string; owner: Actor }
type ModuleItem = { moduleId: number; name: string; isEnabled: boolean; requiresPaidPlan: boolean; levelAvailable: boolean }
type Catalog = { modules: ModuleItem[]; enabledCount: number; planLimit: number }
type OwnedInvite = { id: number; token: string; team: OwnedTeam }

function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('API object契約に不一致')
  return value as Record<string, unknown>
}
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error('API整数契約に不一致')
  return value
}
function text(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) throw new Error('API文字列契約に不一致')
  return value
}
function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('API真偽値契約に不一致')
  return value
}
function data(value: unknown): Record<string, unknown> {
  return object(object(value).data)
}

async function loginApi(credentials: Credentials, contexts: APIRequestContext[]): Promise<Actor> {
  // ログイン応答Cookieはこの短命contextで破棄する。UIにも後続APIにも共有しない。
  const login = await request.newContext()
  let token: string
  try {
    const response = await login.post(`${API}/auth/login`, { data: credentials })
    expect(response.status(), 'fixture用APIログイン').toBe(200)
    token = text(data(await response.json()).accessToken)
  }
  finally {
    await login.dispose()
  }
  const api = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${token}` } })
  contexts.push(api)
  const response = await api.get(`${API}/users/me`)
  expect(response.status()).toBe(200)
  const profile = data(await response.json())
  expect(profile.systemRole, 'SYSTEM_ADMINによる認可迂回を使わない').toBeNull()
  return { api, id: integer(profile.id) }
}

async function createTeam(owner: Actor, name: string, teams: OwnedTeam[]): Promise<OwnedTeam> {
  const response = await owner.api.post(`${API}/teams`, { data: { name, visibility: 'PUBLIC' } })
  expect(response.status(), '使い捨てチーム作成').toBe(201)
  const created = data(await response.json())
  const team: OwnedTeam = { scope: 'TEAM', id: integer(created.numericId), slug: text(created.slug), name, owner }
  teams.push(team)
  expect(object(created.basicInfo).name).toBe(name)
  await assertOwnedTeam(team)
  return team
}

async function assertOwnedTeam(team: OwnedTeam): Promise<void> {
  expect(team.scope).toBe('TEAM')
  expect(team.name).toMatch(/^CMP2338-/)
  const response = await team.owner.api.get(`${API}/teams/${team.slug}`)
  expect(response.status(), '削除対象の所有情報を実GETで照合').toBe(200)
  const current = data(await response.json())
  expect(integer(current.numericId)).toBe(team.id)
  expect(current.slug).toBe(team.slug)
  expect(object(current.basicInfo).name).toBe(team.name)
  await expectRole(team.owner, team, 'ADMIN')
}

async function expectRole(actor: Actor, team: OwnedTeam, role: string | null): Promise<void> {
  const response = await actor.api.get(`${API}/teams/${team.slug}/me/permissions`)
  expect(response.status()).toBe(200)
  expect(data(await response.json()).roleName).toBe(role)
}

async function catalog(team: OwnedTeam): Promise<Catalog> {
  const response = await team.owner.api.get(`${API}/teams/${team.slug}/modules/catalog`)
  expect(response.status()).toBe(200)
  const current = data(await response.json())
  if (!Array.isArray(current.modules)) throw new Error('modules配列契約に不一致')
  return {
    enabledCount: integer(current.enabledCount),
    planLimit: integer(current.planLimit),
    modules: current.modules.map((value: unknown) => {
      const item = object(value)
      return {
        moduleId: integer(item.moduleId), name: text(item.name), isEnabled: boolean(item.isEnabled),
        requiresPaidPlan: boolean(item.requiresPaidPlan), levelAvailable: boolean(item.levelAvailable),
      }
    }),
  }
}

async function loginBrowser(page: Page, credentials: Credentials): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('input#email').fill(credentials.email)
  await page.locator('input[type="password"]').fill(credentials.password)
  const [response] = await Promise.all([
    page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/auth/login' && r.request().method() === 'POST'),
    page.getByRole('button', { name: 'ログイン', exact: true }).click(),
  ])
  expect(response.status(), 'UIフォームの正規ログイン').toBe(200)
  await page.waitForURL(url => !url.pathname.includes('/login'))
}

function moduleCard(page: Page, name: string) {
  return page.locator('div.rounded-xl:has(.pi-puzzle)').filter({ has: page.getByText(name, { exact: true }) })
}

async function toggle(page: Page, team: OwnedTeam, target: ModuleItem, enabled: boolean, initial: Catalog): Promise<void> {
  const [response] = await Promise.all([
    page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/modules/${target.moduleId}/toggle` && r.request().method() === 'PATCH'),
    moduleCard(page, target.name).getByRole('switch').click(),
  ])
  const body: unknown = response.request().postDataJSON()
  expect(body, '現役toggleModuleが要求するbody').toEqual({ moduleId: target.moduleId, enabled })
  expect(response.status(), '実Controllerの切替成功は200').toBe(200)
  await expect(page.getByText(`${target.name} を${enabled ? '有効' : '無効'}にしました`, { exact: true })).toBeVisible()
  await expect(moduleCard(page, target.name).getByRole('switch')).toBeChecked({ checked: enabled })
  await expect(page.getByText(`有効な機能: ${initial.enabledCount + (enabled ? 1 : 0)} / ${initial.planLimit}`, { exact: true })).toBeVisible()
}

async function denyEdit(page: Page, actor: Actor, team: OwnedTeam, target: ModuleItem): Promise<void> {
  await page.goto(`/teams/${team.slug}/modules`)
  await waitForHydration(page)
  await expect(page).toHaveURL(new RegExp(`/teams/${team.slug}$`))
  await expect(page.getByRole('tab', { name: '機能設定', exact: true })).toHaveCount(0)
  await expect(page.locator('div.rounded-xl:has(.pi-puzzle)')).toHaveCount(0)
  // TeamModuleController#toggleTeamModuleのisAdminチェック→COMMON_002→403。
  const denied = await actor.api.patch(`${API}/teams/${team.slug}/modules/${target.moduleId}/toggle`, {
    data: { moduleId: target.moduleId, enabled: true },
  })
  expect(denied.status(), 'MEMBER・他tenant ADMINとも正規bodyでも403').toBe(403)
  expect(object(object(await denied.json()).error).code).toBe('COMMON_002')
}

test('ADMINの実UI切替・永続・復元とMEMBER／他tenantの管理直URL拒否', async ({ browser }, testInfo: TestInfo) => {
  test.setTimeout(300_000)
  const apiContexts: APIRequestContext[] = []
  const browserContexts: BrowserContext[] = []
  const teams: OwnedTeam[] = []
  let invitation: OwnedInvite | undefined
  const cleanupErrors: unknown[] = []
  const prefix = `CMP2338-${Date.now()}`
  const freshPage = async () => {
    const context = await browser.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'ja-JP', storageState: { cookies: [], origins: [] } })
    browserContexts.push(context)
    return context.newPage()
  }
  try {
    const owner = await loginApi(admin, apiContexts)
    const user = await loginApi(member, apiContexts)
    const other = await loginApi(outsider, apiContexts)
    expect(new Set([owner.id, user.id, other.id]).size, '独立した3ユーザー').toBe(3)
    const team = await createTeam(owner, `${prefix}-owner`, teams)
    const otherTeam = await createTeam(other, `${prefix}-outsider`, teams)
    await expectRole(other, otherTeam, 'ADMIN')
    await expectRole(other, team, null)

    const inviteResponse = await owner.api.post(`${API}/teams/${team.slug}/invite-tokens`, {
      data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
    })
    expect(inviteResponse.status()).toBe(201)
    const invite = data(await inviteResponse.json())
    invitation = { id: integer(invite.id), token: text(invite.token), team }
    expect(invite.roleName).toBe('MEMBER')
    const joined = await user.api.post(`${API}/invite/${invitation.token}/join`)
    expect(joined.status()).toBe(200)
    await expectRole(user, team, 'MEMBER')

    const initial = await catalog(team)
    // ModuleService.activeOptionalModules()がcatalog母集合をOPTIONAL限定にしている。
    const target = initial.modules.find(item => !item.requiresPaidPlan && item.levelAvailable && !item.isEnabled)
    expect(target, '非有料・レベル利用可・未有効のOPTIONALが必須').toBeDefined()
    if (!target) throw new Error('切替可能なOPTIONALが存在しない')
    expect(initial.enabledCount, '有効化のプラン枠がある').toBeLessThan(initial.planLimit)

    const page = await freshPage()
    await loginBrowser(page, admin)
    const [shellModules] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/modules` && r.request().method() === 'GET'),
      page.goto(`/teams/${team.slug}`),
    ])
    expect(shellModules.status(), 'team shellのgetTeamModules consumer').toBe(200)
    await waitForHydration(page)
    await expect(page.getByText(team.name, { exact: true }).first()).toBeVisible()
    const [sidebarModules] = await Promise.all([
      page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/modules` && r.request().method() === 'GET'),
      page.getByTestId('scope-sidebar-toggle').click(),
    ])
    expect(sidebarModules.status(), 'BaseSidebarのgetTeamModules consumer').toBe(200)
    const drawer = page.locator('.p-drawer')
    await expect(drawer.locator('nav')).toBeVisible()
    await expect(drawer.locator(`a[href^="/teams/${team.slug}"]`).first()).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
    await page.getByRole('tab', { name: '機能設定', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/teams/${team.slug}/modules$`))
    await expect(moduleCard(page, target.name)).toHaveCount(1)
    await expect(moduleCard(page, target.name).getByRole('switch')).not.toBeChecked()
    await toggle(page, team, target, true, initial)
    await page.reload()
    await waitForHydration(page)
    await expect(moduleCard(page, target.name).getByRole('switch')).toBeChecked()
    await expect(page.getByText(`有効な機能: ${initial.enabledCount + 1} / ${initial.planLimit}`, { exact: true })).toBeVisible()
    await toggle(page, team, target, false, initial)
    const restored = await catalog(team)
    expect(restored.enabledCount).toBe(initial.enabledCount)
    expect(restored.modules.find(item => item.moduleId === target.moduleId)?.isEnabled).toBe(false)

    const memberPage = await freshPage()
    await loginBrowser(memberPage, member)
    await denyEdit(memberPage, user, team, target)
    const otherPage = await freshPage()
    await loginBrowser(otherPage, outsider)
    await denyEdit(otherPage, other, team, target)
    const unchanged = await catalog(team)
    expect(unchanged.enabledCount).toBe(initial.enabledCount)
    expect(unchanged.modules.find(item => item.moduleId === target.moduleId)?.isEnabled).toBe(false)
  }
  finally {
    // 途中setup失敗でも作成できた所有fixtureだけ回収。各失敗を集約して隠さない。
    for (const context of browserContexts) {
      try { await context.close() }
      catch (error) { cleanupErrors.push(error) }
    }
    if (invitation) {
      try {
        const owned = invitation
        await assertOwnedTeam(owned.team)
        const path = `${API}/teams/${owned.team.slug}/invite-tokens`
        const response = await owned.team.owner.api.get(path)
        expect(response.status()).toBe(200)
        const list: unknown = object(await response.json()).data
        if (!Array.isArray(list)) throw new Error('招待一覧配列契約に不一致')
        const exact = list.map(object).find(item => item.id === owned.id)
        expect(exact?.token === owned.token, '所有scope内の今回発行した招待だけ失効').toBe(true)
        expect((await owned.team.owner.api.delete(`${path}/${owned.id}`)).status()).toBe(204)
        const after = await owned.team.owner.api.get(path)
        expect(after.status()).toBe(200)
        const remaining: unknown = object(await after.json()).data
        if (!Array.isArray(remaining)) throw new Error('招待一覧配列契約に不一致')
        expect(remaining.map(object).some(item => item.id === owned.id)).toBe(false)
      }
      catch (error) { cleanupErrors.push(error) }
    }
    for (const team of [...teams].reverse()) {
      try {
        await assertOwnedTeam(team)
        expect((await team.owner.api.delete(`${API}/teams/${team.slug}`)).status(), '所有チーム削除').toBe(204)
        expect((await team.owner.api.get(`${API}/teams/${team.slug}`)).status(), '削除後は404').toBe(404)
      }
      catch (error) { cleanupErrors.push(error) }
    }
    for (const context of apiContexts) {
      try { await context.dispose() }
      catch (error) { cleanupErrors.push(error) }
    }
    // token・cookie・資格情報を含めず、回収対象の所有manifestだけ証拠に残す。
    await testInfo.attach('CMP2338-fixture-manifest', {
      body: JSON.stringify({ teams: teams.map(team => ({ scope: team.scope, id: team.id, slug: team.slug, name: team.name, ownerId: team.owner.id })), invitation: invitation ? { id: invitation.id, scope: 'TEAM', scopeId: invitation.team.id } : null, cleanupFailureCount: cleanupErrors.length }),
      contentType: 'application/json',
    })
    if (cleanupErrors.length > 0) throw new AggregateError(cleanupErrors, 'CMP2338所有fixture回収が未完了')
  }
})
