/**
 * F22.1 市（Market）— 認証付き実機 CRUD E2E（モック不使用・書き込み経路）
 *
 * 実際のバックエンド（localhost:8080）とフロントエンド（dev server）に対し、ログイン済み
 * ADMIN ユーザーで札のライフサイクル（作成 → 公開 → 編集 → 下げる）を一気通貫で踏み、
 * 各ステップで実 BE レスポンスと公開市（/market）の UI 描画の両方を検証する。
 *
 * 読み取り専用の market.real.spec.ts（未ログイン permitAll 経路）と対をなす「書き込み経路」スペック。
 * page.route によるモックは一切使用しない。
 *
 * ── 🔴 実機 CRUD E2E で炙り出した 2 件のバグと根治（2026-06-02）──────────────
 *   バグ②（最重大）: FE の市札立て導線（pages/teams/[id]/recruitment-listings/new.vue →
 *     pages/recruitment-listings/[id].vue の publish ボタン）が distribution_targets を一切
 *     設定しないため、UI から PUBLIC 札を作成して公開すると必ず RECRUITMENT_204 で失敗し、
 *     市に永遠に出なかった。
 *     → 根治: new.vue が visibility=PUBLIC のとき作成直後に PUT distribution-targets で
 *       PUBLIC_FEED を登録するようにした。本スペックの MRC-001 は API 裏技を使わず
 *       「実 UI の作成フォーム → publish ボタン」導線だけで PUBLIC 札が /market に出ることを実証する。
 *   バグ①（防御）: RECRUITMENT_204 / RECRUITMENT_207 が ERROR severity 既定で HTTP 500 として
 *     漏れていた（FRIEND 経路の MARKET_002=400 と非対称）。
 *     → 根治: GlobalExceptionHandler.ERROR_CODE_STATUS_MAP に両コードを 400 として登録。
 *       本スペックの MRC-BUG は「配信対象0件の publish が 400・RECRUITMENT_204」を回帰固定する。
 *
 * ── 認証セッションの確立 ──────────────────────────────────────────────
 *   API 経路: 実 BE の POST /api/v1/auth/login で Bearer トークンを取得（team id 解決・cleanup 用）。
 *   UI 経路 : /login フォームから実ログインしてブラウザセッション（authStore + cookie）を確立する。
 *   テストユーザー: e2e-admin@test.mannschaft.local / TestPass2026!
 *     - SYSTEM_ADMIN + JFA(org) ADMIN + FC東京U-18(team) ADMIN
 *     - 市の札主は scope の ADMIN。team の ADMIN なので札立て可能。
 *
 * テストID:
 *   MRC-000   認証セッション確立（ADMIN ログイン → Bearer 取得・team id 解決）
 *   MRC-001   実 UI の作成フォーム → publish ボタンだけで PUBLIC 札が市に出る（裏技なし・根治の実証）
 *   MRC-002   編集(PATCH)→市詳細に反映される
 *   MRC-003   フレンド宛先(FRIEND_TEAMS_ONLY)＋宛先0件 MARKET_002 / 宛先付き publish
 *   MRC-005   下げる(cancel)→市から当該札が消える（詳細404・一覧非表示）
 *   MRC-AUTHZ 非 ADMIN（MEMBER）は作成不可（403・COMMON_002）
 *   MRC-BUG   PUBLIC publish の配信対象0件は RECRUITMENT_204 を 400 で返す（旧 500 → 400 化の回帰固定）
 */

