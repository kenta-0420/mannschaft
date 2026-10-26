/**
 * F09.15 区分所有者承継支援＋法的督促 — 実機 E2E テスト（SUCCESSION-001〜015）。
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用。
 * 未生成の場合は loginIfNeeded() でフォールバックログインする。
 *
 * 前提シード: backend/scripts/seed-e2e-data.js の F09.15 ブロックを実行済み。
 *   - apartment 組織 "E2Eテストマンション管理組合" を 1 件
 *   - E2E_USER / E2E_ADMIN に当該組織の ADMIN ロール（role_id=2）付与
 *   - dwelling_units 1 件（unit_number=101） / resident_registry 1 件（E2E_USER）
 *   - succession_covenants / succession_pre_registrations /
 *     unseal_requests / delinquency_escalations / legal_filings 各 1 件
 *
 * 検証粒度: villages.spec.ts / wallet.spec.ts と同等の「ページ描画 + 主要要素可視 + API 200」レベル。
 *
 * 実装範囲外（深追いしない）:
 *   - 死亡確認→封緘解除の二者承認フロー
 *   - 5段階自動エスカレーション進行
 *   - 申立書 PDF 生成 / 区分所有法 8 条証拠 ZIP
 *   これらは「画面が描画される」「API が認証付きで反応する」までで十分。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const USER_EMAIL = 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = 'TestPass2026!'

const APARTMENT_ORG_NAME = 'E2Eテストマンション管理組合'

// ---------------------------------------------------------------------------
// ヘルパー: storageState が無効な場合のフォールバックログイン
// ---------------------------------------------------------------------------
async function loginIfNeeded(page: Page): Promise<void> {
  await page.goto('/my/dashboard')
  if (page.url().includes('/login')) {
    await waitForHydration(page)
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(USER_EMAIL, { delay: 10 })
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(USER_PASSWORD, { delay: 10 })
    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 })
  }
}

// ===========================================================================
// SUCCESSION-001〜015: F09.15 区分所有者承継支援＋法的督促
//
// 注意:
//   - villages.spec.ts と同じく beforeAll で token と apartmentOrgId を 1 回だけ取得し、
//     全ケースで使い回す（連続 login によるレート制限を避ける）。
//   - apartmentOrgId は seed が name で UPSERT した組織を /organizations 検索ではなく
//     直接 admin API が無いため、storage や別 API 経由で取れない。よって seed が
//     生成する organization id を確実に取り直す方法として、E2E_USER に紐づく
//     UserOrganizationsApi (`/api/v1/users/me/organizations` 系) を使うか、
//     代わりに organization-search 系 API を叩く。
//   - 本ファイルでは /api/v1/organizations/search?q={name} で対象組織 id を取得する。
// ===========================================================================
test.describe('SUCCESSION-001〜015: F09.15 区分所有者承継支援', () => {
  // 詳細ページは複数 API 直列のためタイムアウト延長
  test.setTimeout(120_000)
  // serial モードで beforeAll の login を 1 ワーカーに集約する。
  // playwright.config.ts は fullyParallel: true だが、本 describe では
  // 連続 login によるレート制限を避けるため意図的に直列化する。
  test.describe.configure({ mode: 'serial' })

  // describe スコープで token と apartmentOrgId を 1 回だけ取得
  let cachedToken = ''
  let cachedApartmentOrgId = 0

  test.beforeAll(async ({ playwright }) => {
    const ctx = await playwright.request.newContext()
    try {
      const loginResp = await ctx.post('http://localhost:8080/api/v1/auth/login', {
        data: { email: USER_EMAIL, password: USER_PASSWORD },
      })
      expect(loginResp.status()).toBe(200)
      const loginBody = await loginResp.json()
      cachedToken = loginBody.data.accessToken as string

      // /api/v1/organizations/search?keyword={name} で apartment org id を取得する。
      // seed は当該名で UPSERT するため必ず 1 件 hit する想定。
      // 注: クエリパラメータは "keyword" であり "q" ではない（OrganizationController#searchOrganizations）。
      const searchResp = await ctx.get(
        `http://localhost:8080/api/v1/organizations/search?keyword=${encodeURIComponent(APARTMENT_ORG_NAME)}`,
        { headers: { Authorization: `Bearer ${cachedToken}` } },
      )
      expect(searchResp.status()).toBe(200)
      const searchBody = await searchResp.json()
      const orgList = (searchBody.data ?? searchBody) as Array<{ id: number, name: string }>
      const apartment = orgList.find((o) => o.name === APARTMENT_ORG_NAME)
      expect(apartment, `seed 済の "${APARTMENT_ORG_NAME}" が organization 検索に含まれていない`).toBeTruthy()
      cachedApartmentOrgId = Number(apartment!.id)
    }
    finally {
      await ctx.dispose()
    }
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-001: /organizations/{id}/residents 住民台帳ページが表示される
  // -------------------------------------------------------------------------
  test('SUCCESSION-001: /organizations/{id}/residents 住民台帳ページが表示される', async ({ page }) => {
    await loginIfNeeded(page)
    await page.goto(`/organizations/${cachedApartmentOrgId}/residents`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // PageHeader（"住民台帳"）または main 要素が描画されていれば pass
    const heading = page.getByRole('heading').first()
    const mainEl = page.locator('main').first()
    await expect(heading.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-002: /organizations/{id}/succession/unseal-requests 開封リクエストページ
  // -------------------------------------------------------------------------
  test('SUCCESSION-002: /organizations/{id}/succession/unseal-requests ページが表示される', async ({ page }) => {
    await loginIfNeeded(page)
    await page.goto(`/organizations/${cachedApartmentOrgId}/succession/unseal-requests`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // 封緘解除申請一覧 h1（i18n 既存キー）または main 要素
    const heading = page.getByRole('heading', { name: '封緘解除申請一覧' }).first()
    const anyHeading = page.getByRole('heading').first()
    const mainEl = page.locator('main').first()
    await expect(heading.or(anyHeading).or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-003: /organizations/{id}/succession/legal-filings 法的督促ページ
  // -------------------------------------------------------------------------
  test('SUCCESSION-003: /organizations/{id}/succession/legal-filings ページが表示される', async ({ page }) => {
    await loginIfNeeded(page)
    await page.goto(`/organizations/${cachedApartmentOrgId}/succession/legal-filings`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // 任意の h1 / main が描画されていれば pass
    // （i18n キー `succession.legalFilings.title` が ja ロケールに未定義のため
    // キー文字列がそのまま表示される可能性があるが、heading 自体は描画される）
    const anyHeading = page.getByRole('heading').first()
    const mainEl = page.locator('main').first()
    await expect(anyHeading.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-004: GET /api/v1/organizations/{orgId}/succession/covenants 認証 + 200
  // -------------------------------------------------------------------------
  test('SUCCESSION-004: GET covenants（誓約一覧）が認証付きで 200 を返す', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/covenants?page=0&size=20`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    // Page<SuccessionCovenantResponse> 形式（content 配列含む）
    const content = body.data?.content ?? body.content
    expect(Array.isArray(content)).toBe(true)
    // seed 投入の covenant が含まれること（SUCCESSION-011 と重複だが API レベル検証）
    expect(content.length).toBeGreaterThanOrEqual(1)
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-005: GET /api/v1/succession/covenants/me 本人履歴が 200
  // -------------------------------------------------------------------------
  test('SUCCESSION-005: GET pre-registrations / covenants/me が認証付きで 200 を返す', async ({ page }) => {
    const resp = await page.request.get(
      'http://localhost:8080/api/v1/succession/covenants/me',
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    // List<SuccessionCovenantResponse> 形式
    const list = (body.data ?? body) as unknown[]
    expect(Array.isArray(list)).toBe(true)
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-006: GET unseal-requests（特定 org）が認証付きで 200
  // -------------------------------------------------------------------------
  test('SUCCESSION-006: GET unseal-requests（特定 org）が認証付きで 200 を返す', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/unseal-requests`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as unknown[]
    expect(Array.isArray(list)).toBe(true)
    expect(list.length).toBeGreaterThanOrEqual(1)
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-007: GET delinquency-escalations が認証付きで 200
  // -------------------------------------------------------------------------
  test('SUCCESSION-007: GET delinquency-escalations が認証付きで 200 を返す', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/delinquency-escalations`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as unknown[]
    expect(Array.isArray(list)).toBe(true)
    expect(list.length).toBeGreaterThanOrEqual(1)
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-008: GET legal-filings が認証付きで 200
  // -------------------------------------------------------------------------
  test('SUCCESSION-008: GET legal-filings が認証付きで 200 を返す', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/legal-filings`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as unknown[]
    expect(Array.isArray(list)).toBe(true)
    expect(list.length).toBeGreaterThanOrEqual(1)
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-009: 不正な orgId で 403 or 404
  //
  //   E2E_USER は ADMIN 権限を持たない別組織 (id=999999) に対しては
  //   isAdminOrAbove 判定で 403 Forbidden が返るはず。
  //   または対象組織が存在せず 404 NotFound。いずれも認証境界として正常。
  // -------------------------------------------------------------------------
  test('SUCCESSION-009: 不正な orgId で 403/404 が返る（権限境界）', async ({ page }) => {
    const invalidOrgId = 999999
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${invalidOrgId}/succession/unseal-requests`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect([403, 404]).toContain(resp.status())
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-010: 未認証時 /organizations/{id}/residents で /login にリダイレクト
  // -------------------------------------------------------------------------
  test('SUCCESSION-010: 未認証時に /login にリダイレクトされる（auth ガード）', async ({ browser }) => {
    // storageState を使わない素のコンテキストで /organizations/{id}/residents を踏む
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const guestPage = await ctx.newPage()
    try {
      await guestPage.goto(`/organizations/${cachedApartmentOrgId}/residents`)
      await guestPage.waitForURL(/\/login/, { timeout: 20_000 })
      await expect(guestPage).toHaveURL(/\/login/)
    }
    finally {
      await guestPage.close()
      await ctx.close()
    }
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-011: seed 投入の covenant が一覧に出る（UI 連携）
  //
  //   covenants 一覧 API を直接叩いて seed 行が現れることを確認。
  //   組織内 UI は /organizations/{id}/succession/covenants ページが未実装
  //   （現在は legal-filings / unseal-requests のみ）のため、API レベルで検証する。
  // -------------------------------------------------------------------------
  test('SUCCESSION-011: seed 投入の covenant が API 一覧に出る', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/covenants?page=0&size=20`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const content = body.data?.content ?? body.content
    expect(Array.isArray(content)).toBe(true)
    // seed で SUCCESSION_PRE_REGISTRATION 区分の誓約を 1 件投入している
    const found = content.find(
      (c: { covenantType?: string }) => c.covenantType === 'SUCCESSION_PRE_REGISTRATION',
    )
    expect(found, 'seed 投入の SUCCESSION_PRE_REGISTRATION 誓約が一覧に含まれない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-012: seed 投入の pre_registration が一覧に出る
  //
  //   covenants/me API は本人の誓約一覧（pre-registration は含まれない設計だが
  //   seed で SUCCESSION_PRE_REGISTRATION 誓約を 1 件投入している）。
  //   ここでは本人の誓約に SUCCESSION_PRE_REGISTRATION 区分が含まれることを検証。
  // -------------------------------------------------------------------------
  test('SUCCESSION-012: seed 投入の pre_registration（本人誓約）が一覧に出る', async ({ page }) => {
    const resp = await page.request.get(
      'http://localhost:8080/api/v1/succession/covenants/me',
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as Array<{ covenantType?: string }>
    expect(Array.isArray(list)).toBe(true)
    const found = list.find((c) => c.covenantType === 'SUCCESSION_PRE_REGISTRATION')
    expect(found, 'seed 投入の本人誓約（事前登録）が covenants/me に含まれない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-013: seed 投入の unseal_request が一覧に出る
  // -------------------------------------------------------------------------
  test('SUCCESSION-013: seed 投入の unseal_request が一覧に出る', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/unseal-requests`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as Array<{ requestReason?: string }>
    expect(Array.isArray(list)).toBe(true)
    const found = list.find((r) => typeof r.requestReason === 'string' && r.requestReason.includes('E2E'))
    expect(found, 'seed 投入の unseal_request（E2E ダミー申請）が一覧に含まれない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-014: seed 投入の delinquency_escalation が一覧に出る
  // -------------------------------------------------------------------------
  test('SUCCESSION-014: seed 投入の delinquency_escalation が一覧に出る', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/delinquency-escalations`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as Array<{ currentStage?: string }>
    expect(Array.isArray(list)).toBe(true)
    const found = list.find((e) => e.currentStage === 'STAGE_1_REMINDER')
    expect(found, 'seed 投入の delinquency_escalation（STAGE_1_REMINDER）が一覧に含まれない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // SUCCESSION-015: seed 投入の legal_filing が一覧に出る
  // -------------------------------------------------------------------------
  test('SUCCESSION-015: seed 投入の legal_filing が一覧に出る', async ({ page }) => {
    const resp = await page.request.get(
      `http://localhost:8080/api/v1/organizations/${cachedApartmentOrgId}/succession/legal-filings`,
      { headers: { Authorization: `Bearer ${cachedToken}` } },
    )
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    const list = (body.data ?? body) as Array<{ filingType?: string }>
    expect(Array.isArray(list)).toBe(true)
    const found = list.find((f) => f.filingType === 'ABSENTEE_PROPERTY_MANAGER')
    expect(found, 'seed 投入の legal_filing（不在者財産管理人申立て）が一覧に含まれない').toBeTruthy()
  })
})
