import { expect, test, type Locator, type Page } from '@playwright/test'
import { waitForHydration as waitForNuxtHydration } from '../helpers/wait'
import desktopBaselines from './fixtures/common-controls-desktop.json' with { type: 'json' }

// 実Nuxt/PrimeVue/CSSを使い、外部API境界だけをstubする。実ログインは行わない。
test.use({ storageState: { cookies: [], origins: [] } })
const actor = { id: 1, email: 'ui-fixture@example.invalid', fullName: 'UI Fixture', profileImageUrl: null, systemRole: null, timezone: 'Asia/Tokyo' }

async function restoreStoredActor(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000))
    localStorage.setItem('currentScope', JSON.stringify({ type: 'team', id: '1', name: 'UI Fixture' }))
  }, actor)
}

async function waitForHydration(page: Page) {
  await waitForNuxtHydration(page)
  // Vue app登録の直後はdefault layoutがSSRのLoadingBounceのままの場合がある。
  // このspecだけで実mountを待ち、対象controlの不在を寸法redと混同しない。
  if (new URL(page.url()).pathname !== '/login') await expect(page.locator('header')).toBeVisible({ timeout: 60_000 })
}

async function stubBoundary(page: Page, authenticated = true, emptyReceipts = false) {
  const requests: { method: string, path: string, search: string }[] = []
  const unhandled: string[] = []
  if (authenticated) await restoreStoredActor(page)
  page.on('pageerror', error => console.warn('COMMON pageerror:', error.message))
  page.on('requestfailed', request => console.warn('COMMON requestfailed:', request.method(), new URL(request.url()).pathname, request.failure()?.errorText))
  page.on('console', message => { if (message.type() === 'error') console.warn('COMMON browser console:', message.text()) })
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    requests.push({ method, path, search: url.search })
    const get: Record<string, unknown> = {
      '/api/v1/users/me': { ...actor, firstName: 'UI', lastName: 'Fixture', avatarUrl: null, hasPassword: true, twoFaEnabled: false },
      '/api/v1/me/teams': [{ id: 1, slug: 'ui-fixture', name: 'UI Fixture', role: 'ADMIN', template: 'GENERAL', memberCount: 1, nickname1: null, iconUrl: null }],
      '/api/v1/me/organizations': [],
      '/api/v1/settings/nav': { features: [] },
      '/api/v1/feature-flags': [],
      '/api/v1/dashboard/scope-tabs': { items: [], page: 0, page_size: 20, total_pages: 0, total_count: 0, has_next: false, has_prev: false },
      '/api/v1/settings/appearance': { theme: 'LIGHT', bgColor: '#f3efe0', darkBgColor: '#18181b', seasonalThemeId: null, hideChatPreview: false },
      '/api/v1/notifications/unread-count': { unreadCount: 0 },
      '/api/v1/chat/channels': [], '/api/v1/mentions': [],
      '/api/v1/inbox/summary': { byState: {}, byPriority: {}, bySourceType: {} },
      '/api/v1/active-incidents': { incidents: [] },
      '/api/v1/notification-preferences': [],
      '/api/v1/notification-settings': { priorityAutoDelivery: true },
      '/api/v1/notification-type-preferences': [{ notificationType: 'URGENT', label: 'Urgent', isLocked: true, isEnabled: true, channelOverride: false, inAppEnabled: true, pushEnabled: true }],
      '/api/v1/auth/sessions': [], '/api/v1/auth/webauthn/credentials': [],
      '/api/v1/users/me/oauth': [], '/api/v1/users/me/line/status': { linked: false },
      '/api/v1/member-cards/my': [], '/api/v1/social/profiles/me': null,
      '/api/v1/users/1/seals': [], '/api/v1/users/1/seals/scope-defaults': [],
      '/api/v1/me/google-calendar/status': { connected: false, googleAccountEmail: null, googleCalendarId: null, active: false, personalSyncEnabled: false, lastSyncError: null },
      '/api/v1/admin/receipt-settings': { id: 1, scopeType: 'TEAM', scopeId: 1, issuerName: 'UI Fixture', isQualifiedInvoicer: false, invoiceRegistrationNumber: null, autoResetNumber: true, fiscalYearStartMonth: 4, nextReceiptNumber: 1 },
      '/api/v1/teams/ui-fixture': { id: 'ui-fixture', slug: 'ui-fixture', numericId: 1, basicInfo: { name: 'UI Fixture' }, location: { template: 'OTHER' }, visibility: { visibility: 'MEMBERS_AND_ABOVE', supporterEnabled: false }, metadata: { memberCount: 1 }, social: { supporterCount: 0 }, timezone: 'Asia/Tokyo' },
      '/api/v1/organizations/ui-fixture': { id: 'ui-fixture', slug: 'ui-fixture', numericId: 1, basicInfo: { name: 'UI Fixture' }, visibility: { visibility: 'PRIVATE', supporterEnabled: false }, metadata: { memberCount: 1 }, social: { supporterCount: 0 } },
      '/api/v1/teams/ui-fixture/me/permissions': { roleName: 'ADMIN', permissions: [], scopeAdmin: true },
      '/api/v1/organizations/ui-fixture/me/permissions': { roleName: 'ADMIN', permissions: [], scopeAdmin: true },
      '/api/v1/dashboard/team/ui-fixture/widget-visibility': { widgets: [] },
      '/api/v1/dashboard/organization/ui-fixture/widget-visibility': { widgets: [] },
      '/api/v1/teams/ui-fixture/modules': [],
      '/api/v1/organizations/ui-fixture/modules': [],
      '/api/v1/postal-code/policies': [{ countryCode: 'JP', pattern: '^\\d{3}-?\\d{4}$', example: '123-4567' }],
      '/api/v1/me/favorites/check': { isFavorited: false, favoriteId: null },
      '/api/v1/teams/ui-fixture/profile': { id: 1, homepage_url: null, established_date: null, established_date_precision: null, philosophy: null, profile_visibility: null },
      '/api/v1/organizations/ui-fixture/profile': { id: 1, homepage_url: null, established_date: null, established_date_precision: null, philosophy: null, profile_visibility: null },
      '/api/v1/teams/ui-fixture/officers': [], '/api/v1/organizations/ui-fixture/officers': [],
      '/api/v1/teams/ui-fixture/custom-fields': [], '/api/v1/organizations/ui-fixture/custom-fields': [],
      '/api/v1/master/prefectures': [],
      '/api/v1/admin/member-permissions': { permissions: ['MANAGE_SCHEDULES', 'MANAGE_FILES', 'MANAGE_POSTS'].map(name => ({ name, enabled: false, inherited: false })) },
      '/api/v1/teams/ui-fixture/follow/status': { status: 'NONE' },
      '/api/v1/organizations/ui-fixture/follow/status': { status: 'NONE' },
      '/api/v1/me/org-affiliation-eligibility': { canApply: false },
      '/api/v1/organizations/ui-fixture/teams': [],
      '/api/v1/organizations/ui-fixture/permission-groups': [],
      '/api/v1/organizations/ui-fixture/ancestors': [],
    }
    if (method === 'GET' && path === '/api/v1/admin/receipts') {
      const number = Number(url.searchParams.get('page') ?? '0')
      await route.fulfill({ json: { data: emptyReceipts ? [] : [{ id: number + 1, receiptNumber: 'UI-1', status: 'ISSUED', recipientName: 'UI Fixture', issuerName: 'UI Fixture', isQualifiedInvoice: false, description: 'UI Fixture', amount: 100, taxRate: 10, taxAmount: 9, amountExclTax: 91, issuedAt: '2026-10-17T12:00:00', createdAt: '2026-10-17T12:00:00', updatedAt: '2026-10-17T12:00:00' }], meta: { total: emptyReceipts ? 0 : 120, page: number, size: 20, totalPages: emptyReceipts ? 0 : 6 } } })
    } else if (method === 'GET' && path === '/api/v1/users/1/stamps') {
      await route.fulfill({ json: { data: [], meta: { nextCursor: null } } })
    } else if (method === 'GET' && path === '/api/v1/organizations/ui-fixture/children') {
      await route.fulfill({ json: { data: [], meta: { hasNext: false, nextCursor: null } } })
    } else if (method === 'GET' && path === '/api/v1/users/me/login-history') {
      const next = !url.searchParams.has('cursor')
      await route.fulfill({ json: { data: next ? [{ id: 1, eventType: 'LOGIN_SUCCESS', ipAddress: '192.0.2.1', userAgent: 'UI Fixture', createdAt: '2026-10-17T12:00:00', method: 'PASSWORD' }] : [], meta: { nextCursor: next ? 'fixture-next' : null, hasNext: next, limit: 10 } } })
    } else if (method === 'GET' && path === '/api/v1/account/data-export/status') {
      await route.fulfill({ status: 404, json: { error: { code: 'GDPR_003', message: 'No export' } } })
    } else if (method === 'GET' && Object.hasOwn(get, path)) {
      await route.fulfill({ json: { data: get[path] } })
    } else {
      // 未定義を正常空へ偽装しない。呼出元/契約を調査して明示stubへ追加する。
      unhandled.push(`${method} ${path}`)
      console.warn('COMMON undefined API boundary:', method, path)
      await route.fulfill({ status: 500, json: { error: { code: 'TEST_UNHANDLED_BOUNDARY', message: 'Unstubbed external boundary' } } })
    }
  })
  return { requests, unhandled }
}

