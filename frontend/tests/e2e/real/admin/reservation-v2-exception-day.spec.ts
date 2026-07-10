/**
 * 予約v2 W2-1-FE ⑤例外日カレンダー（第二隊 #2221）実機一気通貫E2E
 *
 * バックエンド http://localhost:8080（本陣・稼働中/停止厳禁）
 * フロントエンド http://localhost:3001（検証用 dev server。BASE_URL 環境変数で上書き可）
 *
 * 写経元: reservation-v2-menu-template.spec.ts / reservation-v2-matrix-group.spec.ts
 * （ログイン機構・CORS APIブリッジ・使い捨てチーム作成・予約モジュール有効化）。
 * 単一セッション設計・総当りログイン禁止・page.request統一。
 *
 * 対象コンポーネント: ScheduleExceptionPanel.vue（F03.4.5 §3.3・commit 40431c343 / PR #2221）
 *   管理タブ「予約対象の管理」→ Accordion「⑤例外日カレンダー」内の月表示ミニカレンダー。
 *   日クリック→「この日を休業にする」「臨時営業する」の2択ダイアログ。
 *
 * 受け入れ条件:
 *   AC-FE13: 日クリックで2択ダイアログが出る
 *   AC-FE14: 「休業にする」は impact API(全日・TEAM軸)を呼び、予約ありなら警告カード＋登録disabled
 *   AC-FE14b: impact 0件なら登録可→実DBに全日ブロックが書き込まれる
 *   AC-FE15: 「臨時営業する」は曜日Select既定=当日曜日、実行でgenerate-single-day→実DBに単日枠生成
 *   AC-FE16: 同日に全日休業があるとき「臨時営業する」はblocked_conflict警告＋実行disabled
 *   境界: 明日以降〜90日以内のみ実行可（当日/過去/91日超はdisableまたは400を正直に表示）
 *
 * PrimeVue DatePicker inline の DOM 構造（node_modules/primevue/datepicker/style/index.mjs で裏取り済み）:
 *   .p-datepicker-panel（root）/ .p-datepicker-next-button（翌月）/
 *   td.p-datepicker-day-cell[aria-label="{day}"] > span.p-datepicker-day（日クリック対象）
 *   showOtherMonths 既定 false のため、表示月内の日番号は一意（他月グレー日は描画されない）。
 *   月送りは「表示月=常に実行時の今月」を前提に、目的日までの月差分だけ翌月ボタンを叩く
 *   （テキスト読み取り不要・ロケール非依存で頑健）。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Locator,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const FE_ORIGIN = process.env.BASE_URL ?? 'http://localhost:3001'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

interface MeProfile {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

async function login(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${BE}/api/v1/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

/** 使い捨てチームを新規作成する（ASCIIプレフィックス必須: slug数字化バグ回避）。 */
async function createThrowawayTeam(
  ctx: APIRequestContext,
  adminToken: string,
): Promise<{ slug: string }> {
  const res = await ctx.post(`${BE}/api/v1/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvExc_例外日検証_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  const data = (await res.json()).data as { slug: string }
  return { slug: data.slug }
}

async function enableReservationModule(
  ctx: APIRequestContext,
  adminToken: string,
  slug: string,
): Promise<void> {
  const catalogRes = await ctx.get(`${BE}/api/v1/teams/${slug}/modules/catalog`, {
    headers: authHeaders(adminToken),
  })
  if (!catalogRes.ok()) {
    throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  }
  const catalog = (await catalogRes.json()).data as {
    modules: { moduleId: number; slug: string; isEnabled: boolean }[]
  }
  const reservationModule = catalog.modules.find((m) => m.slug === 'reservation')
  if (!reservationModule) throw new Error('カタログに reservation モジュールが見つからない')
  const toggleRes = await ctx.patch(
    `${BE}/api/v1/teams/${slug}/modules/${reservationModule.moduleId}/toggle`,
    {
      headers: authHeaders(adminToken),
      data: { moduleId: reservationModule.moduleId, enabled: true },
    },
  )
  if (!toggleRes.ok()) {
    throw new Error(`予約モジュール有効化失敗: ${toggleRes.status()} ${await toggleRes.text()}`)
  }
}

/**
 * APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。
 * Originヘッダから都度ACAO算出＋route try/catchでabort（写経元の根治2点を踏襲）。
 */
async function installApiBridge(page: Page, token: string): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const target = `http://127.0.0.1:8080${url.pathname}${url.search}`
    const pageOrigin = req.headers()['origin'] || FE_ORIGIN
    const headers: Record<string, string> = {
      ...req.headers(),
      origin: 'http://localhost:3000',
      referer: 'http://localhost:3000/',
      authorization: `Bearer ${token}`,
    }
    try {
      const relay = await page.request.fetch(target, {
        method: req.method(),
        headers,
        data: req.postData() ?? undefined,
        maxRedirects: 0,
      })
      const respHeaders: Record<string, string> = { ...relay.headers() }
      respHeaders['access-control-allow-origin'] = pageOrigin
      respHeaders['access-control-allow-credentials'] = 'true'
      await route.fulfill({
        status: relay.status(),
        headers: respHeaders,
        body: await relay.body(),
      })
    }
    catch {
      await route.abort().catch(() => {})
    }
  })
}

