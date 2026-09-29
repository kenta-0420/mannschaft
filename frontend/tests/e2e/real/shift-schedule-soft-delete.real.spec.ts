/**
 * 実機E2E: シフト表削除のUI導線と提出履歴保持（CMP-260923-0953 / PR #3507）
 *
 * 対象操作の削除は必ず ADMIN の画面から行う。API はログイン、前提データ作成、
 * ロールの裏取り、失敗時の後始末にだけ使う。
 *
 * ロール横断:
 * - ADMIN: /shift/{id} の削除確認を承認し、一覧から対象が消える
 * - MEMBER: 同じ詳細を閲覧できるが削除導線は無く、削除後も /my/shifts に希望履歴が残る
 * - OUTSIDER: チーム一覧URL・詳細URLを直打ちしても対象を表示できない
 */
import {
  expect,
  request as playwrightRequest,
  test,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(240_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API = `${API_BASE}/api/v1`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const TEAM_SLUG = process.env.TEST_TEAM_SLUG ?? 'fc-u-18'
const ADMIN = process.env.TEST_TEAM_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local'
const MEMBER = process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const OUTSIDER = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'
const RUN_TAG = `CMP2609230953_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const TITLE = `論理削除実機_${RUN_TAG}`

type Session = { token: string; id: number }

function headers(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function login(api: APIRequestContext, email: string): Promise<Session> {
  const response = await api.post(`${API}/auth/login`, {
    data: { email, password: PASSWORD },
  })
  expect(response.status(), `${email} のログイン`).toBe(200)
  const token = ((await response.json()) as { data: { accessToken: string } }).data.accessToken
  const me = await api.get(`${API}/users/me`, { headers: headers(token) })
  expect(me.status(), `${email} の本人情報`).toBe(200)
  return {
    token,
    id: ((await me.json()) as { data: { id: number } }).data.id,
  }
}

async function openAs(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
  return page
}

test('ADMINがUIから削除し、MEMBER履歴を保持して他テナントへ秘匿する', async ({ browser }) => {
  const api = await playwrightRequest.newContext()
  let scheduleId = 0
  let adminToken = ''
  let deletedByUi = false

  try {
    const admin = await login(api, ADMIN)
    const member = await login(api, MEMBER)
    await login(api, OUTSIDER)
    adminToken = admin.token

    const permissions = await api.get(`${API}/teams/${TEAM_SLUG}/me/permissions`, {
      headers: headers(admin.token),
    })
    expect(permissions.status(), '操作役のチーム権限').toBe(200)
    expect(((await permissions.json()) as { data: { roleName: string } }).data.roleName).toBe(
      'ADMIN',
    )

    const teamResponse = await api.get(`${API}/teams/${TEAM_SLUG}`, {
      headers: headers(admin.token),
    })
    expect(teamResponse.status(), '共有チーム取得').toBe(200)
    const teamId = ((await teamResponse.json()) as { data: { numericId: number } }).data.numericId

    const today = new Date(Date.now() + 9 * 60 * 60 * 1000)
    const startDate = new Date(today.getTime() + 10 * 24 * 60 * 60 * 1000)
      .toISOString()
      .slice(0, 10)
    const endDate = new Date(today.getTime() + 11 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)

    const created = await api.post(`${API}/shifts/schedules?teamId=${teamId}`, {
      headers: headers(admin.token),
      data: { title: TITLE, startDate, endDate, note: RUN_TAG },
    })
    expect(created.status(), `シフト表作成: ${await created.text()}`).toBe(201)
    scheduleId = ((await created.json()) as { data: { id: number } }).data.id

    const slot = await api.post(`${API}/shifts/schedules/${scheduleId}/slots`, {
      headers: headers(admin.token),
      data: {
        slotDate: startDate,
        startTime: '09:00:00',
        endTime: '12:00:00',
        requiredCount: 1,
      },
    })
    expect(slot.status(), `シフト枠作成: ${await slot.text()}`).toBe(201)
    const slotId = ((await slot.json()) as { data: { id: number } }).data.id

    const collecting = await api.post(
      `${API}/shifts/schedules/${scheduleId}/transition?status=COLLECTING`,
      { headers: headers(admin.token) },
    )
    expect(collecting.status(), '希望収集中への遷移').toBe(200)

    const request = await api.post(`${API}/shifts/requests`, {
      headers: headers(member.token),
      data: { slotId, preference: 'AVAILABLE', note: RUN_TAG },
    })
    expect(request.status(), `MEMBERの希望作成: ${await request.text()}`).toBe(201)
    const requestId = ((await request.json()) as { data: { id: number } }).data.id

    // 負の視点: MEMBER は詳細を閲覧できても、削除導線を操作できない。
    const memberPage = await openAs(browser, MEMBER)
    await memberPage.goto(`/shift/${scheduleId}`)
    await waitForHydration(memberPage)
    await expect(memberPage.getByText(TITLE, { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(memberPage.getByTestId('shift-schedule-delete')).toHaveCount(0)
    await memberPage.context().close()

    // 他テナント視点: 一覧URL・詳細URLの直打ちでも存在を開示しない。
    const outsiderPage = await openAs(browser, OUTSIDER)
    const outsiderListResponse = outsiderPage.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().includes('/api/v1/shifts/schedules?teamId='),
    )
    await outsiderPage.goto(`/teams/${TEAM_SLUG}/shifts`)
    await waitForHydration(outsiderPage)
    expect([403, 404], '他テナントの一覧直打ち').toContain((await outsiderListResponse).status())
    await expect(outsiderPage.getByText(TITLE, { exact: true })).toHaveCount(0)

    const outsiderDetailResponse = outsiderPage.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().endsWith(`/api/v1/shifts/schedules/${scheduleId}`),
    )
    await outsiderPage.goto(`/shift/${scheduleId}`)
    await waitForHydration(outsiderPage)
    expect([403, 404], '他テナントの詳細直打ち').toContain((await outsiderDetailResponse).status())
    await expect(outsiderPage.getByText(TITLE, { exact: true })).toHaveCount(0)
    await outsiderPage.context().close()

    // 正の視点: 素のチームADMINが実画面の確認ダイアログを経て削除する。
    const adminPage = await openAs(browser, ADMIN)
    await adminPage.goto(`/shift/${scheduleId}`)
    await waitForHydration(adminPage)
    await expect(adminPage.getByText(TITLE, { exact: true })).toBeVisible({ timeout: 30_000 })
    const deleteButton = adminPage.getByTestId('shift-schedule-delete')
    await expect(deleteButton).toBeVisible()
    await deleteButton.click()

    const dialog = adminPage.getByRole('alertdialog')
    await expect(dialog.getByText('シフト表を削除', { exact: true })).toBeVisible()
    const deleteResponse = adminPage.waitForResponse(
      (response) =>
        response.request().method() === 'DELETE' &&
        response.url().endsWith(`/api/v1/shifts/schedules/${scheduleId}`),
    )
    await dialog.getByRole('button', { name: '削除', exact: true }).click()
    expect((await deleteResponse).status(), 'UI経由のシフト表削除').toBe(204)
    deletedByUi = true
    await expect.poll(() => new URL(adminPage.url()).pathname).toBe('/shift')

    // 削除後の一覧を実UIで開き直し、対象が消えたことを確認する。
    await adminPage.goto(`/teams/${TEAM_SLUG}/shifts`)
    await waitForHydration(adminPage)
    await expect(adminPage.getByText('シフト管理', { exact: true })).toBeVisible({
      timeout: 30_000,
    })
    await expect(adminPage.getByText(TITLE, { exact: true })).toHaveCount(0)
    await adminPage.context().close()

    // MEMBER本人の画面では希望が履歴として残り、削除済み表示になって詳細リンクを出さない。
    const historyPage = await openAs(browser, MEMBER)
    await historyPage.goto('/my/shifts')
    await waitForHydration(historyPage)
    const historyRow = historyPage.getByTestId(`my-shift-request-${requestId}`)
    await expect(historyRow, '本人の希望履歴').toBeVisible({ timeout: 30_000 })
    await expect(historyPage.getByTestId(`my-shift-schedule-deleted-${requestId}`)).toHaveText(
      'シフト表は削除済みです',
    )
    await expect(historyRow.locator(`a[href="/shift/${scheduleId}"]`)).toHaveCount(0)
    await historyPage.context().close()
  } finally {
    if (scheduleId && adminToken && !deletedByUi) {
      const cleanup = await api.delete(`${API}/shifts/schedules/${scheduleId}`, {
        headers: headers(adminToken),
      })
      expect([204, 404], `シフト表 ${scheduleId} の後始末`).toContain(cleanup.status())
    }
    await api.dispose()
  }
})
