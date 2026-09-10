/**
 * 柱③-A「参加申請（join request）」— 実機 E2E テスト（CMP-260901-1538）
 *
 * 対象:
 *   BE: `com.mannschaft.app.joinrequest`（PR #3139）
 *   FE: 組織詳細の参加申請ボタン・自分の申請状況表示、
 *       `/organizations/[slug]/join-requests`（ADMIN/DEPUTY_ADMIN 向け一覧・PR #3170）
 *
 * 方針（`.claude/commands/実機.md` §3・§3.5）:
 *   - 対象操作（申請・承認・却下・再申請）は**すべてブラウザ操作**で踏む。
 *     API 利用はログイン補助・前提データ作成（PUBLIC 組織の払い出し）・後始末に限る。
 *   - ロール横断の3視点（正 / 負＝権限なし / 他テナント＝非所属）を実ログインセッションで踏む。
 *   - 権限のない者が審査画面へ入ろうとする経路は、**SPA 内遷移（$router.push）と
 *     フルロードの URL 直打ち（page.goto）の両方**を踏む。両者は挙動が異なる（下記）。
 *
 * ⚠ 既知の欠陥（CMP-260910-1056・PR #3208 で別戦役として対応中）:
 *   `pages/organizations/[slug].vue` の管理ルート滞在防止 watch に `immediate: true` が無く、
 *   **初回描画では発火しない**。そのため:
 *     - SPA 内遷移（$router.push）→ watch が発火し `/organizations/{slug}` へ差し戻される
 *     - フルロードの直打ち（page.goto）→ **URL は join-requests のまま維持**され、
 *       組織ダッシュボードが描画される（＝滞在防止が効いていない）
 *   本 spec は直打ちケースについて **URL が差し戻されることを assert しない**。
 *   代わりに「**情報が漏れないこと**」（申請行0件・審査パネル非描画・一覧 API 未発火・
 *   BE 直叩き 403）だけを固定する。URL 挙動の是正は CMP-260910-1056 の対象。
 *
 * テストユーザー:
 *   審査者(ADMIN) : e2e-user@test.mannschaft.local   （検証用 PUBLIC 組織の作成者＝ADMIN）
 *   申請者        : e2e-dummy-1@test.mannschaft.local（非メンバー → 承認後 MEMBER）
 *   部外者        : e2e-dummy-2@test.mannschaft.local（最後まで非メンバー）
 *
 *   ⚠ 審査者に `e2e-admin@test.mannschaft.local` を使ってはならない。
 *     同アカウントはプラットフォーム SYSTEM_ADMIN であり、
 *     `AccessControlService#isAdminOrAbove` が SYSTEM_ADMIN を ADMIN_ROLES に含めないため、
 *     組織 ADMIN であっても ADMIN 専用 API が一律 403 になる（本 spec のヘッダコメント末尾参照）。
 *
 * ⚠ 既知の制約（Codex 検分 P2・許容して残している）:
 *   JR-01〜JR-07 は「未申請 → 申請 → 却下 → 再申請 → 承認 → メンバー」という一連の
 *   状態遷移そのものを検証対象にしているため、`serial` で DB 状態を受け渡している。
 *   各テストを単独実行することはできず、途中で失敗すると後続は skip される。
 *   これは状態遷移を画面で追うという目的上の制約であり、独立させるには各テストが
 *   前提状態を毎回 API で作り直す必要がある（＝対象操作の API 代替が増える）ため採らない。
 *   JR-08 のみ前段の状態に依存しない。
 *
 * 実行方法（BE 8081 / FE 3001 が起動済みの状態で）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/join-request-roles.spec.ts \
 *     --config playwright-real.config.ts --project chromium-real
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// storageState に依存せず各テスト内でロールを切り替える
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

// ── 定数 ──────────────────────────────────────────────────────────────────
const BE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const REVIEWER_EMAIL = 'e2e-user@test.mannschaft.local'
const REVIEWER_PASSWORD = 'TestPass2026!'
const APPLICANT_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
const APPLICANT_PASSWORD = 'TestPass2026!'
const OUTSIDER_EMAIL = 'e2e-dummy-2@test.mannschaft.local'
const OUTSIDER_PASSWORD = 'TestPass2026!'

// ── ヘルパー ──────────────────────────────────────────────────────────────

interface LoginResult {
  accessToken: string
  userId: number
}

/** BE の /api/v1/auth/login で Bearer トークンを取得する（ログイン補助のみ）。 */
async function apiLogin(
  api: APIRequestContext,
  email: string,
  password: string,
): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string; userId: number } }
  // 「実際にどのユーザーでログインできたか」をログに残す（.env.test 差し替え事故の検知）
  console.log(`[join-request-e2e] apiLogin ok: ${email} → userId=${json.data.userId}`)
  return { accessToken: json.data.accessToken, userId: json.data.userId }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** /login フォームからブラウザセッションを確立する（PrimeVue InputText 対応）。 */
