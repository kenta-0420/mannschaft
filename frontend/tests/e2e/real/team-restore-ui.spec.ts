import { readFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page, type Locator } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

type Team = {
  id: number
  slug: string
  scheduleId?: number
  scheduleTitle?: string
  scheduleStartAt?: string
}
type Fixtures = {
  baseUrl: string
  apiBaseUrl: string
  teams: Record<string, Team>
}

// 前提作成・ログイン以外の対象操作は実UI。モックも復元/予定削除APIの代替呼出しも使わない。
test.describe.configure({ mode: 'serial' })
const fixturePath = process.env.RESTORE_UI_FIXTURES
if (!fixturePath) throw new Error('RESTORE_UI_FIXTURES_REQUIRED')
const fixtures = JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixtures
const privateDir = path.dirname(fixturePath)
const credentialIndex = JSON.parse(
  readFileSync(path.join(privateDir, 'browser-credentials.json'), 'utf8'),
) as Record<string, string>
const deletedPath = '/system-admin/teams/deleted'
const deletedApi = '/api/v1/system-admin/dashboard/teams/deleted'

async function login(page: Page, role: string) {
  const name = credentialIndex[role]
  if (!name || path.basename(name) !== name) throw new Error('OWNED_CREDENTIAL_FILE_REQUIRED')
  try {
    const credentials = JSON.parse(readFileSync(path.join(privateDir, name), 'utf8')) as {
      email: string
      password: string
    }
    await loginViaApi(page, credentials, { apiBaseUrl: fixtures.apiBaseUrl })
  } catch {
    // 既存helperの失敗応答・アカウントをレポートへ残さない。
    throw new Error('OWNED_ROLE_LOGIN_FAILED')
  }
}

async function noHorizontalPan(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
}

async function touchTarget(control: Locator) {
  const box = await control.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.width).toBeGreaterThanOrEqual(44)
  expect(box!.height).toBeGreaterThanOrEqual(44)
}

