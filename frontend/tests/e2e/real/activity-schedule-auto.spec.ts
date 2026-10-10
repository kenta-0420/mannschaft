import { test, expect, type Browser, type Page, type Request, type Response, type TestInfo } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'
import type { ActivitySyncPreview } from '../../../app/types/activityScheduleSync'

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
    const listRequests: string[] = []
    outsider.on('request', (request) => {
      const url = new URL(request.url())
      if (
        request.method() === 'GET' &&
        url.origin === 'http://localhost:8081' &&
        url.pathname === '/api/v1/activities'
      )
        listRequests.push(url.pathname)
    })
    const plural = scope.type === 'TEAM' ? 'teams' : 'organizations'
    // 非所属は me の slug 解決が null になり、活動一覧APIの前にUIエラーとなる。
    const [membership] = await Promise.all([
      outsider.waitForResponse(
        (response) =>
          response.url() === `${API}/me/${plural}` && response.request().method() === 'GET',
      ),
      outsider.goto(`${scopePath(scope)}/activities`),
    ])
    expect(membership.status()).toBe(200)
    const rows = ((await membership.json()) as { data: Array<{ id: number }> }).data
    expect(rows.some((row) => row.id === scope.id)).toBe(false)
    await dismissInitialPermissionDialog(outsider)
    const listError = outsider.getByTestId('load-error-state')
    await expect(listError).toBeVisible()
    await expect(listError).not.toHaveText('')
    expect(listRequests).toEqual([])
    const listErrorText = await listError.innerText()
    await expect(outsider.getByRole('link', { name: title, exact: true })).toHaveCount(0)
    await expect(outsider.getByTestId(`activity-detail-${id}`)).toHaveCount(0)
    await expect(outsider.locator('body')).not.toContainText(privateBody)
    await expect(outsider.locator('body')).not.toContainText(title)
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
        membershipStatus: membership.status(),
        listRequestCount: listRequests.length,
        listVisibleError: listErrorText,
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
async function createViaCalendar(
  page: Page,
  scope: Scope,
  title: string,
  info: TestInfo,
): Promise<number> {
  const startedAt = Date.now()
  const schedulePaths = [
    new URL(schedulesApi(scope)).pathname,
    `/api/v1/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}/schedules`,
  ]
  const isSchedule = (request: Request) =>
    request.method() === 'GET' && schedulePaths.includes(new URL(request.url()).pathname)
  const permissionsPaths = schedulePaths.map((path) => path.replace(/\/schedules$/, '/me/permissions'))
  const isPermissions = (request: Request) =>
    request.method() === 'GET' && permissionsPaths.includes(new URL(request.url()).pathname)
  const observed = (request: Request) => {
    const url = new URL(request.url())
    return request.method() === 'GET' && url.hostname === 'localhost' && ['3001', '8081'].includes(url.port)
  }
  const events: Array<Record<string, string | number>> = []
  const pending = new Map<Request, string>()
  const onRequest = (request: Request) => {
    if (!observed(request)) return
    const url = new URL(request.url())
    const path = `${url.port}${url.pathname}`
    pending.set(request, path)
    events.push({ event: 'request', path, elapsedMs: Date.now() - startedAt })
  }
  const onResponse = (response: Response) => {
    if (!observed(response.request())) return
    const url = new URL(response.url())
    events.push({ event: 'response', path: `${url.port}${url.pathname}`, status: response.status(), elapsedMs: Date.now() - startedAt })
  }
  const onFinished = (request: Request) => {
    if (!observed(request)) return
    events.push({ event: 'finished', path: pending.get(request) ?? new URL(request.url()).pathname, elapsedMs: Date.now() - startedAt })
    pending.delete(request)
  }
  const onFailed = (request: Request) => {
    if (!observed(request)) return
    events.push({ event: 'failed', path: pending.get(request) ?? new URL(request.url()).pathname, error: request.failure()?.errorText ?? 'unknown', elapsedMs: Date.now() - startedAt })
    pending.delete(request)
  }
  page.on('request', onRequest)
  page.on('response', onResponse)
  page.on('requestfinished', onFinished)
  page.on('requestfailed', onFailed)
  try {
    // UIが発行する実GETを受動観測する。データ作成・権限変更のAPI代替はしない。
    const [schedules, permissions] = await Promise.all([
      page.waitForResponse((response) => isSchedule(response.request()), { timeout: 60_000 }),
      page.waitForResponse((response) => isPermissions(response.request()), { timeout: 60_000 }),
      page.goto(`${scopePath(scope)}/schedule`),
    ])
    expect(schedules.status()).toBe(200)
    expect(permissions.status()).toBe(200)
  } finally {
    page.off('request', onRequest)
    page.off('response', onResponse)
    page.off('requestfinished', onFinished)
    page.off('requestfailed', onFailed)
    await writeFile(info.outputPath('calendar-readiness.json'), JSON.stringify({ scope: scope.type, scopeId: scope.id, startedAt: new Date(startedAt).toISOString(), elapsedMs: Date.now() - startedAt, events, pending: [...pending.values()] }, null, 2))
  }
  const add = page.getByRole('button', { name: '予定を追加', exact: true })
  const defer = page.getByRole('button', { name: 'あとで決める', exact: true })
  // 初期権限modalが開くと、背面の予定ボタンはrole探索の対象外になる。
  await expect(add.or(defer).first()).toBeVisible()
  await dismissInitialPermissionDialog(page)
  await expect(add).toBeVisible()
  await add.click()
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
    const combobox = page.getByText(label, { exact: true }).locator('..').getByRole('combobox')
    await combobox.click()
    const controls = await combobox.getAttribute('aria-controls')
    expect(controls).toBeTruthy()
    const listbox = page.locator(`[id="${controls}"]`)
    await expect(listbox).toBeVisible()
    await listbox.getByRole('option', { name: time, exact: true }).click()
    // 前のSelectの閉鎖transitionを次の同名option選択へ持ち越さない。
    await expect(listbox).toBeHidden()
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
  activityId: number,
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
  const scheduleUrls = [
    `${schedulesApi(scope)}/${id}`,
    `${API}/${scope.type === 'TEAM' ? 'teams' : 'organizations'}/${scope.slug}/schedules/${id}`,
  ]
  const [updated, preview] = await Promise.all([
    page.waitForResponse(
      (response) =>
        scheduleUrls.includes(response.url()) && response.request().method() === 'PATCH',
    ),
    (async () => {
      const [response] = await Promise.all([
        page.waitForResponse(
          (response) =>
            scheduleUrls.some((url) => response.url() === `${url}/activity-sync-preview`) &&
            response.request().method() === 'POST',
        ),
        page.getByTestId('schedule-submit').click(),
      ])
      expect(response.status()).toBe(200)
      expect(response.request().postDataJSON()).toMatchObject({ scheduleUpdate: { title } })
      const preview = ((await response.json()) as { data: ActivitySyncPreview }).data
      expect(preview.expectedScheduleState.schedules.map((schedule) => schedule.id)).toEqual([id])
      expect(preview.activities.map((activity) => activity.id)).toEqual([activityId])
      const changes = preview.activities.flatMap((activity) => activity.changes)
      // この操作で承認するのは、所有する予定・活動の日付変更だけ。
      expect(changes.length).toBeGreaterThan(0)
      for (const change of changes) {
        expect(['activityDate', 'activityEndDate']).toContain(change.field)
      }
      if (changes.some((change) => !change.automatic)) {
        await expect(page.getByTestId('activity-sync-apply')).toBeVisible()
        for (const change of changes.filter((change) => !change.automatic)) {
          await page.locator(`input[id="sync-${activityId}-${change.field}"]`).check()
        }
        await page.getByTestId('activity-sync-apply').click()
      }
      return preview
    })(),
  ])
  expect(updated.status()).toBe(200)
  expect(updated.request().postDataJSON()).toMatchObject({
    title,
    syncConfirmation: {
      expectedScheduleState: preview.expectedScheduleState,
      activities: preview.activities.map((activity) => ({
        id: activity.id,
        version: activity.version,
        applyFields: activity.changes.map((change) => change.field),
      })),
    },
  })
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
      scheduleId = await createViaCalendar(page, scope, title, info)
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
    scheduleId = await createViaCalendar(page, scope, title, info)
    activityId = await openActivityFromList(page, scope, title)
    await expect(page.getByTestId(`activity-planned-${activityId}`)).toBeVisible()
    await image(page, info, 'planned-before-batch')
    // 初期 planned を実画面で確認してから、予定終了を実UIで過去へ移す。
    // fixedDelay は前回実行終了基準なので壁時計の0秒を位相と仮定しない。
    await moveScheduleIntoPast(page, scope, scheduleId, activityId, title)
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
