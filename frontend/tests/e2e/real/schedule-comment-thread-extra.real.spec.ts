/**
 * F03.16 追加確認: Codex是正で変わった3点のうち、正式10シナリオに含まれない2点を実機で確認する一時spec。
 *   - 通知リンク（/calendar?scheduleId=&commentId=）から対象コメントへ辿り着けること
 *   - 個人予定（isPersonal）ではコメント欄が出ないこと
 * 実機隊c が任務完了後に削除する想定の検証用ファイル。
 */
import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3090'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8090'
const TEAM_SLUG = 'fc-u-18'

const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }

test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } })

test.describe.configure({ mode: 'serial' })
test.setTimeout(90_000)

let scheduleId: number
const title = `EXTRA通知リンク検証-${Date.now()}`
let commentId: string

test.beforeAll(async ({ playwright }) => {
  const ctx = await playwright.request.newContext()
  const loginRes = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: E2E_ADMIN })
  expect(loginRes.ok()).toBeTruthy()
  const start = new Date(Date.now() + 5 * 60_000).toISOString()
  const end = new Date(Date.now() + 65 * 60_000).toISOString()
  const createRes = await ctx.post(`${API_BASE}/api/v1/teams/${TEAM_SLUG}/schedules`, {
    data: {
      title, description: null, location: 'E2E会場', startAt: start, endAt: end, allDay: false,
      eventType: 'PRACTICE', visibility: 'MEMBERS_ONLY', minViewRole: 'MEMBER_PLUS',
      minResponseRole: 'MEMBER_PLUS', attendanceRequired: false, commentOption: 'OPTIONAL',
    },
  })
  expect(createRes.ok(), await createRes.text()).toBeTruthy()
  scheduleId = (await createRes.json()).data.id

  const commentRes = await ctx.post(`${API_BASE}/api/v1/schedules/${scheduleId}/comments`, {
    data: { body: 'EXTRA通知リンク対象コメント', parentId: null, mentionedUserIds: [] },
  })
  expect(commentRes.ok(), await commentRes.text()).toBeTruthy()
  commentId = (await commentRes.json()).data.id
  await ctx.dispose()
})

test('EXTRA-1: 通知リンク(/calendar?scheduleId=&commentId=)から対象コメントへ辿り着ける', async ({ page }) => {
  await loginViaApi(page, E2E_ADMIN, { apiBaseUrl: API_BASE })
  await page.goto(`${BASE_URL}/calendar?scheduleId=${scheduleId}&commentId=${commentId}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 20_000 })
  await expect(section.getByText('EXTRA通知リンク対象コメント')).toBeVisible({ timeout: 15_000 })

  // ハイライト対象要素が実際にDOM上に存在すること（highlightedCommentId適用対象）
  const highlighted = page.locator(`[data-testid="schedule-comment-item-${commentId}"]`)
  await expect(highlighted).toBeVisible({ timeout: 10_000 })

  // 是正1: ハイライト処理完了後は commentId クエリが除去される
  await expect.poll(() => new URL(page.url()).searchParams.has('commentId'), { timeout: 10_000 }).toBe(false)
})

let personalTitle: string

test.beforeAll(async ({ playwright }) => {
  const ctx = await playwright.request.newContext()
  const loginRes = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: E2E_USER })
  expect(loginRes.ok()).toBeTruthy()
  personalTitle = `EXTRA個人予定検証-${Date.now()}`
  const start = new Date(Date.now() + 10 * 60_000).toISOString()
  const end = new Date(Date.now() + 70 * 60_000).toISOString()
  const res = await ctx.post(`${API_BASE}/api/v1/me/schedules`, {
    data: { title: personalTitle, description: null, startAt: start, endAt: end, allDay: false, eventType: 'OTHER' },
  })
  expect(res.ok(), await res.text()).toBeTruthy()
  await ctx.dispose()
})

test('EXTRA-2: 個人予定ではコメント欄(schedule-comment-section)が出ない', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await page.goto(`${BASE_URL}/calendar`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

  const row = page.getByText(personalTitle, { exact: false }).first()
  await expect(row, `個人予定「${personalTitle}」がカレンダーに見つかること`).toBeVisible({ timeout: 20_000 })
  await row.click()

  // 詳細パネルが開き、コメント欄(schedule-comment-section)が存在しないこと（EventDetailPanel.vue:
  // v-if="event.scheduleId !== null && event.scheduleId !== undefined" による非表示を実UIで確認）
  await expect(page.getByText(personalTitle, { exact: false }).first()).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('[data-testid="schedule-comment-section"]')).toHaveCount(0, { timeout: 10_000 })
})
