/**
 * CMP-019 Wave9: シフト希望の手動督促が現役 MEMBER の未提出者に届き、
 * SUPPORTER とチーム非所属者には届かないことを実機で確認する。
 *
 * 手動督促の FE 導線は未実装のため、対象操作のみ実 BE API を呼ぶ。
 * 受信結果は実 FE の通知画面と実 MySQL の行で確認する。作成した schedule と通知だけを ID 指定で消す。
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
test.setTimeout(240_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const TEAM_SLUG = 'fc-u-18'
const ADMIN = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const SUPPORTER = process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local'
const OUTSIDER = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'
const RUN_TAG = `CMP019_W9_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const TITLE = `シフト希望督促_${RUN_TAG}`
const NOTIFICATION_TYPE = 'SHIFT_REQUEST_REMINDER_MANUAL'
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''

type Session = { token: string; id: number }
type Notification = { id: number; notificationType: string; sourceId: number; title: string; body: string }

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

async function notifications(api: APIRequestContext, token: string): Promise<Notification[]> {
  // 共有 seed ユーザーには 2000 件超の既存通知がある。今回の新着は先頭ページで確認し、
  // 全件を跨ぐ「不在」は source_id を絞った実 DB の結果で担保する。
  const response = await api.get(`${API}/notifications?page=0&size=100`, {
    headers: headers(token),
  })
  expect(response.status(), '通知一覧 API').toBe(200)
  return ((await response.json()) as { data: Notification[] }).data
}

async function showNotification(browser: Browser, email: string, expectedBody: string, shouldExist: boolean): Promise<void> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  try {
    const page = await context.newPage()
    await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText('通知').first()).toBeVisible({ timeout: 30_000 })
    const target = page.getByText(expectedBody, { exact: true })
    if (shouldExist) {
      // 通知が 1 ページ目に無い場合だけ、実 UI の「もっと読む」を押して探す。
      for (let i = 0; i < 20 && await target.count() === 0; i++) {
        const more = page.getByRole('button', { name: 'もっと読む' })
        if (await more.count() === 0) break
        await more.click()
      }
      await expect(target, `${email} の通知画面に今回の督促`).toBeVisible({ timeout: 30_000 })
      if (process.env.E2E_MEMBER_SCREENSHOT) {
        await target.scrollIntoViewIfNeeded()
        await page.screenshot({ path: process.env.E2E_MEMBER_SCREENSHOT })
      }
    } else {
      await expect(target, `${email} の通知画面に今回の督促は無い`).toHaveCount(0)
    }
  } finally {
    await context.close()
  }
}

test('現役 MEMBER の未提出者に届き SUPPORTER とチーム非所属者には届かない', async ({ browser }) => {
  const api = await pwRequest.newContext()
  let scheduleId = 0
  let adminToken = ''
  try {
    const admin = await login(api, ADMIN)
    const member = await login(api, MEMBER)
    const supporter = await login(api, SUPPORTER)
    const outsider = await login(api, OUTSIDER)
    adminToken = admin.token
    const teamResponse = await api.get(`${API}/teams/${TEAM_SLUG}`, { headers: headers(adminToken) })
    expect(teamResponse.status(), '共有チームの取得').toBe(200)
    const teamId = ((await teamResponse.json()) as { data: { numericId: number } }).data.numericId

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
    try {
      const adminPage = await adminContext.newPage()
      await loginViaApi(adminPage, { email: ADMIN, password: PASSWORD }, { apiBaseUrl: API_BASE })
      await adminPage.goto(`/shift/${scheduleId}`)
      await waitForHydration(adminPage)
      await expect(adminPage.getByText(TITLE).first(), '管理者が対象のシフト詳細を開ける').toBeVisible({ timeout: 30_000 })
    } finally {
      await adminContext.close()
    }

    // FE に手動督促導線が無いため、この業務操作だけ API を使用する。
    const reminder = await api.post(`${API}/shifts/schedules/${scheduleId}/remind`, {
      headers: headers(adminToken),
    })
    expect(reminder.status(), `手動督促: ${await reminder.text()}`).toBe(200)
    const result = (await reminder.json()) as { data: { remindedCount: number; remindedUserIds: number[] } }
    expect(result.data.remindedCount).toBe(result.data.remindedUserIds.length)
    expect(result.data.remindedUserIds, '現役 MEMBER の受信').toContain(member.id)
    expect(result.data.remindedUserIds, 'SUPPORTER の除外').not.toContain(supporter.id)
    expect(result.data.remindedUserIds, 'チーム非所属者の除外').not.toContain(outsider.id)

    await expect.poll(async () => {
      const rows = await notifications(api, member.token)
      return rows.filter(row => row.notificationType === NOTIFICATION_TYPE && row.sourceId === scheduleId).length
    }, { timeout: 30_000 }).toBe(1)
    const memberRows = await notifications(api, member.token)
    const delivered = memberRows.find(row => row.notificationType === NOTIFICATION_TYPE && row.sourceId === scheduleId)
    expect(delivered?.body, '受信通知に対象シフト名が含まれる').toContain(TITLE)
    for (const [email, session] of [[SUPPORTER, supporter], [OUTSIDER, outsider]] as const) {
      const rows = await notifications(api, session.token)
      expect(rows.filter(row => row.notificationType === NOTIFICATION_TYPE && row.sourceId === scheduleId), `${email} の通知 API`).toHaveLength(0)
    }

    const dbRows = mysql(`SELECT user_id FROM notifications WHERE notification_type='${NOTIFICATION_TYPE}' AND source_type='SHIFT_SCHEDULE' AND source_id=${scheduleId} ORDER BY user_id`).trim()
      .split(/\r?\n/).filter(Boolean).map(Number)
    expect(dbRows, '実 MySQL の配送先が API 応答と一致').toEqual([...result.data.remindedUserIds].sort((a, b) => a - b))

    await showNotification(browser, MEMBER, delivered!.body, true)
    await showNotification(browser, SUPPORTER, delivered!.body, false)
    await showNotification(browser, OUTSIDER, delivered!.body, false)
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
        const deleted = await api.delete(`${API}/shifts/schedules/${scheduleId}`, {
          headers: headers(adminToken),
        })
        expect(deleted.status(), `作成スケジュール ${scheduleId} の削除`).toBeLessThan(300)
      }
    }
    await api.dispose()
  }
})
