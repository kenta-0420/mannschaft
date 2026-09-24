/**
 * F08.7 シフト予算 閾値超過警告 — 実機 E2E（Issue #2990 L13 再検証）
 *
 * ── 何を検証するか ───────────────────────────────────────────────────────────
 * Issue #2990 L13 は「通知の送信を業務トランザクションの外へ出す」是正である。
 * {@code ThresholdAlertEvaluationService} は警告レコードを INSERT したあと
 * {@code BudgetThresholdAlertTriggeredEvent} を publish するだけにとどめ、
 * 実際の配送は {@code ShiftBudgetThresholdAlertNotificationListener} が
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ＋ {@code @Async("event-pool")}
 * で受け取って行う。
 *
 * 実機での検証観点は 3 つある。
 *   ① 通知が実際に届く      — 受信者の通知一覧画面に警告通知が現れる
 *   ② 通知失敗が本処理を巻き添えにしない — （PR #3188 以前に検証済み。本スペックの射程外）
 *   ③ 通知はコミット後に飛ぶ — 業務データ（シフト確定）が画面で確定済みになってから
 *                              通知が現れる。加えて BE ログで配送が event-pool スレッドの
 *                              AFTER_COMMIT リスナー上で走っていることを裏取りする。
 *
 * ── シナリオ ─────────────────────────────────────────────────────────────────
 *   前提データ（API で作成。UI 操作の対象そのものではない）:
 *     - 組織 9 / チーム 1 に予算割当（25,000 円）を作る
 *     - チーム 1 にシフトスケジュールを作り、8 時間枠に受信者（時給 3,000 円）を割り当てる
 *       → 消化 24,000 円 = 96% となり 80% 閾値のみが発火する
 *     - スケジュールを ADJUSTING まで進める
 *
 *   UI 操作（対象操作。page.request で代替してはならない）:
 *     - 管理者がブラウザで /shift/{id} を開き「確定公開」ボタンを押す
 *     - 受信者がブラウザで /notifications を開き警告通知を見つける
 *     - 各ロールがブラウザで /admin/shift-budget/alerts を開く
 *
 * ── ロール横断 ───────────────────────────────────────────────────────────────
 *   権限あり  : e2e-user (userId=23) — 組織 9 の ADMIN。閾値警告の受信者であり
 *               BUDGET_VIEW を持つため警告履歴を閲覧できる
 *   権限なし  : e2e-dummy-6 (userId=8) — 組織 9 の一般 MEMBER。BUDGET_VIEW を持たず
 *               警告履歴 API が 403（SHIFT_BUDGET_018）。受信者でもない
 *   他テナント: e2e-outsider (userId=90245) — 組織 9 に所属しない。同じく 403
 *
 * ── 環境前提 ─────────────────────────────────────────────────────────────────
 *   - BE の feature flag {@code feature.shift-budget.enabled} が true であること
 *   - 組織 9 に会計年度 / 費目が存在すること（既定は id=1 / id=1。env で上書き可）
 *   - チーム 1 の受信者に時給が登録されていること（shift_hourly_rates）
 *   - BE ログファイルが読めること（既定 /tmp/be8081.log。BE_LOG_PATH で上書き可）
 *
 * 実行例:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/f087-shift-budget-threshold-alert.real.spec.ts
 */

import { existsSync, readFileSync } from 'node:fs'
import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// 実ログインで認証を作るため storageState には依存しない
test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const BE_LOG_PATH = process.env.BE_LOG_PATH ?? '/tmp/be8081.log'

