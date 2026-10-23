/** CMP-261024-0528: モックなし。API補助はログイン・前提作成・所有fixtureの削除のみ。 */
import { test, expect, type Locator, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const PASSWORD = 'TestPass2026!'
const USER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const ADMIN = { email: 'e2e-dummy-1@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }
const TEAM = process.env.E2E_CALENDAR_TEAM ?? 'fc-u-18'
const ORG = process.env.E2E_CALENDAR_ORG ?? 's-c6151c64'
const MONTH = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit',
}).format(new Date()).replace('/', '-')
const date = (day: number, month = MONTH) => `${month}-${String(day).padStart(2, '0')}`
const RUN = `DAYDIALOG-${Date.now()}`

type Owned = { path: string; id: number }
type ScheduleBody = {
  data: { id: number; content: { title: string }; time: { startAt: string; endAt: string }; reminders?: unknown[] }
}

// setupプロジェクトの認証状態を継承せず、各caseが単一セッションを作る。
test.use({ storageState: { cookies: [], origins: [] }, timezoneId: 'America/Los_Angeles' })
test.describe.configure({ mode: 'default', timeout: 120_000 })

const browserErrors = new WeakMap<Page, string[]>()
test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  browserErrors.set(page, errors)
  page.on('pageerror', error => errors.push(`pageerror: ${error.message}`))
  page.on('console', message => {
    if (message.type() === 'error') errors.push(`console: ${message.text()}`)
  })
  page.on('response', response => {
    if (response.status() >= 400) errors.push(`HTTP ${response.status()}: ${new URL(response.url()).pathname}`)
  })
})
test.afterEach(async ({ page }, testInfo) => {
  const errors = browserErrors.get(page) ?? []
  if (errors.length) await testInfo.attach('browser-errors', { body: errors.join('\n'), contentType: 'text/plain' })
})

async function signIn(page: Page, credentials = USER) {
  await loginViaApi(page, credentials, { apiBaseUrl: API })
}

async function openPersonal(page: Page): Promise<Locator> {
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.getByTestId('scope-segment-PERSONAL').click()
  const widget = page.locator('#scope-panel-PERSONAL')
    .getByRole('heading', { name: /マイカレンダー/ }).locator('../..')
  await expect(widget.locator('h2')).toBeVisible({ timeout: 30_000 })
  return widget
}

async function openShared(page: Page, base: string): Promise<Locator> {
  // 初回管理者向け案内は権限を書き換えず、実UIの「あとで決める」で閉じる。
  await page.addLocatorHandler(page.getByTestId('member-permission-setup'), async dialog => {
    await dialog.getByRole('button', { name: 'あとで決める', exact: true }).click()
  })
  await page.goto(`/${base}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const section = page.getByRole('button', { name: /^予定・活動/ })
  await expect(section).toBeVisible()
  if (await section.getAttribute('aria-expanded') !== 'true') await section.click()
  const widget = page.getByTestId('widget-schedule-calendar').filter({ visible: true }).first()
  await expect(widget.locator('h2')).toBeVisible({ timeout: 30_000 })
  return widget
}

async function clickDay(widget: Locator, day: number) {
  // CalendarGridの数字を押し、予定chipのstopPropagationとは別の入口を踏む。
  await widget.locator('div[tabindex="-1"]:not([class*="bg-surface-50/50"])').filter({
    has: widget.page().getByText(String(day), { exact: true }),
  }).locator(':scope > div.inline-flex').click()
}

async function expectInitialDate(page: Page, day: number, month = MONTH) {
  const dialog = page.getByRole('dialog').filter({ has: page.getByTestId('schedule-title') })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('#schedule-start-date')).toHaveValue(date(day, month).replaceAll('-', '/'))
  await expect(dialog.locator('#schedule-end-date')).toHaveValue(date(day, month).replaceAll('-', '/'))
  return dialog
}

async function createFromDialog(page: Page, path: string, title: string, owned: Owned[]) {
  await page.getByTestId('schedule-title').fill(title)
  const saved = page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/v1/${path}` && response.request().method() === 'POST')
  await page.getByTestId('schedule-submit').click()
  const response = await saved
  expect(response.status(), `${title}を実BEへ保存`).toBe(201)
  const body = await response.json() as ScheduleBody
  owned.push({ path, id: body.data.id })
  await expect(page.getByTestId('schedule-title')).not.toBeVisible()
  return body.data
}

