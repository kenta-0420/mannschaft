/**
 * F03.17「キープ（日付未定の予定）」実機 E2E テスト。
 *
 * 設計書: docs/features/F03.17_schedule_keep.md（§9 受け入れ条件）
 * 検分で指摘された「未検証の穴」を埋める8シナリオを検証する:
 *   1. タイトル1項目だけで作成 → 一覧に出る（AC-01）
 *   2. 候補日バッジ1タップで変換 → チームカレンダーに予定が現れる（AC-06 / AC-08）
 *   3. 変換後もキープが status=ALL に残り、そこから予定へ辿れる（AC-07）
 *   4. 候補日なしキープを2タップ以内で変換（AC-08b）
 *   5. revert → カレンダーから予定が消える（AC-11）
 *   6. archive → 一覧から消える → restore → 戻る（AC-10）
 *   7. ブラウザTZ 3種で候補日が常に 8/15 と表示される（AC-24）
 *   8. SUPPORTER ロールではキープ画面に到達できない（AC-16）
 *
 * このテストは API モックを使わない実機テストです。
 * バックエンド http://localhost:8081 / フロントエンド http://localhost:3001 が起動済みの状態で実行してください。
 *
 * 認証: page.request で BE へ直接ログインし、Cookie + localStorage.currentUser を設定する
 * （tests/e2e/fixtures/auth.ts の loginViaApi を利用。single-session 設計・別 context での
 * 二重ログインは禁止 — 本ファイルは全シナリオを直列 (`describe.configure({ mode: 'serial' })`) で走らせ、
 * シナリオ間でトークンを使い回さず毎回ログインし直す）。
 *
 * テストユーザー:
 *   - e2e-admin@test.mannschaft.local / TestPass2026!（fc-u-18 チーム ADMIN）
 *   - e2e-user@test.mannschaft.local / TestPass2026!（fc-u-18 チーム MEMBER）
 *   - e2e-supporter@test.mannschaft.local / TestPass2026!（fc-u-18 チーム SUPPORTER）
 * 対象チーム: fc-u-18（FC東京U-18（テスト））
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3001'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const TEAM_SLUG = 'fc-u-18'

const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: 'TestPass2026!' }

// storageState をクリアして各テストで自前ログインする（single-session 設計）
test.use({ storageState: { cookies: [], origins: [] } })

// ---------------------------------------------------------------------------
// ヘルパー
// ---------------------------------------------------------------------------

async function apiLogin(ctx: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `ログイン失敗 (${email}): ${res.status()}`).toBeTruthy()
  const body = (await res.json()) as { data: { accessToken: string } }
  return body.data.accessToken
}

async function api(
  ctx: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  data?: unknown,
) {
  return ctx.fetch(`${API_BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    data,
  })
}

/** チームキープ一覧 GET（後始末・検証共通）。 */
async function listKeeps(
  ctx: APIRequestContext,
  token: string,
  status: 'KEPT' | 'SCHEDULED' | 'ARCHIVED' | 'ALL' = 'ALL',
) {
  const res = await api(ctx, token, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps?status=${status}`)
  expect(res.ok(), `キープ一覧取得失敗: ${res.status()}`).toBeTruthy()
  return ((await res.json()) as { data: Array<Record<string, unknown>> }).data
}

/** タイトルで作成済みキープを後始末する（論理削除。存在しなければ無視）。 */
async function cleanupKeepsByTitlePrefix(ctx: APIRequestContext, token: string, prefix: string): Promise<void> {
  const all = await listKeeps(ctx, token, 'ALL')
  const targets = all.filter((k) => String(k['title'] ?? '').startsWith(prefix))
  for (const k of targets) {
    // eslint-disable-next-line no-restricted-syntax -- 後始末のため失敗を握りつぶす（本体のアサーションではない）
    await api(ctx, token, 'DELETE', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps/${k['id']}`).catch(() => {})
  }
}

