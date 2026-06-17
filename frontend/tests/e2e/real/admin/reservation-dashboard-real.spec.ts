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
 *   D. FE が実際に送る予約作成ペイロードが BE 契約と一致するか（実機専用バグの捕捉）
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
  { authToken: string; adminInit: void },
  { workerToken: { token: string; me: MeProfile } }
>({
  // storageState 依存を外す（共有 setup が生成できないため空で上書き）
  storageState: async ({}, use) => {
    await use(undefined)
  },
  workerToken: [
    async ({}, use) => {
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
      await use()
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
// シナリオD: FE が実際に送る予約作成ペイロードが BE 契約と一致するか（実機専用バグ）
// ---------------------------------------------------------------------------

test.describe('RSV-REAL-004(D): FE→BE 予約作成ペイロード契約の整合', () => {
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

  /**
   * RSV-REAL-004-01:
   * FE の ReservationForm.vue → useReservationApi.createReservation() は
   * `{ slotId, serviceNotes }` を POST する。
   * 一方 BE の CreateReservationRequest は `{ reservationSlotId, lineId, userNote }`
   * を要求する（reservationSlotId/lineId は @NotNull）。
   *
   * このテストは「FE が実際に組み立てるペイロード」をそのまま投げ、
   * BE 契約と一致して 2xx になることを期待する。
   * 一致していなければ 400 になり、ユーザーが「予約する」ボタンを押しても
   * 予約できない（FE 側は notification.error で握りつぶす）実機バグを示す。
   *
   * 【既知バグ・2026-06-17 実機E2E で確認】
   *   現状 FE ペイロードは BE 契約と不一致で 400 になる（=予約作成導線が壊れている）。
   *   そのため test.fail() で「既知の失敗」として明示的に可視化する。
   *   症状を握りつぶさず、修正されたら test.fail() が「予期せぬ成功」を報告して
   *   このマーカーの除去を促す設計（対処療法ではなく可視化）。
   *
   *   根治するには次の3点を修正する必要がある:
   *     1) SlotPicker.vue が slotSelected で lineId も emit する
   *        （現状 slotId/lineName/date/startTime/endTime のみで lineId を渡していない）
   *     2) ReservationForm.vue / useReservationApi.createReservation が
   *        body を { reservationSlotId, lineId, userNote } で送る
   *        （現状 { slotId, serviceNotes }）
   *     3) reservations.vue の onSlotSelected も lineId を受け渡す
   */
  test('RSV-REAL-004-01: FE のペイロード {slotId, serviceNotes} で予約作成が 2xx になる', async ({
    request,
    authToken,
  }) => {
    // 既知バグ: 現状の FE ペイロードは BE 契約と不一致で 400 になる
    test.fail(
      true,
      'FE(ReservationForm/useReservationApi)が {slotId, serviceNotes} を送るが BE は {reservationSlotId, lineId, userNote} を要求するため予約作成が 400 になる既知バグ',
    )
    const line = await createLine(request, authToken, `E2E契約ライン_${Date.now()}`)
    lineId = line.id
    const slot = await createSlot(request, authToken, {
      slotDate: futureDate(31),
      startTime: '11:00',
      endTime: '11:30',
    })
    slotId = slot.id

    // FE（ReservationForm.vue → useReservationApi.createReservation）が実際に送る形
    const feResp = await request.post(`${BE}/api/v1/teams/${TEAM_SLUG}/reservations`, {
      headers: authHeaders(authToken),
      data: { slotId, serviceNotes: 'FE実送信ペイロード' },
    })

    const status = feResp.status()
    const text = await feResp.text()
    // 後片付け: 成功してしまった場合は作成された予約をキャンセル
    if (feResp.ok()) {
      const id = JSON.parse(text).data?.id
      if (id) {
        await request.post(
          `${BE}/api/v1/teams/${TEAM_SLUG}/reservations/${id}/cancel`,
          { headers: authHeaders(authToken), data: { reason: 'E2E cleanup' } },
        )
      }
    }

    expect(
      feResp.ok(),
      `FE ペイロード {slotId, serviceNotes} が BE 契約 {reservationSlotId, lineId, userNote} と不一致。` +
        `status=${status} body=${text}。` +
        `ReservationForm.vue / useReservationApi.createReservation を修正する必要がある。`,
    ).toBe(true)
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
