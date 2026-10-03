import { expect, request as pwRequest, test, type APIRequestContext } from '@playwright/test'

/**
 * 実機検証: PR #3355 GET /api/v1/team/members/lookup の teamPageId 必須化・認可修正
 * (CMP-260918-0025 検証戦役)
 *
 * PR本文の記載どおり、この API を呼ぶ画面側コンポーネントはアプリ内に0件（未結線）
 * であることを frontend/app 全体の grep で確認済み（useMemberProfileApi.ts の
 * lookupMembers を呼ぶ .vue が存在しない）。よって「画面から検索を使う導線」は
 * 現状存在せず、UI回帰確認は対象外。API契約そのものの認可挙動を実測する。
 *
 * このファイルは `page`/`browser` の storageState を使わず `APIRequestContext` のみで
 * 完結しているため、chromium-real プロジェクトの既定 storageState
 * (`tests/e2e/.auth/real-user.json`) 引き継ぎ問題は原理的に起きない。
 * ただし念のため describe レベルで storageState を空に明示し、意図を固定する。
 */
test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

let api: APIRequestContext
let adminToken: string
let memberToken: string
let outsiderToken: string

test.beforeAll(async () => {
  api = await pwRequest.newContext({ baseURL: API_BASE_URL })

  async function login(email: string): Promise<string> {
    const res = await api.post('/api/v1/auth/login', {
      data: { email, password: 'TestPass2026!' },
    })
    expect(res.status(), `${email} のログインに失敗`).toBe(200)
    return ((await res.json()).data as { accessToken: string }).accessToken
  }

  adminToken = await login('e2e-user@test.mannschaft.local')
  memberToken = await login('e2e-dummy-6@test.mannschaft.local')
  outsiderToken = await login('e2e-supporter@test.mannschaft.local')

  // 誰としてログインしているかをここで固定的に確認する（別アカウント取り違え防止）。
  async function assertIdentity(token: string, expectedEmail: string): Promise<void> {
    const me = await api.get('/api/v1/users/me', { headers: { Authorization: `Bearer ${token}` } })
    expect(me.status()).toBe(200)
    const body = (await me.json()).data as { email: string }
    expect(body.email).toBe(expectedEmail)
  }
  await assertIdentity(adminToken, 'e2e-user@test.mannschaft.local')
  await assertIdentity(memberToken, 'e2e-dummy-6@test.mannschaft.local')
  await assertIdentity(outsiderToken, 'e2e-supporter@test.mannschaft.local')
})

test.afterAll(async () => {
  await api.dispose()
})

test('teamPageId 未指定は400（分岐外の必須パラメータ化）', async () => {
  const res = await api.get('/api/v1/team/members/lookup?q=test', {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(res.status()).toBe(400)
})

test('teamPageId を指定すればADMINは200で検索できる', async () => {
  const pages = await api.get('/api/v1/team/pages?organizationId=9&size=100', {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(pages.status()).toBe(200)
  const pageList = (await pages.json()).data as Array<{ id: number }>

  if (pageList.length === 0) {
    test.info().annotations.push({
      type: 'note',
      description: 'organizationId=9 に team_page が0件のため、この検証はスキップ（他検証④で作成後に確認可能）',
    })
    test.skip()
    return
  }

  const first = pageList[0]
  if (!first) {
    test.skip()
    return
  }

  const res = await api.get(`/api/v1/team/members/lookup?q=&teamPageId=${first.id}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(res.status()).toBe(200)
})

test('他テナント(non-member)は同一パラメータでも404で弾かれる（横断確認）', async () => {
  const pages = await api.get('/api/v1/team/pages?organizationId=9&size=100', {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  const pageList = (await pages.json()).data as Array<{ id: number }>
  if (pageList.length === 0) {
    test.skip()
    return
  }

  const first = pageList[0]
  if (!first) {
    test.skip()
    return
  }

  const res = await api.get(`/api/v1/team/members/lookup?q=&teamPageId=${first.id}`, {
    headers: { Authorization: `Bearer ${outsiderToken}` },
  })
  expect([403, 404]).toContain(res.status())
})

test('未認証は401', async () => {
  // beforeAll で作った `api` コンテキストは3アカウント分のログインを経ており、
  // access_token Cookie が付与された「認証済み」コンテキストになっている。
  // 未認証を検証するには Cookie を一切持たない別コンテキストを使う必要がある。
  const anonApi = await pwRequest.newContext({ baseURL: API_BASE_URL })
  try {
    const res = await anonApi.get('/api/v1/team/members/lookup?q=test&teamPageId=1')
    expect(res.status()).toBe(401)
  } finally {
    await anonApi.dispose()
  }
})

// memberToken を使い切っていない旨の記録（同一組織MEMBERとしての横断は④の
// member-profiles 検証内でカバーする）。
test.skip('MEMBER横断は④の member-profiles 検証で兼ねる', async () => {
  void memberToken
})
