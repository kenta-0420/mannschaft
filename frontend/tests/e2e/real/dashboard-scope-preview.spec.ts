import { test, expect, request, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/** 実BE/実FE。APIの用途は認証・専用fixture作成・後始末だけ。対象操作は/dashboardからUIで行う。 */
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const ADMIN = { email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local', password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!' }
const MEMBER = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!' }
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

async function openApp(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await page.waitForFunction(() => {
    const root = document.querySelector('#__nuxt')
    return root !== null && '__vue_app__' in root && root.childElementCount > 0
  }, undefined, { timeout: 60_000 })
}

async function openDashboard(page: Page): Promise<void> {
  const loaded = Promise.all(['TEAM', 'ORGANIZATION'].map(type => page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname.endsWith('/dashboard/scope-tabs') && url.searchParams.get('scopeType') === type && response.status() === 200
  })))
  await openApp(page, '/dashboard')
  await loaded
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
  const previous = page.getByTestId(`scope-tab-prevpage-${scope.type}`)
  for (let index = 0; index < 100 && await previous.isVisible() && await previous.isEnabled(); index++) {
    const loaded = page.waitForResponse(response => response.url().includes('/dashboard/scope-tabs') && response.status() === 200)
    await previous.click()
    await loaded
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
    const loaded = page.waitForResponse(response => response.url().includes('/dashboard/scope-tabs') && response.status() === 200)
    await next.click()
    await loaded
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
  })
  if (mobile) await row.click()
  else {
    await row.focus()
    await row.press('Enter')
  }
  expect((await detail).status(), '行の操作が実BEの本文取得に成功する').toBe(200)
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
  await test.info().attach(`${scope.type}-${kind}-${mobile ? 'mobile' : 'desktop'}`, {
    body: await page.screenshot(), contentType: 'image/png',
  })
}

test.describe('個人dashboard TEAM/ORG 詳細プレビュー 実API', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('非所属では一覧・直打ち拒否、MEMBER加入後は本文モーダル・閉じる・所属先CTAを利用できる', async ({ browser }) => {
    test.setTimeout(600_000)
    expect(ADMIN.email).not.toBe(MEMBER.email)
    const api = await request.newContext({ baseURL: API_BASE })
    const contexts: BrowserContext[] = []
    const scopes: Scope[] = []
    const marker = `ScopePreview-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
    let adminHeaders: Record<string, string> = {}
    let memberPage: Page | undefined
    const failures: string[] = []
    try {
      adminHeaders = await headers(api)
      for (const [type, root] of [['TEAM', 'teams'], ['ORGANIZATION', 'organizations']] as const) {
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
      }
      const memberContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      contexts.push(memberContext)
      memberPage = await memberContext.newPage()
      await loginViaApi(memberPage, MEMBER, { apiBaseUrl: API_BASE, deferNavigation: true })
      // 同じ実アカウントを非所属→MEMBERで比較する。既存scope・既存所属は変更しない。
      for (const scope of scopes) {
        await openDashboard(memberPage)
        await scopeChip(memberPage, scope, false)
        for (const path of [`/${scope.root}/${scope.slug}/bulletin?threadId=${scope.threadId}`, `/${scope.root}/${scope.slug}/todos/${scope.todoId}`]) {
          const isTodo = path.includes('/todos/')
          const deniedResponse = memberPage.waitForResponse(response => {
            const pathname = new URL(response.url()).pathname
            return response.request().method() === 'GET' && (isTodo
              ? pathname.endsWith(`/${scope.root}/${scope.slug}/todos/${scope.todoId}`)
              : pathname.endsWith(`/${scope.root}/${scope.numericId}/bulletin/threads/${scope.threadId}`))
          })
          await openApp(memberPage, path)
          const status = (await deniedResponse).status()
          expect([403, 404], 'UIから発行された他scope詳細GETを実BEが拒否する').toContain(status)
          const denied = isTodo
            ? memberPage.getByRole('alert').filter({ hasText: 'TODOの取得に失敗しました' }).first()
            : memberPage.getByTestId('load-error-state')
          await expect(denied, '非所属の詳細直打ちは明示的な拒否画面を出す').toBeVisible({ timeout: 20_000 })
          await expect(memberPage.getByText(`${marker}-${scope.type}-bulletin-本文`, { exact: false })).toHaveCount(0)
          await expect(memberPage.getByText(`${marker}-${scope.type}-todo-本文`, { exact: false })).toHaveCount(0)
          await test.info().attach(`${scope.type}-${path.includes('/todos/') ? 'todo' : 'bulletin'}-denied`, {
            body: JSON.stringify({ path, detailStatus: status, visibleError: await denied.innerText() }), contentType: 'application/json',
          })
        }
      }
      for (const scope of scopes) {
        const invitation = await api.post(`/api/v1/${scope.root}/${scope.slug}/invite-tokens`, { headers: adminHeaders,
          data: { roleId: 4, maxUses: 1, expiresIn: '1d' } })
        expect(invitation.status(), '専用scopeのMEMBER招待').toBe(201)
        const invite = (await invitation.json()).data
        scope.inviteId = invite.id
        const joined = await memberPage.request.post(`${API_BASE}/api/v1/invite/${invite.token}/join`)
        expect(joined.status(), '前提MEMBER加入').toBe(200)
        scope.joined = true
      }
      // MEMBERの通常権限でTEAM/ORGとも掲示板・TODOの本文と実ページを確認する。
      for (const scope of scopes) {
        for (const kind of ['bulletin', 'todo'] as const) await preview(memberPage, scope, kind, marker, false)
      }
      await memberPage.setViewportSize({ width: 390, height: 844 })
      for (const scope of scopes) await preview(memberPage, scope, 'todo', marker, true)
    }
    finally {
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
      }
      finally {
        await Promise.all(contexts.map(context => context.close()))
        await api.dispose()
      }
      expect(failures, '専用fixtureのcleanup').toEqual([])
    }
  })
})