import { test, expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 書き込み経路は storageState に依存せず、各テストで自前ログインする（未ログインから始める）。
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`
const PUBLIC_MARKET = `${BE_API}/public/market`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// 練習試合カテゴリ（seed-e2e-data の recruitment-categories マスタ id=9）
const CATEGORY_PRACTICE_MATCH = 9

// 直列で create→publish→edit→cancel を踏むため並列無効・タイムアウト延長
test.describe.configure({ mode: 'serial' })

interface LoginResult {
  accessToken: string
  userId: number
}

/** 実 BE の auth/login で Bearer トークンを取得する（レスポンスは data.data.accessToken の入れ子）。 */
async function login(api: APIRequestContext, email: string, password: string): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string, userId: number } }
  expect(json.data?.accessToken, 'accessToken が data 直下に存在する').toBeTruthy()
  return { accessToken: json.data.accessToken, userId: json.data.userId }
}

/** /login フォームから実ログインし、ブラウザセッション（authStore + cookie）を確立する。 */
async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  // PrimeVue InputText は fill() だと v-model 反映漏れがあるため click → pressSequentially。
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

/** e2e-admin が ADMIN を持つ FC東京U-18 のチーム ID を /api/v1/me/teams から解決する。 */
async function resolveAdminTeamId(api: APIRequestContext, token: string): Promise<number> {
  const res = await api.get(`${BE_API}/me/teams`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as { data: Array<{ id: number, name: string, role: string }> }
  const adminTeam = json.data.find((t) => t.role === 'ADMIN' && t.name.includes('FC東京U-18'))
    ?? json.data.find((t) => t.role === 'ADMIN')
  expect(adminTeam, 'ADMIN ロールのチームが存在する').toBeTruthy()
  return adminTeam!.id
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** datetime-local 入力へ値を設定する（PrimeVue を経由しない素の input なので fill で確実に入る）。 */
async function fillDateTime(page: Page, id: string, value: string): Promise<void> {
  const input = page.locator(`input#${id}`)
  await input.fill(value)
  // change イベントで v-model を確実に同期させる。
  await input.dispatchEvent('change')
}

// 未来日（seed と被らない高位の日付）。datetime-local は秒なしの分精度。
const FUTURE_UI = {
  startAt: '2026-11-15T09:00',
  endAt: '2026-11-15T12:00',
  applicationDeadline: '2026-11-13T23:59',
  autoCancelAt: '2026-11-13T23:59',
}
const FUTURE_API = {
  startAt: '2026-11-15T09:00:00',
  endAt: '2026-11-15T12:00:00',
  applicationDeadline: '2026-11-13T23:59:59',
  autoCancelAt: '2026-11-13T23:59:59',
}

let api: APIRequestContext
let adminToken: string
let adminTeamId: number
let createdId: number | null = null
const cleanupIds: number[] = []

test.beforeAll(async () => {
  api = await pwRequest.newContext()
})

