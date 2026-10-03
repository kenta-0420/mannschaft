/**
 * CMP-261001-0835 実機E2E（応援者フォロー解除のロール横断）。
 *
 * このテストはAPIモックを使わない実機テストです（page.route によるAPI横取り禁止）。
 * ログイン・前提データ作成・後始末のみ API を使用し、対象操作（フォロー解除・ボタン有無の確認）は
 * 必ず実画面から行う。
 *
 * 対象シナリオ（組織・チームの両方、デスクトップ幅1280・モバイル幅390の両方）:
 *  1. SUPPORTER: 「フォロー解除」が出る／「退出」は出ない → 解除→成功→「サポーターになる」へ切替→リロードでも未フォロー
 *  2. MEMBER: 退出が出る・フォロー系は出ない
 *  3. ADMIN: 退出もフォロー系も出ない
 *  4. 未所属: 「サポーターになる」が出る、退出は出ない
 *  5. PENDING（手動承認）: 「申請中」+「取消」→取消で消える
 *  6. 他組織: 組織Aの応援者で組織Bのページを開くと未所属表示（混線しない）。A→B遷移も確認
 *  7. BE 直叩き（認可境界）
 *  8. URL 直打ち
 *
 * 固定ユーザー（既存シード、共有開発DB）:
 *   MEMBER:    e2e-user@test.mannschaft.local      (id=23)  — ORG s-98024ad7 / TEAM fc-u-18
 *   SUPPORTER: e2e-supporter@test.mannschaft.local  (id=90156) — ORG s-98024ad7 / TEAM fc-u-18 の APPROVED サポーター
 *   OUTSIDER:  e2e-outsider@test.mannschaft.local   (id=90245) — ORG s-98024ad7 / TEAM fc-u-18 には未所属
 *   ADMIN:     e2e-admin@test.mannschaft.local      (id=24)  — ORG s-98024ad7 / TEAM fc-u-18 の ADMIN
 * 他組織（org-000001, id=1）には e2e-supporter / e2e-outsider いずれも無関係（クロスオーグ検証に使用）。
 */

import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3005'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8085'
const PASSWORD = 'TestPass2026!'

const TEAM_SLUG = 'fc-u-18'
const ORG_SLUG = 's-98024ad7'
const OTHER_ORG_SLUG = 'org-000001'

const MEMBER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD }

test.describe.configure({ mode: 'serial' })

async function openAs(
  browser: Browser,
  credentials: { email: string, password: string },
  viewport = { width: 1280, height: 900 },
): Promise<Page> {
  const context = await browser.newContext({
    storageState: { cookies: [], origins: [] },
    viewport,
  })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL, deferNavigation: true })

  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const body = (await me.json()).data as { email: string }
  expect(body.email, '別アカウントの認証状態が混入している').toBe(credentials.email)
  return page
}

async function openScope(page: Page, path: string): Promise<void> {
  const response = await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  expect(response?.status() ?? 200).toBeLessThan(400)
  await waitForHydration(page)
  await waitForSpinnerGone(page)
}

async function bearerFor(request: APIRequestContext, credentials: { email: string, password: string }): Promise<string> {
  const res = await request.post(`${API_BASE_URL}/api/v1/auth/login`, {
    data: { email: credentials.email, password: credentials.password },
  })
  expect(res.status()).toBe(200)
  const body = (await res.json()).data as { accessToken: string }
  return body.accessToken
}