async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  // 「Googleでログイン」ボタンとも一致するため exact 指定で送信ボタンだけを掴む
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

/**
 * 「権限・組織データのロードが完了した」ことを示す肯定的な終端表示（Codex 検分 P1-3 是正）。
 *
 * 非漏洩の assert は「存在しない」という否定条件ばかりなので、画面が描画途中でも
 * 直ちに成功してしまう。固定待ちに頼らず、ロール別に必ず現れる肯定的な要素を
 * ここで待ってから非漏洩を検証する。
 *
 * - 非メンバー（未申請）    : 「参加申請」ボタン（roleName 解決済み＝非メンバー確定）
 * - 非メンバー（申請中/却下）: 申請状態の表示
 * - メンバー / 管理者        : ヘッダの RoleBadge（roleName 解決済み＝所属とロールが確定）
 * - 取得失敗の異常系        : 取得失敗表示（fail-close が効いたことの確定）
 */
function readySignal(page: Page, kind: ReadyKind) {
  switch (kind) {
    case 'apply':
      return page.getByTestId('join-request-apply-button')
    case 'pending':
      return page.getByTestId('join-request-pending')
    case 'rejected':
      return page.getByTestId('join-request-rejected')
    case 'error':
      return page.getByTestId('join-request-error')
    case 'member':
      // RoleBadge は PrimeVue Tag。タブの「メンバー」と衝突しないよう完全一致で絞る。
      return page.locator('.p-tag').filter({ hasText: /^メンバー$/ }).first()
    case 'admin':
      return page.locator('.p-tag').filter({ hasText: /^管理者$/ }).first()
  }
}

type ReadyKind = 'apply' | 'pending' | 'rejected' | 'error' | 'member' | 'admin'

/** 肯定的な終端表示が出るまで待つ。出なければ失敗させる（握りつぶさない）。 */
async function waitUntilScopeLoaded(page: Page, kind: ReadyKind): Promise<void> {
  await expect(
    readySignal(page, kind),
    `権限・組織データのロード完了を示す表示（${kind}）が現れること`,
  ).toBeVisible({ timeout: 30_000 })
}

/**
 * ログイン後にクライアントサイドナビゲーションで目的ページへ入る。
 * 既存の実機 spec（f089-billing-crud-roles.spec.ts）と同じ Nuxt ルーター経由。
 * 到達後は固定待ちではなく肯定的な終端表示を待つ（P1-3 是正）。
 */
async function loginAndNavigate(
  page: Page,
  email: string,
  password: string,
  targetPath: string,
  ready: ReadyKind,
): Promise<void> {
  await loginUI(page, email, password)
  await page.evaluate((path) => {
    type VueApp = { config: { globalProperties?: { $router?: { push: (p: string) => void } } } }
    const el = document.querySelector('#__nuxt') as (Element & { __vue_app__?: VueApp }) | null
    const router = el?.__vue_app__?.config?.globalProperties?.$router
    if (router) return router.push(path)
    window.location.href = path
  }, targetPath)
  await waitForHydration(page)
  await waitUntilScopeLoaded(page, ready)
}

