/**
 * Issue #2508 Phase 1（`@RequestParam` 日時のユーザーTZ正規化）— 実機 E2E。
 *
 * ## 何が直ったのか
 *
 * - BE (PR #2596) `UserZoneLocalDateTimeFormatter` / `UserZoneLocalDateTimeParser`
 *   - オフセット付き → `OffsetDateTime.parse(...).atZoneSameInstant(Asia/Tokyo)` で JST 壁時計へ正規化
 *   - オフセット無し → `TimezoneContextHolder` が解決済みならユーザーTZ壁時計として解釈
 *   - 不正 → `ParseException` → 400（500 に化けない）
 *   修正前は `@DateTimeFormat(iso = DATE_TIME)` の 20 経路が**オフセットを黙って捨て**、
 *   無アノテーションの 6 経路はオフセット付きで 400 だった。
 * - FE (PR #2600) 範囲検索クエリを**ユーザーTZ基準のオフセット付き**で送るよう是正
 *   （`useCalendarEvents` / `settings/login-history` / `WidgetAttendanceResults` /
 *    `me/guardianship/children/[childUserId]/watch` / `recruitment-listings`）
 *
 * ## この spec が「JST だけでは何も証明できない」問題にどう答えているか
 *
 * BE の変換は JST ユーザーでは恒等に潰れるため、JST での通過は修正前後を区別しない。
 * よって**主役は非JST（`America/Los_Angeles`）ケース**であり、JST ケースは
 * 「非JST 対応で JST が壊れていないこと（回帰なし）」だけを担当する。
 *
 * 非JST を成立させるには 2 箇所を同時に非JST にする必要がある:
 *   1. `localStorage.currentUser.timezone` — FE の `useDatetime().userTimezone` が読む実体
 *      （`authStore.user?.timezone ?? 'Asia/Tokyo'`）。`test.use({ timezoneId })` だけでは**効果ゼロ**
 *   2. DB `users.timezone` — BE の `TimezoneContextHolder` の供給元
 * どちらも `afterAll` で必ず元の値へ戻す。
 *
 * ## 「200 が返った」で終わらせないための設計（QP2508-01 の核心）
 *
 * `/calendar` は `useMyCalendarData` → `useCalendarEvents(fetcher, { cacheHalfMonths: 0 })` であり、
 * **取得レンジ ＝ 表示グリッドのレンジ**（6週=42セル、`buildGridRange`）である。
 * したがってグリッド両端に予定を仕込めば、レンジのずれが「予定の消失」として画面に出る。
 *
 * - 対照 A: グリッド開始日 00:30（ユーザーTZ壁時計）
 * - 判別 B: グリッド終了日 20:00（ユーザーTZ壁時計）  ← ここが効く
 *
 * 修正前（旧FE が裸のナイーブ文字列を送り、旧BE がそれを JST 壁時計として受けた）の `to` は
 * `{グリッド終了日}T23:59:59` の **JST 壁時計**だった。一方 B の瞬間を JST 壁時計で見ると
 * ロサンゼルスでは翌日の昼になり、旧 `to` を**超える**＝ B は取りこぼされて画面から消える。
 * この「取りこぼしが起きる仕込みになっていること」自体をテスト内で計算して表明しているので、
 * フィクスチャが判別力を失った場合はテストが赤くなる（無言の偽緑にならない）。
 *
 * ⚠ ただし正直に記しておくと、**BE が単独で強い**。PR #2596 の BE はオフセット無し入力も
 *   ユーザーTZ壁時計として解釈するため、旧FE のナイーブ文字列でも新BEなら同じ結果になりうる。
 *   よって QP2508-01 が直接殺せるのは「旧BE」であって「旧FE」ではない。
 *   旧FE を単独で殺せるのは、UTC 壁時計を裸で送っていた QP2508-04（出欠ウィジェット）である
 *   （`toISOString()` の UTC 壁時計を新BEがユーザーTZ壁時計と解釈すると、そのぶんだけずれる）。
 *
 * ## 期待値にオフセットのリテラルを書かない理由
 *
 * `-07:00` を直書きすると夏時間の切替で壊れる。本 spec は一貫して
 * 「その瞬間を当該TZで見た壁時計」（{@link wallClockIn}）で比較する。
 *
 * ## 実行方法
 *
 * ```
 * BASE_URL=http://127.0.0.1:3003 API_BASE_URL=http://127.0.0.1:8083 \
 *   npx playwright test --config=playwright-real.config.ts \
 *   tests/e2e/real/query-param-tz-2508-phase1.spec.ts
 * ```
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 環境
// ---------------------------------------------------------------------------
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

/** 既存の real spec（datetime-offset-2508.spec.ts L73-74 ほか）と同一の資格情報。 */
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }

