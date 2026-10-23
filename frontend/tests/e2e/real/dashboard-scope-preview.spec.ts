import { test, expect, request, type APIRequestContext, type Page, type Response } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/** 実BE/実FE。APIの用途は認証・専用fixture作成・後始末だけ。対象操作は/dashboardからUIで行う。 */
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const ADMIN = { email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local', password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!' }
// 通常USERの大量既存所属による対象外のwidget負荷を避け、所属数の少ない公開認可seedを使う。
const MEMBER = { email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!' }
type Scope = {
  type: 'TEAM' | 'ORGANIZATION'
  root: 'teams' | 'organizations'
  slug: string
  numericId: number
  threadId?: number
  todoId?: number
  inviteId?: number
  joined?: boolean
}

async function stage<T>(name: string, action: () => Promise<T>, timeout = 120_000): Promise<T> {
  console.info(`[dashboard-scope-preview] 開始: ${name}`)
  const result = await test.step(name, action, { timeout })
  console.info(`[dashboard-scope-preview] 完了: ${name}`)
  return result
}

async function openApp(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded', timeout: 60_000 })
  const initialHydration = await page.evaluate(() => {
    const root = document.querySelector('#__nuxt') as (HTMLElement & { __vue_app__?: { config: { globalProperties: { $nuxt?: { isHydrating?: boolean } } } } }) | null
    return root?.__vue_app__?.config.globalProperties.$nuxt?.isHydrating ?? null
  })
  console.info(`[dashboard-scope-preview] navigation: isHydrating=${initialHydration}`)
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt') as (HTMLElement & { __vue_app__?: { config: { globalProperties: { $nuxt?: { isHydrating?: boolean } } } } }) | null
    return root?.__vue_app__?.config.globalProperties.$nuxt?.isHydrating === false && root.childElementCount > 0
  }, undefined, { timeout: 60_000 })
  console.info('[dashboard-scope-preview] navigation: isHydrating=false')
}

async function openDashboard(page: Page): Promise<void> {
  // dev環境のmodule起動時間と、起動後のAPI応答時間を分ける。早い応答もlistenerで取りこぼさない。
  const loaded = new Set<string>()
  const observe = (response: Response) => {
    const url = new URL(response.url())
    if (url.pathname.endsWith('/dashboard/scope-tabs') && response.status() === 200) loaded.add(url.searchParams.get('scopeType') ?? '')
  }
  page.on('response', observe)
  try {
    await openApp(page, '/dashboard')
    await expect.poll(() => ['TEAM', 'ORGANIZATION'].every(type => loaded.has(type)), { timeout: 30_000 }).toBe(true)
  }
  finally { page.off('response', observe) }
}

async function headers(api: APIRequestContext): Promise<Record<string, string>> {
  const response = await api.post('/api/v1/auth/login', { data: ADMIN })
  expect(response.status(), 'fixture管理者ログイン').toBe(200)
  return { Authorization: `Bearer ${(await response.json()).data.accessToken}` }
}

