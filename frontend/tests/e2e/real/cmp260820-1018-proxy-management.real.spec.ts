import { expect, test, type Browser, type Page, type TestInfo } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/** CMP-260820-1018。APIは認証・専用fixtureの前提準備と読み取り裏付けに限る。 */
const manifestPath = process.env.CMP1018_MANIFEST
const fixture = (manifestPath ? JSON.parse(readFileSync(manifestPath, 'utf8')) : {}) as {
  organization: { id: number, slug: string, name: string }
  emptyOrganization?: { id: number, slug: string, name: string }
  users: Record<'admin' | 'deputy' | 'member' | 'system', { id: number, email: string }>
  consents: Record<'paper' | 'online' | 'selfProxy' | 'deputyApprove', { id: number }>
  permissionGroupId?: number
  survey?: { id: number, questionId: number, title: string, status: string }
}
const base = 'http://localhost:3001'
const apiBase = 'http://localhost:8081'

async function memberName(page: Page, userId: number) {
  const response = await page.context().request.get(`${apiBase}/api/v1/organizations/${fixture.organization.slug}/members?page=0&size=20`)
  expect(response.status()).toBe(200)
  const rows = (await response.json()).data as Array<{ userId: number, displayName: string }>
  const member = rows.find(row => row.userId === userId)
  expect(member, '専用fixtureの候補が最初の20人に存在する').toBeDefined()
  return member!.displayName
}

async function surveyRecords(page: Page, subjectUserId?: number) {
  const query = new URLSearchParams({ organizationId: String(fixture.organization.id), page: '0', size: '20' })
  if (subjectUserId != null) query.set('subjectUserId', String(subjectUserId))
  const response = await page.context().request.get(`${apiBase}/api/v1/proxy-input-records?${query}`)
  expect(response.status()).toBe(200)
  const rows = (await response.json()).data as Array<{ id: number, consentId: number, subjectUserId: number, proxyUserId: number, targetEntityType: string, targetEntityId: number }>
  return rows.filter(row => row.consentId === fixture.consents.paper.id && row.targetEntityType === 'SURVEY' && row.targetEntityId === fixture.survey!.id)
}

async function openAs(browser: Browser, actor: keyof typeof fixture.users, organization = fixture.organization, locale = 'ja'): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport: { width: 1440, height: 900 }, locale: locale === 'ja' ? 'ja-JP' : locale })
  const page = await context.newPage()
  try {
    await loginViaApi(page, { email: fixture.users[actor].email, password: 'TestPass2026!' }, { apiBaseUrl: apiBase, deferNavigation: true })
    await page.addInitScript((org) => {
      localStorage.setItem('currentScope', JSON.stringify({ type: 'organization', id: String(org.id), name: org.name }))
    }, organization)
    await context.addCookies([{ name: 'i18n_locale', value: locale, domain: 'localhost', path: '/' }])
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

async function closeOwned(page: Page, info: TestInfo) {
  const phases: Array<{ phase: string, event: 'start' | 'complete', at: string }> = []
  const save = (phase: string, event: 'start' | 'complete') => {
    phases.push({ phase, event, at: new Date().toISOString() })
    writeFileSync(info.outputPath('cleanup-phases.json'), JSON.stringify(phases, null, 2))
  }
  await test.step('APIRequestContext.dispose', async () => {
    save('APIRequestContext.dispose', 'start')
    await page.context().request.dispose()
    save('APIRequestContext.dispose', 'complete')
  })
  await test.step('BrowserContext.close', async () => {
    save('BrowserContext.close', 'start')
    await page.context().close()
    save('BrowserContext.close', 'complete')
  })
}

test.describe.configure({ mode: 'serial' })
test.setTimeout(300_000)
test.skip(!manifestPath, 'CMP1018_MANIFEST で専用の実機fixtureを指定したときに実行する')

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
    await closeOwned(page, info)
  }
})

