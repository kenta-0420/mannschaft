/**
 * F22.1 市（Market）— 認証付き実機 CRUD E2E（モック不使用・書き込み経路）
 *
 * 実際のバックエンド（localhost:8080）に対し、ログイン済み ADMIN ユーザーで
 * 札の「作成(POST) → 公開(publish) → 編集(PATCH) → 下げる(cancel)」を一気通貫で踏み、
 * 各ステップで実 BE レスポンスと公開市（/market）の UI 描画の両方を検証する。
 *
 * 読み取り専用の market.real.spec.ts（未ログイン permitAll 経路）と対をなす「書き込み経路」スペック。
 * page.route によるモックは一切使用しない。
 *
 * ── 認証セッションの確立 ──────────────────────────────────────────────
 *   実 BE の POST /api/v1/auth/login で Bearer トークンを取得する。
 *   テストユーザー: e2e-admin@test.mannschaft.local / TestPass2026!
 *     - backend/scripts/seed-e2e-data.js が投入する E2E 管理者
 *     - SYSTEM_ADMIN + JFA(org) ADMIN + FC東京U-18(team id=1) ADMIN
 *     - 市の札主は scope の ADMIN。team id=1 の ADMIN なので札立て可能。
 *   ※ ログインレスポンスは { data: { accessToken } } 形式（data 直下ではなく data.data.accessToken）。
 *     既存の seed-api.ts fetchBearerToken は data.accessToken を読むため
 *     この入れ子では undefined になる（本スペックは正しい入れ子で取得する）。
 *
 * ── 札の書き込み経路（実 BE の本物のエラーを炙り出すのが主目的）──────────
 *   POST   /api/v1/teams/{teamId}/recruitment-listings        作成（DRAFT で生成）
 *   PUT    /api/v1/recruitment-listings/{id}/distribution-targets  配信対象設定（PUBLIC は PUBLIC_FEED 必須）
 *   POST   /api/v1/recruitment-listings/{id}/publish          公開（DRAFT → OPEN・市に出る）
 *   PATCH  /api/v1/recruitment-listings/{id}                  編集
 *   POST   /api/v1/recruitment-listings/{id}/cancel           下げる（→ CANCELLED・市から消える）
 *   GET    /api/v1/public/market/listings/{id}                公開市での見え方（permitAll）
 *
 * テストID:
 *   MRC-000  認証セッション確立（ADMIN ログイン → Bearer 取得・team id 解決）
 *   MRC-001  作成(POST)＋公開→市の一覧/詳細に camelCase 実値で出る
 *   MRC-002  編集(PATCH)→市詳細に反映される
 *   MRC-003  フレンド宛先(FRIEND_TEAMS_ONLY)＋宛先0件 MARKET_002 / 宛先付き publish
 *   MRC-005  下げる(cancel)→市から当該札が消える（詳細404・一覧非表示）
 *   MRC-AUTHZ 非 ADMIN（MEMBER）は作成不可（403・COMMON_002）
 *   MRC-BUG  PUBLIC publish の配信対象0件が HTTP 500 で返る既知バグの回帰固定
 */