const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN = { email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local', password: PASSWORD }
const RECIPIENT = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: PASSWORD }
const NO_PERMISSION = { email: 'e2e-dummy-6@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

/** 検証対象テナント。チーム 1 は team_org_memberships 上で組織 9 に属する。 */
const ORG_ID = Number(process.env.L13_ORG_ID ?? 9)
const TEAM_ID = Number(process.env.L13_TEAM_ID ?? 1)
const ORG_NAME = 'E2E 検証組織'
/** 組織 9 に用意済みの会計年度 / 費目。割当作成が 201 になることで妥当性は担保される。 */
const FISCAL_YEAR_ID = Number(process.env.L13_FISCAL_YEAR_ID ?? 1)
const BUDGET_CATEGORY_ID = Number(process.env.L13_BUDGET_CATEGORY_ID ?? 1)

/** 割当額。8h × 時給 3,000 円 = 24,000 円の消化で 96% となり 80% 閾値のみ発火する。 */
const ALLOCATED_AMOUNT = 25000
const EXPECTED_THRESHOLD = 80
const ALERT_TITLE = `シフト予算 警告 (${EXPECTED_THRESHOLD}%)`
const ALERT_BODY = `予算 ${EXPECTED_THRESHOLD}% に到達しました`

test.describe.configure({ mode: 'serial' })
// 前提データ作成 → UI 操作 → ロール横断確認 と段取りが長いため既定より長めに取る
test.setTimeout(180_000)

// ---------------------------------------------------------------------------
// API ヘルパー（ログイン・前提データ作成・後始末にのみ使う）
// ---------------------------------------------------------------------------

async function login(api: APIRequestContext, cred: { email: string; password: string }): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: cred })
  expect(res.status(), `login(${cred.email}) は 200`).toBe(200)
  return ((await res.json()) as { data: { accessToken: string } }).data.accessToken
}

function budgetHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'X-Organization-Id': String(ORG_ID),
  }
}

interface Allocation {
  id: number
  team_id: number | null
  period_start: string
  period_end: string
}

/** 月初 / 月末を YYYY-MM-DD で返す。 */
function monthBounds(base: Date, monthOffset: number): { start: string; end: string; mid: string } {
  const y = base.getFullYear()
  const m = base.getMonth() + monthOffset
  const first = new Date(Date.UTC(y, m, 1))
  const last = new Date(Date.UTC(y, m + 1, 0))
  const mid = new Date(Date.UTC(y, m, 15))
  const iso = (d: Date) => d.toISOString().slice(0, 10)
  return { start: iso(first), end: iso(last), mid: iso(mid) }
}

/**
 * 既存の生存割当と期間が重ならない月を選ぶ。
 *
 * <p>{@code ShiftBudgetAllocationRepository#findContainingPeriod} は {@code Optional} を返すため、
 * 同一 (組織, チーム) で期間が重なる生存割当が 2 件あると実行時に例外になる。
 * 共有 dev DB で繰り返し実行できるよう、空いている月を選んで衝突を避ける。</p>
 */
async function pickFreeMonth(
  api: APIRequestContext,
  token: string,
): Promise<{ start: string; end: string; mid: string }> {
  const res = await api.get(`${BE_API}/shift-budget/allocations?page=0&size=200`, {
    headers: budgetHeaders(token),
  })
  expect(res.status(), '割当一覧は 200').toBe(200)
  const items = ((await res.json()) as { data: { items: Allocation[] } }).data.items
  const taken = new Set(
    items
      .filter(a => a.team_id === TEAM_ID || a.team_id === null)
      .map(a => a.period_start.slice(0, 7)),
  )

  const now = new Date()
  for (let offset = 1; offset <= 48; offset++) {
    const bounds = monthBounds(now, offset)
    if (!taken.has(bounds.start.slice(0, 7))) return bounds
  }
  throw new Error('48 ヶ月先まで空き月が無い。共有 dev DB の shift_budget_allocations を整理すること')
}

// ---------------------------------------------------------------------------
// ブラウザヘルパー
// ---------------------------------------------------------------------------

/** 実ログイン済み、かつ組織スコープを注入した Page を作る。 */
async function openAs(
  browser: Browser,
  cred: { email: string; password: string },
  options: { organizationScope?: boolean } = {},
): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  if (options.organizationScope) {
    await page.addInitScript(
      (data) => {
        localStorage.setItem(
          'currentScope',
          JSON.stringify({ type: 'organization', id: String(data.id), name: data.name }),
        )
      },
      { id: ORG_ID, name: ORG_NAME },
    )
  }
  await loginViaApi(page, cred, { apiBaseUrl: API_BASE_URL })
  return page
}