async function seedBrowserAuth(page: Page, me: MeProfile): Promise<void> {
  const currentUser = {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  }
  const farFuture = Date.now() + 24 * 60 * 60 * 1000
  await page.addInitScript(
    ({ user, expiresAt }) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      localStorage.setItem('tokenExpiresAt', String(expiresAt))
    },
    { user: currentUser, expiresAt: farFuture },
  )
}

// === 日付ユーティリティ（Asia/Tokyo 前提・playwright.config の timezoneId と一致。写経元踏襲） ===
const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
type DayCode = (typeof DAY_CODES)[number]

const NOW = new Date()

interface DateInfo {
  date: Date
  iso: string
  dayCode: DayCode
  dayOfMonth: number
  /** 実行時の「今月」から見た月差分（翌月ボタンのクリック回数として使う）。 */
  monthsAhead: number
}

function dateInfo(daysAhead: number): DateInfo {
  const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() + daysAhead)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const monthsAhead
    = (d.getFullYear() * 12 + d.getMonth()) - (NOW.getFullYear() * 12 + NOW.getMonth())
  return {
    date: d,
    iso: `${y}-${m}-${day}`,
    dayCode: DAY_CODES[d.getDay()]!,
    dayOfMonth: d.getDate(),
    monthsAhead,
  }
}

const test = base.extend<
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type -- test-scoped の追加 fixture は無い
  {},
  { tokens: { admin: string; adminMe: MeProfile } }
