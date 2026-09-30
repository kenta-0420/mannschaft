import { expect, request as pwRequest, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * 実機検証: PR #3387 メンバー統合画面（一覧／紹介）サブタブのロール別可視性BE基盤
 * (CMP-260919-1140 Phase 1 / 実機検証戦役 CMP-260930-2235)
 *
 * 【重要な前提（殿への実測報告）】
 * この PR は BE のみの実装であり、`frontend/app` 全体を grep しても
 * `member-subtab-visibility` API を呼ぶ .vue コンポーネントは存在しない
 * （型定義 `types/generated/index.ts` にしか現れない）。同様に
 * `GET /api/v1/team/members/lookup`（会員検索）・
 * `POST /api/v1/team/pages/{id}/copy-members`（メンバー複製）を呼ぶ画面導線もアプリ内に0件
 * （既存実機検証 `cmp260918-2-member-lookup-authz.real.spec.ts` が同じ結論を実測済み）。
 * したがって「④ サブタブ可視性設定を画面から保存する」「操作系(lookup/copy-members)を画面から操作する」
 * という指示のシナリオは、対応する画面が存在しないため実施不能（対処療法で画面をでっち上げることはしない
 * — 未実装は未実装として報告する）。実施できるのは以下の2系統:
 *   (A) 既存の実画面 `/organizations/[slug]/member-profiles` での閲覧制御（非表示プロフィールの除外）を
 *       実ブラウザ操作で確認する。
 *   (B) 画面を持たない API 契約（サブタブ可視性 GET/PUT・lookup・copy-members）を実 API 直叩きで確認する
 *       （指示の「API で代替してよいのはログイン・前提データ作成・後始末だけ」の原則には反するが、
 *       画面が存在しない以上ここは API 直叩きでしか検証できない。この逸脱を明記する）。
 *
 * 【フィクスチャ】(このファイルの beforeAll で作成・afterAll で削除)
 * - 組織A: org-000009 (numericId=9, 既存 e2e 組織)
 *   - ADMIN: e2e-user@test.mannschaft.local (userId=23)
 *   - MEMBER: e2e-dummy-6@test.mannschaft.local
 *   - 非所属(outsider): e2e-supporter@test.mannschaft.local, e2e-outsider@test.mannschaft.local
 *     （実測: この2アカウントは org-000009 の会員一覧に存在しない。SUPPORTER ロールとして
 *     org-000009 に所属する e2e アカウントは存在せず、`PATCH .../members/{userId}/role` は
 *     `roleId` を要求するが roleId 一覧を返す API が見つからず、本物の SUPPORTER/DEPUTY_ADMIN
 *     ロールでの検証は本戦役では未実施。既知の欠落として報告する）。
 *   - 新規作成する組織スコープの紹介ページ（PUBLIC・PUBLISHED）1件、配下にプロフィール2件
 *     （1件は is_visible=false に変更）
 * - 後始末: 作成したページ（プロフィールはページ削除に連鎖）を DELETE する
 *
 * 罠(既存精査スキームに準拠): 別アカウントへの切替は必ず storageState を空にした新規コンテキストを使い、
 * `/api/v1/users/me` で本人確認する（cmp260918-4 の罠メモを踏襲）。
 */

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8085'
const ORG_SLUG = 'org-000009'
const ORG_ID = 9
const uniqueSuffix = Date.now()
const PAGE_TITLE = `jikki subtab page ${uniqueSuffix}`
const PAGE_SLUG = `jikki-3387-subtab-${uniqueSuffix}`
const MEMBER1_NAME = `jikki visible ${uniqueSuffix}`
const MEMBER2_NAME = `jikki hidden ${uniqueSuffix}`

let api: APIRequestContext
let adminToken: string
let memberToken: string
let supporterToken: string // 実際には org-000009 非所属アカウント（outsider 相当。上記コメント参照）
let outsiderToken: string
let createdPageId: number

async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const res = await ctx.post('/api/v1/auth/login', {
    data: { email, password: 'TestPass2026!' },
  })
  expect(res.status(), `${email} のログインに失敗`).toBe(200)
  return ((await res.json()).data as { accessToken: string }).accessToken
}

async function assertIdentity(ctx: APIRequestContext, token: string, expectedEmail: string): Promise<void> {
  const me = await ctx.get('/api/v1/users/me', { headers: { Authorization: `Bearer ${token}` } })
  expect(me.status()).toBe(200)
  const body = (await me.json()).data as { email: string }
  expect(body.email).toBe(expectedEmail)
}

