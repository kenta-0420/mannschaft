/**
 * F03.11.1 キャンセル料の免除画面 実機E2E（CMP-024）
 *
 * 対象: `/me/recruitment-cancellation-fees`（一覧＋免除）
 *       `RecruitmentCancellationFeeWaiveModal.vue`（確認モーダル）
 *
 * ── テストデータの作り方 ─────────────────────────────────────────────────────
 *   未払いのキャンセル料記録は「有料の募集に個人で申し込み → キャンセル」で作られる
 *   （`RecruitmentParticipantService.cancelMyApplication`）。
 *   fee > 0 にするには、募集にキャンセルポリシーを設定し、free_until_hours_before より
 *   近い時刻で（＝ tier が発火する時刻に）キャンセルする必要がある。
 *
 *   セットアップは market-apply.real.spec.ts に倣い API で高速に行う（UI を使わず）:
 *     1. ADMIN（e2e-dummy-1, Team 1 の ADMIN）でキャンセルポリシーを作成
 *        （free_until_hours_before=200h, tier1: 100h 以内で 50%）
 *     2. paymentEnabled=true / price=2000 / payeeKind=TEAM の INDIVIDUAL 公開枠を作成・公開
 *        （startAt は現在+2h なので、直後の申込・即キャンセルは tier1（50%）に必ず該当する）
 *     3. 申込者（デフォルトは e2e-dummy-2。理由は下記）がブラウザ経由で申込 → キャンセル
 *        （UI 導線・fee 承諾込み）
 *     4. これで PENDING のキャンセル料記録が 1 件できる。受取先が TEAM payeeKind のため、
 *        e2e-admin（そのチームの ADMIN＝支払い管理権限者）が免除できる。
 *
 * ── 申込者に e2e-user ではなく e2e-dummy-2 を使う理由 ─────────────────────────
 *   `RecruitmentParticipantService.apply` は「未払いのキャンセル料が残っているユーザーは
 *   有料募集へ申込めない」ブロック（RECRUITMENT_301 CANCELLATION_PAYMENT_FAILED）を持つ
 *   （§10.0・ユーザー単位の判定）。本 spec の開発中の手動検証で e2e-user に PENDING の
 *   キャンセル料記録が残ってしまい（免除 API が当時の BE に未デプロイで免除できず）、
 *   e2e-user は本 spec 作成時点で既にこのブロックを踏んだ状態になっている。
 *   他の実機E2E が e2e-user の「有料募集に申込める」前提を壊さないよう、本 spec は
 *   申込者に e2e-dummy-2（MEMBER・別アカウント）を使う。
 *
 * ── 踏めないこと ────────────────────────────────────────────────────────────
 *   徴収そのもの（Stripe の部分キャプチャ／差額返金）は実機で踏めない
 *   （Stripe テストキー・payouts 有効な Connect 実口座が必要）。
 *   本 spec は「免除」導線（債権放棄・UI 検証）のみを対象にする。
 *
 * ── 既知の失敗（2026-08-13 検分時点） ─────────────────────────────────────────
 *   CMP024-001 の最終アサーション（`GET /api/v1/recruitment-cancellation-records`）と
 *   CMP024-002・CMP024-003 全体が、検証時点で稼働していた共有 BE（localhost:8080）が
 *   `RecruitmentCancellationRecordController`（本機能で新設・7ef2f604e で main に merge）を
 *   含まない古いビルドだったため 404 で落ちた。申込・キャンセルの UI 導線（AUTH-001・
 *   CMP024-001 の申込〜キャンセル確定まで）は実機で緑を確認済み。最新の main を再ビルド・
 *   再起動した BE に対して実行すれば通るはずである（このテスト自体を緩めてはならない）。
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// 書き込み経路なので storageState に依存しない
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const API_BASE_URL = process.env.API_BASE_URL ?? BE

// e2e-admin は SYSTEM_ADMIN であり、チーム単位の role は ADMIN ではない。
// TEAM scope の作成・免除と READY な Connect 口座を一貫して使える Team 1 ADMIN を既定にする。
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const APPLICANT_EMAIL = 'e2e-dummy-2@test.mannschaft.local'
const APPLICANT_PASSWORD = 'TestPass2026!'

// 練習試合カテゴリ（seed-e2e-data の recruitment-categories マスタ id=9）
const CATEGORY_PRACTICE_MATCH = 9

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
  const res = await api.get(`${BE_API}/me/teams`, { headers: { Authorization: `Bearer ${token}` } })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as { data: Array<{ id: number; name: string; role: string }> }
  const adminTeam =
    json.data.find((t) => t.role === 'ADMIN' && t.id === 1) ??
    json.data.find((t) => t.role === 'ADMIN')
  expect(adminTeam, 'ADMIN ロールのチームが存在する').toBeTruthy()
  return adminTeam!.id
}

// BE は JVM 既定ゾーン(JST)で LocalDateTime を解釈するため、UTC epoch を JST 壁時計表記に変換する
// （project memory: project_jvm_default_zone_forced_jst_blocks_tenant_tz 参照）。
function toJstWallClock(d: Date): string {
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(d)
  const get = (t: string) => parts.find((p) => p.type === t)!.value
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}:${get('second')}`
}

let api: APIRequestContext
let adminToken: string
let adminTeamId: number
let policyId: number | null = null
let listingId: number | null = null

test.beforeAll(async () => {
  api = await pwRequest.newContext()

  const admin = await login(api, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = admin.accessToken
  // 申込者のログイン可否だけをここで早期に検証する（login() 内の expect が 200 を保証する）。
  // 実際の申込・キャンセルは CMP024-001 がブラウザ経由（loginViaApi）で行うため、
  // ここで取得した accessToken 自体は使わない。
  await login(api, APPLICANT_EMAIL, APPLICANT_PASSWORD)

  adminTeamId = await resolveAdminTeamId(api, adminToken)

  // 1. キャンセルポリシー作成（100h 以内で 50% のキャンセル料）
  const policyRes = await api.post(`${BE_API}/teams/${adminTeamId}/cancellation-policies`, {
    headers: authHeaders(adminToken),
    data: {
      policyName: 'E2E CMP024 免除テスト用ポリシー',
      freeUntilHoursBefore: 200,
      isTemplatePolicy: false,
      tiers: [{ tierOrder: 1, appliesAtOrBeforeHours: 100, feeType: 'PERCENTAGE', feeValue: 50 }],
    },
  })
  expect(policyRes.status(), 'ポリシー作成は 201').toBe(201)
  policyId = ((await policyRes.json()) as { data: { id: number } }).data.id

  // 2. 有料 INDIVIDUAL 公開枠を作成（startAt は現在+2h。tier1(<=100h) に必ず該当させる）
  const now = new Date()
  const startAt = toJstWallClock(new Date(now.getTime() + 2 * 3600 * 1000))
  const endAt = toJstWallClock(new Date(now.getTime() + 3 * 3600 * 1000))
  const applicationDeadline = toJstWallClock(new Date(now.getTime() + 1.5 * 3600 * 1000))
  const autoCancelAt = toJstWallClock(new Date(now.getTime() + 1 * 3600 * 1000))

  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      title: 'E2E CMP024 キャンセル料免除テスト枠',
      categoryId: CATEGORY_PRACTICE_MATCH,
      participationType: 'INDIVIDUAL',
      startAt,
      endAt,
      applicationDeadline,
      autoCancelAt,
      capacity: 5,
      minCapacity: 1,
      paymentEnabled: true,
      price: 2000,
      payeeKind: 'TEAM',
      cancellationPolicyId: policyId,
      visibility: 'PUBLIC',
    },
  })
  expect(createRes.status(), '募集枠作成は 201').toBe(201)
  listingId = ((await createRes.json()) as { data: { id: number } }).data.id

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
  if (adminToken && listingId) {
    // 後始末（作成した募集枠のキャンセル）は best-effort とする。
    // ここで投げ直すと、既に成功しているテスト本体のアサーション結果まで巻き添えで失敗表示に
    // なってしまい、「何が壊れたか」の切り分けを難しくする。ただし黙って握り潰すと、後始末が
    // 効いていないこと自体に誰も気づけなくなる（次回実行時の listingId 重複等で症状だけが
    // 後から出る）ため、console.warn で必ず表面化させる。
    await api
      .post(`${BE_API}/recruitment-listings/${listingId}/cancel`, {
        headers: authHeaders(adminToken),
        data: { reason: 'e2e-cmp024-waive cleanup' },
      })
      .catch((e: unknown) => {
        console.warn(`E2E後始末: 募集枠 ${listingId} のキャンセルに失敗（best-effort・テスト結果には影響しない）`, e)
      })
  }
  await api.dispose()
})

// ──────────────────────────────────────────────────────────────────────────
// AUTH-001: 未認証で開くとログインへ飛ばされる（middleware: 'auth'）
// ──────────────────────────────────────────────────────────────────────────
test('AUTH-001: 未認証で /me/recruitment-cancellation-fees を開くとログインへリダイレクトされる', async ({ page }) => {
  await page.context().clearCookies()
  await page.goto('/me/recruitment-cancellation-fees')
  await page.waitForURL(/\/login/, { timeout: 15_000 })
  expect(page.url()).toContain('/login')
})

// ──────────────────────────────────────────────────────────────────────────
// CMP024-001: 申込 → キャンセル（UI）でキャンセル料記録が作られる
// ──────────────────────────────────────────────────────────────────────────
test('CMP024-001: 申込者が UI から申込・キャンセルし、fee>0 のキャンセル料記録が作られる', async ({ page }) => {
  test.setTimeout(120_000)
  expect(listingId, 'セットアップで枠が作成されていること').toBeTruthy()

  await page.context().clearCookies()
  await loginViaApi(page, { email: APPLICANT_EMAIL, password: APPLICANT_PASSWORD }, { apiBaseUrl: API_BASE_URL })

  // 申込・キャンセルの UI（RecruitmentApplicationButton）は /recruitment-listings/{id} にある
  // （/market/listings/{id} は公開市の閲覧・申込専用ページで、キャンセル導線を持たない別ページ）。
  await page.goto(`/recruitment-listings/${listingId}`)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'E2E CMP024 キャンセル料免除テスト枠' })).toBeVisible({ timeout: 20_000 })

  // 申込
  const applyButton = page.getByRole('button', { name: '申込', exact: true })
  await expect(applyButton).toBeVisible({ timeout: 10_000 })
  const [applyRes] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(`/recruitment-listings/${listingId}/applications`) && r.request().method() === 'POST',
      { timeout: 15_000 },
    ),
    applyButton.click(),
  ])
  expect(applyRes.status(), '申込 API は 201').toBe(201)

  // 有料募集は応募者本人の支払い確認ダイアログを自動表示する。
  // 本 spec の対象はキャンセル料免除であり、Stripe 与信そのものではないため、
  // 導線が応募者側に表示されたことを確認して閉じ、キャンセル操作へ進む。
  const paymentDialog = page.getByRole('dialog', { name: '謝礼のお支払い確認' })
  await expect(paymentDialog).toBeVisible({ timeout: 15_000 })
  await paymentDialog.getByRole('button', { name: 'キャンセル', exact: true }).click()
  await expect(paymentDialog).not.toBeVisible({ timeout: 10_000 })

  // キャンセル（見積り取得 → 確認モーダル → 同意してキャンセル）
  const cancelButton = page.getByRole('button', { name: '申込をキャンセル', exact: true })
  await expect(cancelButton).toBeVisible({ timeout: 10_000 })
  await cancelButton.click()

  // RecruitmentCancellationConfirmModal が見積りを表示する（tier1 50% の 2000 円 → 1000 円）
  await expect(page.locator('body')).toContainText('¥1,000', { timeout: 10_000 })

  const agreeButton = page.getByRole('button', { name: '同意してキャンセル', exact: true })
  const [cancelRes] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(`/recruitment-listings/${listingId}/applications/me`) && r.request().method() === 'DELETE',
      { timeout: 15_000 },
    ),
    agreeButton.click(),
  ])
  expect(cancelRes.status(), 'キャンセル API は 200').toBe(200)

  // BE 側でキャンセル料記録が作られたことを API で裏取り（fee>0）
  const listRes = await api.get(`${BE_API}/recruitment-cancellation-records`, { headers: authHeaders(adminToken) })
  expect(listRes.status(), 'ADMIN から記録一覧が 200 で見える').toBe(200)
  const listJson = (await listRes.json()) as {
    data: Array<{ id: number; listingId: number; feeAmount: number; paymentStatus: string }>
  }
  const record = listJson.data.find((r) => r.listingId === listingId)
  expect(record, 'キャンセル料記録が一覧に存在する').toBeTruthy()
  expect(record!.feeAmount, 'キャンセル料は 0 より大きい（tier1 50% の 2000 円 → 1000 円）').toBeGreaterThan(0)
  expect(record!.paymentStatus, '未払い状態（PENDING/FAILED/UNCOLLECTIBLE のいずれか）').not.toBe('WAIVED')
})

// ──────────────────────────────────────────────────────────────────────────
// CMP024-002: 一覧・免除モーダルの文言・必須検証・免除実行
// ──────────────────────────────────────────────────────────────────────────
test('CMP024-002: ADMIN が一覧を開き、但し書きを確認し、理由必須のバリデーションを経て免除できる', async ({ page }) => {
  test.setTimeout(120_000)

  await page.context().clearCookies()
  await loginViaApi(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, { apiBaseUrl: API_BASE_URL })

  await page.goto('/me/recruitment-cancellation-fees')
  await waitForHydration(page)

  // 一覧に対象の記録が見える
  const targetCard = page.locator('text=E2E CMP024 キャンセル料免除テスト枠').first()
  await expect(targetCard, '一覧に未払いの記録が見える').toBeVisible({ timeout: 20_000 })

  // 免除ボタン → 確認モーダル
  // （このテストが作った記録は本 spec 内で一意なので、祖先 div の filter/last() のような
  //   壊れやすい絞り込みは使わず、ボタンをロールで直接特定する）
  const waiveButton = page.getByRole('button', { name: '免除する' }).first()
  await expect(waiveButton, '免除ボタンが見える').toBeVisible({ timeout: 10_000 })
  await waiveButton.click()

  const dialog = page.getByTestId('waive-confirm-dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // 文言検証: 「解除されます」と言い切っていないこと（「解除されません」の否定形であること）
  const message = page.getByTestId('waive-confirm-message')
  await expect(message).toContainText('他の未払いのキャンセル料が残っている場合')
  await expect(message).toContainText('募集への申込制限は解除されません')
  await expect(message).not.toContainText('申込制限が解除されます')

  // 理由未入力では確定できない（必須検証）
  const confirmButton = page.getByTestId('waive-confirm-button')
  await expect(confirmButton).toBeDisabled()
  await expect(page.getByTestId('waive-reason-error')).toBeVisible()

  // 理由を入力すると有効化される
  const reasonInput = page.getByTestId('waive-reason-input')
  await reasonInput.fill('E2E CMP024: 実機E2Eによる免除テスト')
  await expect(confirmButton).toBeEnabled({ timeout: 5_000 })

  // 確定 → 免除 API 呼び出し → 一覧から消える（既定絞り込みは免除可能な3状態のみ）
  const [waiveRes] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/recruitment-cancellation-records/') && r.url().includes('/waive') && r.request().method() === 'POST',
      { timeout: 15_000 },
    ),
    confirmButton.click(),
  ])
  expect(waiveRes.status(), '免除 API は 200').toBe(200)

  await expect(dialog).not.toBeVisible({ timeout: 10_000 })

  // BE 側で WAIVED になったことを裏取り
  const listRes = await api.get(`${BE_API}/recruitment-cancellation-records?status=WAIVED`, {
    headers: authHeaders(adminToken),
  })
  expect(listRes.status()).toBe(200)
  const listJson = (await listRes.json()) as {
    data: Array<{ listingId: number; paymentStatus: string }>
  }
  const waived = listJson.data.find((r) => r.listingId === listingId)
  expect(waived, '免除後は WAIVED 状態で一覧に見える').toBeTruthy()
  expect(waived!.paymentStatus).toBe('WAIVED')
})

// ──────────────────────────────────────────────────────────────────────────
// CMP024-003: 無関係な利用者には1件も見えない（他人の債権が見えない）
// ──────────────────────────────────────────────────────────────────────────
test('CMP024-003: 無関係な利用者（申込者本人・受取先ではない）には他人のキャンセル料記録が見えない', async ({ page }) => {
  await page.context().clearCookies()
  await loginViaApi(page, { email: APPLICANT_EMAIL, password: APPLICANT_PASSWORD }, { apiBaseUrl: API_BASE_URL })

  await page.goto('/me/recruitment-cancellation-fees')
  await waitForHydration(page)

  // 申込者は今回のキャンセル料の債務者であって受取先(TEAM ADMIN)ではないため、
  // 一覧には自分がキャンセルした記録を含め何も出ない（設計書 §12: 債務者向け一覧はスコープ外）。
  await expect(
    page.locator('text=E2E CMP024 キャンセル料免除テスト枠'),
    '受取先ではない利用者には対象の記録が見えない',
  ).not.toBeVisible({ timeout: 10_000 })
})
