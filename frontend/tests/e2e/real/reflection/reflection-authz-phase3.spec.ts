/**
 * F06.5 Phase 3: アーカイブ＆分類 — 越境認可（2ユーザー）実機 E2E（モック不使用）
 *
 * 認可方針（BE）: 全エンドポイント「認証必須＋本人所有のみ」。他人所有リソースは IDOR 対策で
 * 404（NOT_FOUND）。未認証は 401。
 *
 * 単一セッション設計（reflection-authz.spec.ts 踏襲）:
 *   - beforeEach で page context cookie を user A に fresh 化（別 context login 禁止）。
 *   - user B（e2e-admin）の検証は、同テスト内で page.request.post(login) により page の cookie
 *     ジャーを B に切り替えて行う（B のトークンで A のリソースを叩く）。
 *   - 未認証 401 は playwright フィクスチャ（cookie 無し context）で叩く。
 *
 * 2 ユーザーの用意:
 *   - user A = e2e-archive-1782272116@test.mannschaft.local（Passw0rd!2026・seed 済み id=90212）
 *     ※ e2e-user(id=23)はパスワードドリフトが既知のため代替ユーザーを使用
 *   - user B = e2e-admin@test.mannschaft.local（TestPass2026!・seed 済み id=24）
 *
 * カバー（Phase 3 固有エンドポイント）:
 *   AUTHZ-P3-01: user B が user A のテーマを archive → 404（IDOR）
 *   AUTHZ-P3-02: user B が user A のアーカイブ済みテーマを restore → 404（IDOR）
 *   AUTHZ-P3-03: user B が user A のテーマ詳細を取得 → 404（IDOR）
 *   AUTHZ-P3-04: user B の archive/search に user A のテーマが漏洩しない
 *   AUTHZ-P3-05: user B の archive/folders に user A のフォルダが漏洩しない
 *   AUTHZ-P3-06: user B の bulk-archive が user A のテーマに影響しない（最重要 tripwire）
 *   AUTHZ-P3-07: 未認証で Phase 3 全エンドポイント → 401
 *
 * NOTE: termLabel に日本語を使用するとバリデーションエラー（COMMON_001）になるため
 * termLabel は ASCII 英字を使用する（"CrossBorder"）。
 */
import { test, expect } from '@playwright/test'

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

// user A: e2e-archive ユーザー（id=90212）
const USER_A_EMAIL = process.env.TEST_ARCH_USER_EMAIL ?? 'e2e-archive-1782272116@test.mannschaft.local'
const USER_A_PASSWORD = process.env.TEST_ARCH_USER_PASSWORD ?? 'Passw0rd!2026'
// user B: e2e-admin ユーザー（id=24）
const USER_B_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const USER_B_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const RUN_ID = Date.now()

// テーマ識別子（academicYear=2099, termLabel=CrossBorder で重複回避）
const THEME_ACADEMIC_YEAR = 2099
const THEME_TERM_LABEL = `CBorder${RUN_ID}` // ASCII のみ・ユニーク化

test.describe.configure({ mode: 'serial' })

// user A が作成したリソース ID（後続テストで B からアクセスして越境を確認）
let aThemeId = ''
let aUserId = ''
let bUserId = ''