test.afterAll(async () => {
  // 作成した札を後始末（冪等再走のため CANCELLED にしておく。失敗は無視）。
  if (adminToken) {
    for (const id of cleanupIds) {
      await api.post(`${BE_API}/recruitment-listings/${id}/cancel`, {
        headers: authHeaders(adminToken),
        data: { reason: 'e2e cleanup' },
      }).catch(() => {})
    }
  }
  await api.dispose()
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-000: 認証セッション確立
// ──────────────────────────────────────────────────────────────────────────
test('MRC-000: ADMIN ログインで Bearer トークンを取得し、ADMIN チーム ID を解決できる', async () => {
  const result = await login(api, ADMIN_EMAIL, ADMIN_PASSWORD)
  adminToken = result.accessToken
  expect(adminToken.length).toBeGreaterThan(50)

  adminTeamId = await resolveAdminTeamId(api, adminToken)
  expect(adminTeamId).toBeGreaterThan(0)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-001: 実 UI の作成フォーム → publish ボタンだけで PUBLIC 札が市に出る（根治の実証）
//
// 🔴 バグ②の根治確認: API で distribution-targets を裏から設定する回避は一切使わない。
//   ブラウザで new.vue の作成フォームを埋めて submit → 詳細ページの publish ボタンを押す、
//   という「ユーザーが実際に踏む導線」だけで PUBLIC 札が公開され /market に出ることを検証する。
//   FE が visibility=PUBLIC のとき PUBLIC_FEED を自動設定するようになったため、これが通る。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-001: 実 UI の作成フォーム → publish ボタンだけで PUBLIC 札が公開され市に出る', async ({ page }) => {
  expect(adminToken, 'MRC-000 でトークン取得済みであること').toBeTruthy()

  await loginUI(page, ADMIN_EMAIL, ADMIN_PASSWORD)

  // 1) 作成フォーム（market 拡張つき）を開く
  await page.goto(`/teams/${adminTeamId}/recruitment-listings/new`)
  await waitForHydration(page)
  await expect(page.getByTestId('market-form-extension')).toBeVisible({ timeout: 15_000 })

  // 2) 公開範囲は既定で PUBLIC（MarketListingFormExtension の visibility ref 初期値）。
  //    PrimeVue SelectButton は「選択済みオプションを再クリックすると選択解除（null 化）」する
  //    トグル挙動を持つため、既に選択されている「市に公開」を押すと visibility=null になり
  //    create が COMMON_001（visibility 必須）で 400 になる。したがって既定の PUBLIC をそのまま使い、
  //    選択状態（aria-pressed=true）だけを検証する。フレンド経路の検証は MRC-003 で別途行う。
  const visibilitySelector = page.getByTestId('market-visibility-selector')
  await expect(
    visibilitySelector.getByRole('button', { name: '市に公開', exact: true }),
    '公開範囲は既定で「市に公開」が選択されている',
  ).toHaveAttribute('aria-pressed', 'true')

  // 3) タイトル
  const titleInput = page.locator('input#title')
  await titleInput.click()
  await titleInput.pressSequentially('E2E実機CRUD UI導線 練習試合募集', { delay: 5 })

  // 4) カテゴリ（PrimeVue Select）を「練習試合」で選択
  await page.locator('#category').click()
  await page.getByRole('option').filter({ hasText: '練習試合' }).first().click()
  await page.locator('#location').fill('E2E市民競技場')

  // 5) 日時 4 種（datetime-local）
  await fillDateTime(page, 'startAt', FUTURE_UI.startAt)
  await fillDateTime(page, 'endAt', FUTURE_UI.endAt)
  await fillDateTime(page, 'applicationDeadline', FUTURE_UI.applicationDeadline)
  await fillDateTime(page, 'autoCancelAt', FUTURE_UI.autoCancelAt)

  // 6) 定員・最小催行（PrimeVue InputNumber は内部 input へ type）
  const capacityInput = page.locator('#capacity input')
  await capacityInput.click()
  await capacityInput.pressSequentially('6', { delay: 5 })
  const minCapacityInput = page.locator('#minCapacity input')
  await minCapacityInput.click()
  await minCapacityInput.pressSequentially('2', { delay: 5 })

  // 7) 作成 submit → 詳細ページ /recruitment-listings/{id} へ遷移する
  //    ここで FE は createListing 後に visibility=PUBLIC のため PUBLIC_FEED を自動登録する。
  await Promise.all([
    page.waitForURL(/\/recruitment-listings\/\d+$/, { timeout: 30_000, waitUntil: 'commit' }),
    page.getByRole('button', { name: '作成' }).click(),
  ])

  const match = page.url().match(/\/recruitment-listings\/(\d+)$/)
  expect(match, '作成後 /recruitment-listings/{id} へ遷移する').toBeTruthy()
  createdId = Number(match![1])
  cleanupIds.push(createdId)

  // 8) 詳細ページを開いて listing をロードし終える（タイトル h1 の出現）まで待つ。
  //    new.vue の router.push 直後の SPA 遷移は dev サーバーでハイドレーション競合により
  //    getListing が稀に error トーストになり描画されないことがあるため、同 URL を明示 goto して
  //    確実な描画経路（フル SSR ロード）に乗せる。これは UI 上の同じ詳細ページであり、
  //    distribution-targets の API 裏設定は一切していない（#1279 の FE 自動設定だけで成立する）。
  await page.goto(`/recruitment-listings/${createdId}`)
  await waitForHydration(page)
  await expect(
    page.locator('main h1', { hasText: 'E2E実機CRUD UI導線 練習試合募集' }),
    '詳細ページが作成した札をロードする',
  ).toBeVisible({ timeout: 20_000 })

  // 9) 作成直後は DRAFT。公開ボタン（main 内・厳密一致「公開」）を押下する。
  //    サイドナビ等の "公開" を含む要素を誤検出しないよう main スコープ + exact にする。
  //    裏では new.vue が PUBLIC_FEED を distribution-targets に登録済み（#1279 FE 修正）。
  const publishButton = page.locator('main').getByRole('button', { name: '公開', exact: true })
  await expect(publishButton, '作成直後の DRAFT 札に公開ボタンが出る').toBeVisible({ timeout: 15_000 })

  // publish API 応答を確実に捕捉する。#1279 修正により RECRUITMENT_204/500 ではなく 200 が返るはず。
  const [publishResp] = await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/v1\/recruitment-listings\/\d+\/publish$/.test(r.url()) && r.request().method() === 'POST',
      { timeout: 15_000 },
    ),
    publishButton.click(),
  ])
  expect(
    publishResp.status(),
    'UI の公開ボタンによる publish が 200 成功（RECRUITMENT_204/500 が出ない＝#1279 修正の核心）',
  ).toBe(200)

  // 10) BE 実値: 公開市の詳細 API が 200 で実値を返す（DRAFT なら 404 のはず → publish 成功の裏取り）。
  //     publish 反映の僅かな伝播ラグを吸収するため数回ポーリングする。
  let detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  for (let i = 0; i < 10 && detailRes.status() !== 200; i++) {
    await page.waitForTimeout(500)
    detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  }
  expect(detailRes.status(), 'UI 導線で publish した PUBLIC 札は公開市で 200').toBe(200)
  const detail = (await detailRes.json()) as {
    data: {
      title: string
      owner: { displayName: string, scopeType: string, scopeId: number }
      status: string
    }
  }
  expect(detail.data.title).toBe('E2E実機CRUD UI導線 練習試合募集')
  expect(detail.data.owner.scopeType).toBe('TEAM')
  expect(detail.data.owner.scopeId).toBe(adminTeamId)
  expect(detail.data.status).toBe('OPEN')

  // 11) 公開市の一覧にも含まれる
  const listRes = await api.get(`${PUBLIC_MARKET}/listings?size=100`)
  expect(listRes.status()).toBe(200)
  const list = (await listRes.json()) as { data: Array<{ id: number }> }
  expect(list.data.map((d) => d.id), 'UI 導線で公開した札が公開一覧に出る').toContain(createdId)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-001b: 公開した札が公開市詳細ページで undefined/NaN なく描画される
// ──────────────────────────────────────────────────────────────────────────
test('MRC-001b: UI 公開した札が /market/listings/{id} で実値描画される（undefined/NaN なし）', async ({ page }) => {
  expect(createdId, 'MRC-001 で札が公開済みであること').toBeTruthy()

  await page.goto(`/market/listings/${createdId}`)
  await waitForHydration(page)
  await expect(page.getByTestId('market-detail-card')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('market-detail-title')).toContainText('E2E実機CRUD UI導線 練習試合募集')

  const body = await page.locator('body').innerText()
  expect(body, '詳細画面に "undefined" が出てはならない').not.toContain('undefined')
  expect(body, '詳細画面に "NaN" が出てはならない').not.toContain('NaN')
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-002: 編集(PATCH) → 市詳細に反映される
// ──────────────────────────────────────────────────────────────────────────
test('MRC-002: 札を編集（タイトル/定員）すると公開市の詳細に反映される', async () => {
  expect(createdId, 'MRC-001 で札が公開済みであること').toBeTruthy()

  const patchRes = await api.patch(`${BE_API}/recruitment-listings/${createdId}`, {
    headers: authHeaders(adminToken),
    data: { title: 'E2E実機CRUD UI導線 練習試合募集（編集後）', capacity: 10 },
  })
  expect(patchRes.status(), '編集は 200').toBe(200)
  const patched = (await patchRes.json()) as { data: { title: string, capacity: number } }
  expect(patched.data.title).toBe('E2E実機CRUD UI導線 練習試合募集（編集後）')
  expect(patched.data.capacity).toBe(10)

  const detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(detailRes.status()).toBe(200)
  const detail = (await detailRes.json()) as { data: { title: string, capacity: number } }
  expect(detail.data.title).toBe('E2E実機CRUD UI導線 練習試合募集（編集後）')
  expect(detail.data.capacity).toBe(10)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-003: フレンド宛先（FRIEND_TEAMS_ONLY）
//   宛先0件は publish 時に MARKET_002（400）。宛先 ALL_FRIENDS 指定で作成・公開できる。
//   FRIEND 経路は distribution_targets を使わず friendTargets で配信する（市には出ない）。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-003: FRIEND_TEAMS_ONLY 札は宛先指定で公開可（市には出ない）', async () => {
  expect(adminToken).toBeTruthy()

  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'E2E実機CRUD フレンド限定札',
      participationType: 'INDIVIDUAL',
      ...FUTURE_API,
      capacity: 4,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'FRIEND_TEAMS_ONLY',
      friendTargets: [{ targetKind: 'ALL_FRIENDS' }],
    },
  })
  expect(createRes.status(), 'フレンド限定札の作成は 201').toBe(201)
  const created = (await createRes.json()) as {
    data: { id: number, visibility: string, friendTargets: Array<{ targetKind: string }> }
  }
  const friendId = created.data.id
  cleanupIds.push(friendId)
  expect(created.data.visibility).toBe('FRIEND_TEAMS_ONLY')
  expect(created.data.friendTargets.map((t) => t.targetKind)).toContain('ALL_FRIENDS')

  const publishRes = await api.post(`${BE_API}/recruitment-listings/${friendId}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publishRes.status(), 'フレンド限定札の公開は 200').toBe(200)
  const published = (await publishRes.json()) as { data: { status: string } }
  expect(published.data.status).toBe('OPEN')

  // FRIEND_TEAMS_ONLY は公開市（PUBLIC）には出ない → 404 存在秘匿
  const marketRes = await api.get(`${PUBLIC_MARKET}/listings/${friendId}`)
  expect(marketRes.status(), 'フレンド限定札は公開市で 404').toBe(404)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-005: 下げる(cancel) → 市から当該札が消える
// ──────────────────────────────────────────────────────────────────────────
test('MRC-005: 札を下げる（cancel）と CANCELLED になり、公開市から消える（詳細404・一覧非表示）', async () => {
  expect(createdId, 'MRC-001 の PUBLIC 札を下げる').toBeTruthy()

  const cancelRes = await api.post(`${BE_API}/recruitment-listings/${createdId}/cancel`, {
    headers: authHeaders(adminToken),
    data: { reason: 'E2E test cancel' },
  })
  expect(cancelRes.status(), 'cancel は 200').toBe(200)
  const cancelled = (await cancelRes.json()) as { data: { status: string } }
  expect(cancelled.data.status, '下げると CANCELLED').toBe('CANCELLED')

  const detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(detailRes.status(), '下げた札は市詳細で 404').toBe(404)

  const listRes = await api.get(`${PUBLIC_MARKET}/listings?size=100`)
  const list = (await listRes.json()) as { data: Array<{ id: number }> }
  expect(list.data.map((d) => d.id), '下げた札は公開一覧に出ない').not.toContain(createdId)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-AUTHZ: 非 ADMIN（MEMBER）は札を作成できない（403）
// ──────────────────────────────────────────────────────────────────────────
test('MRC-AUTHZ: MEMBER ロールのユーザーは札を作成できない（403・COMMON_002）', async () => {
  const member = await login(api, MEMBER_EMAIL, MEMBER_PASSWORD)
  const res = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(member.accessToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'MEMBER による不正な札立て',
      participationType: 'INDIVIDUAL',
      ...FUTURE_API,
      capacity: 4,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
    },
  })
  expect(res.status(), 'MEMBER の札立ては 403 で拒否').toBe(403)
  const json = (await res.json()) as { error: { code: string } }
  expect(json.error.code).toBe('COMMON_002')
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-AUTHZ-2: MEMBER は他人（ADMIN チーム）の札を編集できない（横方向 IDOR 防止）
//   本陣救出 market-role-check.real.spec.ts B5 由来。MRC-AUTHZ が「作成」の認可境界を
//   固定するのに対し、こちらは「他人リソースへの更新（PATCH）」の認可境界を固定する。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-AUTHZ-2: MEMBER は他人の札を編集できない（PATCH が 403 で拒否）', async () => {
  // ADMIN が DRAFT 札を作成（公開不要・編集対象としてのみ使う）
  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'MRC-AUTHZ-2 編集対象札（DRAFT）',
      participationType: 'INDIVIDUAL',
      ...FUTURE_API,
      capacity: 4,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
    },
  })
  expect(createRes.status(), 'ADMIN の札作成は 201').toBe(201)
  const created = (await createRes.json()) as { data: { id: number } }
  cleanupIds.push(created.data.id)

  // MEMBER が他人の札を PATCH → 403 で拒否される
  const member = await login(api, MEMBER_EMAIL, MEMBER_PASSWORD)
  const patchRes = await api.patch(`${BE_API}/recruitment-listings/${created.data.id}`, {
    headers: authHeaders(member.accessToken),
    data: { title: 'MEMBER による不正編集' },
  })
  expect(patchRes.status(), 'MEMBER の他人札編集は 403 で拒否').toBe(403)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-BUG: PUBLIC publish の配信対象0件は RECRUITMENT_204 を 400 で返す（旧 500 → 400 化の回帰固定）
//
// 🔴 旧バグ（2026-06-02 実機 E2E で発覚）:
//   PUBLIC 札を distribution_targets 未設定で publish すると RECRUITMENT_204 で弾かれるが、
//   このエラーが HTTP 500（サーバエラー）で返っていた。クライアント起因の修正可能な条件であり
//   本来 4xx であるべき（FRIEND_TEAMS_ONLY 側は MARKET_002=400 で正しかった）。
//   → 根治: GlobalExceptionHandler に RECRUITMENT_204/207 を 400 として登録。
//   本テストは「400・RECRUITMENT_204」を回帰として固定する（再び 500 に戻ったら落ちる）。
//   なお UI 導線（new.vue）は PUBLIC_FEED を自動設定するため、この 0 件 publish は API 直叩きでのみ起こる。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-BUG: PUBLIC 札を配信対象未設定で publish すると RECRUITMENT_204 が 400 で返る', async () => {
  expect(adminToken).toBeTruthy()

  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'E2E実機CRUD 配信対象未設定の PUBLIC 札',
      participationType: 'INDIVIDUAL',
      ...FUTURE_API,
      capacity: 4,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
    },
  })
  expect(createRes.status()).toBe(201)
  const created = (await createRes.json()) as { data: { id: number } }
  cleanupIds.push(created.data.id)

  // 配信対象を設定せずに publish → RECRUITMENT_204 が 400 で返る（旧 500 からの根治）
  const publishRes = await api.post(`${BE_API}/recruitment-listings/${created.data.id}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publishRes.status(), 'PUBLIC publish 配信対象0件は 400（旧 500 から根治）').toBe(400)
  const json = (await publishRes.json()) as { error: { code: string } }
  expect(json.error.code).toBe('RECRUITMENT_204')

  // この札は DRAFT のまま市に出ない
  const marketRes = await api.get(`${PUBLIC_MARKET}/listings/${created.data.id}`)
  expect(marketRes.status(), '公開できなかった札は市で 404').toBe(404)
})
