/**
 * Issue #2508 ①（LocalDateTime 受け口へのユーザーTZオフセット明示付与）— 実機 E2E。
 *
 * ## なぜこの spec が要るのか
 *
 * FE のユニットテスト（`tests/unit/**`）は `$fetch` をモックしており、
 * 「FE が正しい形の文字列を組み立てる」ことしか証明していない。BE 側も
 * HTTP 往復を通した検証は 1 経路（Survey）に留まる。
 * よって **「FE が送った文字列を BE が実際に受理し、意図した壁時計で保存し、
 * 画面へ読み戻せる」という一気通貫が、分類A の 7 経路で未証明** である。
 * 本 spec がその穴を埋める。
 *
 * ## 検証している仕組み（実物のコードから確認済み）
 *
 * - FE `app/composables/useDatetime.ts` L78-88 `buildOffsetDateTimeStr()`
 *   → `dayjs(date).tz(userTimezone).format()` で **ユーザーTZのオフセット付き**
 *     ISO 文字列（例 `2027-03-15T10:30:00-07:00`）を作る。
 *     `userTimezone` は `useAuthStore().user?.timezone ?? 'Asia/Tokyo'`（＝ localStorage の `currentUser`）。
 * - BE `config/jackson/LocalDateTimeTimezoneDeserializer.java`
 *   → オフセット付き入力は `OffsetDateTime.parse(...).atZoneSameInstant(SERVER_ZONE)` で
 *     **Asia/Tokyo の壁時計**へ正規化して `LocalDateTime` に保持する。
 * - DB は `hibernate.jdbc.time_zone: UTC`（`application.yml` L72）なので、
 *   カラムの生値は **UTC 壁時計**になる（JST 壁時計 −9 時間）。
 * - BE `LocalDateTimeTimezoneSerializer` が出力時に JST 壁時計 → ユーザーTZへ戻すため、
 *   往復は恒等になるはずである。
 *
 * ## 各テストが見ているもの
 *
 * 1. 実UIで日時を入力して保存する（モックなし）
 * 2. 送信リクエストの本文を実測し、**オフセット付き文字列**であることを確認する
 * 3. レスポンスが **400 にならない**こと（BE がオフセット付き入力を受理する）
 * 4. 保存後の画面表示が入力した日時と一致すること（往復の恒等性）
 *
 * ## 非JST 検証（本 spec の核心・DT2508-08）
 *
 * JST ユーザーだけで通しても意味がない。`buildOffsetDateTimeStr` が付ける
 * オフセットが `+09:00` になり、BE の `SERVER_ZONE`（Asia/Tokyo）への変換が
 * **恒等変換に潰れる**ため、オフセット無しの旧実装と結果が区別できないからである。
 * DT2508-08 だけは `timezoneId: 'America/Los_Angeles'` かつユーザーの
 * `users.timezone` も `America/Los_Angeles` にして走らせる。
 *
 * 経路として「村の祭り」を選んだ理由:
 *   (a) 一覧カード（`VillageFestivalListSection.vue` L110-112）が
 *       `{{ f.startsAt }} 〜 {{ f.endsAt }}` と **BE の生 ISO 文字列を整形せず**表示するため、
 *       往復した瞬間・オフセットが DOM から直接観測できる（他 6 経路は整形済みで潰れる）
 *   (b) 入力が `<InputText type="datetime-local">`（他 6 経路の PrimeVue DatePicker と別系統）で、
 *       `fill()` が確実に効く。ピッカーの手入力パースに依存しない
 *   (c) 前提が seed 村 1 つだけで済む（MinIO 不要・レート制限なし・テンプレート連鎖なし）
 *   (d) `startsAt`/`endsAt` は時刻込みなので、時間単位のズレ（旧実装なら 16 時間）が検出できる
 *
 * ## 実行前提
 *   - BE / FE / MySQL / （DT2508-04 のみ）MinIO が起動済み
 *   - `backend/scripts/seed-e2e-data.js` 実行済み
 *   - `BASE_URL` / `API_BASE_URL` を実行時に指定する
 *     例: `BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *          npx playwright test --config=playwright-real.config.ts tests/e2e/real/datetime-offset-2508.spec.ts`
 *
 * ## 前提が崩れたときの方針
 *   API で作れる前提（支払い項目・ファイル・祭）は `beforeAll` で作る。
 *   作れないもの（disclosure エクスポート・未払いメンバー・レート制限）だけ、
 *   理由を明記して `test.skip` する。無条件スキップはしない。
 */

import { test, expect, type Page, type APIRequestContext, type Locator } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 環境
// ---------------------------------------------------------------------------
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

/** seed 済みチーム（FC東京U-18）。チーム詳細ページ/権限解決は slug 専用（数値 id は 404）。 */
const TEAM_SLUG = 'fc-u-18'
const TEAM_ID = 1

/** e2e-user が HEADMAN を務める seed 村（village-events-wave2.spec.ts L45 と同一）。 */
const VILLAGE_ID = '6e87b493-512a-11f1-95e3-2ec96fe3ea06'

const LA_TZ = 'America/Los_Angeles'
const JST_TZ = 'Asia/Tokyo'

// UI 文字列（locales/ja と一致することを確認済み）
const TXT = {
  broadcastButton: 'チーム内告知',
  next: '次へ',
  channelTimeline: 'タイムライン',
  channelSurvey: 'アンケート',
  submitBroadcast: '告知を送る',
  broadcastSuccess: '告知を送信しました',
  expiresAtLabel: '表示期限',
  closesAtLabel: '締切日時',
  festivalCreate: 'お祭りを企画',
  festivalSave: '保存',
  festivalSaveSuccess: 'お祭りを保存しました',
  festivalFilterAll: 'すべて',
  extendExpiry: '保管期限を延長',
} as const

// ---------------------------------------------------------------------------
// 汎用ヘルパー
// ---------------------------------------------------------------------------

/**
 * 瞬間（Date）を指定 IANA タイムゾーンの壁時計 `YYYY-MM-DD HH:mm:ss` に整形する。
 *
 * オフセットのリテラル（`-07:00` 等）を期待値に直書きすると DST 切替で壊れるため、
 * 「その瞬間を当該TZで見たときの壁時計」で比較する。
 */
function wallClockIn(instant: Date, timeZone: string): string {
  // sv-SE は ISO 風（YYYY-MM-DD HH:mm:ss）で返す
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(instant)
}

/** ISO 文字列が「オフセット（+09:00 / -07:00 / Z）を持つ」ことを表明する。 */
function expectHasOffset(iso: string, label: string): void {
  expect(
    /([+-]\d{2}:?\d{2}|Z)$/.test(iso),
    `${label} がオフセット付きで送られていない（Issue #2508 の修正が効いていない）: ${iso}`,
  ).toBe(true)
}