/** 所属チップのページ送りも利用者と同じUIを通し、既存の表示順設定は変更しない。 */
async function scopeChip(page: Page, scope: Scope, mustExist: boolean): Promise<void> {
  await page.getByTestId(`scope-segment-${scope.type}`).click()
  const bar = page.getByTestId(`scope-tab-bar-${scope.type}`)
  await expect(bar).toBeVisible()
  async function turn(direction: 'prev' | 'next'): Promise<void> {
    const loaded = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname.endsWith('/dashboard/scope-tabs') && url.searchParams.get('scopeType') === scope.type
    }, { timeout: 30_000 })
    const [, response] = await Promise.all([page.getByTestId(`scope-tab-${direction}page-${scope.type}`).click(), loaded])
    expect(response.status(), 'UIページ送りのscope-tabs取得').toBe(200)
    const data = (await response.json()).data as { items: Array<{ public_id: string }> }
    const expected = data.items.map(item => `scope-tab-chip-${scope.type}-${item.public_id}`).sort()
    // responseイベント直後の旧hasNext/旧chipを見て次へ進まず、返ったページのDOM確定まで待つ。
    await expect.poll(async () => (await bar.locator(`[data-testid^="scope-tab-chip-${scope.type}-"]`).evaluateAll(
      elements => elements.map(element => element.getAttribute('data-testid')),
    )).sort()).toEqual(expected)
  }
  const previous = page.getByTestId(`scope-tab-prevpage-${scope.type}`)
  for (let index = 0; index < 100 && await previous.isVisible() && await previous.isEnabled(); index++) {
    await turn('prev')
  }
  const chip = page.getByTestId(`scope-tab-chip-${scope.type}-${scope.slug}`)
  for (let index = 0; index < 100; index++) {
    if (await chip.count()) {
      expect(mustExist, '非所属の専用scopeチップが表示されない').toBe(true)
      if (await chip.getAttribute('aria-pressed') !== 'true') await chip.click()
      await expect(chip).toHaveAttribute('aria-pressed', 'true')
      return
    }
    const next = page.getByTestId(`scope-tab-nextpage-${scope.type}`)
    if (!await next.isVisible() || !await next.isEnabled()) {
      expect(mustExist, '所属scopeチップがUIの全ページから到達できる').toBe(false)
      return
    }
    await turn('next')
  }
  throw new Error('scopeチップが100ページを超えました')
}

async function preview(page: Page, scope: Scope, kind: 'bulletin' | 'todo', marker: string, mobile: boolean): Promise<void> {
  await openDashboard(page)
  await scopeChip(page, scope, true)
  const id = kind === 'bulletin' ? scope.threadId! : scope.todoId!
  const grid = page.getByTestId(`swipe-widget-grid-${scope.type}`)
  const row = grid.getByTestId(`scope-widget-${kind}-${id}`)
  await expect(row).toBeVisible({ timeout: 20_000 })
  const sourcePath = kind === 'bulletin'
    ? `/${scope.root}/${scope.slug}/bulletin?threadId=${id}`
    : `/${scope.root}/${scope.slug}/todos/${id}`
  const body = `${marker}-${scope.type}-${kind}-本文`
  const detail = page.waitForResponse(response => {
    const pathname = new URL(response.url()).pathname
    return response.request().method() === 'GET' && (kind === 'bulletin'
      ? pathname.endsWith(`/bulletin/threads/${id}`)
      : pathname.endsWith(`/todos/${id}`))
  }, { timeout: 30_000 })
  const [, detailResponse] = await Promise.all([(async () => {
    if (mobile) await row.click()
    else {
      await row.focus()
      await row.press('Enter')
    }
  })(), detail])
  expect(detailResponse.status(), '行の操作が実BEの本文取得に成功する').toBe(200)
  const dialog = page.getByTestId('scope-widget-preview')
  await expect(dialog).toBeVisible()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByTestId('scope-widget-preview-body')).toContainText(body)
  const source = page.getByTestId('scope-widget-preview-source')
  await expect(source).toHaveAttribute('href', sourcePath)
  if (mobile) {
    const bounds = await dialog.boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(391)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  }
  await test.info().attach(`${scope.type}-${kind}-${mobile ? 'mobile' : 'desktop'}-modal`, {
    body: await page.screenshot(), contentType: 'image/png',
  })
  if (!mobile && kind === 'bulletin') await page.keyboard.press('Escape')
  else await page.getByTestId('scope-widget-preview-close').click()
  await expect(dialog).not.toBeVisible()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(row).toBeFocused()
  // 閉じたあと同じ行から再表示し、CTAで所属先の該当詳細まで進む。
  if (mobile) await row.click()
  else await row.press('Space')
  await expect(page.getByTestId('scope-widget-preview-body')).toContainText(body)
  await source.click()
  await expect.poll(() => new URL(page.url()).pathname + new URL(page.url()).search).toBe(sourcePath)
  await expect(page.getByText(body, { exact: false }).first()).toBeVisible({ timeout: 20_000 })
  await page.getByText(body, { exact: false }).first().scrollIntoViewIfNeeded()
  await test.info().attach(`${scope.type}-${kind}-${mobile ? 'mobile' : 'desktop'}`, {
    body: await page.screenshot(), contentType: 'image/png',
  })
}

