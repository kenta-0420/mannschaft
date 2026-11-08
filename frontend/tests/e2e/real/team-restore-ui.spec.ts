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
      await expect
        .poll(() =>
          dialog.evaluate((element) => {
            const style = getComputedStyle(element)
            return (
              !Array.from(element.classList).some((name) =>
                /-enter-(from|active|to)$/.test(name),
              ) &&
              style.opacity === '1' &&
              (style.transform === 'none' || new DOMMatrixReadOnly(style.transform).isIdentity)
            )
          }),
        )
        .toBe(true)
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
      await expect(
        former.getByText(team.scheduleTitle, { exact: true }).filter({ visible: true }).first(),
      ).toBeVisible()
      await page.setViewportSize({ width, height: 900 })
      await login(page, 'sys_test')
      await page.goto('/system-admin')
      await waitForHydration(page)
      const listedResponse = page.waitForResponse(
        (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
      )
      await page.getByRole('link', { name: '削除済みチームの復元' }).click()
      expect((await listedResponse).status()).toBe(200)
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
      await page.getByTestId('scope-sidebar-toggle').click()
      await page
        .getByRole('dialog')
        .getByRole('button', { name: 'スケジュール', exact: false })
        .click()
      await page.locator(`a[href="/teams/${team.slug}/schedule"]:visible`).click()
      await expect(page).toHaveURL(new RegExp(`/teams/${team.slug}/schedule$`))
      const selected = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/schedules/${team.scheduleId}`,
      )
      await page
        .getByText(team.scheduleTitle, { exact: false })
        .filter({ visible: true })
        .first()
        .click()
      expect((await selected).status()).toBe(200)
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
      await expect(
        former.getByText(controlTitle, { exact: true }).filter({ visible: true }).first(),
      ).toBeVisible()
      await expect(former.getByText(team.scheduleTitle, { exact: true })).toHaveCount(0)
      await former.screenshot({
        path: testInfo.outputPath('former-calendar-after-delete.png'),
        fullPage: true,
      })
      await openList(page)
      const reloaded = page.waitForResponse(
        (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
      )
      await page.reload()
      expect((await reloaded).status()).toBe(200)
      await expect(page.getByTestId(`restore-${fixtures.teams.explore_public!.slug}`)).toBeVisible()
      await expect(target).toHaveCount(0)
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

// 復元済みの所有fixtureを使い、復元・予定削除を繰り返さずに通常メニューの導線を検査する。
test('復元後メニューの360/390px主要操作は44px以上で予定へ進める', async ({ browser }, testInfo) => {
  const team = fixtures.teams.restore_public!
  const measurements: {
    width: number
    control: string
    box: Awaited<ReturnType<Locator['boundingBox']>>
  }[] = []
  async function measureTarget(control: Locator, name: string, width: number) {
    const box = await control.boundingBox()
    measurements.push({ width, control: name, box })
    await touchTarget(control)
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width)
  }
  async function waitForDrawer(drawer: Locator) {
    await expect(drawer).toBeVisible()
    await expect
      .poll(() =>
        drawer.evaluate((element) => {
          const style = getComputedStyle(element)
          return (
            style.opacity === '1' &&
            (style.transform === 'none' || new DOMMatrixReadOnly(style.transform).isIdentity) &&
            !Array.from(element.classList).some((name) => /-enter-(from|active|to)$/.test(name)) &&
            element
              .getAnimations({ subtree: true })
              .every((animation) => animation.playState !== 'running')
          )
        }),
      )
      .toBe(true)
  }
  const context = await browser.newContext({
    baseURL: fixtures.baseUrl,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    isMobile: true,
    hasTouch: true,
    viewport: { width: 360, height: 844 },
  })
  const page = await context.newPage()
  try {
    await login(page, 'sys_explore')
    for (const width of [360, 390]) {
      await page.setViewportSize({ width, height: 844 })
      await page.goto(`/teams/${team.slug}`)
      await waitForHydration(page)
      const toggle = page.getByTestId('scope-sidebar-toggle')
      await expect(toggle).toBeVisible()
      await noHorizontalPan(page)
      await page.screenshot({
        path: testInfo.outputPath(`team-menu-toggle-${width}.png`),
        fullPage: true,
      })
      await measureTarget(toggle, 'menu', width)
      await toggle.tap()
      const drawer = page.getByRole('dialog')
      await waitForDrawer(drawer)
      const close = drawer.locator('button[aria-label]').first()
      await measureTarget(close, 'close', width)
      await close.tap()
      await expect(drawer).toHaveCount(0)

      await toggle.tap()
      await waitForDrawer(drawer)
      const category = drawer.getByRole('button', { name: 'スケジュール', exact: false })
      await expect(category).toBeVisible()
      await measureTarget(category, 'schedule-category', width)
      const schedule = drawer.locator(`a[href="/teams/${team.slug}/schedule"]`)
      // カテゴリ状態はlocalStorageへ残るため、2幅目も実際のリンク可視状態から開閉する。
      if (!(await schedule.isVisible())) await category.tap()
      await expect(schedule).toBeVisible()
      await measureTarget(schedule, 'schedule-link', width)
      await noHorizontalPan(page)
      await page.screenshot({
        path: testInfo.outputPath(`team-menu-open-${width}.png`),
        fullPage: true,
      })
      await schedule.tap()
      await expect(page).toHaveURL(new RegExp(`/teams/${team.slug}/schedule$`))
      await expect(page.getByRole('heading', { name: 'スケジュール', exact: true })).toBeVisible()
      await noHorizontalPan(page)
    }
  } finally {
    await testInfo.attach('touch-target-measurements', {
      body: JSON.stringify(measurements),
      contentType: 'application/json',
    })
    await context.close()
  }
})

// 以下は既存の所有fixtureを読むだけの独立回帰。復元・予定編集・予定削除は行わない。
async function mobileControlBounds(control: Locator, width: number, height: number) {
  await expect(control).toBeVisible()
  await control.scrollIntoViewIfNeeded()
  await expect
    .poll(() =>
      control.evaluate((element) =>
        element
          .getAnimations({ subtree: true })
          .every((animation) => animation.playState !== 'running'),
      ),
    )
    .toBe(true)
  const box = await control.boundingBox()
  await touchTarget(control)
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.y).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(width)
  expect(box!.y + box!.height).toBeLessThanOrEqual(height)
  return box
}

test('復元一覧のページ操作は360/390pxで44px以上', async ({ browser }, testInfo) => {
  const context = await browser.newContext({
    baseURL: fixtures.baseUrl,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    isMobile: true,
    hasTouch: true,
    viewport: { width: 360, height: 844 },
  })
  const page = await context.newPage()
  const measurements: unknown[] = []
  try {
    await login(page, 'sys_explore')
    for (const width of [360, 390]) {
      await page.setViewportSize({ width, height: 844 })
      const listed = page.waitForResponse(
        (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
      )
      await page.goto(deletedPath)
      expect((await listed).status()).toBe(200)
      await waitForHydration(page)
      const firstRestore = page.locator('button[data-testid^="restore-"]').first()
      await expect(firstRestore).toBeVisible()
      const paginator = page.locator('[data-pc-name="paginator"]')
      const current = paginator.locator('button[aria-current="page"]')
      await expect(current).toBeEnabled()
      await current.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath(`paginator-${width}.png`), fullPage: true })
      measurements.push({ width, control: 'current-page', box: await current.boundingBox() })
      await mobileControlBounds(current, width, 844)
      for (const section of ['prev', 'next']) {
        const control = paginator.locator(`button[data-pc-section="${section}"]`)
        await expect(control).toBeDisabled()
        const box = await mobileControlBounds(control, width, 844)
        measurements.push({ width, control: section, box })
      }
      await noHorizontalPan(page)
      const loaded = page.waitForResponse(
        (r) => new URL(r.url()).pathname === deletedApi && r.request().method() === 'GET',
      )
      await current.tap()
      expect((await loaded).status()).toBe(200)
      await expect(paginator.locator('button[aria-current="page"]')).toHaveText('1')
      await expect(firstRestore).toBeVisible()
      await noHorizontalPan(page)
    }
  } finally {
    await testInfo.attach('paginator-target-measurements', {
      body: JSON.stringify(measurements),
      contentType: 'application/json',
    })
    await context.close()
  }
})

test('予定詳細の編集と削除は360/390pxで44px以上かつ操作名を持つ', async ({ browser }, testInfo) => {
  const team = fixtures.teams.alive_control!
  if (!team.scheduleId || !team.scheduleTitle) throw new Error('OWNED_SCHEDULE_FIXTURE_REQUIRED')
  const context = await browser.newContext({
    baseURL: fixtures.baseUrl,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    isMobile: true,
    hasTouch: true,
    viewport: { width: 360, height: 844 },
  })
  const page = await context.newPage()
  const measurements: unknown[] = []
  try {
    await login(page, 'sys_explore')
    for (const width of [360, 390]) {
      await page.setViewportSize({ width, height: 844 })
      await page.goto(`/teams/${team.slug}/schedule`)
      await waitForHydration(page)
      const selected = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === `/api/v1/teams/${team.slug}/schedules/${team.scheduleId}`,
      )
      await page
        .getByText(team.scheduleTitle, { exact: true })
        .filter({ visible: true })
        .first()
        .tap()
      expect((await selected).status()).toBe(200)
      const heading = page
        .getByRole('heading', { level: 2, name: team.scheduleTitle, exact: true })
        .filter({ visible: true })
      await expect(heading).toBeVisible()
      const header = heading.locator('..').locator('..').locator('..')
      const edit = header.locator('button:has(.pi-pencil)')
      const remove = header.locator('button:has(.pi-trash)')
      await expect(edit).toHaveAttribute('data-testid', 'schedule-edit')
      await edit.scrollIntoViewIfNeeded()
      await page.screenshot({
        path: testInfo.outputPath(`event-detail-${width}.png`),
        fullPage: true,
      })
      measurements.push({ width, control: 'edit', box: await edit.boundingBox() })
      await mobileControlBounds(edit, width, 844)
      await expect(edit).toHaveAccessibleName('編集')
      measurements.push({ width, control: 'delete', box: await remove.boundingBox() })
      await mobileControlBounds(remove, width, 844)
      await expect(remove).toHaveAccessibleName('削除')
      await noHorizontalPan(page)
    }
  } finally {
    await testInfo.attach('event-detail-target-measurements', {
      body: JSON.stringify(measurements),
      contentType: 'application/json',
    })
    await context.close()
  }
})