/**
 * FE(3001 等) 起源のブラウザ XHR を Node fetch で BE へ中継し、CORS を通す。
 * BE の許可オリジンに FE ポートが無い環境でも spec が落ちないようにするための、
 * 既存 real spec（village-events-wave2.spec.ts L52-101）と同一の作法。
 */
async function setupApiBridge(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    if (req.method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': BASE_URL,
          'access-control-allow-credentials': 'true',
          'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
          'access-control-allow-headers':
            req.headers()['access-control-request-headers'] ?? 'authorization,content-type',
        },
      })
      return
    }
    const url = req.url().replace(/^https?:\/\/[^/]+/, API_BASE)
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers())) {
      const lk = k.toLowerCase()
      // Origin/Referer/Host を落として「サーバー間リクエスト」として渡す（CORS 403 回避）
      if (lk === 'origin' || lk === 'referer' || lk === 'host') continue
      headers[k] = v
    }
    try {
      const bodyText = req.postData()
      const fetchRes = await fetch(url, {
        method: req.method(),
        headers,
        body: bodyText ?? undefined,
      })
      const resBody = await fetchRes.arrayBuffer()
      const resHeaders: Record<string, string> = {}
      fetchRes.headers.forEach((v, k) => {
        const lk = k.toLowerCase()
        if (lk === 'access-control-allow-origin' || lk === 'access-control-allow-credentials') return
        // node fetch は解凍済みなので圧縮/長さ系はブラウザを壊す
        if (lk === 'content-encoding' || lk === 'content-length' || lk === 'transfer-encoding') return
        resHeaders[k] = v
      })
      resHeaders['access-control-allow-origin'] = BASE_URL
      resHeaders['access-control-allow-credentials'] = 'true'
      await route.fulfill({ status: fetchRes.status, headers: resHeaders, body: Buffer.from(resBody) })
    } catch {
      await route.abort()
    }
  })
}

/**
 * BE へ直接ログインし、Cookie + Bearer + localStorage.currentUser を整える。
 *
 * `fixtures/auth.ts` の `loginViaApi` と同じ思想だが、
 * **`timezone` を明示指定できる**点だけが異なる（DT2508-08 で LA ユーザーを作るために必要）。
 * FE の `useDatetime().userTimezone` は `authStore.user?.timezone` を読むため、
 * localStorage 側の timezone がオフセット生成の入力そのものになる。
 */