test.describe('個人dashboard TEAM/ORG 詳細プレビュー 実API', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('非所属では一覧・直打ち拒否、MEMBER加入後は本文モーダル・閉じる・所属先CTAを利用できる', async ({ page }) => {
    test.setTimeout(600_000)
    expect(ADMIN.email).not.toBe(MEMBER.email)
    const api = await request.newContext({ baseURL: API_BASE, timeout: 30_000 })
    const scopes: Scope[] = []
    const marker = `ScopePreview-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
    let adminHeaders: Record<string, string> = {}
    let memberPage: Page | undefined
    const originalMembershipCounts: Partial<Record<Scope['type'], number>> = {}
    const diagnostics: { failed: Array<{ type: string; method: string; pathname: string; failure: string | null }>; httpErrors: Array<{ pathname: string; status: number }>; scriptErrors: string[]; consoleErrors: number; moduleResponses: number } = {
      failed: [], httpErrors: [], scriptErrors: [], consoleErrors: 0, moduleResponses: 0,
    }
    const failures: string[] = []
    try {
      adminHeaders = await stage('fixture管理者ログイン', () => headers(api), 30_000)
      for (const [type, root] of [['TEAM', 'teams'], ['ORGANIZATION', 'organizations']] as const) {
        console.info(`[dashboard-scope-preview] 開始: ${type}所有fixture作成`)
        const slug = `scope-${type === 'TEAM' ? 't' : 'o'}-${Date.now()}`
        const created = await api.post(`/api/v1/${root}`, { headers: adminHeaders, data: {
          name: `${marker}-${type}`, slug,
          ...(type === 'ORGANIZATION' ? { orgType: 'COMMUNITY', visibility: 'PUBLIC' } : {}),
        } })
        expect(created.status(), `${type}専用fixture作成`).toBe(201)
        const data = (await created.json()).data
        const scope: Scope = { type, root, slug, numericId: data.numericId }
        scopes.push(scope)
        expect(Number.isSafeInteger(scope.numericId)).toBe(true)
        const thread = await api.post(`/api/v1/${root}/${scope.numericId}/bulletin/threads`, { headers: adminHeaders,
          data: { title: `${marker}-${type}-掲示板`, body: `<p>${marker}-${type}-bulletin-本文</p>`, priority: 'INFO' } })
        expect(thread.status(), `${type}掲示板fixture`).toBe(201)
        scope.threadId = (await thread.json()).data.id
        const todo = await api.post(`/api/v1/${root}/${slug}/todos`, { headers: adminHeaders,
          data: { title: `${marker}-${type}-TODO`, description: `${marker}-${type}-todo-本文` } })
        expect(todo.status(), `${type}TODO fixture`).toBe(201)
        scope.todoId = (await todo.json()).data.id
        console.info(`[dashboard-scope-preview] 完了: ${type}所有fixture作成`)
      }
      memberPage = page
      const privatePath = (url: string) => new URL(url).pathname.replace(/\/invite\/[^/]+/, '/invite/[redacted]')
      memberPage.on('requestfailed', result => diagnostics.failed.push({ type: result.resourceType(), method: result.method(), pathname: privatePath(result.url()), failure: result.failure()?.errorText ?? null }))
      memberPage.on('response', result => {
        if (result.request().resourceType() === 'script') diagnostics.moduleResponses++
        if (result.status() >= 400) diagnostics.httpErrors.push({ pathname: privatePath(result.url()), status: result.status() })
      })
      memberPage.on('pageerror', error => diagnostics.scriptErrors.push(error.name))
      memberPage.on('console', message => { if (message.type() === 'error') diagnostics.consoleErrors++ })
      await stage('MEMBER fresh login・me取得', () => loginViaApi(memberPage!, MEMBER, { apiBaseUrl: API_BASE, deferNavigation: true }), 30_000)
      for (const scope of scopes) {
        const result = await memberPage.request.get(`${API_BASE}/api/v1/dashboard/scope-tabs?scopeType=${scope.type}&page=0`)
        expect(result.status(), '既存所属件数のfixture前提確認').toBe(200)
        originalMembershipCounts[scope.type] = (await result.json()).data.total_count
      }
      await test.info().attach('fixture-actor-selection', { body: JSON.stringify({
        actor: '公開OUTSIDER seed', reason: '通常USERの大量既存所属による無関係なwidget負荷を避ける。既存所属は変更せず新規専用scopeだけ加入・退出する',
        originalMembershipCounts,
      }), contentType: 'application/json' })
      // 同じ実アカウントを非所属→MEMBERで比較する。既存scope・既存所属は変更しない。
      for (const scope of scopes) {
        console.info(`[dashboard-scope-preview] 開始: 非所属${scope.type} UI`)
        await openDashboard(memberPage)
        await scopeChip(memberPage, scope, false)
        for (const path of [`/${scope.root}/${scope.slug}/bulletin?threadId=${scope.threadId}`, `/${scope.root}/${scope.slug}/todos/${scope.todoId}`]) {
          const isTodo = path.includes('/todos/')
          const deniedResponses: Response[] = []
          const observeDenied = (response: Response) => {
            const pathname = new URL(response.url()).pathname
            const scopeBase = `/api/v1/${scope.root}/${scope.slug}`
            const target = isTodo ? `${scopeBase}/todos/${scope.todoId}` : `/api/v1/${scope.root}/${scope.numericId}/bulletin/threads/${scope.threadId}`
            if (response.request().method() === 'GET' && [403, 404].includes(response.status())
              && [target, scopeBase, `${scopeBase}/me/permissions`].includes(pathname)) deniedResponses.push(response)
          }
          memberPage.on('response', observeDenied)
          try {
            await openApp(memberPage, path)
            await expect.poll(() => deniedResponses.length, { timeout: 30_000 }).toBeGreaterThan(0)
          }
          finally { memberPage.off('response', observeDenied) }
          const response = deniedResponses[0]!
          const status = response.status()
          expect([403, 404], 'UIから発行された他scope詳細GETを実BEが拒否する').toContain(status)
          const denied = memberPage.getByTestId('load-error-state')
            .or(memberPage.getByRole('alert').filter({ hasText: 'TODOの取得に失敗しました' }))
            .or(memberPage.getByText('情報を取得できませんでした', { exact: true }).locator('..')).first()
          await expect(denied, '非所属の詳細直打ちは明示的な拒否画面を出す').toBeVisible({ timeout: 20_000 })
          await expect(memberPage.getByText(`${marker}-${scope.type}-bulletin-本文`, { exact: false })).toHaveCount(0)
          await expect(memberPage.getByText(`${marker}-${scope.type}-todo-本文`, { exact: false })).toHaveCount(0)
          await test.info().attach(`${scope.type}-${path.includes('/todos/') ? 'todo' : 'bulletin'}-denied`, {
            body: JSON.stringify({ path, deniedApiPath: new URL(response.url()).pathname, detailStatus: status, visibleError: await denied.innerText() }), contentType: 'application/json',
          })
        }
        console.info(`[dashboard-scope-preview] 完了: 非所属${scope.type} UI`)
      }
      for (const scope of scopes) {
        console.info(`[dashboard-scope-preview] 開始: ${scope.type} MEMBER加入`)
        const invitation = await api.post(`/api/v1/${scope.root}/${scope.slug}/invite-tokens`, { headers: adminHeaders,
          data: { roleId: 4, maxUses: 1, expiresIn: '1d' } })
        expect(invitation.status(), '専用scopeのMEMBER招待').toBe(201)
        const invite = (await invitation.json()).data
        scope.inviteId = invite.id
        const joined = await memberPage.request.post(`${API_BASE}/api/v1/invite/${invite.token}/join`)
        expect(joined.status(), '前提MEMBER加入').toBe(200)
        scope.joined = true
        console.info(`[dashboard-scope-preview] 完了: ${scope.type} MEMBER加入`)
      }
      // MEMBERの通常権限でTEAM/ORGとも掲示板・TODOの本文と実ページを確認する。
      for (const scope of scopes) {
        for (const kind of ['bulletin', 'todo'] as const) await stage(`${scope.type} ${kind} desktop UI`, () => preview(memberPage!, scope, kind, marker, false))
      }
      await memberPage.setViewportSize({ width: 390, height: 844 })
      for (const scope of scopes) await stage(`${scope.type} todo mobile UI`, () => preview(memberPage!, scope, 'todo', marker, true))
    }
    catch (error) {
      if (memberPage && !memberPage.isClosed()) {
        const dom = await Promise.race([
          memberPage.evaluate(() => ({ hydrated: Boolean(document.querySelector('#__nuxt') && '__vue_app__' in document.querySelector('#__nuxt')!),
            header: Boolean(document.querySelector('header')), carousel: Boolean(document.querySelector('[data-testid="scope-carousel"]')), readyState: document.readyState,
          })).catch(() => ({ unavailable: true })),
          new Promise<{ unavailable: true }>(resolve => setTimeout(() => resolve({ unavailable: true }), 5_000)),
        ])
        await test.info().attach('failure-runtime', { body: JSON.stringify({ ...diagnostics, url: new URL(memberPage.url()).pathname,
          dom,
        }), contentType: 'application/json' })
        try {
          await test.info().attach('failure-page', { body: await memberPage.screenshot({ timeout: 10_000 }), contentType: 'image/png' })
        }
        catch (captureError) {
          await test.info().attach('failure-page-unavailable', { body: JSON.stringify({ reason: captureError instanceof Error ? captureError.name : 'capture_failed' }), contentType: 'application/json' })
        }
      }
      throw error
    }
    finally {
      console.info('[dashboard-scope-preview] 開始: 所有fixture cleanup')
      // 本specで作ったIDのみ削除。途中失敗でも残りのcleanupを続け、失敗は隠さず集約する。
      async function remove(path: string, member = false): Promise<void> {
        try {
          const result = member
            ? await memberPage!.request.delete(`${API_BASE}${path}`)
            : await api.delete(path, { headers: adminHeaders })
          if (![200, 204, 404].includes(result.status())) failures.push(`${path}: ${result.status()}`)
        }
        catch (error) { failures.push(`${path}: ${error instanceof Error ? error.message : String(error)}`) }
      }
      try {
        adminHeaders = await headers(api)
        for (const scope of [...scopes].reverse()) {
          if (scope.joined) await remove(`/api/v1/${scope.root}/${scope.slug}/me`, true)
          if (scope.inviteId) await remove(`/api/v1/${scope.root}/${scope.slug}/invite-tokens/${scope.inviteId}`)
          if (scope.todoId) await remove(`/api/v1/${scope.root}/${scope.slug}/todos/${scope.todoId}`)
          if (scope.threadId) await remove(`/api/v1/bulletin/threads/${scope.threadId}`)
          await remove(`/api/v1/${scope.root}/${scope.slug}`)
        }
        if (memberPage) {
          for (const scope of scopes) {
            if (originalMembershipCounts[scope.type] === undefined) continue
            const result = await memberPage.request.get(`${API_BASE}/api/v1/dashboard/scope-tabs?scopeType=${scope.type}&page=0`)
            if (result.status() !== 200 || (await result.json()).data.total_count !== originalMembershipCounts[scope.type]) failures.push(`${scope.type}: 既存所属件数が前提と不一致`)
          }
        }
      }
      finally {
        await api.dispose()
      }
      expect(failures, '専用fixtureのcleanup').toEqual([])
      console.info('[dashboard-scope-preview] 完了: 所有fixture cleanup')
    }
  })
})
