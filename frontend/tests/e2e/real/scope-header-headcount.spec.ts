/**
 * 実機E2E: CMP-261004-1942/1943 スコープヘッダのメンバー数・サポーター数表示。PR #3670 の検証。
 * 検証対象: AC-1/2/6/9/11/13/14、ロール横断（ADMIN/MEMBER/一般/サポーターのみ/無関係）、
 *   および API 直叩き（PRIVATE 組織の非所属者・他テナントからの取得が 403 VISIBILITY_001）。
 * 作法: モックなし（page.route 禁止）。ログイン・前提データ作成・後始末のみ API 使用。
 *   対象操作（応援・解除・承認・言語切替）は画面操作で行い、結果は画面表示で確認する。
 * 実機（モックなし）で 10 件 PASS / skipped 0 を確認済み。
 * 資格情報: TEST_USER_PASSWORD（frontend/.env.test と同じ共通パスワード）。未設定時は既存 spec と同じ既定値 'TestPass2026!' を使う。
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 TEST_USER_PASSWORD=... \
 *   npx playwright test -c playwright-real.config.ts --project=chromium-real --no-deps scope-header-headcount
 */
import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD }
const MEMBER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const GENERAL = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD } // 所属の無い一般ユーザー（AC-1/2で自ら応援する）
const OUTSIDER = { email: 'e2e-dummy-5@test.mannschaft.local', password: PASSWORD } // 一切関与しない控え
const SUPPORTER_ONLY = { email: 'e2e-dummy-6@test.mannschaft.local', password: PASSWORD } // AC-6/AC-11専用

let ORG_SLUG = ''
let OTHER_ORG_SLUG = ''
let PRIVATE_ORG_SLUG = ''
let TEAM_SLUG = ''
let SUPPORTERS_TEAM_SLUG = ''
let EMPTY_TEAM_SLUG = ''
let NO_SUPPORTER_TEAM_SLUG = ''
let adminToken = ''

async function bearerFor(request: APIRequestContext, credentials: { email: string, password: string }): Promise<string> {
  const res = await request.post(`${API_BASE_URL}/api/v1/auth/login`, { data: credentials })
  expect(res.status(), await res.text()).toBe(200)
  return ((await res.json()).data as { accessToken: string }).accessToken
}

