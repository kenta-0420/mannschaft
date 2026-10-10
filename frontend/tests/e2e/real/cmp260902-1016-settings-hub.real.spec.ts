/** CMP1016: Actions専用DBと同headのAPI/UIを使う設定一覧の実機試験。 */
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { test, expect, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const OWNER = 'e2e-dummy-1@test.mannschaft.local'
const DEPUTY = 'e2e-dummy-2@test.mannschaft.local'
const MEMBER = 'e2e-dummy-3@test.mannschaft.local'
const OUTSIDER = 'e2e-dummy-4@test.mannschaft.local'
const SYSTEM = 'e2e-admin@test.mannschaft.local'
const PASSWORD = 'TestPass2026!'
interface Db {
  execute(sql: string, params?: unknown[]): Promise<[Array<Record<string, unknown>>, unknown]>
  end(): Promise<void>
}
interface Scope {
  type: 'teams' | 'organizations'
  id: number
  slug: string
  name: string
  ownerId: number
}
const scopes: Scope[] = []
let db: Db
let ownerContext: BrowserContext | undefined
const logs = new Map<string, string[]>()
const typeName = (scope: Scope) => scope.type === 'teams' ? 'TEAM' : 'ORGANIZATION'
const base = (scope: Scope) => `/${scope.type}/${scope.slug}`
test.use({ storageState: { cookies: [], origins: [] }, locale: 'ja-JP', trace: 'off' })

async function login(page: Page, email: string) {
  try {
    await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API })
  }
  catch {
    // canonical helper の認証レスポンス本文を証跡へ出さない。
    throw new Error('実機の本人ログインに失敗しました（認証本文は除外）')
  }
}

async function ownsScope(scope: Scope) {
  const [rows] = await db.execute(`SELECT id, slug, name FROM ${scope.type} WHERE id=? AND slug=? AND name=? AND deleted_at IS NULL`, [scope.id, scope.slug, scope.name])
  expect(rows, '今回作成した団体のID/slug/nameが一致').toHaveLength(1)
  const column = scope.type === 'teams' ? 'team_id' : 'organization_id'
  const [creators] = await db.execute(
    `SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN memberships m ON m.user_id=ur.user_id AND m.scope_id=ur.${column} `
    + `WHERE ur.${column}=? AND ur.user_id=? AND r.name='ADMIN' AND m.scope_type=? AND m.role_kind='MEMBER' AND m.left_at IS NULL AND m.archived_at IS NULL`,
    [scope.id, scope.ownerId, typeName(scope)],
  )
  expect(creators, '作成APIで確認した本人ADMINとactiveMEMBERが一致').toHaveLength(1)
}