const LA_TZ = 'America/Los_Angeles'
const JST_TZ = 'Asia/Tokyo'

/** seed 済みチーム（FC東京U-18）。詳細ページ/権限解決は slug 専用（数値 id は 404）。 */
const TEAM_SLUG = 'fc-u-18'

// ---------------------------------------------------------------------------
// 時刻ユーティリティ（オフセットのリテラルを一切書かないための道具）
// ---------------------------------------------------------------------------

/** 瞬間を指定 IANA TZ の壁時計 `YYYY-MM-DD HH:mm:ss` に整形する（sv-SE は ISO 風）。 */
function wallClockIn(instant: Date | number, timeZone: string): string {
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

/** 瞬間 `ts`（epoch ms）における当該TZの UTC からのオフセット（分）。 */
function tzOffsetMinutes(ts: number, timeZone: string): number {
  const wall = wallClockIn(ts, timeZone).replace(' ', 'T')
  return (Date.parse(`${wall}Z`) - ts) / 60_000
}

/**
 * 「当該TZの壁時計 `ymd` `hms`」が指す瞬間（epoch ms）を求める。
 *
 * UTC とみなした値から出発してオフセットで補正する定番手法。DST 切替をまたいでも
 * 2 回の反復で収束する（切替の隙間にあたる存在しない壁時計は本 spec では使わない）。
 */
function instantOf(ymd: string, hms: string, timeZone: string): number {
  const asUtc = Date.parse(`${ymd}T${hms}Z`)
  let ts = asUtc
  for (let i = 0; i < 2; i++) ts = asUtc - tzOffsetMinutes(ts, timeZone) * 60_000
  return ts
}

/** `±HH:MM` 形式のオフセット文字列。 */
function offsetLabel(minutes: number): string {
  const sign = minutes >= 0 ? '+' : '-'
  const abs = Math.abs(minutes)
  return `${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
}

/** 当該TZの壁時計をオフセット付き ISO-8601 文字列にする（例 `2026-08-02T00:30:00-07:00`）。 */
function offsetIso(ymd: string, hms: string, timeZone: string): string {
  const ts = instantOf(ymd, hms, timeZone)
  return `${ymd}T${hms}${offsetLabel(tzOffsetMinutes(ts, timeZone))}`
}

/** ISO 文字列が末尾にオフセット（`+09:00` / `-07:00` / `Z`）を持つことを表明する。 */
function expectHasOffset(iso: string, label: string): void {
  expect(
    /([+-]\d{2}:?\d{2}|Z)$/.test(iso),
    `${label} がオフセット無しで送られている（Issue #2508 Phase 1 の FE 修正が効いていない）: ${iso}`,
  ).toBe(true)
}

/**
 * 送信された日時クエリが「当該TZで見て期待の壁時計」であることを、
 * オフセットのリテラルに依存せず表明する。
 */
function expectWallClock(iso: string, expected: string, timeZone: string, label: string): void {
  expectHasOffset(iso, label)
  const actual = wallClockIn(Date.parse(iso), timeZone)
  expect(actual, `${label} を ${timeZone} の壁時計で見た値（送信値: ${iso}）`).toBe(expected)
}

const ymdOfUtc = (ts: number) => wallClockIn(ts, 'UTC').slice(0, 10)

/**
 * `useCalendarEvents.buildGridRange` と**同一のロジック**でグリッド両端の日付を求める。
 *
 * 本体は `new Date(year, month - 1, 1)` のローカル日付から曜日を取るが、
 * 「その年月の 1 日の曜日」も「そこから ±N 日」も暦の演算であって TZ に依存しないため、
 * ここでは再現性のために UTC で計算する。
 */
