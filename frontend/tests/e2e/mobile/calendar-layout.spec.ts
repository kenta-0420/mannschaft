import { expect, test, type Page } from '@playwright/test'
import { waitForHydration as waitForNuxtHydration } from '../helpers/wait'

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
      '/api/v1/me/schedules': [], '/api/v1/my/calendar': [], '/api/v1/todos/my/calendar': [],
      '/api/v1/me/calendar-layers': [
        { scopeType: 'PERSONAL', scopeId: 0, scopeName: '', scopeNameKey: 'schedule.calendar.layer.personal', scopeIconUrl: null, color: '#94a3b8', colorSource: 'LAYER_AUTO', hidden: false },
        { scopeType: 'TEAM', scopeId: 1, scopeName: 'UI Fixture', scopeNameKey: null, scopeIconUrl: null, color: '#2563eb', colorSource: 'LAYER_AUTO', hidden: false },
      ],
      '/api/v1/settings/nav': { features: [] },
      '/api/v1/feature-flags': [{ flagKey: 'FEATURE_WEBHOOK_SYNC_ENABLED', enabled: true }],
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
      '/api/v1/teams/ui-fixture': { id: 'ui-fixture', slug: 'ui-fixture', numericId: 1, basicInfo: { name: 'UI Team Fixture' }, location: { template: 'OTHER' }, visibility: { visibility: 'MEMBERS_AND_ABOVE', supporterEnabled: false }, metadata: { memberCount: 1 }, social: { supporterCount: 0 }, timezone: 'Asia/Tokyo' },
      '/api/v1/organizations/ui-fixture': { id: 'ui-fixture', slug: 'ui-fixture', numericId: 1, basicInfo: { name: 'UI Organization Fixture' }, visibility: { visibility: 'PRIVATE', supporterEnabled: false }, metadata: { memberCount: 1 }, social: { supporterCount: 0 } },
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

for (const locale of ['ja', 'de']) for (const width of [360, 390, 768, 1280]) {
  test(`COMMON-CALENDAR-${locale}-${width}: 見出しと操作ラベルが収まりmobileタブ44px・desktop密度を維持`, async ({ page, baseURL }) => {
    await page.setViewportSize({ width, height: 844 })
    await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: baseURL! }])
    const boundary = await stubBoundary(page)
    await page.goto('/calendar')
    await waitForHydration(page)
    const heading = page.locator('h1')
    await expect(heading).toHaveText(locale === 'ja' ? 'マイカレンダー' : 'Mein Kalender')
    const header = heading.locator('..')
    await expect(header.locator('.p-select')).toBeVisible()
    await page.evaluate(() => document.fonts.ready)
    const tabs = page.getByRole('button', { name: locale === 'ja' ? /カレンダー$/ : /Kalender$/ }).locator('..').locator(':scope > button')
    await expect(tabs).toHaveCount(2)
    const measurements = await header.locator('h1, button, .p-select').evaluateAll(elements => elements.map(el => {
      const rect = el.getBoundingClientRect()
      const label = el.querySelector('.p-button-label, .p-select-label') ?? el
      const range = document.createRange()
      range.selectNodeContents(label)
      const text = range.getBoundingClientRect()
      return { tag: el.tagName, label: label.textContent?.trim(), x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height, lineHeight: parseFloat(getComputedStyle(label).lineHeight), unclipped: label.scrollWidth <= label.clientWidth, labelInside: el.tagName === 'H1' ? text.left >= 0 && text.right <= innerWidth : text.left >= rect.left && text.right <= rect.right && text.top >= rect.top && text.bottom <= rect.bottom }
    }))
    const tabBoxes = await tabs.evaluateAll(elements => elements.map(el => {
      const rect = el.getBoundingClientRect()
      return { label: el.textContent?.trim(), x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height, unclipped: el.scrollWidth <= el.clientWidth }
    }))
    await test.info().attach('calendar-measurements.json', { body: JSON.stringify({ locale, width, header: measurements, tabs: tabBoxes }, null, 2), contentType: 'application/json' })
    await test.info().attach('calendar-header.png', { body: await page.screenshot(), contentType: 'image/png' })
    expect.soft(boundary.unhandled).toEqual([])
    expect.soft(boundary.requests.filter(r => r.method !== 'GET')).toEqual([])
    expect.soft(measurements[0]!.height).toBeLessThanOrEqual(measurements[0]!.lineHeight * (width < 768 ? 2 : 1))
    for (const [index, box] of measurements.entries()) {
      expect.soft(box.x).toBeGreaterThanOrEqual(0)
      expect.soft(box.right).toBeLessThanOrEqual(width)
      expect.soft(box.unclipped).toBe(true)
      expect.soft(box.labelInside).toBe(true)
      // mainのheader操作・Selectは意図的に44px。幅は固定せず、bboxはdelta前後で別比較する。
      if (width === 1280) {
        if (index === 0) expect.soft(box.height).toBe(36)
        else expect.soft(box.height).toBeGreaterThanOrEqual(44)
      }
      for (const other of measurements.slice(index + 1)) expect.soft(Math.min(box.right, other.right) - Math.max(box.x, other.x) > 0.5 && Math.min(box.bottom, other.bottom) - Math.max(box.y, other.y) > 0.5).toBe(false)
    }
    for (const box of tabBoxes) {
      if (width < 768) expect.soft(box.height).toBeGreaterThanOrEqual(44)
      else expect.soft(box.height).toBe(32)
      expect.soft(box.x).toBeGreaterThanOrEqual(0)
      expect.soft(box.right).toBeLessThanOrEqual(width)
      expect.soft(box.unclipped).toBe(true)
    }
    await noPagePan(page)
  })
}