// beforeEach で page cookie を user A に fresh 化する（単一セッション harness）
test.beforeEach(async ({ page }) => {
  let loggedIn = false
  for (let i = 0; i < 5; i++) {
    const res = await page.request.post(`${BE_API}/auth/login`, {
      data: { email: USER_A_EMAIL, password: USER_A_PASSWORD },
    })
    if (res.status() === 200) {
      loggedIn = true
      break
    }
    await page.waitForTimeout(2_000)
  }
  expect(loggedIn, 'beforeEach の user A login（リトライ込）が成功').toBe(true)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-000: 前提セットアップ（user A でテーマ作成）
// ---------------------------------------------------------------------------
test('AUTHZ-P3-000: user A でテーマ作成（越境テスト用リソース確保）', async ({ page }) => {
  // user A の userId を取得
  const meRes = await page.request.get(`${BE_API}/users/me`)
  expect(meRes.status(), 'user A GET /users/me が 200').toBe(200)
  const meBody = await meRes.json()
  aUserId = String(meBody.data?.id ?? meBody.id ?? '')
  expect(aUserId, 'user A の userId が取得できた').toBeTruthy()

  // user B の userId を取得（cookie を B に切り替え）
  const loginBRes = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginBRes.status(), 'user B login が 200').toBe(200)
  const meBRes = await page.request.get(`${BE_API}/users/me`)
  expect(meBRes.status(), 'user B GET /users/me が 200').toBe(200)
  const meBBody = await meBRes.json()
  bUserId = String(meBBody.data?.id ?? meBBody.id ?? '')
  expect(bUserId, 'user B の userId が取得できた').toBeTruthy()

  // user A と user B が別人であることを確認（越境テストの前提）
  expect(aUserId, 'user A と user B は別人（userId が異なる）').not.toBe(bUserId)
  // eslint-disable-next-line no-console
  console.log(`user A id=${aUserId}, user B id=${bUserId}`)

  // user A に戻る（beforeEach が A に設定しているが念のため再ログイン）
  const loginARes = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_A_EMAIL, password: USER_A_PASSWORD },
  })
  expect(loginARes.status(), 'user A 再ログインが 200').toBe(200)

  // user A でテーマを作成（academicYear=2099, termLabel=CrossBorder）
  const createRes = await page.request.post(`${BE_API}/me/reflections/themes`, {
    data: {
      title: `Phase3 越境テスト ${RUN_ID}`,
      sourceType: 'FREE',
      academicYear: THEME_ACADEMIC_YEAR,
      termLabel: THEME_TERM_LABEL,
    },
  })
  expect(createRes.status(), 'user A のテーマ作成が成功').toBeLessThan(300)
  const createBody = await createRes.json()
  aThemeId = createBody.data?.id ?? ''
  expect(aThemeId, 'aThemeId が取得できた').toBeTruthy()

  // アーカイブしておく（archive/restore/search/folders はアーカイブ後のデータを扱う）
  const archiveRes = await page.request.patch(
    `${BE_API}/me/reflections/themes/${aThemeId}/archive`,
  )
  expect(archiveRes.status(), 'user A がテーマをアーカイブ成功').toBe(200)
  const archiveBody = await archiveRes.json()
  expect(archiveBody.data?.archivedAt, 'archivedAt が設定された').not.toBeNull()
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-01: user B が user A のテーマを archive → 404
// ---------------------------------------------------------------------------
test('AUTHZ-P3-01: user B が user A のテーマを archive → 404（IDOR）', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  // B にログイン切り替え
  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  const res = await page.request.patch(
    `${BE_API}/me/reflections/themes/${aThemeId}/archive`,
  )
  expect(
    res.status(),
    `user B が user A のテーマを archive できてしまった（status: ${res.status()}）`,
  ).toBe(404)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-02: user B が user A のアーカイブ済みテーマを restore → 404
// ---------------------------------------------------------------------------
test('AUTHZ-P3-02: user B が user A のアーカイブ済みテーマを restore → 404（IDOR）', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  const res = await page.request.patch(
    `${BE_API}/me/reflections/themes/${aThemeId}/restore`,
  )
  expect(
    res.status(),
    `user B が user A のテーマを restore できてしまった（status: ${res.status()}）`,
  ).toBe(404)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-03: user B が user A のテーマ詳細を取得 → 404
// ---------------------------------------------------------------------------
test('AUTHZ-P3-03: user B が user A のテーマ詳細を取得 → 404（IDOR）', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  const res = await page.request.get(
    `${BE_API}/me/reflections/themes/${aThemeId}`,
  )
  expect(
    res.status(),
    `user B が user A のテーマ詳細を取得できてしまった（status: ${res.status()}）`,
  ).toBe(404)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-04: user B の archive/search に user A のテーマが漏洩しない
// ---------------------------------------------------------------------------
test('AUTHZ-P3-04: user B の archive/search に user A のテーマが漏洩しない', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  const searchRes = await page.request.get(
    `${BE_API}/me/reflections/archive/search?academicYear=${THEME_ACADEMIC_YEAR}&termLabel=${THEME_TERM_LABEL}`,
  )
  expect(searchRes.status(), 'archive/search が 200').toBe(200)

  const searchBody = await searchRes.json()
  const items = (searchBody.data?.content ?? []) as Array<{ id: string }>
  const leaked = items.some(item => item.id === aThemeId)
  expect(
    leaked,
    `user B の archive/search 結果に user A のテーマ(${aThemeId})が漏洩している（重大脆弱性）`,
  ).toBe(false)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-05: user B の archive/folders に user A のフォルダが漏洩しない
// ---------------------------------------------------------------------------
test('AUTHZ-P3-05: user B の archive/folders に user A のフォルダが漏洩しない', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  const foldersRes = await page.request.get(`${BE_API}/me/reflections/archive/folders`)
  expect(foldersRes.status(), 'archive/folders が 200').toBe(200)

  const foldersBody = await foldersRes.json()
  const folders = (foldersBody.data ?? []) as Array<{
    academicYear: number | null
    termLabel: string | null
  }>
  const leaked = folders.some(
    f => f.academicYear === THEME_ACADEMIC_YEAR && f.termLabel === THEME_TERM_LABEL,
  )
  expect(
    leaked,
    `user B の archive/folders に user A のフォルダ(academicYear=${THEME_ACADEMIC_YEAR}, termLabel=${THEME_TERM_LABEL})が漏洩している（重大脆弱性）`,
  ).toBe(false)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-06: user B の bulk-archive が user A のテーマに影響しない（最重要 tripwire）
// ---------------------------------------------------------------------------
test('AUTHZ-P3-06: user B の bulk-archive が user A のテーマに影響しない（tripwire）', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  // まず user A でテーマを restore（アクティブ状態にしてから bulk-archive の越境を確認）
  // A に戻る（beforeEach で設定済み）
  const restoreRes = await page.request.patch(
    `${BE_API}/me/reflections/themes/${aThemeId}/restore`,
  )
  expect(restoreRes.status(), 'user A が restore 成功').toBe(200)
  const restoreBody = await restoreRes.json()
  expect(restoreBody.data?.archivedAt, 'restore 後 archivedAt=null').toBeNull()

  // user B にログイン切り替え
  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login が 200').toBe(200)

  // user B が A のテーマと同じ academicYear/termLabel で bulk-archive
  const bulkRes = await page.request.post(`${BE_API}/me/reflections/archive/bulk-archive`, {
    data: {
      academicYear: THEME_ACADEMIC_YEAR,
      termLabel: THEME_TERM_LABEL,
    },
  })
  expect(
    [200, 201, 204],
    `bulk-archive status が 200/201/204 のいずれかであること（実際: ${bulkRes.status()}）`,
  ).toContain(bulkRes.status())

  const bulkBody = bulkRes.status() !== 204 ? await bulkRes.json() : {}
  const archivedCount: number = bulkBody.data?.archivedCount ?? 0

  // user A に戻ってテーマがまだアクティブであることを確認
  const loginA = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_A_EMAIL, password: USER_A_PASSWORD },
  })
  expect(loginA.status(), 'user A 再ログインが 200').toBe(200)

  const themeRes = await page.request.get(
    `${BE_API}/me/reflections/themes/${aThemeId}`,
  )
  expect(
    themeRes.status(),
    `user A が自分のテーマを取得できること（status: ${themeRes.status()}）`,
  ).toBe(200)

  const themeBody = await themeRes.json()
  expect(
    themeBody.data?.archivedAt,
    `user B の bulk-archive によって user A のテーマ(${aThemeId})がアーカイブされてしまった（重大脆弱性）`,
  ).toBeNull()

  expect(
    archivedCount,
    `bulk-archive で B が A のテーマをアーカイブしてしまった（archivedCount=${archivedCount}）`,
  ).toBe(0)
})

// ---------------------------------------------------------------------------
// AUTHZ-P3-07: 未認証（cookie 無し）で Phase 3 全エンドポイント → 401
// ---------------------------------------------------------------------------
test('AUTHZ-P3-07: 未認証で Phase 3 全エンドポイント → 401', async ({ playwright }) => {
  expect(aThemeId, 'AUTHZ-P3-000 が成功していること').toBeTruthy()

  // cookie を一切持たない clean な request context
  const anon = await playwright.request.newContext({
    baseURL: BE,
    storageState: { cookies: [], origins: [] },
  })
  try {
    const endpoints: Array<{ method: 'get' | 'post' | 'patch'; path: string }> = [
      { method: 'get', path: '/me/reflections/archive/folders' },
      { method: 'get', path: `/me/reflections/archive/search?academicYear=${THEME_ACADEMIC_YEAR}` },
      { method: 'post', path: '/me/reflections/archive/bulk-archive' },
      { method: 'get', path: `/me/reflections/themes/${aThemeId}` },
      { method: 'patch', path: `/me/reflections/themes/${aThemeId}/archive` },
      { method: 'patch', path: `/me/reflections/themes/${aThemeId}/restore` },
    ]

    for (const ep of endpoints) {
      let res
      if (ep.method === 'get') {
        res = await anon.get(`${BE_API}${ep.path}`)
      } else if (ep.method === 'post') {
        res = await anon.post(`${BE_API}${ep.path}`, { data: {} })
      } else {
        res = await anon.patch(`${BE_API}${ep.path}`)
      }
      expect(
        res.status(),
        `未認証で ${ep.method.toUpperCase()} ${ep.path} が ${res.status()} を返した（401 期待）`,
      ).toBe(401)
    }
  } finally {
    await anon.dispose()
  }
})

// ---------------------------------------------------------------------------
// afterAll: クリーンアップ（Node.js fetch で直接 BE を叩く・beforeEach 不要）
// ---------------------------------------------------------------------------
test.afterAll(async () => {
  if (!aThemeId) return

  // Node.js fetch でログインしてクリーンアップ（beforeEach のレートリミットを回避）
  try {
    const loginRes = await fetch(`${BE_API}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: USER_A_EMAIL, password: USER_A_PASSWORD }),
    })
    if (!loginRes.ok) return

    const rawSetCookie = loginRes.headers.get('set-cookie') ?? ''
    const cookieHeader = rawSetCookie
      .split(/, ?(?=[a-z_]+=)/i)
      .map((c: string) => (c.split(';')[0] ?? '').trim())
      .filter((c: string) => c.includes('='))
      .join('; ')

    // restore（アーカイブ状態だと削除できない場合があるため）
    await fetch(`${BE_API}/me/reflections/themes/${aThemeId}/restore`, {
      method: 'PATCH',
      headers: { Cookie: cookieHeader },
    }).catch(() => {})

    // 削除
    await fetch(`${BE_API}/me/reflections/themes/${aThemeId}`, {
      method: 'DELETE',
      headers: { Cookie: cookieHeader },
    }).catch(() => {})
  }
  catch {
    // クリーンアップ失敗は無視（テスト結果に影響しない）
  }
})