async function loginViaApiWithTimezone(
  page: Page,
  credentials: { email: string; password: string },
  timezone: string,
): Promise<{ userId: number; accessToken: string }> {
  const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
    data: credentials,
  })
  if (!loginRes.ok()) {
    throw new Error(`API ログイン失敗 (${credentials.email}): ${loginRes.status()} ${await loginRes.text()}`)
  }
  const accessToken = (await loginRes.json()).data.accessToken as string

  const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`)
  if (!meRes.ok()) throw new Error(`/users/me 取得失敗: ${meRes.status()} ${await meRes.text()}`)
  const me = (await meRes.json()).data as {
    id: number
    email: string
    lastName: string
    firstName: string
    avatarUrl: string | null
    systemRole: string | null
  }

  await page.setExtraHTTPHeaders({ Authorization: `Bearer ${accessToken}` })
  await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(
    (user) => localStorage.setItem('currentUser', JSON.stringify(user)),
    {
      id: me.id,
      email: me.email,
      fullName: `${me.lastName} ${me.firstName}`,
      profileImageUrl: me.avatarUrl,
      systemRole: me.systemRole ?? undefined,
      timezone,
    },
  )
  return { userId: me.id, accessToken }
}

/** Node 側の認証済み API 呼び出し（前提作成・後始末用）。 */
async function api(
  ctx: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
) {
  const opt: { headers: Record<string, string>; data?: unknown } = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  }
  if (body !== undefined) opt.data = body
  return ctx.fetch(`${API_BASE}${path}`, { method, ...opt })
}

async function loginToken(
  ctx: APIRequestContext,
  email: string,
  password: string,
): Promise<{ token: string; userId: number }> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `login ${email}: ${res.status()}`).toBeTruthy()
  const b = (await res.json()).data
  return { token: b.accessToken, userId: b.userId }
}

/** ページ遷移＋ハイドレーション＋スピナー消滅を待つ。 */
async function goto(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // スピナーは一度も出ないことがある（キャッシュ済み描画）。その場合の待機失敗は
  // 「読み込み完了済み」を意味するため無視してよい。後続の expect が実体を検証する。
  // eslint-disable-next-line no-restricted-syntax -- 上記の理由により意図的な無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  await expect(page).not.toHaveURL(/\/login/)
}

/**
 * PrimeVue DatePicker に手入力する。
 *
 * `data-testid` / `id` は DatePicker の**ルート要素**に付き、実体の `<input>` はその子孫にある。
 * また本リポの規約どおり PrimeVue 入力は `fill()` だと v-model へ反映されないことがあるため、
 * クリック → 全選択 → `pressSequentially` → `Escape`（パネルを閉じる）で入れる。
 *
 * @param root DatePicker のルート Locator
 * @param text コンポーネントの `date-format` に一致した表示文字列（例 `2027/03/15 10:30`）
 */
async function typeIntoDatePicker(root: Locator, text: string): Promise<void> {
  const input = root.locator('input').first()
  await input.click()
  await input.press('ControlOrMeta+a')
  await input.pressSequentially(text, { delay: 15 })
  await input.press('Escape')
}

// ===========================================================================
// 経路① 出力履歴の保管期限延長（ExtendExpiryRequest.newExpiresAt）
//   URL: /property-disclosure/exports?organizationId=N（クエリ必須）
//   入力: [data-testid="extend-expiry-date-input"]（show-time あり・date-format="yy-mm-dd"）
//   送信: PATCH /api/v1/organizations/{orgId}/disclosure-exports/{id}/extend-expiry
// ===========================================================================
test.describe('DT2508-01 出力履歴の保管期限延長（newExpiresAt）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(120_000)

  let ctx: APIRequestContext
  let adminToken = ''
  /** 期限延長ボタンを出せる組織 ID とエクスポート ID。見つからなければ null（＝スキップ）。 */
  let orgId: number | null = null
  let exportId: string | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = (await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)).token

    // e2e-admin が所属する組織を総当りせず、権威実物（/me/organizations）から列挙する。
    const orgRes = await api(ctx, adminToken, 'GET', '/api/v1/me/organizations')
    if (!orgRes.ok()) return
    const orgs = ((await orgRes.json()).data ?? []) as Array<{ id: number; role?: string }>

    for (const org of orgs) {
      const listRes = await api(
        ctx,
        adminToken,
        'GET',
        `/api/v1/organizations/${org.id}/disclosure-exports?page=0&size=1`,
      )
      if (!listRes.ok()) continue
      const body = (await listRes.json()).data as { content?: Array<{ id: string }> } | Array<{ id: string }>
      const items = Array.isArray(body) ? body : (body?.content ?? [])
      if (items.length > 0) {
        orgId = org.id
        exportId = items[0]!.id
        return
      }
    }
    // 既存エクスポートが 1 件も無い場合は、テンプレート → ドラフト → エクスポートの連鎖で作る。
    for (const org of orgs) {
      const tplRes = await api(ctx, adminToken, 'GET', `/api/v1/disclosure-templates?organizationId=${org.id}`)
      if (!tplRes.ok()) continue
      const tpls = ((await tplRes.json()).data ?? []) as Array<{ id: string }>
      if (tpls.length === 0) continue

      const draftRes = await api(ctx, adminToken, 'POST', `/api/v1/organizations/${org.id}/disclosure-drafts`, {
        templateId: tpls[0]!.id,
        title: `#2508 E2E ドラフト ${Date.now()}`,
      })
      if (!draftRes.ok()) continue
      const draftId = (await draftRes.json()).data.id as string

      const expRes = await api(
        ctx,
        adminToken,
        'POST',
        `/api/v1/organizations/${org.id}/disclosure-drafts/${draftId}/export?format=pdf`,
        {},
      )
      if (expRes.status() !== 201) continue
      orgId = org.id
      exportId = (await expRes.json()).data.id as string
      return
    }
  })

  test.afterAll(async () => {
    // NOTE: disclosure-exports に DELETE エンドポイントは存在しない
    //       （DisclosureExportController は POST/GET/PATCH のみ。expires_at 到来で自動削除される設計）。
    //       よって作成したエクスポート行は削除できない。テスト用ドラフトのみ残るが、
    //       ドラフト削除もエクスポートの参照を壊しうるため意図的に行わない。
    await ctx.dispose()
  })

  test('DT2508-01: 保管期限を延長するとオフセット付きで送られ 400 にならず、一覧の期限表示が更新される', async ({ page }) => {
    test.skip(
      orgId === null || exportId === null,
      '保管期限延長の対象となる disclosure_exports が 1 件も無く、テンプレート→ドラフト→エクスポートの生成にも失敗したためスキップ',
    )

    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_ADMIN, JST_TZ)
    await goto(page, `/property-disclosure/exports?organizationId=${orgId}`)

    const extendBtn = page.getByTestId(`disclosure-extend-expiry-${exportId}`)
    await expect(extendBtn, '組織 ADMIN なら期限延長ボタンが出る').toBeVisible({ timeout: 20_000 })
    await extendBtn.click()

    const dialog = page.getByTestId('extend-expiry-dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // min-date=現在時刻 / max-date=本日+7年 の範囲内に収まる未来日時を選ぶ。
    const target = new Date(Date.now() + 120 * 24 * 3600_000)
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    const typed = `${y}-${m}-${d} 10:30` // date-format="yy-mm-dd" + show-time(24h)
    await typeIntoDatePicker(page.getByTestId('extend-expiry-date-input'), typed)

    const [req, res] = await Promise.all([
      page.waitForRequest(
        (r) => r.url().includes('/extend-expiry') && r.method() === 'PATCH',
        { timeout: 20_000 },
      ),
      page.waitForResponse(
        (r) => r.url().includes('/extend-expiry') && r.request().method() === 'PATCH',
        { timeout: 20_000 },
      ),
      page.getByTestId('extend-expiry-submit').click(),
    ])

    // (2) FE がオフセット付きで送っていること
    const sent = JSON.parse(req.postData() ?? '{}') as { newExpiresAt?: string }
    expect(sent.newExpiresAt, 'newExpiresAt が送られていること').toBeTruthy()
    expectHasOffset(sent.newExpiresAt!, 'newExpiresAt')

    // (3) BE がオフセット付き入力を受理すること（400 にならない）
    expect(
      res.status(),
      `PATCH extend-expiry が失敗した（400 ならオフセット付き入力を BE が受理していない）: ${await res.text()}`,
    ).toBe(200)

    // (4) 画面表示が入力した壁時計と一致すること（exports.vue の formatDate は 'YYYY/MM/DD HH:mm'）
    const expected = `${y}/${m}/${d} 10:30`
    await expect(
      page.getByTestId('disclosure-expires-at').filter({ hasText: expected }).first(),
      `延長後の期限表示が入力値と一致すること（期待 ${expected}）`,
    ).toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// 経路②③ 一斉配信（BroadcastRequestDto.expiresAt / AnnouncementContentRequest.closesAt）
//   URL: /teams/{slug} → ヘッダ「チーム内告知」→ ウィザード Step3
//   入力: DatePicker（testid 無し・ラベルで引く）
//   送信: POST /api/v1/teams/{teamId}/broadcast
//
// ⚠ レート制限: BroadcastRateLimitFilter が **ユーザー別 5 件/5 分**。
//    ②は e2e-user、③は e2e-admin と **バケットを分けて 1 発ずつ**しか投げない設計にしている。
//    429 は製品不具合ではなく環境制約なのでスキップ、それ以外の異常は assert で検知する。
// ===========================================================================
test.describe('DT2508-02/03 一斉配信の日時（expiresAt / closesAt）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(150_000)

  let ctx: APIRequestContext
  let userToken = ''
  /** 作成された告知フィード ID（後始末用）。 */
  const createdFeedIds: number[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = (await loginToken(ctx, E2E_USER.email, E2E_USER.password)).token
  })

  test.afterAll(async () => {
    for (const id of createdFeedIds) {
      // 後始末の失敗はテスト結果を左右しない（既に消えている・権限失効など）。
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'DELETE', `/api/v1/teams/${TEAM_ID}/announcements/${id}`).catch(() => {})
    }
    await ctx.dispose()
  })

  /** ウィザードを Step3 まで進める。 */
  async function openWizardToStep3(page: Page, channelLabel: string): Promise<Locator> {
    const btn = page.getByRole('button', { name: TXT.broadcastButton })
    await expect(btn, '「チーム内告知」ボタン（SUPPORTER 以外に表示）').toBeVisible({ timeout: 30_000 })
    await btn.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    // Step1: MEMBERS_AND_ABOVE が既定選択 → 次へ
    await dialog.getByRole('button', { name: TXT.next }).click()
    // Step2: チャネル選択 → 次へ
    await dialog.getByRole('button', { name: channelLabel }).click()
    await dialog.getByRole('button', { name: TXT.next }).click()
    await expect(dialog.getByRole('button', { name: TXT.submitBroadcast })).toBeVisible({ timeout: 10_000 })
    return dialog
  }

  /**
   * ラベル文字列からその欄の DatePicker ルートを引く。
   * BroadcastStep3Content.vue の各欄は `<div class="flex flex-col gap-1"><label>…</label><DatePicker/></div>` 構造で、
   * label に `for` が無いため `getByLabel` は使えない。
   */
  function datePickerByLabel(dialog: Locator, labelText: string): Locator {
    return dialog
      .locator('div.flex.flex-col.gap-1')
      .filter({ has: dialog.locator('label', { hasText: labelText }) })
      .first()
  }

  test('DT2508-02: タイムライン告知の「表示期限」がオフセット付きで送られ 400 にならない', async ({ page }) => {
    // dirty クローズ確認の window.confirm を自動承認する。既にダイアログが閉じていて
    // accept() が失敗しても検証対象ではないため無視する。
    // eslint-disable-next-line no-restricted-syntax -- 上記の理由により意図的な無視
    page.on('dialog', (d) => d.accept().catch(() => {}))
    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_USER, JST_TZ)
    await goto(page, `/teams/${TEAM_SLUG}`)

    const dialog = await openWizardToStep3(page, TXT.channelTimeline)
    await dialog.locator('textarea').first().fill(`#2508 expiresAt 実機E2E ${Date.now()}`)

    const target = new Date(Date.now() + 30 * 24 * 3600_000)
    const typed = `${target.getFullYear()}/${String(target.getMonth() + 1).padStart(2, '0')}/${String(target.getDate()).padStart(2, '0')} 18:00`
    await typeIntoDatePicker(datePickerByLabel(dialog, TXT.expiresAtLabel), typed)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => r.url().includes('/broadcast') && r.method() === 'POST', { timeout: 25_000 }),
      page.waitForResponse((r) => r.url().includes('/broadcast') && r.request().method() === 'POST', { timeout: 25_000 }),
      dialog.getByRole('button', { name: TXT.submitBroadcast }).click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { expiresAt?: string }
    expect(sent.expiresAt, 'expiresAt が送られていること（DatePicker への手入力が反映されている）').toBeTruthy()
    expectHasOffset(sent.expiresAt!, 'BroadcastRequestDto.expiresAt')

    if (res.status() === 429) {
      test.skip(true, 'broadcast レート制限（ユーザー別 5 件/5 分）に達したためスキップ')
    }
    expect(
      res.status(),
      `broadcast が失敗した（400 ならオフセット付き expiresAt を BE が受理していない）: ${await res.text()}`,
    ).toBe(201)

    const feedId = ((await res.json()).data as { announcementFeedId?: number })?.announcementFeedId
    if (typeof feedId === 'number') createdFeedIds.push(feedId)

    await expect(page.getByText(TXT.broadcastSuccess)).toBeVisible({ timeout: 10_000 })
  })

  test('DT2508-03: アンケート告知の「締切日時」がオフセット付きで送られ 400 にならない', async ({ page }) => {
    // dirty クローズ確認の window.confirm を自動承認する。既にダイアログが閉じていて
    // accept() が失敗しても検証対象ではないため無視する。
    // eslint-disable-next-line no-restricted-syntax -- 上記の理由により意図的な無視
    page.on('dialog', (d) => d.accept().catch(() => {}))
    await setupApiBridge(page)
    // ②とレート制限バケットを分けるため e2e-admin で投げる
    await loginViaApiWithTimezone(page, E2E_ADMIN, JST_TZ)
    await goto(page, `/teams/${TEAM_SLUG}`)

    let dialog: Locator
    try {
      dialog = await openWizardToStep3(page, TXT.channelSurvey)
    } catch {
      test.skip(true, 'このチームで SURVEY チャネルが無効（Step2 で選択できない）ためスキップ')
      return
    }

    // SURVEY は canSubmit にタイトル必須（BroadcastStep3Content.vue L207-220）
    await dialog.locator('input.p-inputtext').first().fill(`#2508 closesAt 実機E2E ${Date.now()}`)

    const target = new Date(Date.now() + 20 * 24 * 3600_000)
    const typed = `${target.getFullYear()}/${String(target.getMonth() + 1).padStart(2, '0')}/${String(target.getDate()).padStart(2, '0')} 21:15`
    await typeIntoDatePicker(datePickerByLabel(dialog, TXT.closesAtLabel), typed)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => r.url().includes('/broadcast') && r.method() === 'POST', { timeout: 25_000 }),
      page.waitForResponse((r) => r.url().includes('/broadcast') && r.request().method() === 'POST', { timeout: 25_000 }),
      dialog.getByRole('button', { name: TXT.submitBroadcast }).click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { content?: { closesAt?: string } }
    expect(sent.content?.closesAt, 'content.closesAt が送られていること').toBeTruthy()
    expectHasOffset(sent.content!.closesAt!, 'AnnouncementContentRequest.closesAt')

    if (res.status() === 429) {
      test.skip(true, 'broadcast レート制限（ユーザー別 5 件/5 分）に達したためスキップ')
    }
    expect(
      res.status(),
      `broadcast(SURVEY) が失敗した（400 ならオフセット付き closesAt を BE が受理していない）: ${await res.text()}`,
    ).toBe(201)

    const feedId = ((await res.json()).data as { announcementFeedId?: number })?.announcementFeedId
    if (typeof feedId === 'number') createdFeedIds.push(feedId)
  })
})

