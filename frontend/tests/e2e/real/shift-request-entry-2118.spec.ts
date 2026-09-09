/**
 * 実機E2E: シフト希望の導線整備と入力事故の防止（CMP-260908-2118）
 *
 * 対象コミット: fdb141e3c6 / e269d4b27c
 *
 * 確認する 7 点（殿の指示）:
 *   1. チームのシフト表一覧の行内ボタンを押しても希望ダイアログが同時に開かない
 *   2. シフトボードでエラートーストが出ず、GET /shifts/positions が 200、ポジション列が空でない
 *   3. 「シフトボード」ボタンは ADMIN / DEPUTY_ADMIN のみ（一般メンバーには出ない）
 *   4. /my/shift-request の未入力既定が「できれば休みたい」、送信前確認モーダルの 3 区分と警告
 *   5. 受付不可のシフト表は /my/shift-request の選択肢に出ず、一覧では「希望の受付は終了しました」
 *   6. /my/shifts のリンクが /shift/{id} で 404 にならない・common.edit / common.save が生キーでない
 *   7. /my/shift-availability への導線が存在し、実際に開ける
 *
 * ロール横断:
 *   - TEAM_ADMIN … e2e-dummy-1（田中太郎 / id=3）。fc-u-18 の実効ロールが素の ADMIN。
 *     e2e-admin はプラットフォーム SYSTEM_ADMIN を併せ持ち roleName が 'SYSTEM_ADMIN' に
 *     解決されるため、`isScopeAdmin`（ADMIN / DEPUTY_ADMIN の等値比較）を満たさない。
 *     ボード導線の「正」の視点はこの素の ADMIN で踏む。
 *   - MEMBER … e2e-user（id=23）。負の視点（ボタンが出ない）と希望提出フロー。
 *
 * 前提データは e2e-admin の API で作り、後始末で必ず消す（共有チーム fc-u-18 を使うため）。
 */