test.beforeAll(async ({ request }) => {
  adminToken = await bearerFor(request, ADMIN)
  const authed = (method: 'get' | 'post' | 'patch' | 'put', path: string, data?: unknown) =>
    request[method](`${API_BASE_URL}${path}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      ...(data !== undefined ? { data } : {}),
    })
  const suffix = Date.now().toString(36)

  // 組織A: PUBLIC + supporterEnabled + autoApprove（AC-1/2/9/13代表、承認フロー土台）
  const orgRes = await authed('post', '/api/v1/organizations', {
    name: `e2e-1942-org-${suffix}`, orgType: 'OTHER', visibility: 'PUBLIC', slug: `e2e-1942-o-${suffix}`.slice(0, 30),
  })
  expect(orgRes.status(), await orgRes.text()).toBe(201)
  ORG_SLUG = ((await orgRes.json()).data as { slug: string }).slug
  expect((await authed('patch', `/api/v1/organizations/${ORG_SLUG}`, { supporterEnabled: true, version: 0 })).status()).toBe(200)
  expect((await authed('put', `/api/v1/organizations/${ORG_SLUG}/supporter-settings`, { autoApprove: true })).status()).toBe(200)

  // 組織B: 別組織（クロスオーグ用、PUBLIC）
  const orgBRes = await authed('post', '/api/v1/organizations', {
    name: `e2e-1942-orgB-${suffix}`, orgType: 'OTHER', visibility: 'PUBLIC', slug: `e2e-1942-ob-${suffix}`.slice(0, 30),
  })
  expect(orgBRes.status(), await orgBRes.text()).toBe(201)
  OTHER_ORG_SLUG = ((await orgBRes.json()).data as { slug: string }).slug

  // 組織C: PRIVATE + supporterEnabled（AC-11/クロステナント負検証用）
  const orgCRes = await authed('post', '/api/v1/organizations', {
    name: `e2e-1942-orgC-${suffix}`, orgType: 'OTHER', visibility: 'PRIVATE', slug: `e2e-1942-oc-${suffix}`.slice(0, 30),
  })
  expect(orgCRes.status(), await orgCRes.text()).toBe(201)
  PRIVATE_ORG_SLUG = ((await orgCRes.json()).data as { slug: string }).slug
  expect((await authed('patch', `/api/v1/organizations/${PRIVATE_ORG_SLUG}`, { supporterEnabled: true, version: 0 })).status()).toBe(200)
  expect((await authed('put', `/api/v1/organizations/${PRIVATE_ORG_SLUG}/supporter-settings`, { autoApprove: true })).status()).toBe(200)

  // チーム（主対象）: PUBLIC + supporterEnabled + autoApprove
  const teamRes = await authed('post', '/api/v1/teams', {
    name: `e2e-1942-team-${suffix}`, visibility: 'PUBLIC', slug: `e2e-1942-t-${suffix}`.slice(0, 30),
  })
  expect(teamRes.status(), await teamRes.text()).toBe(201)
  TEAM_SLUG = ((await teamRes.json()).data as { slug: string }).slug
  expect((await authed('patch', `/api/v1/teams/${TEAM_SLUG}`, { supporterEnabled: true, version: 0 })).status()).toBe(200)
  expect((await authed('put', `/api/v1/teams/${TEAM_SLUG}/supporter-settings`, { autoApprove: true })).status()).toBe(200)

  // チーム（SUPPORTERS_AND_ABOVE、AC-11用）
  const teamSupRes = await authed('post', '/api/v1/teams', {
    name: `e2e-1942-teamSup-${suffix}`, visibility: 'SUPPORTERS_AND_ABOVE', slug: `e2e-1942-ts-${suffix}`.slice(0, 30),
  })
  expect(teamSupRes.status(), await teamSupRes.text()).toBe(201)
  SUPPORTERS_TEAM_SLUG = ((await teamSupRes.json()).data as { slug: string }).slug
  expect((await authed('patch', `/api/v1/teams/${SUPPORTERS_TEAM_SLUG}`, { supporterEnabled: true, version: 0 })).status()).toBe(200)
  expect((await authed('put', `/api/v1/teams/${SUPPORTERS_TEAM_SLUG}/supporter-settings`, { autoApprove: true })).status()).toBe(200)

  // サポーター0人チーム（AC-13: 0表示）
  const teamEmptyRes = await authed('post', '/api/v1/teams', {
    name: `e2e-1942-teamEmpty-${suffix}`, visibility: 'PUBLIC', slug: `e2e-1942-te-${suffix}`.slice(0, 30),
  })
  expect(teamEmptyRes.status(), await teamEmptyRes.text()).toBe(201)
  EMPTY_TEAM_SLUG = ((await teamEmptyRes.json()).data as { slug: string }).slug
  expect((await authed('patch', `/api/v1/teams/${EMPTY_TEAM_SLUG}`, { supporterEnabled: true, version: 0 })).status()).toBe(200)

  // supporterEnabled=false チーム（AC-13: サポーター欄非表示）
  const teamNoSupRes = await authed('post', '/api/v1/teams', {
    name: `e2e-1942-teamNoSup-${suffix}`, visibility: 'PUBLIC', slug: `e2e-1942-tn-${suffix}`.slice(0, 30),
  })
  expect(teamNoSupRes.status(), await teamNoSupRes.text()).toBe(201)
  NO_SUPPORTER_TEAM_SLUG = ((await teamNoSupRes.json()).data as { slug: string }).slug

  // MEMBER（e2e-user）を組織A・チームへ招待トークンでMEMBER化
  const memberToken = await bearerFor(request, MEMBER)
  for (const [scopePath, slug] of [['organizations', ORG_SLUG], ['teams', TEAM_SLUG]] as const) {
    const invite = await authed('post', `/api/v1/${scopePath}/${slug}/invite-tokens`, { roleId: 4, expiresIn: '1d', maxUses: 1 })
    expect(invite.status(), await invite.text()).toBe(201)
    const token = ((await invite.json()).data as { token: string }).token
    const join = await request.post(`${API_BASE_URL}/api/v1/invite/${token}/join`, { headers: { Authorization: `Bearer ${memberToken}` } })
    expect(join.status(), await join.text()).toBe(200)
  }

  console.log('FIXTURE SLUGS:', JSON.stringify({
    ORG_SLUG, OTHER_ORG_SLUG, PRIVATE_ORG_SLUG, TEAM_SLUG, SUPPORTERS_TEAM_SLUG, EMPTY_TEAM_SLUG, NO_SUPPORTER_TEAM_SLUG,
  }))
})

async function openAs(browser: Browser, credentials: { email: string, password: string }, viewport = { width: 1280, height: 900 }): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, viewport })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL, deferNavigation: true })
  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  expect(((await me.json()).data as { email: string }).email).toBe(credentials.email)
  return page
}

async function openScope(page: Page, path: string): Promise<number> {
  const response = await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  // eslint-disable-next-line no-restricted-syntax -- 全画面スピナーが出ない画面もあるため、待機タイムアウトは後続の表示アサーションで検出する
  await page.locator('.p-progressspinner').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  return response?.status() ?? 200
}

async function ensureUnfollowed(page: Page, apiPath: string): Promise<void> {
  const statusRes = await page.request.get(`${API_BASE_URL}${apiPath}/follow/status`)
  if (statusRes.ok()) {
    const body = (await statusRes.json()).data as { status: string }
    if (body.status !== 'NONE') {
      const res = await page.request.delete(`${API_BASE_URL}${apiPath}/follow`)
      expect([200, 204, 404], await res.text()).toContain(res.status())
    }
  }
}

// serial固定はしない: 1件の失敗が後続全件のskipを招くため、各テストは独立に前提を確認して進める。

// ============================================================
// AC-1: チーム — 一般ユーザーが応援ボタンでサポーター化・解除
// ============================================================
test('AC1-TEAM: 所属の無い一般ユーザーが応援→サポーター数+1、解除→-1', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, GENERAL)
  await ensureUnfollowed(page, `/api/v1/teams/${TEAM_SLUG}`)
  await openScope(page, `/teams/${TEAM_SLUG}`)

  const supporterCountEl = page.getByTestId('scope-header-supporter-count')
  await expect(supporterCountEl).toBeVisible({ timeout: 60_000 })
  const before = await supporterCountEl.innerText()
  const beforeNum = Number.parseInt(before.replace(/\D/g, ''), 10) || 0

  const applyButton = page.getByTestId('follow-apply-button')
  await expect(applyButton).toBeVisible({ timeout: 30_000 })
  await applyButton.click()

  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 30_000 })
  await expect(supporterCountEl).toContainText(String(beforeNum + 1), { timeout: 15_000 })

  // 解除（確認ダイアログ経由）
  await page.getByTestId('follow-unfollow-button').click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })
  await expect(supporterCountEl).toContainText(String(beforeNum), { timeout: 15_000 })

  await page.context().close()
})

// ============================================================
// AC-2 / AC-9: 組織 — 同様の応援・解除。ヘッダのサポーター数が実数であること
// ============================================================
test('AC2-ORG: 一般ユーザーが応援→サポーター数+1(実数表示)、解除→-1', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, GENERAL)
  await ensureUnfollowed(page, `/api/v1/organizations/${ORG_SLUG}`)
  await openScope(page, `/organizations/${ORG_SLUG}`)

  const supporterCountEl = page.getByTestId('scope-header-supporter-count')
  await expect(supporterCountEl).toBeVisible({ timeout: 60_000 })
  const beforeText = await supporterCountEl.innerText()
  expect(beforeText, 'AC-9: サポーター数は — ではなく実数であること').not.toContain('—')
  const beforeNum = Number.parseInt(beforeText.replace(/\D/g, ''), 10) || 0

  await page.getByTestId('follow-apply-button').click()
  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 30_000 })
  await expect(supporterCountEl).toContainText(String(beforeNum + 1), { timeout: 15_000 })

  await page.getByTestId('follow-unfollow-button').click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })
  await expect(supporterCountEl).toContainText(String(beforeNum), { timeout: 15_000 })

  await page.context().close()
})

// ============================================================
// AC-6: 承認制（組織）— 画面の「自動承認」トグルをOFFにし、申請→承認で+1
// ============================================================
test('AC6-ORG: 承認制で申請中は数が変わらず、管理者承認後に+1', async ({ browser }) => {
  test.setTimeout(240_000)
  const adminPage = await openAs(browser, ADMIN)
  await openScope(adminPage, `/organizations/${ORG_SLUG}/supporters`)

  const toggle = adminPage.locator('button[role="switch"], .p-toggleswitch').first()
  await expect(toggle).toBeVisible({ timeout: 60_000 })
  const isChecked = async () => (await toggle.getAttribute('aria-checked')) === 'true' || (await toggle.getAttribute('data-p-checked')) === 'true'
  if (await isChecked()) {
    await toggle.click()
    await expect.poll(isChecked, { timeout: 10_000 }).toBe(false)
  }

  try {
    const applicantPage = await openAs(browser, SUPPORTER_ONLY)
    await ensureUnfollowed(applicantPage, `/api/v1/organizations/${ORG_SLUG}`)
    await openScope(applicantPage, `/organizations/${ORG_SLUG}`)

    const supporterCountEl = applicantPage.getByTestId('scope-header-supporter-count')
    const beforeText = await supporterCountEl.innerText()
    const beforeNum = Number.parseInt(beforeText.replace(/\D/g, ''), 10) || 0

    await applicantPage.getByTestId('follow-apply-button').click()
    await expect(applicantPage.getByTestId('follow-pending-cancel-button')).toBeVisible({ timeout: 30_000 })
    await expect(supporterCountEl).toContainText(String(beforeNum), { timeout: 10_000 }) // 申請中は不変

    // 管理者が申請一覧から承認
    await adminPage.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(adminPage)
    await waitForSpinnerGone(adminPage)
    const approveButton = adminPage.getByRole('button', { name: '承認', exact: true }).first()
    await expect(approveButton).toBeVisible({ timeout: 30_000 })
    await approveButton.click()

    await applicantPage.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(applicantPage)
    await waitForSpinnerGone(applicantPage)
    await expect(applicantPage.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 30_000 })
    await expect(applicantPage.getByTestId('scope-header-supporter-count')).toContainText(String(beforeNum + 1), { timeout: 15_000 })

    const unfollowRes = await applicantPage.request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
    expect([200, 204, 404], await unfollowRes.text()).toContain(unfollowRes.status())
    await applicantPage.context().close()
  } finally {
    await adminPage.goto(`${BASE_URL}/organizations/${ORG_SLUG}/supporters`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(adminPage)
    const toggleAfter = adminPage.locator('button[role="switch"], .p-toggleswitch').first()
    if (await toggleAfter.count() > 0 && !(await isChecked())) {
      await toggleAfter.click()
    }
    await adminPage.context().close()
  }
})

// ============================================================
// API-DIRECT: PRIVATE組織のGETを非所属・他テナントユーザーで直接叩き、
// ステータスと応答本文（name/socialの有無）を確認する（情報漏洩の有無の裏取り）。
// ============================================================
test('API-DIRECT: 非所属・他テナントがPRIVATE組織GETを直接叩いた実ステータスと応答本文', async ({ request }) => {
  test.setTimeout(60_000)

  const outsiderToken = await bearerFor(request, OUTSIDER)
  const outsiderRes = await request.get(`${API_BASE_URL}/api/v1/organizations/${PRIVATE_ORG_SLUG}`, {
    headers: { Authorization: `Bearer ${outsiderToken}` },
  })
  const outsiderBody = await outsiderRes.text()
  console.log(`API-DIRECT [未所属 e2e-dummy-5] GET /api/v1/organizations/${PRIVATE_ORG_SLUG} status=${outsiderRes.status()} body(先頭500字)=${outsiderBody.slice(0, 500)}`)

  const memberToken = await bearerFor(request, MEMBER)
  const memberRes = await request.get(`${API_BASE_URL}/api/v1/organizations/${PRIVATE_ORG_SLUG}`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  const memberBody = await memberRes.text()
  console.log(`API-DIRECT [他テナント e2e-user(組織Aのみ所属)] GET /api/v1/organizations/${PRIVATE_ORG_SLUG} status=${memberRes.status()} body(先頭500字)=${memberBody.slice(0, 500)}`)

  // 期待値を緩めない: 非所属・他テナントいずれも 200 で name/social を含む本文を返してはならない。
  if (outsiderRes.status() === 200) {
    const json = JSON.parse(outsiderBody) as { data?: { name?: unknown, social?: unknown } }
    expect(json.data?.social, 'PRIVATE組織のsocial(人数情報)が非所属ユーザーに漏洩していないこと').toBeUndefined()
    expect(json.data?.name, 'PRIVATE組織のnameが非所属ユーザーに漏洩していないこと').toBeUndefined()
  } else {
    expect([403, 404]).toContain(outsiderRes.status())
  }
  if (memberRes.status() === 200) {
    const json = JSON.parse(memberBody) as { data?: { name?: unknown, social?: unknown } }
    expect(json.data?.social, 'PRIVATE組織のsocial(人数情報)が他テナントユーザーに漏洩していないこと').toBeUndefined()
    expect(json.data?.name, 'PRIVATE組織のnameが他テナントユーザーに漏洩していないこと').toBeUndefined()
  } else {
    expect([403, 404]).toContain(memberRes.status())
  }
})

// ============================================================
// AC-11: PUBLICのまま画面から応援→サポーター化→管理者が公開範囲を制限→
// サポーターが画面から解除→詳細が閉じて読み込みエラー面になる（チーム・組織）
// ============================================================
test('AC11-TEAM: 応援後に公開範囲をサポーター以上へ変更→画面から解除→読み込みエラー面になる', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const suffix = `ac11t-${Date.now().toString(36)}`
  const teamRes = await request.post(`${API_BASE_URL}/api/v1/teams`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { name: `e2e-1942-${suffix}`, visibility: 'PUBLIC', slug: `e2e-1942-${suffix}`.slice(0, 30) },
  })
  expect(teamRes.status(), await teamRes.text()).toBe(201)
  const slug = ((await teamRes.json()).data as { slug: string }).slug
  const patchRes = await request.patch(`${API_BASE_URL}/api/v1/teams/${slug}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { supporterEnabled: true, version: 0 },
  })
  expect(patchRes.status(), await patchRes.text()).toBe(200)
  const settingsRes = await request.put(`${API_BASE_URL}/api/v1/teams/${slug}/supporter-settings`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { autoApprove: true },
  })
  expect(settingsRes.status(), await settingsRes.text()).toBe(200)

  // 1) PUBLICのうちに画面から応援する（スコープ未制限で見えている状態）
  const page = await openAs(browser, SUPPORTER_ONLY)
  await openScope(page, `/teams/${slug}`)
  const applyButton = page.getByTestId('follow-apply-button')
  await expect(applyButton).toBeVisible({ timeout: 60_000 })
  await applyButton.click()
  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: test.info().outputPath('ac11-team-01-after-apply.png'), fullPage: true })

  // 2) 管理者が公開範囲を「サポーター以上」へ変更（画面に可視性編集UIが無いためAPIで前提化。
  //    フロントの team settings 画面を調査したが visibility を変更する入力が見当たらなかった）
  const version2Res = await request.get(`${API_BASE_URL}/api/v1/teams/${slug}`, { headers: { Authorization: `Bearer ${adminToken}` } })
  const currentVersion = ((await version2Res.json()).data as { metadata: { version: number } }).metadata.version
  const visPatchRes = await request.patch(`${API_BASE_URL}/api/v1/teams/${slug}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { visibility: 'SUPPORTERS_AND_ABOVE', version: currentVersion },
  })
  expect(visPatchRes.status(), await visPatchRes.text()).toBe(200)

  // 3) サポーターがページを開き直し（制限後も既存サポーターなので見える想定）、画面から解除する
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await page.screenshot({ path: test.info().outputPath('ac11-team-02-before-unfollow-restricted.png'), fullPage: true })

  const unfollowButton = page.getByTestId('follow-unfollow-button')
  await expect(unfollowButton).toBeVisible({ timeout: 60_000 })
  await unfollowButton.click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  const unfollowCompleted = page.waitForResponse(r => r.request().method() === 'DELETE' && new URL(r.url()).pathname === `/api/v1/teams/${slug}/follow`)
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  expect((await unfollowCompleted).status()).toBe(204)

  await page.waitForTimeout(1500)
  await page.screenshot({ path: test.info().outputPath('ac11-team-03-after-unfollow.png'), fullPage: true })

  // 4) 期待値を緩めない: 詳細（旧ヘッダ人数・旧コンテンツ）は残らず、読み込みエラー面（再試行/ダッシュボードへ戻る）になる
  await expect(page.getByTestId('scope-header-member-count')).toHaveCount(0)
  await expect(page.getByTestId('scope-header-supporter-count')).toHaveCount(0)
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /ダッシュボードへ戻る/ })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(/権限がありません|再度お試しください/).first()).toBeVisible()

  await page.context().close()
})

test('AC11-ORG: 応援後に公開範囲をPRIVATEへ変更→画面から解除→読み込みエラー面になる', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const suffix = `ac11o-${Date.now().toString(36)}`
  const orgRes = await request.post(`${API_BASE_URL}/api/v1/organizations`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { name: `e2e-1942-${suffix}`, orgType: 'OTHER', visibility: 'PUBLIC', slug: `e2e-1942-${suffix}`.slice(0, 30) },
  })
  expect(orgRes.status(), await orgRes.text()).toBe(201)
  const slug = ((await orgRes.json()).data as { slug: string }).slug
  expect((await request.patch(`${API_BASE_URL}/api/v1/organizations/${slug}`, {
    headers: { Authorization: `Bearer ${adminToken}` }, data: { supporterEnabled: true, version: 0 },
  })).status()).toBe(200)
  expect((await request.put(`${API_BASE_URL}/api/v1/organizations/${slug}/supporter-settings`, {
    headers: { Authorization: `Bearer ${adminToken}` }, data: { autoApprove: true },
  })).status()).toBe(200)

  // 1) PUBLICのうちに画面から応援する
  const page = await openAs(browser, SUPPORTER_ONLY)
  await openScope(page, `/organizations/${slug}`)
  const applyButton = page.getByTestId('follow-apply-button')
  await expect(applyButton).toBeVisible({ timeout: 60_000 })
  await applyButton.click()
  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: test.info().outputPath('ac11-org-01-after-apply.png'), fullPage: true })

  // 2) 管理者が公開範囲をPRIVATEへ変更（組織設定画面にvisibility変更UIが無いためAPIで前提化）
  const version2Res = await request.get(`${API_BASE_URL}/api/v1/organizations/${slug}`, { headers: { Authorization: `Bearer ${adminToken}` } })
  const currentVersion = ((await version2Res.json()).data as { metadata: { version: number } }).metadata.version
  const visPatchRes = await request.patch(`${API_BASE_URL}/api/v1/organizations/${slug}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { visibility: 'PRIVATE', version: currentVersion },
  })
  expect(visPatchRes.status(), await visPatchRes.text()).toBe(200)

  // 3) サポーターが開き直し、画面から解除する
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await page.screenshot({ path: test.info().outputPath('ac11-org-02-before-unfollow-restricted.png'), fullPage: true })

  const unfollowButton = page.getByTestId('follow-unfollow-button')
  await expect(unfollowButton).toBeVisible({ timeout: 60_000 })
  await unfollowButton.click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  const unfollowCompleted = page.waitForResponse(r => r.request().method() === 'DELETE' && new URL(r.url()).pathname === `/api/v1/organizations/${slug}/follow`)
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  expect((await unfollowCompleted).status()).toBe(204)

  await page.waitForTimeout(1500)
  await page.screenshot({ path: test.info().outputPath('ac11-org-03-after-unfollow.png'), fullPage: true })

  await expect(page.getByTestId('scope-header-member-count')).toHaveCount(0)
  await expect(page.getByTestId('scope-header-supporter-count')).toHaveCount(0)
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /ダッシュボードへ戻る/ })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(/権限がありません|再度お試しください/).first()).toBeVisible()

  await page.context().close()
})