// ===========================================================================
// 経路④ ファイル共有リンク（CreateLinkRequest.expiresAt）
//   URL: /teams/{slug}/files
//   入力: [data-testid="share-link-expires"]（show-time あり・date-format="yy/mm/dd"・max-date=+30日）
//   送信: POST /api/v1/files/{fileId}/links
// ===========================================================================
test.describe('DT2508-04 ファイル共有リンクの有効期限（expiresAt）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(150_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let folderId: string | null = null
  let fileId: string | null = null
  let fileName = ''
  /** 前提づくりが失敗した理由（MinIO 未起動など）。null なら成功。 */
  let blockedReason: string | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = (await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)).token

    // 1) チームフォルダを確保（無ければ作る）
    const listRes = await api(ctx, adminToken, 'GET', `/api/v1/teams/${TEAM_ID}/folders`)
    if (listRes.ok()) {
      const folders = ((await listRes.json()).data ?? []) as Array<{ id: string }>
      folderId = folders[0]?.id ?? null
    }
    if (!folderId) {
      const createRes = await api(ctx, adminToken, 'POST', '/api/v1/files/folders', {
        scopeType: 'TEAM',
        scopeId: String(TEAM_ID),
        name: `#2508E2E-${Date.now()}`,
      })
      if (createRes.ok()) folderId = (await createRes.json()).data.id as string
    }
    if (!folderId) {
      blockedReason = 'チームのファイルフォルダを取得・作成できなかったためスキップ'
      return
    }

    // 2) presign → PUT → register でファイルを 1 件アップロードする
    //    （file-sharing-security.spec.ts L155-214 の実績手順）
    fileName = `issue2508-${Date.now()}.txt`
    const content = Buffer.from('Issue #2508 datetime offset E2E fixture\n', 'utf-8')
    const presignRes = await api(ctx, adminToken, 'POST', '/api/v1/files/presign-upload', {
      folderId,
      fileName,
      contentType: 'text/plain',
      fileSize: content.byteLength,
    })
    if (!presignRes.ok()) {
      blockedReason = `presign-upload が ${presignRes.status()}（MinIO 未起動の可能性: docker compose --profile storage up -d）のためスキップ`
      return
    }
    const { uploadUrl, fileKey } = (await presignRes.json()).data as { uploadUrl: string; fileKey: string }
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content,
    })
    if (!putRes.ok) {
      blockedReason = `オブジェクトストレージへの PUT が ${putRes.status}（MinIO 未起動の可能性）のためスキップ`
      return
    }
    const registerRes = await api(ctx, adminToken, 'POST', '/api/v1/files', {
      folderId,
      name: fileName,
      fileKey,
      fileSize: content.byteLength,
      contentType: 'text/plain',
    })
    if (registerRes.status() !== 201) {
      blockedReason = `ファイル登録が ${registerRes.status()} のためスキップ`
      return
    }
    fileId = (await registerRes.json()).data.id as string
  })

  test.afterAll(async () => {
    // 共有リンクはファイル削除に付随して消える。ファイル自体を消して後始末する。
    // 後始末の失敗はテスト結果を左右しない。
    // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
    if (fileId) await api(ctx, adminToken, 'DELETE', `/api/v1/files/${fileId}`).catch(() => {})
    await ctx.dispose()
  })

  test('DT2508-04: 共有リンクの有効期限がオフセット付きで送られ 400 にならず、一覧の期限表示が一致する', async ({ page }) => {
    test.skip(blockedReason !== null, blockedReason ?? '')
    test.skip(fileId === null, 'テスト用ファイルを用意できなかったためスキップ')

    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_ADMIN, JST_TZ)
    await goto(page, `/teams/${TEAM_SLUG}/files`)

    // 対象ファイルの行から共有ダイアログを開く（file-share-open は各行に常時描画される）
    const row = page.locator('div.flex.items-center.gap-3').filter({ hasText: fileName }).first()
    await expect(row, 'アップロードしたファイルの行が一覧に出ること').toBeVisible({ timeout: 25_000 })
    await row.getByTestId('file-share-open').click()

    const dialog = page.getByTestId('file-share-link-dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // max-date は +30 日なので 10 日後を選ぶ
    const target = new Date(Date.now() + 10 * 24 * 3600_000)
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    const typed = `${y}/${m}/${d} 09:45` // date-format="yy/mm/dd" + show-time hour-format="24"
    await typeIntoDatePicker(page.getByTestId('share-link-expires'), typed)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => /\/files\/[^/]+\/links$/.test(new URL(r.url()).pathname) && r.method() === 'POST', { timeout: 20_000 }),
      page.waitForResponse((r) => /\/files\/[^/]+\/links$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST', { timeout: 20_000 }),
      page.getByTestId('share-link-create').click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { expiresAt?: string }
    expect(sent.expiresAt, 'expiresAt が送られていること').toBeTruthy()
    expectHasOffset(sent.expiresAt!, 'CreateLinkRequest.expiresAt')

    expect(
      res.status(),
      `共有リンク作成が失敗した（400 ならオフセット付き expiresAt を BE が受理していない）: ${await res.text()}`,
    ).toBe(201)

    // 一覧の「有効期限」表示は useDatetime().formatDateTime（'YYYY/MM/DD HH:mm'・ユーザーTZ）
    await expect(
      dialog.locator('li').filter({ hasText: `${y}/${m}/${d} 09:45` }).first(),
      `作成された共有リンクの有効期限が入力値と一致すること（期待 ${y}/${m}/${d} 09:45）`,
    ).toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// 経路⑤⑥ 支払い記録（CreateManualPaymentRequest.paidAt）
//   URL: /teams/{slug}/payments
//   ⚠ payments.vue は route.params.slug を **そのまま scopeId として** API に渡す
//      （teamId への変換をしない）。既存 spec f089 と同様 slug のまま踏む。
//   入力: 単一=[data-testid="payment-record-paidat"] / 一括=ダイアログ内 DatePicker（testid 無し）
//        いずれも **日付のみ**（show-time 無し）。FE は buildOffsetDateTimeStr(date, '') で
//        00:00:00 + オフセットを送る。
// ===========================================================================
test.describe('DT2508-05/06 支払い記録の入金日（paidAt）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(180_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let singleItemId: number | null = null
  let singleItemName = ''
  let bulkItemId: number | null = null
  let bulkItemName = ''

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = (await loginToken(ctx, E2E_ADMIN.email, E2E_ADMIN.password)).token

    // 単一記録用と一括記録用で支払い項目を分ける（単一記録が PAID にしてしまい
    // 一括の対象＝未払いメンバーが枯れるのを避ける。f089 spec と同じ理由）。
    const stamp = Date.now()
    singleItemName = `#2508単体_${stamp}`
    const r1 = await api(ctx, adminToken, 'POST', `/api/v1/teams/${TEAM_ID}/payment-items`, {
      name: singleItemName,
      type: 'ANNUAL_FEE',
      amount: 5000,
    })
    if ([200, 201].includes(r1.status())) singleItemId = (await r1.json()).data.id as number

    bulkItemName = `#2508一括_${stamp}`
    const r2 = await api(ctx, adminToken, 'POST', `/api/v1/teams/${TEAM_ID}/payment-items`, {
      name: bulkItemName,
      type: 'ANNUAL_FEE',
      amount: 3000,
    })
    if ([200, 201].includes(r2.status())) bulkItemId = (await r2.json()).data.id as number
  })

  test.afterAll(async () => {
    // 支払い項目を消せば紐づく member_payments も落ちる（同一ドメイン内 CASCADE）。
    for (const id of [singleItemId, bulkItemId]) {
      if (id != null) {
        // 後始末の失敗はテスト結果を左右しない。
        // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
        await api(ctx, adminToken, 'DELETE', `/api/v1/teams/${TEAM_ID}/payment-items/${id}`).catch(() => {})
      }
    }
    await ctx.dispose()
  })

  /** 支払い管理画面を開き、左サイドバーから対象の支払い項目を選択する（選択するまで記録ボタンは DOM に無い）。 */
  async function openPaymentsAndSelectItem(page: Page, itemName: string): Promise<void> {
    await goto(page, `/teams/${TEAM_SLUG}/payments`)
    const itemButton = page.locator('.w-64 button').filter({ hasText: itemName }).first()
    await expect(itemButton, `支払い項目「${itemName}」がサイドバーに出ること`).toBeVisible({ timeout: 25_000 })
    await itemButton.click()
  }

  test('DT2508-05: 入金記録の paidAt がオフセット付きで送られ 400 にならない', async ({ page }) => {
    test.skip(singleItemId === null, '支払い項目を作成できなかったためスキップ')

    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_ADMIN, JST_TZ)
    await openPaymentsAndSelectItem(page, singleItemName)

    const openBtn = page.getByTestId('payment-record-open')
    await expect(openBtn).toBeVisible({ timeout: 20_000 })
    await openBtn.click()

    const dialog = page.getByTestId('payment-record-dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // メンバー選択（PrimeVue Select）
    await page.getByTestId('payment-record-member').click()
    const firstOption = page.locator('.p-select-list li, .p-dropdown-item').first()
    await expect(firstOption, 'メンバー選択肢が出ること').toBeVisible({ timeout: 10_000 })
    await firstOption.click()

    // 金額（InputNumber）
    await page.getByTestId('payment-record-amount').locator('input').first().fill('5000')

    // 入金日（日付のみ・date-format="yy-mm-dd"）。過去日を明示指定して
    // 「初期値の今日がたまたま通っただけ」にならないようにする。
    const target = new Date(Date.now() - 3 * 24 * 3600_000)
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    await typeIntoDatePicker(page.getByTestId('payment-record-paidat'), `${y}-${m}-${d}`)

    const isSinglePost = (url: string, method: string) =>
      method === 'POST' &&
      /\/payments$/.test(new URL(url).pathname)

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => isSinglePost(r.url(), r.method()), { timeout: 20_000 }),
      page.waitForResponse((r) => isSinglePost(r.url(), r.request().method()), { timeout: 20_000 }),
      page.getByTestId('payment-record-submit').click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { paidAt?: string }
    expect(sent.paidAt, 'paidAt が送られていること').toBeTruthy()
    expectHasOffset(sent.paidAt!, 'CreateManualPaymentRequest.paidAt')
    // 日付のみピッカーは buildOffsetDateTimeStr(date, '') で 00:00:00 固定になるはず
    // （time 省略だと実行時の現在時刻が混入する回帰があったため、ここで固定する）。
    expect(sent.paidAt, 'paidAt の時刻部が 00:00:00 であること').toMatch(/T00:00:00/)

    expect(
      res.status(),
      `入金記録が失敗した（400 ならオフセット付き paidAt を BE が受理していない）: ${await res.text()}`,
    ).toBe(201)

    // 一覧行の paidAt は BE の生文字列をそのまま表示する（PaymentAdminPanel.vue L394・整形なし）。
    // JST ユーザーなので入力した日付がそのまま現れる。
    await expect(
      page.locator('[data-testid^="payment-row-"]').filter({ hasText: `${y}-${m}-${d}` }).first(),
      `記録した入金日 ${y}-${m}-${d} が一覧に現れること`,
    ).toBeVisible({ timeout: 15_000 })
  })

  test('DT2508-06: 一括入金記録の paidAt がオフセット付きで送られ 400 にならない', async ({ page }) => {
    test.skip(bulkItemId === null, '一括記録用の支払い項目を作成できなかったためスキップ')

    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_ADMIN, JST_TZ)
    await openPaymentsAndSelectItem(page, bulkItemName)

    const openBtn = page.getByTestId('payment-bulk-open')
    await expect(openBtn).toBeVisible({ timeout: 20_000 })
    await openBtn.click()

    const dialog = page.getByTestId('payment-bulk-dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 一括記録の対象は既存の UNPAID/PENDING 行のみ（PaymentBulkRecordDialog.vue L42-46）。
    // 支払い項目を作っただけでは行が materialize されない環境では 0 件になる。
    const memberList = dialog.getByTestId('payment-bulk-member-list')
    // 未払いメンバーが 0 件だとリスト自体が描画されない。存在確認の失敗は
    // 「0 件」という正当な状態であり、直後に理由付きで test.skip する。
    const hasUnpaid = await memberList.isVisible().catch(() => false)
    test.skip(!hasUnpaid, '未払い（UNPAID/PENDING）のメンバーが 0 件で一括記録の対象が無いためスキップ')

    const firstMember = memberList.locator('[data-testid^="payment-bulk-member-"]').first()
    await expect(firstMember).toBeVisible({ timeout: 10_000 })
    await firstMember.click()

    // 入金日欄は testid も id も label 紐付けも無いため、ダイアログ内の DatePicker input を直接引く。
    const target = new Date(Date.now() - 5 * 24 * 3600_000)
    const y = target.getFullYear()
    const m = String(target.getMonth() + 1).padStart(2, '0')
    const d = String(target.getDate()).padStart(2, '0')
    const bulkDateInput = dialog.locator('input.p-datepicker-input').first()
    await bulkDateInput.click()
    await bulkDateInput.press('ControlOrMeta+a')
    await bulkDateInput.pressSequentially(`${y}-${m}-${d}`, { delay: 15 })
    await bulkDateInput.press('Escape')

    const isBulkPost = (url: string, method: string) =>
      method === 'POST' && new URL(url).pathname.endsWith('/payments/bulk')

    const [req, res] = await Promise.all([
      page.waitForRequest((r) => isBulkPost(r.url(), r.method()), { timeout: 20_000 }),
      page.waitForResponse((r) => isBulkPost(r.url(), r.request().method()), { timeout: 20_000 }),
      page.getByTestId('payment-bulk-submit').click(),
    ])

    const sent = JSON.parse(req.postData() ?? '{}') as { payments?: Array<{ paidAt?: string }> }
    expect(sent.payments?.length, '一括記録の明細が 1 件以上あること').toBeGreaterThan(0)
    for (const p of sent.payments!) {
      expect(p.paidAt, 'paidAt が送られていること').toBeTruthy()
      expectHasOffset(p.paidAt!, 'BulkPaymentRequest.payments[].paidAt')
      expect(p.paidAt, 'paidAt の時刻部が 00:00:00 であること').toMatch(/T00:00:00/)
    }

    expect(
      res.status(),
      `一括入金記録が失敗した（400 ならオフセット付き paidAt を BE が受理していない）: ${await res.text()}`,
    ).toBe(200)
  })
})