>({
  // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
  storageState: async ({}, use) => {
    await use(undefined)
  },
  tokens: [
    // eslint-disable-next-line no-empty-pattern -- Playwright は fixture 第1引数にオブジェクト分割代入を要求する
    async ({}, use) => {
      const ctx = await playwrightRequest.newContext()
      const admin = await login(ctx, ADMIN_EMAIL, ADMIN_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      await ctx.dispose()
      await use({ admin, adminMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(180_000)
// 注意: 各 AC は beforeAll の共有セットアップ以外に相互依存が無い（対象日を +2〜+5 で分離済み）。
// serial モードにすると先行テストの failed が後続を skip させ、他 AC の実走結果が得られなくなるため
// あえて serial にしない（各 AC を独立に実走結果として観測するため）。

test.describe('RSV-V2: 例外日カレンダー（休業/臨時営業・impact警告・衝突ガード・実機一気通貫）', () => {
  let teamSlug = ''
  let lineId = 0

  // AC 検証用の対象日（今日から+2,+3,+5日。互いに重複しない）
  const impactDay = dateInfo(2) // AC-FE14: 予約ありの日
  const closeOnlyDay = dateInfo(3) // AC-FE14b: 予約なしの日（休業登録に成功させる）
  // AC-FE15: 臨時営業。テンプレ保存＝同期自動生成（F03.4.5 §3.1・28日先まで）の対象に
  // 巻き込まれないよう、意図的に 28 日超（90 日以内）の日を選ぶ。28日以内だと beforeAll の
  // テンプレ作成時点で該当曜日の枠が既に自動生成済みになり、単日生成が「冪等スキップ＝0件」に
  // なってしまう（実機E2Eで実際に踏んだ地雷。テスト設計側の問題であり BE バグではない）。
  const specialDay = dateInfo(35)
  const conflictDay = dateInfo(5) // AC-FE16: 全日休業と衝突させる日
  const todayDay = dateInfo(0) // 境界: 当日

  let closeOnlyBlockedId: number | null = null

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    // 営業時間（単日生成は skipBusinessHours=true のため必須ではないが、写経元踏襲で全曜日設定）
    const hours = DAY_CODES.map((code) => ({
      dayOfWeek: code,
      isOpen: true,
      openTime: '09:00:00',
      closeTime: '19:00:00',
    }))
    const hoursRes = await ctx.put(
      `${BE}/api/v1/teams/${teamSlug}/reservation-settings/business-hours`,
      { headers: authHeaders(tokens.admin), data: { hours } },
    )
    if (!hoursRes.ok()) throw new Error(`営業時間PUT失敗: ${hoursRes.status()} ${await hoursRes.text()}`)

    // ライン
    const lineRes = await ctx.post(`${BE}/api/v1/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '例外日検証席' },
    })
    if (!lineRes.ok()) throw new Error(`ライン作成失敗: ${lineRes.status()} ${await lineRes.text()}`)
    lineId = ((await lineRes.json()).data as { id: number }).id

    // AC-FE15 用: specialDay の曜日に active テンプレを用意（既定=実曜日で generate-single-day が成功するように）
    const tplRes = await ctx.post(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slot-templates`,
      {
        headers: authHeaders(tokens.admin),
        data: {
          lineId,
          dayOfWeek: specialDay.dayCode,
          startTime: '10:00:00',
          endTime: '11:00:00',
          capacity: 1,
        },
      },
    )
    if (!tplRes.ok()) throw new Error(`テンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`)

    // AC-FE14 用: impactDay に手動枠＋予約を作り「有効な予約」を用意する
    const slotRes = await ctx.post(`${BE}/api/v1/teams/${teamSlug}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: impactDay.iso, startTime: '10:00:00', endTime: '10:30:00', lineId },
    })
    if (!slotRes.ok()) throw new Error(`impact用枠作成失敗: ${slotRes.status()} ${await slotRes.text()}`)
    const slotId = ((await slotRes.json()).data as { id: number }).id

    const reserveRes = await ctx.post(`${BE}/api/v1/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: slotId, lineId, userNote: 'E2E例外日検証(impact)' },
    })
    if (!reserveRes.ok()) throw new Error(`impact用予約作成失敗: ${reserveRes.status()} ${await reserveRes.text()}`)

    console.log(
      `[SETUP] teamSlug=${teamSlug} lineId=${lineId} impactDay=${impactDay.iso} closeOnlyDay=${closeOnlyDay.iso} `
      + `specialDay=${specialDay.iso}(${specialDay.dayCode}) conflictDay=${conflictDay.iso} todayDay=${todayDay.iso}`,
    )
    await ctx.dispose()
  })

  test.afterAll(async ({ tokens }) => {
    // 後始末: AC-FE14b で作成した全日休業を削除する（AC-FE16分はテスト内で削除済み）
    if (closeOnlyBlockedId != null) {
      const ctx = await playwrightRequest.newContext()
      const res = await ctx.delete(
        `${BE}/api/v1/teams/${teamSlug}/reservation-settings/blocked-times/${closeOnlyBlockedId}`,
        { headers: authHeaders(tokens.admin) },
      )
      console.log(`[CLEANUP] blocked-time id=${closeOnlyBlockedId} 削除=${res.ok() ? '成功' : `失敗(${res.status()})`}`)
      await ctx.dispose()
    }
  })

  async function gotoReservations(page: Page, tokens: { admin: string; adminMe: MeProfile }) {
    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)
    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
  }

  /** 管理タブ→⑤例外日カレンダーの Accordion を開き、インラインカレンダー(root)を返す。 */
  async function openExceptionDayCalendar(page: Page): Promise<Locator> {
    const manageTab = page.getByRole('tab', { name: '予約対象の管理' })
    await expect(manageTab).toBeVisible({ timeout: 20_000 })
    await manageTab.click()

    const header = page.getByRole('button', { name: '例外日カレンダー' })
    await expect(header).toBeVisible({ timeout: 15_000 })
    const calendar = page.locator('.p-datepicker-panel').first()
    const alreadyOpen = await calendar.isVisible().catch(() => false)
    if (!alreadyOpen) await header.click()
    await expect(calendar).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('カレンダーの日付を選ぶと')).toBeVisible({ timeout: 10_000 })
    return calendar
  }

  /**
   * PrimeVue DatePicker inline のカレンダー日セルをクリックする（実ユーザー操作）。
   * 表示月は常にページ読み込み時点の実行時「今月」のため、目的日までの月差分だけ翌月ボタンを押す。
   */
  async function clickCalendarDate(page: Page, calendar: Locator, target: DateInfo): Promise<void> {
    for (let i = 0; i < target.monthsAhead; i++) {
      await calendar.locator('.p-datepicker-next-button').click()
      await page.waitForTimeout(150)
    }
    const cell = calendar.locator(`td.p-datepicker-day-cell[aria-label="${target.dayOfMonth}"] span.p-datepicker-day`)
    await expect(cell, `${target.iso} のセルがクリック可能であること`).toBeVisible({ timeout: 10_000 })
    await cell.click()
  }

  test('AC-FE13: 日をクリックすると「休業にする/臨時営業する」2択ダイアログが出る', async ({ page, tokens }) => {
    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, impactDay)

    const choiceDialog = page.getByRole('dialog')
    await expect(choiceDialog).toBeVisible({ timeout: 10_000 })
    await expect(choiceDialog.getByText(impactDay.iso)).toBeVisible()
    await expect(page.getByTestId('exception-choice-close')).toBeVisible()
    await expect(page.getByTestId('exception-choice-special')).toBeVisible()

    await page.screenshot({ path: 'test-results/rsv-exc-01-choice-dialog.png', fullPage: true })
  })

  /**
   * 【実機E2Eで発見したBEバグ・2026-07-10】GET .../blocked-times/impact および
   * createBlockedTime の 409 ガード（RESERVATION_027）は、共通ヘルパー
   * ReservationBusinessHourService#findActiveOverlappingReservations が「全日」判定のため
   * startTime/endTime を LocalTime.MIN/LocalTime.MAX に展開する実装になっているが、
   * LocalTime.MAX（23:59:59.999999999）を JDBC 経由で TIME 型パラメータへバインドする際に
   * 丸め/繰り上がりが発生し、ReservationRepository#findActiveReservationsOverlappingUnavailability
   * の overlap 条件 `s.startTime < :endTimeExclusive` が全 slot で false になる（affectedCount が
   * 常に 0 になる）ため、全日休業（startTime/endTime 未指定）を選ぶと有効な予約があっても
   * 警告が出ず、409 ガードも発動せずに休業登録が成立してしまう。
   *
   * 実機裏取り（curl）:
   *   - GET impact?date=D&resourceType=TEAM（null/null＝全日）→ affectedCount=0（誤り）
   *   - GET impact?date=D&resourceType=TEAM&startTime=00:00:00&endTime=23:59:59
   *     （明示的な終日範囲）→ affectedCount=1（正しく検出）
   *   - POST blocked-times（全日）→ 有効な予約が残っているのに 201 で成功してしまう
   *     （本来は 409=RESERVATION_027 になるべき）
   *
   * 本テストは受け入れ条件（impact警告＋登録disabled）をそのまま検証する（対処療法で緩めない）。
   * 現状の実装では赤化する＝バグの実機的な固定化として扱う。修正は殿が別途手配。
   */
  test('AC-FE14: 予約が残る日は「休業にする」で impact 警告カード＋登録ボタンdisabledになる', async ({ page, tokens }) => {
    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, impactDay)

    await page.getByTestId('exception-choice-close').click()
    const dialog = page.getByRole('dialog', { name: /この日を休業にする/ })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // impact API 呼び出し結果の反映を待つ（affectedCount>=1 の警告カード）
    await expect(
      page.getByText(/この日には有効な予約が\s*\d+\s*件あります/),
      'impact警告カードが表示されること',
    ).toBeVisible({ timeout: 15_000 })

    const submitBtn = page.getByTestId('exception-close-submit')
    await expect(submitBtn).toBeDisabled()

    await page.screenshot({ path: 'test-results/rsv-exc-02-impact-warning.png', fullPage: true })

    // 「予約一覧で対応」導線→予約一覧タブへ切り替わること
    await page.getByTestId('exception-close-goto-list').first().click()
    await expect(page.getByRole('tab', { name: '予約一覧', selected: true })).toBeVisible({ timeout: 10_000 })
  })

  test('AC-FE14b: 予約が無い日は「休業にする」で登録でき、実DBに全日ブロックが書き込まれる', async ({
    page,
    tokens,
    request,
  }) => {
    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, closeOnlyDay)

    await page.getByTestId('exception-choice-close').click()
    const dialog = page.getByRole('dialog', { name: /この日を休業にする/ })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    const submitBtn = page.getByTestId('exception-close-submit')
    await expect(submitBtn, '予約が無い日は登録ボタンが有効であること').toBeEnabled({ timeout: 15_000 })

    await page.getByTestId('exception-close-reason').fill('E2E例外日検証(休業)')
    await submitBtn.click()

    await expect(page.getByText('予約不可枠を登録しました')).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/rsv-exc-03-close-day-success.png', fullPage: true })

    // 実DB裏取り: 全日・TEAM軸のブロックが closeOnlyDay に作成されていること
    const res = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-settings/blocked-times?from=${closeOnlyDay.iso}&to=${closeOnlyDay.iso}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(res.ok(), `blocked-times一覧取得失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
    const list = (await res.json()).data as Array<{
      id: number
      resource?: { resourceType?: string }
      timeSlot?: { blockedDate?: string; startTime?: string | null; endTime?: string | null }
    }>
    const created = list.find(b =>
      b.resource?.resourceType === 'TEAM' && !b.timeSlot?.startTime && !b.timeSlot?.endTime,
    )
    console.log(`[AC-FE14b] 実DB blocked-times(${closeOnlyDay.iso})=${JSON.stringify(list)}`)
    expect(created, '実DBに全日休業（startTime/endTime=null）が書き込まれていること').toBeTruthy()
    closeOnlyBlockedId = created!.id
  })

  test('AC-FE15: 「臨時営業する」は曜日Select既定=当日曜日で、実行すると実DBに単日枠が生成される', async ({
    page,
    tokens,
    request,
  }) => {
    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, specialDay)

    await page.getByTestId('exception-choice-special').click()
    const dialog = page.getByRole('dialog', { name: /臨時営業する/ })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 曜日Select既定値 = specialDay の実曜日であること（PrimeVue Select の hidden select 経由で値検証）
    const daySelect = page.getByTestId('exception-special-day-select')
    await expect(daySelect).toBeVisible({ timeout: 10_000 })
    await expect(daySelect, `既定の曜日ダイヤが実曜日(${specialDay.dayCode})と一致すること`).toContainText(
      specialDayLabel(specialDay.dayCode),
    )

    const submitBtn = page.getByTestId('exception-special-submit')
    await expect(submitBtn, '衝突なし・日付有効なら実行ボタンが有効であること').toBeEnabled({ timeout: 15_000 })
    await submitBtn.click()

    // トースト通知＋ダイアログ内の成功メッセージの双方に同一文言が出るため .first() で束ねる
    const doneToast = page.getByText(new RegExp(`${specialDay.iso} に\\s*\\d+\\s*件の枠を作成しました`)).first()
    await expect(doneToast, 'special_done トーストが出ること').toBeVisible({ timeout: 15_000 })
    const toastText = (await doneToast.textContent()) ?? ''
    const m = toastText.match(/に\s*(\d+)\s*件の枠を作成しました/)
    const generated = Number(m?.[1] ?? -1)
    console.log(`[AC-FE15] special_done generated=${generated} (raw="${toastText}")`)
    expect(generated, '生成0件は不合格（テンプレ/曜日一致を疑う）').toBeGreaterThan(0)

    const gotoBookBtn = page.getByTestId('exception-special-goto-book')
    await expect(gotoBookBtn, '単日ビュー確認への導線ボタンが出ること').toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-exc-04-special-done.png', fullPage: true })
    await gotoBookBtn.click()

    // 予約するタブ（activeTab=0）へ切り替わること
    await expect(page.getByRole('tab', { name: '予約する', selected: true })).toBeVisible({ timeout: 10_000 })

    // 実DB裏取り: specialDay に枠が生成されていること
    const res = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slots?from=${specialDay.iso}&to=${specialDay.iso}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(res.ok(), `枠一覧取得失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
    const slots = (await res.json()).data as unknown[]
    console.log(`[AC-FE15] 実DB枠数(${specialDay.iso})=${slots.length}`)
    expect(slots.length, '実DBに単日枠が書き込まれていること').toBeGreaterThanOrEqual(1)
  })

  test('AC-FE16: 同日に全日休業があると「臨時営業する」は blocked_conflict 警告＋実行ボタンdisabledになる', async ({
    page,
    tokens,
    request,
  }) => {
    // 事前に conflictDay へ全日休業（TEAM軸）を作成（API・機能Bのショートカット元と同一エンドポイント）
    const blockRes = await request.post(
      `${BE}/api/v1/teams/${teamSlug}/reservation-settings/blocked-times`,
      { headers: authHeaders(tokens.admin), data: { blockedDate: conflictDay.iso, resourceType: 'TEAM' } },
    )
    expect(blockRes.ok(), `衝突用全日休業作成失敗: ${blockRes.status()} ${await blockRes.text()}`).toBeTruthy()

    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, conflictDay)

    await page.getByTestId('exception-choice-special').click()
    const dialog = page.getByRole('dialog', { name: /臨時営業する/ })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    await expect(
      page.getByText('この日は休業に設定されています'),
      'blocked_conflict 警告が出ること',
    ).toBeVisible({ timeout: 15_000 })
    const submitBtn = page.getByTestId('exception-special-submit')
    await expect(submitBtn, '全日休業と衝突する日は実行ボタンがdisabledであること').toBeDisabled()
    await page.screenshot({ path: 'test-results/rsv-exc-05-blocked-conflict.png', fullPage: true })

    // その場で衝突を解除→実行ボタンが有効化されることまで確認（native confirm を自動承認）
    page.once('dialog', d => d.accept())
    await page.getByTestId('exception-conflict-delete').click()
    await expect(page.getByText('予約不可枠を削除しました')).toBeVisible({ timeout: 10_000 })
    await expect(submitBtn, '衝突解除後は実行ボタンが有効化されること').toBeEnabled({ timeout: 10_000 })
  })

  test('境界(当日): 今日を選ぶと「臨時営業する」は date_range_hint 警告＋実行ボタンdisabledになる', async ({
    page,
    tokens,
  }) => {
    await gotoReservations(page, tokens)
    const calendar = await openExceptionDayCalendar(page)
    await clickCalendarDate(page, calendar, todayDay)

    await page.getByTestId('exception-choice-special').click()
    const dialog = page.getByRole('dialog', { name: /臨時営業する/ })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    await expect(
      page.getByText('臨時営業は明日から90日先までの日付に設定できます'),
      '当日は date_range_hint が表示されること',
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      page.getByTestId('exception-special-submit'),
      '当日は実行ボタンがdisabledであること（明日以降のみ有効）',
    ).toBeDisabled()

    await page.screenshot({ path: 'test-results/rsv-exc-06-today-boundary.png', fullPage: true })
  })

  /**
   * generate-single-day は generate（旧一括生成）と同一 zone のレートリミットを共有する
   * （ReservationSlotTemplateService §6: 1チーム2回/分・超過429=RESERVATION_044）。
   * 同一チームへ複数回連続で叩くと 429 に化けて境界検証にならない（実機E2Eで実際に踏んだ地雷）ため、
   * 呼び出しごとに使い捨てチームを分けてレート制限バケットを独立させる。
   */
  async function postGenerateSingleDay(
    tokens: { admin: string },
    date: string,
  ): Promise<{ status: number, body: { error?: { code: string, fieldErrors?: Array<{ field: string, message: string }> } } }> {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    await enableReservationModule(ctx, tokens.admin, team.slug)
    const res = await ctx.post(
      `${BE}/api/v1/teams/${team.slug}/reservation-slot-templates/generate-single-day`,
      { headers: authHeaders(tokens.admin), data: { date } },
    )
    const body = await res.json()
    await ctx.dispose()
    return { status: res.status(), body }
  }

  test('境界(過去/91日超/90日ちょうど): generate-single-day API が正しい 400/成功可否を返す', async ({ tokens }) => {
    // UI（min-date=today）は過去日を選択できないため、BEガードは API 直叩きで裏取りする。
    const pastDay = dateInfo(-1)
    const past = await postGenerateSingleDay(tokens, pastDay.iso)
    expect(past.status, '過去日は400であること').toBe(400)
    console.log(`[境界-過去] ${pastDay.iso} => ${past.status} ${past.body.error?.code}`)
    expect(past.body.error?.code, '過去日はRESERVATION_023(PAST_DATE_SLOT)であること').toBe('RESERVATION_023')

    // 当日もAPIレベルでは同じ理由で拒否される（当日枠は手動作成の領分）
    const today = await postGenerateSingleDay(tokens, todayDay.iso)
    expect(today.status, '当日は400であること').toBe(400)
    console.log(`[境界-当日API] ${todayDay.iso} => ${today.status} ${today.body.error?.code}`)
    expect(today.body.error?.code, '当日もRESERVATION_023であること').toBe('RESERVATION_023')

    // 91日超は汎用400(COMMON_001)+fieldErrors[field=date]
    const farDay = dateInfo(91)
    const far = await postGenerateSingleDay(tokens, farDay.iso)
    expect(far.status, '91日超は400であること').toBe(400)
    console.log(`[境界-91日超] ${farDay.iso} => ${far.status} ${JSON.stringify(far.body.error)}`)
    expect(far.body.error?.code, '91日超はCOMMON_001であること').toBe('COMMON_001')
    expect(
      far.body.error?.fieldErrors?.some(fe => fe.field === 'date'),
      '91日超のfieldErrorsはdateフィールドを指すこと',
    ).toBe(true)

    // 90日ちょうどは「日付の90日上限」には抵触しない（テンプレ未整備なら templates フィールドの400になり得る＝date超過とは別種のエラーであることを確認）
    const boundaryDay = dateInfo(90)
    const boundary = await postGenerateSingleDay(tokens, boundaryDay.iso)
    console.log(`[境界-90日ちょうど] ${boundaryDay.iso} => ${boundary.status} ${JSON.stringify(boundary.body.error)}`)
    if (boundary.status === 400) {
      expect(
        boundary.body.error?.fieldErrors?.some(fe => fe.field === 'date'),
        '90日ちょうどは日付超過エラーではないこと（テンプレ未整備等の別理由であるべき）',
      ).toBeFalsy()
    }
    else {
      expect(boundary.status, `90日ちょうどは成功するはず: ${JSON.stringify(boundary.body)}`).toBe(200)
    }
  })
})

/** 曜日コード→日本語ラベル（useReservationDayOptions のロケールキー実体・ja固定値）。 */
function specialDayLabel(code: DayCode): string {
  const map: Record<DayCode, string> = {
    SUN: '日',
    MON: '月',
    TUE: '火',
    WED: '水',
    THU: '木',
    FRI: '金',
    SAT: '土',
  }
  return map[code]
}