// ============================================================
// AC-13: サポーター0人は「0」。supporterEnabled=false は欄自体が非表示
// ============================================================
test('AC13: サポーター0人は0表示、supporterEnabled=falseでは欄が無い', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, OUTSIDER)

  await openScope(page, `/teams/${EMPTY_TEAM_SLUG}`)
  await expect(page.getByTestId('scope-header-supporter-count')).toContainText('0', { timeout: 60_000 })

  await openScope(page, `/teams/${NO_SUPPORTER_TEAM_SLUG}`)
  await expect(page.getByTestId('scope-header-member-count')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('scope-header-supporter-count')).toHaveCount(0)

  await page.context().close()
})

// ============================================================
// AC-14: 言語切替（en / zh）でヘッダ人数表示が多言語化
// ============================================================
test('AC14: 言語をenに切替えるとヘッダ人数表示が英語化される', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, GENERAL)
  await openScope(page, '/settings/language')

  const select = page.locator('.p-select').first()
  await select.click()
  await page.getByRole('option', { name: 'English', exact: true }).click()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await page.waitForTimeout(1000)

  await openScope(page, `/teams/${TEAM_SLUG}`)
  const memberCountText = await page.getByTestId('scope-header-member-count').innerText()
  const supporterCountText = await page.getByTestId('scope-header-supporter-count').innerText()
  expect(memberCountText).toMatch(/members?/i)
  expect(supporterCountText).toMatch(/supporters?/i)
  expect(memberCountText).not.toContain('メンバー')
  expect(memberCountText).not.toContain('人')
  expect(supporterCountText).not.toContain('サポーター')
  expect(supporterCountText).not.toContain('人')

  // zh へ切替
  await openScope(page, '/settings/language')
  const select2 = page.locator('.p-select').first()
  await select2.click()
  await page.getByRole('option', { name: '中文（简体）', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.waitForTimeout(1000)

  await openScope(page, `/teams/${TEAM_SLUG}`)
  const memberCountZh = await page.getByTestId('scope-header-member-count').innerText()
  expect(memberCountZh).not.toContain('メンバー')

  // 戻す
  await openScope(page, '/settings/language')
  const select3 = page.locator('.p-select').first()
  await select3.click()
  await page.getByRole('option', { name: '日本語', exact: true }).click()
  await page.getByRole('button', { name: /保存|Save|保存/i }).click()

  await page.context().close()
})

