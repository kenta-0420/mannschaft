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

// ブラウザ認証・前提作成APIとも同じ接続先に揃える（market-apply.real.spec.ts と同じ戦略）。
// BE_ORIGIN（既定8080）はブラウザが向く先（API_BASE_URL、既定8081）と異なりうるため、
// API 呼び出しは全て API_BASE_URL 系に統一する。
const API_BASE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${API_BASE}/api/v1`
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

interface NotificationCreditBalance {
  creditBalance: number
  inGracePeriod: boolean
  gracePeriodEndsAt: string | null
}

// 通知クレジット枯渇状態にしてある検証用チーム（team_id=1 "fc-u-18"、organization_id=9）を明示特定する。
// フォールバックは行わない — 前提が崩れていれば画面側の確認が無意味になるため、
// 組織の残高APIで「残高0以下」かつ「猶予期間開始から72時間超過」を確認できない場合は即座に失敗させる。
async function resolveCreditExhaustedTeamId(api: APIRequestContext, token: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: { Authorization: `Bearer ${token}` } })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as {
    data: Array<{ id: number; name: string; role: string; organizationId: number | null }>
  }
  // "fc-u-18" はスラッグであり表示名ではない（表示名は「FC Tokyo U-18 Test」）。
  // 名前の部分一致ではなく、シード前提の team_id=1・organization_id=9 で明示特定する
  // （MyTeamResponse: id, organizationId, role フィールドを backend DTO で確認済み）。
  const team = json.data.find((t) => t.id === 1 && t.organizationId === 9 && t.role === 'ADMIN')
  if (!team) {
    throw new Error('検証用チーム team_id=1（organization_id=9・ADMINロール）が見つからない。E2Eシードを確認せよ。')
  }

  const balanceRes = await api.get(
    `${BE_API}/organizations/${team.organizationId}/notification-credits/balance`,
    { headers: { Authorization: `Bearer ${token}` } },
  )
  expect(balanceRes.status(), '通知クレジット残高APIは 200').toBe(200)
  const balanceJson = (await balanceRes.json()) as { data: NotificationCreditBalance }
  const balance = balanceJson.data
  // gracePeriodEndsAt は Java の LocalDateTime（タイムゾーン情報なし）を JSON シリアライズしたもの（例: "2026-10-05T12:00:00"）。
  // JVM 既定ゾーンは強制 JST のため値自体は JST の壁時計。オフセット無し文字列は `new Date()` でも
  // 実行環境のローカルタイムゾーンとして解釈されるため、実機E2E実行環境（Windows, JST）では一致し正しく比較できる。
  const graceExpired = balance.gracePeriodEndsAt !== null && new Date(balance.gracePeriodEndsAt).getTime() < Date.now()
  if (!(balance.creditBalance <= 0 && graceExpired)) {
    throw new Error(
      `fc-u-18 の通知クレジット前提（残高0以下かつ猶予期間72時間超過）が崩れている: ${JSON.stringify(balance)}`,
    )
  }
  return team.id
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

  // TEST_CONVENTION.md §2.4 方式2: 実行時点から相対生成する（固定日付は日跨ぎでflaky化するため禁則）。
  // 制約: 開催開始 < 開催終了、申込締切 > 自動キャンセル判定の基準（自動キャンセルは締切より先に来てはならない）。
  const now = Date.now()
  const startAt = new Date(now + 10 * 24 * 60 * 60 * 1000)
  const endAt = new Date(now + 10 * 24 * 60 * 60 * 1000 + 3 * 60 * 60 * 1000)
  const applicationDeadline = new Date(now + 8 * 24 * 60 * 60 * 1000)
  const autoCancelAt = new Date(now + 7 * 24 * 60 * 60 * 1000)
  const toLocalDateTime = (d: Date) => d.toISOString().slice(0, 19)

  const createRes = await api.post(`${BE_API}/teams/${teamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      title: 'E2E申込テスト（通知枠検証用）',
      categoryId: CATEGORY_PRACTICE_MATCH,
      participationType: 'INDIVIDUAL',
      startAt: toLocalDateTime(startAt),
      endAt: toLocalDateTime(endAt),
      applicationDeadline: toLocalDateTime(applicationDeadline),
      autoCancelAt: toLocalDateTime(autoCancelAt),
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
  // （検証対象はトーストに限定する。募集タイトル等には「クレジット」等の語を含めないため、
  //   ページ全体を対象にしても本来誤検知しないが、トーストの文言変化に強くするため明示的に絞る）
  const toast = page.locator('.p-toast')
  await expect(toast).toContainText('申込')
  await expect(toast.getByText(/クレジット|credit/i)).toHaveCount(0)
})