async function targetBox(target: Locator) {
  await expect(target).toBeVisible()
  const box = await target.boundingBox()
  expect(box).not.toBeNull()
  expect.soft(box!.width, '実クリックtargetの幅').toBeGreaterThanOrEqual(44)
  expect.soft(box!.height, '実クリックtargetの高さ').toBeGreaterThanOrEqual(44)
  return box!
}

async function noPagePan(page: Page) {
  const overflow = await page.evaluate(() => {
    const viewport = document.documentElement.clientWidth
    if (document.documentElement.scrollWidth <= viewport) return null
    const describe = (el: Element) => {
      const rect = el.getBoundingClientRect()
      const style = getComputedStyle(el)
      return { tag: el.tagName, class: el.className, text: el.textContent?.trim().slice(0, 100), x: rect.x, width: rect.width, right: rect.right, display: style.display, minWidth: style.minWidth, flex: style.flex, flexWrap: style.flexWrap, whiteSpace: style.whiteSpace, overflowWrap: style.overflowWrap, overflowX: style.overflowX }
    }
    const heading = document.querySelector('h1')
    const ancestors = []
    for (let el: HTMLElement | null = heading; el; el = el.parentElement) ancestors.push(describe(el))
    return { viewport, scrollWidth: document.documentElement.scrollWidth, ancestors, elements: [...document.querySelectorAll('body *')].filter(el => { const rect = el.getBoundingClientRect(); return rect.width > 0 && rect.right > viewport + 1 && !el.closest('.p-datatable-table-container') }).slice(0, 25).map(describe) }
  })
  if (overflow) await test.info().attach('page-overflow.json', { body: JSON.stringify(overflow, null, 2), contentType: 'application/json' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}

for (const width of [360, 390]) {
  test(`COMMON-${width}-AUX: org/team補助ナビの実targetと通常開閉`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    await page.goto('/admin/receipts')
    await waitForHydration(page)
    for (const scope of ['teams', 'organizations']) {
      // slug解決のSSRは外部APIを使うため、stub済みentryから実routerで初期化する。
      // 自然導線の検証ではない。補助ナビの開閉は以下で実UIを操作する。
      await page.evaluate(async (path) => {
        const root = document.querySelector('#__nuxt') as HTMLElement & { __vue_app__?: { config: { globalProperties: { $router: { push: (path: string) => Promise<unknown> } } } } }
        if (!root.__vue_app__) throw new Error('Nuxt app is not mounted')
        await root.__vue_app__.config.globalProperties.$router.push(path)
      }, `/${scope}/ui-fixture/info`)
      await expect(page).toHaveURL(new RegExp(`/${scope}/ui-fixture/info$`))
      await waitForHydration(page)
      const toggle = page.getByTestId('scope-sidebar-toggle')
      await targetBox(toggle)
      await toggle.click()
      await expect(page.locator('.p-drawer')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(page.locator('.p-drawer')).not.toBeVisible()
      await noPagePan(page)
    }
    expect(boundary.unhandled).toEqual([])
    expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
  })

  test(`COMMON-${width}-LOGIN: 実email/Password入力44pxと16px文字・表示切替`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page, false)
    await page.goto('/login')
    await waitForHydration(page)
    for (const selector of ['#email', '#password']) {
      const input = page.locator(selector)
      const box = await targetBox(input)
      expect(await input.evaluate(el => Number.parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(16)
      await input.click({ position: { x: 2, y: box.height / 2 } })
      await expect(input).toBeFocused()
    }
    await page.locator('#password').fill('UiFixturePassword')
    await page.locator('.p-password-toggle-mask-icon').click()
    await expect(page.locator('#password')).toHaveAttribute('type', 'text')
    await noPagePan(page)
    expect(boundary.requests.filter(r => r.method === 'POST')).toEqual([])
    expect(boundary.unhandled).toEqual([])
  })

  test(`COMMON-${width}-PAGES: 共通nav/bell・paginator・行icon44pxと通常操作`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    await page.goto('/admin/receipts')
    await waitForHydration(page)
    await expect(page.locator('.p-datatable')).toBeVisible()
    const hamburger = page.locator('header button:visible').first()
    await targetBox(hamburger)
    await hamburger.click()
    await expect(page.locator('.p-drawer')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.locator('.p-drawer')).not.toBeVisible()
    const bell = page.locator('header button:has(.pi-bell)')
    await targetBox(bell)
    await bell.click()
    await expect(page.locator('.p-popover')).toBeVisible()
    await page.keyboard.press('Escape')
    for (const target of await page.locator('.p-paginator button:visible').all()) await targetBox(target)
    for (const target of await page.locator('.p-datatable-tbody button:visible').all()) await targetBox(target)
    const first = page.locator('.p-paginator-first')
    await expect(first).toBeDisabled()
    await page.locator('.p-paginator-next').click()
    await expect.poll(() => boundary.requests.some(r => r.path === '/api/v1/admin/receipts' && new URLSearchParams(r.search).get('page') === '1')).toBe(true)
    await page.locator('.p-paginator-last').click()
    await expect.poll(() => boundary.requests.some(r => r.path === '/api/v1/admin/receipts' && new URLSearchParams(r.search).get('page') === '5')).toBe(true)
    await expect(page.locator('.p-paginator-last')).toBeDisabled()
    await expect(page.locator('.p-paginator-next')).toBeDisabled()
    await page.locator('.p-paginator-prev').click()
    await expect.poll(() => boundary.requests.some(r => r.path === '/api/v1/admin/receipts' && new URLSearchParams(r.search).get('page') === '4')).toBe(true)
    await page.locator('.p-paginator-first').click()
    await expect(first).toBeDisabled()
    await expect(page.locator('.p-paginator-prev')).toBeDisabled()
    await noPagePan(page)
    expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
    expect(boundary.unhandled).toEqual([])
  })

  test(`COMMON-${width}-SWITCH: 実input四隅・slider中央・checked/Space/focus`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    await page.goto('/admin/receipt-settings')
    await waitForHydration(page)
    const input = page.locator('#isQualifiedInvoicer')
    const box = await targetBox(input)
    const slider = input.locator('..').locator('.p-toggleswitch-slider')
    const visual = await slider.boundingBox()
    expect(visual!.width).toBe(40)
    expect(visual!.height).toBe(24)
    expect(Math.abs(visual!.x + 20 - box.x - box.width / 2)).toBeLessThanOrEqual(0.5)
    expect(Math.abs(visual!.y + 12 - box.y - box.height / 2)).toBeLessThanOrEqual(0.5)
    let checked = false
    for (const position of [{ x: 1, y: 1 }, { x: box.width - 1, y: 1 }, { x: 1, y: box.height - 1 }, { x: box.width - 1, y: box.height - 1 }]) {
      await input.click({ position })
      checked = !checked
      await expect(input).toBeChecked({ checked })
      await slider.locator('.p-toggleswitch-handle').evaluate(async el => { await Promise.all(el.getAnimations().map(animation => animation.finished)) })
      const handle = await slider.locator('.p-toggleswitch-handle').boundingBox()
      expect(handle!.x).toBeGreaterThanOrEqual(visual!.x)
      expect(handle!.x + handle!.width).toBeLessThanOrEqual(visual!.x + visual!.width)
      expect(handle!.x - visual!.x).toBe(checked ? desktopBaselines[0]!.checkedHandleOffset : desktopBaselines[0]!.uncheckedHandleOffset)
    }
    await input.focus()
    await page.keyboard.press('Space')
    await expect(input).toBeChecked()
    await expect(input).toBeFocused()
    expect(await slider.evaluate(el => getComputedStyle(el).outlineStyle)).not.toBe('none')
    await noPagePan(page)
    expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
    expect(boundary.unhandled).toEqual([])
  })

  test(`COMMON-${width}-DISABLED: disabled実switch44pxで操作要求0`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    await page.goto('/settings/notifications')
    await waitForHydration(page)
    const input = page.locator('input[role="switch"]:disabled')
    const box = await targetBox(input)
    await expect(input).toBeChecked()
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
    await expect(input).toBeChecked()
    await noPagePan(page)
    expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
    expect(boundary.unhandled).toEqual([])
  })

  test(`COMMON-${width}-ACCOUNT: 設定のsmall/追加取得/削除入口44pxと通常遷移`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    await page.goto('/settings/account')
    await waitForHydration(page)
    const more = page.getByRole('button', { name: 'もっと読む', exact: true })
    const detail = page.getByTestId('gcal-detail-link')
    for (const target of [more, detail, page.getByRole('button', { name: 'アカウントを削除', exact: true })]) await targetBox(target)
    await more.click()
    await expect.poll(() => boundary.requests.some(r => r.path === '/api/v1/users/me/login-history' && new URLSearchParams(r.search).get('cursor') === 'fixture-next')).toBe(true)
    await noPagePan(page)
    expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
    expect(boundary.unhandled).toEqual([])
    await detail.click()
    await expect(page).toHaveURL(/\/settings\/calendar-sync$/)
  })
}

