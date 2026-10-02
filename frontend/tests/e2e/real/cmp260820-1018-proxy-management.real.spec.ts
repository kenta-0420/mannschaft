import { expect, test, type Browser, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/** CMP-260820-1018。APIは認証・専用fixtureの前提準備と読み取り裏付けに限る。 */
const manifestPath = process.env.CMP1018_MANIFEST
if (!manifestPath) throw new Error('CMP1018_MANIFEST に自所有fixtureのmanifestを指定してください')
const fixture = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
  organization: { id: number, slug: string, name: string }
  users: Record<'admin' | 'deputy' | 'member' | 'system', { id: number, email: string }>
  consents: Record<'paper' | 'online' | 'selfProxy' | 'deputyApprove', { id: number }>
}
const base = 'http://localhost:3001'
const apiBase = 'http://localhost:8081'

async function openAs(browser: Browser, actor: keyof typeof fixture.users): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport: { width: 1440, height: 900 }, locale: 'ja-JP' })
  const page = await context.newPage()
  try {
    await loginViaApi(page, { email: fixture.users[actor].email, password: 'TestPass2026!' }, { apiBaseUrl: apiBase, deferNavigation: true })
    await page.addInitScript((org) => {
      localStorage.setItem('currentScope', JSON.stringify({ type: 'organization', id: String(org.id), name: org.name }))
    }, fixture.organization)
    await context.addCookies([{ name: 'i18n_locale', value: 'ja', domain: 'localhost', path: '/' }])
    return page
  }
  catch (error) {
    await context.close()
    throw error
  }
}

async function open(page: Page, path: string) {
  await page.goto(base + path, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
}

test.describe.configure({ mode: 'serial' })
test.setTimeout(300_000)

test('診断: ADMINの実資格と管理ハブの実画像を保存する', async ({ browser }, info) => {
  test.setTimeout(600_000)
  const page = await openAs(browser, 'admin')
  const network: Array<{ path: string, status?: number, failure?: string, finished?: boolean }> = []
  const consoleKinds: string[] = []
  const pending = new Map<import('@playwright/test').Request, string>()
  const safePath = (url: string) => {
    const parsed = new URL(url)
    return ['http://localhost:3001', 'http://localhost:8081', 'http://127.0.0.1:3001', 'http://127.0.0.1:8081'].includes(parsed.origin) ? parsed.pathname : null
  }
  page.on('request', (request) => {
    const path = safePath(request.url())
    if (path) pending.set(request, path)
  })
  page.on('response', (response) => {
    const path = safePath(response.url())
    if (path) network.push({ path, status: response.status() })
  })
  page.on('requestfinished', (request) => {
    const path = safePath(request.url())
    if (path) network.push({ path, finished: true })
    pending.delete(request)
  })
  page.on('requestfailed', (request) => {
    const path = safePath(request.url())
    if (path) network.push({ path, failure: request.failure()?.errorText })
    pending.delete(request)
  })
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    const text = message.text()
    consoleKinds.push(['CORS', 'Outdated Optimize Dep', 'Failed to fetch', 'ERR_CONNECTION', 'Hydration', 'timeout'].find(kind => text.includes(kind)) ?? message.type())
  })
  page.on('pageerror', (error) => consoleKinds.push(`pageerror:${error.name}`))
  try {
    const response = await page.request.get(`${apiBase}/api/v1/organizations/${fixture.organization.slug}/me/permissions`)
    expect(response.status()).toBe(200)
    const data = (await response.json()).data as { roleName: string, permissions: string[] }
    await info.attach('資格の安全な投影', { body: JSON.stringify({ roleName: data.roleName, approve: data.permissions.includes('PROXY_CONSENT_APPROVE') }), contentType: 'application/json' })
    expect(data.roleName).toBe('ADMIN')
    await page.goto(`${base}/organizations/${fixture.organization.slug}/admin`, { waitUntil: 'commit', timeout: 180_000 })
    await page.locator('body').waitFor({ state: 'visible', timeout: 90_000 })
    await page.screenshot({ path: info.outputPath('admin-hub-before-hydration.png'), fullPage: true })
    await waitForHydration(page)
    const hubReady = await page.getByRole('heading', { name: '代理入力同意管理', exact: true }).waitFor({ state: 'visible', timeout: 120_000 }).then(() => true, () => false)
    await page.screenshot({ path: info.outputPath('admin-hub-hydrated.png'), fullPage: true })
    const dom = await page.evaluate(() => {
      const root = document.querySelector('#__nuxt') as HTMLElement & {
        __vue_app__?: { config?: { globalProperties?: { $nuxt?: { isHydrating?: boolean }, $router?: { currentRoute?: { value?: { path?: string } } } } } }
      }
      const globals = root?.__vue_app__?.config?.globalProperties
      return {
        path: location.pathname,
        routerPath: globals?.$router?.currentRoute?.value?.path ?? null,
        isHydrating: globals?.$nuxt?.isHydrating ?? null,
        loadingOnly: document.body.innerText.replace(/\s/g, '') === 'loading',
        headings: [...document.querySelectorAll('h1,h2')].map(element => element.textContent?.trim()),
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
      }
    })
    await info.attach('画面の安全な投影', { body: JSON.stringify(dom), contentType: 'application/json' })
    writeFileSync(info.outputPath('safe-browser-proof.json'), JSON.stringify({ hubReady, dom, network, pending: [...pending.values()], consoleKinds }, null, 2))
  }
  finally {
    writeFileSync(info.outputPath('safe-network-proof.json'), JSON.stringify({ network, pending: [...pending.values()], consoleKinds }, null, 2))
    await page.context().close()
  }
})