import { test, expect, request as pwRequest, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 書き込み経路は storageState に依存せず Bearer トークンで実行する（未ログインでも自前ログインする）。
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

// 未来日（seed と被らない高位の日付）。
const FUTURE = {
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
// MRC-001: 作成(POST) → 配信対象設定 → 公開 → 市の一覧/詳細に camelCase 実値で出る
// ──────────────────────────────────────────────────────────────────────────
test('MRC-001: PUBLIC 札を作成・公開し、公開市の一覧/詳細に camelCase 実値で出る', async () => {
  expect(adminToken, 'MRC-000 でトークン取得済みであること').toBeTruthy()

  // 1) 作成（DRAFT で生成される）
  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'E2E実機CRUD 練習試合募集',
      description: 'E2E自動テストで作成した PUBLIC 札',
      participationType: 'INDIVIDUAL',
      ...FUTURE,
      capacity: 6,
      minCapacity: 2,
      paymentEnabled: false,
      visibility: 'PUBLIC',
      location: '調布市総合体育館',
      prefectureCode: '44',
      cityCode: '44201',
    },
  })
  expect(createRes.status(), '作成は 201 CREATED').toBe(201)
  const created = (await createRes.json()) as { data: { id: number, status: string, visibility: string } }
  createdId = created.data.id
  cleanupIds.push(createdId)
  expect(created.data.status, '作成直後は DRAFT').toBe('DRAFT')
  expect(created.data.visibility).toBe('PUBLIC')

  // 2) 作成直後は DRAFT なので市にはまだ出ない
  const beforePublish = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(beforePublish.status(), 'DRAFT 札は公開市で 404 存在秘匿').toBe(404)

  // 3) PUBLIC 札は PUBLIC_FEED の配信対象を設定しないと公開できない（publish の前提）
  const dtRes = await api.put(`${BE_API}/recruitment-listings/${createdId}/distribution-targets`, {
    headers: authHeaders(adminToken),
    data: { targetTypes: ['PUBLIC_FEED'] },
  })
  expect(dtRes.status(), '配信対象設定は 200').toBe(200)

  // 4) 公開（DRAFT → OPEN）
  const publishRes = await api.post(`${BE_API}/recruitment-listings/${createdId}/publish`, {
    headers: authHeaders(adminToken),
  })
  expect(publishRes.status(), '公開は 200').toBe(200)
  const published = (await publishRes.json()) as { data: { status: string } }
  expect(published.data.status, '公開後は OPEN').toBe('OPEN')

  // 5) 公開市の詳細 API が camelCase 実値を返す（owner.displayName / region / capacity 等）
  const detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(detailRes.status(), '公開後は市詳細で 200').toBe(200)
  const detail = (await detailRes.json()) as {
    data: {
      id: number
      title: string
      owner: { displayName: string, scopeType: string, scopeId: number }
      region: { prefectureName: string, cityName: string } | null
      category: { nameKey: string }
      capacity: number
      confirmedCount: number
      status: string
    }
  }
  expect(detail.data.title).toBe('E2E実機CRUD 練習試合募集')
  expect(detail.data.owner.displayName).toBe('FC東京U-18（テスト）')
  expect(detail.data.owner.scopeType).toBe('TEAM')
  expect(detail.data.owner.scopeId).toBe(adminTeamId)
  expect(detail.data.region?.prefectureName).toBe('大分県')
  expect(detail.data.region?.cityName).toBe('大分市')
  expect(detail.data.category.nameKey).toBe('recruitment.category.practice_match')
  expect(detail.data.capacity).toBe(6)
  expect(detail.data.confirmedCount).toBe(0)
  expect(detail.data.status).toBe('OPEN')

  // 6) 公開市の一覧 API にも作成した札が含まれる
  const listRes = await api.get(`${PUBLIC_MARKET}/listings?size=100`)
  expect(listRes.status()).toBe(200)
  const list = (await listRes.json()) as { data: Array<{ id: number }> }
  expect(list.data.map((d) => d.id)).toContain(createdId)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-001b: 作成した札がブラウザの公開市詳細ページで undefined にならず描画される
// ──────────────────────────────────────────────────────────────────────────
test('MRC-001b: 作成・公開した札が /market/listings/{id} で実値描画される（undefined/NaN なし）', async ({ page }) => {
  expect(createdId, 'MRC-001 で札が公開済みであること').toBeTruthy()

  await page.goto(`/market/listings/${createdId}`)
  await waitForHydration(page)
  await expect(page.getByTestId('market-detail-card')).toBeVisible({ timeout: 15_000 })

  await expect(page.getByTestId('market-detail-organizer-name')).toHaveText('FC東京U-18（テスト）')
  await expect(page.getByTestId('market-detail-title')).toContainText('E2E実機CRUD 練習試合募集')

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
    data: { title: 'E2E実機CRUD 練習試合募集（編集後）', capacity: 10 },
  })
  expect(patchRes.status(), '編集は 200').toBe(200)
  const patched = (await patchRes.json()) as { data: { title: string, capacity: number } }
  expect(patched.data.title).toBe('E2E実機CRUD 練習試合募集（編集後）')
  expect(patched.data.capacity).toBe(10)

  // 公開市の詳細に反映される
  const detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(detailRes.status()).toBe(200)
  const detail = (await detailRes.json()) as { data: { title: string, capacity: number } }
  expect(detail.data.title).toBe('E2E実機CRUD 練習試合募集（編集後）')
  expect(detail.data.capacity).toBe(10)
})

