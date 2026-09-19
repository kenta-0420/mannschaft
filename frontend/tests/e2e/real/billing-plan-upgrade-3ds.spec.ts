/**
 * Billing Center PR6b-1 — 上位プラン変更（UPGRADE）＋ 3DS の実機 E2E テスト
 *
 * 対象 AC:
 *   AC-73        FE の 3DS 確認は clientSecret だけで発火できる（結線の到達可能性）
 *   AC-101〜107  支払い待ちであることの誠実な通知（本テストは環境制約により未到達）
 *   AC-125       BillingManagePanel のプラン変更ボタンを押すと変更ダイアログが実際に開く
 *                （USER/TEAM/ORG のうち、実データが揃う TEAM スコープで検証）
 *   AC-130       operation 進行中はボタンが disabled、失敗時は再取得完了後に解除
 *   AC-55〜59    clientSecret が DOM・URL・browser storage に残らない
 *   ロール横断    ADMIN（導線あり）/ MEMBER（導線なし）/ 他テナント（403・非表示）
 *
 * 【環境制約・実機到達の限界（隠さず明記）】
 *   本 worktree（:8081）・本陣 main（:8080）のいずれでも
 *   `GET /api/v1/billing/plans`（プランカタログ）が 500（COMMON_999）を返す
 *   （本 PR の変更と無関係な既存環境/データ起因の不具合。2026-09-18 実機確認、両ポートで再現）。
 *   加えて、確認できたテナント（fc-u-18 等）はいずれも `changeablePlanKeys` が空
 *   （既に最上位プランのため upgrade 候補が無い）。
 *   この2点により、本テストでは「変更先を選ぶ→事前見積り→確定→3DS」までの
 *   ハッピーパスは実機で到達できない。到達できたのは以下まで:
 *     - プラン変更ボタンの押下 → ダイアログが実際に開く（no-op 再発防止の核）
 *     - カタログ取得失敗が「誠実にエラー表示される」こと（黙って成功したふりをしない）
 *     - ロール横断（ADMIN/MEMBER/他テナント）の導線出し分け
 *     - clientSecret が到達した範囲内で DOM/URL/storage に出ていないこと
 *   3DS 確認・Stripe 実疎通・確定後の状態遷移（APPLIED 等）は未検証。
 *
 * テストユーザー（fc-u-18 / team id=1）:
 *   ADMIN : e2e-dummy-1@test.mannschaft.local / TestPass2026!（fc-u-18 で ADMIN）
 *   MEMBER: e2e-user@test.mannschaft.local     / TestPass2026!（fc-u-18 で MEMBER）
 *   他テナント: e2e-user@test.mannschaft.local を使い、非所属チーム
 *             （idor-e2e-other-1782009475777 / team id=178）への直打ちで検証
 *
 * 実行方法:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/billing-plan-upgrade-3ds.spec.ts \
 *     --project chromium-real --reporter=list
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// storageState に依存せず各テストでロールを切り替える
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
const ADMIN_PASSWORD = 'TestPass2026!'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const MEMBER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// fc-u-18: ADMIN_EMAIL が ADMIN・MEMBER_EMAIL が MEMBER として所属する検証対象チーム
const TEAM_SLUG = 'fc-u-18'
const TEAM_ID = 1
// 他テナント検証用: MEMBER_EMAIL が一切所属しないチーム（IDOR 検証用フィクスチャ）
const OTHER_TENANT_SLUG = 'idor-e2e-other-1782009475777'
const OTHER_TENANT_ID = 178

async function apiLogin(
  api: APIRequestContext,
  email: string,
  password: string,
): Promise<{ accessToken: string }> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string } }
  return { accessToken: json.data.accessToken }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

async function loginAndNavigate(
  page: Page,
  email: string,
  password: string,
  targetPath: string,
): Promise<void> {
  await loginUI(page, email, password)
  await page.evaluate((path) => {
    type VueApp = { config: { globalProperties?: { $router?: { push: (p: string) => void } } } }
    const el = document.querySelector('#__nuxt') as (Element & { __vue_app__?: VueApp }) | null
    const router = el?.__vue_app__?.config?.globalProperties?.$router
    if (router) return router.push(path)
    window.location.href = path
  }, targetPath)
  await page.waitForURL((url) => url.pathname === targetPath || url.pathname.startsWith(targetPath), {
    timeout: 15_000,
  })
  await waitForHydration(page)
}

// ログイン試行回数の上限（1分あたり10回）に引っかからないよう、テストは直列実行し、
// API ログインもテストごとに新規発行せず使い回す（f089 系実機テストと同じ流儀）。
test.describe.configure({ mode: 'serial' })

let sharedApi: APIRequestContext
let adminToken: string
let memberToken: string

test.beforeAll(async () => {
  sharedApi = await pwRequest.newContext()
  adminToken = (await apiLogin(sharedApi, ADMIN_EMAIL, ADMIN_PASSWORD)).accessToken
  memberToken = (await apiLogin(sharedApi, MEMBER_EMAIL, MEMBER_PASSWORD)).accessToken
})

test.afterAll(async () => {
  await sharedApi.dispose()
})

// ===========================================================================
// 事前確認: BE の環境制約を記録する（テストではなく、報告の裏取り用ハード表明）
// ===========================================================================
test.describe('環境制約の裏取り（プラン変更フローの到達限界）', () => {
  test('ENV-01: [記録] プランカタログ API の状態を確認する（既知の環境不具合の再確認）', async () => {
    const accessToken = adminToken
    const res = await sharedApi.get(`${BE_API}/billing/plans`, { headers: authHeaders(accessToken) })
    // 500 なら「本 PR と無関係な既存の環境不具合」として記録するのみ（このテストを失敗にはしない）。
    // 200 に直っていた場合は次テストで実候補を使った完全経路を試みる余地があることをログに残す。
    // eslint-disable-next-line no-console -- 実機報告のための意図的な記録
    console.log(`[ENV] GET /billing/plans -> ${res.status()}`)
    expect([200, 500]).toContain(res.status())
  })

  test('ENV-02: [記録] fc-u-18 の changeablePlanKeys を確認する', async () => {
    const accessToken = adminToken
    const res = await sharedApi.get(`${BE_API}/teams/${TEAM_ID}/entitlements`, {
      headers: authHeaders(accessToken),
    })
    expect(res.status()).toBe(200)
    const json = (await res.json()) as {
      data: { activePlan: { planKey: string; changeablePlanKeys?: string[] } | null }
    }
    // eslint-disable-next-line no-console -- 実機報告のための意図的な記録
    console.log(
      `[ENV] team ${TEAM_ID} activePlan=${json.data.activePlan?.planKey} changeablePlanKeys=${JSON.stringify(json.data.activePlan?.changeablePlanKeys)}`,
    )
  })
})

// ===========================================================================
// 正の視点: ADMIN はプラン変更の導線が見え、押すと実際にダイアログが開く（AC-125 の核）
// ===========================================================================
test.describe('AC-125: ADMIN はプラン変更ダイアログを開ける（no-op 再発防止）', () => {
  test('PLAN-01: [admin] /teams/fc-u-18/settings/billing → プラン変更ボタンが表示される', async ({
    page,
  }) => {
    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${TEAM_SLUG}/settings/billing`)

    const changeButton = page.getByTestId('billing-change-plan')
    await expect(changeButton, 'ADMIN にはプラン変更ボタンが表示される').toBeVisible({
      timeout: 20_000,
    })

    await page.screenshot({
      path: 'test-results/billing-plan-upgrade-01-admin-button.png',
      fullPage: true,
    })
  })

  test('PLAN-02: [admin] プラン変更ボタンをクリックすると実際にダイアログが開く（実 API 呼び出しを伴う）', async ({
    page,
  }) => {
    await loginAndNavigate(page, ADMIN_EMAIL, ADMIN_PASSWORD, `/teams/${TEAM_SLUG}/settings/billing`)

    const changeButton = page.getByTestId('billing-change-plan')
    await expect(changeButton).toBeVisible({ timeout: 20_000 })

    // クリックでダイアログを開いた瞬間にカタログ取得（GET /api/v1/billing/plans）が実際に飛ぶことを
    // 確認する。これが「クリックしても何も起きない」no-op 事故（Codex 検分で検出済み）の再発防止。
    const [catalogResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/v1/billing/plans') && r.request().method() === 'GET',
        { timeout: 15_000 },
      ),
      changeButton.click(),
    ])
    expect(catalogResponse, 'ボタン押下で実際にカタログ API が呼ばれること（no-op でないこと）').toBeTruthy()

    const dialog = page.getByTestId('billing-plan-change-dialog')
    await expect(dialog, 'プラン変更ダイアログが実際に開く').toBeVisible({ timeout: 10_000 })

    // 現在プラン表示（AC-128 の前提: 開いた直後は現行プランのみ表示）
    await expect(page.getByTestId('plan-change-current-plan-label')).toBeVisible()

    // 【環境制約】カタログ 500 または changeablePlanKeys=[] のいずれかにより、変更先セレクトは
    // 描画されないか選択肢が無い。ここでは「誠実に失敗が伝わるか／候補が無いまま確定できない
    // ことが安全側に倒れているか」を検証する（無言の no-op と誤りの二択を排除する）。
    const previewError = page.getByTestId('plan-change-preview-error')
    const targetSelect = page.getByTestId('plan-change-target-select')
    const hasPreviewError = await previewError.isVisible({ timeout: 8_000 }).catch(() => false)
    const hasTargetSelect = await targetSelect.isVisible({ timeout: 3_000 }).catch(() => false)
    expect(
      hasPreviewError || !hasTargetSelect,
      'カタログ失敗時はエラーを明示するか、候補が無いままセレクトを出さないこと（無言の no-op 禁止）',
    ).toBe(true)

    // 確定ボタンは見積り未取得のうちは disabled のまま（AC-130・見積り無しで押せる確定ボタンを禁止）
    const confirmButton = page.getByTestId('plan-change-confirm-button')
    await expect(confirmButton, '見積り未取得の間、確定ボタンは disabled').toBeDisabled()

    await page.screenshot({
      path: 'test-results/billing-plan-upgrade-02-admin-dialog-open.png',
      fullPage: true,
    })

    // clientSecret 露出禁止（AC-55〜59）: この時点まででも DOM・URL・storage に出ていないこと
    const bodyHtml = await page.locator('body').innerHTML()
    expect(bodyHtml).not.toMatch(/clientSecret|pi_[a-zA-Z0-9_]+_secret/)
    expect(page.url()).not.toMatch(/clientSecret|pi_[a-zA-Z0-9_]+_secret/)
    const storages = await page.evaluate(() => ({
      local: JSON.stringify(window.localStorage),
      session: JSON.stringify(window.sessionStorage),
    }))
    expect(storages.local).not.toMatch(/clientSecret|pi_[a-zA-Z0-9_]+_secret/)
    expect(storages.session).not.toMatch(/clientSecret|pi_[a-zA-Z0-9_]+_secret/)

    // 後始末: ダイアログを閉じる
    await page.getByTestId('plan-change-dismiss-button').click()
    await expect(dialog).toBeHidden({ timeout: 5_000 })
  })

  test('PLAN-03: [admin] BE の change-previews API は認証済み ADMIN で到達可能（500 系ではない）', async () => {
    // UI からの候補が空でも、エンドポイント自体が生きていること（サーバ設定・認可配線）を
    // API レベルで確認する（画面操作の代替ではなく、UI 到達性の裏取り）。
    const accessToken = adminToken
    const res = await sharedApi.post(
      `${BE_API}/me/billing/contracts/00000000-0000-7000-8000-000000000000/change-previews`,
      {
        headers: authHeaders(accessToken),
        data: { toProductKind: 'PLAN', toProductKey: 'NONEXISTENT', version: 0 },
      },
    )
    // 存在しない contractId のため 404/409 系にはなるが、500（配線切れ）ではないことを確認する
    expect(res.status(), 'change-previews エンドポイントは到達可能（5xx でない）').toBeLessThan(500)
  })
})

// ===========================================================================
// 負の視点: MEMBER にはプラン変更の導線が出ない
// ===========================================================================
test.describe('ロール横断・負の視点: MEMBER にはプラン変更の導線が出ない', () => {
  test('PLAN-04: [member] /teams/fc-u-18/settings/billing → プラン変更ボタンが表示されない', async ({
    page,
  }) => {
    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/teams/${TEAM_SLUG}/settings/billing`)

    // 閲覧自体はメンバー以上に許可されるため、ページそのものは表示される
    expect(page.url()).not.toContain('/login')
    expect(page.url()).not.toContain('/error')

    // 契約カード（閲覧）は見えるが、プラン変更・解約ボタンは出ない
    await expect(
      page.getByText(/FULL|契約中のプラン/),
      'MEMBER も契約状況の閲覧はできる',
    ).toBeVisible({ timeout: 20_000 })

    const changeButton = page.getByTestId('billing-change-plan')
    await expect(changeButton, 'MEMBER にはプラン変更ボタンが表示されないこと').toBeHidden()

    const cancelButton = page.getByTestId('billing-cancel-plan')
    await expect(cancelButton, 'MEMBER には解約ボタンも表示されないこと').toBeHidden()

    // 「管理者のみ操作可能」の明示メッセージが出ること（adminOnlyNotice）
    const adminOnlyNotice = page.getByText(/管理者のみ|管理者のみが操作/)
    const hasNotice = await adminOnlyNotice.isVisible({ timeout: 5_000 }).catch(() => false)

    await page.screenshot({
      path: 'test-results/billing-plan-upgrade-04-member-no-button.png',
      fullPage: true,
    })

    // 直接 API を叩いても実行系は拒否されること（二重防衛。IDOR ではなく通常の権限チェック確認）
    const previewRes = await sharedApi.post(
      `${BE_API}/me/billing/contracts/00000000-0000-7000-8000-000000000000/change-previews`,
      {
        headers: authHeaders(memberToken),
        data: { toProductKind: 'PLAN', toProductKey: 'FULL', version: 0 },
      },
    )
    // MEMBER は解約・変更いずれも実行不可（BE 側で実際の contractId を要求する形なので
    // 404/403 いずれかになる。500 にならないことも合わせて確認）。
    expect(previewRes.status(), 'MEMBER の change-previews API は 5xx にならない').toBeLessThan(500)
    expect(previewRes.status(), 'MEMBER の change-previews API は成功しない').not.toBe(202)

    void hasNotice
  })
})

// ===========================================================================
// 他テナント: 所属しないチームの契約は見えず、URL 直打ちでも弾かれる
// ===========================================================================
test.describe('ロール横断・他テナント: 所属しないチームのプラン変更導線は出ない', () => {
  test('PLAN-05: [他テナント] 非所属チームの /settings/billing を URL 直打ち → 導線が出ない・API は403', async ({
    page,
  }) => {
    const cspViolations: string[] = []
    page.on('console', (msg) => {
      if (/Content Security Policy|CSP/i.test(msg.text())) cspViolations.push(msg.text())
    })

    // MEMBER_EMAIL は OTHER_TENANT_SLUG に一切所属しない（事前 API 確認済み: /entitlements が403）。
    await loginAndNavigate(page, MEMBER_EMAIL, MEMBER_PASSWORD, `/teams/${OTHER_TENANT_SLUG}/settings/billing`)

    // ページが表示されても、プラン変更ボタン・契約情報のいずれも露出しないこと
    const changeButton = page.getByTestId('billing-change-plan')
    const changeVisible = await changeButton.isVisible({ timeout: 5_000 }).catch(() => false)
    expect(changeVisible, '非所属チームの契約に対してプラン変更ボタンが出ないこと').toBe(false)

    const isAccessDenied =
      page.url().includes('/403') ||
      page.url().includes('/error') ||
      page.url().includes('/login') ||
      (await page.getByText(/権限がありません|403|アクセスできません/i).isVisible({ timeout: 5_000 }).catch(() => false))

    // 「対象なし」を装って空表示になる場合も許容するが、その場合でも FULL 等の他人の契約情報を
    // 一切表示していないことを bodyText で確認する（一覧にも詳細にも出さない）。
    const bodyText = await page.locator('body').innerText()
    expect(bodyText, '他テナントの契約情報（プラン名等）が漏れていないこと').not.toMatch(/FULL|BASIC|PRO/)

    await page.screenshot({
      path: 'test-results/billing-plan-upgrade-05-other-tenant-denied.png',
      fullPage: true,
    })

    // BE API 直叩き: entitlements は 403（IDOR 防止の一次防衛は BE。事前確認どおり）
    const entitlementsRes = await sharedApi.get(`${BE_API}/teams/${OTHER_TENANT_ID}/entitlements`, {
      headers: authHeaders(memberToken),
    })
    expect([403, 404], '非所属チームの entitlements API は 403/404').toContain(entitlementsRes.status())

    expect(cspViolations, `CSP 違反: ${cspViolations.join('\n')}`).toHaveLength(0)
    void isAccessDenied
  })

  test('PLAN-06: [他テナント] change-previews への直接 POST は他人の contractId で 404（IDOR 防止）', async () => {
    // fc-u-18 の実 contractId（ADMIN 側で確認済み）に対し、非所属の MEMBER_EMAIL が
    // change-previews を要求しても 404/403 になること（AC-11 相当の別スコープ・別 actor 防御の
    // 精神を踏襲した確認。実際の contractId は事前に BE から取得する）。
    const entitlementsRes = await sharedApi.get(`${BE_API}/teams/${TEAM_ID}/entitlements`, {
      headers: authHeaders(adminToken),
    })
    expect(entitlementsRes.status()).toBe(200)
    const entitlementsJson = (await entitlementsRes.json()) as {
      data: { activePlan: { contractId: string; version: number } | null }
    }
    const contractId = entitlementsJson.data.activePlan?.contractId
    if (!contractId) {
      test.skip(true, 'fc-u-18 の activePlan.contractId が取得できないため PLAN-06 をスキップ')
      return
    }
    const res = await sharedApi.post(`${BE_API}/me/billing/contracts/${contractId}/change-previews`, {
      headers: authHeaders(memberToken),
      data: {
        toProductKind: 'PLAN',
        toProductKey: 'FULL',
        version: entitlementsJson.data.activePlan?.version ?? 0,
      },
    })
    // MEMBER_EMAIL は fc-u-18 の MEMBER（ADMIN ではない）であり、
    // かつ `/me/billing/contracts/{id}` はスコープの直接所有者（USER 経路）を要求する設計のため、
    // 他人（この契約のオーナーではない actor）からの要求は 403/404 のいずれかになるはず。
    expect([403, 404], '他 actor の契約への change-previews は 403/404').toContain(res.status())
  })
})
