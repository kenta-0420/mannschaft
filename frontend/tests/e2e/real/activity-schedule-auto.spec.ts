import { test, expect, type Browser, type Page, type TestInfo } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'

const API = 'http://localhost:8081/api/v1'
const CAMPAIGN = 'CMP-261008-1203'
interface Scope {
  type: 'TEAM' | 'ORGANIZATION'
  id: number
  slug: string
  ownerPrefix: string
}
interface Manifest {
  campaign: string
  apiBase: string
  baseUrl: string
  scopes: Scope[]
}
let manifest: Manifest

// fixture の認証値をコード・manifest・診断出力へ埋め込まない。
test.skip(process.env.CMP_ACTIVITY_AUTO_REAL !== '1', '専用環境の明示許可後だけ実行する')
test.beforeAll(async () => {
  const path = process.env.CMP_ACTIVITY_AUTO_MANIFEST
  if (!path) throw new Error('専用 fixture manifest が必要です')
  manifest = JSON.parse(await readFile(path, 'utf8')) as Manifest
  if (
    manifest.campaign !== CAMPAIGN ||
    manifest.apiBase !== 'http://localhost:8081' ||
    manifest.baseUrl !== 'http://localhost:3001' ||
    !manifest.scopes.every(
      (scope) =>
        Number.isSafeInteger(scope.id) &&
        scope.id > 0 &&
        ['TEAM', 'ORGANIZATION'].includes(scope.type) &&
        scope.ownerPrefix.startsWith('cmp2610081203-') &&
        scope.slug.startsWith(scope.ownerPrefix),
    )
  )
    throw new Error('専用環境と所有 scope の照合に失敗しました')
})