/**
 * SPA 内遷移（画面上の導線を一切使わず Nuxt ルーターへ直接 push する）。
 * フルロードの URL 直打ちとは別経路であり、両者は挙動が異なる（冒頭コメント参照）。
 *
 * 遷移完了は固定時間ではなく URL の確定で待つ（P2 是正）。直リンク防御で
 * `fallbackPath` へ差し戻される場合があるため、どちらかに落ち着くまで待つ。
 */
async function spaNavigate(
  page: Page,
  path: string,
  fallbackPath: string,
  ready: ReadyKind,
): Promise<void> {
  await page.evaluate((p) => {
    type VueApp = { config: { globalProperties?: { $router?: { push: (to: string) => void } } } }
    const el = document.querySelector('#__nuxt') as (Element & { __vue_app__?: VueApp }) | null
    el?.__vue_app__?.config?.globalProperties?.$router?.push(p)
  }, path)
  await page.waitForURL(
    (url) => url.pathname === path || url.pathname === fallbackPath,
    { timeout: 20_000 },
  )
  await waitUntilScopeLoaded(page, ready)
}

/**
 * フルロードの URL 直打ち（ブラウザのアドレスバーに打ち込むのと同じ経路）。
 * ログインセッションは Cookie で保持されるためリロードしても維持される。
 * hydration 後に肯定的な終端表示を待つ（P1-3 是正・固定待ちに頼らない）。
 */
async function fullReloadGoto(page: Page, path: string, ready: ReadyKind): Promise<void> {
  await page.goto(path, { waitUntil: 'commit' })
  await waitForHydration(page)
  await waitUntilScopeLoaded(page, ready)
}

/**
 * ADMIN 向け審査一覧 API（`GET /organizations/{id}/join-requests`）の発火を観測する。
 * 申請者本人向けの `.../join-requests/me` は対象外（誰でも呼んでよい）。
 */
function trackReviewerListCalls(page: Page, targetOrgId: number): string[] {
  const calls: string[] = []
  page.on('request', (req) => {
    const url = req.url()
    if (
      req.method() === 'GET'
      && url.includes(`/organizations/${targetOrgId}/join-requests`)
      && !url.includes('/join-requests/me')
    ) {
      calls.push(url)
    }
  })
  return calls
}

/**
 * 権限のない者に審査画面の情報が一切漏れていないことを固定する共通アサート。
 *
 * 呼び出し側は必ず先に {@link waitUntilScopeLoaded} で権限・組織データのロード完了を
 * 待つこと（否定条件だけでは描画途中でも成功してしまうため）。そのうえで、ロード完了後に
 * 遅れて発火する審査一覧 API を取りこぼさないよう短い整定時間を置いてから判定する。
 */
async function expectNoReviewerDataLeak(page: Page, listCalls: string[]): Promise<void> {
  // ロード完了後に後追いで飛ぶリクエストを拾うための整定（待ちの主体は上の肯定的表示）
  await page.waitForTimeout(1_500)
  await expect(
    page.getByTestId('join-request-row'),
    '他人の申請行が一切見えない',
  ).toHaveCount(0)
  await expect(
    page.getByText('承認待ちの参加申請'),
    '審査パネル自体が描画されない',
  ).toHaveCount(0)
  expect(listCalls, `審査一覧 API が呼ばれない（実際の呼び出し: ${listCalls.join(', ')}）`)
    .toHaveLength(0)
}

// ── 前提データ（PUBLIC 組織を1つ払い出す。API 利用は前提作成のみ） ────────────
let sharedApi: APIRequestContext
let reviewerToken: string
let applicantUserId: number
let orgSlug: string
let orgId: number

