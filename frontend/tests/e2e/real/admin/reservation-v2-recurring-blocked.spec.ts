/**
 * 予約v2 W2-2「定期予約不可枠」実機一気通貫E2E（BE PR #2232 / FE PR #2485・worktree HEAD 0ae6bcb2c）
 *
 * バックエンド http://127.0.0.1:8081（検証用・稼働中/停止厳禁）
 * フロントエンド http://127.0.0.1:3001（検証用 dev server。BASE_URL/API_BASE_URL 環境変数で上書き可）
 *
 * 【重要】ブラウザ・Playwright はすべて 127.0.0.1 系で統一する（localhost と混在させると
 * Origin 不一致で CORS 死する。殿が FE を NUXT_PUBLIC_API_BASE=http://127.0.0.1:8081・
 * --host 127.0.0.1 で再起動し、BE の MANNSCHAFT_ALLOWED_ORIGINS に http://127.0.0.1:3001 を追加済み）。
 *
 * 写経元: reservation-v2-exception-day.spec.ts（ログイン機構・日付ユーティリティ・ダイアログ操作作法）、
 * reservation-v2-matrix-group.spec.ts（マトリックスUI・週送り・セルaria-label規則）、
 * module-activation-backfill.spec.ts（BASE_URL=3001/API_BASE_URL=8081構成・loginViaApi実例）。
 * 単一セッション設計・別contextログイン禁止。
 *
 * 対象コンポーネント: WeeklyScheduleManager.vue（F03.4.5 §4 定期予約不可枠・週次繰り返し）。
 * 会員側表示は SlotMatrixPicker.vue（unavailableReasonOfSlot・reservationMatrix.ts）。
 *
 * 【シナリオ→AC対応】
 *   1. 作成: ダイアログで曜日/時間帯/事由/公開トグルを入力して登録できる。
 *      dayOfWeek が3文字正準（'MON'..'SUN'）で送信されていること（実POSTボディで裏取り）。
 *   2. reason_no_pii ガイド: 事由欄に「個人情報を書かない」旨の必須ガイド文言（isPublic ON時）。
 *   3. 全日タイプ不可: 定期不可枠には全日指定UIが無く、full_day_hint 文言で案内される。
 *   4. 会員側の事由ラベル表示: is_public=TRUE の枠が UNAVAILABLE セルに事由ラベル付きで表示される。
 *   5. 非公開はPII漏れなし: is_public=FALSE の枠は DOM にも API レスポンス実体にも事由文字列が
 *      一切出ない（BE の GridCellDto.unavailableReason はソースコード上 is_public=TRUE のときのみ
 *      値を詰める設計＝ボディごと落とす想定。本specで実機のネットワーク実体から裏取りする）。
 *   6. 停止・編集・削除: 一覧から対象枠の有効/無効トグル・編集・削除ができる。
 *   7. 409ガード: 既存予約と衝突する枠は impact 警告＋登録ボタンdisabled（UI一次防御）。
 *      UIをバイパスした直接API呼びでも 409(RESERVATION_027) が返ることを defense-in-depth で確認。
 *   8. 既存テンプレの非退行: 従来の枠テンプレ（週間スケジュール内の青系セクション）のCRUDが壊れていない。
 */

import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Locator,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'
import { selectDropdown, fillInput } from '../../helpers/form'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://127.0.0.1:8081'
const BE_API = `${API_BASE_URL}/api/v1`

// E2E固定ユーザー（memory: project_e2e_test_user_provisioning_when_seed_creds_drift）。id=90209。
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

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
  const res = await ctx.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