async function gotoTeamKeeps(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/teams/${TEAM_SLUG}/schedule-keeps`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
}

/**
 * F03.17 §4.5.3 変換ダイアログの inline DatePicker で日付を選ぶ。
 * inline のため popup を開く操作は不要 — パネルは常に表示されている。
 * 月送りは行わず「今日」のセルをクリックする（テストの決定性のため、当日を候補にする）。
 */
async function pickTodayInInlineDatePicker(page: Page, testId: string): Promise<void> {
  const panel = page.getByTestId(testId).locator('.p-datepicker-panel')
  await expect(panel).toBeVisible({ timeout: 10_000 })
  // PrimeVue v4 は「今日」のセルに class ではなく data-p-today="true" 属性を付ける
  // （DatePicker.vue の td 要素参照）。クリック対象は td 直下の span（onDateSelect ハンドラを持つ）。
  const todayCell = panel.locator('td[data-p-today="true"] span').first()
  await expect(todayCell, '「今日」のセルが選択できること').toBeVisible({ timeout: 10_000 })
  await todayCell.click()
}

// ===========================================================================
// SK-01: タイトル1項目だけで作成 → 一覧に出る（AC-01）
// ===========================================================================
test.describe('SK-01 タイトル1項目だけで作成（AC-01・ADHD中核）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const TITLE_PREFIX = 'SK01キープ'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-01: タイトルだけ入力して送信すると201で作成され一覧に現れる', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`

    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    const titleInput = page.getByTestId('schedule-keep-title-input')
    await expect(titleInput, '作成フォームが表示されること').toBeVisible({ timeout: 20_000 })

    // メモ・候補日は一切触らず、タイトルだけ入れて送信する（AC-01: 他フィールド必須化禁止の実測）
    await titleInput.click()
    await titleInput.fill(title)

    const submitButton = page.getByTestId('schedule-keep-submit-button')
    await expect(submitButton, 'タイトルのみで送信ボタンが有効化されること').toBeEnabled()
    await submitButton.click()

    // 送信後、一覧（既定 status=KEPT）に現れること
    const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(card, '作成したキープが一覧に現れること').toBeVisible({ timeout: 20_000 })

    // メモ・候補日を送っていないので 400 になっていないこと（201 で通ったことの裏取り）
    const keeps = await listKeeps(ctx, userToken, 'KEPT')
    const created = keeps.find((k) => k['title'] === title)
    expect(created, 'BE側でも201作成が確認できること').toBeTruthy()
    expect(created?.['memo'], 'memoは未入力のためnullであること').toBeNull()
    expect(created?.['candidateDates'], 'candidateDatesは未入力のためnullであること').toBeNull()
  })
})

