/** CMP107: recurring schedule update scope through the real calendar UI. */
import { test, expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const V1 = `${API}/api/v1`
const TEAM = 'fc-u-18'
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
type Entry = { id: number; content?: { title?: string }; title?: string; time?: { startAt?: string; endAt?: string }; startAt?: string; endAt?: string }
type Feed = { targetId: number; type: string; detail?: { title?: string; affectedCount?: number } | null }

let api: APIRequestContext
let token = ''
let memberToken = ''
let page: Page
const h = () => ({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' })
const titleOf = (e: Entry) => e.content?.title ?? e.title
const startOf = (e: Entry) => e.time?.startAt ?? e.startAt ?? ''
const endOf = (e: Entry) => e.time?.endAt ?? e.endAt ?? ''
const monthIndex = (date: string | number) => {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Tokyo', year: 'numeric', month: 'numeric' })
    .formatToParts(new Date(date))
  return Number(parts.find(p => p.type === 'year')?.value) * 12
    + Number(parts.find(p => p.type === 'month')?.value)
}

async function createSeries(title: string) {
  const start = Date.now() + 7 * 86_400_000
  const res = await api.post(`${V1}/teams/${TEAM}/schedules`, { headers: h(), data: {
    title, startAt: new Date(start).toISOString(), endAt: new Date(start + 3_600_000).toISOString(),
    allDay: false, eventType: 'PRACTICE', visibility: 'MEMBERS_ONLY', minViewRole: 'ANYONE', attendanceRequired: false,
    recurrenceRule: { type: 'WEEKLY', interval: 1, daysOfWeek: ['MONDAY'], endType: 'COUNT', count: 4 },
  } })
  expect(res.status()).toBe(201)
}

async function teamEntries(): Promise<Entry[]> {
  const from = new Date(Date.now() - 86_400_000).toISOString()
  const to = new Date(Date.now() + 45 * 86_400_000).toISOString()
  const res = await api.get(`${V1}/teams/${TEAM}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { headers: h() })
  expect(res.status()).toBe(200)
  return (await res.json() as { data: Entry[] }).data
}

async function idsFor(title: string) {
  return (await teamEntries()).filter(e => titleOf(e) === title)
    .sort((a, b) => Date.parse(a.time?.startAt ?? a.startAt ?? '') - Date.parse(b.time?.startAt ?? b.startAt ?? ''))
    .map(e => e.id)
}

async function waitFeed(id: number, title: string, count: number) {
  const deadline = Date.now() + 45_000
  while (Date.now() < deadline) {
    const res = await api.get(`${V1}/dashboard/activity?limit=50`, {
      headers: { Authorization: `Bearer ${memberToken}`, 'Content-Type': 'application/json' },
    })
    expect(res.status()).toBe(200)
    const items = (await res.json() as { data: { items: Feed[] } }).data.items
    const row = items.find(i => i.targetId === id && i.type.startsWith('SCHEDULE_') && i.detail?.title === title)
    if (row) {
      expect(row.detail?.affectedCount).toBe(count)
      return
    }
    await new Promise(resolve => setTimeout(resolve, 1_000))
  }
  throw new Error(`activity feed row not found: ${title}`)
}

async function edit(id: number, before: string, after: string, testId: string) {
  const entries = await teamEntries()
  const selected = entries.find(e => e.id === id)
  expect(selected).toBeDefined()
  const targetMonth = monthIndex(startOf(selected!))
  const sameMonth = entries.filter(e => titleOf(e) === before && monthIndex(startOf(e)) === targetMonth)
    .sort((a, b) => Date.parse(startOf(a)) - Date.parse(startOf(b)))
  const ordinal = sameMonth.findIndex(e => e.id === id)
  expect(ordinal).toBeGreaterThanOrEqual(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`/teams/${TEAM}/schedule`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const monthDelta = targetMonth - monthIndex(Date.now())
  expect(monthDelta).toBeGreaterThanOrEqual(0)
  for (let i = 0; i < monthDelta; i++) {
    await page.locator('button').filter({ has: page.locator('.pi-chevron-right') }).first().click()
  }
  await page.getByTestId('schedule-list-row-wrap').filter({ hasText: before }).nth(ordinal).click()
  const pencil = page.locator('button').filter({ has: page.locator('.pi-pencil') })
    .and(page.locator(':visible')).first()
  await expect(pencil).toBeVisible({ timeout: 30_000 })
  await pencil.click()
  const dialog = page.locator('[role="dialog"]').last()
  const input = dialog.locator('input').first()
  await expect(input).toHaveValue(before)
  await input.fill(after)
  await dialog.getByTestId('schedule-submit').click()
  const scope = page.getByTestId('recurrence-update-scope-dialog')
  await expect(scope).toBeVisible()
  await expect(scope.getByTestId('recurrence-update-this')).toBeVisible()
  await expect(scope.getByTestId('recurrence-update-following')).toBeVisible()
  await expect(scope).not.toContainText('ALL')
  const [updateResponse] = await Promise.all([
    page.waitForResponse(response => response.request().method() === 'PATCH'
      && response.url().includes(`/schedules/${id}`)),
    scope.getByTestId(testId).click(),
  ])
  expect(updateResponse.status(), await updateResponse.text()).toBe(200)
  await expect(scope).toBeHidden()
}

test.describe('CMP107 recurring edit scope (real UI)', () => {
  test.setTimeout(120_000)
  test.beforeAll(async ({ browser }) => {
    api = await pwRequest.newContext()
    const res = await api.post(`${V1}/auth/login`, { data: ADMIN })
    expect(res.status()).toBe(200)
    token = (await res.json() as { data: { accessToken: string } }).data.accessToken
    const memberLogin = await api.post(`${V1}/auth/login`, { data: { email: 'e2e-user@test.mannschaft.local', password: ADMIN.password } })
    expect(memberLogin.status()).toBe(200)
    memberToken = (await memberLogin.json() as { data: { accessToken: string } }).data.accessToken
    page = await (await browser.newContext()).newPage()
    await loginViaApi(page, ADMIN, { apiBaseUrl: API })
  })
  test.afterAll(async () => { await page?.context().close(); await api?.dispose() })

  test('THIS_ONLY updates selected occurrence only', async () => {
    const before = `CMP107-only-${Date.now()}`
    const after = `${before}-changed`
    await createSeries(before)
    const ids = await idsFor(before)
    expect(ids.length).toBeGreaterThan(3)
    await edit(ids[1]!, before, after, 'recurrence-update-this')
    const entries = await teamEntries()
    expect(entries.filter(e => titleOf(e) === after)).toHaveLength(1)
    expect(entries.filter(e => titleOf(e) === before)).toHaveLength(ids.length - 1)
    await waitFeed(ids[1]!, after, 1)
  })

  test('THIS_AND_FOLLOWING updates selected and future, not prior', async () => {
    const before = `CMP107-following-${Date.now()}`
    const after = `${before}-changed`
    await createSeries(before)
    const ids = await idsFor(before)
    expect(ids.length).toBeGreaterThan(3)
    await edit(ids[1]!, before, after, 'recurrence-update-following')
    const entries = await teamEntries()
    expect(entries.filter(e => titleOf(e) === after)).toHaveLength(ids.length - 1)
    expect(entries.filter(e => titleOf(e) === before)).toHaveLength(1)
    await waitFeed(ids[1]!, after, ids.length - 1)
  })

  test('THIS_AND_FOLLOWING shifts each future occurrence by its own original time', async () => {
    const title = `CMP107-time-${Date.now()}`
    await createSeries(title)
    const ids = await idsFor(title)
    expect(ids.length).toBeGreaterThan(3)
    const original = new Map((await teamEntries()).filter(e => ids.includes(e.id)).map(e => [e.id, e]))
    const selected = original.get(ids[1]!)!
    const shiftMs = 30 * 60_000
    const response = await api.patch(`${V1}/teams/${TEAM}/schedules/${ids[1]}?updateScope=THIS_AND_FOLLOWING`, {
      headers: h(), data: {
        startAt: new Date(Date.parse(startOf(selected)) + shiftMs).toISOString(),
        endAt: new Date(Date.parse(endOf(selected)) + shiftMs).toISOString(),
      },
    })
    expect(response.status(), await response.text()).toBe(200)
    const updated = new Map((await teamEntries()).filter(e => ids.includes(e.id)).map(e => [e.id, e]))
    for (const [index, id] of ids.entries()) {
      const before = original.get(id)!
      const after = updated.get(id)!
      const expectedShift = index === 0 ? 0 : shiftMs
      expect(Date.parse(startOf(after)) - Date.parse(startOf(before))).toBe(expectedShift)
      expect(Date.parse(endOf(after)) - Date.parse(endOf(before))).toBe(expectedShift)
    }
  })

  test('5-minute aggregation keeps the latest scope count and title', async () => {
    const before = `CMP107-aggregate-${Date.now()}`
    const first = `${before}-first`
    const latest = `${before}-latest`
    await createSeries(before)
    const ids = await idsFor(before)
    expect(ids.length).toBeGreaterThan(3)
    const selected = ids[1]!

    await edit(selected, before, first, 'recurrence-update-this')
    await waitFeed(selected, first, 1)
    await edit(selected, first, latest, 'recurrence-update-following')

    const entries = await teamEntries()
    expect(entries.filter(e => titleOf(e) === latest)).toHaveLength(ids.length - 1)
    expect(entries.filter(e => titleOf(e) === before)).toHaveLength(1)
    await waitFeed(selected, latest, ids.length - 1)

    const feedResponse = await api.get(`${V1}/dashboard/activity?limit=50`, {
      headers: { Authorization: `Bearer ${memberToken}`, 'Content-Type': 'application/json' },
    })
    const rows = (await feedResponse.json() as { data: { items: Feed[] } }).data.items
    expect(rows.filter(row => row.targetId === selected && row.type.startsWith('SCHEDULE_') && row.detail?.title === latest)).toHaveLength(1)
    expect(rows.filter(row => row.targetId === selected && row.type.startsWith('SCHEDULE_') && row.detail?.title === first)).toHaveLength(0)
  })

  test('ALICE: independent admin, member and outsider contexts preserve edit authorization', async ({ browser }) => {
    test.setTimeout(240_000)
    const credentials = [ADMIN, { email: 'e2e-user@test.mannschaft.local', password: ADMIN.password },
      { email: 'e2e-outsider@test.mannschaft.local', password: ADMIN.password }]
    const contexts = await Promise.all(credentials.map(() => browser.newContext()))
    const pages = await Promise.all(contexts.map(context => context.newPage()))
    try {
      await Promise.all(pages.map((personaPage, index) => loginViaApi(personaPage, credentials[index]!, { apiBaseUrl: API })))
      const title = `CMP107-alice-${Date.now()}`
      await createSeries(title)
      const ids = await idsFor(title)
      expect(ids.length).toBeGreaterThan(3)
      const url = `${V1}/teams/${TEAM}/schedules/${ids[1]}`

      const [adminDetail, memberDetail, outsiderDetail] = await Promise.all(
        pages.map(personaPage => personaPage.request.get(url)))
      expect(adminDetail.status()).toBe(200)
      expect(memberDetail.status()).toBe(200)
      expect(outsiderDetail.status()).toBeGreaterThanOrEqual(400)
      expect(outsiderDetail.status()).toBeLessThan(500)
      const adminData = (await adminDetail.json() as { data: {
        content: { title: string }; recurrenceInfo: { parentScheduleId: number | null }
      } }).data
      expect(adminData.content.title).toBe(title)
      expect(adminData.recurrenceInfo.parentScheduleId).not.toBeNull()

      const attackTitle = `${title}-unauthorized`
      const [memberAttack, outsiderAttack] = await Promise.all([
        pages[1]!.request.patch(`${url}?updateScope=ALL`, { data: { title: attackTitle } }),
        pages[2]!.request.patch(`${url}?updateScope=THIS_AND_FOLLOWING`, { data: { title: attackTitle } }),
      ])
      for (const attack of [memberAttack, outsiderAttack]) {
        expect(attack.status()).toBeGreaterThanOrEqual(400)
        expect(attack.status()).toBeLessThan(500)
      }
      expect(await idsFor(title)).toHaveLength(ids.length)
      expect(await idsFor(attackTitle)).toHaveLength(0)
    } finally {
      await Promise.all(contexts.map(context => context.close()))
    }
  })
})