import {
  test as base,
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`

const PASSWORD = 'TestPass2026!'
const SEED_ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
/** fc-u-18 の実効ロールが素の ADMIN であるユーザー（田中太郎）。 */
const TEAM_ADMIN_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
const MEMBER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'

const TEAM_SLUG = 'fc-u-18'

const RUN_TAG = `R${Date.now().toString(36)}`
const TITLE_OPEN = `導線2118_収集中_${RUN_TAG}`
const TITLE_CLOSED = `導線2118_調整中_${RUN_TAG}`
const POSITION_NAME = `導線2118_${RUN_TAG}`

// i18n（ja）の実文言
const LABEL_BULK = 'まとめて希望を出す'
const LABEL_BOARD = 'シフトボード'
const LABEL_CLOSED = '希望の受付は終了しました'
const LABEL_WEEKLY_DEFAULT = '曜日ごとの既定を設定'
const LABEL_REQUEST_DIALOG = 'シフト希望を提出'
const LABEL_STRONG_REST = 'できれば休み'
const LABEL_CONFIRM_TITLE = 'この内容で送信しますか？'
const LABEL_FILLED = '入力済みの日'
const LABEL_UNTOUCHED = '未入力の日'
const LABEL_UNTOUCHED_NOTICE = '人手が足りない場合はシフトの依頼が来ることがあります'
const LABEL_ERROR_TOAST = 'エラーが発生しました'

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

async function login(ctx: APIRequestContext, email: string): Promise<string> {
  const res = await ctx.post(`${BE_API}/auth/login`, {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password: PASSWORD },
  })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

function todayIsoJst(): string {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

function addDaysIso(baseIso: string, days: number): string {
  const [y, m, d] = baseIso.split('-').map(Number)
  const dt = new Date(Date.UTC(y!, m! - 1, d!))
  dt.setUTCDate(dt.getUTCDate() + days)
  return dt.toISOString().slice(0, 10)
}

interface Fixture {
  teamNumericId: number
  teamName: string
  positionId: number
  openScheduleId: number
  closedScheduleId: number
  slotIds: number[]
  dates: string[]
  memberUserId: number
  createdRequestIds: number[]
}

let apiCtx: APIRequestContext
let adminToken: string
let fx: Fixture

const test = base.extend<Record<string, never>>({
  // 各テストで役者ごとにログインするため、既定の storageState は使わない
  // eslint-disable-next-line no-empty-pattern -- Playwright の fixture 定義形式
  storageState: async ({}, use) => {
    await use(undefined)
  },
})

// dev サーバーの初回ルートコンパイルが重く、既定 60s では役者 2 人ぶんの
// ページ遷移が入るテストが落ちる（症状隠しではなく実測に基づく余裕の確保）。
test.beforeEach(({}, testInfo) => {
  testInfo.setTimeout(180_000)
})

/** 指定ユーザーでログイン済みのページを開く。 */
async function openAs(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE_URL })
  return page
}

test.beforeAll(async () => {
  apiCtx = await playwrightRequest.newContext()
  adminToken = await login(apiCtx, SEED_ADMIN_EMAIL)

  const teamRes = await apiCtx.get(`${BE_API}/teams/${TEAM_SLUG}`, {
    headers: authHeaders(adminToken),
  })
  expect(teamRes.status(), 'チーム取得').toBe(200)
  const team = (await teamRes.json()).data as {
    numericId: number
    basicInfo?: { name?: string }
    name?: string
  }
  const teamNumericId = team.numericId
  const teamName = team.basicInfo?.name ?? team.name!

  // 役者の実効ロールを実測で裏取りする（シードの揺れで負の視点が無効化されるのを防ぐ）
  for (const [email, expected] of [
    [TEAM_ADMIN_EMAIL, 'ADMIN'],
    [MEMBER_EMAIL, 'MEMBER'],
  ] as const) {
    const ctx = await playwrightRequest.newContext()
    const token = await login(ctx, email)
    const res = await ctx.get(`${BE_API}/teams/${TEAM_SLUG}/me/permissions`, {
      headers: authHeaders(token),
    })
    expect(res.status()).toBe(200)
    expect(
      ((await res.json()).data as { roleName: string }).roleName,
      `${email} の ${TEAM_SLUG} における実効ロール`,
    ).toBe(expected)
    await ctx.dispose()
  }

  const meRes = await (async () => {
    const ctx = await playwrightRequest.newContext()
    const token = await login(ctx, MEMBER_EMAIL)
    const r = await ctx.get(`${BE_API}/users/me`, { headers: authHeaders(token) })
    const id = ((await r.json()).data as { id: number }).id
    await ctx.dispose()
    return id
  })()

  // 前回の中断で残った本 spec 由来の残骸を掃除する（共有チームなので放置できない）。
  // 対象は本 spec の接頭辞を持つものだけ。ワーカー再起動で afterAll が走らないことがある。
  {
    const listRes = await apiCtx.get(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
      headers: authHeaders(adminToken),
    })
    const list = (await listRes.json()).data as Array<{ id: number; content: { title: string } }>
    for (const s of list) {
      if (!s.content?.title?.startsWith('導線2118_')) continue
      const r = await apiCtx.delete(`${BE_API}/shifts/schedules/${s.id}`, {
        headers: authHeaders(adminToken),
      })
      console.log(`[PURGE] schedule ${s.id} (${s.content.title}) -> ${r.status()}`)
    }
    const posList = await apiCtx.get(`${BE_API}/shifts/positions?teamId=${teamNumericId}`, {
      headers: authHeaders(adminToken),
    })
    const positions = (await posList.json()).data as Array<{ id: number; name: string }>
    for (const p of positions) {
      if (!p.name?.startsWith('導線2118_')) continue
      const r = await apiCtx.delete(`${BE_API}/shifts/positions/${p.id}`, {
        headers: authHeaders(adminToken),
      })
      console.log(`[PURGE] position ${p.id} (${p.name}) -> ${r.status()}`)
    }
  }

  // ポジション
  const posRes = await apiCtx.post(`${BE_API}/shifts/positions?teamId=${teamNumericId}`, {
    headers: authHeaders(adminToken),
    data: { name: POSITION_NAME, displayOrder: 1 },
  })
  expect(posRes.status(), `ポジション作成: ${await posRes.text()}`).toBeLessThan(300)
  const positionId = ((await posRes.json()).data as { id: number }).id

  const start = addDaysIso(todayIsoJst(), 3)
  const dates = [start, addDaysIso(start, 1)]

  async function createSchedule(title: string, s: string, e: string): Promise<number> {
    const res = await apiCtx.post(`${BE_API}/shifts/schedules?teamId=${teamNumericId}`, {
      headers: authHeaders(adminToken),
      data: { title, startDate: s, endDate: e },
    })
    expect(res.status(), `シフト表作成(${title}): ${await res.text()}`).toBeLessThan(300)
    return ((await res.json()).data as { id: number }).id
  }

  async function createSlot(scheduleId: number, slotDate: string): Promise<number> {
    const res = await apiCtx.post(`${BE_API}/shifts/schedules/${scheduleId}/slots`, {
      headers: authHeaders(adminToken),
      data: {
        slotDate,
        startTime: '09:00:00',
        endTime: '12:00:00',
        positionId,
        requiredCount: 2,
        note: null,
      },
    })
    expect(res.status(), `枠作成: ${await res.text()}`).toBeLessThan(300)
    return ((await res.json()).data as { id: number }).id
  }

  async function transition(scheduleId: number, status: string): Promise<void> {
    const res = await apiCtx.post(
      `${BE_API}/shifts/schedules/${scheduleId}/transition?status=${status}`,
      { headers: authHeaders(adminToken) },
    )
    expect(res.status(), `遷移 ${scheduleId}→${status}: ${await res.text()}`).toBeLessThan(300)
  }

  const openScheduleId = await createSchedule(TITLE_OPEN, dates[0]!, dates[1]!)
  const slotIds = [await createSlot(openScheduleId, dates[0]!), await createSlot(openScheduleId, dates[1]!)]
  await transition(openScheduleId, 'COLLECTING')

  const closedScheduleId = await createSchedule(TITLE_CLOSED, dates[0]!, dates[1]!)
  await createSlot(closedScheduleId, dates[0]!)
  await transition(closedScheduleId, 'COLLECTING')
  await transition(closedScheduleId, 'ADJUSTING')

  fx = {
    teamNumericId,
    teamName,
    positionId,
    openScheduleId,
    closedScheduleId,
    slotIds,
    dates,
    memberUserId: meRes,
    createdRequestIds: [],
  }
  console.log(`[FIXTURE] team=${teamNumericId} open=${openScheduleId} closed=${closedScheduleId}`)
})

test.afterAll(async () => {
  if (!fx) return
  for (const id of [fx.openScheduleId, fx.closedScheduleId]) {
    const res = await apiCtx.delete(`${BE_API}/shifts/schedules/${id}`, {
      headers: authHeaders(adminToken),
    })
    console.log(`[CLEANUP] DELETE schedule ${id} -> ${res.status()}`)
  }
  const res = await apiCtx.delete(`${BE_API}/shifts/positions/${fx.positionId}`, {
    headers: authHeaders(adminToken),
  })
  console.log(`[CLEANUP] DELETE position ${fx.positionId} -> ${res.status()}`)
  await apiCtx.dispose()
})

/**
 * 「シフト希望を提出」ダイアログが一瞬でも現れたら記録する検出器を仕掛ける。
 * SPA 遷移後も同一 window なので、遷移してから読み出せる。
 */
async function installDialogDetector(page: Page): Promise<void> {
  await page.evaluate((label) => {
    const w = window as unknown as { __shiftDialogSeen?: boolean }
    w.__shiftDialogSeen = false
    const check = () => {
      if (document.body.innerText.includes(label)) w.__shiftDialogSeen = true
    }
    new MutationObserver(check).observe(document.body, { subtree: true, childList: true })
    check()
  }, LABEL_REQUEST_DIALOG)
}

async function dialogWasSeen(page: Page): Promise<boolean> {
  return page.evaluate(
    () => (window as unknown as { __shiftDialogSeen?: boolean }).__shiftDialogSeen === true,
  )
}

function rowOf(page: Page, title: string) {
  // 行は「タイトル・期間・ステータス・ボタン群」を含む枠
  return page.locator('div.rounded-lg.border').filter({ hasText: title }).first()
}

/**
 * /my/shift-request を開き、チーム選択ステップを抜けてシフト表選択まで進む。
 * 所属チームが 1 つのときは自動選択されるため、カードが出た場合のみ踏む。
 */
async function gotoBulkRequestScheduleSelect(page: Page): Promise<void> {
  await page.goto('/my/shift-request')
  await waitForHydration(page)
  const teamCard = page.getByText(fx.teamName, { exact: true }).first()
  await expect(teamCard).toBeVisible({ timeout: 30_000 })
  // dev サーバーではハイドレーション直後のクリックが握られないことがあるため、
  // 「チーム選択ステップを抜けた」ことを条件にクリックを繰り返す。
  await expect(async () => {
    if (await teamCard.isVisible()) await teamCard.click()
    await expect(teamCard).toBeHidden({ timeout: 5_000 })
  }).toPass({ timeout: 90_000 })
}

async function gotoTeamShifts(page: Page): Promise<void> {
  await page.goto(`/teams/${TEAM_SLUG}/shifts`)
  await expect(page.getByText(TITLE_OPEN)).toBeVisible({ timeout: 20_000 })
}

// ============================================================================
// 1 / 3 / 5 / 7: チームのシフト表一覧
// ============================================================================

test('①-a 管理者: 行内「まとめて希望を出す」で希望ダイアログが開かない', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoTeamShifts(page)
  await installDialogDetector(page)

  const row = rowOf(page, TITLE_OPEN)
  await row.getByRole('button', { name: LABEL_BULK }).click()

  await expect(page).toHaveURL(/\/my\/shift-request/, { timeout: 15_000 })
  expect(await dialogWasSeen(page), '希望ダイアログが同時に開いていないこと').toBe(false)
  await page.context().close()
})

test('①-b 管理者: 行内「シフトボード」で希望ダイアログが開かない', async ({ browser }) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoTeamShifts(page)
  await installDialogDetector(page)

  const row = rowOf(page, TITLE_OPEN)
  await row.getByRole('button', { name: LABEL_BOARD }).click()

  await expect(page).toHaveURL(
    new RegExp(`/teams/${TEAM_SLUG}/shifts/${fx.openScheduleId}/board`),
    { timeout: 15_000 },
  )
  expect(await dialogWasSeen(page), '希望ダイアログが同時に開いていないこと').toBe(false)
  await page.context().close()
})

test('①-c 行本体のクリックでは従来どおり希望ダイアログが開く', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoTeamShifts(page)
  await rowOf(page, TITLE_OPEN).getByText(TITLE_OPEN).click()
  await expect(page.getByText(LABEL_REQUEST_DIALOG)).toBeVisible({ timeout: 15_000 })
  await page.context().close()
})

test('③-a 管理者には「シフトボード」ボタンが出る', async ({ browser }) => {
  const adminPage = await openAs(browser, TEAM_ADMIN_EMAIL)
  await gotoTeamShifts(adminPage)
  await expect(
    rowOf(adminPage, TITLE_OPEN).getByRole('button', { name: LABEL_BOARD }),
    'ADMIN にはシフトボードボタンが出る',
  ).toBeVisible()
  await adminPage.context().close()
})

test('③-b 一般メンバーには「シフトボード」ボタンが出ない', async ({ browser }) => {
  const memberPage = await openAs(browser, MEMBER_EMAIL)
  await gotoTeamShifts(memberPage)
  await expect(
    memberPage.getByRole('button', { name: LABEL_BOARD }),
    '一般メンバーにはシフトボードボタンが出ない',
  ).toHaveCount(0)
  // まとめて希望を出すは一般メンバーにも出る（受付中の行）
  await expect(rowOf(memberPage, TITLE_OPEN).getByRole('button', { name: LABEL_BULK })).toBeVisible()
  await memberPage.context().close()
})

test('⑤-a 受付不可（ADJUSTING）の行は「希望の受付は終了しました」で希望ボタンが出ない', async ({
  browser,
}) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoTeamShifts(page)
  const closedRow = rowOf(page, TITLE_CLOSED)
  await expect(closedRow).toBeVisible()
  await expect(closedRow.getByText(LABEL_CLOSED)).toBeVisible()
  await expect(closedRow.getByRole('button', { name: LABEL_BULK })).toHaveCount(0)
  await page.context().close()
})

test('⑦ 「曜日ごとの既定を設定」から /my/shift-availability を開ける', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoTeamShifts(page)
  await page.getByRole('button', { name: LABEL_WEEKLY_DEFAULT }).click()
  await expect(page).toHaveURL(/\/my\/shift-availability/, { timeout: 15_000 })
  await expect(page.getByText('404')).toHaveCount(0)
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: 'test-results/2118-shift-availability.png', fullPage: true })
  await page.context().close()
})

// ============================================================================
// 2: シフトボード
// ============================================================================

test('② シフトボード: positions が 200・ポジション列が空でない・エラートーストが出ない', async ({
  browser,
}) => {
  const page = await openAs(browser, TEAM_ADMIN_EMAIL)
  const positionsResponses: Array<{ url: string; status: number }> = []
  page.on('response', (res) => {
    if (res.url().includes('/api/v1/shifts/positions')) {
      positionsResponses.push({ url: res.url(), status: res.status() })
    }
  })

  await page.goto(`/teams/${TEAM_SLUG}/shifts/${fx.openScheduleId}/board`)
  await waitForHydration(page)
  await expect(page.getByText(TITLE_OPEN)).toBeVisible({ timeout: 30_000 })
  // ポジション列が描画されるまで待ってから通信結果を見る（描画待ちを先にしないと
  // 「まだ呼ばれていないだけ」を「呼ばれていない」と誤判定する）
  await expect(page.getByText(POSITION_NAME).first(), 'ポジション列が空でないこと').toBeVisible({
    timeout: 30_000,
  })
  await page.waitForLoadState('networkidle')

  console.log('[NET] positions:', JSON.stringify(positionsResponses))
  expect(positionsResponses.length, 'GET /shifts/positions が呼ばれること').toBeGreaterThan(0)
  for (const r of positionsResponses) {
    expect(r.status, `${r.url} が 200 であること`).toBe(200)
  }

  await expect(page.getByText(LABEL_ERROR_TOAST)).toHaveCount(0)
  await page.screenshot({ path: 'test-results/2118-shift-board.png', fullPage: true })
  await page.context().close()
})

// ============================================================================
// 4 / 5: 一括入力ページ
// ============================================================================

test('④ 一括入力: 未入力既定が「できれば休みたい」・送信前確認モーダルの3区分と警告', async ({
  browser,
}) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoBulkRequestScheduleSelect(page)
  const scheduleCard = page.getByText(TITLE_OPEN).first()
  await expect(scheduleCard).toBeVisible({ timeout: 20_000 })
  await scheduleCard.click()

  // 未入力スロットの既定が STRONG_REST であること
  const strongRest = page.getByRole('radio', { name: LABEL_STRONG_REST }).first()
  await expect(strongRest).toBeVisible({ timeout: 20_000 })
  await expect(strongRest, '未入力の既定は「できれば休みたい」').toHaveAttribute(
    'aria-checked',
    'true',
  )

  // プレビュー → 送信 → 確認モーダル
  await page.getByRole('button', { name: '提出前確認' }).click()
  await page.getByRole('button', { name: '提出', exact: true }).click()

  await expect(page.getByText(LABEL_CONFIRM_TITLE)).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(LABEL_FILLED)).toBeVisible()
  await expect(page.getByText(LABEL_UNTOUCHED, { exact: false }).first()).toBeVisible()
  await expect(page.getByText(LABEL_UNTOUCHED_NOTICE, { exact: false })).toBeVisible()
  await page.screenshot({ path: 'test-results/2118-submit-confirm.png', fullPage: true })
  await page.context().close()
})

test('⑤-b 受付不可のシフト表は /my/shift-request の選択肢に出ない', async ({ browser }) => {
  const page = await openAs(browser, MEMBER_EMAIL)
  await gotoBulkRequestScheduleSelect(page)
  await expect(page.getByText(TITLE_OPEN)).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(TITLE_CLOSED), 'ADJUSTING は選択肢に出ない').toHaveCount(0)
  await page.context().close()
})

// ============================================================================
// 6: /my/shifts のリンクと生キー
// ============================================================================

test('⑥ /my/shifts のリンクが /shift/{id} で 404 にならない・生キーが出ない', async ({
  browser,
}) => {
  // 前提: メンバーの希望を 1 件作る（API。後始末で消す）
  const memberCtx = await playwrightRequest.newContext()
  const memberToken = await login(memberCtx, MEMBER_EMAIL)
  const submitRes = await memberCtx.post(`${BE_API}/shifts/requests`, {
    headers: authHeaders(memberToken),
    data: { slotId: fx.slotIds[0], preference: 'AVAILABLE', note: null },
  })
  const submitted = submitRes.ok()
  let requestId: number | null = null
  if (submitted) {
    requestId = ((await submitRes.json()).data as { id: number }).id
  } else {
    console.log(`[WARN] 希望作成に失敗: ${submitRes.status()} ${await submitRes.text()}`)
  }

  try {
    const page = await openAs(browser, MEMBER_EMAIL)
    await page.goto('/my/shifts')
    await page.waitForLoadState('networkidle')

    const bodyText = (await page.locator('body').innerText()) ?? ''
    expect(bodyText, '/my/shifts に生キーが出ていないこと').not.toContain('common.edit')
    expect(bodyText, '/my/shifts に生キーが出ていないこと').not.toContain('common.save')

    if (submitted) {
      const link = page.locator(`a[href="/shift/${fx.openScheduleId}"]`).first()
      await expect(link, `/shift/${fx.openScheduleId} へのリンクがあること`).toBeVisible({
        timeout: 15_000,
      })
      await link.click()
      await expect(page).toHaveURL(new RegExp(`/shift/${fx.openScheduleId}`), { timeout: 15_000 })
      await expect(page.getByText(TITLE_OPEN)).toBeVisible({ timeout: 15_000 })
    }
    await page.screenshot({ path: 'test-results/2118-my-shifts.png', fullPage: true })
    await page.context().close()

    // 管理者視点: /shift/{id} の「編集」ボタンが日本語で出る（common.edit の生キーでない）
    const adminPage = await openAs(browser, TEAM_ADMIN_EMAIL)
    await adminPage.goto(`/shift/${fx.openScheduleId}`)
    await expect(adminPage.getByText(TITLE_OPEN)).toBeVisible({ timeout: 20_000 })
    const adminBody = await adminPage.locator('body').innerText()
    expect(adminBody, 'common.edit の生キーが出ていないこと').not.toContain('common.edit')
    expect(adminBody, 'common.save の生キーが出ていないこと').not.toContain('common.save')
    await expect(adminPage.getByRole('button', { name: '編集' }).first()).toBeVisible()
    await adminPage.getByRole('button', { name: '編集' }).first().click()
    const adminBodyAfter = await adminPage.locator('body').innerText()
    expect(adminBodyAfter, '編集ダイアログに common.save の生キーが出ていないこと').not.toContain(
      'common.save',
    )
    await expect(adminPage.getByRole('button', { name: '保存' }).first()).toBeVisible()
    await adminPage.screenshot({ path: 'test-results/2118-shift-detail-edit.png', fullPage: true })
    await adminPage.context().close()
  } finally {
    if (requestId !== null) {
      const del = await memberCtx.delete(`${BE_API}/shifts/requests/${requestId}`, {
        headers: authHeaders(memberToken),
      })
      console.log(`[CLEANUP] DELETE request ${requestId} -> ${del.status()}`)
    }
    await memberCtx.dispose()
  }
})