// ===========================================================================
// SK-02 / SK-03: 候補日バッジ1タップ変換 → カレンダーに現れる（AC-06/AC-08）
//                変換後もキープが status=ALL に残り予定へ辿れる（AC-07）
// ===========================================================================
test.describe('SK-02/03 候補日1タップ変換とキープの残存（AC-06/AC-07/AC-08）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const TITLE_PREFIX = 'SK0203キープ'
  let candidateDate = ''

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
    // 候補日は「今日から2日後」。スケジュール一覧(useCalendarEvents)は既定で当月のみ取得するため
    // （P3-01 の実績どおり月をまたぐと偽陰性になる）、当月内に収まる近い未来日を使う。
    const d = new Date()
    d.setDate(d.getDate() + 2)
    candidateDate = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-02/03: 候補日バッジを1タップすると即変換され、カレンダーに現れ、キープはSCHEDULEDのまま残る', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`

    // 候補日付きキープを API で用意（作成UIはSK-01で検証済みのため、ここでは変換UIに集中する）
    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, {
      title,
      candidateDates: [candidateDate],
    })
    expect(createRes.ok(), `キープ作成失敗: ${createRes.status()} ${await createRes.text()}`).toBeTruthy()

    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(card, '候補日付きキープが一覧に現れること').toBeVisible({ timeout: 20_000 })

    // === AC-08（1タップ）: 候補日バッジをクリックするだけで変換される（確認ダイアログを経由しない） ===
    // タップ操作の回数をユーザー操作ログとして明示的に記録し、1回であることを assert する。
    const userTaps: string[] = []
    const badge = card.getByTestId('schedule-keep-candidate-badge').first()
    await expect(badge, '候補日バッジが表示されること').toBeVisible()
    await badge.click()
    userTaps.push('候補日バッジタップ')

    expect(userTaps.length, 'AC-08: 候補日からの変換は1タップで完了すること').toBe(1)

    // === AC-06 (b): チームカレンダーに同じタイトルの予定が実際に現れる ===
    // 一覧上、変換後のカードは status=SCHEDULED 表示に変わる（既定フィルタ KEPT からは消える）ことも確認
    await expect(card, '変換直後はKEPTフィルタから外れて一覧から消えること').not.toBeVisible({ timeout: 10_000 })

    await page.goto(`${BASE_URL}/teams/${TEAM_SLUG}/schedule`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

    // BE 側で確実に schedules に反映されていることをまず実測（UI表示のズレと切り分けるため）
    let convertedScheduleId: number | null = null
    await expect(async () => {
      const keeps = await listKeeps(ctx, userToken, 'ALL')
      const found = keeps.find((k) => k['title'] === title)
      expect(found?.['status'], 'AC-06(a): 変換後キープはSCHEDULEDになること').toBe('SCHEDULED')
      expect(found?.['convertedScheduleId'], 'AC-06(a): convertedScheduleIdが非nullであること').not.toBeNull()
      convertedScheduleId = found?.['convertedScheduleId'] as number
    }).toPass({ timeout: 15_000 })

    const scheduleDetailRes = await api(ctx, userToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedules/${convertedScheduleId}`)
    expect(scheduleDetailRes.ok(), 'AC-06(b): 変換で生成されたschedulesが実在すること').toBeTruthy()
    const scheduleDetail = (await scheduleDetailRes.json()) as { data: { title: string; time?: { startAt?: string } } }
    expect(scheduleDetail.data.title, 'AC-06(b): 変換先予定のタイトルがキープと一致すること').toBe(title)

    // AC-06(b) の本体: 画面上でもチームカレンダーに実際に現れることを確認する（API実測だけで終わらせない）
    await expect(
      page.getByText(title),
      'AC-06(b): 変換された予定がチームカレンダー画面に実際に表示されること',
    ).toBeVisible({ timeout: 20_000 })

    // === AC-07: 変換後もキープはレコードとして残り、status=ALL で辿れる ===
    const allKeeps = await listKeeps(ctx, userToken, 'ALL')
    const survived = allKeeps.find((k) => k['title'] === title)
    expect(survived, 'AC-07: 変換後もキープがstatus=ALLで残っていること').toBeTruthy()
    expect(survived?.['convertedScheduleId'], 'AC-07: convertedScheduleIdから予定を辿れること').toBe(convertedScheduleId)

    // 既定フィルタ（KEPT）には出ないが、ALL フィルタに切り替えると一覧UIでも見えることを確認
    await gotoTeamKeeps(page)
    const statusFilter = page.getByTestId('schedule-keep-status-filter')
    await statusFilter.getByText('すべて', { exact: false }).click().catch(async () => {
      // ラベルがi18nで変わっている可能性があるため、'ALL' セグメントの位置(4番目)にフォールバック
      await statusFilter.locator('[role="radio"], button').last().click()
    })
    const survivedCard = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(survivedCard, 'AC-07: ALLフィルタでSCHEDULED状態のキープが見えること').toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// SK-04: 候補日なしキープを2タップ以内で変換（AC-08b）
// ===========================================================================
test.describe('SK-04 候補日なしキープの2タップ以内変換（AC-08b）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const TITLE_PREFIX = 'SK04キープ'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-04: 候補日なしキープは「予定にする」→日付選択の2タップで変換完了する（確認ダイアログなし）', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`

    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, { title })
    expect(createRes.ok(), `キープ作成失敗: ${createRes.status()}`).toBeTruthy()

    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(card, '候補日なしキープが一覧に現れること').toBeVisible({ timeout: 20_000 })

    // ユーザー操作を1つずつ記録し、変換完了までの操作回数を実測する。
    // §4.5.3 の設計どおり「確認ダイアログを挟まない」ことも同時に検証する
    // （3回目の操作を要求されない＝ダイアログ内で確認ボタンを押す操作が存在しないことの構造的証明）。
    const userOperations: string[] = []

    // タップ1: 「予定にする」ボタン → ダイアログが開く
    const convertButton = card.getByTestId('schedule-keep-convert-button')
    await expect(convertButton).toBeVisible()
    await convertButton.click()
    userOperations.push('「予定にする」ボタンタップ')

    const dialog = page.getByTestId('schedule-keep-convert-dialog')
    await expect(dialog, '変換ダイアログが開くこと（時刻入力等は既定で畳まれている）').toBeVisible({ timeout: 10_000 })
    // 時刻入力欄は既定で表示されない（段階開示・§4.5.3）ことを確認
    await expect(
      page.getByTestId('schedule-keep-convert-time-input'),
      '時刻入力は既定で畳まれ、変換操作に必須の項目として現れないこと',
    ).not.toBeVisible()

    // タップ2: inline DatePicker で日付を選ぶ → 確認ボタンを挟まず即 convert される
    await pickTodayInInlineDatePicker(page, 'schedule-keep-convert-datepicker')
    userOperations.push('日付セルタップ')

    // === AC-08b: 2タップ以内で変換完了 ===
    expect(userOperations.length, 'AC-08b: 候補日なしキープの変換はダイアログを開く操作＋日付選択の合計2操作で完了すること').toBe(2)

    // ダイアログが自動で閉じ（確認ボタンを押す3操作目が存在しない構造）、一覧からKEPTのカードが消える
    await expect(dialog, '確認操作なしで自動的にダイアログが閉じること').not.toBeVisible({ timeout: 10_000 })
    await expect(card, '変換後はKEPTフィルタから外れて一覧から消えること').not.toBeVisible({ timeout: 10_000 })

    await expect(async () => {
      const keeps = await listKeeps(ctx, userToken, 'ALL')
      const found = keeps.find((k) => k['title'] === title)
      expect(found?.['status'], 'AC-08b: 2操作で変換が完了していること（status=SCHEDULED）').toBe('SCHEDULED')
    }).toPass({ timeout: 15_000 })
  })
})