test('ADMINが管理ハブから同意全状態一覧と空の実操作履歴に到達する', async ({ browser }, info) => {
  const page = await openAs(browser, 'admin')
  try {
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await expect(page.getByRole('heading', { name: '代理入力同意管理', exact: true })).toBeVisible({ timeout: 120_000 })
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/proxy\/consents/, { timeout: 120_000 })
    await expect(page.locator('article')).toHaveCount(4, { timeout: 120_000 })
    await page.screenshot({ path: info.outputPath('admin-pending-consents.png'), fullPage: true })
    await page.getByRole('link', { name: '代理入力履歴', exact: true }).click()
    await expect(page.getByText('操作履歴はありません。', { exact: true })).toBeVisible({ timeout: 120_000 })
    await page.screenshot({ path: info.outputPath('admin-empty-records.png'), fullPage: true })
  }
  finally { await page.context().close() }
})

test('DEPUTYは管理導線を使えて承認権限を持たない状態では承認操作を表示しない', async ({ browser }) => {
  const page = await openAs(browser, 'deputy')
  try {
    const response = await page.request.get(`${apiBase}/api/v1/organizations/${fixture.organization.slug}/me/permissions`)
    expect(response.status()).toBe(200)
    expect((await response.json()).data.permissions).not.toContain('PROXY_CONSENT_APPROVE')
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await expect(page.getByRole('button', { name: '管理画面を開く', exact: true })).toBeVisible({ timeout: 120_000 })
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    await expect(page.locator('article')).toHaveCount(4, { timeout: 120_000 })
    await expect(page.getByRole('button', { name: '承認する', exact: true })).toHaveCount(0)
  }
  finally { await page.context().close() }
})

for (const actor of ['member', 'system'] as const) {
  test(`${actor}は通常管理導線を持たず直URLでも同意データを表示しない`, async ({ browser }, info) => {
    const page = await openAs(browser, actor)
    try {
      await open(page, `/organizations/${fixture.organization.slug}/admin`)
      await expect(page.getByRole('button', { name: '管理画面を開く', exact: true })).toHaveCount(0)
      await open(page, '/admin/proxy/consents')
      await expect(page.getByRole('alert').filter({ hasText: '選択した組合の管理資格が必要です。' })).toBeVisible({ timeout: 120_000 })
      await expect(page.locator('article')).toHaveCount(0)
      await page.screenshot({ path: info.outputPath(`${actor}-denied.png`), fullPage: true })
    }
    finally { await page.context().close() }
  })
}

test('ADMINは取消後に他代理者の同意を承認し、本人オンライン撤回を保存できる', async ({ browser }, info) => {
  const page = await openAs(browser, 'admin')
  const mutations: Array<{ path: string, method?: string, witness?: number, reasonLength?: number }> = []
  page.on('request', (request) => {
    if (request.method() !== 'PATCH' || !request.url().includes('/proxy-input-consents/')) return
    const body = request.postDataJSON() as { revokeMethod?: string, revokeWitnessedByUserId?: number, revokeReason?: string } | null
    mutations.push({ path: new URL(request.url()).pathname, method: body?.revokeMethod, witness: body?.revokeWitnessedByUserId, reasonLength: body?.revokeReason?.length })
  })
  try {
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    const row = (id: number) => page.locator('article').filter({ has: page.getByRole('heading', { name: `代理入力同意書 #${id}`, exact: true }) })
    await expect(row(fixture.consents.online.id)).toBeVisible({ timeout: 120_000 })
    await expect(row(fixture.consents.selfProxy.id).getByRole('button', { name: '承認する', exact: true })).toHaveCount(0)
    const online = row(fixture.consents.online.id)
    await online.getByRole('button', { name: '承認する', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(mutations).toHaveLength(0)
    await online.getByRole('button', { name: '承認する', exact: true }).click()
    const approved = page.waitForResponse(response => response.request().method() === 'PATCH' && new URL(response.url()).pathname.endsWith(`/${fixture.consents.online.id}/approve`))
    await page.getByRole('dialog').getByRole('button', { name: '承認する', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await expect(online.getByText('承認済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(online.getByRole('button', { name: '承認する', exact: true })).toHaveCount(0)
    await online.getByRole('button', { name: '同意書撤回', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('checkbox')).not.toBeChecked()
    await expect(dialog.getByRole('combobox')).toHaveCount(0)
    await dialog.locator('textarea').fill('本'.repeat(255))
    const revoked = page.waitForResponse(response => response.request().method() === 'PATCH' && new URL(response.url()).pathname.endsWith(`/${fixture.consents.online.id}/revoke`))
    await dialog.getByRole('button', { name: '同意書撤回', exact: true }).click()
    expect((await revoked).status()).toBe(200)
    await expect(online.getByText('撤回済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(online.getByText('本人オンライン申請', { exact: true })).toBeVisible()
    await expect(online.getByText('本'.repeat(255), { exact: true })).toBeVisible()
    await expect(online.getByRole('button')).toHaveCount(0)
    expect(mutations.at(-1)).toMatchObject({ method: 'API_BY_SUBJECT', reasonLength: 255 })
    expect(mutations.at(-1)?.witness).toBeUndefined()
    await page.screenshot({ path: info.outputPath('admin-online-revoked.png'), fullPage: true })
  }
  finally {
    writeFileSync(info.outputPath('safe-mutation-proof.json'), JSON.stringify({ mutations }, null, 2))
    await page.context().close()
  }
})