test.beforeAll(async () => {
  sharedApi = await pwRequest.newContext()
  const reviewer = await apiLogin(sharedApi, REVIEWER_EMAIL, REVIEWER_PASSWORD)
  reviewerToken = reviewer.accessToken
  const applicant = await apiLogin(sharedApi, APPLICANT_EMAIL, APPLICANT_PASSWORD)
  applicantUserId = applicant.userId

  const stamp = Date.now().toString().slice(-9)
  orgSlug = `jr-e2e-${stamp}`
  const res = await sharedApi.post(`${BE_API}/organizations`, {
    headers: authHeaders(reviewerToken),
    data: {
      name: `JR-E2E-${stamp}`,
      orgType: 'COMMUNITY',
      visibility: 'PUBLIC',
      slug: orgSlug,
      confirmDuplicate: true,
    },
  })
  expect(res.status(), '検証用 PUBLIC 組織の作成は 201').toBe(201)
  const json = (await res.json()) as { data: { numericId: number; slug: string } }
  orgId = json.data.numericId
  console.log(`[join-request-e2e] 検証用組織: slug=${orgSlug} id=${orgId}（ADMIN=${reviewer.userId}）`)
})

/**
 * 後始末（Codex 検分 P1-1 是正）。
 *
 * 何がどこまで消えるかを正直に記す:
 *   1. 承認で付与されたメンバーシップ → `DELETE /organizations/{slug}/members/{userId}` で除去する
 *   2. 検証用組織そのもの             → `DELETE /organizations/{slug}` で削除する。ただしこれは
 *      `OrganizationService` の**論理削除**（`deleted_at` 付与）であり、行自体は DB に残る
 *   3. `join_requests` の申請履歴     → **消えない**。組織への FK/CASCADE を持たず、申請を削除する
 *      API も存在しないため、当該組織 ID に紐づく PENDING/REJECTED/APPROVED の行が残留する
 *
 * つまり「DB を実行前と完全に同一へ戻す」ことはできていない。緩和策として検証用組織を
 * 毎回新規に払い出し（`jr-e2e-<タイムスタンプ>`）、残留物を既存データから隔離している。
 * 残留行を物理削除するには join_requests の削除 API か、テスト用の掃除機構が別途必要
 * （本 spec の範囲外。必要なら別戦役で起票すること）。
 *
 * ステータスは記録するだけでなく **assert する**（403/500 を見逃すと後始末が黙って
 * 失敗し、データが際限なく蓄積するため）。
 */
test.afterAll(async () => {
  try {
    // 1. 承認で付与されたメンバーシップを外す（未承認で終わった場合は 404 もあり得る）
    const memberRes = await sharedApi.delete(
      `${BE_API}/organizations/${orgSlug}/members/${applicantUserId}`,
      { headers: authHeaders(reviewerToken) },
    )
    console.log(
      `[join-request-e2e] 後始末 DELETE members/${applicantUserId} → ${memberRes.status()}`,
    )
    expect(
      [204, 404],
      `申請者のメンバーシップ除去は 204（未所属なら 404）。実際: ${memberRes.status()}`,
    ).toContain(memberRes.status())

    // 2. 検証用組織を削除する（論理削除）
    const orgRes = await sharedApi.delete(`${BE_API}/organizations/${orgSlug}`, {
      headers: authHeaders(reviewerToken),
    })
    console.log(`[join-request-e2e] 後始末 DELETE /organizations/${orgSlug} → ${orgRes.status()}`)
    expect(
      orgRes.status(),
      `検証用組織の削除は 204 でなければならない（実際: ${orgRes.status()}）`,
    ).toBe(204)

    // 3. 削除後は誰から見ても引けないこと（論理削除が効いていることの裏取り）
    const afterRes = await sharedApi.get(`${BE_API}/organizations/${orgSlug}`, {
      headers: authHeaders(reviewerToken),
    })
    expect(
      [403, 404],
      `削除後の組織取得は 403/404（実際: ${afterRes.status()}）`,
    ).toContain(afterRes.status())

    console.log(
      `[join-request-e2e] 残留: join_requests（organization_id=${orgId}）の申請履歴は`
      + ' 削除 API が無いため DB に残る（上記コメント参照）',
    )
  }
  finally {
    await sharedApi.dispose()
  }
})

