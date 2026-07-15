/**
 * 予約v2第二弾（マトリックスUI＋メニュー連続枠＋グループ予約）実機一気通貫E2E
 *
 * バックエンド http://localhost:8080（本陣・稼働中/停止厳禁）
 * フロントエンド http://localhost:3001（検証用 dev server。BASE_URL 環境変数で上書き可）
 *
 * 写経元: reservation-v2-menu-template.spec.ts（ログイン機構・CORS APIブリッジ・
 * 使い捨てチーム作成・予約モジュール有効化・営業時間設定・ライン/メニュー/テンプレ作成の
 * セットアップ）。単一セッション設計・総当りログイン禁止。
 *
 * 【F03.4.5 W2-1 追従（例外日カレンダー第二隊）】
 *   「今すぐ枠を作成」ボタンは撤去され、テンプレ保存＝即同期自動生成に統合された（§3.1）。
 *   本 spec の STEP-1 セットアップは旧UI（generate-now testid・「テンプレートを作成しました」
 *   単独トースト）を参照しており新設計で実走すると赤化するため、保存時自動生成トースト
 *   （reservation.template.auto_generated）に追従させた。
 *
 * 対象コンポーネント（PR #2191 / commit 984279dcc）:
 *   - SlotMatrixPicker.vue（マトリックス本体。既定表示 bookDisplayMode='matrix'）
 *   - GroupBookingDialog.vue（セルクリック→メニュー選択→連続枠プレビュー→＋30分延長→確定）
 *   - ReservationList.vue（グループ予約は代表行1件に集約表示・グループキャンセル）
 *
 * シナリオ:
 *   0. セットアップ: 営業時間・ライン「席A」・メニュー「カット・60分」・テンプレ（10:00-13:00・6セル）・生成
 *   1. マトリックス既定表示の確認
 *   2. セル選択→メニュー選択→連続2セルプレビュー（60分メニュー=30分×2）
 *   3. ＋30分延長（3セル→4セル→5セル）
 *   4. 末尾が予約済みで延長不可（disabled）の確認（事前にAPIで12:30枠を予約済みにしておく）
 *   5. グループ予約確定→成功トースト→マトリックスがBOOKEDに変わる
 *   6. 予約一覧タブでグループが1行表示（代表行のみ・兄弟行が複数行に見えない）
 *   7. グループキャンセル→マトリックスがAVAILABLEに復帰（事前予約枠は不変）
 *   8. 楽観ロック競合（RESERVATION_039）: 別経路（API）で先に埋めてから確定→エラートースト＋自動再読込
 *   9. 表示切替（グリッド/リスト）の共存確認
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

async function createThrowawayTeam(
  ctx: APIRequestContext,
  adminToken: string,
): Promise<{ slug: string }> {
  const res = await ctx.post(`${BE}/api/v1/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvMtx_予約v2第二弾検証_${Date.now()}` },
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

/** APIブリッジ（memory: feedback_e2e_wsl2_cors_apibridge）。 */
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

/**
 * iso 文字列を「タイムゾーンを持たない暦日」として扱う純粋な暦計算用 Date を作る
 * （UTC epoch を計算尺として使うだけで、実時刻の意味は持たせない）。
 *
 * 【根治治療メモ】当初 `new Date(iso + 'T00:00:00+09:00')` で JST アンカーの実時刻を作り、
 * その `getUTCDate()/getUTCDay()` を読む実装だったが、JST深夜0時のUTC表現は前日15:00Zになるため
 * UTCフィールドを直接読むと暦日・曜日が1日ズレるバグがあった（実機E2Eで発見・本関数で根治）。
 */
function calendarDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y!, m! - 1, d!))
}