async function fetchMe(ctx: APIRequestContext, token: string): Promise<MeProfile> {
  const res = await ctx.get(`${BE_API}/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data as MeProfile
}

async function createThrowawayTeam(ctx: APIRequestContext, adminToken: string): Promise<{ slug: string }> {
  const res = await ctx.post(`${BE_API}/teams`, {
    headers: authHeaders(adminToken),
    data: { name: `RsvRec_定期不可枠検証_${Date.now()}` },
  })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  const data = (await res.json()).data as { slug: string }
  return { slug: data.slug }
}

async function enableReservationModule(ctx: APIRequestContext, adminToken: string, slug: string): Promise<void> {
  const catalogRes = await ctx.get(`${BE_API}/teams/${slug}/modules/catalog`, { headers: authHeaders(adminToken) })
  if (!catalogRes.ok()) throw new Error(`モジュールカタログ取得失敗: ${catalogRes.status()} ${await catalogRes.text()}`)
  const catalog = (await catalogRes.json()).data as { modules: { moduleId: number; slug: string; isEnabled: boolean }[] }
  const reservationModule = catalog.modules.find(m => m.slug === 'reservation')
  if (!reservationModule) throw new Error('カタログに reservation モジュールが見つからない')
  const toggleRes = await ctx.patch(`${BE_API}/teams/${slug}/modules/${reservationModule.moduleId}/toggle`, {
    headers: authHeaders(adminToken),
    data: { moduleId: reservationModule.moduleId, enabled: true },
  })
  if (!toggleRes.ok()) throw new Error(`予約モジュール有効化失敗: ${toggleRes.status()} ${await toggleRes.text()}`)
}

// === 日付ユーティリティ（Asia/Tokyo 前提・playwright.config の timezoneId と一致。写経元踏襲） ===
const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
type DayCode = (typeof DAY_CODES)[number]
const WEEKDAY_JA = ['日', '月', '火', '水', '木', '金', '土'] as const
const DAY_LABEL_JA: Record<DayCode, string> = { SUN: '日', MON: '月', TUE: '火', WED: '水', THU: '木', FRI: '金', SAT: '土' }

/**
 * iso 文字列を「タイムゾーンを持たない暦日」として扱う純粋な暦計算用 Date を作る
 * （UTC epoch を計算尺として使うだけで、実時刻の意味は持たせない。写経元 matrix-group spec 踏襲）。
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

interface DateInfo {
  iso: string
  dayCode: DayCode
  /** マトリックス行ラベル "YYYY/MM/DD (ddd)"（dayjs ja ロケール互換）。 */
  rowLabel: string
}
function dateInfo(offsetDays: number): DateInfo {
  const iso = addDaysIso(todayIsoJst(), offsetDays)
  const dow = isoWeekday(iso)
  return { iso, dayCode: DAY_CODES[dow]!, rowLabel: `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[dow]})` }
}

interface SlotRow {
  id: number
  lineId: number | null
  basic: { slotDate: string; startTime: string; endTime: string }
}

async function findSlotId(
  ctx: APIRequestContext,
  teamSlug: string,
  token: string,
  date: string,
  startTimePrefix: string,
  lineId: number,
): Promise<number> {
  const res = await ctx.get(`${BE_API}/teams/${teamSlug}/reservation-slots?from=${date}&to=${date}`, {
    headers: authHeaders(token),
  })
  if (!res.ok()) throw new Error(`枠一覧取得失敗: ${res.status()} ${await res.text()}`)
  const rows = (await res.json()).data as SlotRow[]
  const found = rows.find(r => r.lineId === lineId && r.basic?.startTime?.startsWith(startTimePrefix))
  if (!found) {
    throw new Error(
      `枠が見つからない date=${date} start=${startTimePrefix} lineId=${lineId} `
      + `rows=${JSON.stringify(rows.map(r => ({ id: r.id, lineId: r.lineId, st: r.basic?.startTime })))}`,
    )
  }
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
      const admin = await login(ctx, USER_EMAIL, USER_PASSWORD)
      const adminMe = await fetchMe(ctx, admin)
      await ctx.dispose()
      await use({ admin, adminMe })
    },
    { scope: 'worker' },
  ],
})

test.setTimeout(240_000)
// 各シナリオは互いに独立な曜日/時間帯を使うため serial にしない（各シナリオの実走結果を独立に観測する）。

test.describe('RSV-V2 W2-2: 定期予約不可枠（実機一気通貫・8シナリオ）', () => {
  let teamSlug = ''
  let interviewLineId = 0

  // 曜日が重複しないよう offset を1日ずつずらす（+2〜+7の6日間・互いに異なる曜日）
  const dayCreate = dateInfo(2) // シナリオ1: 作成＋dayOfWeek 3文字コード裏取り
  // シナリオ2/3 は同一ダイアログ内のUI確認のみで DB 書込を伴わないため、専用の曜日を持たない
  const dayPublic = dateInfo(3) // シナリオ4: 会員側 公開事由ラベル表示
  const dayNonPublic = dateInfo(4) // シナリオ5: 会員側 非公開PII非漏洩
  const dayConflict = dateInfo(5) // シナリオ7: 409ガード
  const dayCrud = dateInfo(6) // シナリオ6: 停止/編集/削除
  const dayRegress = dateInfo(7) // シナリオ8: 既存テンプレ非退行

  const PII_MARKER = 'PII漏洩検査用マーカー山田太郎様専用清掃'

  test.beforeAll(async ({ tokens }) => {
    const ctx = await playwrightRequest.newContext()
    const team = await createThrowawayTeam(ctx, tokens.admin)
    teamSlug = team.slug
    await enableReservationModule(ctx, tokens.admin, teamSlug)

    // 営業時間: 全曜日 08:00-22:00（対象6日間すべてを内包する広めの設定）
    const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
    const hoursRes = await ctx.put(`${BE_API}/teams/${teamSlug}/reservation-settings/business-hours`, {
      headers: authHeaders(tokens.admin),
      data: { hours },
    })
    if (!hoursRes.ok()) throw new Error(`営業時間PUT失敗: ${hoursRes.status()} ${await hoursRes.text()}`)

    // 面談室ライン（シナリオ4/5/7の実枠生成用）
    const lineRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-lines`, {
      headers: authHeaders(tokens.admin),
      data: { name: '面談室' },
    })
    if (!lineRes.ok()) throw new Error(`ライン作成失敗: ${lineRes.status()} ${await lineRes.text()}`)
    interviewLineId = ((await lineRes.json()).data as { id: number }).id

    // dayPublic / dayNonPublic / dayConflict の3曜日にテンプレ 09:00-12:00（6セル）を用意
    for (const d of [dayPublic, dayNonPublic, dayConflict]) {
      const tplRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-slot-templates`, {
        headers: authHeaders(tokens.admin),
        data: { lineId: interviewLineId, dayOfWeek: d.dayCode, startTime: '09:00:00', endTime: '12:00:00', capacity: 1 },
      })
      if (!tplRes.ok()) throw new Error(`テンプレ作成失敗(${d.dayCode}): ${tplRes.status()} ${await tplRes.text()}`)
    }

    // dayConflict 10:00 枠に実予約を作成（シナリオ7の409ガード検証用）
    const conflictSlotId = await findSlotId(ctx, teamSlug, tokens.admin, dayConflict.iso, '10:00', interviewLineId)
    const reserveRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservations`, {
      headers: authHeaders(tokens.admin),
      data: { reservationSlotId: conflictSlotId, lineId: interviewLineId, userNote: 'E2E定期不可枠検証(409衝突用)' },
    })
    if (!reserveRes.ok()) throw new Error(`衝突用予約作成失敗: ${reserveRes.status()} ${await reserveRes.text()}`)

    console.log(
      `[SETUP] teamSlug=${teamSlug} interviewLineId=${interviewLineId} `
      + `dayCreate=${dayCreate.iso}(${dayCreate.dayCode}) dayPublic=${dayPublic.iso}(${dayPublic.dayCode}) `
      + `dayNonPublic=${dayNonPublic.iso}(${dayNonPublic.dayCode}) dayConflict=${dayConflict.iso}(${dayConflict.dayCode}) `
      + `dayCrud=${dayCrud.iso}(${dayCrud.dayCode}) dayRegress=${dayRegress.iso}(${dayRegress.dayCode})`,
    )
    await ctx.dispose()
  })

  async function gotoReservations(page: Page): Promise<void> {
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })
    await page.goto(`/teams/${teamSlug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
    await waitForHydration(page)
  }

  /** 「予約対象の管理」タブ→「週間スケジュール」アコーディオンを開く。 */
  async function openWeeklySchedule(page: Page): Promise<void> {
    const manageTab = page.getByRole('tab', { name: '予約対象の管理' })
    await expect(manageTab).toBeVisible({ timeout: 30_000 })
    await manageTab.click()
    const header = page.getByRole('button', { name: /^週間スケジュール(\s*\(\d+\))?$/ })
    await expect(header, '週間スケジュールセクションのヘッダーが表示されること').toBeVisible({ timeout: 20_000 })
    if ((await header.getAttribute('aria-expanded')) !== 'true') await header.click()
  }

  /** 定期不可の追加ダイアログを開く（ヘッダー右の「予約不可を追加」）。 */
  async function openCreateRecurringDialog(page: Page): Promise<Locator> {
    await page.getByTestId('recurring-add').click()
    const dialog = page.getByRole('dialog', { name: '予約不可を追加' })
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    return dialog
  }

  /** 曜日・時間帯を選択する（Select系はテスト用ヘルパー selectDropdown 経由）。 */
  async function setRecurringDayAndTime(page: Page, day: DayCode, start: string, end: string): Promise<void> {
    await selectDropdown(page, page.getByTestId('recurring-day-select'), DAY_LABEL_JA[day])
    await selectDropdown(page, page.getByTestId('recurring-start-time'), start)
    await selectDropdown(page, page.getByTestId('recurring-end-time'), end)
  }

  /**
   * マトリックスの週送りで目的日を含む週まで移動する（写経元 matrix-group spec STEP-11 踏襲）。
   *
   * 【自己バグ修正】目的日が「ページ初回ロード時点で既に表示中の週」に含まれる場合、
   * ループが1度もnextWeekBtnをクリックせずに即returnしてしまい、直前にUIから作成した
   * 定期不可枠がgridへ反映される前の“古い取得結果”のまま描画され続ける競合があった
   * （初回実行時にシナリオ4/5のDOMセル検証が誤って赤化した原因・製品コードは無関係。
   * curlでの直接API裏取りでは正しい値が返っていたことを確認済み）。
   * 対象週へ到達した後、必ず prev→next の往復で明示的に再フェッチさせて解消する。
   */
  async function goToWeekContaining(page: Page, targetIso: string): Promise<void> {
    const weekBtn = page.getByRole('button', { name: /^週 /, exact: false })
    await expect(weekBtn).toBeVisible({ timeout: 20_000 })
    const prevWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-left') }).first()
    const nextWeekBtn = page.locator('button').filter({ has: page.locator('.pi-angle-right') }).first()
    let reached = false
    for (let i = 0; i < 8; i++) {
      const text = (await weekBtn.textContent()) ?? ''
      const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
      if (!m) throw new Error(`週ラベル取得失敗: "${text}"`)
      const start = `${m[1]}-${m[2]}-${m[3]}`
      const end = `${m[4]}-${m[5]}-${m[6]}`
      if (targetIso >= start && targetIso <= end) { reached = true; break }
      await nextWeekBtn.click()
      await page.waitForTimeout(400)
    }
    if (!reached) throw new Error(`週範囲内に ${targetIso} が見つからない`)
    // 対象週の grid を強制的に再フェッチさせる（既に対象週にいた場合でも最新状態を確実に反映する）
    await prevWeekBtn.click()
    await page.waitForTimeout(400)
    await nextWeekBtn.click()
    await page.waitForTimeout(400)
  }

  // ── シナリオ1: 作成（dayOfWeek 3文字正準の実POSTボディ裏取り） ─────────────────
  test('シナリオ1: 曜日/時間帯/事由/公開トグルを入力して登録でき、dayOfWeekが3文字正準で送信される', async ({ page }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)
    const dialog = await openCreateRecurringDialog(page)

    await setRecurringDayAndTime(page, dayCreate.dayCode, '18:00', '18:30')
    await fillInput(page.getByTestId('recurring-reason'), 'E2E作成検証(シナリオ1)')

    const postPromise = page.waitForRequest(
      req => req.url().includes('/reservation-recurring-blocked-times') && req.method() === 'POST',
      { timeout: 15_000 },
    )
    await dialog.getByTestId('recurring-save').click()
    const req = await postPromise
    const bodyText = req.postData() ?? '{}'
    const body = JSON.parse(bodyText) as { dayOfWeek?: string }
    console.log(`[シナリオ1] POST body dayOfWeek実体="${body.dayOfWeek}"`)

    expect(body.dayOfWeek, 'dayOfWeekは3文字大文字コードであること（MONDAY等のフルネーム混入禁止）')
      .toMatch(/^(SUN|MON|TUE|WED|THU|FRI|SAT)$/)
    expect(body.dayOfWeek, `送信されたdayOfWeekが選択曜日(${dayCreate.dayCode})と一致すること`).toBe(dayCreate.dayCode)

    await expect(page.getByText('定期予約不可枠を登録しました')).toBeVisible({ timeout: 15_000 })
    await page.screenshot({ path: 'test-results/rsv-rec-01-create-success.png', fullPage: true })
  })

  // ── シナリオ2: reason_no_pii ガイド ─────────────────────────────────────────
  test('シナリオ2: 公開トグルONで事由欄に「個人情報を書かない」旨の必須ガイドが表示される', async ({ page }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)
    const dialog = await openCreateRecurringDialog(page)

    const guide = page.getByTestId('recurring-reason-no-pii')
    await expect(guide, '公開トグルOFF時点ではガイドは非表示').not.toBeVisible()

    await page.getByTestId('recurring-is-public-toggle').click()
    await expect(guide, '公開トグルONでガイドが表示される').toBeVisible({ timeout: 5_000 })
    await expect(guide).toHaveText('事由に個人名やお客様情報を含めないでください（会員全員に公開されます）')
    await page.screenshot({ path: 'test-results/rsv-rec-02-no-pii-guide.png', fullPage: true })

    // OFFに戻すと再び非表示になること（表示切替の往復確認）
    await page.getByTestId('recurring-is-public-toggle').click()
    await expect(guide, '公開トグルOFFに戻すとガイドが再び非表示になる').not.toBeVisible()
    void dialog
  })

  // ── シナリオ3: 全日タイプ不可 ────────────────────────────────────────────
  test('シナリオ3: 定期不可枠には全日指定UIが無く、full_day_hintの案内文が出る', async ({ page }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)
    await openCreateRecurringDialog(page)

    // 全日/終日を示すチェックボックス・トグル・ボタン等が存在しないこと
    await expect(page.getByRole('checkbox', { name: /終日|全日/ })).toHaveCount(0)
    await expect(page.getByText('終日休みにする場合は営業時間で定休日に設定してください')).toBeVisible({ timeout: 10_000 })

    // 開始・終了時刻の両方が必須入力として存在すること（全日型を作らせない設計の裏付け）
    await expect(page.getByTestId('recurring-start-time')).toBeVisible()
    await expect(page.getByTestId('recurring-end-time')).toBeVisible()
    await page.screenshot({ path: 'test-results/rsv-rec-03-no-fullday-option.png', fullPage: true })
  })

  // ── シナリオ4: 会員側の事由ラベル表示（is_public=TRUE） ───────────────────────
  test('シナリオ4: is_public=TRUEの枠は会員側マトリックスでUNAVAILABLE＋事由ラベル表示される', async ({ page, tokens }) => {
    const reason = 'E2E定期メンテナンス(公開)'
    await gotoReservations(page)
    await openWeeklySchedule(page)
    const dialog = await openCreateRecurringDialog(page)
    await setRecurringDayAndTime(page, dayPublic.dayCode, '10:00', '10:30')
    await fillInput(page.getByTestId('recurring-reason'), reason)
    await page.getByTestId('recurring-is-public-toggle').click()
    await dialog.getByTestId('recurring-save').click()
    await expect(page.getByText('定期予約不可枠を登録しました')).toBeVisible({ timeout: 15_000 })

    // マトリックス（予約するタブ）で対象セルを確認
    await page.getByRole('tab', { name: '予約する' }).click()
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
    await goToWeekContaining(page, dayPublic.iso)

    const cell = page.getByRole('button', { name: `${dayPublic.rowLabel} 10:00 面談室 × ${reason}`, exact: true })
    await expect(cell, 'UNAVAILABLEセルに事由ラベル付きテキストが表示されること').toBeVisible({ timeout: 15_000 })
    await expect(cell).toBeDisabled()
    await page.screenshot({ path: 'test-results/rsv-rec-04-public-reason-cell.png', fullPage: true })

    // API実体でも unavailableReason が載っていること（DOM表示の裏取り）
    const gridRes = await page.request.get(
      `${API_BASE_URL}/api/v1/teams/${teamSlug}/reservation-slots/grid?from=${dayPublic.iso}&to=${dayPublic.iso}&axis=LINE`,
      { headers: { Authorization: `Bearer ${tokens.admin}` } },
    )
    expect(gridRes.ok(), `grid API失敗: ${gridRes.status()}`).toBeTruthy()
    const gridBody = (await gridRes.json()) as { data: { days: Array<{ date: string; columns: Array<{ lineId: number | null; cells: Array<{ startTime: string; state: string; unavailableReason: string | null }> }> }> } }
    const day = gridBody.data.days.find(d => d.date === dayPublic.iso)
    const col = day?.columns.find(c => c.lineId === interviewLineId)
    const target = col?.cells.find(c => c.startTime.startsWith('10:00'))
    console.log(`[シナリオ4] 実API cell実体=${JSON.stringify(target)}`)
    expect(target?.state, 'API実体でもUNAVAILABLE').toBe('UNAVAILABLE')
    expect(target?.unavailableReason, 'API実体にunavailableReasonが載っていること(is_public=TRUE)').toBe(reason)
  })

  // ── シナリオ5: 非公開はPII漏れなし（is_public=FALSE） ─────────────────────────
  test('シナリオ5: is_public=FALSEの枠は会員側のDOM・API実体のいずれにも事由文字列が一切出ない', async ({ page, tokens }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)
    const dialog = await openCreateRecurringDialog(page)
    await setRecurringDayAndTime(page, dayNonPublic.dayCode, '10:00', '10:30')
    await fillInput(page.getByTestId('recurring-reason'), PII_MARKER)
    // isPublicトグルには触れない（既定値=OFF/非公開のまま）
    await dialog.getByTestId('recurring-save').click()
    await expect(page.getByText('定期予約不可枠を登録しました')).toBeVisible({ timeout: 15_000 })

    await page.getByRole('tab', { name: '予約する' }).click()
    await expect(page.getByText('メニューで絞り込む')).toBeVisible({ timeout: 20_000 })
    await goToWeekContaining(page, dayNonPublic.iso)

    // grid API のレスポンス実体を明示的なGETで直接検証する（UI自身のfetchタイミングに依存せず、
    // 既に対象週にいる/いないに関わらず確実に捕捉できるようにするため waitForResponse は使わない）。
    const gridRes = await page.request.get(
      `${API_BASE_URL}/api/v1/teams/${teamSlug}/reservation-slots/grid?from=${dayNonPublic.iso}&to=${dayNonPublic.iso}&axis=LINE`,
      { headers: { Authorization: `Bearer ${tokens.admin}` } },
    )
    expect(gridRes.ok(), `grid API失敗: ${gridRes.status()}`).toBeTruthy()
    const rawBodyText = await gridRes.text()

    console.log(`[シナリオ5] grid APIレスポンス実体にPIIマーカー文字列が含まれるか: ${rawBodyText.includes(PII_MARKER)}`)
    expect(rawBodyText.includes(PII_MARKER), 'BE grid APIレスポンス実体にPII事由文字列が一切含まれないこと(is_public=FALSE)').toBe(false)

    const gridBody = JSON.parse(rawBodyText) as { data: { days: Array<{ date: string; columns: Array<{ lineId: number | null; cells: Array<{ startTime: string; state: string; unavailableReason: string | null }> }> }> } }
    const day = gridBody.data.days.find(d => d.date === dayNonPublic.iso)
    const col = day?.columns.find(c => c.lineId === interviewLineId)
    const target = col?.cells.find(c => c.startTime.startsWith('10:00'))
    console.log(`[シナリオ5] 実API cell実体=${JSON.stringify(target)}`)
    expect(target?.state, 'stateはUNAVAILABLE（枠自体は不可として見える）').toBe('UNAVAILABLE')
    expect(target?.unavailableReason ?? null, 'unavailableReasonはnull/欠落であること(is_public=FALSE)').toBeNull()

    // DOM側（セルの見た目テキスト・aria-label・title）にもPII文字列が出ないこと
    const cell = page.getByRole('button', { name: `${dayNonPublic.rowLabel} 10:00 面談室 予約不可`, exact: true })
    await expect(cell, '事由ラベル無しの汎用「予約不可」表示であること').toBeVisible({ timeout: 15_000 })
    const pageText = await page.locator('body').innerText()
    expect(pageText.includes(PII_MARKER), 'DOM上にもPII事由文字列が一切出ないこと').toBe(false)
    await page.screenshot({ path: 'test-results/rsv-rec-05-nonpublic-no-pii.png', fullPage: true })
  })

  // ── シナリオ6: 停止・編集・削除 ────────────────────────────────────────────
  test('シナリオ6: 一覧から対象枠の有効/無効トグル・編集・削除ができる', async ({ page, tokens }) => {
    // セットアップ: API直叩きで初期ルールを1件作る（UI操作対象を用意するだけの副次セットアップ）
    const ctx = await playwrightRequest.newContext()
    const createRes = await ctx.post(`${BE_API}/teams/${teamSlug}/reservation-recurring-blocked-times`, {
      headers: authHeaders(tokens.admin),
      data: { dayOfWeek: dayCrud.dayCode, startTime: '20:00:00', endTime: '20:30:00', reason: 'E2E CRUD検証(初期値)', isPublic: false },
    })
    if (!createRes.ok()) throw new Error(`初期ルール作成失敗: ${createRes.status()} ${await createRes.text()}`)
    const ruleId = ((await createRes.json()).data as { id: string }).id
    await ctx.dispose()

    await gotoReservations(page)
    await openWeeklySchedule(page)

    const row = page.getByTestId(`recurring-row-${ruleId}`)
    await expect(row, '作成した定期不可枠の行が一覧に表示されること').toBeVisible({ timeout: 15_000 })

    // 停止（一時停止トグル）
    const toggleBtn = page.getByTestId(`recurring-toggle-${ruleId}`)
    await toggleBtn.click()
    await expect(row, '停止後は行がopacity-60クラスで淡色表示になること').toHaveClass(/opacity-60/, { timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-rec-06a-toggle-paused.png', fullPage: true })

    // 再開
    await toggleBtn.click()
    await expect(row).not.toHaveClass(/opacity-60/, { timeout: 10_000 })

    // 編集
    await row.locator('button:has(.pi-pencil)').click()
    const editDialog = page.getByRole('dialog', { name: '予約不可を編集' })
    await expect(editDialog).toBeVisible({ timeout: 10_000 })
    const reasonInput = page.getByTestId('recurring-reason')
    await reasonInput.click()
    await reasonInput.press('ControlOrMeta+A')
    await reasonInput.press('Delete')
    await reasonInput.pressSequentially('E2E CRUD検証(編集後)', { delay: 10 })
    await editDialog.getByTestId('recurring-save').click()
    await expect(page.getByText('定期予約不可枠を更新しました')).toBeVisible({ timeout: 15_000 })
    await expect(row.getByText('事由: E2E CRUD検証(編集後)')).toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-rec-06b-edit-success.png', fullPage: true })

    // 削除（native confirm を自動承認）
    page.once('dialog', d => d.accept())
    await row.locator('button:has(.pi-trash)').click()
    await expect(page.getByText('定期予約不可枠を削除しました')).toBeVisible({ timeout: 15_000 })
    await expect(row, '削除後は行が消えること').not.toBeVisible({ timeout: 10_000 })

    // 実DB裏取り
    const verifyCtx = await playwrightRequest.newContext()
    const listRes = await verifyCtx.get(`${BE_API}/teams/${teamSlug}/reservation-recurring-blocked-times`, {
      headers: authHeaders(tokens.admin),
    })
    const list = (await listRes.json()).data as Array<{ id: string }>
    console.log(`[シナリオ6] 削除後の実DB一覧に対象ruleIdが含まれるか: ${list.some(r => r.id === ruleId)}`)
    expect(list.some(r => r.id === ruleId), '実DBからも削除されていること').toBe(false)
    await verifyCtx.dispose()
  })

  // ── シナリオ7: 409ガード ────────────────────────────────────────────────
  test('シナリオ7: 既存予約と衝突する枠はimpact警告＋登録disabled(UI一次防御)、直接APIでも409(RESERVATION_027)', async ({ page, tokens }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)
    const dialog = await openCreateRecurringDialog(page)
    await setRecurringDayAndTime(page, dayConflict.dayCode, '10:00', '10:30')
    await fillInput(page.getByTestId('recurring-reason'), 'E2E409検証(衝突枠)')

    await expect(
      page.getByText(/今後90日間に重複する予約が\s*\d+\s*件あります/),
      'impact警告カードが表示されること',
    ).toBeVisible({ timeout: 15_000 })
    const saveBtn = dialog.getByTestId('recurring-save')
    await expect(saveBtn, '衝突時は登録ボタンがdisabledであること(UI一次防御)').toBeDisabled()
    await page.screenshot({ path: 'test-results/rsv-rec-07-impact-warning.png', fullPage: true })

    // defense-in-depth: UIをバイパスした直接API呼びでもBEが409(RESERVATION_027)で拒否すること
    const directRes = await page.request.post(
      `${API_BASE_URL}/api/v1/teams/${teamSlug}/reservation-recurring-blocked-times`,
      {
        headers: { Authorization: `Bearer ${tokens.admin}` },
        data: { dayOfWeek: dayConflict.dayCode, startTime: '10:00:00', endTime: '10:30:00', reason: 'E2E409検証(直接API)', isPublic: false },
      },
    )
    const directBody = await directRes.json() as { error?: { code?: string } }
    console.log(`[シナリオ7] 直接API呼び結果: status=${directRes.status()} code=${directBody.error?.code}`)
    expect(directRes.status(), 'UIをバイパスした直接APIでも409であること(BE最終防御)').toBe(409)
    expect(directBody.error?.code, 'エラーコードはRESERVATION_027であること').toBe('RESERVATION_027')
  })

  // ── シナリオ8: 既存テンプレの非退行 ──────────────────────────────────────────
  test('シナリオ8: 従来の枠テンプレ（週間スケジュール内・青系セクション）の作成・表示・削除が壊れていない', async ({ page }) => {
    await gotoReservations(page)
    await openWeeklySchedule(page)

    await page.getByTestId('template-add').click()
    const dialog = page.getByRole('dialog', { name: 'テンプレートを追加' })
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    await dialog.locator(`button[data-day="${dayRegress.dayCode}"]`).click()
    await selectDropdown(page, dialog.getByRole('combobox').nth(1), '14:00')
    await selectDropdown(page, dialog.getByRole('combobox').nth(2), '15:00')
    await dialog.getByTestId('template-save').click()

    const resultToast = page.getByText(/保存し、28日先までの枠を\d+件作成しました/)
    await expect(resultToast, 'テンプレ保存＝自動生成トーストが従来通り出ること').toBeVisible({ timeout: 20_000 })
    const text = (await resultToast.textContent()) ?? ''
    const m = text.match(/保存し、28日先までの枠を(\d+)件作成しました/)
    const generated = Number(m?.[1] ?? -1)
    console.log(`[シナリオ8] テンプレ自動生成結果: generated=${generated} (raw="${text}")`)
    expect(generated, '生成0件は不合格（テンプレ機能の回帰）').toBeGreaterThan(0)

    await expect(page.getByText('14:00 - 15:00')).toBeVisible({ timeout: 10_000 })
    await page.screenshot({ path: 'test-results/rsv-rec-08a-template-created.png', fullPage: true })

    // 削除（従来UIのまま壊れていないこと）
    // 【自己バグ修正】`page.locator('div').filter({hasText}).last()` は該当テキストを含む
    // 祖先divが多数マッチしてしまい(accordionラッパー等)、.last()がボタンを持たない内側の
    // div(class="min-w-0 flex-1")を拾って編集/削除ボタンへの到達が永久に不能になり、
    // 240sのテストタイムアウトに達していた(製品コードは無関係)。
    // テンプレ行に固有の class（枠テンプレ=青系 border-blue-200・定期不可枠=赤系 border-red-200 と
    // 意図的に色分けされている実装）でスコープを絞り込み、行全体(編集/削除ボタンを含む)を確実に取る。
    const templateRow = page.locator('div.border-blue-200').filter({ hasText: '14:00 - 15:00' }).first()
    await expect(templateRow, 'テンプレ行(青系)が一意に特定できること').toBeVisible({ timeout: 10_000 })
    page.once('dialog', d => d.accept())
    await templateRow.locator('button:has(.pi-trash)').first().click()
    await expect(page.getByText('テンプレートを削除しました')).toBeVisible({ timeout: 15_000 }).catch(async () => {
      // 文言が i18n キー差異で異なる場合に備え、行が消えたことそのもので判定するフォールバック
      await expect(page.getByText('14:00 - 15:00')).not.toBeVisible({ timeout: 10_000 })
    })
    await page.screenshot({ path: 'test-results/rsv-rec-08b-template-deleted.png', fullPage: true })
  })
})