async function screenshot(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

test.describe('CMP1016 設定一覧の実ブラウザ（API smokeとは別判定）', () => {
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
    ownerContext = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP' })
    const owner = await ownerContext.newPage()
    await login(owner, OWNER)
    const me = await owner.request.get(`${API}/api/v1/users/me`)
    expect(me.status()).toBe(200)
    const ownerId = Number((await me.json()).data.id)
    expect(ownerId).toBeGreaterThan(0)
    const [roles] = await db.execute('SELECT id, name FROM roles WHERE name IN (?, ?)', ['MEMBER', 'DEPUTY_ADMIN'])
    expect(roles).toHaveLength(2)
    const memberRoleId = Number(roles.find(role => role.name === 'MEMBER')?.id)
    const deputyRoleId = Number(roles.find(role => role.name === 'DEPUTY_ADMIN')?.id)
    expect(memberRoleId).toBeGreaterThan(0)
    expect(deputyRoleId).toBeGreaterThan(0)
    const deputyContext = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP' })
    const memberContext = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP' })
    const outsiderContext = await browser.newContext({ baseURL: process.env.BASE_URL, locale: 'ja-JP' })
    try {
      const deputy = await deputyContext.newPage()
      await login(deputy, DEPUTY)
      const deputyMe = await deputy.request.get(`${API}/api/v1/users/me`)
      expect(deputyMe.status()).toBe(200)
      const deputyId = Number((await deputyMe.json()).data.id)
      expect(deputyId).toBeGreaterThan(0)
      const member = await memberContext.newPage()
      const outsider = await outsiderContext.newPage()
      await login(member, MEMBER)
      await login(outsider, OUTSIDER)
      const memberMe = await member.request.get(`${API}/api/v1/users/me`)
      const outsiderMe = await outsider.request.get(`${API}/api/v1/users/me`)
      expect(memberMe.status()).toBe(200)
      expect(outsiderMe.status()).toBe(200)
      const memberId = Number((await memberMe.json()).data.id)
      const outsiderId = Number((await outsiderMe.json()).data.id)
      expect(memberId).toBeGreaterThan(0)
      expect(outsiderId).toBeGreaterThan(0)
      expect(new Set([ownerId, deputyId, memberId, outsiderId]).size).toBe(4)
      for (const type of ['teams', 'organizations'] as const) {
        const slug = `c1016-${type === 'teams' ? 't' : 'o'}-${Date.now().toString(36)}`
        const name = `CMP1016-${slug}`
        const created = await owner.request.post(`${API}/api/v1/${type}`, {
          data: type === 'teams' ? { name, slug, template: 'OTHER', visibility: 'MEMBERS_AND_ABOVE' } : { name, slug, orgType: 'OTHER', visibility: 'PRIVATE' },
        })
        expect(created.status(), '正規APIで本人の団体を作成').toBe(201)
        const data = (await created.json()).data as { numericId: number; slug: string }
        expect(data.slug).toBe(slug)
        expect(data.numericId).toBeGreaterThan(0)
        const scope: Scope = { type, id: data.numericId, slug, name, ownerId }
        scopes.push(scope)
        await ownsScope(scope)
        const invited = await owner.request.post(`${API}/api/v1/${type}/${slug}/invite-tokens`, { data: { roleId: memberRoleId, expiresIn: '1d', maxUses: 1 } })
        expect(invited.status()).toBe(201)
        const invitation = (await invited.json()).data as { id: number; token: string }
        const joined = await deputy.request.post(`${API}/api/v1/invite/${encodeURIComponent(invitation.token)}/join`, { data: {} })
        expect(joined.status(), '正規招待APIで通常MEMBER所属を作成').toBe(200)
        const revoked = await owner.request.delete(`${API}/api/v1/${type}/${slug}/invite-tokens/${invitation.id}`)
        expect(revoked.status()).toBe(204)
        const memberPermission = await deputy.request.get(`${API}/api/v1/${type}/${slug}/me/permissions`)
        expect(memberPermission.status()).toBe(200)
        expect((await memberPermission.json()).data.roleName).toBe('MEMBER')
        await ownsScope(scope)
        // 特権ロールの招待は禁止されているため、本人ADMINの既存ロール変更APIを使う。
        const changed = await owner.request.patch(`${API}/api/v1/${type}/${slug}/members/${deputyId}/role`, { data: { roleId: deputyRoleId } })
        expect(changed.status(), '本人ADMINが今回所属したMEMBERをDEPUTYへ変更').toBe(200)
        const permission = await deputy.request.get(`${API}/api/v1/${type}/${slug}/me/permissions`)
        expect(permission.status()).toBe(200)
        expect((await permission.json()).data.roleName).toBe('DEPUTY_ADMIN')
        const memberInvite = await owner.request.post(`${API}/api/v1/${type}/${slug}/invite-tokens`, { data: { roleId: memberRoleId, expiresIn: '1d', maxUses: 1 } })
        expect(memberInvite.status()).toBe(201)
        const memberInvitation = (await memberInvite.json()).data as { id: number; token: string }
        const memberJoined = await member.request.post(`${API}/api/v1/invite/${encodeURIComponent(memberInvitation.token)}/join`, { data: {} })
        expect(memberJoined.status()).toBe(200)
        expect((await owner.request.delete(`${API}/api/v1/${type}/${slug}/invite-tokens/${memberInvitation.id}`)).status()).toBe(204)
        const ordinaryPermission = await member.request.get(`${API}/api/v1/${type}/${slug}/me/permissions`)
        expect(ordinaryPermission.status()).toBe(200)
        expect((await ordinaryPermission.json()).data.roleName).toBe('MEMBER')
        const column = type === 'teams' ? 'team_id' : 'organization_id'
        const [outsiderRoles] = await db.execute(`SELECT user_id FROM user_roles WHERE user_id=? AND ${column}=?`, [outsiderId, scope.id])
        const [outsiderMemberships] = await db.execute('SELECT user_id FROM memberships WHERE user_id=? AND scope_id=? AND scope_type=? AND left_at IS NULL AND archived_at IS NULL', [outsiderId, scope.id, typeName(scope)])
        expect(outsiderRoles, '今回団体の非所属者にスコープロールが無い').toHaveLength(0)
        expect(outsiderMemberships, '今回団体の非所属者にactive membershipが無い').toHaveLength(0)
        // 登録済み一覧には初期未登録のpaymentが無いため、既存のscope別カタログでIDを解決する。
        const catalog = await owner.request.get(`${API}/api/v1/${type}/${slug}/modules/catalog`)
        expect(catalog.status()).toBe(200)
        const payment = ((await catalog.json()).data.modules as Array<{ moduleId: number; slug: string; isEnabled: boolean; levelAvailable: boolean; requiresPaidPlan: boolean }>).find(item => item.slug === 'payment')
        expect(payment, '既存paymentカタログを使う').toBeDefined()
        expect(payment!.levelAvailable).toBe(true)
        expect(payment!.requiresPaidPlan).toBe(false)
        if (!payment!.isEnabled) {
          const toggled = await owner.request.patch(`${API}/api/v1/${type}/${slug}/modules/${payment!.moduleId}/toggle`, { data: { moduleId: payment!.moduleId, enabled: true } })
          expect(toggled.status()).toBe(200)
        }
        const modules = await owner.request.get(`${API}/api/v1/${type}/${slug}/modules`)
        expect(modules.status()).toBe(200)
        expect(((await modules.json()).data as Array<{ moduleId: number; moduleSlug: string; isEnabled: boolean }>).find(item => item.moduleSlug === 'payment'))
          .toMatchObject({ moduleId: payment!.moduleId, isEnabled: true })
      }
    }
    finally {
      await deputyContext.close()
      await memberContext.close()
      await outsiderContext.close()
      await ownerContext.close()
      ownerContext = undefined
    }
  })

  test.beforeEach(async ({ page }, info) => {
    const events: string[] = []
    logs.set(info.testId, events)
    page.on('console', message => events.push(/token|authorization|cookie|bearer/i.test(message.text()) ? 'console: 認証関連出力を除外' : `console ${message.type()}: ${message.text()}`))
    page.on('pageerror', error => events.push(/token|authorization|cookie|bearer/i.test(error.message) ? 'pageerror: 認証関連出力を除外' : `pageerror: ${error.message}`))
    page.on('response', response => { if (response.url().includes('/api/v1/')) events.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`) })
    page.on('requestfailed', request => events.push(`requestfailed: ${new URL(request.url()).pathname}`))
  })
  test.afterEach(async ({ page }, info) => {
    await screenshot(page, info, 'final-screen')
    await info.attach('UI-observations', { body: (logs.get(info.testId) ?? []).join('\n'), contentType: 'text/plain' })
  })
  test.afterAll(async ({ browser }) => {
    let context: BrowserContext | undefined
    try {
      if (scopes.length) {
        context = await browser.newContext({ baseURL: process.env.BASE_URL })
        const owner = await context.newPage()
        await login(owner, OWNER)
        for (const scope of scopes) {
          await ownsScope(scope)
          const deleted = await owner.request.delete(`${API}/api/v1/${scope.type}/${scope.slug}`)
          expect(deleted.status(), '今回本人が作成した団体だけを後始末').toBe(204)
        }
      }
    }
    finally {
      await context?.close()
      await ownerContext?.close()
      await db?.end()
    }
  })

  for (const type of ['teams', 'organizations'] as const) {
    for (const width of [1280, 390]) {
      test(`${type} ADMIN ${width}px: L2から設定/戻る/LINE・領収書へ実遷移し団体を保持する`, async ({ page }, info) => {
        const scope = scopes.find(item => item.type === type)!
        await page.setViewportSize({ width, height: 720 })
        await login(page, OWNER)
        await page.goto(`${base(scope)}/admin`)
        await waitForHydration(page)
        // /admin は親のshell対象外。初回案内のGET/ダイアログを必要としない。
        await expect(page.getByRole('dialog', { name: 'メンバーの権限を初期設定', exact: true })).toHaveCount(0)
        const entry = page.locator(`a[href="${base(scope)}/admin/settings"]`).filter({ visible: true })
        await expect(entry).toHaveCount(1)
        const hubPermissions = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === `/api/v1/${type}/${scope.slug}/me/permissions`)
        const hubModules = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === `/api/v1/${type}/${scope.slug}/modules`)
        await entry.click()
        await expect(page).toHaveURL(`${process.env.BASE_URL}${base(scope)}/admin/settings`)
        const [permissionsResponse, modulesResponse] = await Promise.all([hubPermissions, hubModules])
        expect(permissionsResponse.status(), '設定一覧自身の実権限GET').toBe(200)
        expect((await permissionsResponse.json()).data.roleName).toBe('ADMIN')
        expect(modulesResponse.status(), '設定一覧自身の実モジュールGET').toBe(200)
        await expect(page.getByTestId('setting-line')).toBeVisible()
        await expect(page.getByTestId('setting-receipts')).toBeVisible()
        const hubScope = await page.evaluate(() => JSON.parse(localStorage.getItem('currentScope') ?? '{}') as { type?: string; id?: string })
        expect(hubScope).toMatchObject({ type: type === 'teams' ? 'team' : 'organization', id: String(scope.id) })
        await screenshot(page, info, 'settings-hub')
        const key = type === 'teams' ? 'shift' : 'public'
        const destination = type === 'teams' ? 'settings/shift' : 'settings/public-settings'
        const link = page.getByTestId(`setting-${key}`)
        await link.focus()
        await expect(link).toBeFocused()
        await page.keyboard.press('Tab')
        await expect(page.getByTestId(type === 'teams' ? 'setting-faq' : 'setting-todoLabels')).toBeFocused()
        await page.keyboard.press('Shift+Tab')
        await expect(link).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(`${process.env.BASE_URL}${base(scope)}/${destination}`)
        await page.goBack()
        await expect(page.getByTestId('setting-line')).toBeVisible()
        await page.locator(`a[href="${base(scope)}/admin"]`).filter({ visible: true }).click()
        await expect(entry).toBeVisible()
        await entry.click()
        const lineResponse = page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/${type}/${scope.id}/line/config` && response.request().method() === 'GET')
        await page.getByTestId('setting-line').click()
        expect((await lineResponse).status(), 'LINE設定未登録は既存404、団体を誤って403にしない').toBe(404)
        await expect(page).toHaveURL(`${process.env.BASE_URL}/admin/line-settings`)
        await page.goBack()
        await expect(page.getByTestId('setting-receipts')).toBeVisible()
        const receiptResponse = page.waitForResponse(response => {
          const url = new URL(response.url())
          return url.pathname === '/api/v1/admin/receipt-settings' && url.searchParams.get('scopeType') === typeName(scope) && url.searchParams.get('scopeId') === String(scope.id)
        })
        await page.getByTestId('setting-receipts').click()
        expect((await receiptResponse).status(), '本人新団体の未登録発行者設定').toBe(404)
        await expect(page).toHaveURL(`${process.env.BASE_URL}/admin/receipt-settings`)
        const current = await page.evaluate(() => JSON.parse(localStorage.getItem('currentScope') ?? '{}') as { type?: string; id?: string })
        expect(current).toMatchObject({ type: type === 'teams' ? 'team' : 'organization', id: String(scope.id) })
      })
    }
    for (const actor of [DEPUTY, SYSTEM]) {
      test(`${type} ${actor === DEPUTY ? 'DEPUTY' : 'SYS'}: 旧L2導線を残し新hub直URLを許可しない`, async ({ page }) => {
        const scope = scopes.find(item => item.type === type)!
        await page.setViewportSize({ width: 1280, height: 720 })
        await login(page, actor)
        const permissionPath = `/api/v1/${type}/${scope.slug}/me/permissions`
        const expectedRole = actor === SYSTEM ? 'SYSTEM_ADMIN' : 'DEPUTY_ADMIN'
        const l2Permissions = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === permissionPath)
        await page.goto(`${base(scope)}/admin`)
        await waitForHydration(page)
        const l2Response = await l2Permissions
        expect(l2Response.status()).toBe(200)
        expect((await l2Response.json()).data.roleName).toBe(expectedRole)
        await expect(page).toHaveURL(`${process.env.BASE_URL}${base(scope)}/admin`)
        const legacy = `${base(scope)}/${type === 'teams' ? 'settings/shift' : 'settings/faq-settings'}`
        await expect(page.locator(`a[href="${legacy}"]`).filter({ visible: true })).toHaveCount(1)
        await expect(page.locator('body > .pointer-events-none.fixed.inset-0')).not.toBeVisible()
        await expect(page.locator(`a[href="${base(scope)}/admin/settings"]`)).toHaveCount(0)
        const hubPermissions = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === permissionPath)
        await page.goto(`${base(scope)}/admin/settings`)
        await waitForHydration(page)
        const hubResponse = await hubPermissions
        expect(hubResponse.status()).toBe(200)
        expect((await hubResponse.json()).data.roleName).toBe(expectedRole)
        await expect(page).toHaveURL(`${process.env.BASE_URL}${base(scope)}/admin/settings`)
        // loading中のリンク0を拒否と誤判定せず、新hub自身の終端forbiddenを確認する。
        const forbidden = page.getByTestId('load-error-state')
        await expect(forbidden).toBeVisible()
        await expect(forbidden.locator('.pi-lock')).toBeVisible()
        await expect(page.getByTestId('load-error-state-retry')).toHaveCount(0)
        await expect(page.locator('body > .pointer-events-none.fixed.inset-0')).not.toBeVisible()
        await expect(page.getByTestId('setting-line')).toHaveCount(0)
        await expect(page.locator('[data-testid^="setting-"]')).toHaveCount(0)
      })
    }
    for (const actor of [MEMBER, OUTSIDER]) {
      test(`${type} ${actor === MEMBER ? 'MEMBER' : '非所属'}: 新hub直URLは通常画面へ戻し設定と非公開情報を開示しない`, async ({ page }) => {
        const scope = scopes.find(item => item.type === type)!
        await page.setViewportSize({ width: 1280, height: 720 })
        await login(page, actor)
        const permissionPath = `/api/v1/${type}/${scope.slug}/me/permissions`
        const permissions = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === permissionPath)
        const scopeResponse = page.waitForResponse(response => response.request().method() === 'GET'
          && new URL(response.url()).pathname === `/api/v1/${type}/${scope.slug}`)
        await page.goto(`${base(scope)}/admin/settings`)
        await waitForHydration(page)
        const permission = await permissions
        expect(permission.status()).toBe(200)
        expect((await permission.json()).data.roleName).toBe(actor === MEMBER ? 'MEMBER' : null)
        await expect(page).toHaveURL(`${process.env.BASE_URL}${base(scope)}`)
        const detail = await scopeResponse
        expect(await detail.finished(), '団体詳細の実応答が終了している').toBeNull()
        if (actor === MEMBER) {
          expect(detail.status()).toBe(200)
          await expect(page.getByText(scope.name, { exact: true }).filter({ visible: true }).first()).toBeVisible()
          // 親の初回権限案内はADMIN限定。MEMBERに設定操作を求めない。
          const setupDialog = page.getByRole('dialog', { name: 'メンバーの権限を初期設定', exact: true })
          await expect(setupDialog).toHaveCount(0)
        }
        else {
          expect([403, 404], '非公開団体の取得自体を拒否').toContain(detail.status())
          await expect(page.getByText('情報を取得できませんでした', { exact: true })).toBeVisible()
          await expect(page.getByText('時間をおいて再度お試しください。権限がない場合は表示できないことがあります。', { exact: true })).toBeVisible()
          await expect(page.getByText(scope.name, { exact: true })).toHaveCount(0)
        }
        await expect(page.locator('body > .pointer-events-none.fixed.inset-0')).not.toBeVisible()
        await expect(page.locator('[data-testid^="setting-"]')).toHaveCount(0)
        await expect(page.locator(`a[href="${base(scope)}/admin/settings"]`)).toHaveCount(0)
        await expect(page.locator(`a[href="${base(scope)}/admin"]`)).toHaveCount(0)
        await expect(page.locator('a[href="/admin/line-settings"], a[href="/admin/receipt-settings"]')).toHaveCount(0)
      })
    }
  }
})