// ===========================================================================
// 経路⑦ 村の祭り（FestivalCreateRequest.startsAt / endsAt）— JST ユーザー
//   URL: /villages/{id}/festivals
//   入力: <InputText type="datetime-local">（他 6 経路と違い fill() が確実に効く）
// ===========================================================================
test.describe('DT2508-07 村の祭りの開催日時（startsAt / endsAt）— JST ユーザー', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(150_000)

  let ctx: APIRequestContext
  let userToken = ''
  const createdFestivalIds: string[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = (await loginToken(ctx, E2E_USER.email, E2E_USER.password)).token
  })

  test.afterAll(async () => {
    // NOTE: 祭に DELETE エンドポイントは無い（VillageFestivalController は cancel のみ）。
    //       中止（CANCELLED）に落として実質的に後始末する。
    for (const id of createdFestivalIds) {
      // 後始末の失敗はテスト結果を左右しない（既に消えている・権限失効など）。
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${id}/cancel`).catch(() => {})
    }
    await ctx.dispose()
  })

  test('DT2508-07: 祭を企画すると startsAt/endsAt がオフセット付きで送られ 400 にならず、一覧の日時が一致する', async ({ page }) => {
    await setupApiBridge(page)
    await loginViaApiWithTimezone(page, E2E_USER, JST_TZ)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    const title = `#2508祭JST-${Date.now()}`
    const startsWall = '2027-03-15T10:30'
    const endsWall = '2027-03-15T18:00'

    const result = await createFestivalViaUi(page, title, startsWall, endsWall)

    // (2) FE がオフセット付きで送っている
    expectHasOffset(result.sent.startsAt!, 'FestivalCreateRequest.startsAt')
    expectHasOffset(result.sent.endsAt!, 'FestivalCreateRequest.endsAt')

    // ブラウザTZ・ユーザーTZともに JST なので、送信値の JST 壁時計は入力どおり
    expect(wallClockIn(new Date(result.sent.startsAt!), JST_TZ)).toBe('2027-03-15 10:30:00')
    expect(wallClockIn(new Date(result.sent.endsAt!), JST_TZ)).toBe('2027-03-15 18:00:00')

    // (3) BE が受理する
    expect(
      result.status,
      `祭の作成が失敗した（400 ならオフセット付き startsAt/endsAt を BE が受理していない）: ${result.bodyText}`,
    ).toBe(201)
    createdFestivalIds.push(result.festivalId!)

    // (4) 一覧カードの生 ISO 文字列が同じ瞬間に読み戻せる（往復の恒等性）
    const card = await findFestivalCard(page, title)
    const cardText = (await card.innerText()).trim()
    const [returnedStart, returnedEnd] = parseCardPeriod(cardText)
    expect(wallClockIn(new Date(returnedStart), JST_TZ)).toBe('2027-03-15 10:30:00')
    expect(wallClockIn(new Date(returnedEnd), JST_TZ)).toBe('2027-03-15 18:00:00')
  })
})

