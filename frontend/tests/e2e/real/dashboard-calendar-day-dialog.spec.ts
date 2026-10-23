/** CMP-261024-0528: モックなし。API補助はログイン・前提作成・所有fixtureの削除のみ。 */
import { test, expect, type Locator, type Page } from '@playwright/test'
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
const date = (day: number) => `${MONTH}-${String(day).padStart(2, '0')}`
const RUN = `DAYDIALOG-${Date.now()}`

type Owned = { path: string; id: number }
type ScheduleBody = {
  data: { id: number; content: { title: string }; time: { startAt: string }; reminders?: unknown[] }
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

async function expectInitialDate(page: Page, day: number) {
  const dialog = page.getByRole('dialog').filter({ has: page.getByTestId('schedule-title') })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('#schedule-start-date')).toHaveValue(date(day).replaceAll('-', '/'))
  await expect(dialog.locator('#schedule-end-date')).toHaveValue(date(day).replaceAll('-', '/'))
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
