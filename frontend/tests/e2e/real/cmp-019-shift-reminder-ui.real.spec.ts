/**
 * CMP-019 Wave10: 管理者 UI からの手動督促、現役 MEMBER の通知リンクから
 * 特定シフト希望フォームへの到達、SUPPORTER 非配信、管理画面の MEMBER 分母を実機で確認する。
 *
 * 実 BE/FE/MySQL を使い、共有 seed ユーザーは変更しない。作成した schedule と通知だけを
 * ID 指定で片付ける。
 */
import { execFileSync } from 'node:child_process'
import {
  expect,
  request as pwRequest,
  test,
  type APIRequestContext,
  type Browser,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(480_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'
const ADMIN = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const TEAM_ADMIN = process.env.TEST_TEAM_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const SUPPORTER = process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local'
const OUTSIDER = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'
const RUN_TAG = `CMP019_W10_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const TITLE = `シフト希望督促_${RUN_TAG}`
const NOTIFICATION_TYPE = 'SHIFT_REQUEST_REMINDER_MANUAL'
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''

type Session = { token: string; id: number }
type Notification = { id: number; notificationType: string; sourceId: number; title: string; body: string; actionUrl: string | null }

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

function mysql(sql: string): string {
  if (!MYSQL_USER || !MYSQL_PASSWORD) {
    throw new Error('E2E_MYSQL_USER / E2E_MYSQL_PASSWORD が必要です')
  }
  const jdbcJar = process.env.E2E_MYSQL_JDBC_JAR
  if (jdbcJar) {
    return execFileSync('java', ['--class-path', jdbcJar, 'tests/e2e/real/MysqlExec.java', sql], {
      cwd: process.cwd(), env: process.env, encoding: 'utf8',
    })
  }
  const dockerArgs = ['exec', 'mannschaft-mysql', 'mysql', '--batch', '--skip-column-names',
    `-u${MYSQL_USER}`, `-p${MYSQL_PASSWORD}`, 'mannschaft', `--execute=${sql}`]
  return execFileSync(process.platform === 'win32' ? 'wsl.exe' : 'docker',
    process.platform === 'win32' ? ['-e', 'docker', ...dockerArgs] : dockerArgs,
    { encoding: 'utf8' })
}

async function login(api: APIRequestContext, email: string): Promise<Session> {
  const response = await api.post(`${API}/auth/login`, { data: { email, password: PASSWORD } })
  expect(response.status(), `${email} のログイン`).toBe(200)
  const token = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
  const me = await api.get(`${API}/users/me`, { headers: headers(token) })
  expect(me.status(), `${email} の本人情報`).toBe(200)
  return { token, id: ((await me.json()) as { data: { id: number } }).data.id }
}

async function notifications(api: APIRequestContext, token: string, page = 0): Promise<Notification[]> {
  const response = await api.get(`${API}/notifications?page=${page}&size=100`, {
    headers: headers(token),
  })
  expect(response.status(), '通知一覧 API').toBe(200)
  return ((await response.json()) as { data: Notification[] }).data
}

async function findReminder(api: APIRequestContext, token: string, scheduleId: number): Promise<Notification | undefined> {
  // 共有 seed ユーザーには大量の高優先通知があり、新着NORMAL通知が先頭100件から
  // 押し出される。今回の source_id を先頭1000件まで探し、全件を跨ぐ不在は実DBで担保する。
  for (let page = 0; page < 10; page++) {
    const rows = await notifications(api, token, page)
    const found = rows.find(row => row.notificationType === NOTIFICATION_TYPE && row.sourceId === scheduleId)
    if (found) return found
    if (rows.length < 100) break
  }
  return undefined
}

async function showNotification(
  browser: Browser,
  email: string,
  expectedBody: string,
  expectedActionUrl: string | null,
  expectedTitle: string,
): Promise<void> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  try {
    const page = await context.newPage()
    await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText('通知').first()).toBeVisible({ timeout: 30_000 })
    const notificationRows = page.locator('[role="button"][tabindex="0"]')
    await notificationRows.first().waitFor({ state: 'visible', timeout: 30_000 })
    const target = page.getByText(expectedBody, { exact: true })
    if (expectedActionUrl) {
      // 通知が 1 ページ目に無い場合だけ、実 UI の「もっと読む」を押して探す。
      for (let i = 0; i < 50 && await target.count() === 0; i++) {
        const more = page.getByRole('button', { name: 'もっと読む' })
        if (await more.count() === 0) break
        const beforeCount = await notificationRows.count()
        await more.click()
        await expect.poll(() => notificationRows.count(), { timeout: 30_000 })
          .toBeGreaterThan(beforeCount)
      }
      await expect(target, `${email} の通知画面に今回の督促`).toBeVisible({ timeout: 30_000 })
      if (process.env.E2E_MEMBER_SCREENSHOT) {
        await target.scrollIntoViewIfNeeded()
        await page.screenshot({ path: process.env.E2E_MEMBER_SCREENSHOT })
      }
      // 通知の本文を実際にクリックし、指定シフトの希望入力まで到達する。
      await target.click()
      await expect.poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
        .toBe('/my/shift-request')
      const actual = new URL(page.url())
      const expected = new URL(expectedActionUrl, API_BASE)
      expect(actual.searchParams.get('teamId')).toBe(expected.searchParams.get('teamId'))
      expect(actual.searchParams.get('scheduleId')).toBe(expected.searchParams.get('scheduleId'))
      await expect(page.getByText(expectedTitle, { exact: true }).first(),
        '通知リンクが対象シフトの希望入力を開く').toBeVisible({ timeout: 30_000 })
      if (process.env.E2E_FORM_SCREENSHOT) {
        await page.screenshot({ path: process.env.E2E_FORM_SCREENSHOT })
      }
    } else {
      await expect(target, `${email} の通知画面に今回の督促は無い`).toHaveCount(0)
    }
  } finally {
    await context.close()
  }
}

async function assertDirectLinkUnavailable(
  browser: Browser,
  email: string,
  actionUrl: string,
  title: string,
): Promise<void> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  try {
    const page = await context.newPage()
    await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
    await page.goto(actionUrl)
    await waitForHydration(page)
    await expect(page.getByText('このシフト表は表示できないか、希望を受け付けていません。'),
      `${email} に対象シフトの情報を出さない`).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(title, { exact: true }),
      `${email} に対象シフト名を出さない`).toHaveCount(0)
  } finally {
    await context.close()
  }
}

test('管理 UI の督促から MEMBER の対象希望フォームへ到達し SUPPORTER を除外する', async ({ browser }) => {
  const api = await pwRequest.newContext()
  let scheduleId = 0
  let adminToken = ''
  let scheduleDeleted = false
  try {
    const admin = await login(api, ADMIN)
    const teamAdmin = await login(api, TEAM_ADMIN)
    const member = await login(api, MEMBER)
    const supporter = await login(api, SUPPORTER)
    const outsider = await login(api, OUTSIDER)
    adminToken = admin.token
    const teamResponse = await api.get(`${API}/teams/${TEAM_SLUG}`, { headers: headers(adminToken) })
    expect(teamResponse.status(), '共有チームの取得').toBe(200)
    const teamId = ((await teamResponse.json()) as { data: { numericId: number } }).data.numericId

    // 画面操作役はプラットフォーム SYSTEM_ADMIN ではなく当該チームの ADMIN。
    const permissions = await api.get(`${API}/teams/${TEAM_SLUG}/me/permissions`, {
      headers: headers(teamAdmin.token),
    })
    expect(permissions.status(), 'チーム管理者の権限').toBe(200)
    expect(((await permissions.json()) as { data: { roleName: string } }).data.roleName).toBe('ADMIN')

    // fixture のロールと在籍状態を実 DB で確認し、誤った seed で見せかけの green を出さない。
    const membershipRows = mysql(`SELECT CONCAT(user_id, ':', role_kind) FROM memberships WHERE scope_type='TEAM' AND scope_id=${teamId} AND left_at IS NULL AND user_id IN (${member.id},${supporter.id},${outsider.id}) ORDER BY user_id`).trim()
      .split(/\r?\n/).filter(Boolean)
    const roles = new Map(membershipRows.map(row => {
      const [id, role] = row.split(':')
      return [Number(id), role] as const
    }))
    expect(roles.get(member.id), '受信対象は現役 MEMBER').toBe('MEMBER')
    expect(roles.get(supporter.id), 'SUPPORTER fixture').toBe('SUPPORTER')
    expect(roles.has(outsider.id), '他チームのユーザーは当該チームに未所属').toBe(false)

    const activeMemberCount = Number(mysql(`SELECT COUNT(DISTINCT user_id) FROM memberships WHERE scope_type='TEAM' AND scope_id=${teamId} AND left_at IS NULL AND role_kind='MEMBER'`).trim())
    expect(activeMemberCount, '共有 fixture の現役 MEMBER 分母').toBe(7)

    const today = new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
    const future = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000 + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
    const created = await api.post(`${API}/shifts/schedules?teamId=${teamId}`, {
      headers: headers(adminToken), data: { title: TITLE, startDate: today, endDate: future },
    })
    expect(created.status(), `スケジュール作成: ${await created.text()}`).toBe(201)
    scheduleId = ((await created.json()) as { data: { id: number } }).data.id
    const slot = await api.post(`${API}/shifts/schedules/${scheduleId}/slots`, {
      headers: headers(adminToken),
      data: { slotDate: future, startTime: '09:00:00', endTime: '12:00:00', requiredCount: 1 },
    })
    expect(slot.status(), `希望枠作成: ${await slot.text()}`).toBe(201)
    const transition = await api.post(`${API}/shifts/schedules/${scheduleId}/transition?status=COLLECTING`, {
      headers: headers(adminToken),
    })
    expect(transition.status(), '希望収集中に遷移').toBe(200)

    const adminContext = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    let remindedUserIds: number[] = []
    let remindedCount = 0
    try {
      const adminPage = await adminContext.newPage()
      await loginViaApi(adminPage, { email: TEAM_ADMIN, password: PASSWORD }, { apiBaseUrl: API_BASE })
      await adminPage.goto(`/teams/${TEAM_SLUG}/shifts`)
      await waitForHydration(adminPage)
      await expect(adminPage.getByText(TITLE, { exact: true }),
        '管理者が対象シフトの一覧行を見られる').toBeVisible({ timeout: 30_000 })
      await expect(adminPage.getByTestId(`shift-reminder-${scheduleId}`),
        'チームのシフト一覧にも手動督促ボタンがある').toBeVisible()

      // 住民がサイドバーから辿る管理画面の詳細でも送信できることを本経路にする。
      await adminPage.goto(`/shift/${scheduleId}`)
      await waitForHydration(adminPage)
      await expect(adminPage.getByText(TITLE, { exact: true }),
        '管理者が対象シフトの詳細を開ける').toBeVisible({ timeout: 30_000 })
      const remindButton = adminPage.getByTestId(`shift-reminder-${scheduleId}`)
      await expect(remindButton, '詳細画面に手動督促ボタンが表示される').toBeVisible()
      await remindButton.click()
      const dialog = adminPage.getByRole('alertdialog')
      await expect(dialog.getByText('未提出者へリマインド', { exact: true })).toBeVisible()

      const reminderResponse = adminPage.waitForResponse(response =>
        response.request().method() === 'POST'
          && response.url().endsWith(`/api/v1/shifts/schedules/${scheduleId}/remind`),
      )
      await dialog.getByRole('button', { name: '送信する' }).click()
      const reminder = await reminderResponse
      expect(reminder.status(), '画面操作による手動督促').toBe(200)
      const result = (await reminder.json()) as { data: { remindedCount: number; remindedUserIds: number[] } }
      remindedUserIds = result.data.remindedUserIds
      remindedCount = result.data.remindedCount
      await expect(adminPage.getByText('リマインドを送信しました')).toBeVisible()

      // 同じ対象の管理画面で、現役 MEMBER 7人だけを分母とすることを確認。
      await adminPage.goto(`/shift/${scheduleId}/requests`)
      await waitForHydration(adminPage)
      await expect(adminPage.getByText(TITLE, { exact: true })).toBeVisible({ timeout: 30_000 })
      await expect(adminPage.getByText('0 / 7', { exact: false }),
        '管理画面の提出率分母').toBeVisible({ timeout: 30_000 })
      await expect(adminPage.getByText('未提出', { exact: true }).locator('..')
        .getByText('7', { exact: true }), '管理画面の未提出数').toBeVisible()
    } finally {
      await adminContext.close()
    }

    const summaryResponse = await api.get(`${API}/shifts/requests/summary?scheduleId=${scheduleId}`, {
      headers: headers(teamAdmin.token),
    })
    expect(summaryResponse.status(), '希望提出サマリー API').toBe(200)
    const summary = (await summaryResponse.json()) as { data: { totalMembers: number; submittedCount: number; pendingCount: number } }
    expect(summary.data).toMatchObject({ totalMembers: 7, submittedCount: 0, pendingCount: 7 })
    expect(remindedCount).toBe(remindedUserIds.length)
    expect(remindedCount, '全現役 MEMBER が未提出の fixture').toBe(activeMemberCount)
    expect(remindedUserIds, '現役 MEMBER の受信').toContain(member.id)
    expect(remindedUserIds, 'SUPPORTER の除外').not.toContain(supporter.id)
    expect(remindedUserIds, 'チーム非所属者の除外').not.toContain(outsider.id)

    await expect.poll(async () => (await findReminder(api, member.token, scheduleId))?.id ?? 0,
      { timeout: 30_000 }).toBeGreaterThan(0)
    const delivered = await findReminder(api, member.token, scheduleId)
    expect(delivered?.body, '受信通知に対象シフト名が含まれる').toContain(TITLE)
    for (const [email, session] of [[SUPPORTER, supporter], [OUTSIDER, outsider]] as const) {
      const rows = await notifications(api, session.token)
      expect(rows.filter(row => row.notificationType === NOTIFICATION_TYPE && row.sourceId === scheduleId), `${email} の通知 API`).toHaveLength(0)
    }

    const dbRows = mysql(`SELECT user_id FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' AND source_type='SHIFT_SCHEDULE' AND source_id=${scheduleId} ORDER BY user_id`).trim()
      .split(/\r?\n/).filter(Boolean).map(Number)
    expect(dbRows, '実 MySQL の配送先が UI 操作の API 応答と一致').toEqual([...remindedUserIds].sort((a, b) => a - b))

    const expectedActionUrl = `/my/shift-request?teamId=${teamId}&scheduleId=${scheduleId}`
    expect(delivered?.actionUrl, '通知に対象シフト希望フォームの URL').toBe(expectedActionUrl)
    await showNotification(browser, MEMBER, delivered!.body, expectedActionUrl, TITLE)
    await showNotification(browser, SUPPORTER, delivered!.body, null, TITLE)
    await showNotification(browser, OUTSIDER, delivered!.body, null, TITLE)

    // 通知を受けない権限外ユーザーがURLを知っていても対象シフトを表示できない。
    await assertDirectLinkUnavailable(browser, SUPPORTER, expectedActionUrl, TITLE)
    await assertDirectLinkUnavailable(browser, OUTSIDER, expectedActionUrl, TITLE)

    const deleted = await api.delete(`${API}/shifts/schedules/${scheduleId}`, {
      headers: headers(adminToken),
    })
    expect(deleted.status(), `作成スケジュール ${scheduleId} の削除`).toBe(204)
    scheduleDeleted = true
    // 既に通知を受けた MEMBER でも、削除済みシフトは深いリンクから開けない。
    await assertDirectLinkUnavailable(browser, MEMBER, expectedActionUrl, TITLE)
  } catch (error) {
    // Playwright の通信例外は Authorization/Cookie を call log に含むことがある。
    const message = error instanceof Error ? error.message : String(error)
    if (!/Authorization:|cookie:/i.test(message)) throw error
    throw new Error(message
      .replace(/(Authorization:\s*Bearer\s+)\S+/gi, '$1[REDACTED]')
      .replace(/(access_token=|refresh_token=)[^;\s]+/gi, '$1[REDACTED]'))
  } finally {
    if (scheduleId && adminToken) {
      // 新規 schedule に紐づく今回の通知だけを消す。共有 fixture の行は触らない。
      try {
        mysql(`DELETE FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' AND source_type='SHIFT_SCHEDULE' AND source_id=${scheduleId}`)
      } finally {
        if (!scheduleDeleted) {
          const deleted = await api.delete(`${API}/shifts/schedules/${scheduleId}`, {
            headers: headers(adminToken),
          })
          expect(deleted.status(), `作成スケジュール ${scheduleId} の削除`).toBeLessThan(300)
        }
      }
    }
    await api.dispose()
  }
})
