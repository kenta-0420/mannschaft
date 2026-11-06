import { expect, test, type Locator, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 実Nuxt/CSSの操作領域番人。API境界だけfixture化し、実認可・実機の証拠とは分ける。
test.use({ storageState: { cookies: [], origins: [] } })
const actor = { id: 1, email: 'touch-fixture@example.invalid', fullName: 'UI Fixture', systemRole: null, timezone: 'Asia/Tokyo' }
const slots = [
  { slotId: 1, slotDate: '2026-10-10', startTime: '09:00:00', endTime: '12:00:00', teamId: 1, teamName: 'A', scheduleId: 1, scheduleName: 'Aの勤務', positionName: null },
  { slotId: 2, slotDate: '2026-10-10', startTime: '13:00:00', endTime: '17:00:00', teamId: 2, teamName: 'チームB', scheduleId: 2, scheduleName: 'Bの勤務', positionName: null },
]

async function openShift(page: Page, data = slots) {
  await page.clock.setFixedTime(new Date('2026-10-10T03:00:00Z'))
  // 通知のSTOMP接続も外部境界。BEなしでNuxtへ実接続させない。
  await page.routeWebSocket('**/ws/**', socket => socket.close())
  await page.addInitScript((user) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000))
  }, actor)
  const unhandled: string[] = []
  const writes: string[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const fixtures: Record<string, unknown> = {
      '/api/v1/users/me': actor,
      '/api/v1/shifts/my/confirmed-slots': data,
      '/api/v1/me/teams': [], '/api/v1/me/organizations': [],
      '/api/v1/settings/nav': { features: [] },
      '/api/v1/feature-flags': [{ flagKey: 'FEATURE_SHIFT_ENABLED', enabled: true }],
      '/api/v1/dashboard/scope-tabs': { items: [], page: 0, page_size: 20, total_pages: 0, total_count: 0, has_next: false, has_prev: false },
      '/api/v1/settings/appearance': { theme: 'LIGHT', bgColor: '#f3efe0', darkBgColor: '#18181b', seasonalThemeId: null, hideChatPreview: false },
      '/api/v1/notifications/unread-count': { unreadCount: 0 },
      '/api/v1/chat/channels': [], '/api/v1/mentions': [],
      '/api/v1/inbox/summary': { byState: {}, byPriority: {}, bySourceType: {} },
      '/api/v1/active-incidents': { incidents: [] },
    }
    if (request.method() === 'GET' && Object.hasOwn(fixtures, path)) {
      await route.fulfill({ json: { data: fixtures[path] } })
      return
    }
    if (request.method() !== 'GET') writes.push(`${request.method()} ${path}`)
    unhandled.push(`${request.method()} ${path}`)
    await route.fulfill({ status: 500, json: { error: { code: 'TEST_UNHANDLED_BOUNDARY', message: 'Unstubbed external boundary' } } })
  })
  await page.goto('/my/shift')
  await waitForHydration(page)
  await expect(page).toHaveURL(/\/my\/shift$/)
  await expect(page.getByRole('heading', { name: 'マイシフト', exact: true })).toBeVisible()
  await expect(page.locator('.pi-spin')).toHaveCount(0)
  return { unhandled, writes }
}

function dayCell(page: Page, day: string) {
  return page.locator('.grid.grid-cols-7').last().locator(':scope > div').filter({ has: page.getByText(day, { exact: true }) })
}

function closeButton(page: Page) {
  return page.locator('h3').locator('..').getByRole('button')
}

async function targetBox(target: Locator) {
  await expect(target).toBeVisible()
  const box = await target.boundingBox()
  expect(box).not.toBeNull()
  expect.soft(box!.width, '実buttonの幅').toBeGreaterThanOrEqual(44)
  expect.soft(box!.height, '実buttonの高さ').toBeGreaterThanOrEqual(44)
  return box!
}

async function noPagePan(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}

async function clickEdge(target: Locator) {
  await target.evaluate(el => el.scrollIntoView({ block: 'center', inline: 'nearest' }))
  const box = await target.boundingBox()
  expect(box).not.toBeNull()
  // pill/circleの丸角外ではなく、中心軸上の端を実クリックする。
  await target.click({ position: { x: 2, y: box!.height / 2 }, timeout: 10000 })
}