// ===========================================================================
// 正の視点（申請者）: 未申請 → 申請 → 「申請中」表示
// ===========================================================================
test('JR-01: [applicant] 組織詳細の「参加申請」ボタンをクリックすると「申請中（承認待ち）」になる', async ({
  page,
}) => {
  test.setTimeout(90_000)

  await loginAndNavigate(page, APPLICANT_EMAIL, APPLICANT_PASSWORD, `/organizations/${orgSlug}`, 'apply')

  const applyButton = page.getByTestId('join-request-apply-button')
  await expect(applyButton, '非メンバーには「参加申請」ボタンが表示される').toBeVisible({
    timeout: 20_000,
  })
  await expect(applyButton, '取得成功時の申請ボタンは活性').toBeEnabled({ timeout: 15_000 })
  await expect(applyButton).toHaveText(/参加申請/)

  const [apiResp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/organizations/${orgId}/join-requests`)
        && r.request().method() === 'POST',
      { timeout: 20_000 },
    ),
    applyButton.click(),
  ])
  expect(apiResp.status(), '参加申請 API は 201 Created').toBe(201)

  await expect(
    page.getByTestId('join-request-pending'),
    '申請後は「申請中（承認待ち）」が表示される',
  ).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('join-request-pending')).toHaveText(/申請中（承認待ち）/)
  await expect(
    page.getByTestId('join-request-apply-button'),
    '申請中は「参加申請」ボタンが消える',
  ).toHaveCount(0)

  await page.screenshot({ path: 'test-results/jr-01-applicant-pending.png', fullPage: true })
})

// ===========================================================================
// 負の視点（権限なし）: 申請者は審査画面の導線が出ず、URL 直打ちでも入れない
// ===========================================================================
test('JR-02: [applicant/権限なし] 管理タブが出ず、SPA 内遷移でもフルロード直打ちでも審査情報が漏れない', async ({
  page,
}) => {
  // ログイン2回相当 + SPA遷移 + フルロード直打ちの2経路を1テストで踏むため長めに取る
  test.setTimeout(180_000)

  const listCalls = trackReviewerListCalls(page, orgId)
  await loginAndNavigate(page, APPLICANT_EMAIL, APPLICANT_PASSWORD, `/organizations/${orgSlug}`, 'pending')
  // 申請中であること（＝この組織を確かに見ている）を先に確認
  await expect(page.getByTestId('join-request-pending')).toBeVisible({ timeout: 20_000 })

  // ① 導線（管理タブ）自体が出ないこと
  await expect(
    page.getByRole('link', { name: '参加申請' }),
    '非管理者には「参加申請」管理タブの導線が出ない',
  ).toHaveCount(0)

  // ② SPA 内遷移（画面の導線を一切使わない直接 push）→ 差し戻される
  await spaNavigate(page, `/organizations/${orgSlug}/join-requests`, `/organizations/${orgSlug}`, 'pending')
  await expectNoReviewerDataLeak(page, listCalls)
  expect(
    new URL(page.url()).pathname,
    'SPA 内遷移は組織ダッシュボードへ差し戻される',
  ).toBe(`/organizations/${orgSlug}`)

  // ③ フルロードの URL 直打ち → 情報が漏れないことのみ固定する。
  //    URL が差し戻されないのは CMP-260910-1056（管理ルート滞在防止 watch に
  //    immediate: true が無く初回描画で発火しない）の既知欠陥であり、
  //    ここで URL を assert すると本 spec が別戦役の修正待ちで赤くなるため assert しない。
  listCalls.length = 0
  await fullReloadGoto(page, `/organizations/${orgSlug}/join-requests`, 'pending')
  await expectNoReviewerDataLeak(page, listCalls)

  // ④ BE 二重防衛: 申請者本人の一覧 API は 403
  const { accessToken } = await apiLogin(sharedApi, APPLICANT_EMAIL, APPLICANT_PASSWORD)
  const res = await sharedApi.get(`${BE_API}/organizations/${orgId}/join-requests`, {
    headers: authHeaders(accessToken),
  })
  expect([403, 404], '申請者の審査一覧 API は 403/404').toContain(res.status())

  await page.screenshot({ path: 'test-results/jr-02-applicant-denied.png', fullPage: true })
})

// ===========================================================================
// 他テナント（非所属の第三者）: 一覧にも詳細にも出ない
// ===========================================================================
test('JR-03: [outsider/非所属] 管理タブが出ず、SPA 内遷移でもフルロード直打ちでも審査情報が漏れない', async ({
  page,
}) => {
  // ログイン2回相当 + SPA遷移 + フルロード直打ちの2経路を1テストで踏むため長めに取る
  test.setTimeout(180_000)

  const listCalls = trackReviewerListCalls(page, orgId)
  await loginAndNavigate(page, OUTSIDER_EMAIL, OUTSIDER_PASSWORD, `/organizations/${orgSlug}`, 'apply')
  // PUBLIC 組織なので詳細自体は見える（＝「見えない」のは審査画面だけ）
  await expect(page.getByTestId('join-request-apply-button')).toBeVisible({ timeout: 20_000 })
  await expect(
    page.getByRole('link', { name: '参加申請' }),
    '非所属ユーザーに「参加申請」管理タブは出ない',
  ).toHaveCount(0)

  // ① SPA 内遷移 → 差し戻される
  await spaNavigate(page, `/organizations/${orgSlug}/join-requests`, `/organizations/${orgSlug}`, 'apply')
  await expectNoReviewerDataLeak(page, listCalls)
  expect(new URL(page.url()).pathname).toBe(`/organizations/${orgSlug}`)

  // ② フルロードの URL 直打ち → 情報非漏洩のみ固定（URL 挙動は CMP-260910-1056 の対象）
  listCalls.length = 0
  await fullReloadGoto(page, `/organizations/${orgSlug}/join-requests`, 'apply')
  await expectNoReviewerDataLeak(page, listCalls)

  const { accessToken } = await apiLogin(sharedApi, OUTSIDER_EMAIL, OUTSIDER_PASSWORD)
  const res = await sharedApi.get(`${BE_API}/organizations/${orgId}/join-requests`, {
    headers: authHeaders(accessToken),
  })
  expect([403, 404], '非所属ユーザーの審査一覧 API は 403/404').toContain(res.status())

  await page.screenshot({ path: 'test-results/jr-03-outsider-denied.png', fullPage: true })
})

// ===========================================================================
// 正の視点（ADMIN）: 一覧が見え、却下できる
// ===========================================================================
test('JR-04: [admin] 「参加申請」タブから申請が見え、画面の「却下」ボタンで却下できる', async ({
  page,
}) => {
  test.setTimeout(90_000)

  await loginAndNavigate(page, REVIEWER_EMAIL, REVIEWER_PASSWORD, `/organizations/${orgSlug}`, 'admin')

  // 導線（管理タブ）から入る
  const tab = page.getByRole('link', { name: '参加申請' }).first()
  await expect(tab, 'ADMIN には「参加申請」管理タブが表示される').toBeVisible({ timeout: 20_000 })
  await tab.click()
  await page.waitForURL(`**/organizations/${orgSlug}/join-requests`, { timeout: 15_000 })

  await expect(page.getByText('承認待ちの参加申請')).toBeVisible({ timeout: 15_000 })
  const row = page.getByTestId('join-request-row').first()
  await expect(row, 'ADMIN には申請行が見える').toBeVisible({ timeout: 15_000 })
  await expect(row, '申請者のユーザーIDが表示される').toContainText(`ユーザーID: ${applicantUserId}`)

  const [apiResp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/join-requests/') && r.url().endsWith('/reject'),
      { timeout: 20_000 },
    ),
    row.getByRole('button', { name: '却下' }).click(),
  ])
  expect(apiResp.status(), '却下 API は 200').toBe(200)

  await expect(
    page.getByTestId('join-request-row'),
    '却下後は承認待ちの行が無くなる',
  ).toHaveCount(0, { timeout: 15_000 })
  await expect(page.getByText('承認待ちの参加申請はありません')).toBeVisible({ timeout: 15_000 })

  await page.screenshot({ path: 'test-results/jr-04-admin-reject.png', fullPage: true })
})

// ===========================================================================
// 状態遷移: 却下後に再申請できる（検分で退行が見つかった箇所）
// ===========================================================================
test('JR-05: [applicant] 却下表示が出たうえで、画面から再申請できる', async ({ page }) => {
  test.setTimeout(90_000)

  await loginAndNavigate(page, APPLICANT_EMAIL, APPLICANT_PASSWORD, `/organizations/${orgSlug}`, 'rejected')

  await expect(
    page.getByTestId('join-request-rejected'),
    '却下後は「却下されました」が表示される',
  ).toBeVisible({ timeout: 20_000 })
  await expect(page.getByTestId('join-request-rejected')).toHaveText(/却下されました/)

  const applyButton = page.getByTestId('join-request-apply-button')
  await expect(applyButton, '却下後も「参加申請」ボタンは表示される').toBeVisible({ timeout: 15_000 })
  await expect(applyButton, '却下後の再申請ボタンは活性（PENDING のみ重複扱い）').toBeEnabled()

  const [apiResp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/organizations/${orgId}/join-requests`)
        && r.request().method() === 'POST',
      { timeout: 20_000 },
    ),
    applyButton.click(),
  ])
  expect(apiResp.status(), '再申請 API は 201 Created').toBe(201)

  await expect(
    page.getByTestId('join-request-pending'),
    '再申請後は「申請中（承認待ち）」に戻る',
  ).toBeVisible({ timeout: 15_000 })

  await page.screenshot({ path: 'test-results/jr-05-applicant-reapply.png', fullPage: true })
})

