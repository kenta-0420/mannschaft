/**
 * 予約v2第一弾（メニュー管理＋週間テンプレート一括枠生成）実機一気通貫E2E
 *
 * バックエンド http://localhost:8080（本陣・稼働中/停止厳禁）
 * フロントエンド http://localhost:3002（検証用 dev server。BASE_URL 環境変数で上書き可）
 *
 * 写経元: reservation-empty-onboarding-real.spec.ts（ログイン機構・CORS APIブリッジ・
 * 使い捨てチーム作成・予約モジュール有効化）。単一セッション設計・総当りログイン禁止。
 *
 * 【F03.4.5 W2-1 追従（例外日カレンダー第二隊）】
 *   「今すぐ枠を作成」ボタン・weeks Select は撤去され、テンプレ保存＝即同期自動生成に統合された
 *   （SlotTemplateSaveResponse{ template, generation }・§3.1）。本 spec は旧UI（generate-now
 *   testid・「テンプレートを保存しました。変更を反映するには『今すぐ枠を作成』を押してください」
 *   トースト）を参照しており新設計で実走すると赤化するため、保存時自動生成トースト
 *   （reservation.template.auto_generated=「保存し、{days}日先までの枠を{generated}件作成しました」）
 *   に追従させた。冪等性検証は「今すぐ枠を作成」の再クリックが無くなったため、テンプレ編集
 *   （PATCH・時刻不変）による再保存で generated=0 を確認する形に置き換えた。
 *
 * シナリオ:
 *   0. 営業時間設定（UIなし→API PUT。全曜日 10:00-18:00。テンプレ生成の前提）
 *   1. ライン作成（UI・席A）
 *   2. メニュー作成（UI・カット 60分）→ 一覧表示・提供可否既定（全ライン）
 *   3. テンプレ作成（UI・席A・明日の曜日・10:00-12:00・capacity1）→ 保存時に同期自動生成
 *      （4セル×4回 = 16枠 期待）
 *   4. 「予約する」タブ → 明日に 10:00/10:30/11:00/11:30 の4枠表示
 *   5. 予約成立（ダイアログ→確定→一覧反映＋API裏取り=実DB書込）
 *   6. 冪等: テンプレ編集（時刻不変のPATCH）で再保存 → 0枠を作成（16枠は作成済みのため生成対象なし）
 *   7. ライン削除新仕様（BE §5.5: テンプレ停止＋未来枠purge）を使い捨てラインBで確認
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = 'http://localhost:8080'
const FE_ORIGIN = process.env.BASE_URL ?? 'http://localhost:3002'
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
    data: { name: `RsvTpl_予約v2検証_${Date.now()}` },
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

// === 日付ユーティリティ（Asia/Tokyo 前提・playwright.config の timezoneId と一致） ===
const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
const WEEKDAY_JA = ['日', '月', '火', '水', '木', '金', '土'] as const

/** マトリックスの行ラベル "YYYY/MM/DD (ddd)"（SlotMatrixPicker の dayjs ja ロケール表記と一致）。 */
function matrixRowDateLabel(iso: string): string {
  return `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[new Date(`${iso}T00:00:00Z`).getUTCDay()]})`
}

/** 正規表現メタ文字を含む行ラベルを安全に埋め込むためのエスケープ。 */
function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * マトリックスは月曜起点の週表示のため、対象日を含む週まで週ナビを進める
 * （明日が日曜→月曜またぎで翌週になるケースを取りこぼさない）。
 * 写経元: reservation-v2-d-group.spec.ts の goToWeekContaining。
 */
async function openMatrixWeekContaining(page: Page, targetIso: string): Promise<void> {
  const weekBtn = page.getByRole('button', { name: /^週 /, exact: false })
  await expect(weekBtn).toBeVisible({ timeout: 20_000 })
  const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
  for (let i = 0; i < 8; i++) {
    const text = (await weekBtn.textContent()) ?? ''
    const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
    if (!m) throw new Error(`週ラベル取得失敗: "${text}"`)
    if (targetIso >= `${m[1]}-${m[2]}-${m[3]}` && targetIso <= `${m[4]}-${m[5]}-${m[6]}`) return
    await nextWeekBtn.click()
    await page.waitForTimeout(400)
  }
  throw new Error(`週範囲内に ${targetIso} が見つからない`)
}