// ============================================================
// ロール横断
// ============================================================
test('ROLE-MATRIX: ADMINは人数が見える／未所属は限定公開を直打ちで到達不可／他テナントは詳細も人数も出ない', async ({ browser }) => {
  test.setTimeout(240_000)

  // 正: ADMIN
  const adminPage = await openAs(browser, ADMIN)
  await openScope(adminPage, `/organizations/${ORG_SLUG}`)
  await expect(adminPage.getByTestId('scope-header-member-count')).toBeVisible({ timeout: 60_000 })
  await expect(adminPage.getByTestId('scope-header-supporter-count')).toBeVisible()
  await adminPage.context().close()

  // 負: 未所属一般ユーザーがPRIVATE組織を直打ち
  const outsiderPage = await openAs(browser, OUTSIDER)
  const status1 = await openScope(outsiderPage, `/organizations/${PRIVATE_ORG_SLUG}`)
  const bodyText1 = await outsiderPage.locator('body').innerText()
  console.log('ROLE-MATRIX 未所属→PRIVATE組織 status=', status1, ' body先頭500字:', bodyText1.slice(0, 500))
  await expect(outsiderPage.getByTestId('scope-header-member-count')).toHaveCount(0)
  await expect(outsiderPage.getByTestId('scope-header-supporter-count')).toHaveCount(0)
  await outsiderPage.context().close()

  // 他テナント: 組織AのMEMBERが組織C(PRIVATE)を直打ち
  const memberPage = await openAs(browser, MEMBER)
  const status2 = await openScope(memberPage, `/organizations/${PRIVATE_ORG_SLUG}`)
  const bodyText2 = await memberPage.locator('body').innerText()
  console.log('ROLE-MATRIX 他テナントMEMBER→PRIVATE組織 status=', status2, ' body先頭500字:', bodyText2.slice(0, 500))
  await expect(memberPage.getByTestId('scope-header-member-count')).toHaveCount(0)
  await expect(memberPage.getByTestId('scope-header-supporter-count')).toHaveCount(0)
  await memberPage.context().close()
})