// ===========================================================================
// 経路⑦（非JST）— 本 spec の核心
//   ブラウザTZ・ユーザーTZともに America/Los_Angeles にして、
//   「オフセット付きで送られた値が BE で正しく JST へ正規化される」ことを確認する。
//
//   旧実装（オフセット無しの壁時計送信）だと BE は "2027-03-15T10:30:00" を
//   JST の壁時計として取り込むため、LA の 10:30 が JST の 10:30（＝LA 前日 18:30）に
//   化ける。差は 16 時間で、下の壁時計 assert が確実に落ちる。
//   JST だけで通した DT2508-07 ではこの変換が恒等に潰れて区別できない。
// ===========================================================================
test.describe('DT2508-08 村の祭りの開催日時 — 非JST（America/Los_Angeles）ユーザー【核心】', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ timezoneId: LA_TZ })
  test.setTimeout(150_000)

  let ctx: APIRequestContext
  let userToken = ''
  /** 変更前の users.timezone（afterAll で必ず戻す）。 */
  let originalTimezone = JST_TZ
  const createdFestivalIds: string[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = (await loginToken(ctx, E2E_USER.email, E2E_USER.password)).token

    const meRes = await api(ctx, userToken, 'GET', '/api/v1/users/me')
    if (meRes.ok()) {
      originalTimezone = ((await meRes.json()).data as { timezone?: string }).timezone ?? JST_TZ
    }
    // users.timezone を LA にする。UpdateProfileRequest は null フィールドを「更新しない」と扱うため、
    // timezone だけを渡せば他のプロフィール項目は保たれる（UserService.updateProfile）。
    // TZ キャッシュ（TTL 5分）はコミット後に evict されるので即座に反映される（Issue #2487）。
    const putRes = await api(ctx, userToken, 'PUT', '/api/v1/users/me', { timezone: LA_TZ })
    expect(putRes.ok(), `users.timezone を ${LA_TZ} に変更できること: ${putRes.status()}`).toBeTruthy()
  })

  test.afterAll(async () => {
    for (const id of createdFestivalIds) {
      // 後始末の失敗はテスト結果を左右しない（既に消えている・権限失効など）。
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${id}/cancel`).catch(() => {})
    }
    // タイムゾーンを必ず元へ戻す（他 spec を巻き込まないため）
    // 復元失敗は後続 spec に影響しうるが、ここで throw すると afterAll が中断して
    // 祭の後始末まで巻き添えになるため無視する（失敗時は users.timezone を手動確認すること）。
    // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
    await api(ctx, userToken, 'PUT', '/api/v1/users/me', { timezone: originalTimezone }).catch(() => {})
    await ctx.dispose()
  })

  test('DT2508-08: LA ユーザーが入力した壁時計が、オフセット付き送信を経て BE で正しく正規化される', async ({ page }) => {
    await setupApiBridge(page)
    // localStorage 側の timezone も LA にする。
    // useDatetime().userTimezone は authStore.user?.timezone を読むため、これが
    // buildOffsetDateTimeStr のオフセット決定そのものになる。
    await loginViaApiWithTimezone(page, E2E_USER, LA_TZ)
    await goto(page, `/villages/${VILLAGE_ID}/festivals`)

    const title = `#2508祭LA-${Date.now()}`
    // ブラウザTZ = LA なので、datetime-local に入れた値は LA の壁時計として解釈される。
    const startsWall = '2027-03-15T10:30'
    const endsWall = '2027-03-15T18:00'

    const result = await createFestivalViaUi(page, title, startsWall, endsWall)

    // --- (2) FE が LA のオフセットを明示して送っていること -------------------
    expectHasOffset(result.sent.startsAt!, 'FestivalCreateRequest.startsAt')
    expectHasOffset(result.sent.endsAt!, 'FestivalCreateRequest.endsAt')
    // オフセットのリテラル（-07:00 / -08:00）は DST で変わるため、
    // 「その瞬間を LA で見た壁時計」が入力どおりであることで判定する。
    expect(
      wallClockIn(new Date(result.sent.startsAt!), LA_TZ),
      '送信された startsAt が LA の壁時計で入力どおりであること',
    ).toBe('2027-03-15 10:30:00')
    expect(wallClockIn(new Date(result.sent.endsAt!), LA_TZ)).toBe('2027-03-15 18:00:00')

    // 旧実装（オフセット無し）との差を明示的に固定する。
    // オフセットが無ければ BE は同じ文字列を JST 壁時計として取り込み、16 時間ずれる。
    expect(
      wallClockIn(new Date(result.sent.startsAt!), JST_TZ),
      '送信値を JST で見ると翌日 02:30 になる（LA 10:30 と同じ瞬間）',
    ).toBe('2027-03-16 02:30:00')

    // --- (3) BE がオフセット付き入力を受理すること -------------------------
    expect(
      result.status,
      `祭の作成が失敗した（400 ならオフセット付き startsAt/endsAt を BE が受理していない）: ${result.bodyText}`,
    ).toBe(201)
    createdFestivalIds.push(result.festivalId!)

    // --- (4) 往復の恒等性: 読み戻した値が LA の壁時計で入力どおりであること ---
    // 一覧カードは BE の生 ISO 文字列をそのまま描画する（整形されないので瞬間が直接見える）。
    const card = await findFestivalCard(page, title)
    const cardText = (await card.innerText()).trim()
    const [returnedStart, returnedEnd] = parseCardPeriod(cardText)

    expect(
      wallClockIn(new Date(returnedStart), LA_TZ),
      `読み戻した startsAt が LA の壁時計で入力どおりであること（実際のカード表示: ${cardText}）`,
    ).toBe('2027-03-15 10:30:00')
    expect(wallClockIn(new Date(returnedEnd), LA_TZ)).toBe('2027-03-15 18:00:00')

    // BE は JST 壁時計で保持するので、JST で見ると翌日 02:30 になっているはず
    // （DB の生値は hibernate.jdbc.time_zone=UTC なのでさらに −9 時間の 2027-03-15 17:30:00）。
    expect(wallClockIn(new Date(returnedStart), JST_TZ)).toBe('2027-03-16 02:30:00')
  })
})