function addDaysIso(baseIso: string, days: number): string {
  const dt = calendarDate(baseIso)
  dt.setUTCDate(dt.getUTCDate() + days)
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, '0')}-${String(dt.getUTCDate()).padStart(2, '0')}`
}

function isoWeekday(iso: string): number {
  return calendarDate(iso).getUTCDay()
}

function todayIsoJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return `${jst.getUTCFullYear()}-${String(jst.getUTCMonth() + 1).padStart(2, '0')}-${String(jst.getUTCDate()).padStart(2, '0')}`
}

function tomorrowInfo(): { iso: string; slash: string; dayCode: (typeof DAY_CODES)[number] } {
  const iso = addDaysIso(todayIsoJst(), 1)
  return {
    iso,
    slash: iso.replaceAll('-', '/'),
    dayCode: DAY_CODES[isoWeekday(iso)]!,
  }
}

/** マトリックスの行ラベル "YYYY/MM/DD (ddd)" を再現する（dayjs ja ロケール互換）。 */
function rowDateLabel(iso: string): string {
  return `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[isoWeekday(iso)]})`
}

interface SlotRow {
  id: number
  lineId: number | null
  basic: { slotDate: string; startTime: string; endTime: string }
  status: { slotStatus: string }
}

async function findSlotId(
  ctx: APIRequestContext,
  teamSlug: string,
  token: string,
  date: string,
  startTimePrefix: string,
  lineId: number,
): Promise<number> {
  const res = await ctx.get(`${BE}/api/v1/teams/${teamSlug}/reservation-slots?from=${date}&to=${date}`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`枠一覧取得失敗: ${res.status()} ${await res.text()}`)
  const rows = (await res.json()).data as SlotRow[]
  const found = rows.find((r) => r.lineId === lineId && r.basic?.startTime?.startsWith(startTimePrefix))
  if (!found) throw new Error(`枠が見つからない date=${date} start=${startTimePrefix} lineId=${lineId} rows=${JSON.stringify(rows.map(r => ({ id: r.id, lineId: r.lineId, st: r.basic?.startTime })))}`)
  return found.id
}

async function findLineId(ctx: APIRequestContext, teamSlug: string, token: string, name: string): Promise<number> {
  const res = await ctx.get(`${BE}/api/v1/teams/${teamSlug}/reservation-lines`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`ライン一覧取得失敗: ${res.status()} ${await res.text()}`)
  const rows = (await res.json()).data as Array<{ id: number; meta?: { name?: string } }>
  const found = rows.find((r) => r.meta?.name === name)
  if (!found) throw new Error(`ライン「${name}」が見つからない`)
  return found.id
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

test.setTimeout(240_000)
test.describe.configure({ mode: 'serial' })

test.describe('RSV-V2b: マトリックスUI＋グループ予約（実機一気通貫）', () => {
  let teamSlug = ''
  let lineId = 0
  const tmr = tomorrowInfo()
  const tmrPlus7 = addDaysIso(tmr.iso, 7)

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    // 営業時間: 全曜日 10:00-18:00（テンプレ 10:00-13:00 を包含）
    const hours = DAY_CODES.map((code) => ({
      dayOfWeek: code, isOpen: true, openTime: '10:00:00', closeTime: '18:00:00',
    }))
    const bhRes = await ctx.put(`${BE}/api/v1/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin), data: { hours },
    })
    if (!bhRes.ok()) throw new Error(`営業時間PUT失敗: ${bhRes.status()} ${await bhRes.text()}`)

    await ctx.dispose()
    console.log(`[SETUP] teamSlug=${teamSlug} tomorrow=${tmr.iso}(${tmr.dayCode}) tomorrow+7=${tmrPlus7}`)
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
    // タブ切替直後に見えるのは各セクションのヘッダーボタンのみで、「予約対象を追加」等の
    // 中身ボタンはセクションを展開するまで現れない。
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

  test('STEP-1: ライン「席A」・メニュー「カット・60分」・テンプレ(10:00-13:00)を作成し枠を生成する', async ({
    page, tokens,
  }) => {
    await gotoReservations(page, tokens)
    await openManageTab(page)
    await openAccordionSection(page, '予約対象の管理')

    // ライン作成
    await page.getByRole('button', { name: '予約対象を追加' }).click()
    let dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await dialog.getByRole('textbox').first().fill('席A')
    await dialog.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('予約対象を作成しました')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('席A').first()).toBeVisible({ timeout: 10_000 })

    // メニュー作成
    await openAccordionSection(page, 'メニュー管理')
    await page.getByTestId('menu-add').click()
    dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await page.getByTestId('menu-name').fill('カット')
    await dialog.getByRole('combobox').first().click()
    await page.getByRole('option', { name: '60分', exact: true }).click()
    await page.getByTestId('menu-save').click()
    await expect(page.getByText('メニューを作成しました')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('カット').first()).toBeVisible({ timeout: 10_000 })

    // ライン作成後にページ再読込せずテンプレダイアログを開くと、SlotTemplateManager が
    // mount 時点の古いライン一覧（=空）をキャッシュしたままで「席A」が選択肢に出ない
    // （写経元 first-forge spec は各フェーズを別 test() ＝別ページ読込に分けていたため顕在化しなかった）。
    // 根治: テンプレ作成前に一度リロードしてライン一覧を再取得させる。
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await openManageTab(page)
    await openAccordionSection(page, '週間テンプレート')

    // テンプレ作成: 席A・明日の曜日・10:00-13:00・定員1
    await page.getByTestId('template-add').click()
    dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await dialog.getByRole('combobox').nth(0).click()
    await page.getByRole('option', { name: '席A', exact: true }).click()
    await dialog.locator(`button[data-day="${tmr.dayCode}"]`).click()
    await dialog.getByRole('combobox').nth(1).click()
    await page.getByRole('option', { name: '10:00', exact: true }).last().click()
    await dialog.getByRole('combobox').nth(2).click()
    await page.getByRole('option', { name: '13:00', exact: true }).last().click()
    await page.getByTestId('template-save').click()

    // テンプレ保存＝同期自動生成（F03.4.5 §3.1）。「今すぐ枠を作成」ボタンは撤去済みで、
    // 保存直後に生成結果込みのトースト1本が出る（6セル(10:00-13:00)×該当曜日4回=24枠 期待）。
    const resultToast = page.getByText(/保存し、28日先までの枠を\d+件作成しました/)
    await expect(resultToast).toBeVisible({ timeout: 20_000 })
    const text = (await resultToast.textContent()) ?? ''
    const m = text.match(/保存し、28日先までの枠を(\d+)件作成しました/)
    const generated = Number(m?.[1] ?? -1)
    console.log(`[STEP-1] 保存時自動生成結果: generated=${generated} (raw="${text}")`)
    await expect(page.getByText('10:00 - 13:00')).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-01-setup-generated.png', fullPage: true })

    expect(generated, '生成0件は不合格').toBeGreaterThan(0)
    expect(generated, '6セル(10:00-13:00)×該当曜日4回=24枠').toBe(24)
  })

  test('STEP-2: 事前準備 — 12:30枠をAPIで予約済みにする（延長disabled検証用）', async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    lineId = await findLineId(ctx, teamSlug, tokens.admin, '席A')
    const slotId = await findSlotId(ctx, teamSlug, tokens.admin, tmr.iso, '12:30', lineId)
    const res = await ctx.post(`${BE}/api/v1/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: slotId, lineId, userNote: 'E2E事前予約(延長disabled検証)' },
    })
    if (!res.ok()) throw new Error(`事前予約失敗: ${res.status()} ${await res.text()}`)
    console.log(`[STEP-2] lineId=${lineId} 12:30枠(slotId=${slotId})を事前予約済みにした`)
    await ctx.dispose()
    expect(res.ok()).toBeTruthy()
  })

  test('STEP-3〜7:【核心】マトリックス既定表示→セル選択→メニュー→連続枠プレビュー→延長→確定→一覧→キャンセル', async ({
    page, tokens,
  }) => {
    await gotoReservations(page, tokens)

    // STEP-3: マトリックス既定表示の確認（SelectButton・メニューフィルター・週ナビ・凡例が見える）
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByRole('button', { name: /^週 /, exact: false })).toBeVisible()
    await expect(page.getByText('空き', { exact: true }).first()).toBeVisible()
    const matrixOption = page.getByRole('button', { name: 'マトリックス表示' })
    await expect(matrixOption).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-03-matrix-default.png', fullPage: true })

    // STEP-4: 明日の10:00セルをクリック
    const dateLabel = rowDateLabel(tmr.iso)
    const cell1000 = page.getByRole('button', { name: `${dateLabel} 10:00 席A 空き`, exact: true })
    await expect(cell1000).toBeVisible({ timeout: 15_000 })
    await cell1000.click()

    const menuDialog = page.getByRole('dialog', { name: 'メニューを選ぶ' })
    await expect(menuDialog).toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-v2b-04a-menu-select.png', fullPage: true })

    // メニュー「カット（60分）」選択 → 連続2セル自動プレビュー(10:00-11:00)
    // 注: getByRole の name アンカー正規表現は PrimeIcons の ::before 生成グリフが
    // アクセシブルネーム末尾に混入して不一致になるため、data-testid（コンポーネント付与）を使う。
    const menuOption = menuDialog.locator('[data-testid^="group-menu-option-"]').first()
    await expect(menuOption).toContainText('カット（60分）')
    await menuOption.click()
    const previewDialog = page.getByRole('dialog', { name: 'この内容で予約します' })
    await expect(previewDialog).toBeVisible({ timeout: 10_000 })
    await expect(previewDialog.getByText(tmr.iso, { exact: true })).toBeVisible()
    await expect(previewDialog.getByText('10:00 - 11:00', { exact: true })).toBeVisible()
    await expect(previewDialog.getByText('30分×2枠', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-04b-preview-2cells.png', fullPage: true })

    // STEP-5:【核心】＋30分延長 3回（2→3→4→5セル）
    const extendBtn = previewDialog.getByTestId('group-extend')
    await extendBtn.click()
    await expect(previewDialog.getByText('10:00 - 11:30', { exact: true })).toBeVisible()
    await expect(previewDialog.getByText('30分×3枠', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-05a-extend-3cells.png', fullPage: true })

    await extendBtn.click()
    await expect(previewDialog.getByText('10:00 - 12:00', { exact: true })).toBeVisible()
    await expect(previewDialog.getByText('30分×4枠', { exact: true })).toBeVisible()

    await extendBtn.click()
    await expect(previewDialog.getByText('10:00 - 12:30', { exact: true })).toBeVisible()
    await expect(previewDialog.getByText('30分×5枠', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-05b-extend-5cells.png', fullPage: true })

    // STEP-6:【核心】末尾(12:30-13:00)が事前予約済みのため、これ以上延長できない = disabled
    await expect(extendBtn).toBeDisabled()
    const title = await extendBtn.getAttribute('title')
    console.log(`[STEP-6] 延長ボタンdisabled時のtitle="${title}"`)
    expect(title, 'disabled理由のtitleが表示されること').toContain('これ以上延長できません')
    await page.screenshot({ path: 'test-results/rsv-v2b-06-extend-disabled.png', fullPage: true })

    // STEP-7: グループ予約確定
    const confirmBtn = previewDialog.getByTestId('group-confirm')
    await confirmBtn.click()
    await expect(page.getByText('予約が完了しました')).toBeVisible({ timeout: 15_000 })
    await expect(previewDialog).not.toBeVisible({ timeout: 10_000 })

    // マトリックスが自動リフレッシュされ、10:00〜12:30の5セルがBOOKEDに変わる（emit連鎖・ポーリング無し）
    for (const t of ['10:00', '10:30', '11:00', '11:30', '12:00']) {
      await expect(
        page.getByRole('button', { name: `${dateLabel} ${t} 席A 埋まっている`, exact: true }),
        `${t}セルがBOOKEDに変わること`,
      ).toBeVisible({ timeout: 15_000 })
    }
    // 12:30は事前予約分でもとからBOOKED
    await expect(
      page.getByRole('button', { name: `${dateLabel} 12:30 席A 埋まっている`, exact: true }),
    ).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-07-matrix-booked.png', fullPage: true })

    // STEP-8: 予約一覧タブでグループが1行表示（代表行のみ・兄弟行が複数行に見えない）
    await page.getByRole('tab', { name: '予約一覧' }).click()
    // 表示実体は「カット ・30分×5枠」（Vueテンプレート改行由来の空白が「・」前に入る）ため \s* で吸収する
    const groupRowText = page.getByText(/カット\s*・30分×5枠/)
    await expect(groupRowText).toBeVisible({ timeout: 15_000 })
    const groupRowCount = await groupRowText.count()
    console.log(`[STEP-8] グループ表示行数=${groupRowCount}（1行のはず。兄弟行が複数行に見えるバグの検出）`)
    expect(groupRowCount, 'グループ予約は代表行1件のみ表示されること').toBe(1)
    await page.screenshot({ path: 'test-results/rsv-v2b-08-list-group-row.png', fullPage: true })

    // STEP-9:【修正1検証】グループキャンセル。ConfirmDialog は app.vue 一本化により
    // role=alertdialog が常に1要素のみ出現する（旧実装は ReservationList/LineManager 等の
    // 重複マウントで3要素同時に開き、1個操作しても残りが画面を塞ぐ実バグがあった。#2179系第二弾
    // 実機E2Eで発見・根治済み。回帰ガードとして toHaveCount(1) を明示的に確認する）。
    const cancelBtn = page.locator('button:has(.pi-ban)').first()
    await expect(cancelBtn).toBeVisible({ timeout: 10_000 })
    await cancelBtn.click()
    const confirmDialog = page.getByRole('alertdialog').filter({ hasText: '枠すべてキャンセルされます' })
    await expect(confirmDialog, 'ConfirmDialog は app.vue の単一インスタンスのみ出現すること（多重マウント回帰ガード）').toHaveCount(1, { timeout: 10_000 })
    await expect(confirmDialog.getByText('5枠すべてキャンセルされます', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-09a-cancel-confirm.png', fullPage: true })
    await confirmDialog.getByRole('button', { name: 'キャンセルする' }).click()
    await expect(page.getByText('予約をキャンセルしました')).toBeVisible({ timeout: 15_000 })
    // キャンセル操作後、残留ダイアログが無いこと（多重マウント時代は残り2個が残留していた）。
    await expect(page.getByRole('alertdialog')).toHaveCount(0, { timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-v2b-09b-cancel-done.png', fullPage: true })

    // STEP-10:【修正2検証】マトリックスに戻り、キャンセルした5セルがAVAILABLEに復帰・12:30は不変(BOOKEDのまま)。
    // ページ再読込は行わない — ReservationList の cancel/approve/reject 成功時に emit('changed') し、
    // TeamReservationsPanel が SlotMatrixPicker/SlotGridPicker/SlotPicker の refresh を発火する結線
    // （#2179で結線した「予約→枠表示」の逆方向）を根治したため、タブ切替のみで反映されるはず
    // （旧実装は結線が無く、ページ再読込するまで枠表示が古いままだった実バグがあった）。
    await page.getByRole('tab', { name: '予約する' }).click()
    for (const t of ['10:00', '10:30', '11:00', '11:30', '12:00']) {
      await expect(
        page.getByRole('button', { name: `${dateLabel} ${t} 席A 空き`, exact: true }),
        `${t}セルがAVAILABLEに復帰すること（ページ再読込なし＝一覧操作→枠表示 refresh結線の回帰ガード）`,
      ).toBeVisible({ timeout: 15_000 })
    }
    await expect(
      page.getByRole('button', { name: `${dateLabel} 12:30 席A 埋まっている`, exact: true }),
      '事前予約(12:30)は今回のグループキャンセルの対象外で不変であること',
    ).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-10-matrix-restored.png', fullPage: true })
  })

  test('STEP-11:【核心】楽観ロック競合(RESERVATION_039) — 別経路で先に埋めてからグループ確定を試みる', async ({
    page, tokens,
  }) => {
    await gotoReservations(page, tokens)

    // 週送り: tomorrow+7（テンプレの次回生成分・まだ何も予約されていない同曜日の枠）へ移動
    const weekBtn = page.getByRole('button', { name: /^週 /, exact: false })
    // PrimeVue Button の icon は <span class="p-button-icon pi pi-angle-right"> で描画される（<i> ではない）
    const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
    let moved = false
    for (let i = 0; i < 6; i++) {
      const text = (await weekBtn.textContent()) ?? ''
      const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
      if (!m) throw new Error(`週ラベル取得失敗: "${text}"`)
      const start = `${m[1]}-${m[2]}-${m[3]}`
      const end = `${m[4]}-${m[5]}-${m[6]}`
      if (tmrPlus7 >= start && tmrPlus7 <= end) { moved = true; break }
      await nextWeekBtn.click()
      await page.waitForTimeout(400)
    }
    expect(moved, `週範囲内に ${tmrPlus7} が見つかること`).toBeTruthy()

    const dateLabel = rowDateLabel(tmrPlus7)
    const cell1000 = page.getByRole('button', { name: `${dateLabel} 10:00 席A 空き`, exact: true })
    await expect(cell1000).toBeVisible({ timeout: 15_000 })
    await cell1000.click()

    const menuDialog = page.getByRole('dialog', { name: 'メニューを選ぶ' })
    await expect(menuDialog).toBeVisible({ timeout: 10_000 })
    await menuDialog.locator('[data-testid^="group-menu-option-"]').first().click()
    const previewDialog = page.getByRole('dialog', { name: 'この内容で予約します' })
    await expect(previewDialog).toBeVisible({ timeout: 10_000 })
    await expect(previewDialog.getByText('10:00 - 11:00', { exact: true })).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-v2b-11a-conflict-preview.png', fullPage: true })

    // 別経路（API直叩き=別リクエスト）で 10:30-11:00 の枠を先に塞ぐ（ダイアログのプレビューは古いまま）。
    // 【観測メモ（実API裏取り済み）】同一ユーザーの先着予約で塞ぐと、グループ確保の満席チェック(039)より
    // 先に自己重複チェック（RESERVATION_013・409「同じスロットに既に予約が存在します」）が発火する。
    // 【修正3で根治済み】発見当時 FE GroupBookingDialog は 013 をハンドリングしておらず
    // 「予約に失敗しました」汎用トーストになっていたが、専用文言
    // （reservation.group.own_overlap）を追加した（vitest AC-5c で分岐を番人化。本 spec は
    // 013 自体の再現には他ユーザーの先着が必要なため対象外とし、下記は別シナリオ(039)を検証する）。
    // E2E一人分の資格情報では「他人の先着」を作れないため、
    // ここでは管理者の枠締切（close→CLOSED化）で「確定時に枠が塞がっていた」状況を作り、
    // 039（トースト＋自動再読込）の体感を検証する（BE実応答 039 は curl で裏取り済み）。
    const ctx = await playwrightRequest.newContext()
    const slotId = await findSlotId(ctx, teamSlug, tokens.admin, tmrPlus7, '10:30', lineId)
    const closeRes = await ctx.post(`${BE}/api/v1/teams/${teamSlug}/reservation-slots/${slotId}/close`, {
      headers: authHeaders(tokens.admin),
      data: { reason: 'E2E-039' },
    })
    if (!closeRes.ok()) throw new Error(`競合用の枠締切に失敗: ${closeRes.status()} ${await closeRes.text()}`)
    await ctx.dispose()
    console.log(`[STEP-11] 10:30枠(slotId=${slotId})を別経路でCLOSED化した`)

    // 古いプレビューのまま確定を試みる → RESERVATION_039 → 競合エラートースト＋自動再読込
    const confirmBtn = previewDialog.getByTestId('group-confirm')
    await confirmBtn.click()
    const conflictToast = page.getByText('他の方が先に予約しました。空き状況を更新したので選び直してください')
    await expect(conflictToast, '競合エラートーストが出ること').toBeVisible({ timeout: 15_000 })
    await expect(previewDialog, 'ダイアログは自動で閉じる(emit reserved+close)').not.toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-v2b-11b-conflict-toast.png', fullPage: true })

    // 自動再読込で 10:30 が CLOSED（受付終了）として反映されていること（空き状況の更新）
    await expect(
      page.getByRole('button', { name: `${dateLabel} 10:30 席A 受付終了`, exact: true }),
    ).toBeVisible({ timeout: 15_000 })
  })

  test('STEP-12: 表示切替（グリッド/リスト）でも従来通り描画される（1枚ずつ）', async ({ page, tokens }) => {
    await gotoReservations(page, tokens)

    await page.getByRole('button', { name: 'スタッフ別グリッド' }).click()
    await page.waitForTimeout(500)
    await page.screenshot({ path: 'test-results/rsv-v2b-12a-grid-view.png', fullPage: true })
    // グリッド表示は従来コンポーネント(SlotGridPicker)。凡例文言などエラーなく描画されること
    await expect(page.getByText('予約対象で絞り込む')).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: 'リスト表示' }).click()
    await page.waitForTimeout(500)
    await page.screenshot({ path: 'test-results/rsv-v2b-12b-list-view.png', fullPage: true })
    // リスト表示は既存 SlotPicker。日付ピッカーが表示されること
    await expect(page.locator('.p-datepicker-input').first()).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: 'マトリックス表示' }).click()
    await page.waitForTimeout(500)
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-v2b-12c-back-to-matrix.png', fullPage: true })
  })
})