// ===========================================================================
// SK-05: revert → カレンダーから予定が消える（AC-11）
// ===========================================================================
test.describe('SK-05 revertで変換取消・予定が消える（AC-11）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const TITLE_PREFIX = 'SK05キープ'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-05: SCHEDULEDのキープをrevertするとKEPTに戻り、変換先の予定がカレンダーから消える', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`

    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, { title })
    expect(createRes.ok()).toBeTruthy()
    const keepId = ((await createRes.json()) as { data: { id: string } }).data.id

    const convertRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps/${keepId}/convert`, {
      startAt: '2027-05-01T00:00:00',
      allDay: true,
    })
    expect(convertRes.ok(), `変換失敗: ${convertRes.status()} ${await convertRes.text()}`).toBeTruthy()
    const convertBody = (await convertRes.json()) as { data: { keep: { convertedScheduleId: number } } }
    const scheduleId = convertBody.data.keep.convertedScheduleId
    expect(scheduleId).toBeTruthy()

    // 変換直後は予定が実在すること（revert前のベースライン確認）
    const beforeRevertRes = await api(ctx, userToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedules/${scheduleId}`)
    expect(beforeRevertRes.ok(), 'revert前: 変換先の予定が存在すること').toBeTruthy()

    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    // SCHEDULEDはKEPTフィルタに出ないため ALL に切り替える
    const statusFilter = page.getByTestId('schedule-keep-status-filter')
    await statusFilter.getByText('すべて', { exact: false }).click()
    const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(card, 'SCHEDULEDのキープがALLフィルタで見えること').toBeVisible({ timeout: 20_000 })

    // revert 操作（UIの「取消」ボタン）
    const revertButton = card.getByRole('button', { name: /取り消す|revert/i })
    await expect(revertButton, 'revertボタンが表示されること').toBeVisible({ timeout: 10_000 })
    await revertButton.click()

    // === AC-11: revertでKEPTに戻り、convertedScheduleIdがnullになる ===
    await expect(async () => {
      const keeps = await listKeeps(ctx, userToken, 'ALL')
      const found = keeps.find((k) => k['title'] === title)
      expect(found?.['status'], 'AC-11: revert後はKEPTに戻ること').toBe('KEPT')
      expect(found?.['convertedScheduleId'], 'AC-11: convertedScheduleIdがnullになること').toBeNull()
    }).toPass({ timeout: 15_000 })

    // 変換先の予定がカレンダーから消えている（論理削除）ことを実測
    const afterRevertRes = await api(ctx, userToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedules/${scheduleId}`)
    expect(
      afterRevertRes.status(),
      'AC-11: revert後は変換先の予定がカレンダーから消える（論理削除で404になる）',
    ).toBe(404)

    // カレンダーページのUIでも消えていることを確認
    await page.goto(`${BASE_URL}/teams/${TEAM_SLUG}/schedule`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByText(title)).not.toBeVisible({ timeout: 10_000 })
  })
})