function gridRangeDates(year: number, month: number): { gridStart: string; gridEnd: string } {
  const firstUtc = Date.UTC(year, month - 1, 1)
  const startOffset = new Date(firstUtc).getUTCDay() // 0=日曜
  const startUtc = Date.UTC(year, month - 1, 1 - startOffset)
  const endUtc = Date.UTC(year, month - 1, 1 - startOffset + 41) // 42セル=6週
  return { gridStart: ymdOfUtc(startUtc), gridEnd: ymdOfUtc(endUtc) }
}

// ---------------------------------------------------------------------------
// API / 認証ヘルパー
// ---------------------------------------------------------------------------

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

async function loginToken(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `API ログイン (${email}) が ${res.status()}`).toBeTruthy()
  return (await res.json()).data.accessToken as string
}

/** `/api/v1/users/me` のうち本 spec が触る部分だけを型付けする。 */
interface ProfileShape {
  lastName: string
  firstName: string
  lastNameKana?: string | null
  firstNameKana?: string | null
  nickname?: string | null
  nickname2?: string | null
  locale?: string | null
  countryCode?: string | null
  timezone?: string | null
  isSearchable?: boolean
  avatarUrl?: string | null
  phoneNumber?: string | null
}

async function getProfile(ctx: APIRequestContext, token: string): Promise<ProfileShape> {
  const res = await api(ctx, token, 'GET', '/api/v1/users/me')
  expect(res.ok(), `/users/me が ${res.status()}`).toBeTruthy()
  return (await res.json()).data as ProfileShape
}

/**
 * DB `users.timezone` を書き換える。
 *
 * `PUT /api/v1/users/me` は差分更新ではないため、既存プロフィールを丸ごと送り直したうえで
 * timezone だけを差し替える（settings/language.vue の save() と同じ作法）。
 */
async function setDbTimezone(
  ctx: APIRequestContext,
  token: string,
  profile: ProfileShape,
  timezone: string,
): Promise<void> {
  const res = await api(ctx, token, 'PUT', '/api/v1/users/me', { ...profile, timezone })
  expect(res.ok(), `users.timezone を ${timezone} に更新（${res.status()}: ${await res.text()}）`).toBeTruthy()

  // 「書けたつもり」を許さない。実値を読み戻して確認する。
  const after = await getProfile(ctx, token)
  expect(after.timezone, 'DB の users.timezone が意図した値になっていること').toBe(timezone)
}

/**
 * BE へ直接ログインし、Cookie + Bearer + `localStorage.currentUser` を整える。
 *
 * `timezone` を明示指定できる点が既存 `loginViaApi` との差分であり、
 * これが無いと FE の `useDatetime().userTimezone` は常に `Asia/Tokyo` に落ちて
 * 非JST 検証が**通ったように見えて何も検証していない偽緑**になる。
 *
 * 単一セッションで回す（別 context で再ログインするとトークンが回り既存セッションが死ぬ）。
 */