function scopePath(scope: Scope): string {
  return `/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}`
}
function schedulesApi(scope: Scope): string {
  return `${API}/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.id}/schedules`
}
async function signIn(page: Page, role: 'AUTHOR' | 'READER' | 'OUTSIDER'): Promise<void> {
  const email = process.env[`CMP_ACTIVITY_${role}_EMAIL`]
  const password = process.env[`CMP_ACTIVITY_${role}_PASSWORD`]
  if (!email || !password) throw new Error('専用ロールの認証設定が必要です')
  try {
    await loginViaApi(
      page,
      { email, password },
      { apiBaseUrl: 'http://localhost:8081', deferNavigation: true },
    )
  } catch {
    // 共通認証 helper の失敗本文には email があるため、実機証跡へ再出力しない。
    throw new Error('専用ロールの認証に失敗しました')
  }
}
async function readerPage(browser: Browser, width: number): Promise<Page> {
  const context = await browser.newContext({
    baseURL: 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport: { width, height: 800 },
  })
  const page = await context.newPage()
  try {
    await signIn(page, 'READER')
    return page
  } catch (error) {
    await context.close()
    throw error
  }
}
async function verifyOutsiderCannotRead(
  browser: Browser,
  scope: Scope,
  id: number,
  title: string,
  privateBody: string,
  info: TestInfo,
): Promise<void> {
  const context = await browser.newContext({
    baseURL: 'http://localhost:3001',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport: { width: 1280, height: 720 },
  })
  const outsider = await context.newPage()
  try {
    await signIn(outsider, 'OUTSIDER')
    const [listed] = await Promise.all([
      outsider.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.origin === 'http://localhost:8081' &&
          url.pathname === '/api/v1/activities' &&
          url.searchParams.get('scope_type') === scope.type &&
          url.searchParams.get('scope_id') === String(scope.id) &&
          response.request().method() === 'GET'
        )
      }),
      outsider.goto(`${scopePath(scope)}/activities`),
    ])
    expect([200, 403, 404]).toContain(listed.status())
    await dismissInitialPermissionDialog(outsider)
    if (listed.status() === 200) {
      const rows = ((await listed.json()) as { data: Array<{ id: number }> }).data
      expect(rows.some((row) => row.id === id)).toBe(false)
      await expect(outsider.getByTestId('activity-status-filter')).toBeVisible()
    } else {
      await expect(outsider.getByTestId('load-error-state')).toBeVisible()
    }
    await expect(outsider.getByRole('link', { name: title, exact: true })).toHaveCount(0)
    await expect(outsider.getByTestId(`activity-detail-${id}`)).toHaveCount(0)
    await expect(outsider.locator('body')).not.toContainText(privateBody)
    await image(outsider, info, `${scope.type}-outsider-list`)
    const [denied] = await Promise.all([
      outsider.waitForResponse(
        (response) =>
          response.url() === `${API}/activities/${id}` &&
          response.request().method() === 'GET',
      ),
      outsider.goto(`/activities/${id}`),
    ])
    expect([403, 404]).toContain(denied.status())
    const error = outsider.getByTestId('load-error-state')
    await expect(error).toBeVisible()
    await expect(error).not.toHaveText('')
    await expect(outsider.getByRole('heading', { name: title, exact: true })).toHaveCount(0)
    await expect(outsider.locator('body')).not.toContainText(privateBody)
    for (const testid of [
      'activity-metadata-only',
      'activity-description',
      'activity-template-fields',
      'activity-participants',
      'activity-edit-draft',
      'activity-publish',
      'activity-delete',
      'activity-source-schedule',
    ]) {
      await expect(outsider.getByTestId(testid)).toHaveCount(0)
    }
    await info.attach(`${scope.type}-outsider-denied`, {
      body: JSON.stringify({
        listStatus: listed.status(),
        detailStatus: denied.status(),
        detailPath: new URL(outsider.url()).pathname,
        visibleError: await error.innerText(),
      }),
      contentType: 'application/json',
    })
    await image(outsider, info, `${scope.type}-outsider-detail-denied`)
  } finally {
    await context.close()
  }
}
async function image(page: Page, info: TestInfo, name: string): Promise<void> {
  await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })
}
async function dismissInitialPermissionDialog(page: Page): Promise<void> {
  const defer = page.getByRole('button', { name: 'あとで決める', exact: true })
  if (await defer.isVisible()) await defer.click()
}
function date(days: number): string {
  const value = new Date()
  value.setDate(value.getDate() + days)
  return `${value.getFullYear()}/${String(value.getMonth() + 1).padStart(2, '0')}/${String(value.getDate()).padStart(2, '0')}`
}
async function createViaCalendar(page: Page, scope: Scope, title: string): Promise<number> {
  await page.goto(`${scopePath(scope)}/schedule`)
  await expect(page.getByRole('button', { name: '予定を追加', exact: true })).toBeVisible()
  await dismissInitialPermissionDialog(page)
  await page.getByRole('button', { name: '予定を追加', exact: true }).click()
  await page.getByTestId('schedule-title').fill(title)
  const day = date(2)
  for (const id of ['schedule-start-date', 'schedule-end-date']) {
    await page.locator(`#${id}`).fill(day)
    await page.locator(`#${id}`).press('Tab')
  }
  for (const [label, time] of [
    ['開始時刻', '09:00'],
    ['終了時刻', '10:00'],
  ] as const) {
    await page.getByText(label, { exact: true }).locator('..').getByRole('combobox').click()
    await page.getByRole('option', { name: time, exact: true }).click()
  }
  const attendance = page.locator('#attendance-required')
  if (await attendance.isChecked()) await attendance.uncheck()
  const [created] = await Promise.all([
    page.waitForResponse(
      (response) =>
        [
          schedulesApi(scope),
          `${API}/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}/schedules`,
        ].includes(response.url()) && response.request().method() === 'POST',
    ),
    page.getByTestId('schedule-submit').click(),
  ])
  expect(created.ok()).toBe(true)
  return ((await created.json()) as { data: { id: number } }).data.id
}
async function writePrivateDescription(
  page: Page,
  id: number,
  title: string,
  body: string,
): Promise<void> {
  await page.getByTestId('activity-edit-draft').click()
  await expect(page.getByTestId('activity-edit-title')).toHaveValue(title)
  await page.getByTestId('activity-edit-description').fill(body)
  const [saved] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url() === `${API}/activities/${id}` && response.request().method() === 'PUT',
    ),
    page.getByTestId('activity-edit-save').click(),
  ])
  expect(saved.status()).toBe(200)
  expect(saved.request().postDataJSON()).toMatchObject({ title, description: body })
  await expect(page.getByTestId('activity-description')).toContainText(body)
}
async function moveScheduleIntoPast(
  page: Page,
  scope: Scope,
  id: number,
  title: string,
): Promise<void> {
  await page.getByTestId('activity-source-schedule').click()
  await page.locator('[data-testid="schedule-edit"]:visible').click()
  await expect(page.getByTestId('schedule-edit-loading')).toHaveCount(0)
  await expect(page.getByTestId('schedule-title')).toHaveValue(title)
  for (const field of ['schedule-start-date', 'schedule-end-date']) {
    await page.locator(`#${field}`).fill(date(-1))
    await page.locator(`#${field}`).press('Tab')
  }
  const [updated] = await Promise.all([
    page.waitForResponse(
      (response) =>
        [
          `${schedulesApi(scope)}/${id}`,
          `${API}/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}/schedules/${id}`,
        ].includes(response.url()) && response.request().method() === 'PATCH',
    ),
    page.getByTestId('schedule-submit').click(),
  ])
  expect(updated.status()).toBe(200)
  expect(updated.request().postDataJSON()).toMatchObject({ title })
}
async function openActivityFromList(page: Page, scope: Scope, title: string): Promise<number> {
  await page.goto(`${scopePath(scope)}/activities`)
  await dismissInitialPermissionDialog(page)
  await page.getByRole('link', { name: title, exact: true }).click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  return Number(new URL(page.url()).pathname.split('/').at(-1))
}
async function cleanup(
  page: Page,
  scope: Scope,
  scheduleId: number | undefined,
  title: string,
  activityId?: number,
): Promise<void> {
  if (!scheduleId) return
  // 原予定が所有タイトルと一致した場合だけ fixture を撤去する。
  const original = await page.request.get(`${schedulesApi(scope)}/${scheduleId}`)
  if (original.status() !== 200) throw new Error('所有予定の cleanup 照合に失敗しました')
  const data = ((await original.json()) as { data: { content: { title: string } } }).data
  if (data.content.title !== title || !title.startsWith(scope.ownerPrefix))
    throw new Error('所有予定タイトルが一致しません')
  if (activityId) {
    const removed = await page.request.delete(`${API}/activities/${activityId}`)
    expect([204, 404]).toContain(removed.status())
  }
  expect((await page.request.delete(`${schedulesApi(scope)}/${scheduleId}`)).status()).toBe(204)
}