async function closePage(page: Page): Promise<void> {
  await page.context().close()
}

// ---------------------------------------------------------------------------
// テスト本体
// ---------------------------------------------------------------------------

let api: APIRequestContext
let adminToken = ''
let allocationId = 0
let scheduleId = 0
let slotDate = ''
/** テスト 1 の実行前に記録した BE ログの長さ。以降の追記分だけを検査する。 */
let logOffset = 0

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await login(api, ADMIN)

  const period = await pickFreeMonth(api, adminToken)
  slotDate = period.mid

  // --- 予算割当 ---
  const allocRes = await api.post(`${BE_API}/shift-budget/allocations`, {
    headers: budgetHeaders(adminToken),
    data: {
      team_id: TEAM_ID,
      fiscal_year_id: FISCAL_YEAR_ID,
      budget_category_id: BUDGET_CATEGORY_ID,
      period_start: period.start,
      period_end: period.end,
      allocated_amount: ALLOCATED_AMOUNT,
      currency: 'JPY',
      note: 'L13 実機E2E',
    },
  })
  expect(allocRes.status(), `割当作成は 201（本文: ${await allocRes.text()}）`).toBe(201)
  allocationId = ((await allocRes.json()) as { data: { id: number } }).data.id

  // --- シフトスケジュール ---
  const scheduleRes = await api.post(`${BE_API}/shifts/schedules?teamId=${TEAM_ID}`, {
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    data: {
      title: `L13実機E2E-${Date.now()}`,
      periodType: 'MONTHLY',
      startDate: period.start,
      endDate: period.end,
    },
  })
  expect(scheduleRes.status(), 'スケジュール作成は 201').toBe(201)
  scheduleId = ((await scheduleRes.json()) as { data: { id: number } }).data.id

  // --- 8 時間枠 ---
  const slotRes = await api.post(`${BE_API}/shifts/schedules/${scheduleId}/slots`, {
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    data: { slotDate, startTime: '09:00:00', endTime: '17:00:00', requiredCount: 1 },
  })
  expect(slotRes.status(), 'スロット作成は 201').toBe(201)
  const slotId = ((await slotRes.json()) as { data: { id: number } }).data.id

  // --- 受信者を枠に割り当てる ---
  const recipientToken = await login(api, RECIPIENT)
  const meRes = await api.get(`${BE_API}/users/me`, {
    headers: { Authorization: `Bearer ${recipientToken}` },
  })
  expect(meRes.status(), '/users/me は 200').toBe(200)
  const recipientUserId = ((await meRes.json()) as { data: { id: number } }).data.id

  const assignRes = await api.patch(`${BE_API}/shifts/slots/${slotId}/assignments`, {
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    data: { addUserIds: [recipientUserId], removeUserIds: [], slotVersion: 0 },
  })
  expect(assignRes.status(), '割当更新は 200').toBe(200)

  // --- 確定公開の一歩手前まで進める（公開そのものは UI で行う） ---
  for (const status of ['COLLECTING', 'ADJUSTING']) {
    const res = await api.post(`${BE_API}/shifts/schedules/${scheduleId}/transition?status=${status}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(res.status(), `${status} への遷移は 200`).toBe(200)
  }
})

test.afterAll(async () => {
  if (scheduleId) {
    await api.delete(`${BE_API}/shifts/schedules/${scheduleId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
  }
  if (allocationId) {
    const res = await api.delete(`${BE_API}/shift-budget/allocations/${allocationId}`, {
      headers: budgetHeaders(adminToken),
    })
    if (res.status() !== 204) {
      // 手動アーカイブでは ShiftArchivedEvent が publish されず PLANNED 消化が残るため、
      // 製品側に消化を取り消す導線が無く 409 (SHIFT_BUDGET_012) になる。
      // 次回実行は pickFreeMonth が別の月を選ぶので衝突はしない。
      console.warn(
        `[L13 後始末] 割当 ${allocationId} を削除できなかった: ${res.status()} ${await res.text()}`,
      )
    }
  }
  await api.dispose()
})

