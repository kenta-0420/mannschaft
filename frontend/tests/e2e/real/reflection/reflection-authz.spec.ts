/**
 * F06.5 振り返り — 認可（F00 漏洩防止・IDOR）実機 E2E（モック不使用）
 *
 * 認可方針（BE）: 全エンドポイント「認証必須＋本人所有のみ」。他人所有リソースは IDOR 対策で
 * REFLECTION_001 = 404（NOT_FOUND）。未認証は 401。
 *
 * 単一セッション設計（reflection-active-recall.spec.ts 踏襲）:
 *   - beforeEach で page context cookie を user A（e2e-user）に fresh 化（別 context login 禁止）。
 *   - user B（e2e-admin）の検証は、同テスト内で page.request.post(login) により page の cookie ジャーを
 *     B に切り替えて行う（B のトークンで A のリソースを叩く）。次テストの beforeEach が再び A へ戻す。
 *   - 未認証 401 は、cookie を持たない standalone request（playwright の `request` フィクスチャ）で叩く。
 *
 * 2 ユーザーの用意:
 *   - user A = e2e-user@test.mannschaft.local（storageState/real-user.json・seed 済み）
 *   - user B = e2e-admin@test.mannschaft.local（real-admin.setup.ts と同一・seed 済み）
 *   どちらも backend/scripts/seed-e2e-data.js で投入される前提。
 *
 * カバー:
 *   AUTHZ-001: user A でテーマ／エントリ作成。
 *   AUTHZ-002: user B で A 所有のテーマ GET → 404（IDOR）。
 *   AUTHZ-003: user B で A 所有のエントリ GET → 404（IDOR）。
 *   AUTHZ-004: 未認証（cookie 無し）で reflection API → 401。
 */
import { test, expect } from '@playwright/test'

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const USER_A_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_A_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const USER_B_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const USER_B_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

const RUN_ID = Date.now()
const THEME_TITLE = `E2E 認可テーマ ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

// user A が作成したリソース ID（後続テストで B からアクセスして 404 を確認）。
let aThemeId = ''
let aEntryId = ''

// beforeEach で page cookie を user A に fresh 化する（単一セッション harness）。
test.beforeEach(async ({ page }) => {
  // 多数テスト連続実行で login のレート制限/瞬間負荷により稀に非200になるため数回リトライする。
  let loggedIn = false
  for (let i = 0; i < 5; i++) {
    const res = await page.request.post(`${BE_API}/auth/login`, {
      data: { email: USER_A_EMAIL, password: USER_A_PASSWORD },
    })
    if (res.status() === 200) { loggedIn = true; break }
    await page.waitForTimeout(2_000)
  }
  expect(loggedIn, 'beforeEach の user A login（リトライ込）が成功').toBe(true)
})

// ---------------------------------------------------------------------------
// AUTHZ-001: user A でテーマ／エントリ作成
// ---------------------------------------------------------------------------
test('AUTHZ-001: user A がテーマ／当日エントリを作成', async ({ page }) => {
  const themeRes = await page.request.post(`${BE_API}/me/reflections/themes`, {
    data: { title: THEME_TITLE, sourceType: 'FREE' },
  })
  expect(themeRes.status()).toBeLessThan(300)
  aThemeId = (await themeRes.json()).data.id
  expect(aThemeId).toBeTruthy()

  const today = new Date().toISOString().slice(0, 10)
  const entryRes = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId: aThemeId,
      targetDate: today,
      structuredContent: { main_theme: `A の秘密本文 ${RUN_ID}`, sections: [], free_note: '' },
    },
  })
  expect(entryRes.status()).toBeLessThan(300)
  aEntryId = (await entryRes.json()).data.id
  expect(aEntryId).toBeTruthy()
})

// ---------------------------------------------------------------------------
// AUTHZ-002: user B で A 所有テーマ GET → 404（IDOR）
// ---------------------------------------------------------------------------
test('AUTHZ-002: user B で A 所有テーマ GET → 404（IDOR）', async ({ page }) => {
  expect(aThemeId, 'AUTHZ-001 が成功していること').toBeTruthy()

  // page の cookie ジャーを user B（admin）へ切り替える。
  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status(), 'user B login は 200').toBe(200)

  // B 自身のテーマ一覧は 200（認証は通っている＝401 でない）。
  const ownList = await page.request.get(`${BE_API}/me/reflections/themes`)
  expect(ownList.status(), 'B 自身の一覧は 200（認証済み）').toBe(200)
  // B の一覧に A のテーマは含まれない（本人スコープ）。
  const bThemes = ((await ownList.json()).data ?? []) as Array<{ id: string }>
  expect(bThemes.some(t => t.id === aThemeId), 'B の一覧に A テーマは出ない').toBe(false)

  // B が A 所有テーマを直接 GET → 404（IDOR・REFLECTION_001）。
  const res = await page.request.get(`${BE_API}/me/reflections/themes/${aThemeId}`)
  expect(res.status(), '他人所有テーマ GET は 404').toBe(404)
})

// ---------------------------------------------------------------------------
// AUTHZ-003: user B で A 所有エントリ GET → 404（IDOR）
// ---------------------------------------------------------------------------
test('AUTHZ-003: user B で A 所有エントリ GET → 404（IDOR）', async ({ page }) => {
  expect(aEntryId).toBeTruthy()

  const loginB = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_B_EMAIL, password: USER_B_PASSWORD },
  })
  expect(loginB.status()).toBe(200)

  const res = await page.request.get(`${BE_API}/me/reflections/entries/${aEntryId}`)
  expect(res.status(), '他人所有エントリ GET は 404').toBe(404)

  // A 所有テーマ配下エントリ一覧も 404（theme 本人所有チェックで弾く）。
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes/${aThemeId}/entries`)
  expect(listRes.status(), '他人所有テーマ配下エントリ一覧は 404').toBe(404)
})

// ---------------------------------------------------------------------------
// AUTHZ-004: 未認証（cookie 無し）→ 401
// ---------------------------------------------------------------------------
test('AUTHZ-004: 未認証で reflection API → 401', async ({ playwright }) => {
  // cookie を一切持たない clean な request context を作る（page とは別・認証ヘッダ無し）。
  // newContext は project の storageState を継承しうるため、空の storageState を明示して
  // 確実に未認証（cookie 無し）にする。
  const anon = await playwright.request.newContext({
    baseURL: BE,
    storageState: { cookies: [], origins: [] },
  })
  try {
    const themesRes = await anon.get(`${BE_API}/me/reflections/themes`)
    expect(themesRes.status(), '未認証のテーマ一覧は 401').toBe(401)

    const settingsRes = await anon.get(`${BE_API}/me/reflections/settings`)
    expect(settingsRes.status(), '未認証の設定取得は 401').toBe(401)

    // 存在しうるリソースへの GET も認証が先（404 でなく 401）。
    if (aThemeId) {
      const themeRes = await anon.get(`${BE_API}/me/reflections/themes/${aThemeId}`)
      expect(themeRes.status(), '未認証は所有判定より前に 401').toBe(401)
    }
  }
  finally {
    await anon.dispose()
  }
})

// ---------------------------------------------------------------------------
// クリーンアップ（user A で削除・beforeEach が A に戻している）
// ---------------------------------------------------------------------------
test('AUTHZ-999: クリーンアップ（user A でテーマ削除）', async ({ page }) => {
  if (!aThemeId) return
  const res = await page.request.delete(`${BE_API}/me/reflections/themes/${aThemeId}`)
  expect([200, 204, 404]).toContain(res.status())
})
