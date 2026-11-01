/** CMP1017: Actions 専用 DB の正規 fixture で、単発 UI と無効化認可を検証する。 */
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { test, expect, type APIResponse, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const emails = {
  owner: 'e2e-dummy-1@test.mannschaft.local',
  deputy: 'e2e-dummy-2@test.mannschaft.local',
  member: 'e2e-dummy-3@test.mannschaft.local',
  foreign: 'e2e-dummy-4@test.mannschaft.local',
  system: 'e2e-admin@test.mannschaft.local',
} as const
type ActorKey = keyof typeof emails
type ScopeType = 'teams' | 'organizations'
interface Actor { context: BrowserContext; page: Page; id: number }
interface Scope { type: ScopeType; id: number; slug: string; name: string; owner: ActorKey }
interface Receipt {
  id: number
  recipientName: string
  receiptNumber: string
  isVoided: boolean
  voidedAt: string | null
  voidedBy: number | null
  voidedReason: string | null
}
interface Db {
  execute(sql: string, params?: unknown[]): Promise<[Array<Record<string, unknown>>, unknown]>
  end(): Promise<void>
}
const actors = {} as Record<ActorKey, Actor>
const scopes: Scope[] = []
const createdReceipts: Array<{ scopeId: number; scopeType: string; ownerId: number; id: number; recipientName: string }> = []
const observations: string[] = []
let db: Db | undefined
let receiptSequence = 0
const typeName = (scope: Scope) => scope.type === 'teams' ? 'TEAM' : 'ORGANIZATION'
const query = (scope: Scope) => `scopeType=${typeName(scope)}&scopeId=${scope.id}`
const receiptUrl = (scope: Scope, id?: number) => `${API}/api/v1/admin/receipts${id === undefined ? '' : `/${id}`}?${query(scope)}`
const audit = (receipt: Receipt) => ({
  isVoided: receipt.isVoided, voidedAt: receipt.voidedAt,
  voidedBy: receipt.voidedBy, voidedReason: receipt.voidedReason,
})
test.use({ storageState: { cookies: [], origins: [] }, locale: 'ja-JP', trace: 'off' })

function recordResponse(actor: ActorKey, response: APIResponse) {
  observations.push(`${actor} HTTP ${response.status()} ${new URL(response.url()).pathname}`)
}

async function screenshot(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

async function ownsScope(scope: Scope) {
  if (!db) throw new Error('専用 DB の所有証明が未成立')
  const [rows] = await db.execute(`SELECT id FROM ${scope.type} WHERE id=? AND slug=? AND name=? AND deleted_at IS NULL`, [scope.id, scope.slug, scope.name])
  expect(rows, '作成 API の返却 ID/slug と今回の名前だけを対象とする').toHaveLength(1)
  const column = scope.type === 'teams' ? 'team_id' : 'organization_id'
  const [roles] = await db.execute(
    `SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN memberships m ON m.user_id=ur.user_id AND m.scope_id=ur.${column} `
    + `WHERE ur.${column}=? AND ur.user_id=? AND r.name='ADMIN' AND m.scope_type=? AND m.role_kind='MEMBER' AND m.left_at IS NULL AND m.archived_at IS NULL`,
    [scope.id, actors[scope.owner].id, typeName(scope)],
  )
  expect(roles, '本人 scope ADMIN と active MEMBER 所属を別々に証明').toHaveLength(1)
}

async function createScope(owner: ActorKey, type: ScopeType) {
  const slug = `c1017-${owner[0]}-${type[0]}-${Date.now().toString(36)}`
  const name = `CMP1017-${slug}`
  const page = actors[owner].page
  const response = await page.request.post(`${API}/api/v1/${type}`, {
    data: type === 'teams'
      ? { name, slug, template: 'OTHER', visibility: 'MEMBERS_AND_ABOVE' }
      : { name, slug, orgType: 'OTHER', visibility: 'PRIVATE' },
  })
  expect(response.status(), '正規作成 API').toBe(201)
  const data = (await response.json()).data as { numericId: number; slug: string }
  expect(data.slug).toBe(slug)
  expect(data.numericId).toBeGreaterThan(0)
  const scope: Scope = { type, id: data.numericId, slug, name, owner }
  scopes.push(scope)
  await ownsScope(scope)
  const detail = await page.request.get(`${API}/api/v1/${type}/${slug}`)
  expect(detail.status()).toBe(200)
  expect((await detail.json()).data).toMatchObject({ numericId: scope.id, slug, basicInfo: { name } })
  const catalog = await page.request.get(`${API}/api/v1/${type}/${slug}/modules/catalog`)
  expect(catalog.status()).toBe(200)
  const payment = ((await catalog.json()).data.modules as Array<{ moduleId: number; slug: string; isEnabled: boolean; levelAvailable: boolean; requiresPaidPlan: boolean }>).find(item => item.slug === 'payment')
  expect(payment).toBeDefined()
  expect(payment!.levelAvailable).toBe(true)
  expect(payment!.requiresPaidPlan).toBe(false)
  if (!payment!.isEnabled) {
    const enabled = await page.request.patch(`${API}/api/v1/${type}/${slug}/modules/${payment!.moduleId}/toggle`, { data: { moduleId: payment!.moduleId, enabled: true } })
    expect(enabled.status()).toBe(200)
  }
  const modules = await page.request.get(`${API}/api/v1/${type}/${slug}/modules`)
  expect(modules.status()).toBe(200)
  expect(((await modules.json()).data as Array<{ moduleId: number; moduleSlug: string; isEnabled: boolean }>).find(item => item.moduleSlug === 'payment'))
    .toMatchObject({ moduleId: payment!.moduleId, isEnabled: true })
  const settings = await page.request.patch(`${API}/api/v1/admin/receipt-settings?${query(scope)}`, {
    data: { issuerName: name, isQualifiedInvoicer: false, receiptNumberPrefix: 'C1017-' },
  })
  expect(settings.status()).toBe(200)
  const saved = await page.request.get(`${API}/api/v1/admin/receipt-settings?${query(scope)}`)
  expect(saved.status()).toBe(200)
  expect((await saved.json()).data).toMatchObject({ issuerName: name, isQualifiedInvoicer: false })
  return scope
}

async function join(scope: Scope, actorKey: 'deputy' | 'member', memberRoleId: number, deputyRoleId: number) {
  await ownsScope(scope)
  const owner = actors[scope.owner].page
  const actor = actors[actorKey]
  const response = await owner.request.post(`${API}/api/v1/${scope.type}/${scope.slug}/invite-tokens`, { data: { roleId: memberRoleId, expiresIn: '1d', maxUses: 1 } })
  expect(response.status()).toBe(201)
  const invitation = (await response.json()).data as { id: number; token: string }
  const joined = await actor.page.request.post(`${API}/api/v1/invite/${encodeURIComponent(invitation.token)}/join`, { data: {} })
  expect(joined.status(), '特権 invite は使わず MEMBER で参加').toBe(200)
  const revoked = await owner.request.delete(`${API}/api/v1/${scope.type}/${scope.slug}/invite-tokens/${invitation.id}`)
  expect(revoked.status()).toBe(204)
  const permissionUrl = `${API}/api/v1/${scope.type}/${scope.slug}/me/permissions`
  const member = await actor.page.request.get(permissionUrl)
  expect(member.status()).toBe(200)
  expect((await member.json()).data.roleName).toBe('MEMBER')
  if (actorKey === 'deputy') {
    const changed = await owner.request.patch(`${API}/api/v1/${scope.type}/${scope.slug}/members/${actor.id}/role`, { data: { roleId: deputyRoleId } })
    expect(changed.status()).toBe(200)
    const permission = await actor.page.request.get(permissionUrl)
    expect(permission.status()).toBe(200)
    expect((await permission.json()).data.roleName).toBe('DEPUTY_ADMIN')
  }
}

async function getReceipt(scope: Scope, id: number) {
  const response = await actors[scope.owner].page.request.get(receiptUrl(scope, id))
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data as Receipt
  expect(receipt.id).toBe(id)
  return receipt
}

async function createReceipt(scope: Scope, label: string) {
  await ownsScope(scope)
  const recipientName = `${scope.name}-${label}-${++receiptSequence}`
  const created = await actors[scope.owner].page.request.post(receiptUrl(scope), {
    data: { status: 'ISSUED', recipientName, description: 'CMP1017 void fixture', amount: 1100, taxRate: 10, sealStamp: false },
  })
  expect(created.status()).toBe(201)
  const receipt = (await created.json()).data as Receipt
  expect(receipt.id).toBeGreaterThan(0)
  expect(receipt.recipientName).toBe(recipientName)
  const actual = await getReceipt(scope, receipt.id)
  expect(actual).toMatchObject({
    id: receipt.id, recipientName, receiptNumber: receipt.receiptNumber,
    isVoided: false, voidedAt: null, voidedBy: null, voidedReason: null,
  })
  createdReceipts.push({ scopeId: scope.id, scopeType: typeName(scope), ownerId: actors[scope.owner].id, id: receipt.id, recipientName })
  return actual
}

async function waitForReceiptRow(page: Page, receipt: Receipt) {
  await expect(page.locator('body > div[class~="z-[9998]"]')).toHaveCount(0)
  await expect(page.locator('.p-datatable-mask')).toHaveCount(0)
  const row = page.getByRole('row').filter({ hasText: receipt.recipientName }).filter({ visible: true })
  await expect(row).toHaveCount(1)
  await expect(row.getByText(receipt.receiptNumber, { exact: true })).toBeVisible()
  await expect(row.getByText(receipt.recipientName, { exact: true })).toBeVisible()
}

async function openList(page: Page, scope: Scope, receipt: Receipt) {
  await page.goto(`/${scope.type}/${scope.slug}/admin`)
  await waitForHydration(page)
  await expect.poll(() => page.evaluate(() => {
    const current = JSON.parse(localStorage.getItem('currentScope') ?? '{}') as { type?: string; id?: string }
    return { type: current.type, id: current.id }
  })).toEqual({ type: scope.type === 'teams' ? 'team' : 'organization', id: String(scope.id) })
  const list = page.waitForResponse(response => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/admin/receipts'
      && url.searchParams.get('scopeType') === typeName(scope) && url.searchParams.get('scopeId') === String(scope.id)
  })
  await page.goto('/admin/receipts')
  expect((await list).status()).toBe(200)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: '領収書管理', exact: true })).toBeVisible()
  await waitForReceiptRow(page, receipt)
}