// ---------------------------------------------------------------------------
// 村の祭り 共通ヘルパー（DT2508-07 / DT2508-08 で共有）
// ---------------------------------------------------------------------------

interface FestivalCreateOutcome {
  sent: { startsAt?: string; endsAt?: string }
  status: number
  bodyText: string
  festivalId: string | null
}

/**
 * 「お祭りを企画」ダイアログを実UIで操作して祭を作成し、送信内容とレスポンスを返す。
 *
 * `VillageFestivalCreateDialog.vue` には testid も id も無いため、
 * ダイアログをヘッダー文言で特定し、日時は 2 つしか存在しない
 * `input[type="datetime-local"]` を nth で引く（実物 L77-91）。
 */
async function createFestivalViaUi(
  page: Page,
  title: string,
  startsWall: string,
  endsWall: string,
): Promise<FestivalCreateOutcome> {
  const createBtn = page.getByRole('button', { name: TXT.festivalCreate }).first()
  await expect(createBtn, '村ADMIN（HEADMAN/ELDER）なら「お祭りを企画」ボタンが出る').toBeVisible({
    timeout: 25_000,
  })
  await createBtn.click()

  const dialog = page.getByRole('dialog').filter({ hasText: TXT.festivalCreate }).first()
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // タイトル（label「お祭り名」の直下の InputText）。datetime-local / color を除いた最初のテキスト入力。
  await dialog.locator('input.p-inputtext:not([type="datetime-local"]):not([type="color"])').first().fill(title)

  // 開始 / 終了（ネイティブ datetime-local なので fill() が確実に効く）
  const dtInputs = dialog.locator('input[type="datetime-local"]')
  await expect(dtInputs, '開始・終了の datetime-local が 2 つあること').toHaveCount(2)
  await dtInputs.nth(0).fill(startsWall)
  await dtInputs.nth(1).fill(endsWall)

  const isFestivalPost = (url: string, method: string) =>
    method === 'POST' && new URL(url).pathname.endsWith(`/villages/${VILLAGE_ID}/festivals`)

  const [req, res] = await Promise.all([
    page.waitForRequest((r) => isFestivalPost(r.url(), r.method()), { timeout: 20_000 }),
    page.waitForResponse((r) => isFestivalPost(r.url(), r.request().method()), { timeout: 20_000 }),
    dialog.getByRole('button', { name: TXT.festivalSave }).click(),
  ])

  const sent = JSON.parse(req.postData() ?? '{}') as { startsAt?: string; endsAt?: string }
  expect(sent.startsAt, 'startsAt が送られていること（不正日時なら FE が送信をブロックする）').toBeTruthy()
  expect(sent.endsAt, 'endsAt が送られていること').toBeTruthy()

  const status = res.status()
  // 本文読み取り失敗はテスト結果を左右しない（status で判定する）。失敗した事実は
  // プレースホルダ文字列として assert のメッセージに現れるので握り潰しにならない。
  const bodyText = await res.text().catch(() => '(body read failed)')
  let festivalId: string | null = null
  if (status === 201) {
    try {
      festivalId = (JSON.parse(bodyText).data as { id: string }).id
    } catch {
      festivalId = null
    }
  }
  return { sent, status, bodyText, festivalId }
}