// ===========================================================================
// 正の視点（ADMIN）: 承認 → 申請者がメンバーになる
// ===========================================================================
test('JR-06: [admin] 画面の「承認」ボタンで承認でき、申請者がメンバーになる', async ({ page }) => {
  test.setTimeout(90_000)

  await loginAndNavigate(
    page,
    REVIEWER_EMAIL,
    REVIEWER_PASSWORD,
    `/organizations/${orgSlug}/join-requests`,
    'admin',
  )

  const row = page.getByTestId('join-request-row').first()
  await expect(row, '再申請された行が承認待ちに現れる').toBeVisible({ timeout: 20_000 })

  const [apiResp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/join-requests/') && r.url().endsWith('/approve'),
      { timeout: 20_000 },
    ),
    row.getByRole('button', { name: '承認' }).click(),
  ])
  expect(apiResp.status(), '承認 API は 200').toBe(200)

  await expect(page.getByTestId('join-request-row')).toHaveCount(0, { timeout: 15_000 })
  await expect(page.getByText('承認待ちの参加申請はありません')).toBeVisible({ timeout: 15_000 })

  // 永続化確認（BE 二重防衛）: メンバー一覧 API にも MEMBER として載ること。
  // MEMBER 付与そのものの「画面での確認」は JR-07 が申請者本人で再ログインして
  // ヘッダの RoleBadge を検証する（Codex 検分 P1-2 是正。API はここでは補助）。
  const members = await sharedApi.get(`${BE_API}/organizations/${orgSlug}/members`, {
    headers: authHeaders(reviewerToken),
  })
  expect(members.status()).toBe(200)
  const mJson = (await members.json()) as {
    data: Array<{ userId: number; roleName: string }>
  }
  const joined = mJson.data.find((m) => m.userId === applicantUserId)
  expect(joined, '承認された申請者がメンバー一覧に載る').toBeTruthy()
  expect(joined!.roleName, '付与ロールは MEMBER 固定').toBe('MEMBER')

  await page.screenshot({ path: 'test-results/jr-06-admin-approve.png', fullPage: true })
})