test.describe('CMP1017 無効化のみの実 API/UI 契約', () => {
  test.describe.configure({ mode: 'serial' })
  test.beforeAll(async ({ browser }) => {
    expect(process.env.GITHUB_ACTIONS).toBe('true')
    expect(process.env.E2E_ISOLATED_DB).toBe('true')
    expect(process.env.E2E_DB_HOST).toBe('127.0.0.1')
    expect(process.env.E2E_DB_PORT).toBe('3306')
    expect(process.env.E2E_DB_NAME).toBe('mannschaft')
    expect(process.env.E2E_DB_USER).toBe('mannschaft')
    expect(API).toBe('http://localhost:8080')
    expect(process.env.BASE_URL).toBe('http://localhost:8081')
    const containerId = process.env.E2E_MYSQL_CONTAINER_ID
    expect(containerId).toMatch(/^[a-f0-9]{64}$/)
    const hostname = execFileSync('docker', ['inspect', '--format', '{{.Config.Hostname}}', containerId!], { encoding: 'utf8' }).trim()
    const require = createRequire(new URL('../../../../backend/scripts/package.json', import.meta.url))
    const mysql = require('mysql2/promise') as { createConnection(options: Record<string, unknown>): Promise<Db> }
    db = await mysql.createConnection({ host: process.env.E2E_DB_HOST, port: 3306, database: process.env.E2E_DB_NAME, user: process.env.E2E_DB_USER, password: process.env.E2E_DB_PASSWORD })
    const [hosts] = await db.execute('SELECT DATABASE() AS databaseName, @@hostname AS hostname')
    expect(hosts[0]?.databaseName).toBe('mannschaft')
    expect(hosts[0]?.hostname).toBe(hostname)
    for (const key of Object.keys(emails) as ActorKey[]) {
      const context = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP', viewport: { width: 1280, height: 720 } })
      const page = await context.newPage()
      // URL の path と status のみ。認証本文、Cookie、console 本文は保存しない。
      page.on('response', response => {
        const url = new URL(response.url())
        if (url.pathname.startsWith('/api/v1/') && !/auth|invite/.test(url.pathname)) observations.push(`${key} HTTP ${response.status()} ${url.pathname}`)
      })
      try {
        await loginViaApi(page, { email: emails[key], password: PASSWORD }, { apiBaseUrl: API })
      }
      catch {
        throw new Error('本人ログイン失敗（認証本文は除外）')
      }
      const me = await page.request.get(`${API}/api/v1/users/me`)
      expect(me.status()).toBe(200)
      const user = (await me.json()).data as { id: number; systemRole?: string | null }
      expect(user.id).toBeGreaterThan(0)
      if (key === 'system') expect(user.systemRole).toBe('SYSTEM_ADMIN')
      else expect(user.systemRole).not.toBe('SYSTEM_ADMIN')
      actors[key] = { context, page, id: user.id }
    }
    const [roles] = await db.execute('SELECT id, name FROM roles WHERE name IN (?, ?)', ['MEMBER', 'DEPUTY_ADMIN'])
    expect(roles).toHaveLength(2)
    const memberRoleId = Number(roles.find(role => role.name === 'MEMBER')?.id)
    const deputyRoleId = Number(roles.find(role => role.name === 'DEPUTY_ADMIN')?.id)
    expect(memberRoleId).toBeGreaterThan(0)
    expect(deputyRoleId).toBeGreaterThan(0)
    for (const type of ['teams', 'organizations'] as const) {
      const scope = await createScope('owner', type)
      await join(scope, 'deputy', memberRoleId, deputyRoleId)
      await join(scope, 'member', memberRoleId, deputyRoleId)
      await createScope('foreign', type)
      await createScope('system', type)
    }
  })

  test.afterEach(async ({ browser: _browser }, info) => {
    await info.attach('HTTP-status-path-only', { body: observations.join('\n'), contentType: 'text/plain' })
    await info.attach('owned-fixture-manifest', {
      body: JSON.stringify({ scopes: scopes.map(scope => ({ ...scope, ownerId: actors[scope.owner].id })), receipts: createdReceipts }),
      contentType: 'application/json',
    })
    if (info.status !== info.expectedStatus) {
      for (const key of ['owner', 'deputy', 'system'] as const) {
        if (actors[key] && !actors[key].page.isClosed()) await screenshot(actors[key].page, info, `failure-${key}`)
      }
    }
  })
  test.afterAll(async () => {
    // 領収書は監査データ。削除 API/SQL を創作せず、専用 Actions DB の終了破棄に委ねる。
    for (const actor of Object.values(actors)) await actor.context.close()
    await db?.end()
  })

  for (const type of ['teams', 'organizations'] as const) {
    test(`${type}: ADMIN の画面単発無効化は理由と本人監査値を保存する`, async ({ browser: _browser }, info) => {
      const scope = scopes.find(item => item.type === type && item.owner === 'owner')!
      const receipt = await createReceipt(scope, 'UI')
      const page = actors.owner.page
      await openList(page, scope, receipt)
      const row = page.getByRole('row').filter({ hasText: receipt.recipientName }).filter({ visible: true })
      await expect(row).toHaveCount(1)
      await row.getByRole('button', { name: '無効化', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '領収書を無効化', exact: true })
      await expect(dialog).toBeVisible()
      const reason = `${scope.name} 金額誤記`
      await dialog.locator('textarea').fill(reason)
      const submitted = page.waitForResponse(response => response.request().method() === 'POST'
        && response.url() === `${API}/api/v1/admin/receipts/${receipt.id}/void?${query(scope)}`)
      const reloaded = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return response.request().method() === 'GET' && url.pathname === '/api/v1/admin/receipts'
          && url.searchParams.get('scopeType') === typeName(scope) && url.searchParams.get('scopeId') === String(scope.id)
      })
      await dialog.getByRole('button', { name: '無効化する', exact: true }).click()
      expect((await submitted).status()).toBe(200)
      const refreshed = await reloaded
      expect(refreshed.status()).toBe(200)
      const refreshedReceipts = (await refreshed.json()).data as Receipt[]
      expect(refreshedReceipts.find(item => item.id === receipt.id)).toMatchObject({ id: receipt.id, isVoided: true })
      await expect(dialog).not.toBeVisible()
      await expect(page.getByText('無効化しました', { exact: true })).toBeVisible()
      const actual = await getReceipt(scope, receipt.id)
      expect(actual.isVoided).toBe(true)
      expect(actual.voidedAt).not.toBeNull()
      expect(actual.voidedBy).toBe(actors.owner.id)
      expect(actual.voidedReason).toBe(reason)
      await waitForReceiptRow(page, actual)
      await screenshot(page, info, `${type}-admin-void`)
    })

    test(`${type}: DEPUTY/MEMBER/SYS 資格のみは単発・一括403、別 tenant の ID 束縛を維持する`, async ({ browser: _browser }, info) => {
      const scope = scopes.find(item => item.type === type && item.owner === 'owner')!
      for (const actorKey of ['deputy', 'member', 'system', 'foreign'] as const) {
        const receipt = await createReceipt(scope, actorKey)
        const before = audit(receipt)
        const page = actors[actorKey].page
        const single = await page.request.post(`${API}/api/v1/admin/receipts/${receipt.id}/void?${query(scope)}`, { data: { reason: '権限拒否を確認' } })
        recordResponse(actorKey, single)
        expect(single.status(), `${actorKey} single`).toBe(403)
        const bulk = await page.request.post(`${API}/api/v1/admin/receipts/bulk-void?${query(scope)}`, { data: { receiptIds: [receipt.id], reason: '権限拒否を確認' } })
        recordResponse(actorKey, bulk)
        expect(bulk.status(), `${actorKey} bulk`).toBe(403)
        expect(audit(await getReceipt(scope, receipt.id))).toEqual(before)
        if (actorKey === 'deputy') {
          await openList(page, scope, receipt)
          await expect(page.getByRole('row').filter({ hasText: receipt.recipientName }).filter({ visible: true })).toHaveCount(1)
          await expect(page.getByRole('button', { name: '無効化', exact: true })).toHaveCount(0)
          await screenshot(page, info, `${type}-deputy-no-void`)
        }
        if (actorKey === 'foreign') {
          const foreign = scopes.find(item => item.type === type && item.owner === 'foreign')!
          const wrongScope = await page.request.post(`${API}/api/v1/admin/receipts/${receipt.id}/void?${query(foreign)}`, { data: { reason: '所属先の ID 束縛を確認' } })
          recordResponse(actorKey, wrongScope)
          expect(wrongScope.status(), '実在 ID でも別 scope は entity-bound 404').toBe(404)
          expect(audit(await getReceipt(scope, receipt.id))).toEqual(before)
        }
      }
    })

    test(`${type}: mixed bulk は既存 skip を維持し、SYS 兼任は BE 許可と UI 非表示を分ける`, async ({ browser: _browser }, info) => {
      const scope = scopes.find(item => item.type === type && item.owner === 'owner')!
      const foreign = scopes.find(item => item.type === type && item.owner === 'foreign')!
      const valid = await createReceipt(scope, 'bulk-valid')
      const already = await createReceipt(scope, 'bulk-already')
      const other = await createReceipt(foreign, 'bulk-foreign')
      const first = await actors.owner.page.request.post(`${API}/api/v1/admin/receipts/${already.id}/void?${query(scope)}`, { data: { reason: '先行無効化' } })
      expect(first.status()).toBe(200)
      const priorAudit = audit(await getReceipt(scope, already.id))
      const otherAudit = audit(other)
      const bulk = await actors.owner.page.request.post(`${API}/api/v1/admin/receipts/bulk-void?${query(scope)}`, { data: { receiptIds: [valid.id, already.id, other.id], reason: '混在一括' } })
      recordResponse('owner', bulk)
      expect(bulk.status()).toBe(200)
      expect((await bulk.json()).data).toEqual({ voidedCount: 1, skippedCount: 2 })
      expect(await getReceipt(scope, valid.id)).toMatchObject({ isVoided: true, voidedBy: actors.owner.id, voidedReason: '混在一括' })
      expect(audit(await getReceipt(scope, already.id))).toEqual(priorAudit)
      expect(audit(await getReceipt(foreign, other.id))).toEqual(otherAudit)
      const dual = scopes.find(item => item.type === type && item.owner === 'system')!
      await ownsScope(dual)
      const singleReceipt = await createReceipt(dual, 'SYS-dual-single')
      const bulkReceipt = await createReceipt(dual, 'SYS-dual-bulk')
      const page = actors.system.page
      await openList(page, dual, singleReceipt)
      await expect(page.getByRole('row').filter({ hasText: singleReceipt.recipientName }).filter({ visible: true })).toHaveCount(1)
      await expect(page.getByRole('button', { name: '無効化', exact: true })).toHaveCount(0)
      await screenshot(page, info, `${type}-sys-dual-no-void`)
      const single = await page.request.post(`${API}/api/v1/admin/receipts/${singleReceipt.id}/void?${query(dual)}`, { data: { reason: '同 scope ADMIN 兼任' } })
      recordResponse('system', single)
      expect(single.status()).toBe(200)
      const dualBulk = await page.request.post(`${API}/api/v1/admin/receipts/bulk-void?${query(dual)}`, { data: { receiptIds: [bulkReceipt.id], reason: '同 scope ADMIN 兼任一括' } })
      recordResponse('system', dualBulk)
      expect(dualBulk.status()).toBe(200)
      expect((await dualBulk.json()).data).toEqual({ voidedCount: 1, skippedCount: 0 })
      for (const receipt of [singleReceipt, bulkReceipt]) {
        const actual = await getReceipt(dual, receipt.id)
        expect(actual.isVoided).toBe(true)
        expect(actual.voidedAt).not.toBeNull()
        expect(actual.voidedBy).toBe(actors.system.id)
      }
    })
  }
})