async function loginInBrowser(page: Page, timezone: string): Promise<void> {
  const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, { data: E2E_USER })
  expect(loginRes.ok(), `ブラウザ用ログインが ${loginRes.status()}`).toBeTruthy()
  const accessToken = (await loginRes.json()).data.accessToken as string

  const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`)
  expect(meRes.ok(), `/users/me が ${meRes.status()}`).toBeTruthy()
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

  // 反映確認: FE が実際に読む値そのものを検証する（設定したつもりを許さない）
  const applied = await page.evaluate(
    () => (JSON.parse(localStorage.getItem('currentUser') ?? '{}') as { timezone?: string }).timezone,
  )
  expect(applied, 'localStorage.currentUser.timezone が FE 側の入力として設定されていること').toBe(timezone)
}

/**
 * FE 起源のブラウザ XHR を Node fetch で BE へ中継し、CORS を通す。
 * 既存 real spec（village-events-wave2 / datetime-offset-2508）と同一の作法。
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
      if (lk === 'origin' || lk === 'referer' || lk === 'host') continue
      headers[k] = v
    }
    try {
      const bodyText = req.postData()
      const fetchRes = await fetch(url, { method: req.method(), headers, body: bodyText ?? undefined })
      const resBody = await fetchRes.arrayBuffer()
      const resHeaders: Record<string, string> = {}
      fetchRes.headers.forEach((v, k) => {
        const lk = k.toLowerCase()
        if (lk === 'access-control-allow-origin' || lk === 'access-control-allow-credentials') return
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

/** ページ遷移＋ハイドレーション＋スピナー消滅を待つ。 */
async function goto(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // スピナーは描画キャッシュにより一度も出ないことがある。その待機失敗は「読み込み済み」を意味する。
  // eslint-disable-next-line no-restricted-syntax -- 上記の理由により意図的な無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  // ⚠ `/\/login/` の部分一致は使えない。`/settings/login-history` 自体が "/login" を含むため、
  //    正常に開けていても未ログイン扱いで落ちる（実測でこの spec が誤って赤くなった）。
  //    ログイン画面へ飛ばされたケースだけを拾うよう、パス末尾 or クエリ境界で判定する。
  await expect(page, 'ログイン画面へリダイレクトされていないこと').not.toHaveURL(/\/login(\?|$)/)
}

/** `/calendar` の個人予定取得リクエストか（`GET /api/v1/me/schedules?from=…&to=…`）。 */
function isMeSchedulesRangeRequest(url: string): boolean {
  const u = new URL(url)
  return u.pathname.endsWith('/api/v1/me/schedules') && u.searchParams.has('from') && u.searchParams.has('to')
}

// ===========================================================================
// QP2508-01 / 02: カレンダー月表示のレンジ（`GET /api/v1/me/schedules?from&to`）
//
//   経路: /calendar → useMyCalendarData → useCalendarEvents(cacheHalfMonths: 0)
//         → 取得レンジ ＝ 表示グリッド（6週=42セル）のレンジ
//   BE  : PersonalScheduleController#list の
//         `@RequestParam @DateTimeFormat(iso = DATE_TIME) LocalDateTime from/to`
//         ＝ Issue #2508 が挙げた「オフセットを黙って捨てる 20 経路」のひとつ
// ===========================================================================
test.describe('QP2508-01/02 カレンダー月表示のレンジがユーザーTZ基準になる', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(180_000)

  let ctx: APIRequestContext
  let token = ''
  let originalProfile: ProfileShape | null = null
  /** 後始末対象の個人予定 ID。 */
  const createdScheduleIds: number[] = []

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    token = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
    originalProfile = await getProfile(ctx, token)
  })

  test.afterAll(async () => {
    for (const id of createdScheduleIds) {
      // 後始末の失敗はテスト結果を左右しない（既に消えている等）。
      // eslint-disable-next-line no-restricted-syntax -- 後始末のため意図的な無視
      await api(ctx, token, 'DELETE', `/api/v1/me/schedules/${id}`).catch(() => {})
    }
    // users.timezone は**必ず**元へ戻す（戻し漏れは後続 spec を静かに壊す）。
    if (originalProfile) {
      await setDbTimezone(ctx, token, originalProfile, originalProfile.timezone ?? JST_TZ)
    }
    await ctx.dispose()
  })

  /**
   * グリッド両端に予定を仕込み、指定TZのユーザーで `/calendar` を開いて
   * 「両方見えること」と「クエリがユーザーTZのオフセット付きであること」を実測する。
   *
   * @param expectPreFixWouldDrop true のとき、「修正前のレンジなら判別用の予定が
   *        取りこぼされる仕込みになっていること」も併せて表明する（非JST 専用）。
   */
  async function runCalendarCase(page: Page, tz: string, expectPreFixWouldDrop: boolean): Promise<void> {
    // 1) DB と localStorage の**両方**を当該TZにする（片方だけでは検証にならない）
    expect(originalProfile, '元プロフィールが取得できていること').not.toBeNull()
    await setDbTimezone(ctx, token, originalProfile!, tz)

    await setupApiBridge(page)
    await loginInBrowser(page, tz)

    // 2) FE が開く月を**ブラウザ自身に聞く**。
    //    `useCalendarEvents` の初期表示月は `new Date()`（＝ブラウザTZ）由来であり、
    //    Node 側で推測すると、ブラウザTZとユーザーTZが日付をまたぐ時間帯に静かにずれる。
    //    月末深夜だけ落ちる時限爆弾を作らないよう、実物から取る。（食い違い＝ズレ）
    const [year, month] = await page.evaluate(() => {
      const d = new Date()
      return [d.getFullYear(), d.getMonth() + 1] as [number, number]
    })
    const { gridStart, gridEnd } = gridRangeDates(year, month)

    // 3) 両端に予定を作る（作成の本文側は既に OffsetDateTime 対応済みの経路）
    const stamp = Date.now()
    const titleHead = `#2508P1 端HEAD ${stamp}`
    const titleTail = `#2508P1 端TAIL ${stamp}`

    const fixtures = [
      { title: titleHead, ymd: gridStart, from: '00:30:00', to: '01:30:00' },
      { title: titleTail, ymd: gridEnd, from: '20:00:00', to: '21:00:00' },
    ]
    for (const f of fixtures) {
      const res = await api(ctx, token, 'POST', '/api/v1/me/schedules', {
        title: f.title,
        startAt: offsetIso(f.ymd, f.from, tz),
        endAt: offsetIso(f.ymd, f.to, tz),
        allDay: false,
      })
      expect(res.status(), `個人予定「${f.title}」の作成: ${await res.text()}`).toBe(201)
      createdScheduleIds.push((await res.json()).data.id as number)
    }

    // 4) 「修正前なら取りこぼされる」仕込みになっていることを、テスト自身が計算して確認する。
    //    修正前の to は「グリッド終了日 23:59:59 の **JST 壁時計**」だった
    //    （旧FE がナイーブ文字列を送り、旧BE がオフセットを捨てて JST として受けたため）。
    const tailInstant = instantOf(gridEnd, '20:00:00', tz)
    const tailJst = wallClockIn(tailInstant, JST_TZ)
    const preFixToJst = `${gridEnd} 23:59:59`
    if (expectPreFixWouldDrop) {
      expect(
        tailJst > preFixToJst,
        `判別用の予定が「修正前レンジの外」に無い＝この仕込みは判別力が無い`
        + `（判別予定の JST 壁時計 ${tailJst} / 修正前 to ${preFixToJst}）`,
      ).toBe(true)
    }

    // 5) 個人スコープだけ表示させる。
    //    `useMyCalendarData` の既定は「保存済みフィルタが無く、かつスコープ選択肢が
    //    2 件以上のときだけ全選択」であり、放置すると空フィルタで何も出ない可能性がある。
    await page.evaluate(() =>
      localStorage.setItem('mannschaft:calendar:scopeFilter', JSON.stringify(['PERSONAL'])),
    )

    // 6) 画面を開き、実際に飛んだクエリを捕捉する
    const [rangeReq] = await Promise.all([
      page.waitForRequest((r) => r.method() === 'GET' && isMeSchedulesRangeRequest(r.url()), { timeout: 30_000 }),
      goto(page, '/calendar'),
    ])
    const q = new URL(rangeReq.url()).searchParams
    const sentFrom = q.get('from') ?? ''
    const sentTo = q.get('to') ?? ''

    // 7) クエリがユーザーTZ基準のオフセット付きであること（期待値にオフセットは直書きしない）
    expectWallClock(sentFrom, `${gridStart} 00:00:00`, tz, 'GET /me/schedules の from')
    expectWallClock(sentTo, `${gridEnd} 23:59:59`, tz, 'GET /me/schedules の to')

    // 8) ── 本題 ── 両端の予定が画面に見えること
    await expect(
      page.getByText(titleHead, { exact: false }).first(),
      `グリッド開始日（${gridStart} 00:30 ${tz}）の予定がカレンダーに描画されること`,
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(titleTail, { exact: false }).first(),
      `グリッド終了日（${gridEnd} 20:00 ${tz} ＝ JST ${tailJst}）の予定がカレンダーに描画されること。`
      + `修正前の to は JST ${preFixToJst} だったため、この予定は取りこぼされて消えていた`,
    ).toBeVisible({ timeout: 30_000 })
  }

  test('QP2508-01: America/Los_Angeles のユーザーでグリッド両端の予定が取りこぼされない【核心】', async ({ page }) => {
    await runCalendarCase(page, LA_TZ, true)
  })

  test('QP2508-02: Asia/Tokyo のユーザーでも同じくグリッド両端の予定が見える（回帰なし）', async ({ page }) => {
    await runCalendarCase(page, JST_TZ, false)
  })
})