// ============================================================
// 1. SUPPORTER — 組織（デスクトップ）
// ============================================================
test('SUP-ORG-DESK: 応援者はフォロー解除導線を持ち、解除後は未フォローへ切り替わり永続化される', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, SUPPORTER, { width: 1280, height: 900 })
  await openScope(page, `/organizations/${ORG_SLUG}`)

  // 「フォロー解除」導線は出る／退出は出ない
  const unfollowButton = page.getByTestId('follow-unfollow-button')
  await expect(unfollowButton).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('org-leave-button')).toHaveCount(0)
  await expect(page.getByText('組織から退出', { exact: true })).toHaveCount(0)

  await unfollowButton.click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  const unfollowCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/follow`,
  )
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  expect((await unfollowCompleted).status()).toBe(204)

  // ヘッダが「サポーターになる」へ切り替わる
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)

  // リロードしても未フォロー状態（DB永続化の確認）
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })

  await page.context().close()
})

// ============================================================
// 6. 他組織 — クロスオーグ分離（SUPPORTERのまま。上のテストで解除済みなので、
//     ここでは「組織Aで未所属状態のSUPPORTERアカウント」で組織Bの表示を確認する形になるが、
//     解除前の状態を汚さないよう、クロスオーグ確認は解除前に行う必要がある。
//     → 実行順を入れ替え、クロスオーグ確認を解除テストより前に独立した事実として先に記録する。
// ============================================================

// ============================================================
// 2. MEMBER — 組織（デスクトップ）／ 3. ADMIN／4. 未所属／モバイル
// ============================================================
test('MEMBER-ORG-DESK: 会員は退出導線のみ、フォロー系ボタンは出ない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, MEMBER)
  await openScope(page, `/organizations/${ORG_SLUG}`)

  await expect(page.getByTestId('org-leave-button')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await expect(page.getByTestId('follow-apply-button')).toHaveCount(0)
  await expect(page.getByTestId('follow-pending-cancel-button')).toHaveCount(0)
  await page.context().close()
})

test('ADMIN-ORG-DESK: 管理者は退出・フォロー系いずれも出ない（従来どおり）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, ADMIN)
  await openScope(page, `/organizations/${ORG_SLUG}`)

  await expect(page.getByTestId('org-leave-button')).toHaveCount(0)
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await expect(page.getByTestId('follow-apply-button')).toHaveCount(0)
  await page.context().close()
})

test('OUTSIDER-ORG-DESK: 未所属者には「サポーターになる」が出て退出は出ない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, OUTSIDER)
  await openScope(page, `/organizations/${ORG_SLUG}`)

  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('org-leave-button')).toHaveCount(0)
  await page.context().close()
})

// ============================================================
// モバイル幅（⋯メニュー）: SUPPORTER / MEMBER / 未所属
// ============================================================
test('SUP-ORG-MOBILE: モバイル幅でもフォロー解除はインライン、退出は⋯メニューにも出ない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, SUPPORTER, { width: 390, height: 844 })
  await openScope(page, `/organizations/${ORG_SLUG}`)

  // この時点で前テスト(SUP-ORG-DESK)により解除済み＝未所属なので、サポーター申請を再度行い
  // APPROVED 状態を作ってからモバイル表示を確認する（autoApprove 既定 true）。
  const applyRes = await page.request.post(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  expect(applyRes.status()).toBe(200)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 60_000 })
  // ⋯メニューを実際に開いて「組織から退出」が無いことを確認
  const overflow = page.locator('button:has(.pi-ellipsis-v)')
  if (await overflow.count() > 0) {
    await overflow.click()
    await expect(page.getByText('組織から退出', { exact: true })).toHaveCount(0)
    await page.keyboard.press('Escape')
  }

  // 後始末: モバイル確認用に作った APPROVED を解除し、未所属へ戻す
  const unfollowCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/follow`,
  )
  await page.request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  await unfollowCompleted.catch(() => {})
  await page.context().close()
})

test('MEMBER-ORG-MOBILE: モバイル幅の⋯メニューに退出が出て、フォロー系は出ない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, MEMBER, { width: 390, height: 844 })
  await openScope(page, `/organizations/${ORG_SLUG}`)
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await expect(page.getByTestId('follow-apply-button')).toHaveCount(0)

  const overflow = page.locator('button:has(.pi-ellipsis-v)')
  await expect(overflow).toBeVisible({ timeout: 60_000 })
  await overflow.click()
  await expect(page.getByText('組織から退出', { exact: true }).last()).toBeVisible()
  await page.keyboard.press('Escape')
  await page.context().close()
})