for (const [type, width] of [
  ['TEAM', 360],
  ['ORGANIZATION', 390],
] as const) {
  test(`${type}: カレンダー自動記録・一般metadata・削除取消と通信復旧を実UIで確認`, async ({
    page,
    browser,
  }, info) => {
    const scope = manifest.scopes.find((value) => value.type === type)
    if (!scope) throw new Error('専用 scope がありません')
    await signIn(page, 'AUTHOR')
    const title = `${scope.ownerPrefix}実機-${Date.now()}`
    let scheduleId: number | undefined
    let activityId: number | undefined
    let reader: Page | undefined
    try {
      scheduleId = await createViaCalendar(page, scope, title)
      activityId = await openActivityFromList(page, scope, title)
      await expect(page.getByTestId(`activity-planned-${activityId}`)).toBeVisible()
      await expect(page.getByTestId(`activity-status-${activityId}`)).toHaveText('未公開')
      const privateBody = `${title}：まだ公開していない実活動本文`
      await writePrivateDescription(page, activityId, title, privateBody)
      await image(page, info, `${type}-desktop-author-detail`)
      await page.getByTestId('activity-source-schedule').click()
      await expect(page).toHaveURL(new RegExp(`eventId=${scheduleId}`))
      await page.locator(`[data-testid="schedule-activity-${activityId}"]:visible`).click()
      reader = await readerPage(browser, width)
      await openActivityFromList(reader, scope, title)
      await expect(reader.getByTestId('activity-metadata-only')).toBeVisible()
      await expect(reader.locator('body')).not.toContainText(privateBody)
      for (const id of [
        'activity-description',
        'activity-template-fields',
        'activity-participants',
        'activity-edit-draft',
        'activity-publish',
        'activity-delete',
      ]) {
        await expect(reader.getByTestId(id)).toHaveCount(0)
      }
      await expect(reader.locator('dl')).toHaveCount(0)
      await expect(reader.getByText('参加者は登録されていません', { exact: true })).toHaveCount(0)
      await expect(reader.getByTestId('activity-source-schedule')).toBeVisible()
      expect(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      )
      await image(reader, info, `${type}-metadata-${width}`)
      await verifyOutsiderCannotRead(browser, scope, activityId, title, privateBody, info)
      await page.setViewportSize({ width, height: 800 })
      const requests: string[] = []
      page.on('request', (request) => {
        if (request.method() === 'DELETE') requests.push(new URL(request.url()).pathname)
      })
      await page.getByTestId('activity-delete').click()
      const dialog = page.getByRole('alertdialog')
      await expect(dialog).toContainText('活動記録を削除します。元の予定は削除されません。')
      const cancel = dialog.getByRole('button', { name: 'キャンセル', exact: true })
      await cancel.focus()
      await cancel.press('Enter')
      expect(requests).toEqual([])
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      await page.context().setOffline(true)
      await page.getByTestId('activity-delete').click()
      const [failed] = await Promise.all([
        page.waitForEvent('requestfailed', {
          predicate: (request) =>
            request.method() === 'DELETE' && request.url() === `${API}/activities/${activityId}`,
        }),
        dialog.getByRole('button', { name: '削除する', exact: true }).click(),
      ])
      expect(failed.failure()?.errorText).toBeTruthy()
      expect(requests).toEqual([`/api/v1/activities/${activityId}`])
      await info.attach(`${type}-failed-delete`, {
        body: JSON.stringify({
          method: failed.method(),
          path: new URL(failed.url()).pathname,
          error: failed.failure()?.errorText,
        }),
        contentType: 'application/json',
      })
      await expect(page.getByTestId('activity-delete')).toBeEnabled()
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      await image(page, info, `${type}-delete-offline`)
      await page.context().setOffline(false)
      await page.getByTestId('activity-delete').click()
      for (const label of ['キャンセル', '削除する']) {
        const button = dialog.getByRole('button', { name: label, exact: true })
        await expect
          .poll(async () => (await button.boundingBox())?.height ?? 0)
          .toBeGreaterThanOrEqual(44)
        await expect
          .poll(async () => (await button.boundingBox())?.width ?? 0)
          .toBeGreaterThanOrEqual(44)
      }
      await image(page, info, `${type}-delete-confirm-${width}`)
      const [deleted] = await Promise.all([
        page.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === `/api/v1/activities/${activityId}` &&
            response.request().method() === 'DELETE',
        ),
        dialog.getByRole('button', { name: '削除する', exact: true }).click(),
      ])
      expect(deleted.status()).toBe(204)
      await expect(page).toHaveURL(`${scopePath(scope)}/activities`)
      expect((await page.request.get(`${schedulesApi(scope)}/${scheduleId}`)).status()).toBe(200)
    } finally {
      await page.context().setOffline(false)
      await reader?.context().close()
      await cleanup(page, scope, scheduleId, title, activityId)
    }
  })
}