test('履歴前提: ADMINが紙同意を確認して承認する', async ({ browser }, info) => {
  const page = await openAs(browser, 'admin')
  try {
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    const paper = page.locator('article').filter({ has: page.getByRole('heading', { name: `代理入力同意書 #${fixture.consents.paper.id}`, exact: true }) })
    await expect(paper.getByText('承認待ち', { exact: true })).toBeVisible({ timeout: 120_000 })
    await paper.getByRole('button', { name: '承認する', exact: true }).click()
    const approved = page.waitForResponse(response => response.request().method() === 'PATCH' && new URL(response.url()).pathname.endsWith(`/${fixture.consents.paper.id}/approve`))
    await page.getByRole('dialog').getByRole('button', { name: '承認する', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await expect(paper.getByText('承認済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(paper.getByRole('button', { name: '承認する', exact: true })).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('admin-paper-approved.png'), fullPage: true })
  }
  finally { await closeOwned(page, info) }
})

test('権限あり: DEPUTYが他代理者の同意を確認して承認する', async ({ browser }, info) => {
  const page = await openAs(browser, 'deputy')
  try {
    const response = await page.context().request.get(`${apiBase}/api/v1/organizations/${fixture.organization.slug}/me/permissions`)
    expect(response.status()).toBe(200)
    const value = (await response.json()).data as { roleName: string, permissions: string[] }
    const proof = { roleName: value.roleName, approve: value.permissions.includes('PROXY_CONSENT_APPROVE'), execute: value.permissions.includes('PROXY_INPUT_EXECUTE') }
    writeFileSync(info.outputPath('safe-permission-proof.json'), JSON.stringify(proof, null, 2))
    expect(proof).toEqual({ roleName: 'DEPUTY_ADMIN', approve: true, execute: true })
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    const consent = page.locator('article').filter({ has: page.getByRole('heading', { name: `代理入力同意書 #${fixture.consents.deputyApprove.id}`, exact: true }) })
    await expect(consent.getByText('承認待ち', { exact: true })).toBeVisible({ timeout: 120_000 })
    await consent.getByRole('button', { name: '承認する', exact: true }).click()
    const approved = page.waitForResponse(result => result.request().method() === 'PATCH' && new URL(result.url()).pathname.endsWith(`/${fixture.consents.deputyApprove.id}/approve`))
    await page.getByRole('dialog').getByRole('button', { name: '承認する', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await expect(consent.getByText('承認済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(consent.getByRole('button', { name: '承認する', exact: true })).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('deputy-permitted-approved.png'), fullPage: true })
  }
  finally { await closeOwned(page, info) }
})

test('空組合: ADMINは同意も履歴も空と区別し対象者候補を取得できる', async ({ browser }, info) => {
  if (!fixture.emptyOrganization) throw new Error('専用の空組合fixtureを準備してください')
  const page = await openAs(browser, 'admin', fixture.emptyOrganization)
  try {
    await open(page, `/organizations/${fixture.emptyOrganization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    await expect(page.getByText('同意書はありません。', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(page.locator('article')).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('admin-empty-consents.png'), fullPage: true })
    await page.getByRole('link', { name: '代理入力履歴', exact: true }).click()
    await expect(page.getByText('操作履歴はありません。', { exact: true })).toBeVisible({ timeout: 120_000 })
    const picker = page.getByRole('combobox', { name: '対象者で絞り込み（任意）', exact: true })
    await expect(picker).toBeVisible({ timeout: 120_000 })
    await picker.click()
    await expect(page.getByRole('option')).toHaveCount(1)
    await page.keyboard.press('Escape')
    await expect(page.locator('article')).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('admin-empty-records-picker-ready.png'), fullPage: true })
  }
  finally { await closeOwned(page, info) }
})

test('他テナント: DEPUTYは未所属組合の直URLと切替から同意も履歴も取得できない', async ({ browser }, info) => {
  if (!fixture.emptyOrganization) throw new Error('未所属組合の専用fixtureを準備してください')
  const foreign = fixture.emptyOrganization
  const page = await openAs(browser, 'deputy', foreign)
  const proof: Array<{ path: string, foreignScope: boolean, status: number }> = []
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/v1/')) return
    const foreignScope = url.pathname.includes(`/organizations/${foreign.id}/`)
      || url.pathname.includes(`/organizations/${foreign.slug}/`)
      || url.searchParams.get('organizationId') === String(foreign.id)
    if (foreignScope) proof.push({ path: url.pathname, foreignScope, status: response.status() })
  })
  try {
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    await expect(page.locator('article')).toHaveCount(4, { timeout: 120_000 })
    await open(page, `/organizations/${foreign.slug}/admin`)
    await expect(page.getByRole('button', { name: '管理画面を開く', exact: true })).toHaveCount(0)
    for (const path of ['/admin/proxy/consents', '/admin/proxy/records']) {
      await open(page, path)
      await expect(page.getByRole('alert').filter({ hasText: '選択した組合の管理資格が必要です。' })).toBeVisible({ timeout: 120_000 })
      await expect(page.locator('article')).toHaveCount(0)
      await expect(page.getByText('同意書はありません。', { exact: true })).toHaveCount(0)
      await expect(page.getByText('操作履歴はありません。', { exact: true })).toHaveCount(0)
      const selector = page.getByRole('combobox', { name: '組合を選択', exact: true })
      await selector.click()
      await expect(page.getByRole('option', { name: foreign.name, exact: true })).toHaveCount(0)
      await expect(page.getByRole('option', { name: fixture.organization.name, exact: true })).toBeVisible()
      await page.keyboard.press('Escape')
      await page.screenshot({ path: info.outputPath(path.endsWith('consents') ? 'foreign-consents-denied.png' : 'foreign-records-denied.png'), fullPage: true })
    }
    const selector = page.getByRole('combobox', { name: '組合を選択', exact: true })
    await selector.click()
    await page.getByRole('option', { name: fixture.organization.name, exact: true }).click()
    await expect(page.getByText('操作履歴はありません。', { exact: true })).toBeVisible({ timeout: 120_000 })
    expect(proof.filter(value => (value.path === '/api/v1/proxy-input-records'
      || value.path.endsWith('/proxy-input-consents'))
      && value.status >= 200 && value.status < 300)).toHaveLength(0)
  }
  finally {
    writeFileSync(info.outputPath('safe-foreign-scope-proof.json'), JSON.stringify({ foreignOrganizationId: foreign.id, responses: proof }, null, 2))
    await closeOwned(page, info)
  }
})

test('故障注入: 一覧再試行と保存拒否を区別し実保存後の再取得失敗を明示する', async ({ browser }, info) => {
  const page = await openAs(browser, 'deputy')
  const target = fixture.consents.selfProxy.id
  let failList = true
  let rejectApproval = true
  const proof = { injectedGet503: 0, injectedPatch409: 0, actualPatch200: 0, beforeStatus: '', rejectedStatus: '', savedStatus: '' }
  const listPath = `/api/v1/organizations/${fixture.organization.id}/proxy-input-consents`
  const approvePath = `/api/v1/proxy-input-consents/${target}/approve`
  const fault = (status: number) => ({ status, contentType: 'application/json', headers: { 'access-control-allow-origin': base, 'access-control-allow-credentials': 'true' }, body: JSON.stringify({ code: 'E2E_INJECTED', message: '専用実機の故障注入' }) })
  const persistedStatus = async () => {
    const response = await page.context().request.get(`${apiBase}${listPath}?page=0&size=20`)
    expect(response.status()).toBe(200)
    const rows = (await response.json()).data as Array<{ id: number, status: string }>
    const row = rows.find(value => value.id === target)
    expect(row).toBeDefined()
    return row!.status
  }
  page.on('response', (response) => {
    if (response.request().method() === 'PATCH' && new URL(response.url()).pathname === approvePath && response.status() === 200) {
      proof.actualPatch200++
      failList = true
    }
  })
  try {
    proof.beforeStatus = await persistedStatus()
    expect(proof.beforeStatus).toBe('PENDING_APPROVAL')
    await page.route(url => url.pathname === listPath, async (route) => {
      if (failList && route.request().method() === 'GET') { proof.injectedGet503++; await route.fulfill(fault(503)) }
      else await route.continue()
    })
    await page.route(url => url.pathname === approvePath, async (route) => {
      if (rejectApproval && route.request().method() === 'PATCH') { proof.injectedPatch409++; await route.fulfill(fault(409)) }
      else await route.continue()
    })
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    const loadError = page.getByRole('alert').filter({ hasText: 'データを取得できませんでした。' })
    await expect(loadError).toBeVisible({ timeout: 120_000 })
    await expect(page.getByText('同意書はありません。', { exact: true })).toHaveCount(0)
    failList = false
    await loadError.getByRole('button').click()
    const row = page.locator('article').filter({ has: page.getByRole('heading', { name: `代理入力同意書 #${target}`, exact: true }) })
    await expect(row.getByText('承認待ち', { exact: true })).toBeVisible({ timeout: 120_000 })
    await row.getByRole('button', { name: '承認する', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '承認する', exact: true }).click()
    await expect(page.getByRole('dialog').getByRole('alert')).toHaveText('保存できませんでした。同じ操作を再試行できます。')
    await expect(page.getByText('保存しました。', { exact: true })).toHaveCount(0)
    proof.rejectedStatus = await persistedStatus()
    expect(proof.rejectedStatus).toBe('PENDING_APPROVAL')
    await page.screenshot({ path: info.outputPath('injected-409-no-success.png'), fullPage: true })
    rejectApproval = false
    const approved = page.waitForResponse(response => response.request().method() === 'PATCH' && new URL(response.url()).pathname === approvePath && response.status() === 200)
    await page.getByRole('dialog').getByRole('button', { name: '承認する', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await expect(page.getByRole('alert').filter({ hasText: '保存は完了しましたが、一覧を再取得できませんでした。再読込してください。' })).toBeVisible({ timeout: 120_000 })
    proof.savedStatus = await persistedStatus()
    expect(proof.savedStatus).toBe('APPROVED')
    await page.screenshot({ path: info.outputPath('actual-save-reload-failed.png'), fullPage: true })
    failList = false
    await loadError.getByRole('button').click()
    await expect(row.getByText('承認済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(proof.injectedPatch409).toBe(1)
    expect(proof.actualPatch200).toBe(1)
    await page.screenshot({ path: info.outputPath('actual-save-reloaded.png'), fullPage: true })
  }
  finally {
    writeFileSync(info.outputPath('safe-fault-proof.json'), JSON.stringify(proof, null, 2))
    await closeOwned(page, info)
  }
})

for (const locale of ['ja', 'en', 'de', 'es', 'ko', 'zh']) {
  test(`狭幅6言語: ${locale}の管理一覧と履歴が390pxで操作できる`, async ({ browser }, info) => {
    const texts = JSON.parse(readFileSync(new URL(`../../../app/locales/${locale}/proxy.json`, import.meta.url), 'utf8')).proxy as {
      management: { consentsTitle: string, subjectFilter: string, emptyRecords: string }
      record: { title: string }
    }
    const page = await openAs(browser, 'admin', fixture.organization, locale)
    const measurements: Array<{ page: string, clientWidth: number, scrollWidth: number, buttons: Array<{ width: number, height: number }> }> = []
    const measure = async (name: string) => {
      const value = await page.evaluate(() => ({
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        buttons: Array.from(document.querySelectorAll('.max-w-5xl button')).map(element => {
          const box = element.getBoundingClientRect()
          return { width: box.width, height: box.height }
        }).filter(box => box.width > 0 && box.height > 0),
      }))
      measurements.push({ page: name, ...value })
      expect(value.scrollWidth).toBeLessThanOrEqual(value.clientWidth)
      expect(value.buttons.length).toBeGreaterThan(0)
      for (const button of value.buttons) { expect(button.width).toBeGreaterThanOrEqual(44); expect(button.height).toBeGreaterThanOrEqual(44) }
    }
    try {
      await page.setViewportSize({ width: 390, height: 844 })
      await open(page, '/admin/proxy/consents')
      await expect(page.getByRole('heading', { name: texts.management.consentsTitle, exact: true })).toBeVisible({ timeout: 120_000 })
      await expect(page.locator('article')).toHaveCount(4, { timeout: 120_000 })
      await measure('consents')
      await page.screenshot({ path: info.outputPath(`consents-${locale}-390.png`), fullPage: true })
      await page.getByRole('link', { name: texts.record.title, exact: true }).click()
      await expect(page.getByText(texts.management.emptyRecords, { exact: true })).toBeVisible({ timeout: 120_000 })
      await expect(page.getByRole('combobox', { name: texts.management.subjectFilter, exact: true })).toBeVisible({ timeout: 120_000 })
      await measure('records')
      await page.screenshot({ path: info.outputPath(`records-${locale}-390.png`), fullPage: true })
    }
    finally {
      writeFileSync(info.outputPath('safe-mobile-measurements.json'), JSON.stringify({ locale, viewport: { width: 390, height: 844 }, measurements }, null, 2))
      await closeOwned(page, info)
    }
  })
}

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
  finally { await closeOwned(page, info) }
})

test('DEPUTYは管理導線を使えて承認権限を持たない状態では承認操作を表示しない', async ({ browser }, info) => {
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
  finally { await closeOwned(page, info) }
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
    finally { await closeOwned(page, info) }
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
    await closeOwned(page, info)
  }
})

// 依存CMP-261003-1020を統合した実機jarでのみ実行する。回答・撤回をAPIで代替しない。
test('実履歴: DEPUTYがDeskで本人のアンケートを回答し本人の再表示へ保存する', async ({ browser }, info) => {
  if (!fixture.survey) throw new Error('専用の未回答アンケートfixtureが必要です')
  const page = await openAs(browser, 'deputy')
  try {
    await open(page, '/admin/proxy-desk')
    await page.getByPlaceholder('例: proxy-records/2026/scan_001.pdf', { exact: true }).fill('cmp1018-fixture/paper-original')
    await page.getByText(`代理入力同意書 #${fixture.consents.paper.id}`, { exact: true }).click()
    await page.getByRole('button', { name: '住民をピン留め', exact: true }).click()
    await expect(page.getByText(`(対象住民ID: ${fixture.users.member.id} / 代理入力同意書 #${fixture.consents.paper.id})`, { exact: true })).toBeVisible()
    await open(page, `/surveys/${fixture.survey.id}?scope=organization&scopeId=${fixture.organization.slug}`)
    await expect(page.getByTestId('survey-response-form')).toBeVisible({ timeout: 120_000 })
    await page.getByTestId(`response-text-${fixture.survey.questionId}`).fill('専用実機で本人から預かった回答')
    const submitted = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1/surveys/${fixture.survey!.id}/responses`)
    await page.getByTestId('survey-response-submit').click()
    expect((await submitted).status()).toBe(201)
    await expect(page.getByTestId('survey-already-responded')).toBeVisible({ timeout: 120_000 })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByTestId('survey-already-responded')).toBeVisible({ timeout: 120_000 })
    const records = await surveyRecords(page, fixture.users.member.id)
    expect(records).toHaveLength(1)
    expect(records[0]).toMatchObject({ subjectUserId: fixture.users.member.id, proxyUserId: fixture.users.deputy.id })
    writeFileSync(info.outputPath('safe-saved-record-proof.json'), JSON.stringify(records, null, 2))
    await page.screenshot({ path: info.outputPath('desk-subject-answered.png'), fullPage: true })
  }
  finally { await closeOwned(page, info) }
  const member = await openAs(browser, 'member')
  try {
    const response = await member.context().request.get(`${apiBase}/api/v1/surveys/${fixture.survey.id}/responses/me`)
    expect(response.status()).toBe(200)
    const answers = (await response.json()).data as Array<{ userId: number, questionId: number }>
    expect(answers).toHaveLength(1)
    expect(answers[0]).toMatchObject({ userId: fixture.users.member.id, questionId: fixture.survey.questionId })
    await open(member, `/surveys/${fixture.survey.id}?scope=organization&scopeId=${fixture.organization.slug}`)
    await expect(member.getByTestId('survey-already-responded')).toBeVisible({ timeout: 120_000 })
    writeFileSync(info.outputPath('safe-answer-owner-proof.json'), JSON.stringify(answers.map(row => ({ userId: row.userId, questionId: row.questionId })), null, 2))
    await member.screenshot({ path: info.outputPath('subject-own-answered.png'), fullPage: true })
  }
  finally { await closeOwned(member, info) }
})

test('実履歴: ADMINは保存済操作を表示し組合と対象者の交差で絞り込む', async ({ browser }, info) => {
  if (!fixture.survey) throw new Error('専用の回答済みアンケートfixtureが必要です')
  const page = await openAs(browser, 'admin')
  try {
    const name = await memberName(page, fixture.users.member.id)
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    await page.getByRole('link', { name: '代理入力履歴', exact: true }).click()
    const record = page.locator('article').filter({ hasText: `SURVEY #${fixture.survey.id}` })
    await expect(record).toHaveCount(1, { timeout: 120_000 })
    await expect(record.getByText(`#${fixture.users.deputy.id}`, { exact: true })).toBeVisible()
    const picker = page.getByRole('combobox', { name: '対象者で絞り込み（任意）', exact: true })
    await picker.click()
    await page.getByRole('option', { name, exact: true }).click()
    await expect(record.getByText(name, { exact: true })).toBeVisible({ timeout: 120_000 })
    expect(await surveyRecords(page, fixture.users.member.id)).toHaveLength(1)
    expect(await surveyRecords(page, fixture.users.deputy.id)).toHaveLength(0)
    await page.screenshot({ path: info.outputPath('records-subject-intersection.png'), fullPage: true })
  }
  finally { await closeOwned(page, info) }
})

test('実履歴: DEPUTYの紙撤回はADMIN立会と255文字理由を保存し履歴を残す', async ({ browser }, info) => {
  if (!fixture.survey) throw new Error('専用の回答済みアンケートfixtureが必要です')
  const page = await openAs(browser, 'deputy')
  try {
    const adminName = await memberName(page, fixture.users.admin.id)
    const before = await surveyRecords(page, fixture.users.member.id)
    expect(before).toHaveLength(1)
    await open(page, `/organizations/${fixture.organization.slug}/admin`)
    await page.getByRole('button', { name: '管理画面を開く', exact: true }).click()
    const consent = page.locator('article').filter({ has: page.getByRole('heading', { name: `代理入力同意書 #${fixture.consents.paper.id}`, exact: true }) })
    await consent.getByRole('button', { name: '同意書撤回', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('checkbox')).toBeChecked()
    await dialog.getByRole('combobox', { name: '紙撤回の立会管理者', exact: true }).click()
    await page.getByRole('option', { name: adminName, exact: true }).click()
    await dialog.locator('textarea').fill('紙'.repeat(255))
    const revoked = page.waitForResponse(response => response.request().method() === 'PATCH' && new URL(response.url()).pathname.endsWith(`/${fixture.consents.paper.id}/revoke`))
    await dialog.getByRole('button', { name: '同意書撤回', exact: true }).click()
    expect((await revoked).status()).toBe(200)
    await expect(consent.getByText('撤回済み', { exact: true })).toBeVisible({ timeout: 120_000 })
    await expect(consent.getByText('紙'.repeat(255), { exact: true })).toBeVisible()
    const response = await page.context().request.get(`${apiBase}/api/v1/organizations/${fixture.organization.id}/proxy-input-consents?page=0&size=20`)
    expect(response.status()).toBe(200)
    const rows = (await response.json()).data as Array<{ id: number, status: string, revokeMethod: string, revokeWitnessedByUserId: number, revokeReason: string }>
    const saved = rows.find(row => row.id === fixture.consents.paper.id)!
    expect(saved).toMatchObject({ status: 'REVOKED', revokeMethod: 'PAPER_BY_SUBJECT', revokeWitnessedByUserId: fixture.users.admin.id, revokeReason: '紙'.repeat(255) })
    expect(await surveyRecords(page, fixture.users.member.id)).toEqual(before)
    writeFileSync(info.outputPath('safe-paper-revocation-proof.json'), JSON.stringify({ consentId: saved.id, status: saved.status, method: saved.revokeMethod, witness: saved.revokeWitnessedByUserId, reasonLength: saved.revokeReason.length, retainedRecordIds: before.map(row => row.id) }, null, 2))
    await page.getByRole('link', { name: '代理入力履歴', exact: true }).click()
    await expect(page.locator('article').filter({ hasText: `SURVEY #${fixture.survey.id}` })).toHaveCount(1, { timeout: 120_000 })
    await page.screenshot({ path: info.outputPath('revoked-consent-history-retained.png'), fullPage: true })
    await open(page, '/admin/proxy-desk')
    await expect(page.getByText(`代理入力同意書 #${fixture.consents.paper.id}`, { exact: true })).toHaveCount(0)
    const denied = await page.context().request.get(`${apiBase}/api/v1/surveys/${fixture.survey.id}/responses/me`, { headers: { 'X-Proxy-For-User-Id': String(fixture.users.member.id), 'X-Proxy-Consent-Id': String(fixture.consents.paper.id), 'X-Proxy-Input-Source': 'PAPER_FORM', 'X-Proxy-Original-Storage': 'cmp1018-fixture/paper-original' } })
    expect(denied.status()).toBe(403)
  }
  finally { await closeOwned(page, info) }
})