function tomorrowInfo(): { iso: string; slash: string; dayCode: (typeof DAY_CODES)[number] } {
  const d = new Date(Date.now() + 24 * 60 * 60 * 1000)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return {
    iso: `${y}-${m}-${day}`,
    slash: `${y}/${m}/${day}`,
    dayCode: DAY_CODES[d.getDay()]!,
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
test.describe.configure({ mode: 'serial' })

test.describe('RSV-V2: メニュー管理＋週間テンプレート一括枠生成（実機一気通貫）', () => {
  let teamSlug = ''
  const tmr = tomorrowInfo()

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)
    await ctx.dispose()
    console.log(`[SETUP] teamSlug=${teamSlug} tomorrow=${tmr.iso}(${tmr.dayCode})`)
  })

  async function gotoReservations(page: Page, tokens: { admin: string; adminMe: MeProfile }) {
    await installApiBridge(page, tokens.admin)
    await seedBrowserAuth(page, tokens.adminMe)
    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
  }

  async function openManageTab(page: Page) {
    const manageTab = page.getByRole('tab', { name: '予約対象の管理' })
    await expect(manageTab).toBeVisible({ timeout: 20_000 })
    await manageTab.click()
    // 【F03.4.5 §3.2 タブ6段再編・第一隊 c043bd9e8 追従】
    // 「予約対象の管理」タブの中身は Accordion 化され、初期は全閉（ADHD配慮）。
    // タブ切替直後に見えるのは各セクションのヘッダーボタン（営業時間/予約対象の管理(n)/
    // メニュー管理(n)/週間テンプレート(n)/例外日カレンダー/詳細設定）であり、
    // 「予約対象を追加」等の中身ボタンはセクションを展開するまで現れない。
    await expect(
      page.getByRole('button', { name: /^予約対象の管理(\s*\(\d+\))?$/ }),
      '予約対象の管理セクションのヘッダーが表示されること',
    ).toBeVisible({ timeout: 15_000 })
  }

  /**
   * 管理タブ内 Accordion セクションを展開する（初期は全閉のため必須）。
   * ヘッダー文言は section_count で「{label} ({n})」形式（例外日カレンダー/営業時間は件数無し）。
   */
  async function openAccordionSection(page: Page, labelPrefix: string): Promise<void> {
    const header = page.getByRole('button', {
      name: new RegExp(`^${labelPrefix}(\\s*\\(\\d+\\))?$`),
    })
    await expect(header, `${labelPrefix} セクションのヘッダーが表示されること`).toBeVisible({
      timeout: 15_000,
    })
    if ((await header.getAttribute('aria-expanded')) !== 'true') {
      await header.click()
    }
  }

  test('STEP-1: 営業時間を全曜日 10:00-18:00 に設定できる（UIなし→API PUT）', async ({
    request,
    tokens,
  }) => {
    // UIは存在しない（ReservationUnavailabilityManager は参照のみ）ため API で設定する。
    // BE DTO: BusinessHoursUpdateRequest { hours: BusinessHourEntry[] }
    //   BusinessHourEntry { dayOfWeek: 3文字, isOpen, openTime, closeTime }
    const hours = DAY_CODES.map((code) => ({
      dayOfWeek: code,
      isOpen: true,
      openTime: '10:00:00',
      closeTime: '18:00:00',
    }))
    const res = await request.put(
      `${BE}/api/v1/teams/${teamSlug}/reservation-settings/business-hours`,
      { headers: authHeaders(tokens.admin), data: { hours } },
    )
    expect(res.ok(), `営業時間PUT失敗: ${res.status()} ${await res.text()}`).toBeTruthy()

    const getRes = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-settings/business-hours`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(getRes.ok()).toBeTruthy()
    // BusinessHourResponse は businessStatus にネストされる（実機レスポンスで確認）
    const saved = (await getRes.json()).data as Array<{
      businessStatus: {
        dayOfWeek: string
        isOpen: boolean
        openTime: string | null
        closeTime: string | null
      }
    }>
    console.log(`[STEP-1] business-hours=${JSON.stringify(saved)}`)
    const open = saved.filter(
      (h) => h.businessStatus?.isOpen && h.businessStatus?.openTime?.startsWith('10:00'),
    )
    expect(open.length, '全7曜日が 10:00 開店で保存されていること').toBe(7)
  })

  test('STEP-2: ライン「席A」をUIから作成できる', async ({ page, tokens }) => {
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, '予約対象の管理')

    await page.getByRole('button', { name: '予約対象を追加' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await dialog.getByRole('textbox').first().fill('席A')
    await dialog.getByRole('button', { name: '保存' }).click()

    await expect(
      page.getByText('予約対象を作成しました'),
      '作成成功トーストが出ること',
    ).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('席A').first(), '一覧に「席A」が出ること').toBeVisible({
      timeout: 10_000,
    })

    await page.screenshot({ path: 'test-results/rsv-v2-02-line-created.png', fullPage: true })
  })

  test('STEP-3: メニュー「カット・60分」をUIから作成できる（提供可否既定=全ライン）', async ({
    page,
    tokens,
  }) => {
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, 'メニュー管理')

    await page.getByTestId('menu-add').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    await page.getByTestId('menu-name').fill('カット')
    // 所要時間 Select（ダイアログ内の最初の combobox）→ 60分
    await dialog.getByRole('combobox').first().click()
    await page.getByRole('option', { name: '60分', exact: true }).click()

    await page.screenshot({ path: 'test-results/rsv-v2-03a-menu-dialog.png', fullPage: true })
    await page.getByTestId('menu-save').click()

    await expect(
      page.getByText('メニューを作成しました'),
      '作成成功トーストが出ること',
    ).toBeVisible({ timeout: 10_000 })

    // 一覧: カット / 60分 / 必要枠数（30分×2）/ 提供可否既定=全ての予約対象で提供
    // （行の <p> は「カット 60分 …」を包含するため exact 一致は使わない）
    await expect(page.getByText('カット').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('60分', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('必要枠数（30分×2）')).toBeVisible()
    await expect(
      page.getByText('全ての予約対象で提供').first(),
      '提供可否の既定（lineIds空=全ライン提供）が表示されること',
    ).toBeVisible()

    await page.screenshot({ path: 'test-results/rsv-v2-03b-menu-list.png', fullPage: true })
  })

  test('STEP-4:【核心】週間テンプレート（席A・明日の曜日・10:00-12:00・定員1）をUIから作成すると保存時に16枠が同期自動生成される', async ({
    page,
    tokens,
  }) => {
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, '週間テンプレート')

    await page.getByTestId('template-add').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 対象ライン: 席A（combobox[0]=ライン, [1]=開始時刻, [2]=終了時刻）
    await dialog.getByRole('combobox').nth(0).click()
    await page.getByRole('option', { name: '席A', exact: true }).click()

    // 曜日: 明日の曜日（value は3文字コード。data-day 属性で特定）
    await dialog.locator(`button[data-day="${tmr.dayCode}"]`).click()

    // 時間帯 10:00 - 12:00
    // PrimeVue Select は閉じたオーバーレイが DOM に残留するため、最後（=直近で開いた）を選ぶ
    await dialog.getByRole('combobox').nth(1).click()
    await page.getByRole('option', { name: '10:00', exact: true }).last().click()
    await dialog.getByRole('combobox').nth(2).click()
    await page.getByRole('option', { name: '12:00', exact: true }).last().click()

    // 定員は既定 1 のまま
    await page.screenshot({ path: 'test-results/rsv-v2-04a-template-dialog.png', fullPage: true })
    await page.getByTestId('template-save').click()

    // テンプレ保存＝同期自動生成（F03.4.5 §3.1）。「テンプレートを作成しました」の単独トーストは
    // 廃止され、生成結果を同梱した auto_generated トースト1本に統合されている。
    const resultToast = page.getByText(/保存し、28日先までの枠を\d+件作成しました/)
    await expect(resultToast, '保存時自動生成の結果トーストが出ること').toBeVisible({ timeout: 20_000 })
    const text = (await resultToast.textContent()) ?? ''
    const m = text.match(/保存し、28日先までの枠を(\d+)件作成しました/)
    const generated = Number(m?.[1] ?? -1)
    console.log(`[STEP-4] 保存時自動生成結果: generated=${generated} (raw="${text}")`)
    expect(generated, '生成0件は不合格（曜日コード/営業時間/日付範囲を疑う）').toBeGreaterThan(0)
    expect(generated, '4セル×該当曜日4回=16枠').toBe(16)

    await expect(page.getByText('10:00 - 12:00')).toBeVisible()
    await expect(
      page.getByText('変更は保存時に自動反映されます（追加分のみ）'),
      'regenerate_guide（自動反映の補足ガイド・改訂値）が表示されること',
    ).toBeVisible({ timeout: 10_000 })

    await page.screenshot({ path: 'test-results/rsv-v2-04b-template-created.png', fullPage: true })
  })

  test('STEP-5: 「予約する」タブの明日に 10:00/10:30/11:00/11:30 の4枠が表示される', async ({
    page,
    tokens,
  }) => {
    await gotoReservations(page, tokens)

    await page.getByRole('tab', { name: '予約する' }).click()
    // 【旧表示撤去 2026-08-04 追従】「予約する」タブはマトリックス表示（SlotMatrixPicker）一本になり、
    // 旧リスト表示（SlotPicker）の DatePicker(.p-datepicker-input)は存在しない。
    // 検証内容（明日に 10:00/10:30/11:00/11:30 の4枠が空きで出ること）はマトリックスのセルで確認する。
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
    await openMatrixWeekContaining(page, tmr.iso)

    const dateLabel = matrixRowDateLabel(tmr.iso)
    for (const t of ['10:00', '10:30', '11:00', '11:30']) {
      await expect(
        page.getByRole('button', { name: `${dateLabel} ${t} 席A 空き`, exact: true }),
        `${t} 開始のセルが「空き」で表示されること`,
      ).toBeVisible({ timeout: 15_000 })
    }
    // 明日の行（席A）の「空き」セル総数がちょうど4であること（枠は4件しか作っていない）
    const availableCount = await page
      .getByRole('button', { name: new RegExp(`^${escapeRegExp(dateLabel)} \\d{2}:\\d{2} 席A 空き$`) })
      .count()
    console.log(`[STEP-5] 明日の空きセル数=${availableCount}`)
    expect(availableCount, '明日の枠は4件のはず').toBe(4)

    await page.screenshot({ path: 'test-results/rsv-v2-05-slots-visible.png', fullPage: true })
  })

  test('STEP-6: 枠を選んで予約が成立し、一覧と実DBに反映される', async ({
    page,
    tokens,
    request,
  }) => {
    await gotoReservations(page, tokens)

    await page.getByRole('tab', { name: '予約する' }).click()
    // 【旧表示撤去 2026-08-04 追従】マトリックスの30分セルは GroupBookingDialog（メニュー選択→
    // プレビュー→確定）へルーティングされる。旧リスト表示の ReservationForm 経路は撤去済み。
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
    await openMatrixWeekContaining(page, tmr.iso)

    const dateLabel = matrixRowDateLabel(tmr.iso)
    const cell1000 = page.getByRole('button', { name: `${dateLabel} 10:00 席A 空き`, exact: true })
    await expect(cell1000).toBeVisible({ timeout: 15_000 })
    await cell1000.click()

    const menuDialog = page.getByRole('dialog', { name: 'メニューを選ぶ' })
    await expect(menuDialog, 'メニュー選択ダイアログが開くこと').toBeVisible({ timeout: 10_000 })
    // 本 STEP は「1枠の予約が成立して一覧と実DBに反映される」ことの検証のため、
    // メニュー無し（30分1枠）を選んで最短経路でプレビューへ進む。
    await menuDialog.getByTestId('group-no-menu').click()

    const previewDialog = page.getByRole('dialog', { name: 'この内容で予約します' })
    await expect(previewDialog, '予約プレビューが開くこと').toBeVisible({ timeout: 10_000 })
    await expect(previewDialog.getByText('席A')).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2-06a-reserve-dialog.png', fullPage: true })

    await previewDialog.getByTestId('group-confirm').click()
    await expect(
      page.getByText('予約が完了しました'),
      '予約成立トーストが出ること',
    ).toBeVisible({ timeout: 15_000 })

    // 予約一覧タブへ → 予約が出ること
    // 【発見バグ・回避】ReservationForm の emit('reserved') を TeamReservationsPanel が
    // 結線しておらず、ReservationList は mount 時ロードのみのため、予約直後の同一ページでは
    // 一覧に反映されない（枠の空き表示も残る）。ユーザー実操作の「再読込後に確認」で検証する。
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.getByRole('tab', { name: '予約一覧' }).click()
    await expect(
      page.getByText('席A').filter({ visible: true }).first(),
      '予約一覧に席Aの予約が表示されること（ページ再読込後）',
    ).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/rsv-v2-06b-reservation-list.png', fullPage: true })

    // 実DB裏取り: 管理者APIで予約一覧を取得し、明日の予約が1件存在すること
    const res = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservations?from=${tmr.iso}&to=${tmr.iso}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(res.ok(), `予約一覧API失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
    const body = (await res.json()).data as unknown
    const list = Array.isArray(body)
      ? body
      : ((body as { content?: unknown[] })?.content ?? [])
    console.log(`[STEP-6] 実DB予約件数(明日)=${list.length}`)
    expect(list.length, '実DBに予約が書き込まれていること').toBeGreaterThanOrEqual(1)
  })

  test('STEP-7: 冪等性 — テンプレ編集（時刻不変のPATCH）で再保存すると生成0件になる', async ({ page, tokens }) => {
    // 「今すぐ枠を作成」ボタンは撤去済みのため、冪等性は「既存テンプレを時刻不変のまま編集保存する
    // （PATCH → 同期自動生成が走るが対象セルは全て既存＝生成0件）」で検証する
    // （F03.4.5 §3.1・W2-1追従）。
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, '週間テンプレート')

    // 【F03.4.5 §3.2 タブ6段再編・第一隊 c043bd9e8 追従】
    // WeeklyScheduleManager の行表示は「{曜日} {開始}-{終了} {ライン名}」の1段落に統合され、
    // 単独で「10:00 - 12:00」だけの要素にはならない（exact一致は不合格になる）。
    const templateRow = page
      .locator('div.flex.items-center', { has: page.getByText('10:00 - 12:00') })
      .first()
    await expect(templateRow, '作成済みテンプレ行が表示されること').toBeVisible({ timeout: 15_000 })
    await templateRow.locator('button:has(.pi-pencil)').click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    // 時刻・曜日は変更せず保存のみ行う（PATCH は既存 dayOfWeek/startTime/endTime を維持）
    await page.getByTestId('template-save').click()

    const resultToast = page.getByText(/保存し、28日先までの枠を\d+件作成しました/)
    await expect(resultToast, '再保存時の同期自動生成トーストが出ること').toBeVisible({ timeout: 20_000 })
    const text = (await resultToast.textContent()) ?? ''
    const m = text.match(/保存し、28日先までの枠を(\d+)件作成しました/)
    const generated = Number(m?.[1] ?? -1)
    console.log(`[STEP-7] 再保存結果: generated=${generated} (raw="${text}")`)

    await page.screenshot({ path: 'test-results/rsv-v2-07-idempotent-toast.png', fullPage: true })

    expect(generated, '冪等: 時刻不変の再保存は生成0件（対象セルは全て既存のため）').toBe(0)
  })

  test('STEP-8: ライン削除新仕様 — テンプレ停止＋予約なし未来枠purge（使い捨てラインB）', async ({
    page,
    tokens,
    request,
  }) => {
    // 予約なしの使い捨てラインB＋テンプレ＋手動未来枠を API で用意し、UI から削除する。
    const lineRes = await request.post(`${BE}/api/v1/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '席B' },
    })
    expect(lineRes.ok(), `席B作成失敗: ${lineRes.status()} ${await lineRes.text()}`).toBeTruthy()
    const lineB = (await lineRes.json()).data as { id: number }

    const tplRes = await request.post(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slot-templates`,
      {
        headers: authHeaders(tokens.admin),
        data: {
          lineId: lineB.id,
          dayOfWeek: tmr.dayCode,
          startTime: '14:00:00',
          endTime: '15:00:00',
          capacity: 1,
        },
      },
    )
    expect(tplRes.ok(), `席Bテンプレ作成失敗: ${tplRes.status()} ${await tplRes.text()}`).toBeTruthy()
    // 【根治】F03.4.5 §3.1 のテンプレ保存＝同期自動生成統合により、レスポンスは
    // SlotTemplateSaveResponse{ template, generation } にネストされた（旧: data.id 直下）。
    // 直下参照のままだと tplB.id が undefined になり、実機ログで実際に
    // "tplId=undefined" として検出された（以降の裏取りに使う ID が取れず不正確になる）。
    const tplB = ((await tplRes.json()).data as { template: { id: string } }).template

    // 予約なし未来枠（手動作成・席B紐付け）
    const slotRes = await request.post(`${BE}/api/v1/teams/${teamSlug}/reservation-slots`, {
      headers: authHeaders(tokens.admin),
      data: { slotDate: tmr.iso, startTime: '14:00:00', endTime: '14:30:00', lineId: lineB.id },
    })
    expect(slotRes.ok(), `席B枠作成失敗: ${slotRes.status()} ${await slotRes.text()}`).toBeTruthy()
    const slotB = (await slotRes.json()).data as { id: number }
    console.log(`[STEP-8] 席B lineId=${lineB.id} tplId=${tplB.id} slotId=${slotB.id}`)

    // UI からライン削除（新仕様: LineManager は window.confirm ではなく PrimeVue ConfirmDialog を使う。
    // 新仕様の説明（テンプレ停止＋未来枠purgeのガイド文言）が確認ダイアログに含まれることも確認する）
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, '予約対象の管理')

    // 【根治】'div.flex.items-center' だけだと SlotMatrixPicker のスティッキー列
    // （予約するタブ側・非表示だが keep-alive で DOM 残留）にも一致し33要素中の隠れた
    // 誤爬取（hidden）を先頭で拾ってしまう。LineManager.vue の実クラス
    // （flex items-center gap-3 rounded-lg）まで指定して一意化する。
    const lineRow = page
      .locator('div.flex.items-center.gap-3.rounded-lg', { has: page.getByText('席B', { exact: true }) })
      .first()
    await expect(lineRow).toBeVisible({ timeout: 15_000 })
    await lineRow.locator('button:has(.pi-trash)').click()

    const confirmDialog = page.getByRole('alertdialog')
    await expect(confirmDialog, '削除確認ダイアログが出ること').toBeVisible({ timeout: 10_000 })
    await expect(
      confirmDialog.getByText('枠テンプレートは停止し'),
      '新仕様（テンプレ停止＋未来枠purge）のガイド文言が表示されること',
    ).toBeVisible()
    await confirmDialog.getByRole('button', { name: '削除する' }).click()

    await expect(
      page.getByText('予約対象を削除しました'),
      '削除成功トーストが出ること',
    ).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/rsv-v2-08-line-deleted.png', fullPage: true })

    // 裏取り1: テンプレが is_active=false 化されている（テンプレ停止）
    const tplList = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slot-templates`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(tplList.ok()).toBeTruthy()
    const templates = ((await tplList.json()).data as {
      templates?: Array<{ id: string; isActive?: boolean; lineId?: number | null }>
    }).templates ?? []
    const tplBAfter = templates.find((t) => t.id === tplB.id)
    console.log(`[STEP-8] 削除後テンプレB=${JSON.stringify(tplBAfter)}`)
    expect(tplBAfter?.isActive, 'ライン削除でテンプレが停止（isActive=false）されること').toBe(false)

    // 裏取り2: 予約なし未来枠が purge（論理削除）され、一覧から消えている
    const slotList = await request.get(
      `${BE}/api/v1/teams/${teamSlug}/reservation-slots?from=${tmr.iso}&to=${tmr.iso}`,
      { headers: authHeaders(tokens.admin) },
    )
    expect(slotList.ok()).toBeTruthy()
    const slots = (await slotList.json()).data as Array<{ id?: number }>
    const slotBAfter = slots.find((s) => s.id === slotB.id)
    console.log(`[STEP-8] 削除後 席B枠の残存=${slotBAfter ? 'あり(NG)' : 'なし(purge済)'} 全枠数=${slots.length}`)
    expect(slotBAfter, '予約なし未来枠がpurgeされていること').toBeUndefined()
  })
})