// ===========================================================================
// 負の視点（MEMBER）: 承認後も審査画面には入れない
// ===========================================================================
test('JR-07: [member/権限なし] メンバー昇格後も管理タブが出ず、両経路とも審査情報が漏れない', async ({
  page,
}) => {
  // ログイン2回相当 + SPA遷移 + フルロード直打ちの2経路を1テストで踏むため長めに取る
  test.setTimeout(180_000)

  const listCalls = trackReviewerListCalls(page, orgId)
  await loginAndNavigate(page, APPLICANT_EMAIL, APPLICANT_PASSWORD, `/organizations/${orgSlug}`, 'member')

  // ⓪ MEMBER 付与の画面検証（Codex 検分 P1-2 是正）。
  //    申請者本人で再ログインし、ヘッダの RoleBadge に「メンバー」が出ることを確認する。
  //    「申請ボタンが無い」だけでは申請状態が APPROVED でも成立するため、付与の確認にならない。
  const roleBadge = page.locator('.p-tag').filter({ hasText: /^メンバー$/ }).first()
  await expect(
    roleBadge,
    '承認後、申請者の画面にロールバッジ「メンバー」が表示される',
  ).toBeVisible({ timeout: 20_000 })
  await expect(roleBadge).toHaveText('メンバー')

  await expect(
    page.getByTestId('join-request-apply-button'),
    'メンバーには「参加申請」ボタンが出ない',
  ).toHaveCount(0, { timeout: 20_000 })
  await expect(
    page.getByRole('link', { name: '参加申請' }),
    'MEMBER には「参加申請」管理タブが出ない',
  ).toHaveCount(0)

  // ① SPA 内遷移 → 差し戻される
  await spaNavigate(page, `/organizations/${orgSlug}/join-requests`, `/organizations/${orgSlug}`, 'member')
  await expectNoReviewerDataLeak(page, listCalls)
  expect(new URL(page.url()).pathname).toBe(`/organizations/${orgSlug}`)

  // ② フルロードの URL 直打ち → 情報非漏洩のみ固定（URL 挙動は CMP-260910-1056 の対象）
  listCalls.length = 0
  await fullReloadGoto(page, `/organizations/${orgSlug}/join-requests`, 'member')
  await expectNoReviewerDataLeak(page, listCalls)

  const { accessToken } = await apiLogin(sharedApi, APPLICANT_EMAIL, APPLICANT_PASSWORD)
  const res = await sharedApi.get(`${BE_API}/organizations/${orgId}/join-requests`, {
    headers: authHeaders(accessToken),
  })
  expect([403, 404], 'MEMBER の審査一覧 API は 403/404').toContain(res.status())

  await page.screenshot({ path: 'test-results/jr-07-member-denied.png', fullPage: true })
})