test('COMMON-EMPTY: 0件paginatorでもdisabled実buttonの44pxを維持する', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 844 })
  const boundary = await stubBoundary(page, true, true)
  await page.goto('/admin/receipts')
  await waitForHydration(page)
  await expect(page.locator('.p-paginator-first')).toBeDisabled()
  for (const target of await page.locator('.p-paginator button:visible').all()) await targetBox(target)
  await noPagePan(page)
  expect(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
  expect(boundary.unhandled).toEqual([])
})

for (const width of [767, 768, 1280]) {
  test(`COMMON-BOUNDARY-${width}: mobile境界とdesktopの既存入力密度`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    await stubBoundary(page, false)
    await page.goto('/login')
    await waitForHydration(page)
    const box = await page.locator('#email').boundingBox()
    expect(box).not.toBeNull()
    if (width <= 767) expect(box!.height).toBeGreaterThanOrEqual(44)
    else {
      const baseline = desktopBaselines.find(item => item.viewport === width)!
      expect(box!.height).toBe(baseline.targets.input.height)
      expect((await page.locator('[data-login-submit]').boundingBox())!.height).toBe(baseline.targets.submit.height)
    }
    await noPagePan(page)
  })
}

for (const width of [768, 1280]) {
  test(`COMMON-DESKTOP-${width}: 共通controlの変更前密度を維持する`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const boundary = await stubBoundary(page)
    const baseline = desktopBaselines.find(item => item.viewport === width)!
    const sizes: Record<string, { width?: number, height: number }> = {}
    const measure = async (name: string, target: Locator) => {
      await expect(target).toBeVisible()
      const box = await target.boundingBox()
      sizes[name] = name === 'input' || name === 'submit' ? { height: box!.height } : { width: box!.width, height: box!.height }
    }
    await page.goto('/admin/receipts')
    await waitForHydration(page)
    await measure('paginator', page.locator('.p-paginator-page').first())
    await measure('rowIcon', page.locator('.p-datatable-tbody button:has(.pi-envelope)'))
    await page.getByRole('button', { name: '発行者設定', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/receipt-settings$/)
    await expect(page.locator('#isQualifiedInvoicer')).toBeVisible()
    await measure('switch', page.locator('#isQualifiedInvoicer'))
    const switchInput = page.locator('#isQualifiedInvoicer')
    const slider = switchInput.locator('..').locator('.p-toggleswitch-slider')
    const handle = slider.locator('.p-toggleswitch-handle')
    await measure('slider', slider)
    await measure('handle', handle)
    const handleOffset = async () => (await handle.boundingBox())!.x - (await slider.boundingBox())!.x
    const uncheckedHandleOffset = await handleOffset()
    await switchInput.click()
    await expect(switchInput).toBeChecked()
    await handle.evaluate(async el => { await Promise.all(el.getAnimations().map(animation => animation.finished)) })
    const checkedHandleOffset = await handleOffset()
    expect(checkedHandleOffset).toBeGreaterThan(uncheckedHandleOffset)
    for (const [name, size] of Object.entries(sizes)) expect(size).toEqual(baseline.targets[name as keyof typeof baseline.targets])
    expect(uncheckedHandleOffset).toBe(baseline.uncheckedHandleOffset)
    expect(checkedHandleOffset).toBe(baseline.checkedHandleOffset)
    expect(boundary.unhandled).toEqual([])
  })
}