for (const width of [360, 390, 767]) {
  test(`CMP1320-${width}: チップとcloseは44px、端操作とフィルタとscrollを維持`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    const boundary = await openShift(page)
    const all = page.getByRole('button', { name: 'すべてのチーム', exact: true })
    const teamA = page.getByRole('button', { name: 'A', exact: true })
    const teamB = page.getByRole('button', { name: 'チームB', exact: true })
    for (const chip of [all, teamA, teamB]) await targetBox(chip)
    if (width === 360 || width === 390) {
      await test.info().attach(`chips-${width}.png`, { body: await page.screenshot(), contentType: 'image/png' })
    }

    await dayCell(page, '10').click()
    await expect(page.getByText('Aの勤務', { exact: true })).toBeVisible()
    await expect(page.getByText('Bの勤務', { exact: true })).toBeVisible()
    await clickEdge(teamA)
    await expect(page.getByText('Aの勤務', { exact: true })).toBeVisible()
    await expect(page.getByText('Bの勤務', { exact: true })).toHaveCount(0)
    await clickEdge(all)
    await expect(page.getByText('Bの勤務', { exact: true })).toBeVisible()

    const close = closeButton(page)
    await close.scrollIntoViewIfNeeded()
    const box = await targetBox(close)
    await expect(close).toHaveAccessibleName('閉じる')
    if (width === 360 || width === 390) {
      await test.info().attach(`close-${width}.png`, { body: await page.screenshot(), contentType: 'image/png' })
    }
    await noPagePan(page)
    await close.click({ position: { x: box.width - 2, y: box.height / 2 }, timeout: 10000 })
    await expect(page.locator('h3')).toHaveCount(0)
    await dayCell(page, '11').click()
    await expect(page.getByText('この日のシフトはありません', { exact: true })).toBeVisible()
    await targetBox(closeButton(page))
    await closeButton(page).click()

    await page.getByRole('button', { name: '週ビュー', exact: true }).click()
    await page.getByRole('button').filter({ hasText: '10月10日' }).click()
    await teamB.click()
    await expect(page.getByText('Aの勤務', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Bの勤務', { exact: true })).toBeVisible()
    await all.click()
    await expect(page.getByText('Aの勤務', { exact: true })).toBeVisible()
    await noPagePan(page)
    expect(boundary.unhandled).toEqual([])
    expect(boundary.writes).toEqual([])
  })
}

for (const count of [0, 1]) {
  test(`CMP1320-EMPTY-${count}: 0件はフィルタなし、1チームは全チームと1チップ`, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    const boundary = await openShift(page, slots.slice(0, count))
    const all = page.getByRole('button', { name: 'すべてのチーム', exact: true })
    if (count === 0) {
      await expect(all).toHaveCount(0)
      await expect(page.getByText('シフトがありません', { exact: true })).toBeVisible()
    } else {
      await targetBox(all)
      await targetBox(page.getByRole('button', { name: 'A', exact: true }))
    }
    await noPagePan(page)
    expect(boundary.unhandled).toEqual([])
    expect(boundary.writes).toEqual([])
  })
}

for (const width of [768, 1280]) {
  test(`CMP1320-DESKTOP-${width}: 変更前の32px操作密度を維持`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const boundary = await openShift(page)
    const actual: unknown[] = []
    for (const name of ['すべてのチーム', 'A', 'チームB']) {
      const chip = page.getByRole('button', { name, exact: true })
      await expect(chip).toBeVisible()
      expect((await chip.boundingBox())!.height).toBe(32)
      actual.push(await chip.evaluate(el => {
        const box = el.getBoundingClientRect()
        const style = getComputedStyle(el)
        return { text: el.textContent?.trim(), x: box.x, y: box.y, width: box.width, height: box.height, minWidth: style.minWidth, minHeight: style.minHeight, padding: style.padding, fontSize: style.fontSize }
      }))
    }
    await dayCell(page, '10').click()
    const box = await closeButton(page).boundingBox()
    expect(box!.width).toBe(32)
    expect(box!.height).toBe(32)
    actual.push(await closeButton(page).evaluate(el => {
      const box = el.getBoundingClientRect()
      const style = getComputedStyle(el)
      return { x: box.x, y: box.y, width: box.width, height: box.height, minWidth: style.minWidth, minHeight: style.minHeight, padding: style.padding, fontSize: style.fontSize }
    }))
    await test.info().attach(`desktop-${width}.json`, { body: JSON.stringify(actual, null, 2), contentType: 'application/json' })
    expect(boundary.unhandled).toEqual([])
    expect(boundary.writes).toEqual([])
  })
}