// ===========================================================================
// 異常系: 申請状況の取得に失敗したら安全側（申請ボタン無効）に倒れる
// ===========================================================================
test('JR-08: [outsider/異常系] 申請状況の取得失敗時は申請ボタンが出ず、エラーと再試行が出る', async ({
  page,
}) => {
  test.setTimeout(90_000)

  // ⚠ 本テストは page.route() で実 BE 通信を遮断する障害注入であり、
  //    「モックなし実機E2E」には数えない**補助テスト**である（Codex 検分 P2 是正）。
  //    実機E2E の本体は JR-01〜JR-07（実 BE・実 FE・モックなし）であり、
  //    ここは fail-close の退行を防ぐための追加の網として置いている。
  await page.route('**/join-requests/me', (route) => route.abort('failed'))

  await loginAndNavigate(page, OUTSIDER_EMAIL, OUTSIDER_PASSWORD, `/organizations/${orgSlug}`, 'error')

  await expect(
    page.getByTestId('join-request-error'),
    '取得失敗時は「取得に失敗しました」が表示される',
  ).toBeVisible({ timeout: 25_000 })
  await expect(page.getByTestId('join-request-error')).toContainText('取得に失敗しました')
  await expect(
    page.getByTestId('join-request-retry-button'),
    '再試行ボタンが表示される',
  ).toBeVisible()
  await expect(
    page.getByTestId('join-request-apply-button'),
    '取得失敗時は申請ボタンを出さない（fail-close）',
  ).toHaveCount(0)

  // 遮断を解除して「再試行」を押すと復帰すること
  await page.unroute('**/join-requests/me')
  await page.getByTestId('join-request-retry-button').click()
  await expect(
    page.getByTestId('join-request-apply-button'),
    '再試行後は申請ボタンが復帰する',
  ).toBeVisible({ timeout: 20_000 })

  await page.screenshot({ path: 'test-results/jr-08-fetch-error-failclose.png', fullPage: true })
})