async function openList(page: Page) {
  const loaded = page.waitForResponse(
    (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
  )
  await page.goto(deletedPath)
  expect((await loaded).status()).toBe(200)
  await waitForHydration(page)
  await expect(page.getByTestId(`restore-${fixtures.teams.explore_public!.slug}`)).toBeVisible()
}

test('未認証の直URLはlogin画面へ戻す', async ({ page }, testInfo) => {
  await page.goto(deletedPath)
  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(page.locator('input#email')).toBeVisible()
  await expect(page.getByRole('button', { name: '復元', exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('unauthenticated-login.png'), fullPage: true })
})

test('管理入口・取消・閉じる・Escapeと360/390pxの操作性', async ({ page }, testInfo) => {
  await login(page, 'sys_test')
  await page.goto('/system-admin')
  await waitForHydration(page)
  await page.getByRole('link', { name: '削除済みチームの復元' }).click()
  await expect(page).toHaveURL(new RegExp(`${deletedPath}$`))
  const restore = page.getByTestId(`restore-${fixtures.teams.restore_public!.slug}`)
  await expect(restore).toBeVisible()
  let patches = 0
  page.on('request', (r) => {
    if (r.method() === 'PATCH' && new URL(r.url()).pathname.endsWith('/restore')) patches++
  })
  for (const width of [360, 390]) {
    await page.setViewportSize({ width, height: 844 })
    await noHorizontalPan(page)
    await touchTarget(restore)
    for (const action of ['cancel', 'close', 'escape']) {
      await restore.click()
      const dialog = page.getByRole('alertdialog')
      await expect(dialog).toContainText(fixtures.teams.restore_public!.slug)
      await noHorizontalPan(page)
      const cancel = dialog.getByRole('button', { name: 'キャンセル', exact: true })
      await touchTarget(cancel)
      await touchTarget(dialog.getByRole('button', { name: '復元', exact: true }))
      const close = dialog.locator('button[aria-label]').first()
      await touchTarget(close)
      if (action === 'cancel')
        await page.screenshot({ path: testInfo.outputPath(`confirm-${width}.png`), fullPage: true })
      if (action === 'cancel') {
        await cancel.focus()
        await page.keyboard.press('Enter')
      } else if (action === 'close') await close.click()
      else await page.keyboard.press('Escape')
      await expect(dialog).toHaveCount(0)
      expect(patches).toBe(0)
    }
  }
})

for (const [key, width] of [
  ['restore_public', 1280],
  ['restore_private', 390],
] as const) {
  test(`${key}: 実復元後に予定UIで削除し元利用者calendarから消える`, async ({
    page,
    browser,
  }, testInfo) => {
    const team = fixtures.teams[key]!
    if (!team.scheduleId || !team.scheduleTitle) throw new Error('OWNED_SCHEDULE_FIXTURE_REQUIRED')
    const formerContext = await browser.newContext({
      baseURL: fixtures.baseUrl,
      locale: 'ja-JP',
      timezoneId: 'Asia/Tokyo',
    })
    const former = await formerContext.newPage()
    try {
      await login(former, 'former_admin')
      await former.goto('/calendar')
      await waitForHydration(former)
      await former.getByTestId('calendar-view-agenda').click()
      await expect(former.getByText(team.scheduleTitle, { exact: true }).first()).toBeVisible()
      await page.setViewportSize({ width, height: 900 })
      await login(page, 'sys_test')
      await openList(page)
      const target = page.getByTestId(`restore-${team.slug}`)
      await target.click()
      const restored = page.waitForResponse(
        (r) =>
          r.request().method() === 'PATCH' &&
          new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/restore`,
      )
      await page.getByRole('alertdialog').getByRole('button', { name: '復元', exact: true }).click()
      expect((await restored).status()).toBe(204)
      await expect(page.getByTestId('restore-success')).toBeVisible()
      await expect(target).toHaveCount(0)
      await page.screenshot({ path: testInfo.outputPath('restored-success.png'), fullPage: true })
      await page.getByTestId('restored-team-link').click()
      await expect(page).toHaveURL(new RegExp(`/teams/${team.slug}$`))
      await openList(page)
      const reloaded = page.waitForResponse(
        (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
      )
      await page.reload()
      expect((await reloaded).status()).toBe(200)
      await expect(page.getByTestId(`restore-${fixtures.teams.explore_public!.slug}`)).toBeVisible()
      await expect(target).toHaveCount(0)
      // 既存予定画面の詳細を開き、表示された削除ボタンを操作する。
      await page.goto(`/teams/${team.slug}/schedule?eventId=${team.scheduleId}`)
      await waitForHydration(page)
      const editButton = page.locator('[data-testid="schedule-edit"]:visible')
      await expect(editButton.locator('..').locator('..')).toContainText(team.scheduleTitle)
      const deleteButton = editButton.locator('..').locator('button:has(.pi-trash)')
      await expect(deleteButton).toBeVisible()
      page.once('dialog', (dialog) => dialog.accept())
      const removed = page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/schedules/${team.scheduleId}`,
      )
      await deleteButton.click()
      expect((await removed).status()).toBe(204)
      const calendarLoaded = former.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/my/calendar' && r.request().method() === 'GET',
      )
      await former.reload()
      expect((await calendarLoaded).status()).toBe(200)
      const controlTitle = fixtures.teams.alive_control!.scheduleTitle
      if (!controlTitle) throw new Error('OWNED_CALENDAR_CONTROL_REQUIRED')
      await expect(former.getByText(controlTitle, { exact: true }).first()).toBeVisible()
      await expect(former.getByText(team.scheduleTitle, { exact: true })).toHaveCount(0)
      await former.screenshot({
        path: testInfo.outputPath('former-calendar-after-delete.png'),
        fullPage: true,
      })
    } finally {
      await formerContext.close()
    }
  })
}

test('PROVISIONED復元は通常チームへのリンクを提示しない', async ({ page }, testInfo) => {
  await login(page, 'sys_test')
  await openList(page)
  const team = fixtures.teams.restore_provisioned!
  await page.getByTestId(`restore-${team.slug}`).click()
  const restored = page.waitForResponse(
    (r) =>
      r.request().method() === 'PATCH' &&
      new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/restore`,
  )
  await page.getByRole('alertdialog').getByRole('button', { name: '復元', exact: true }).click()
  expect((await restored).status()).toBe(204)
  await expect(page.getByTestId('restore-success')).toContainText('利用開始前')
  await expect(page.getByTestId('restored-team-link')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('provisioned-success.png'), fullPage: true })
  const unavailable = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/v1/teams/${team.slug}`,
  )
  await page.goto(`/teams/${team.slug}`)
  expect((await unavailable).status()).toBe(404)
  await expect(page.getByText('情報を取得できませんでした', { exact: true })).toBeVisible()
})

for (const role of ['former_admin', 'member', 'other_admin']) {
  test(`${role}: 管理入口非表示・直URL拒否`, async ({ page }, testInfo) => {
    await login(page, role)
    let deletedReads = 0
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === deletedApi) deletedReads++
    })
    await page.goto(deletedPath)
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.getByText('管理者権限が必要です', { exact: true })).toBeVisible()
    await expect(page.locator('a[href="/system-admin"]')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '削除済みチームの復元' })).toHaveCount(0)
    await expect(page.getByTestId(`restore-${fixtures.teams.explore_public!.slug}`)).toHaveCount(0)
    expect(deletedReads).toBe(0)
    await page.screenshot({
      path: testInfo.outputPath('non-system-role-denied.png'),
      fullPage: true,
    })
  })
}