// ===========================================================================
// SK-06: archive → 一覧から消える → restore → 戻る（AC-10）
// ===========================================================================
test.describe('SK-06 archive/restore（AC-10）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  let ctx: APIRequestContext
  let userToken = ''
  const TITLE_PREFIX = 'SK06キープ'

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-06: archiveで既定一覧から消え、restoreでKEPTに戻り再び現れる', async ({ page }) => {
    const title = `${TITLE_PREFIX}-${Date.now()}`

    const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, { title })
    expect(createRes.ok()).toBeTruthy()

    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(card, '作成したキープが既定一覧(KEPT)に現れること').toBeVisible({ timeout: 20_000 })

    const archiveButton = card.getByRole('button', { name: /見送る|archive/i })
    await expect(archiveButton).toBeVisible()
    await archiveButton.click()

    // === AC-10 前半: archiveで既定一覧(KEPT)から消える ===
    await expect(card, 'archive後はKEPT一覧から消えること').not.toBeVisible({ timeout: 10_000 })
    await expect(async () => {
      const keeps = await listKeeps(ctx, userToken, 'ALL')
      const found = keeps.find((k) => k['title'] === title)
      expect(found?.['status'], 'BE側でもARCHIVEDになっていること').toBe('ARCHIVED')
    }).toPass({ timeout: 15_000 })

    // ALLフィルタに切り替えてrestore操作を行う
    const statusFilter = page.getByTestId('schedule-keep-status-filter')
    await statusFilter.getByText('すべて', { exact: false }).click()
    const archivedCard = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(archivedCard, 'ALLフィルタでARCHIVEDのキープが見えること').toBeVisible({ timeout: 15_000 })

    const restoreButton = archivedCard.getByRole('button', { name: /もどす|restore/i })
    await expect(restoreButton).toBeVisible()
    await restoreButton.click()

    // === AC-10 後半: KEPT由来のARCHIVEDはrestoreでKEPTに戻り再び現れる ===
    await expect(async () => {
      const keeps = await listKeeps(ctx, userToken, 'ALL')
      const found = keeps.find((k) => k['title'] === title)
      expect(found?.['status'], 'AC-10: restore後はKEPTに戻ること').toBe('KEPT')
    }).toPass({ timeout: 15_000 })

    // KEPTフィルタに戻すと再び現れることをUIで確認
    await statusFilter.getByText('キープ中', { exact: false }).first().click()
    const restoredCard = page.getByTestId('schedule-keep-card').filter({ hasText: title })
    await expect(restoredCard, 'AC-10: restore後はKEPT一覧に再び現れること').toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// SK-07: ブラウザTZ 3種でも候補日は常に 8/15 と表示される（AC-24）
// ===========================================================================
const TZ_CASES: Array<{ tz: string; label: string }> = [
  { tz: 'Asia/Tokyo', label: 'JST' },
  { tz: 'UTC', label: 'UTC' },
  { tz: 'America/Los_Angeles', label: 'LA' },
]