test('終了経過の毎分完了後、再表示で予定だけ外れ未公開が維持される', async ({ page }, info) => {
  const scope = manifest.scopes.find((value) => value.type === 'TEAM')
  if (!scope) throw new Error('専用 TEAM scope がありません')
  await signIn(page, 'AUTHOR')
  const title = `${scope.ownerPrefix}終了経過-${Date.now()}`
  let scheduleId: number | undefined
  let activityId: number | undefined
  try {
    scheduleId = await createViaCalendar(page, scope, title)
    activityId = await openActivityFromList(page, scope, title)
    await expect(page.getByTestId(`activity-planned-${activityId}`)).toBeVisible()
    await image(page, info, 'planned-before-batch')
    // 初期 planned を実画面で確認してから、予定終了を実UIで過去へ移す。
    // fixedDelay は前回実行終了基準なので壁時計の0秒を位相と仮定しない。
    await moveScheduleIntoPast(page, scope, scheduleId, title)
    await openActivityFromList(page, scope, title)
    for (let attempt = 0; attempt < 2; attempt++) {
      await page.waitForTimeout(60_000)
      await page.reload()
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      if ((await page.getByTestId(`activity-planned-${activityId}`).count()) === 0) break
    }
    await expect(page.getByTestId(`activity-planned-${activityId}`)).toHaveCount(0)
    await expect(page.getByTestId(`activity-status-${activityId}`)).toHaveText('未公開')
    const source = await page.request.get(`${schedulesApi(scope)}/${scheduleId}`)
    expect(source.status()).toBe(200)
    const sourceStatus = ((await source.json()) as { data: { content: { status: string } } }).data
      .content.status
    expect(sourceStatus).toBe('COMPLETED')
    await info.attach('source-completed', {
      body: JSON.stringify({ id: scheduleId, status: sourceStatus }),
      contentType: 'application/json',
    })
    await image(page, info, 'unpublished-after-batch')
  } finally {
    await cleanup(page, scope, scheduleId, title, activityId)
  }
})