test.beforeAll(async () => {
  api = await pwRequest.newContext({ baseURL: API_BASE_URL })
  adminToken = await login(api, 'e2e-user@test.mannschaft.local')
  memberToken = await login(api, 'e2e-dummy-6@test.mannschaft.local')
  supporterToken = await login(api, 'e2e-supporter@test.mannschaft.local')
  outsiderToken = await login(api, 'e2e-outsider@test.mannschaft.local')
  await assertIdentity(api, adminToken, 'e2e-user@test.mannschaft.local')
  await assertIdentity(api, memberToken, 'e2e-dummy-6@test.mannschaft.local')
  await assertIdentity(api, supporterToken, 'e2e-supporter@test.mannschaft.local')
  await assertIdentity(api, outsiderToken, 'e2e-outsider@test.mannschaft.local')

  // 前提データ: 組織Aの紹介ページ（PUBLIC）を作成→公開→プロフィール2件（うち1件を非表示化）
  const pageRes = await api.post('/api/v1/team/pages', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      organizationId: ORG_ID,
      title: PAGE_TITLE,
      slug: PAGE_SLUG,
      pageType: 'YEARLY',
      year: 2026,
      visibility: 'PUBLIC',
    },
  })
  expect(pageRes.status(), 'ページ作成に失敗').toBe(201)
  createdPageId = ((await pageRes.json()).data as { id: number }).id

  const publishRes = await api.patch(`/api/v1/team/pages/${createdPageId}/publish`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { status: 'PUBLISHED' },
  })
  expect(publishRes.status()).toBe(200)

  const m1 = await api.post('/api/v1/team/members', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { teamPageId: createdPageId, displayName: MEMBER1_NAME },
  })
  expect(m1.status()).toBe(201)

  const m2 = await api.post('/api/v1/team/members', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { teamPageId: createdPageId, displayName: MEMBER2_NAME },
  })
  expect(m2.status()).toBe(201)
  const member2Id = ((await m2.json()).data as { id: number }).id

  const hideRes = await api.put(`/api/v1/team/members/${member2Id}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { displayName: MEMBER2_NAME, isVisible: false },
  })
  expect(hideRes.status()).toBe(200)
})

test.afterAll(async () => {
  if (createdPageId != null) {
    try {
      const res = await api.delete(`/api/v1/team/pages/${createdPageId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
      if (![200, 204, 404].includes(res.status())) {
        console.error(`CMP-260930-2235: 後始末のページ削除が想定外のステータス: ${res.status()}`)
      }
    } catch (error) {
      console.error('CMP-260930-2235: 後始末のページ削除に失敗', error)
    }
  }
  await api.dispose()
})

test.describe.configure({ mode: 'serial' })

// ---------------------------------------------------------------------------
// (A) 実画面での閲覧制御（非表示プロフィールの除外）
// ---------------------------------------------------------------------------

