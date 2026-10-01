import { expect, test, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// CMP-260930-1932 実機E2E 補強: 申込操作を画面のボタン操作で行う（API代替しない）。
// E2E-1: クレジット枯渇組織（team_id=1 "fc-u-18"、organization_id=9）の募集に
// e2e-user が画面から申込み、クレジット不足エラーが出ずに申込が成功することを画面で確認する。
//
// 修繕: 以前は固定の listingId（env 既定値）へ毎回申込んでいたため、
// 一度参加者行が永続化されると2回目以降は申込ボタンが出ず必ず失敗していた
// （TEST_CONVENTION.md「各テストは独立実行可能」違反）。
// テストごとに新しい募集枠を API で作成・公開してから画面申込みすることで、
// 繰り返し実行できるようにする（market-apply.real.spec.ts と同じ戦略）。

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const API_BASE = process.env.API_BASE_URL ?? BE
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'

// 練習試合カテゴリ（seed-e2e-data の recruitment-categories マスタ id=9）
const CATEGORY_PRACTICE_MATCH = 9

interface LoginResult {
  accessToken: string
}

async function login(api: APIRequestContext, email: string, password: string): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string } }
  return { accessToken: json.data.accessToken }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function resolveCreditExhaustedTeamId(api: APIRequestContext, token: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: { Authorization: `Bearer ${token}` } })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as { data: Array<{ id: number; name: string; role: string }> }
  // 通知クレジット枯渇状態にしてある検証用チーム（fc-u-18）。無ければ ADMIN ロールの先頭チームで代替する。
  const team =
    json.data.find((t) => t.role === 'ADMIN' && t.name.includes('fc-u-18')) ??
    json.data.find((t) => t.role === 'ADMIN' && t.name.includes('FC東京U-18')) ??
    json.data.find((t) => t.role === 'ADMIN')
  expect(team, 'ADMIN ロールのチームが存在する').toBeTruthy()
  return team!.id
}

async function loginForRealDevice(page: Page, email: string) {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
  const pageHost = new URL(process.env.BASE_URL ?? 'http://localhost:3001').hostname
  const apiHost = new URL(API_BASE).hostname
  if (pageHost === apiHost) return
  const apiCookies = await page.context().cookies(API_BASE)
  await page.context().addCookies(apiCookies.map(cookie => ({ ...cookie, domain: pageHost })))
}

async function waitForPageHydration(page: Page) {
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 150_000 },
  )
}

let api: APIRequestContext
let adminToken: string
let listingId: number | null = null

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  const admin = await login(api, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = admin.accessToken
  const teamId = await resolveCreditExhaustedTeamId(api, adminToken)

  const createRes = await api.post(`${BE_API}/teams/${teamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      title: 'E2E申込テスト（クレジット枯渇組織）',
      categoryId: CATEGORY_PRACTICE_MATCH,
      participationType: 'INDIVIDUAL',
      startAt: '2026-12-20T09:00:00',
      endAt: '2026-12-20T12:00:00',
      applicationDeadline: '2026-12-18T23:59:59',
      autoCancelAt: '2026-12-18T23:59:59',
      capacity: 5,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
    },
  })
  expect(createRes.status(), '募集枠作成は 201').toBe(201)
  const created = (await createRes.json()) as { data: { id: number } }
  listingId = created.data.id

  const dtRes = await api.put(`${BE_API}/recruitment-listings/${listingId}/distribution-targets`, {
    headers: authHeaders(adminToken),
    data: { targetTypes: ['PUBLIC_FEED'] },
  })
  expect(dtRes.status(), '配信対象設定は 200').toBe(200)

  const publishRes = await api.post(`${BE_API}/recruitment-listings/${listingId}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publishRes.status(), '公開は 200').toBe(200)
})

test.afterAll(async () => {
  // テスト後に作成した枠をキャンセルしてクリーンアップ
  if (adminToken && listingId) {
    await api
      .post(`${BE_API}/recruitment-listings/${listingId}/cancel`, {
        headers: authHeaders(adminToken),
        data: { reason: 'e2e-cmp-260930-1932-ui-apply cleanup' },
      })
      .catch(() => {})
  }
  await api.dispose()
})

test('E2E-1: 画面の申込ボタンから申込み、クレジット不足エラーが出ず成功表示になる', async ({ page }) => {
  expect(listingId, 'セットアップで枠が作成されていること').toBeTruthy()

  await loginForRealDevice(page, MEMBER)
  await page.goto(`/recruitment-listings/${listingId}`, { waitUntil: 'commit' })
  await waitForPageHydration(page)

  const applyButton = page.getByRole('button', { name: '申込', exact: true })
  await expect(applyButton).toBeVisible({ timeout: 15_000 })

  const applyResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/v1/recruitment-listings/${listingId}/applications`,
  )
  await applyButton.click()
  const response = await applyResponse
  expect(response.status(), `申込API応答: ${await response.text()}`).toBe(201)

  // クレジット不足系のエラートーストが出ていないこと、申込成功トーストが出ていることを画面で確認する
  await expect(page.getByText(/クレジット|credit/i)).toHaveCount(0)
  await expect(page.locator('.p-toast')).toContainText('申込')
})