// ===========================================================================
// QP2508-03: ログイン履歴の期間指定（`GET /api/v1/users/me/login-history?from&to`）
//
//   settings/login-history.vue は修正前、`buildOffsetDateTimeStr()` が付けたオフセットを
//   正規表現で**剥ぎ取って**送っていた（当時の BE がオフセットを 400 にしたための回避策）。
//   PR #2596 で BE がオフセット付きを受理し、かつオフセット無しも「ユーザーTZ壁時計」として
//   解釈するようになったため、回避策を残すと**二重補正**で逆方向にずれる。
//   ここでは回避策撤去後に期間指定が意図どおり効いていることを実測する。
// ===========================================================================
test.describe('QP2508-03 ログイン履歴の期間指定に二重補正が起きていない', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(150_000)

  /**
   * ブラウザTZもユーザーTZと揃える（＝実在する LA ユーザーを模す）。
   *
   * この経路だけ揃える必要があるのは、日付 DatePicker の v-model が
   * **ブラウザローカルの `Date`** であり、それを `buildOffsetDateTimeStr()` が
   * `dayjs(date).tz(userTimezone).format('YYYY-MM-DD')` でユーザーTZへ投影し直すためである。
   * ブラウザTZ(JST) とユーザーTZ(LA) を食い違わせると、ユーザーが 8/4 を選んでも
   * 送信される日付が 8/3 になる（JST 8/4 00:00 ＝ LA 8/3 08:00）。実測で確認した。
   * これは #2508 の回帰ではなく `buildOffsetDateTimeStr` の従来からの意味論であり、
   * 本 spec の検証対象（クエリのTZ解釈）とは別の論点なので、ここでは現実的な条件に揃える。
   */
  test.use({ timezoneId: LA_TZ })

  let ctx: APIRequestContext
  let token = ''
  let originalProfile: ProfileShape | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    token = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
    originalProfile = await getProfile(ctx, token)
    await setDbTimezone(ctx, token, originalProfile, LA_TZ)
  })

  test.afterAll(async () => {
    if (originalProfile) {
      await setDbTimezone(ctx, token, originalProfile, originalProfile.timezone ?? JST_TZ)
    }
    await ctx.dispose()
  })

  test('QP2508-03: LA ユーザーが「LA の今日」で絞ると、返る履歴がすべて LA の今日である', async ({ page }) => {
    await setupApiBridge(page)
    // ここでのログイン自体が LOGIN_SUCCESS を 1 件積む（＝「LA の今日」に必ず 1 件は存在する）
    await loginInBrowser(page, LA_TZ)

    const today = wallClockIn(Date.now(), LA_TZ).slice(0, 10)
    const [y, m, d] = today.split('-') as [string, string, string]
    const picked = `${y}/${m}/${d}` // DatePicker の date-format="yy/mm/dd"

    await goto(page, '/settings/login-history')

    // 開始日・終了日ともに「LA の今日」を入れる。
    // この 2 つは **show-time を持たない**日付のみ DatePicker なので手入力が効く
    // （show-time 付きは PrimeVue の populateTime が例外を投げ v-model が更新されない既知の欠陥）。
    const pickers = page.locator('.p-datepicker')
    await expect(pickers.first(), '期間指定の DatePicker が描画されること').toBeVisible({ timeout: 20_000 })
    for (const idx of [0, 1]) {
      const input = pickers.nth(idx).locator('input').first()
      await input.click()
      await input.press('ControlOrMeta+a')
      await input.pressSequentially(picked, { delay: 15 })
      await input.press('Escape')
      await expect(input, `${idx === 0 ? '開始日' : '終了日'}が v-model に反映されること`).toHaveValue(picked)
    }

    const [req, res] = await Promise.all([
      page.waitForRequest(
        (r) => r.url().includes('/login-history') && new URL(r.url()).searchParams.has('from'),
        { timeout: 20_000 },
      ),
      page.waitForResponse((r) => r.url().includes('/login-history'), { timeout: 20_000 }),
      page.getByRole('button', { name: '検索' }).click(),
    ])

    // (1) 回避策が復活していないこと＋ユーザーTZ基準であること
    const q = new URL(req.url()).searchParams
    // login-history.vue は開始 00:00 / 終了 23:59（秒は 00）で組み立てる
    expectWallClock(q.get('from') ?? '', `${today} 00:00:00`, LA_TZ, 'login-history の from')
    expectWallClock(q.get('to') ?? '', `${today} 23:59:00`, LA_TZ, 'login-history の to')

    // (2) BE が 400 にしないこと（オフセット付き受理）
    expect(res.status(), `login-history が失敗した: ${await res.text()}`).toBe(200)

    // (3) ── 本題 ── 返ってきた行がすべて「LA の今日」であること。
    //     二重補正が起きていればレンジが 16 時間ずれ、LA の別の日の行が混ざる。
    const rows = ((await res.json()).data ?? []) as Array<{ createdAt: string }>
    expect(rows.length, '直前のログインぶんが少なくとも 1 件は返ること').toBeGreaterThan(0)
    for (const row of rows) {
      expect(
        wallClockIn(Date.parse(row.createdAt), LA_TZ).slice(0, 10),
        `返却された履歴 createdAt=${row.createdAt} が LA の今日（${today}）に収まっていること`,
      ).toBe(today)
    }

    // (4) 画面にも反映されていること（API だけ見て終わらせない）
    await expect(
      page.getByText('ログイン履歴がありません'),
      '期間指定の結果が空表示になっていないこと',
    ).toHaveCount(0)
    const shownDates = await page.locator('.text-xs.text-surface-400').allInnerTexts()
    const dateTexts = shownDates.filter((s) => /^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}$/.test(s.trim()))
    expect(dateTexts.length, '履歴カードに日時が描画されていること').toBeGreaterThan(0)
    for (const textValue of dateTexts) {
      expect(
        textValue.trim().slice(0, 10),
        `画面表示の日時 ${textValue} が LA の今日（${picked}）であること`,
      ).toBe(picked)
    }
  })
})