test('COMMON-LOADING: login処理中/失敗後も44px・二重送信0', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 844 })
  const boundary = await stubBoundary(page, false)
  // 意図した503の報告先も外部境界で受け、未知APIの500拒否は維持する。
  await page.route('**/api/v1/error-reports', route => route.fulfill({ status: 201, json: {} }))
  let count = 0
  let release: () => void = () => {}
  const pending = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/api/v1/auth/login', async (route) => {
    count++
    await pending
    await route.fulfill({ status: 503, json: { error: { code: 'COMMON_999', message: 'Fixture unavailable' } } })
  })
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('#email').fill('ui-fixture@example.invalid')
  await page.locator('#password').fill('UiFixturePassword1!')
  const submit = page.locator('[data-login-submit]')
  try {
    await submit.click()
    await expect(submit).toBeDisabled()
    await expect(submit).toHaveAttribute('aria-busy', 'true')
    await targetBox(submit)
    await page.locator('#password').press('Enter')
    await expect.poll(() => count).toBe(1)
  } finally {
    release()
  }
  await expect(submit).toBeEnabled()
  await targetBox(submit)
  expect(count).toBe(1)
  expect(boundary.unhandled).toEqual([])
})

for (const locale of ['en', 'de']) for (const width of [360, 390]) {
test(`COMMON-LONG-${locale}-${width}: 長い翻訳と操作が重ならず切れず横パンしない`, async ({ page, baseURL }) => {
  await page.setViewportSize({ width, height: 844 })
  await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: baseURL! }])
  const boundary = await stubBoundary(page)
  await page.goto('/admin/receipts')
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: locale === 'en' ? 'Receipt Management' : 'Quittungsverwaltung', exact: true })).toBeVisible()
  await expect(page.locator('.p-datatable')).toBeVisible()
  const heading = page.locator('h1')
  const actions = heading.locator('../../..').locator(':scope > div.flex > a > button, :scope > div.flex > button')
  await expect(actions).toHaveCount(2)
  for (const target of await actions.all()) await targetBox(target)
  const headerBoxes = await heading.locator('../../..').locator('h1, :scope > div.flex > a > button, :scope > div.flex > button').evaluateAll(elements => elements.map(el => {
    const box = el.getBoundingClientRect()
    const label = el.querySelector('.p-button-label') ?? el
    const text = label.getBoundingClientRect()
    return { x: box.x, y: box.y, right: box.right, bottom: box.bottom, labelInside: text.left >= box.left && text.right <= box.right && text.top >= box.top && text.bottom <= box.bottom, unclipped: label.scrollWidth <= label.clientWidth }
  }))
  for (const [index, box] of headerBoxes.entries()) {
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.right).toBeLessThanOrEqual(width)
    expect(box.labelInside).toBe(true)
    expect(box.unclipped).toBe(true)
    for (const other of headerBoxes.slice(index + 1)) expect(Math.min(box.right, other.right) - Math.max(box.x, other.x) > 0.5 && Math.min(box.bottom, other.bottom) - Math.max(box.y, other.y) > 0.5).toBe(false)
  }
  for (const target of await page.locator('.p-paginator button:visible, .p-datatable-tbody button:visible').all()) await targetBox(target)
  for (const group of [page.locator('.p-paginator button:visible'), page.locator('.p-datatable-tbody button:visible')]) {
    const boxes = await group.evaluateAll(elements => elements.map(el => {
      const box = el.getBoundingClientRect()
      const label = el.querySelector('.p-button-label')
      const text = label?.getBoundingClientRect()
      return { x: box.x, y: box.y, right: box.right, bottom: box.bottom, labelInside: !text || (text.left >= box.left && text.right <= box.right && text.top >= box.top && text.bottom <= box.bottom), unclipped: !label || label.scrollWidth <= label.clientWidth }
    }))
    for (const [index, box] of boxes.entries()) {
      expect(box.labelInside).toBe(true)
      expect(box.unclipped).toBe(true)
      for (const other of boxes.slice(index + 1)) expect(Math.min(box.right, other.right) - Math.max(box.x, other.x) > 0.5 && Math.min(box.bottom, other.bottom) - Math.max(box.y, other.y) > 0.5).toBe(false)
    }
  }
  await noPagePan(page)
  expect(boundary.unhandled).toEqual([])
})
}