// ============================================================
// チーム版: SUPPORTER / MEMBER（デスクトップ代表1件ずつ。組織と同一実装パターンのため縮小確認）
// ============================================================
test('SUP-TEAM-DESK: チームでも応援者はフォロー解除導線のみ、退出は出ない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, SUPPORTER)

  // 組織側の解除テストで一時的に未所属化されている可能性があるため、チームは独立に確認する。
  const statusRes = await page.request.get(`${API_BASE_URL}/api/v1/teams/${TEAM_SLUG}/follow/status`)
  expect(statusRes.status()).toBe(200)
  const status = (await statusRes.json()).data as { status: string }
  if (status.status !== 'APPROVED') {
    const applyRes = await page.request.post(`${API_BASE_URL}/api/v1/teams/${TEAM_SLUG}/follow`)
    expect(applyRes.status()).toBe(200)
  }

  await openScope(page, `/teams/${TEAM_SLUG}`)
  const unfollowButton = page.getByTestId('follow-unfollow-button')
  await expect(unfollowButton).toBeVisible({ timeout: 60_000 })
  await expect(page.getByRole('button', { name: 'チームから退出', exact: true })).toHaveCount(0)

  await unfollowButton.click()
  const dialog = page.getByRole('dialog', { name: 'サポーターをやめますか？' })
  await expect(dialog).toBeVisible()
  const unfollowCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/teams/${TEAM_SLUG}/follow`,
  )
  await dialog.getByRole('button', { name: 'やめる', exact: true }).click()
  expect((await unfollowCompleted).status()).toBe(204)
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })
  await page.context().close()
})

test('MEMBER-TEAM-DESK: チームの会員は退出導線のみ', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, MEMBER)
  await openScope(page, `/teams/${TEAM_SLUG}`)
  await expect(page.getByRole('button', { name: 'チームから退出', exact: true })).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)
  await page.context().close()
})

// ============================================================
// 5. PENDING（手動承認）: 組織側で autoApprove=false を一時的に設定 → 新規ユーザーで申請 → 「申請中」+「取消」 → 取消で消える
//     アプリ自身の API（PUT .../supporter-settings）で短時間だけ設定変更し、直後に戻す。
// ============================================================
test('PENDING-ORG-DESK: 手動承認設定時は「申請中」と「取消」が出て、取消で消える', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const adminToken = await bearerFor(request, ADMIN)

  // 1) 一時的に手動承認へ変更
  const before = await request.get(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/supporter-settings`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  expect(before.status()).toBe(200)
  await request.put(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/supporter-settings`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { isAutoApprove: false },
  })

  try {
    // 2) OUTSIDER が申請 → PENDING になる
    const outsiderPage = await openAs(browser, OUTSIDER)
    const applyRes = await outsiderPage.request.post(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
    expect(applyRes.status()).toBe(200)
    const applyBody = (await applyRes.json()).data as { status: string }
    expect(applyBody.status).toBe('PENDING')

    await openScope(outsiderPage, `/organizations/${ORG_SLUG}`)
    await expect(outsiderPage.getByText('申請中', { exact: false })).toBeVisible({ timeout: 60_000 })
    const cancelButton = outsiderPage.getByTestId('follow-pending-cancel-button')
    await expect(cancelButton).toBeVisible()

    const cancelCompleted = outsiderPage.waitForResponse(
      response => response.request().method() === 'DELETE'
        && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/follow`,
    )
    await cancelButton.click()
    expect((await cancelCompleted).status()).toBe(204)
    await expect(outsiderPage.getByTestId('follow-apply-button')).toBeVisible({ timeout: 30_000 })
    await outsiderPage.context().close()
  } finally {
    // 3) 設定を必ず元に戻す（共有DB・他セッションへの影響を最小化）
    const beforeBody = (await before.json()).data as { isAutoApprove: boolean }
    await request.put(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/supporter-settings`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { isAutoApprove: beforeBody.isAutoApprove },
    })
  }
})

// ============================================================
// 6. 他組織 — クロスオーグ分離: 組織Aで応援者申請→APPROVEDにし、組織B(org-000001)では未所属表示を確認。
//     続けて A→B へ画面遷移しても表示が正しく切り替わることを見る。最後に組織Aを解除して後始末。
// ============================================================
test('CROSS-ORG: 組織Aのフォロー状態が組織Bに混線しない', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, SUPPORTER)

  const statusA = await page.request.get(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow/status`)
  const statusABody = (await statusA.json()).data as { status: string }
  if (statusABody.status !== 'APPROVED') {
    const applyRes = await page.request.post(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
    expect(applyRes.status()).toBe(200)
  }

  await openScope(page, `/organizations/${ORG_SLUG}`)
  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 60_000 })

  // 組織Bへ遷移 → 未所属表示（Aの状態が混ざらない）
  await openScope(page, `/organizations/${OTHER_ORG_SLUG}`)
  await expect(page.getByTestId('follow-apply-button')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('follow-unfollow-button')).toHaveCount(0)

  // 再度Aへ戻って表示が正しく切り替わることを確認
  await openScope(page, `/organizations/${ORG_SLUG}`)
  await expect(page.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 60_000 })

  // 後始末: 組織Aのフォローを解除
  const unfollowCompleted = page.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/follow`,
  )
  await page.request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  await unfollowCompleted.catch(() => {})
  await page.context().close()
})

// ============================================================
// 7. BE 直叩き（認可境界）
// ============================================================
test('API-AUTHZ: 応援者/会員/未認証の認可境界がAPIレベルで正しい', async ({ request }) => {
  test.setTimeout(120_000)

  // 応援者の /me（退会API）は 422 ROLE_015
  const supporterToken = await bearerFor(request, SUPPORTER)
  const supporterFollow = await request.get(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow/status`, {
    headers: { Authorization: `Bearer ${supporterToken}` },
  })
  const supporterStatus = (await supporterFollow.json()).data as { status: string }
  if (supporterStatus.status !== 'APPROVED') {
    await request.post(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`, {
      headers: { Authorization: `Bearer ${supporterToken}` },
    })
  }
  const leaveRes = await request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/me`, {
    headers: { Authorization: `Bearer ${supporterToken}` },
  })
  expect(leaveRes.status()).toBe(422)
  const leaveBody = await leaveRes.json()
  expect(leaveBody.error?.code ?? leaveBody.code).toBe('ROLE_015')

  // MEMBER の DELETE /follow は 404 で MEMBER 所属は残る
  const memberToken = await bearerFor(request, MEMBER)
  const memberUnfollow = await request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  expect(memberUnfollow.status()).toBe(404)
  const memberMe = await request.get(`${API_BASE_URL}/api/v1/users/me/organizations`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  }).catch(() => null)
  // users/me/organizations が存在しない場合でも /organizations/{slug}/me/permissions で裏取り
  const memberPerm = await request.get(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/me/permissions`, {
    headers: { Authorization: `Bearer ${memberToken}` },
  })
  expect(memberPerm.status()).toBe(200)
  const memberPermBody = (await memberPerm.json()).data as { roleName: string }
  expect(memberPermBody.roleName).toBe('MEMBER')
  void memberMe

  // 未認証 401
  const anonRes = await request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  expect(anonRes.status()).toBe(401)
})

// ============================================================
// 8. URL 直打ち
// ============================================================
test('URL-DIRECT: 応援者・未所属のURL直打ちでも表示が正しい', async ({ browser }) => {
  test.setTimeout(240_000)

  const supporterPage = await openAs(browser, SUPPORTER)
  const statusRes = await supporterPage.request.get(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow/status`)
  const statusBody = (await statusRes.json()).data as { status: string }
  if (statusBody.status !== 'APPROVED') {
    await supporterPage.request.post(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  }
  await supporterPage.goto(`${BASE_URL}/organizations/${ORG_SLUG}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(supporterPage)
  await waitForSpinnerGone(supporterPage)
  await expect(supporterPage.getByTestId('follow-unfollow-button')).toBeVisible({ timeout: 60_000 })
  // 後始末
  const unfollowCompleted = supporterPage.waitForResponse(
    response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === `/api/v1/organizations/${ORG_SLUG}/follow`,
  )
  await supporterPage.request.delete(`${API_BASE_URL}/api/v1/organizations/${ORG_SLUG}/follow`)
  await unfollowCompleted.catch(() => {})
  await supporterPage.context().close()

  const outsiderPage = await openAs(browser, OUTSIDER)
  await outsiderPage.goto(`${BASE_URL}/organizations/${ORG_SLUG}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(outsiderPage)
  await waitForSpinnerGone(outsiderPage)
  await expect(outsiderPage.getByTestId('follow-apply-button')).toBeVisible({ timeout: 60_000 })
  await outsiderPage.context().close()
})
