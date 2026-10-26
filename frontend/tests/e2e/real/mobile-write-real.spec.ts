/**
 * 実機E2E: モバイル(390x844)での書き込み一気通貫(モックなし・実BE・DB永続化裏取り)
 * MWR-01: スケジュールのリストビュー行内から出欠回答 → API で myAttendanceStatus=ATTENDING を裏取り
 * MWR-02: チームチャット単ペインからメッセージ実送信 → リロード後も残存 → 後始末削除
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *   npx playwright test tests/e2e/real/mobile-write-real.spec.ts --config playwright-real.config.ts
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { loginViaApi } from '../fixtures/auth'

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const RSVP_TEAM_SLUG = 'team-000092'
const CHAT_TEAM_SLUG = 'team-000092'
const CHAT_CHANNEL = '全体連絡'

test.use({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true })

async function getToken(request: APIRequestContext, cred: { email: string, password: string }): Promise<string> {
  const res = await request.post(`${API}/api/v1/auth/login`, { data: cred, headers: { 'Content-Type': 'application/json' } })
  expect(res.ok(), `APIログイン(${cred.email}): ${res.status()}`).toBe(true)
  const body = await res.json()
  return body?.data?.accessToken as string
}

test.describe('MWR: モバイル書き込み実機E2E', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(180_000)

  test.beforeEach(async ({ page }) => {
    await loginViaApi(page, USER, { apiBaseUrl: API })
  })

  test('MWR-01: リストビュー行内の出欠回答が DB に永続化される', async ({ page, request }) => {
    const adminToken = await getToken(request, ADMIN)
    const title = `実機出欠検証 ${Date.now()}`
    const start = new Date(); start.setDate(start.getDate() + 7); start.setHours(10, 0, 0, 0)
    const end = new Date(start); end.setHours(11, 0, 0, 0)
    const createRes = await request.post(`${API}/api/v1/teams/${RSVP_TEAM_SLUG}/schedules`, {
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      data: { title, startAt: start.toISOString(), endAt: end.toISOString(), allDay: false, eventType: 'PRACTICE', attendanceRequired: true },
    })
    expect(createRes.ok(), `予定作成: ${createRes.status()} ${await createRes.text().catch(() => '')}`).toBe(true)
    const scheduleId: number = (await createRes.json())?.data?.id
    expect(scheduleId).toBeTruthy()
    try {
      await page.goto(`/teams/${RSVP_TEAM_SLUG}/schedule`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await expect(page.getByTestId('schedule-list-view')).toBeVisible({ timeout: 20_000 })
      const row = page.getByTestId('schedule-list-row').filter({ hasText: title }).first()
      await row.scrollIntoViewIfNeeded()
      await expect(row).toBeVisible({ timeout: 15_000 })
      await row.getByRole('button', { name: '出席' }).click()
      const userToken = await getToken(request, USER)
      await expect.poll(async () => {
        const res = await request.get(`${API}/api/v1/teams/${RSVP_TEAM_SLUG}/schedules/${scheduleId}`, { headers: { Authorization: `Bearer ${userToken}` } })
        if (!res.ok()) return `HTTP ${res.status()}`
        const data = (await res.json())?.data
        return data?.myAttendanceStatus ?? null
      }, { timeout: 15_000 }).toBe('ATTENDING')
      await page.reload({ waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await expect(page.getByTestId('schedule-list-row').filter({ hasText: title }).first()).toBeVisible({ timeout: 20_000 })
    } finally {
      await request.delete(`${API}/api/v1/teams/${RSVP_TEAM_SLUG}/schedules/${scheduleId}`, { headers: { Authorization: `Bearer ${adminToken}` } }).catch(() => {})
    }
  })

  test('MWR-02: チーム単ペインチャットからの実送信がリロード後も永続する', async ({ page }) => {
    const body = `実機モバイル送信 ${Date.now()}`
    async function openChannelMobile(p: Page): Promise<void> {
      const item = p.locator('aside').getByText(CHAT_CHANNEL, { exact: false }).first()
      await item.waitFor({ state: 'visible', timeout: 20_000 })
      await item.click()
      await expect(p.locator('[data-testid="team-chat-input"]').first()).toBeVisible({ timeout: 15_000 })
    }
    await page.goto(`/teams/${CHAT_TEAM_SLUG}/chat`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await openChannelMobile(page)
    const input = page.locator('[data-testid="team-chat-input"]').first()
    await input.click(); await input.fill(body)
    await page.locator('[data-testid="chat-send-btn"]').first().click()
    await expect(page.locator('[data-testid="chat-message"]').filter({ hasText: body })).toBeVisible({ timeout: 10_000 })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await openChannelMobile(page)
    await expect(page.locator('[data-testid="chat-message"]').filter({ hasText: body })).toBeVisible({ timeout: 10_000 })
    try {
      const bubble = page.locator('[data-testid="chat-message"]').filter({ hasText: body }).last()
      await bubble.click({ button: 'right' })
      const menu = page.locator('[data-testid="chat-context-menu"]')
      await menu.waitFor({ state: 'visible', timeout: 4_000 })
      const del = page.locator('[data-testid="context-menu-item"][data-key="delete"]')
      if (await del.isVisible().catch(() => false)) { await del.click(); await page.waitForTimeout(500) }
    } catch { /* クリーンアップ失敗は無視 */ }
  })
})