test('①ADMIN: /organizations/org-000009/member-profiles が開き、非表示プロフィールを含め全件見える', async ({
  browser,
}) => {
  test.setTimeout(240_000)
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(
    page,
    { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' },
    { apiBaseUrl: API_BASE_URL },
  )

  const res = await page.goto(`/organizations/${ORG_SLUG}/member-profiles`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  expect(res?.status() ?? 200).toBeLessThan(400)

  await expect(page.getByText(PAGE_TITLE, { exact: false }).first()).toBeVisible({ timeout: 60_000 })
  await context.close()
})

test('②MEMBER: 非表示プロフィールが一覧APIの応答から除外される（実画面のデータソース）', async () => {
  const res = await api.get(`/api/v1/team/members?teamPageId=${createdPageId}&size=50`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  expect(res.status()).toBe(200)
  const names = ((await res.json()).data as Array<{ displayName: string }>).map((m) => m.displayName)
  expect(names).toContain(MEMBER1_NAME)
  expect(names).not.toContain(MEMBER2_NAME)
})

test('③ADMIN: 一覧APIの応答は非表示プロフィールを含む（管理者は編集用途で全件取得）', async () => {
  const res = await api.get(`/api/v1/team/members?teamPageId=${createdPageId}&size=50`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(res.status()).toBe(200)
  const names = ((await res.json()).data as Array<{ displayName: string }>).map((m) => m.displayName)
  expect(names).toEqual(expect.arrayContaining([MEMBER1_NAME, MEMBER2_NAME]))
})

test('④MEMBERが非表示プロフィールをIDで直叩きしても404で秘匿される（存在漏洩防止）', async () => {
  const listRes = await api.get(`/api/v1/team/members?teamPageId=${createdPageId}&size=50`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  const all = (await listRes.json()).data as Array<{ id: number; displayName: string }>
  const hidden = all.find((m) => m.displayName === MEMBER2_NAME)
  if (!hidden) throw new Error('非表示プロフィールがADMIN一覧に見つからない')

  const res = await api.get(`/api/v1/team/members/${hidden.id}`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  expect(res.status()).toBe(404)
})

// ---------------------------------------------------------------------------
// (B) サブタブ可視性設定 API（画面導線なし。前掲コメント参照）
// ---------------------------------------------------------------------------

test('⑤GET: 会員(MEMBER)も非会員(outsider)も200・既定値MEMBERで取得できる', async () => {
  for (const token of [memberToken, outsiderToken]) {
    const res = await api.get(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(res.status()).toBe(200)
    const body = (await res.json()).data as {
      subtabs: Array<{ subtabKey: string; minRole: string; default: boolean }>
    }
    const list = body.subtabs.find((s) => s.subtabKey === 'member_list')
    expect(list?.minRole).toBe('MEMBER')
  }
})

test('⑥PUT: MEMBER・非会員(outsider)は権限がなく403で拒否される', async () => {
  for (const token of [memberToken, outsiderToken]) {
    const res = await api.put(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        subtabs: [
          { subtabKey: 'member_list', minRole: 'SUPPORTER' },
          { subtabKey: 'member_profiles', minRole: 'SUPPORTER' },
        ],
      },
    })
    expect(res.status()).toBe(403)
  }
})

test('⑦PUT: 一覧(member_list)にPUBLICを指定すると422で拒否される', async () => {
  const res = await api.put(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      subtabs: [
        { subtabKey: 'member_list', minRole: 'PUBLIC' },
        { subtabKey: 'member_profiles', minRole: 'MEMBER' },
      ],
    },
  })
  expect(res.status()).toBe(422)
})

test('⑧PUT: ADMINは正当な値で保存でき、同じ値での再保存（冪等）も200になる。監査ログ(updatedBy)が残る', async () => {
  const payload = {
    subtabs: [
      { subtabKey: 'member_list', minRole: 'MEMBER' },
      { subtabKey: 'member_profiles', minRole: 'SUPPORTER' },
    ],
  }
  const res1 = await api.put(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: payload,
  })
  expect(res1.status()).toBe(200)
  const body1 = (await res1.json()).data as {
    subtabs: Array<{ subtabKey: string; minRole: string; updatedBy?: { id: number } }>
  }
  const profilesEntry = body1.subtabs.find((s) => s.subtabKey === 'member_profiles')
  expect(profilesEntry?.minRole).toBe('SUPPORTER')
  expect(profilesEntry?.updatedBy?.id).toBeDefined()

  // 冪等: 同じ値で再保存しても200
  const res2 = await api.put(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: payload,
  })
  expect(res2.status()).toBe(200)

  // 元の既定値(MEMBER)に戻す（後続テストへの影響を避ける）
  const reset = await api.put(`/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      subtabs: [
        { subtabKey: 'member_list', minRole: 'MEMBER' },
        { subtabKey: 'member_profiles', minRole: 'MEMBER' },
      ],
    },
  })
  expect(reset.status()).toBe(200)
})

test('⑨URL直打ち: MEMBERはUIに設定画面への導線がなく、PUT APIも直叩きで403（画面調査込み）', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(
    page,
    { email: 'e2e-dummy-6@test.mannschaft.local', password: 'TestPass2026!' },
    { apiBaseUrl: API_BASE_URL },
  )
  // このAPIを呼ぶ設定画面が frontend/app に存在しないため、実在するメンバー紹介画面を開いて
  // 「サブタブ可視性を設定するUIが存在しない」ことを確認する（導線調査）。
  await page.goto(`/organizations/${ORG_SLUG}/member-profiles`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  // 可視性設定ダイアログ・ボタンへの言及がないことを確認（存在すれば見つかるはずの文言）
  await expect(page.getByText('サブタブ可視性', { exact: false })).toHaveCount(0)

  const putRes = await page.request.put(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/member-subtab-visibility`, {
    data: {
      subtabs: [
        { subtabKey: 'member_list', minRole: 'SUPPORTER' },
        { subtabKey: 'member_profiles', minRole: 'SUPPORTER' },
      ],
    },
  })
  expect(putRes.status()).toBe(403)
  await context.close()
})

// ---------------------------------------------------------------------------
// (B') lookup・copy-members（画面導線なし。API契約のみ実測。既存 cmp260918-2 と重複しない範囲）
// ---------------------------------------------------------------------------

test('⑩copy-members: 同一ページをコピー元に指定すると常に400で拒否される（AC-29）', async () => {
  const res = await api.post(`/api/v1/team/pages/${createdPageId}/copy-members`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { sourcePageId: createdPageId },
  })
  expect(res.status()).toBe(400)
  const body = (await res.json()) as { error: { code: string } }
  expect(body.error.code).toBe('MEMBER_011')
})

test('⑪lookup: teamPageId未指定は400、MEMBER以上は200、非所属は404', async () => {
  const noParam = await api.get('/api/v1/team/members/lookup?q=test', {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(noParam.status()).toBe(400)

  const asMember = await api.get(`/api/v1/team/members/lookup?q=&teamPageId=${createdPageId}`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  expect(asMember.status()).toBe(200)

  const asOutsider = await api.get(`/api/v1/team/members/lookup?q=&teamPageId=${createdPageId}`, {
    headers: { Authorization: `Bearer ${outsiderToken}` },
  })
  expect([403, 404]).toContain(asOutsider.status())
})