test('L13-01: シフト確定公開 → 閾値警告通知が受信者の通知一覧に現れ、配送は AFTER_COMMIT の event-pool で走る', async ({
  browser,
}) => {
  logOffset = existsSync(BE_LOG_PATH) ? readFileSync(BE_LOG_PATH, 'utf-8').length : 0
  expect(existsSync(BE_LOG_PATH), `BE ログ ${BE_LOG_PATH} が読めること`).toBe(true)

  // --- ① 対象操作: 管理者がシフト詳細画面で「確定公開」を押す ---
  const adminPage = await openAs(browser, ADMIN)
  try {
    await adminPage.goto(`/shift/${scheduleId}`)
    await waitForHydration(adminPage)

    const publishButton = adminPage.getByRole('button', { name: '確定公開' })
    await expect(publishButton, 'ADJUSTING のシフト詳細に「確定公開」ボタンがある').toBeVisible({
      timeout: 30_000,
    })
    await publishButton.click()

    await expect(
      adminPage.getByText('ステータスを変更しました').first(),
      '確定公開の成功通知が出る',
    ).toBeVisible({ timeout: 30_000 })

    // 業務データが確定し、画面上で「確定済み」として見える状態になった
    await expect(
      adminPage.getByText('確定済み').first(),
      '確定公開後の画面が「確定済み」になる（業務データのコミット完了）',
    ).toBeVisible({ timeout: 30_000 })
  } finally {
    await closePage(adminPage)
  }

  // --- ① 受信者の通知一覧画面に警告通知が現れる ---
  const recipientPage = await openAs(browser, RECIPIENT)
  try {
    const alertRow = recipientPage.getByText(ALERT_TITLE, { exact: true })
    // 通知一覧の行（PrimeVue のボタン類と区別するため div[role=button] に限定する）
    const notificationRows = recipientPage.locator('div[role="button"][tabindex="0"]')
    const moreButton = recipientPage.getByRole('button', { name: 'もっと読む' })

    await recipientPage.goto('/notifications')
    await waitForHydration(recipientPage)
    await expect(notificationRows.first(), '通知一覧が描画される').toBeVisible({ timeout: 30_000 })

    // 共有 dev DB では他ユーザー由来の通知が絶えず積まれるため、
    // 1 ページ 20 件の「もっと読む」を辿って目的の通知まで降りる。
    let found = false
    for (let attempt = 0; attempt < 30; attempt++) {
      if (await alertRow.count() > 0) {
        found = true
        break
      }
      // 読み込み中は PrimeVue Button のラベルがスピナーに差し替わるため、
      // クリック可能になるまで待ってから押す。
      await expect(moreButton, '次ページを読み込む導線がある').toBeVisible({ timeout: 30_000 })
      const before = await notificationRows.count()
      await moreButton.click()
      await expect
        .poll(async () => notificationRows.count(), { timeout: 30_000 })
        .toBeGreaterThan(before)
    }

    expect(found, `受信者の通知一覧に「${ALERT_TITLE}」が表示される`).toBe(true)
    await expect(alertRow.first()).toBeVisible()
    await expect(
      recipientPage.getByText(ALERT_BODY, { exact: true }).first(),
      '通知本文も表示される',
    ).toBeVisible()
  } finally {
    await closePage(recipientPage)
  }

  // --- ③ BE ログで AFTER_COMMIT / event-pool を裏取りする ---
  const tail = readFileSync(BE_LOG_PATH, 'utf-8').slice(logOffset)
  const lines = tail.split(/\r?\n/)

  const transitionLine = lines.find(
    l => l.includes('シフトスケジュールステータス遷移') && l.includes(`id=${scheduleId}, status=PUBLISHED`),
  )
  expect(transitionLine, 'シフト確定公開の遷移ログが残っている').toBeTruthy()

  const deliveryLine = lines.find(
    l =>
      l.includes('ShiftBudgetThresholdAlertNotificationListener')
      && l.includes(`allocId=${allocationId}`),
  )
  expect(deliveryLine, `閾値警告の配送ログ（allocId=${allocationId}）が残っている`).toBeTruthy()

  // 配送は @Async("event-pool") のスレッドで走る（= 業務トランザクションのスレッドではない）
  expect(
    deliveryLine!,
    '閾値警告の配送は event-pool スレッドで実行される（AFTER_COMMIT + @Async）',
  ).toMatch(/\[event-\d+\]/)

  // 因果順序: 業務コミット（遷移ログ）が配送ログより先に出ている
  expect(
    lines.indexOf(transitionLine!),
    '業務コミットのログが通知配送のログより先に出る（通知はコミット後）',
  ).toBeLessThan(lines.indexOf(deliveryLine!))
})