// ===========================================================================
// QP2508-04: 出欠ウィジェットのレンジ（`GET /api/v1/{scope}/schedules?from&to`）
//
//   修正前は `new Date(...).toISOString().slice(0, 19)` ＝ **UTC 壁時計**を裸で送っていた。
//   これは JST ユーザーを含む**全ユーザー**で 9 時間ずれる（新BEは裸の文字列をユーザーTZ壁時計と
//   解釈するため、UTC 壁時計を渡すとそのぶんずれる）。
//   ＝ Phase 1 のなかで唯一「新BE でも旧FE を殺せる」経路であり、ここは JST で検証する価値がある。
// ===========================================================================
test.describe('QP2508-04 出欠ウィジェットが 9 時間ずれない範囲を引く', () => {
  test.setTimeout(150_000)

  test('QP2508-04: ダッシュボードの出欠ウィジェットが「今日±30日」をユーザーTZ基準で引く', async ({ page }) => {
    await setupApiBridge(page)
    await loginInBrowser(page, JST_TZ)

    // ウィジェットは ScopeDashboard（チーム/組織ダッシュボード）にのみ載る。
    // 出欠ウィジェットの取得は size=10 かつ from/to が約 60 日離れているという固有の signature を持つ。
    let captured: { from: string; to: string } | null = null
    page.on('request', (r) => {
      if (r.method() !== 'GET') return
      const url = new URL(r.url())
      if (!/\/api\/v1\/(teams|organizations)\/[^/]+\/schedules$/.test(url.pathname)) return
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      if (!from || !to || url.searchParams.get('size') !== '10') return
      const spanDays = (Date.parse(to) - Date.parse(from)) / 86_400_000
      if (spanDays > 59 && spanDays < 61 && !captured) captured = { from, to }
    })

    await goto(page, `/teams/${TEAM_SLUG}`)
    // ウィジェットは遅延読み込みのため、リクエストが飛ぶ猶予を与える
    await page.waitForTimeout(8_000)

    test.skip(
      captured === null,
      `出欠ウィジェット（attendance-results）の取得リクエスト（size=10・約60日レンジ）が `
      + `/teams/${TEAM_SLUG} で観測できなかったためスキップ。`
      + 'このウィジェットは ScopeDashboard のウィジェット構成に含まれている場合のみ描画されるため、'
      + 'ユーザーのダッシュボード構成から外れていると経路自体が存在しない'
      + '（無条件スキップではなく、経路不在という具体的な理由による条件付きスキップ）。',
    )

    const { from, to } = captured!
    // (1) ユーザーTZのオフセット付きであること（＝ toISOString() の裸 UTC 壁時計ではない）
    expectHasOffset(from, '出欠ウィジェットの from')
    expectHasOffset(to, '出欠ウィジェットの to')

    // (2) ── 本題 ── JST 壁時計で見て「今日の前後30日」であること。
    //     修正前は UTC 壁時計をそのまま渡していたため、ここが 9 時間ずれていた。
    const todayJst = wallClockIn(Date.now(), JST_TZ).slice(0, 10)
    const expectedFromDay = wallClockIn(Date.now() - 30 * 86_400_000, JST_TZ).slice(0, 10)
    const expectedToDay = wallClockIn(Date.now() + 30 * 86_400_000, JST_TZ).slice(0, 10)
    expect(
      wallClockIn(Date.parse(from), JST_TZ).slice(0, 10),
      `from を JST 壁時計で見た日付が「今日(${todayJst})の30日前」であること（送信値: ${from}）`,
    ).toBe(expectedFromDay)
    expect(
      wallClockIn(Date.parse(to), JST_TZ).slice(0, 10),
      `to を JST 壁時計で見た日付が「今日(${todayJst})の30日後」であること（送信値: ${to}）`,
    ).toBe(expectedToDay)
  })
})

