import { readFileSync } from 'node:fs'
import { expect, test, type Browser, type BrowserContext, type Page, type Response, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP0232/1533の投票側だけを実BE/実画面で確認する。
 * Team自然入口は別PRのCMP1538、点呼側1533は別specの責務。
 * API補助は既存loginViaApiと、このspecがUI作成したセッションの後始末だけ。
 * 実行: pinned Nodeでplaywright/cli.js test <本spec> -c playwright-real.config.ts
 *       --project=chromium-real --no-deps --workers=1
 * 共有setup/保存済み認証は使わず、専用fixtureの利用者ごとに1context/1loginにする。
 */
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(240_000)

type Kind = 'TEAM' | 'ORGANIZATION'
type ActorKey = 'admin' | 'deputyGranted' | 'deputyDenied' | 'member' | 'systemOnly' | 'outsider' | 'deputyGrantedSystem'
interface Scope { slug: string; numericId: number }
interface Fixture {
  sourceHead: string
  urls: { frontend: string; backend: string }
  actors: Record<ActorKey, { email: string; password: string; userId: number }>
  scopes: Record<Kind, Scope>
  foreignScopes: Record<Kind, Scope>
}
interface Session { id: number; title: string; scopeType: Kind; resolutionMode: string; meetingDate?: string | null; motionCount?: number; eligibleCount?: number }
interface OpenActor { page: Page; context: BrowserContext }

let fixture: Fixture
const opened = new Map<ActorKey, OpenActor>()
const created: Array<{ actor: ActorKey; session: Session }> = []
const evidence: Array<Record<string, unknown>> = []
let evidenceStart = 0
const KINDS: Kind[] = ['ORGANIZATION', 'TEAM']
const CREATE = '投票セッションを作成'
const EMPTY = '投票セッションがありません'
const MEETING_DATE = '2030-01-15'

function scopePath(kind: Kind, scope: Scope): string {
  return `/${kind === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}`
}

async function actorPage(browser: Browser, actor: ActorKey): Promise<Page> {
  const cached = opened.get(actor)
  if (cached) return cached.page
  const context = await browser.newContext({
    baseURL: fixture.urls.frontend,
    storageState: { cookies: [], origins: [] },
    locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1440, height: 1000 },
  })
  const page = await context.newPage()
  opened.set(actor, { page, context })
  await loginViaApi(page, fixture.actors[actor], { apiBaseUrl: fixture.urls.backend })
  // 同一contextの認証前提を確認し、ラベルだけで利用者/SYS条件を判定しない。
  const me = await page.request.get(`${fixture.urls.backend}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const profile = (await me.json() as { data: { id: number; systemRole: string | null } }).data
  expect(profile.id).toBe(fixture.actors[actor].userId)
  if (actor === 'systemOnly' || actor === 'deputyGrantedSystem') expect(profile.systemRole).toBe('SYSTEM_ADMIN')
  else expect(profile.systemRole).not.toBe('SYSTEM_ADMIN')
  evidence.push({ operation: 'ログイン前提確認', actor, actualUserId: profile.id, actualSystemRole: profile.systemRole })
  return page
}

function listResponse(page: Page, kind: Kind, scope: Scope): Promise<Response> {
  return page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.origin === new URL(fixture.urls.backend).origin
      && response.request().method() === 'GET' && url.pathname === '/api/v1/proxy-votes'
      && url.searchParams.get('scope_type') === kind
      && url.searchParams.get(kind === 'TEAM' ? 'team_id' : 'organization_id') === String(scope.numericId)
  }, { timeout: 120_000 })
}

async function readList(response: Response): Promise<Session[]> {
  expect(response.status()).toBe(200)
  evidence.push({ operation: '実一覧応答', apiOrigin: new URL(response.url()).origin, status: response.status() })
  const body = await response.json() as { data: Session[]; meta: unknown }
  expect(Array.isArray(body.data)).toBe(true)
  expect(body.meta).toBeDefined()
  return body.data
}

async function openList(page: Page, kind: Kind, scope: Scope): Promise<Session[]> {
  const pending = listResponse(page, kind, scope)
  await page.goto(`${scopePath(kind, scope)}/voting`, { waitUntil: 'commit' })
  await waitForHydration(page, { reloadOnTimeout: false })
  const rows = await readList(await pending)
  await expect(page.getByRole('heading', { name: '議決権行使', exact: true })).toBeVisible()
  evidence.push({ operation: '一覧直URL', kind, startUrl: page.url(), status: 200, rows: rows.map(safeSession) })
  return rows
}

function safeSession(session: Session): Record<string, unknown> {
  return { id: session.id, title: session.title, scopeType: session.scopeType,
    resolutionMode: session.resolutionMode, meetingDate: session.meetingDate,
    motionCount: session.motionCount, eligibleCount: session.eligibleCount }
}

async function createViaUi(page: Page, actor: ActorKey, kind: Kind, mode: 'WRITTEN' | 'MEETING'): Promise<void> {
  const scope = fixture.scopes[kind]
  const title = `CMP0232 ${kind} ${actor} ${mode} ${Date.now()}`
  const startUrl = page.url()
  await page.getByRole('button', { name: CREATE, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: CREATE, exact: true })
  await expect(dialog).toBeVisible()
  await dialog.locator('#voting-title').fill(title)
  await dialog.locator('#voting-description').fill('実機UI入力・再読込保持の確認')
  await dialog.locator('#voting-mode').click()
  await page.getByRole('option', { name: mode === 'WRITTEN' ? '書面決議' : '総会決議', exact: true }).click()
  if (mode === 'MEETING') {
    await dialog.locator('#voting-date').fill(MEETING_DATE.replaceAll('-', '/'))
    await dialog.locator('#voting-date').press('Tab')
  }
  const pending = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).origin === new URL(fixture.urls.backend).origin
    && new URL(response.url()).pathname === '/api/v1/proxy-votes', { timeout: 120_000 })
  await dialog.getByRole('button', { name: '作成', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(201)
  const session = (await response.json() as { data: Session }).data
  if (Number.isSafeInteger(session.id)) created.push({ actor, session })
  expect(Number.isSafeInteger(session.id)).toBe(true)
  const sent = response.request().postDataJSON() as Record<string, unknown>
  expect(sent).toMatchObject({ title, scopeType: kind, resolutionMode: mode,
    [kind === 'TEAM' ? 'teamId' : 'organizationId']: scope.numericId })
  expect(sent).not.toHaveProperty('scope_id')
  if (mode === 'MEETING') expect(sent.meetingDate).toBe(MEETING_DATE)
  else expect(sent.meetingDate).toBeUndefined()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  const reloaded = listResponse(page, kind, scope)
  await page.reload({ waitUntil: 'commit' })
  await waitForHydration(page, { reloadOnTimeout: false })
  const rows = await readList(await reloaded)
  const retained = rows.find(row => row.id === session.id)
  expect(retained).toMatchObject({ id: session.id, title, resolutionMode: mode, scopeType: kind })
  if (mode === 'MEETING') expect(retained?.meetingDate).toBe(MEETING_DATE)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await expect(page.getByText(EMPTY, { exact: true })).toHaveCount(0)
  evidence.push({ operation: 'UI作成→再読込', actor, kind, startUrl,
    controls: ['投票セッションを作成', '#voting-title', '#voting-description', '#voting-mode', ...(mode === 'MEETING' ? ['#voting-date'] : []), 'dialog:作成'],
    apiOrigin: new URL(response.url()).origin, postStatus: response.status(), persisted: retained && safeSession(retained), finalUrl: page.url(), apiAuxiliary: 'ログイン・後始末のみ' })
}

async function screenshot(page: Page, info: TestInfo, label: string): Promise<void> {
  await info.attach(label, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
}

test.beforeAll(() => {
  const fixturePath = process.env.VOTING_REAL_FIXTURE
  if (!fixturePath) throw new Error('VOTING_REAL_FIXTUREに専用runtime fixtureの絶対パスが必要です')
  fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixture
  expect(fixture.sourceHead).toBe(process.env.VOTING_SOURCE_HEAD ?? 'd624771f3f096ba9f97147799d851e79b434a35a')
  expect(fixture.urls.frontend).toBe(process.env.BASE_URL)
  expect(fixture.urls.backend).toBe(process.env.API_BASE_URL)
  for (const kind of KINDS) {
    expect(Number.isSafeInteger(fixture.scopes[kind].numericId)).toBe(true)
    expect(fixture.scopes[kind].numericId).toBeGreaterThan(0)
  }
})

test.beforeEach(() => { evidenceStart = evidence.length })
test.afterEach(async () => {
  const info = test.info()
  await info.attach('投票実機UI証拠', { body: JSON.stringify({ sourceHead: fixture?.sourceHead,
    observations: evidence.slice(evidenceStart), apiAuxiliary: ['ログイン', 'UI作成セッションの後始末'] }, null, 2), contentType: 'application/json' })
})

test.afterAll(async () => {
  const failures: string[] = []
  for (const asset of [...created].reverse()) {
    const page = opened.get('admin')?.page
    if (!page) { failures.push(`後始末context不在:${asset.session.id}`); continue }
    // このspecの実UI POST応答で作成を確認したIDだけを、対象scope ADMINで削除する。
    const response = await page.request.delete(`${fixture.urls.backend}/api/v1/proxy-votes/${asset.session.id}`)
    if (![200, 204].includes(response.status())) failures.push(`後始末失敗:${asset.session.id}:${response.status()}`)
  }
  for (const { context } of opened.values()) await context.close()
  expect(failures).toEqual([])
})

test('V-ORG: 組織sidebarの自然入口から正常0件・書面/総会作成と再読込保持', async ({ browser }, info) => {
  const page = await actorPage(browser, 'admin')
  const scope = fixture.scopes.ORGANIZATION
  const start = scopePath('ORGANIZATION', scope)
  await page.goto(start, { waitUntil: 'commit' })
  await waitForHydration(page, { reloadOnTimeout: false })
  await page.getByRole('button', { name: '業務・運用', exact: true }).click()
  const link = page.getByRole('link', { name: '投票', exact: true })
  await expect(link).toHaveAttribute('href', `${start}/voting`)
  const pending = listResponse(page, 'ORGANIZATION', scope)
  await link.click()
  expect(await readList(await pending)).toEqual([])
  await expect(page.getByText(EMPTY, { exact: true })).toBeVisible()
  await expect(page.getByTestId('load-error-state')).toHaveCount(0)
  evidence.push({ operation: '組織自然入口・正常0件', startUrl: `${fixture.urls.frontend}${start}`, entry: '業務・運用→投票', status: 200, finalUrl: page.url() })
  await createViaUi(page, 'admin', 'ORGANIZATION', 'WRITTEN')
  await createViaUi(page, 'admin', 'ORGANIZATION', 'MEETING')
  await screenshot(page, info, '組織・再読込後')
})

test('V-TEAM: チームslug直URLで正常0件・書面/総会作成と再読込保持', async ({ browser }, info) => {
  const page = await actorPage(browser, 'admin')
  expect(await openList(page, 'TEAM', fixture.scopes.TEAM)).toEqual([])
  await expect(page.getByText(EMPTY, { exact: true })).toBeVisible()
  await expect(page.getByTestId('load-error-state')).toHaveCount(0)
  await createViaUi(page, 'admin', 'TEAM', 'WRITTEN')
  await createViaUi(page, 'admin', 'TEAM', 'MEETING')
  await screenshot(page, info, 'チーム直URL・再読込後')
})

test('V-ROLE: 同scope委譲権限ありだけ作成でき、無付与/MEMBER/SYSのみは作成入口なし', async ({ browser }) => {
  for (const actor of ['deputyGranted', 'deputyGrantedSystem'] as const) {
    const page = await actorPage(browser, actor)
    for (const kind of KINDS) {
      await openList(page, kind, fixture.scopes[kind])
      await expect(page.getByRole('button', { name: CREATE, exact: true })).toBeVisible()
      await createViaUi(page, actor, kind, 'WRITTEN')
    }
  }
  for (const actor of ['deputyDenied', 'member', 'systemOnly'] as const) {
    const page = await actorPage(browser, actor)
    for (const kind of KINDS) {
      const rows = await openList(page, kind, fixture.scopes[kind])
      expect(rows.length).toBeGreaterThan(0)
      await expect(page.getByRole('heading', { name: rows[0]!.title, exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: CREATE, exact: true })).toHaveCount(0)
      evidence.push({ operation: '作成入口非表示', actor, kind, finalUrl: page.url(), listStatus: 200, createButtons: 0 })
    }
  }
})

test('V-TENANT: 他tenantの自分の一覧に漏れず、対象tenant一覧の直URLは拒否画面', async ({ browser }, info) => {
  const page = await actorPage(browser, 'outsider')
  for (const kind of KINDS) {
    const ownRows = await openList(page, kind, fixture.foreignScopes[kind])
    for (const asset of created) {
      expect(ownRows.some(row => row.id === asset.session.id)).toBe(false)
      await expect(page.getByRole('heading', { name: asset.session.title, exact: true })).toHaveCount(0)
    }
    const denied: Array<{ path: string; status: number; apiOrigin: string }> = []
    const observe = (response: Response) => {
      const url = new URL(response.url())
      if (url.origin === new URL(fixture.urls.backend).origin
        && url.pathname.startsWith('/api/v1/') && [403, 404].includes(response.status())) {
        denied.push({ path: url.pathname, status: response.status(), apiOrigin: url.origin })
      }
    }
    page.on('response', observe)
    const target = `${scopePath(kind, fixture.scopes[kind])}/voting`
    await page.goto(target, { waitUntil: 'commit' })
    await waitForHydration(page, { reloadOnTimeout: false })
    await expect(page.getByTestId('load-error-state').filter({ hasText: /この画面をご利用いただけません|お探しの情報を表示できませんでした/ }).first()).toBeVisible({ timeout: 120_000 })
    expect(denied.some(row => row.path.includes(fixture.scopes[kind].slug) || row.path === '/api/v1/proxy-votes')).toBe(true)
    await expect(page.getByText(EMPTY, { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: CREATE, exact: true })).toHaveCount(0)
    for (const asset of created) await expect(page.getByRole('heading', { name: asset.session.title, exact: true })).toHaveCount(0)
    evidence.push({ operation: '他tenant直URL拒否', actor: 'outsider', kind,
      startUrl: `${fixture.urls.frontend}${target}`, rejectedResponses: denied,
      negativeText: await page.getByTestId('load-error-state').allTextContents(), finalUrl: page.url(), emptyShown: false, createButtons: 0 })
    page.off('response', observe)
    await screenshot(page, info, `${kind}・他tenant拒否画面`)
  }
})