test('L13-02: 権限あり（組織 ADMIN）は警告履歴画面で当該警告を閲覧できる', async ({ browser }) => {
  const page = await openAs(browser, RECIPIENT, { organizationScope: true })
  try {
    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)

    await expect(page.getByText('予算警告履歴').first(), '警告履歴画面が開ける').toBeVisible({
      timeout: 30_000,
    })

    // 行の同定は Allocation ID セルの完全一致で行う（部分一致は閾値 % 等と衝突するため使わない）
    const row = page
      .getByRole('row')
      .filter({ has: page.getByRole('cell', { name: String(allocationId), exact: true }) })
    await expect(row, '当該割当の警告行が 1 行ある').toHaveCount(1)
    await expect(row.getByText(`${EXPECTED_THRESHOLD}%`, { exact: true })).toBeVisible()
    await expect(row.getByText('未承認', { exact: true })).toBeVisible()
  } finally {
    await closePage(page)
  }
})

test('L13-03: 権限なし（組織の一般 MEMBER）は導線が無く、URL 直打ちでも警告を見られず通知も届かない', async ({
  browser,
}) => {
  const page = await openAs(browser, NO_PERMISSION, { organizationScope: true })
  try {
    // 導線: シフト予算管理へのリンクが存在しない
    await page.goto('/')
    await waitForHydration(page)
    await expect(
      page.getByRole('link', { name: /シフト予算/ }),
      'MEMBER の画面にシフト予算管理への導線が無い',
    ).toHaveCount(0)

    // URL 直打ち
    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)
    await expect(
      page.getByText('警告一覧の取得に失敗しました').first(),
      '権限不足で警告一覧が取得できない旨が表示される',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByRole('cell', { name: String(allocationId), exact: true }),
      '当該割当の警告行は見えない',
    ).toHaveCount(0)

    // 通知も届いていない（受信者は組織 ADMIN のみ）
    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText('通知').first()).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(ALERT_TITLE, { exact: true }),
      '非受信者の通知一覧に閾値警告は現れない',
    ).toHaveCount(0)
  } finally {
    await closePage(page)
  }
})

test('L13-04: 他テナントのユーザーは警告履歴にも通知にも当該警告が出ない', async ({ browser }) => {
  const page = await openAs(browser, OUTSIDER, { organizationScope: true })
  try {
    await page.goto('/admin/shift-budget/alerts')
    await waitForHydration(page)
    await expect(
      page.getByText('警告一覧の取得に失敗しました').first(),
      '他テナントからは警告一覧を取得できない',
    ).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByRole('cell', { name: String(allocationId), exact: true }),
      '他テナントに当該割当の警告行は見えない',
    ).toHaveCount(0)

    await page.goto('/notifications')
    await waitForHydration(page)
    await expect(page.getByText('通知').first()).toBeVisible({ timeout: 30_000 })
    await expect(
      page.getByText(ALERT_TITLE, { exact: true }),
      '他テナントの通知一覧に閾値警告は現れない',
    ).toHaveCount(0)
  } finally {
    await closePage(page)
  }
})