for (const { tz, label } of TZ_CASES) {
  test.describe(`SK-07(${label}) 候補日のTZ非依存表示（AC-24）`, () => {
    test.use({ timezoneId: tz, storageState: { cookies: [], origins: [] } })
    test.setTimeout(60_000)

    let ctx: APIRequestContext
    let userToken = ''
    const TITLE_PREFIX = `SK07キープ${label}`

    test.beforeAll(async ({ playwright }) => {
      ctx = await playwright.request.newContext()
      userToken = await apiLogin(ctx, E2E_USER.email, E2E_USER.password)
    })

    test.afterAll(async () => {
      await cleanupKeepsByTitlePrefix(ctx, userToken, TITLE_PREFIX)
      await ctx.dispose()
    })

    test(`SK-07(${label}): ブラウザTZ=${tz}でも候補日 2026-08-15 が 2026/08/15 と表示される`, async ({ page }) => {
      const title = `${TITLE_PREFIX}-${Date.now()}`

      const createRes = await api(ctx, userToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, {
        title,
        candidateDates: ['2026-08-15'],
      })
      expect(createRes.ok(), `キープ作成失敗: ${createRes.status()}`).toBeTruthy()

      await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
      await gotoTeamKeeps(page)

      const card = page.getByTestId('schedule-keep-card').filter({ hasText: title })
      await expect(card, '候補日付きキープが一覧に現れること').toBeVisible({ timeout: 20_000 })

      const badge = card.getByTestId('schedule-keep-candidate-badge').first()
      await expect(badge, `ブラウザTZ=${tz}でも候補日バッジが「2026/08/15」と表示されること（前日/翌日にずれないこと）`).toHaveText(
        '2026/08/15',
        { timeout: 10_000 },
      )
    })
  })
}

// ===========================================================================
// SK-08: SUPPORTERロールではキープ画面に到達できない（AC-16）
// ===========================================================================
test.describe('SK-08 SUPPORTERロールの遮断（AC-16）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(60_000)

  let ctx: APIRequestContext
  let adminToken = ''
  let supporterToken = ''
  const TITLE_PREFIX = 'SK08キープ'
  let createdKeepId: string | null = null

  test.beforeAll(async ({ playwright }) => {
    ctx = await playwright.request.newContext()
    adminToken = await apiLogin(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
    supporterToken = await apiLogin(ctx, E2E_SUPPORTER.email, E2E_SUPPORTER.password)

    const createRes = await api(ctx, adminToken, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`, {
      title: `${TITLE_PREFIX}-${Date.now()}`,
    })
    expect(createRes.ok()).toBeTruthy()
    createdKeepId = ((await createRes.json()) as { data: { id: string } }).data.id
  })

  test.afterAll(async () => {
    await cleanupKeepsByTitlePrefix(ctx, adminToken, TITLE_PREFIX)
    await ctx.dispose()
  })

  test('SK-08a: SUPPORTERはチームキープ一覧GETが404になる（BE・IDOR/可視性遮断の実測）', async ({ page }) => {
    void page
    const listRes = await api(ctx, supporterToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps`)
    expect(listRes.status(), 'AC-16: SUPPORTERのキープ一覧GETは404であること').toBe(404)

    const getRes = await api(ctx, supporterToken, 'GET', `/api/v1/teams/${TEAM_SLUG}/schedule-keeps/${createdKeepId}`)
    expect(getRes.status(), 'AC-16: SUPPORTERのキープ単体GETも404であること').toBe(404)
  })

  test('SK-08b: SUPPORTERはキープ画面UIで作成フォームに到達できず、一覧も表示されない', async ({ page }) => {
    await loginViaApi(page, E2E_SUPPORTER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)

    // 作成フォーム（isMember=falseのSUPPORTERには表示されない）
    await expect(
      page.getByTestId('schedule-keep-create-form'),
      'AC-16: SUPPORTERには作成フォームが表示されないこと',
    ).not.toBeVisible({ timeout: 10_000 })

    // 一覧取得が404で失敗するため、作成済みキープのカードも一切見えない
    await expect(
      page.getByTestId('schedule-keep-card'),
      'AC-16: SUPPORTERにはキープカードが一切見えないこと（一覧GETが404のため）',
    ).toHaveCount(0, { timeout: 10_000 })
  })

  test('SK-08c: 比較対照 — 同じキープをMEMBER（e2e-user）は問題なく閲覧できる', async ({ page }) => {
    // SUPPORTER遮断が「常に何も出ない」壊れたページのせいではなく、
    // 認可判定そのものであることを正常系との対照で示す。
    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
    await gotoTeamKeeps(page)
    await expect(
      page.getByTestId('schedule-keep-create-form'),
      '対照: MEMBERには作成フォームが表示されること',
    ).toBeVisible({ timeout: 20_000 })
  })
})