// ──────────────────────────────────────────────────────────────────────────
// MRC-003: フレンド宛先（FRIEND_TEAMS_ONLY）
//   宛先0件は publish 時に MARKET_002（400）。宛先 ALL_FRIENDS 指定で作成・公開できる。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-003: FRIEND_TEAMS_ONLY 札は宛先0件 publish が MARKET_002、宛先指定で公開可（市には出ない）', async () => {
  expect(adminToken).toBeTruthy()

  // 宛先を指定して作成（friendTargets が0件だと create 時点で MARKET_002 になるため ALL_FRIENDS を指定）
  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'E2E実機CRUD フレンド限定札',
      participationType: 'INDIVIDUAL',
      ...FUTURE,
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
  // ALL_FRIENDS が targetKind として保存されている（MARKET_002 にならない）
  expect(created.data.friendTargets.map((t) => t.targetKind)).toContain('ALL_FRIENDS')

  // 公開（宛先1件以上あるので OPEN へ。フレンド札は distribution_targets 不要）
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

  // 公開市の詳細は 404 存在秘匿
  const detailRes = await api.get(`${PUBLIC_MARKET}/listings/${createdId}`)
  expect(detailRes.status(), '下げた札は市詳細で 404').toBe(404)

  // 一覧にも出ない
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
      ...FUTURE,
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
// MRC-BUG: PUBLIC publish の配信対象0件が HTTP 500 で返る既知バグ（回帰固定）
//
// 🔴 発見バグ（2026-06-02 実機 E2E）:
//   PUBLIC 札は publish 前に PUBLIC_FEED の distribution_targets を設定しないと
//   RECRUITMENT_204「配信対象が0件のため公開できません」で弾かれるが、
//   このエラーが HTTP 500（サーバエラー）で返る。クライアント起因の修正可能な
//   条件であり本来 4xx であるべき（FRIEND_TEAMS_ONLY 側は MARKET_002=400 で正しい）。
//
//   さらに重大なのは、FE の札立て導線（pages/teams/[id]/recruitment-listings/new.vue →
//   pages/recruitment-listings/[id].vue の publish ボタン）が distribution_targets を
//   一切設定しないため、UI から PUBLIC 札を作成して公開しようとすると必ずこの 500 で
//   失敗し、市に永遠に出ない。本テストはこの 500 を回帰として固定し、4xx 化＋
//   FE 導線での PUBLIC_FEED 自動設定で根治した際に期待値を反転させる。
// ──────────────────────────────────────────────────────────────────────────
test('MRC-BUG: PUBLIC 札を配信対象未設定で publish すると RECRUITMENT_204 が HTTP 500 で返る（既知バグ）', async () => {
  expect(adminToken).toBeTruthy()

  const createRes = await api.post(`${BE_API}/teams/${adminTeamId}/recruitment-listings`, {
    headers: authHeaders(adminToken),
    data: {
      categoryId: CATEGORY_PRACTICE_MATCH,
      title: 'E2E実機CRUD 配信対象未設定の PUBLIC 札',
      participationType: 'INDIVIDUAL',
      ...FUTURE,
      capacity: 4,
      minCapacity: 1,
      paymentEnabled: false,
      visibility: 'PUBLIC',
    },
  })
  expect(createRes.status()).toBe(201)
  const created = (await createRes.json()) as { data: { id: number } }
  cleanupIds.push(created.data.id)

  // 配信対象を設定せずに publish → RECRUITMENT_204
  const publishRes = await api.post(`${BE_API}/recruitment-listings/${created.data.id}/publish`, {
    headers: authHeaders(adminToken),
  })
  // 🔴 本来 4xx であるべきだが現状は 500（このアサーションが落ちたら根治の合図 → 4xx へ反転すること）
  expect(publishRes.status(), 'PUBLIC publish 配信対象0件は現状 HTTP 500（本来 4xx・要根治）').toBe(500)
  const json = (await publishRes.json()) as { error: { code: string } }
  expect(json.error.code).toBe('RECRUITMENT_204')

  // この札は DRAFT のまま市に出ない
  const marketRes = await api.get(`${PUBLIC_MARKET}/listings/${created.data.id}`)
  expect(marketRes.status(), '公開できなかった札は市で 404').toBe(404)
})
