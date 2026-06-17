/**
 * 予約システムを使うチーム（美容院・整骨院など）向け
 * 管理ダッシュボード／予約確認フロー — 実機フルスタックE2Eテスト
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が
 * 起動済みの状態で実行してください。
 *
 * playwright.config.ts の chromium-real-admin プロジェクトで実行されます。
 *   storageState: tests/e2e/.auth/real-admin.json
 *   testMatch:    ** /real/admin/** /*.spec.ts
 *
 * このファイルを real/admin/ 配下に置く理由:
 *   予約の承認(confirm)/キャンセル(cancel)は ADMIN/DEPUTY 限定の操作であり、
 *   一般ユーザー storageState(real-user.json) では認可で弾かれる。
 *   e2e-admin@test.mannschaft.local は FC東京U-18（slug=fc-u-18）の ADMIN なので
 *   管理者 storageState で予約管理一気通貫を検証できる。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local（FC東京U-18 ADMIN / SYSTEM_ADMIN）
 *
 * 検証目的（memory: feedback_e2e_real_full_crud）:
 *   read-only/モックでは出ない本物のバグ（POST フィールド名不一致/契約ずれ/
 *   認可/シリアライズ/握りつぶしエラー）を認証付きCRUD一気通貫で捕捉する。
 *
 * 検証シナリオ:
 *   A. 管理者で /teams/fc-u-18/reservations を開き「予約一覧」タブ(Tab1)が表示される
 *   B. ライン作成→スロット作成→予約作成(PENDING)→承認(CONFIRMED) を実BEで一気通貫
 *   C. /dashboard（横スワイプ）に予約ウィジェットが存在するか実機で確認
 *   D. 実ブラウザで「予約する」タブ→スロット選択→予約フォーム送信で
 *      予約が PENDING で作成される（FE→BE 契約一致を UI 操作で実証）
 *
 * 【2026-06-17 根治済み】
 *   以前は FE(ReservationForm/useReservationApi)が {slotId, serviceNotes} を送り、
 *   BE が要求する {reservationSlotId, lineId, userNote} と不一致で予約作成が 400 になり、
 *   さらに notification.error で握りつぶされて会員が予約できない実機専用バグがあった。
 *   次の3点を修正して根治した:
 *     1) SlotPicker.vue が slotSelected で lineId も emit する
 *     2) ReservationForm.vue / useReservationApi.createReservation が
 *        body を { reservationSlotId, lineId, userNote } で送る
 *     3) reservations.vue の onSlotSelected が lineId を受け渡す
 *   シナリオD(RSV-REAL-004) は test.fail() を撤去し、実ブラウザ操作で
 *   予約が PENDING 成立することを assert する通常テストに書き換えてある。
 */

import { test as base, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
// e2e-admin が ADMIN を持つ予約対象チーム（slug 識別子。BE は slug を teamId パス変数として受け付ける）
const TEAM_SLUG = 'fc-u-18'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

/**
 * このプロジェクト環境では Nuxt dev サーバー(:3000) が /api/v1 を BE へプロキシしない。
 * Nuxt アプリ自体は NUXT_PUBLIC_API_BASE 既定値 http://localhost:8080 で BE を直接叩く構成。
 * そのため共有 setup(real-admin.setup.ts) の loginViaApi は相対URL(/api/v1/auth/login)を
 * :3000 に投げて 404 になる。ここでは BE(:8080) 絶対URLで自前ログインし、
 * 取得した accessToken を Bearer ヘッダで使う（API テスト）とともに、
 * localStorage['currentUser'] を書いて UI テストの認証状態を作る。
 * これは memory: feedback_e2e_wsl2_cors_apibridge / incident-banner-real.spec.ts の作法に倣う。
 *
 * authToken は worker スコープで一度だけ取得する（多並列の同時ログインで稀に
 * BE が 500 を返す事象を避けるため、ログイン回数を worker あたり 1 回に抑える）。
 */
const test = base.extend<
  { authToken: string; adminInit: boolean },
  { workerToken: { token: string; me: MeProfile } }
>({
  // storageState 依存を外す（共有 setup が生成できないため空で上書き）
  storageState: async (_deps, use) => {
    await use(undefined)
  },
  workerToken: [
    async (_deps, use) => {
      const ctx = await playwrightRequest.newContext()
      const loginRes = await ctx.post(`${BE}/api/v1/auth/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      })
      if (!loginRes.ok()) {
        throw new Error(`管理者ログイン失敗: ${loginRes.status()} ${await loginRes.text()}`)
      }
      const token = (await loginRes.json()).data.accessToken as string
      const meRes = await ctx.get(`${BE}/api/v1/users/me`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
      const me = (await meRes.json()).data as MeProfile
      await ctx.dispose()
      await use({ token, me })
    },
    { scope: 'worker' },
  ],
  authToken: async ({ workerToken }, use) => {
    await use(workerToken.token)
  },
  // UI テスト用: ブラウザに認証 Cookie + localStorage を仕込む
  adminInit: [
    async ({ page, workerToken }, use) => {
      // BE(:8080) に対する HttpOnly セッション Cookie を確立する
      await page.request.post(`${BE}/api/v1/auth/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      })
      const me = workerToken.me
      await page.goto('/')
      await page.evaluate(
        (user) => localStorage.setItem('currentUser', JSON.stringify(user)),
        {
          id: me.id,
          email: me.email,
          fullName: `${me.lastName} ${me.firstName}`,
          profileImageUrl: me.avatarUrl,
          systemRole: me.systemRole ?? undefined,
          timezone: me.timezone ?? undefined,
        },
      )
      await use(true)
    },
    { auto: true },
  ],
})