async function cleanup(page: Page, owned: Owned[]) {
  for (const item of [...owned].reverse()) {
    const response = await page.request.delete(`${API}/api/v1/${item.path}/${item.id}`)
    expect([204, 404], `自case所有の予定${item.id}だけを削除`).toContain(response.status())
  }
}

async function dayList(page: Page, title: string) {
  const content = page.getByTestId('schedule-day-dialog')
  await expect(content.getByTestId('schedule-list-row').filter({ hasText: title })).toBeVisible()
  await expect(page.getByTestId('schedule-day-create')).toBeVisible()
  return content
}

test('DAY-001: 個人の空日→日付付き保存→一覧→既存編集→同日新規と別日再表示（390px）', async ({ page }, testInfo) => {
  const owned: Owned[] = []
  await signIn(page)
  try {
    const widget = await openPersonal(page)
    await clickDay(widget, 12)
    const form = await expectInitialDate(page, 12)
    await form.getByRole('button', { name: 'リマインダーを追加', exact: true }).click()
    const title = `${RUN} 個人`
    const created = await createFromDialog(page, 'me/schedules', title, owned)
    expect(created.time.startAt.slice(0, 10)).toBe(date(12))
    expect(created.reminders).toEqual([30])
    await expect(widget.getByText(title, { exact: false }).filter({ visible: true }).first()).toBeVisible()

    await clickDay(widget, 12)
    const list = await dayList(page, title)
    await page.screenshot({ path: testInfo.outputPath('personal-day-list-desktop.png') })
    await list.getByTestId('schedule-list-row').filter({ hasText: title }).getByRole('button').click()
    await expect(page.getByRole('dialog', { name: title, exact: true })).toBeVisible()
    await page.getByTestId('schedule-edit').click()
    const edit = await expectInitialDate(page, 12)
    await expect(edit.getByTestId('schedule-title')).toHaveValue(title)
    await expect(edit.getByRole('combobox', { name: '値' })).toContainText('30分前')
    await edit.getByTestId('schedule-title').fill(`${title} 更新`)
    const updated = page.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/v1/me/schedules/${created.id}` && response.request().method() === 'PATCH')
    await edit.getByTestId('schedule-submit').click()
    const updateResponse = await updated
    expect(updateResponse.status()).toBe(200)
    const updatedSchedule = (await updateResponse.json() as ScheduleBody).data
    expect(updatedSchedule.time).toEqual(created.time)
    expect(updatedSchedule.reminders).toEqual(created.reminders)
    await expect(widget.getByText(`${title} 更新`, { exact: false }).filter({ visible: true }).first()).toBeVisible()

    await page.setViewportSize({ width: 390, height: 844 })
    await clickDay(widget, 12)
    await dayList(page, `${title} 更新`)
    const listBox = await page.getByRole('dialog').boundingBox()
    expect(listBox).not.toBeNull()
    expect(listBox!.width).toBeLessThanOrEqual(390)
    await page.screenshot({ path: testInfo.outputPath('personal-day-list-mobile.png') })
    await page.getByTestId('schedule-day-create').click()
    await expectInitialDate(page, 12)
    await expect(page.getByTestId('schedule-title')).toHaveValue('')
    await page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
    await clickDay(widget, 13)
    await expectInitialDate(page, 13)
    await expect(page.getByTestId('schedule-title')).toHaveValue('')
    await page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
  } finally { await cleanup(page, owned) }
})

test('DAY-004: 月末23:30から翌月00:30の個人予定を両月の日別一覧で見つけられる', async ({ page }, testInfo) => {
  const owned: Owned[] = []
  const [year, month] = MONTH.split('-').map(Number)
  // 翌月グリッドのfromが必ず翌月1日になる、日曜始まりの月を選ぶ。
  // 前月末も取得範囲に含まれる月では、旧startAt BETWEENでも偶然通ってしまう。
  let offset = 0
  let next = new Date(Date.UTC(year!, month!, 1))
  for (let distance = 1; next.getUTCDay() !== 0; distance++) {
    const future = new Date(Date.UTC(year!, month! + distance, 1))
    const past = new Date(Date.UTC(year!, month! - distance, 1))
    if (future.getUTCDay() === 0) {
      offset = distance
      next = future
    }
    else if (past.getUTCDay() === 0) {
      offset = -distance
      next = past
    }
  }
  const target = new Date(Date.UTC(year!, month! - 1 + offset, 1))
  const targetMonth = target.toISOString().slice(0, 7)
  const lastDay = new Date(Date.UTC(next.getUTCFullYear(), next.getUTCMonth(), 0)).getUTCDate()
  const nextMonth = next.toISOString().slice(0, 7)
  const nextFirst = `${nextMonth}-01`
  const title = `${RUN} 月跨ぎ`
  await signIn(page)
  try {
    const widget = await openPersonal(page)
    const paths = ['/api/v1/me/schedules', '/api/v1/my/calendar', '/api/v1/todos/my/calendar']
    const moveMonth = async (first: Date, direction: 1 | -1 = 1) => {
      // UTCのDateは暦日の計算専用。ブラウザTZで前日に変換しない。
      const gridStart = new Date(first)
      gridStart.setUTCDate(1 - first.getUTCDay())
      const from = gridStart.toISOString().slice(0, 10)
      const responses = paths.map(path => page.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname === path && url.searchParams.get('from')?.startsWith(from) === true
      }))
      const buttons = widget.locator('h2').locator('..').getByRole('button')
      await (direction === 1 ? buttons.last() : buttons.first()).click()
      const loaded = await Promise.all(responses)
      await Promise.all(loaded.map(response => response.finished()))
      await Promise.all(loaded.map(response => response.json()))
      await expect(widget.locator('h2')).toHaveText(`${first.getUTCFullYear()}年${first.getUTCMonth() + 1}月`)
      return loaded
    }
    const direction = offset < 0 ? -1 : 1
    for (let step = 1; step <= Math.abs(offset); step++) await moveMonth(new Date(Date.UTC(year!, month! - 1 + step * direction, 1)), direction)
    await clickDay(widget, lastDay)
    if (await page.getByTestId('schedule-day-dialog').isVisible()) await page.getByTestId('schedule-day-create').click()
    const form = await expectInitialDate(page, lastDay, targetMonth)
    await form.locator('.p-select').first().click()
    await page.getByRole('option', { name: '23:30', exact: true }).click()
    await expect(form.locator('.p-select').nth(1)).toContainText('00:30')
    await expect(form.locator('#schedule-end-date')).toHaveValue(nextFirst.replaceAll('-', '/'))
    const created = await createFromDialog(page, 'me/schedules', title, owned)
    expect(created.time).toMatchObject({ startAt: `${date(lastDay, targetMonth)}T23:30:00+09:00`, endAt: `${nextFirst}T00:30:00+09:00` })
    await expect(widget.getByText(title, { exact: false }).filter({ visible: true }).first()).toBeVisible()
    await clickDay(widget, lastDay)
    await dayList(page, title)
    await page.screenshot({ path: testInfo.outputPath('month-boundary-start-day.png') })
    await page.keyboard.press('Escape')

    // APIを直接呼ばず、月移動の実UIが発行した3応答を証跡として観測する。
    const evidence = await Promise.all((await moveMonth(next)).map(async response => {
      expect(response.status(), '翌月範囲の取得自体は成功する').toBe(200)
      const body = await response.json() as { data: Array<{ id: number; time?: unknown }> }
      return { url: response.url(), status: response.status(), count: body.data.length,
        matching: body.data.filter(item => item.id === created.id).map(item => ({ id: item.id, time: item.time })) }
    }))
    const evidencePath = testInfo.outputPath('month-boundary-http.json')
    await writeFile(evidencePath, JSON.stringify({ fixture: { currentMonth: MONTH, targetMonth, nextFirst, monthOffset: offset },
      scheduleId: created.id, time: created.time, responses: evidence }, null, 2))
    await testInfo.attach('month-boundary-http', { path: evidencePath, contentType: 'application/json' })
    await expect(widget.locator('h2')).toHaveText(`${next.getUTCFullYear()}年${next.getUTCMonth() + 1}月`)
    await page.screenshot({ path: testInfo.outputPath('month-boundary-next-calendar.png') })
    await clickDay(widget, 1)
    await expect(page.getByTestId('schedule-day-dialog'), '翌月1日も継続中の予定があるため、新規フォームではなく日別一覧を開く').toBeVisible()
    await expect(page.getByRole('dialog', { name: `${nextFirst} の予定`, exact: true })).toBeVisible()
    await dayList(page, title)
    for (const response of evidence.slice(0, 2)) {
      expect(response.matching.map(item => item.id), '個人一覧・統合カレンダーの両応答に継続中の予定が含まれる').toContain(created.id)
    }
    await page.screenshot({ path: testInfo.outputPath('month-boundary-next-day.png') })
  } finally { await cleanup(page, owned) }
})

for (const [scope, slug, day] of [['teams', TEAM, 14], ['organizations', ORG, 15]] as const) {
  test(`DAY-002-${scope}: 管理者が空日から作成し、日別一覧の新規導線と詳細導線を使える`, async ({ page }, testInfo) => {
    const owned: Owned[] = []
    await signIn(page, ADMIN)
    try {
      const base = `${scope}/${slug}`
      const widget = await openShared(page, base)
      await clickDay(widget, day)
      await expectInitialDate(page, day)
      const title = `${RUN} ${scope}`
      const created = await createFromDialog(page, `${base}/schedules`, title, owned)
      expect(created.time.startAt.slice(0, 10)).toBe(date(day))
      await expect(widget.getByText(title, { exact: false }).filter({ visible: true }).first()).toBeVisible()
      await clickDay(widget, day)
      await dayList(page, title)
      await page.screenshot({ path: testInfo.outputPath(`${scope}-day-list.png`) })
      await page.getByTestId('schedule-day-create').click()
      await expectInitialDate(page, day)
      await expect(page.getByTestId('schedule-title')).toHaveValue('')
      await page.getByRole('dialog').getByRole('button', { name: 'キャンセル', exact: true }).click()
      await clickDay(widget, day)
      await page.getByTestId('schedule-day-dialog').getByTestId('schedule-list-row')
        .filter({ hasText: title }).getByRole('button').click()
      await expect(page).toHaveURL(new RegExp(`/${base}/schedule\\?eventId=${created.id}`))
      await expect(page.getByText(title, { exact: false }).filter({ visible: true }).first()).toBeVisible()
    } finally { await cleanup(page, owned) }
  })
}

test('DAY-003: 閲覧メンバーは新規導線なし、非所属者は日別一覧と詳細へ他テナント予定を出さない', async ({ browser }, testInfo) => {
  const admin = await browser.newPage({ storageState: { cookies: [], origins: [] } })
  const member = await browser.newPage({ storageState: { cookies: [], origins: [] } })
  const outsider = await browser.newPage({ storageState: { cookies: [], origins: [] } })
  const owned: Owned[] = []
  const title = `${RUN} 非公開team`
  try {
    await signIn(admin, ADMIN)
    const response = await admin.request.post(`${API}/api/v1/teams/${TEAM}/schedules`, {
      data: { title, startAt: `${date(16)}T10:00:00+09:00`, endAt: `${date(16)}T11:00:00+09:00`,
        allDay: false, eventType: 'OTHER', targetMode: 'ALL_MEMBERS', targetUserIds: [], attendanceRequired: false },
    })
    expect(response.status(), '認可検証用の前提fixture').toBe(201)
    const fixture = (await response.json() as ScheduleBody).data
    owned.push({ path: `teams/${TEAM}/schedules`, id: fixture.id })
    await signIn(member)
    const memberWidget = await openShared(member, `teams/${TEAM}`)
    await clickDay(memberWidget, 16)
    await expect(member.getByTestId('schedule-day-dialog')).toContainText(title)
    await expect(member.getByTestId('schedule-day-create')).toHaveCount(0)
    await member.keyboard.press('Escape')
    await clickDay(memberWidget, 17)
    await expect(member.getByTestId('schedule-day-dialog')).toContainText('この日の予定はありません')
    await expect(member.getByTestId('schedule-day-create')).toHaveCount(0)
    await member.screenshot({ path: testInfo.outputPath('member-empty-no-create.png') })

    await signIn(outsider, OUTSIDER)
    const outsiderWidget = await openPersonal(outsider)
    await expect(outsiderWidget).not.toContainText(title)
    const denied = outsider.waitForResponse(r => r.url().includes(`/teams/${TEAM}`) && [403, 404].includes(r.status()))
    await outsider.goto(`/teams/${TEAM}/schedule?eventId=${fixture.id}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(outsider)
    await denied
    await expect(outsider.getByText(title, { exact: false })).toHaveCount(0)
    await expect(outsider.getByText(/権限がありません|アクセスできません|見つかりません|所属していません|情報を取得できませんでした/).filter({ visible: true }).first()).toBeVisible()
    await outsider.screenshot({ path: testInfo.outputPath('outsider-direct-url-denied.png') })
  } finally {
    await cleanup(admin, owned)
    await Promise.all([admin.close(), member.close(), outsider.close()])
  }
})