/** 作成した祭のカードを一覧から見つける（作成直後は SCHEDULED なので「すべて」タブに切り替える）。 */
async function findFestivalCard(page: Page, title: string): Promise<Locator> {
  await expect(page.getByText(TXT.festivalSaveSuccess)).toBeVisible({ timeout: 10_000 })
  // 既定フィルタは ACTIVE。未来日時の祭は SCHEDULED になるため「すべて」に切り替える。
  await page.getByRole('button', { name: TXT.festivalFilterAll }).first().click()
  // 同上（スピナーが出ないケースの待機失敗は無視してよい）。
  // eslint-disable-next-line no-restricted-syntax -- 上記の理由により意図的な無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const card = page.locator('.village-festival__card', { hasText: title }).first()
  await expect(card, `作成した祭「${title}」のカードが一覧に出ること`).toBeVisible({ timeout: 20_000 })
  return card
}

/**
 * 祭カードのテキストから開始・終了の生 ISO 文字列を取り出す。
 *
 * `VillageFestivalListSection.vue` L110-112 は `{{ f.startsAt }} 〜 {{ f.endsAt }}` と
 * BE の生 ISO 文字列を整形せずに描画する（＝この spec が瞬間を直接観測できる理由）。
 */
function parseCardPeriod(cardText: string): [string, string] {
  const matches = cardText.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[+-]\d{2}:\d{2}|Z)?/g)
  expect(
    matches?.length,
    `祭カードに ISO 日時が 2 つ含まれること（実際のカードテキスト: ${cardText}）`,
  ).toBeGreaterThanOrEqual(2)
  return [matches![0]!, matches![1]!]
}