test.setTimeout(120_000)

/** Bearer 認証ヘッダ付きで API を叩く共通ヘルパ */
function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

// ---------------------------------------------------------------------------
// BE 直接ヘルパー（Bearer トークンで認証）
// ---------------------------------------------------------------------------

async function createLine(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<{ id: number }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
    headers: authHeaders(token),
    data: { name },
  })
  if (!resp.ok()) throw new Error(`createLine 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data
}

async function deleteLine(request: APIRequestContext, token: string, lineId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines/${lineId}`, {
    headers: authHeaders(token),
  })
}

async function createSlot(
  request: APIRequestContext,
  token: string,
  body: { slotDate: string; startTime: string; endTime: string },
): Promise<{ id: number }> {
  const resp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots`, {
    headers: authHeaders(token),
    data: body,
  })
  if (!resp.ok()) throw new Error(`createSlot 失敗: ${resp.status()} ${await resp.text()}`)
  return (await resp.json()).data
}

async function deleteSlot(request: APIRequestContext, token: string, slotId: number): Promise<void> {
  await request.delete(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots/${slotId}`, {
    headers: authHeaders(token),
  })
}

// 30日後の日付（YYYY-MM-DD）。過去日でのスロット作成バリデーションを避ける
function futureDate(daysAhead: number): string {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

// ---------------------------------------------------------------------------
// RSV-REAL-001: 認可 — 管理者は予約管理APIにアクセスできる
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-001: 予約管理API 認可', () => {
  test('RSV-REAL-001-01: 管理者は予約一覧APIに 200 でアクセスできる', async ({ request, authToken }) => {
    const resp = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
      headers: authHeaders(authToken),
    })
    expect(resp.status()).not.toBe(401)
    expect(resp.status()).not.toBe(403)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(body).toHaveProperty('data')
  })

  test('RSV-REAL-001-02: 管理者はライン/スロット一覧APIに 200 でアクセスできる', async ({ request, authToken }) => {
    const lines = await request.get(`${BE}/api/v1/teams/${TEAM_SLUG}/reservation-lines`, {
      headers: authHeaders(authToken),
    })
    expect(lines.status()).toBe(200)
    // スロット一覧は from/to（取得期間）が必須クエリパラメータ
    const slots = await request.get(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservation-slots?from=${futureDate(0)}&to=${futureDate(60)}`,
      { headers: authHeaders(authToken) },
    )
    expect(slots.status(), `スロット一覧失敗: ${await slots.text()}`).toBe(200)
  })
})

// ---------------------------------------------------------------------------
// シナリオA: 管理者向け予約管理ページ（UI）で「予約一覧」タブが表示される
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-002(A): 管理者向け予約管理ページ UI', () => {
  test('RSV-REAL-002-01: /teams/fc-u-18/reservations に予約タブが描画される', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.waitForTimeout(2_000)

    const bodyText = (await page.locator('body').textContent()) ?? ''
    // タブ「予約する」「予約一覧」が描画される（ロケール: ja）
    // 予約管理ページ自体が描画されていること（404/エラー画面でない）を確認する
    expect(page.url()).toContain(`/teams/${TEAM_SLUG}/reservations`)
    // 予約関連のUI文言が出る（タブラベル等）。文言が変わっても落ちないよう緩めに判定
    const hasReservationUi =
      bodyText.includes('予約') || (await page.getByRole('tab').count()) > 0
    expect(hasReservationUi).toBe(true)

    // 致命的なコンソールエラー（描画不能の兆候）が出ていないこと
    const fatal = consoleErrors.filter(
      (e) => /Cannot read|undefined is not|Hydration|TypeError/i.test(e),
    )
    if (fatal.length > 0) {
      console.warn('RSV-REAL-002-01: コンソールエラー検出:', fatal)
    }
    expect(fatal.length, `致命的コンソールエラー: ${fatal.join(' / ')}`).toBe(0)
  })
})

// ---------------------------------------------------------------------------
// シナリオB: 予約作成→承認 の一気通貫（実BE・正しい契約）
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-003(B): 予約 PENDING→CONFIRMED 一気通貫（実BE）', () => {
  let lineId: number | null = null
  let slotId: number | null = null

  test.afterEach(async ({ request, authToken }) => {
    if (slotId) {
      await deleteSlot(request, authToken, slotId).catch(() => {})
      slotId = null
    }
    if (lineId) {
      await deleteLine(request, authToken, lineId).catch(() => {})
      lineId = null
    }
  })

  test('RSV-REAL-003-01: ライン→スロット→予約(PENDING)→承認(CONFIRMED)', async ({ request, authToken }) => {
    // 1) ライン作成（日本語名で multibyte の往復も同時確認）
    const line = await createLine(request, authToken, `E2E予約ライン_${Date.now()}`)
    lineId = line.id
    expect(line.id).toBeTruthy()

    // 2) スロット作成
    const slot = await createSlot(request, authToken, {
      slotDate: futureDate(30),
      startTime: '10:00',
      endTime: '10:30',
    })
    slotId = slot.id
    expect(slot.id).toBeTruthy()

    // 3) 予約作成（BE 契約: reservationSlotId + lineId）
    const createResp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
      headers: authHeaders(authToken),
      data: { reservationSlotId: slotId, lineId, userNote: 'E2E確認用' },
    })
    expect(
      createResp.ok(),
      `予約作成失敗: ${createResp.status()} ${await createResp.text()}`,
    ).toBe(true)
    const created = (await createResp.json()).data
    const reservationId = created.id
    expect(created.status?.status).toBe('PENDING')

    // 4) 一覧の PENDING に出る
    const pendingList = await request.get(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservations?status=PENDING`,
      { headers: authHeaders(authToken) },
    )
    const pendingBody = await pendingList.json()
    const foundPending = (pendingBody.data ?? []).find(
      (r: { id: number }) => r.id === reservationId,
    )
    expect(foundPending).toBeTruthy()

    // 5) 承認（confirm）→ CONFIRMED
    const confirmResp = await request.post(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/confirm`,
      { headers: authHeaders(authToken) },
    )
    expect(
      confirmResp.ok(),
      `承認失敗: ${confirmResp.status()} ${await confirmResp.text()}`,
    ).toBe(true)
    const confirmed = (await confirmResp.json()).data
    expect(confirmed.status?.status).toBe('CONFIRMED')
    expect(confirmed.status?.confirmedAt).toBeTruthy()

    // 6) CONFIRMED 一覧に出る
    const confirmedList = await request.get(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservations?status=CONFIRMED`,
      { headers: authHeaders(authToken) },
    )
    const confirmedBody = await confirmedList.json()
    const foundConfirmed = (confirmedBody.data ?? []).find(
      (r: { id: number }) => r.id === reservationId,
    )
    expect(foundConfirmed).toBeTruthy()

    // 後片付けのためキャンセルしておく（slot/line は afterEach で削除）
    await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/cancel`, {
      headers: authHeaders(authToken),
      data: { reason: 'E2E cleanup' },
    })
  })
})

// ---------------------------------------------------------------------------
// シナリオD: 実ブラウザで「予約する」タブ→スロット選択→予約フォーム送信で
//           予約が PENDING 成立する（FE→BE 契約一致を UI 操作で実証・根治の証跡）
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-004(D): 会員 UI からの予約作成→PENDING 成立', () => {
  let lineId: number | null = null
  let slotId: number | null = null
  let reservationId: number | null = null

  test.afterEach(async ({ request, authToken }) => {
    // 作成された予約はキャンセルしておく（slot 削除の前に）
    if (reservationId) {
      await request
        .post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${reservationId}/cancel`, {
          headers: authHeaders(authToken),
          data: { reason: 'E2E cleanup' },
        })
        .catch(() => {})
      reservationId = null
    }
    if (slotId) {
      await deleteSlot(request, authToken, slotId).catch(() => {})
      slotId = null
    }
    if (lineId) {
      await deleteLine(request, authToken, lineId).catch(() => {})
      lineId = null
    }
  })

  /**
   * RSV-REAL-004-01:
   * 実ブラウザで予約管理ページの「予約する」タブを開き、ライン/スロット選択 →
   * 予約フォームで「予約する」ボタンを押す。
   * FE(ReservationForm.vue → useReservationApi.createReservation)が
   * 正しく { reservationSlotId, lineId, userNote } を送り、
   * BE(CreateReservationRequest)契約と一致して予約が PENDING で作成されることを実証する。
   *
   * 握りつぶし(notification.error)に頼らず、実際に予約が成立して BE 一覧に
   * PENDING で反映されることを assert する（root-cause 根治の証跡）。
   */
  test('RSV-REAL-004-01: UI で「予約する」→ライン/スロット選択→送信で予約が PENDING 成立する', async ({
    page,
    request,
    authToken,
  }) => {
    // 1) 事前準備: ライン + 近未来スロットを BE 直叩きで用意する
    const lineName = `E2E_UI予約ライン_${Date.now()}`
    const line = await createLine(request, authToken, lineName)
    lineId = line.id
    const slotDate = futureDate(32)
    const slot = await createSlot(request, authToken, {
      slotDate,
      startTime: '13:00',
      endTime: '13:30',
    })
    slotId = slot.id

    // 2) 予約管理ページを開く（既定タブは「予約する」= SlotPicker）
    await page.goto(`/teams/${TEAM_SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // 「予約する」タブを明示的に選択（既定で開いているが念のため）
    const bookTab = page.getByRole('tab', { name: '予約する' })
    if (await bookTab.count()) await bookTab.click()

    // 3) ライン Select で作成したラインを選ぶ
    //    PrimeVue Select はネイティブ <select> ではないため、トリガを開いてから選ぶ
    const lineSelect = page.locator('.p-select').first()
    await lineSelect.waitFor({ state: 'visible', timeout: 15_000 })
    await lineSelect.click()
    await page.getByRole('option', { name: lineName }).click()

    // 4) 日付ピッカーは既定で今日。スロットは指定日(32日後)に作ったので、
    //    SlotPicker の日付を作成スロットの日付に合わせる必要がある。
    //    DatePicker の入力欄へ直接日付文字列を入れて反映させる（yy/mm/dd 形式）。
    const ymd = slotDate.replaceAll('-', '/') // YYYY/MM/DD
    const dateInput = page.locator('.p-datepicker input').first()
    await dateInput.fill(ymd)
    await dateInput.press('Enter')

    // 5) 空きスロットボタン（13:00 - 13:30）が描画されるのを待ってクリック
    const slotButton = page.getByRole('button', { name: /13:00\s*-\s*13:30/ })
    await slotButton.waitFor({ state: 'visible', timeout: 15_000 })
    await slotButton.click()

    // 6) 予約確認ダイアログが開く → 「予約する」ボタンで送信
    const dialog = page.getByRole('dialog')
    await dialog.waitFor({ state: 'visible', timeout: 10_000 })
    // フォームの「予約する」ボタン（ダイアログ内）を押す
    await dialog.getByRole('button', { name: '予約する' }).click()

    // 7) 成功トーストが出る（握りつぶしの error トーストではないこと）
    await expect(page.getByText('予約が完了しました')).toBeVisible({ timeout: 15_000 })

    await page.screenshot({
      path: 'test-results/reservation-real-ui-pending-created.png',
      fullPage: true,
    })

    // 8) BE 一覧(PENDING)に実際に作成されたことを検証する（UI 操作の結果を実BEで裏取り）
    const pendingList = await request.get(
      `${BE}/api/v1/teams/${TEAM_SLUG}/reservations?status=PENDING`,
      { headers: authHeaders(authToken) },
    )
    expect(pendingList.status()).toBe(200)
    const pendingBody = await pendingList.json()
    // 予約レスポンスのスロットIDは identifier.reservationSlotId に入る（slot サマリは id を持たない）
    const created = (pendingBody.data ?? []).find(
      (r: { id: number; identifier?: { reservationSlotId?: number } }) =>
        r.identifier?.reservationSlotId === slotId,
    )
    expect(
      created,
      `UI から作成した予約が PENDING 一覧に出ない（slotId=${slotId}）。` +
        `FE→BE 予約作成契約の不一致が再発した可能性。`,
    ).toBeTruthy()
    reservationId = created.id
  })
})

// ---------------------------------------------------------------------------
// シナリオC: ダッシュボード（横スワイプ）に予約ウィジェットがあるか
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-005(C): 横スワイプダッシュボードの予約ウィジェット', () => {
  test('RSV-REAL-005-01: /dashboard を開きスクショ取得・予約ウィジェット有無を記録', async ({
    page,
  }) => {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.waitForTimeout(3_000)

    await page.screenshot({
      path: 'test-results/reservation-dashboard-real-dashboard.png',
      fullPage: true,
    })

    const bodyText = (await page.locator('body').textContent()) ?? ''
    // 予約ウィジェットらしき文言（「予約」を含むカード/見出し）が出るか
    const mentionsReservation = bodyText.includes('予約')
    console.log(
      `RSV-REAL-005-01: ダッシュボードに「予約」文言 ${mentionsReservation ? 'あり' : 'なし'}。` +
        `（設計上ダッシュボードに予約ウィジェットは含まれない想定）`,
    )
    // ダッシュボード自体が描画されていること（致命エラーで白画面でない）
    expect(page.url()).toContain('/dashboard')
    expect(bodyText.length).toBeGreaterThan(0)
  })
})