// ===========================================================================
// QP2508-05: 保護者ビューの子の予定レンジ
//   （`GET /api/v1/me/guardianship/children/{childUserId}/schedules?from&to`）
//
//   修正前は `dayjs()` ＝ **ブラウザTZ**基準のナイーブ文字列だった。
//   ユーザーTZ（users.timezone）とブラウザTZが食い違う環境で範囲が壊れる。
// ===========================================================================
test.describe('QP2508-05 保護者ビューの子の予定レンジがユーザーTZ基準になる', () => {
  test.setTimeout(150_000)

  let ctx: APIRequestContext
  let token = ''
  let childUserId: number | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    token = await loginToken(ctx, E2E_USER.email, E2E_USER.password)
    const res = await api(ctx, token, 'GET', '/api/v1/me/guardianship/switchable-children')
    if (!res.ok()) return
    const children = ((await res.json()).data ?? []) as Array<{ childUserId?: number; userId?: number; id?: number }>
    const first = children[0]
    if (first) childUserId = first.childUserId ?? first.userId ?? first.id ?? null
  })

  test.afterAll(async () => {
    await ctx.dispose()
  })

  test('QP2508-05: 子の予定レンジがユーザーTZ基準のオフセット付きで送られる', async ({ page }) => {
    test.skip(
      childUserId === null,
      'e2e-user に保護者連携された子ユーザーが 1 人も存在せず'
      + '（GET /api/v1/me/guardianship/switchable-children が空）、'
      + '保護者ビュー /me/guardianship/children/{childUserId} 自体へ到達できないためスキップ。'
      + '保護者連携は招待→同意の二者フローで、片側の資格情報しか持たない本 spec では作成できない'
      + '（無条件スキップではなく、前提データ不在という具体的な理由による条件付きスキップ）。',
    )

    await setupApiBridge(page)
    // ブラウザTZ（config 既定の Asia/Tokyo）とユーザーTZ（LA）を**わざと食い違わせる**。
    // 修正前は dayjs() がブラウザTZを見ていたため、この食い違いでレンジが壊れていた。
    await loginInBrowser(page, LA_TZ)

    const [req] = await Promise.all([
      page.waitForRequest(
        (r) => r.method() === 'GET'
          && /\/guardianship\/children\/\d+\/schedules$/.test(new URL(r.url()).pathname),
        { timeout: 30_000 },
      ),
      goto(page, `/me/guardianship/children/${childUserId}`),
    ])

    const q = new URL(req.url()).searchParams
    const from = q.get('from') ?? ''
    const to = q.get('to') ?? ''

    // 「LA の今日の 00:00:00」〜「LA の 30 日後の 23:59:59」であること。
    // ブラウザTZ(JST)基準なら日付が 1 日ずれるため、ここで確実に判別できる。
    const todayLa = wallClockIn(Date.now(), LA_TZ).slice(0, 10)
    const day30La = wallClockIn(instantOf(todayLa, '12:00:00', LA_TZ) + 30 * 86_400_000, LA_TZ).slice(0, 10)
    expectWallClock(from, `${todayLa} 00:00:00`, LA_TZ, '子の予定の from')
    expectWallClock(to, `${day30La} 23:59:59`, LA_TZ, '子の予定の to')
  })
})
