/**
 * 市の「札に応じる」申込フロー 実機E2E（PR #1941 根治確認）
 *
 * ── 根治した問題 ─────────────────────────────────────────────────────────────
 *   PR #1941 以前: FE が全募集枠を TEAM 参加と決め打ちして
 *     `{ participantType: 'TEAM' }` を送信していた。
 *     INDIVIDUAL 枠に対して送ると BE が PARTICIPATION_TYPE_MISMATCH (400) を返していた。
 *   PR #1941 修正: MarketListingResponse に participationType フィールドを追加し、
 *     FE が INDIVIDUAL 枠なら `{ participantType: 'USER' }` を送るよう変更。
 *
 * ── テスト戦略 ───────────────────────────────────────────────────────────────
 *   1. ADMIN が INDIVIDUAL 公開枠を API で作成・公開する（UI を使わず高速化）
 *   2. 一般ユーザー (e2e-user) がブラウザで /market/listings/{id} を開く
 *   3. 「札に応じる」ボタンをクリックする
 *   4. 申込 API レスポンスが 201（400 PARTICIPATION_TYPE_MISMATCH ではない）ことを確認
 *
 * テストID:
 *   MKT-APPLY-001  INDIVIDUAL公開枠への申込が201で成功する（400エラーなし・PR#1941根治確認）
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// 書き込み経路なので storageState に依存しない
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const API_BASE_URL = process.env.API_BASE_URL ?? BE
const PUBLIC_MARKET = `${BE_API}/public/market`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// 練習試合カテゴリ（seed-e2e-data の recruitment-categories マスタ id=9）
const CATEGORY_PRACTICE_MATCH = 9

// テスト全体を直列実行（セットアップ → テスト → クリーンアップ）
test.describe.configure({ mode: 'serial' })

interface LoginResult {
  accessToken: string
  userId: number
}

async function login(api: APIRequestContext, email: string, password: string): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string; userId: number } }
  return { accessToken: json.data.accessToken, userId: json.data.userId }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function resolveAdminTeamId(api: APIRequestContext, token: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as { data: Array<{ id: number; name: string; role: string }> }
  const adminTeam =
    json.data.find((t) => t.role === 'ADMIN' && t.name.includes('FC東京U-18')) ??
    json.data.find((t) => t.role === 'ADMIN')
  expect(adminTeam, 'ADMIN ロールのチームが存在する').toBeTruthy()
  return adminTeam!.id
}

let api: APIRequestContext
let adminToken: string
let adminTeamId: number
let listingId: number | null = null

test.beforeAll(async () => {
  api = await pwRequest.newContext()
})

test.afterAll(async () => {
  // テスト後に作成した枠をキャンセルしてクリーンアップ
  if (adminToken && listingId) {
    await api
      .post(`${BE_API}/recruitment-listings/${listingId}/cancel`, {
        headers: authHeaders(adminToken),
        data: { reason: 'e2e-market-apply cleanup' },
      })
      .catch(() => {})
  }
  await api.dispose()
})

// ──────────────────────────────────────────────────────────────────────────
// セットアップ: ADMIN ログイン + INDIVIDUAL 公開枠の作成・公開
// ──────────────────────────────────────────────────────────────────────────
test('セットアップ: ADMIN で INDIVIDUAL 公開枠を作成・公開する', async () => {
  // 1. ADMIN ログイン
  const result = await login(api, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = result.accessToken
  expect(adminToken.length).toBeGreaterThan(50)

  // 2. ADMIN のチーム ID を解決
  adminTeamId = await resolveAdminTeamId(api, adminToken)
  expect(adminTeamId).toBeGreaterThan(0)

  // 3. INDIVIDUAL 参加タイプ・PUBLIC 可視性の枠を作成（DRAFT で作成される）
  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      title: 'E2E市申込テスト INDIVIDUAL 公開枠',
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

  // 4. publish 前に配信対象 PUBLIC_FEED を設定する（必須: 0件のまま publish すると RECRUITMENT_204 で 400）
  const dtRes = await api.put(`${BE_API}/recruitment-listings/${listingId}/distribution-targets`, {
    headers: authHeaders(adminToken),
    data: { targetTypes: ['PUBLIC_FEED'] },
  })
  expect(dtRes.status(), '配信対象設定は 200').toBe(200)

  // 5. 公開（DRAFT → OPEN）
  const publishRes = await api.post(`${BE_API}/recruitment-listings/${listingId}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publishRes.status(), '公開は 200').toBe(200)

  // 5. 公開市 API で OPEN になっていることを確認
  let detailRes = await api.get(`${PUBLIC_MARKET}/listings/${listingId}`)
  for (let i = 0; i < 10 && detailRes.status() !== 200; i++) {
    await new Promise((r) => setTimeout(r, 500))
    detailRes = await api.get(`${PUBLIC_MARKET}/listings/${listingId}`)
  }
  expect(detailRes.status(), '公開市から 200 で取得できる').toBe(200)
  const detail = (await detailRes.json()) as { data: { status: string; participationType: string } }
  expect(detail.data.status, '枠のステータスが OPEN').toBe('OPEN')
  expect(detail.data.participationType, '枠の参加タイプが INDIVIDUAL').toBe('INDIVIDUAL')
})

// ──────────────────────────────────────────────────────────────────────────
// MKT-APPLY-001: INDIVIDUAL 公開枠への申込が 201 で成功する（根治確認）
// ──────────────────────────────────────────────────────────────────────────
test(
  'MKT-APPLY-001: 「札に応じる」ボタンクリックで申込 API が 201 を返す（400 PARTICIPATION_TYPE_MISMATCH ではない）',
  async ({ page }) => {
    test.setTimeout(120_000)
    expect(listingId, 'セットアップで枠が作成されていること').toBeTruthy()

    // e2e-user でブラウザログイン
    await page.context().clearCookies()
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })

    // 市の公開詳細ページへ遷移
    await page.goto(`/market/listings/${listingId}`)
    await waitForHydration(page)
    await expect(page.getByTestId('market-detail-card')).toBeVisible({ timeout: 20_000 })

    // 「札に応じる」ボタンが表示されることを確認
    const applyButton = page.getByRole('button', { name: '札に応じる', exact: true })
    await expect(applyButton, '「札に応じる」ボタンが表示される').toBeVisible({ timeout: 10_000 })

    // ボタンクリックと申込 API レスポンスを同時補足
    const [applyRes] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes(`/recruitment-listings/${listingId}/applications`) &&
          r.request().method() === 'POST',
        { timeout: 15_000 },
      ),
      applyButton.click(),
    ])

    // ── 根治確認の核心 ──
    // PR #1941 修正前: FE が TEAM 固定で送信 → BE が PARTICIPATION_TYPE_MISMATCH (400) を返す
    // PR #1941 修正後: FE が participationType=INDIVIDUAL を読み USER を送信 → 201 成功
    expect(
      applyRes.status(),
      '申込 API が 201 を返す（400 PARTICIPATION_TYPE_MISMATCH ではない＝PR #1941 根治の核心）',
    ).toBe(201)

    // 申込リクエストボディに participantType: 'USER' が含まれていることを確認
    const reqBody = applyRes.request().postDataJSON() as { participantType: string } | null
    if (reqBody) {
      expect(
        reqBody.participantType,
        'FE が INDIVIDUAL 枠に USER を送信している（TEAM 固定ではない）',
      ).toBe('USER')
    }

    // ページ上に 400 エラー表示がないことを確認
    await expect(page.locator('body')).not.toContainText('PARTICIPATION_TYPE_MISMATCH')
    await expect(page.locator('body')).not.toContainText('参加種別')
  },
)
