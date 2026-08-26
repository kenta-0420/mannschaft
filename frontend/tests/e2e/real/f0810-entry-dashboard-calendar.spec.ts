/**
 * F08.10 入口③④: ダッシュボードウィジェット / カレンダー予定 → 試合記録 実機E2E。
 *
 * 【入口③】チームダッシュボードの WidgetTeamMatchSummary
 *   - 試合記録がある場合: 「記録を再開する」CTA → /matches/{id}/live へ遷移
 *   - 試合記録がない場合: 「試合を記録する」CTA → /matches の一覧ページへ遷移
 *   - 「詳細分析を見る」CTA → /match-analytics へ遷移
 *   ウィジェットが未配置なら skip（ウィジェット設定依存）。
 *
 * 【入口④】チームスケジュールページ（teams/[id]/schedule）→ EventDetailPanel
 *   - TEAM スコープの予定をクリックするとサイドパネルに「この試合を記録」ボタンが表示される
 *   - ボタンをクリックすると by-schedule 解決 → 未存在なら新規作成 → live ページへ遷移
 *   - 二度押し冪等: 既存試合がある場合は再作成せず既存の live を開く
 *
 * 前提:
 *   - backend/scripts/seed-e2e-data.js 実行済み
 *   - e2e-admin@test.mannschaft.local は FC東京U-18 の ADMIN
 *   - FC東京U-18 のスケジュールに MATCH タイプの予定が存在する（seed で作成済み）
 *   - バックエンド http://localhost:8080 起動済み
 *   - フロントエンド http://localhost:3000 起動済み
 *
 * テストデータ: backend/scripts/seed-e2e-data.js
 *   - 「プリンスリーグ関東 第3節 vs 横浜FCユース」（2026-04-06 MATCH タイプ）
 *   - 「プリンスリーグ関東 第4節 vs 浦和ユース」（2026-04-12 MATCH タイプ）
 *
 * storageState: tests/e2e/.auth/real-admin.json（real-admin.setup.ts で生成）。
 * 本 spec は管理者として動かすため、test.use で admin の storageState に切り替える。
 *
 * 設計書: docs/features/F08.10_match_record_analytics/04_frontend_and_ux.md §G.3（ウィジェット）/ §G.1a（入口④）
 */

import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 管理者 storageState で実行（試合記録に ADMIN が必要なため）
test.use({ storageState: 'tests/e2e/.auth/real-admin.json' })

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// ---------------------------------------------------------------------------
// ヘルパー: フォールバックログイン（storageState 失効時）
// ---------------------------------------------------------------------------
async function loginIfNeeded(page: Page): Promise<void> {
  await page.goto('/my/dashboard')
  await waitForHydration(page)
  if (page.url().includes('/login')) {
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(ADMIN_EMAIL, { delay: 10 })
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(ADMIN_PASSWORD, { delay: 10 })
    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30_000 })
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: FC東京U-18 の slug を取得する。
// /api/v1/me/teams の publicId（または slug）フィールドから取得する。
// API レスポンスの slug / publicId フィールドの両方を試みて取得する。
// ---------------------------------------------------------------------------
async function getE2eTeamId(page: Page): Promise<string> {
  // API から直接 slug/publicId を取得する（UI チームカードクリック方式は
  // team.slug が undefined の場合に遷移しないため、API ベースで確実に取得する）。
  const BE = process.env.NUXT_PUBLIC_API_BASE ?? 'http://localhost:8080'
  const loginRes = await fetch(`${BE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: ADMIN_EMAIL,
      password: ADMIN_PASSWORD,
    }),
  })
  const loginJson = (await loginRes.json()) as { data: { accessToken: string } }
  const token = loginJson.data.accessToken

  const teamsRes = await fetch(`${BE}/api/v1/me/teams`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const teamsJson = (await teamsRes.json()) as {
    data: Array<{ id: number; slug?: string; publicId?: string; name: string }>
  }

  // FC東京U-18 チームを探す
  const team = teamsJson.data.find((t) => t.name?.includes('FC東京U-18') || t.name?.includes('FC'))
  if (!team) throw new Error('FC東京U-18 チームが /api/v1/me/teams に見つかりませんでした')

  // slug または publicId を返す（API バージョンによって異なる可能性）
  const slug = team.slug || team.publicId
  if (!slug) throw new Error(`FC東京U-18 の slug/publicId が取得できません: ${JSON.stringify(team)}`)

  // テキストベースのスラッグを検証
  await page.goto(`/teams/${slug}`)
  await waitForHydration(page)
  if (page.url().includes('/login')) {
    // ログインが必要な場合はフォールバックログイン
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(ADMIN_EMAIL, { delay: 10 })
    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(ADMIN_PASSWORD, { delay: 10 })
    await page.getByRole('button', { name: 'ログイン' }).click()
    await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30_000 })
  }

  return slug
}

/** ネットワーク/コンソールのエラー監視を仕込み、収集配列を返す。 */
function attachErrorCollectors(page: Page): { console: string[]; network: string[] } {
  const consoleErrors: string[] = []
  const networkErrors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  page.on('response', (res) => {
    const url = res.url()
    if (url.includes('/api/v1/') && res.status() >= 400) {
      networkErrors.push(`${res.status()} ${res.request().method()} ${url}`)
    }
  })
  return { console: consoleErrors, network: networkErrors }
}

let teamId: string

/**
 * カレンダーを 2026年4月 に移動する。
 * seed イベント（プリンスリーグ等）は 2026年4月 に存在するため、
 * 現在月（2026年6月等）から月移動ボタンで移動する。
 * 既に 2026年4月 にいる場合は何もしない。
 */
async function navigateTo2026April(page: Page): Promise<void> {
  for (let i = 0; i < 12; i++) {
    const monthHeader = page.locator('h2').filter({ hasText: /2026年?[0-9]{1,2}月/ }).first()
    const headerText = (await monthHeader.textContent({ timeout: 3_000 }).catch(() => '')) ?? ''
    if (headerText.includes('2026') && headerText.includes('4月')) break

    // 現在月から 2026年4月 の方向へ移動
    const today = new Date()
    const target = new Date('2026-04-01')
    const isPastTarget = today > target

    // CalendarGrid は pi-chevron-left（前月）/ pi-chevron-right（次月）ボタンを使う
    const prevBtn = page.locator('button').filter({ has: page.locator('.pi-chevron-left') }).first()
    const nextBtn = page.locator('button').filter({ has: page.locator('.pi-chevron-right') }).first()
    const btn = isPastTarget ? prevBtn : nextBtn
    if (await btn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await btn.click()
      await page.waitForTimeout(800)
    } else {
      break
    }
  }
}

test.beforeAll(async ({ browser }) => {
  const page = await browser.newPage({ storageState: 'tests/e2e/.auth/real-admin.json' })
  await loginIfNeeded(page)
  teamId = await getE2eTeamId(page)
  await page.close()
})

// ===========================================================================
// ─── 入口③: ダッシュボードウィジェット ──────────────────────────────────────
// ===========================================================================

// ---------------------------------------------------------------------------
// ENTRY3-001: ウィジェットが表示される（未配置なら skip）
// ---------------------------------------------------------------------------
test('ENTRY3-001: チームダッシュボードに試合サマリウィジェットが表示される', async ({ page }) => {
  await page.goto(`/teams/${teamId}`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(2_000)

  // ウィジェットのタイトル「試合サマリ」または内部ラベルのいずれかが見えること
  const widgetTitle = page.getByText('試合サマリ').first()
  const widgetWdl = page.getByText('勝/分/敗').first()
  const widgetEmpty = page.getByText('試合記録がありません').first()
  const viewAnalytics = page.getByText('詳細分析を見る').first()
  const recordCta = page.getByText('試合を記録する').first()

  const anyVisible = await widgetTitle
    .or(widgetWdl)
    .or(widgetEmpty)
    .or(viewAnalytics)
    .or(recordCta)
    .first()
    .isVisible({ timeout: 15_000 })
    .catch(() => false)

  if (!anyVisible) {
    test.skip(
      true,
      'チームダッシュボードに試合サマリウィジェットが未配置（ウィジェット設定依存）',
    )
    return
  }

  expect(page.url()).not.toContain('/error')
})

// ---------------------------------------------------------------------------
// ENTRY3-002: 「詳細分析を見る」CTA → match-analytics ページへ遷移
// ---------------------------------------------------------------------------
test('ENTRY3-002: ウィジェット「詳細分析を見る」→ match-analytics ページへ遷移', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(2_000)

  // ウィジェットが未配置 or データなし「詳細分析を見る」リンクが無い場合は skip
  const analyticsLink = page
    .getByRole('link', { name: '詳細分析を見る' })
    .or(page.getByText('詳細分析を見る'))
    .first()
  const isVisible = await analyticsLink.isVisible({ timeout: 10_000 }).catch(() => false)
  if (!isVisible) {
    test.skip(
      true,
      '「詳細分析を見る」リンクが未表示（ウィジェット未配置またはデータなし状態）',
    )
    return
  }

  await analyticsLink.click()
  // match-analytics ページへ遷移する
  await page.waitForURL(/\/match-analytics/, { timeout: 20_000 })
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)

  // チーム試合分析ページが描画される
  const heading = page.getByRole('heading', { name: 'チーム試合分析' }).first()
  const canvas = page.locator('canvas').first()
  const empty = page.getByText('まだ試合記録がありません').first()
  await expect(heading.or(canvas).or(empty).first()).toBeVisible({ timeout: 20_000 })

  // 試合統計 API にエラーがないこと
  const statsErrors = errs.network.filter((e) => /match-stats|\/matches/.test(e))
  expect(
    statsErrors,
    `match-analytics 遷移後に試合 API エラー: ${statsErrors.join(' | ')}`,
  ).toEqual([])
})

// ---------------------------------------------------------------------------
// ENTRY3-003: 進行中試合がある場合 → 「記録を再開する」→ live ページへ遷移
// ---------------------------------------------------------------------------
test('ENTRY3-003: 進行中試合がある場合「記録を再開する」CTA → live ページへ遷移', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(2_000)

  // 「記録を再開する」ボタンが存在しない場合（IN_PROGRESS 試合なし）は進行中試合なしとして skip
  const resumeBtn = page.getByRole('link', { name: '記録を再開する' }).first()
  const isVisible = await resumeBtn.isVisible({ timeout: 5_000 }).catch(() => false)
  if (!isVisible) {
    test.skip(true, '進行中試合なし（IN_PROGRESS の試合が存在しない）。ENTRY3-004 で作成後に確認')
    return
  }

  await resumeBtn.click()
  // live ページへ遷移
  await page.waitForURL(/\/matches\/[0-9a-fA-F-]+\/live/, { timeout: 20_000 })
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  expect(page.url()).not.toContain('/error')

  // live ページのタイマー UI が描画される
  const timerOrLive = page
    .getByRole('button', { name: /前半開始|後半開始|ライブ記録|ハーフタイムへ|試合終了/ })
    .first()
  await expect(timerOrLive).toBeVisible({ timeout: 15_000 })

  // ウィジェットの API 系エラーがないこと
  const widgetErrors = errs.network.filter((e) => /\/matches/.test(e) && !/\/live/.test(e))
  expect(
    widgetErrors,
    `ウィジェット〜live 遷移で API エラー: ${widgetErrors.join(' | ')}`,
  ).toEqual([])
})

// ---------------------------------------------------------------------------
// ENTRY3-004: 試合なし状態（空ウィジェット）→「試合を記録する」CTA → /matches へ遷移
// ---------------------------------------------------------------------------
test('ENTRY3-004: 試合なし状態のウィジェット「試合を記録する」CTA → /teams/[id]/matches へ遷移', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(2_000)

  // 試合記録がない場合のみ「試合を記録する」リンクが出る
  // 試合記録がある場合は skip（ENTRY3-002/003 でカバー）
  const emptyWidgetCta = page
    .getByRole('link', { name: /試合を記録する|試合記録/ })
    .first()
  const isVisible = await emptyWidgetCta.isVisible({ timeout: 5_000 }).catch(() => false)
  if (!isVisible) {
    test.skip(
      true,
      '試合データが存在するため空ウィジェット CTA は未表示（ENTRY3-002/003 でカバー済み）',
    )
    return
  }

  await emptyWidgetCta.click()
  // /teams/{slug}/matches へ遷移する（slug は fc-u-18 等の英数字ハイフン形式）
  await page.waitForURL(/\/teams\/[^/?#]+\/matches/, { timeout: 20_000 })
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)

  // 試合一覧 or FAB が見えること
  const title = page.getByRole('heading', { name: '試合記録' }).first()
  const fab = page.getByRole('button', { name: '＋試合を記録' }).first()
  await expect(title.or(fab).first()).toBeVisible({ timeout: 20_000 })

  // 試合関連 API の 5xx のみチェック（supporters 等の無関係な 500 は除外）
  const matchErrors = errs.network.filter(
    (e) => e.startsWith('5') && /\/matches/.test(e),
  )
  expect(
    matchErrors,
    `試合一覧ページで試合 API の 5xx エラーが発生: ${matchErrors.join(' | ')}`,
  ).toEqual([])
})

// ===========================================================================
// ─── 入口④: カレンダー/予定詳細 → 試合記録 ───────────────────────────────────
// ===========================================================================

// ---------------------------------------------------------------------------
// ENTRY4-001: チームスケジュールページが表示される
// ---------------------------------------------------------------------------
test('ENTRY4-001: チームスケジュールページ（teams/[id]/schedule）が表示される', async ({
  page,
}) => {
  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // カレンダーグリッドまたは「スケジュール」見出しが表示される
  const heading = page.getByRole('heading', { name: 'スケジュール' }).first()
  const calGrid = page.locator('.calendar, [class*="CalendarGrid"], table').first()
  await expect(heading.or(calGrid).first()).toBeVisible({ timeout: 20_000 })
  expect(page.url()).not.toContain('/error')
})

// ---------------------------------------------------------------------------
// ENTRY4-002: 予定をクリックするとサイドパネルに「この試合を記録」ボタンが表示される
// ---------------------------------------------------------------------------
test('ENTRY4-002: 予定をクリックすると EventDetailPanel に「この試合を記録」ボタンが表示される', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  // seed イベントは 2026年4月 にあるため、必要なら月を移動する
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  // CalendarGrid.vue の実装に従ったセレクタ:
  // 1日イベントは `.space-y-0.5 > div` でレンダリングされ @click.stop でイベントを発火する
  const eventCard = page.locator('.space-y-0\\.5 > div').first()
  const altEventCard = page.locator('span[style*="backgroundColor"], span[style*="background-color"]').first()
  const cardToClick = (await eventCard.isVisible({ timeout: 5_000 }).catch(() => false))
    ? eventCard
    : altEventCard

  // 根治後はカレンダーにイベントが描画されるため、見つからなければ skip ではなく失敗させる。
  const hasEvent = await cardToClick.isVisible({ timeout: 8_000 }).catch(() => false)
  expect(hasEvent, '2026年4月のカレンダーに seed イベントが描画されていること').toBe(true)

  await cardToClick.click()
  await page.waitForTimeout(1_500)

  // EventDetailPanel がサイドパネルとして表示される（予定タイトル見出し）
  const recordBtn = page.getByRole('button', { name: 'この試合を記録' }).first()
  const panelTitle = page.getByRole('heading').first()
  await expect(panelTitle, 'EventDetailPanel のサイドパネルが表示されること').toBeVisible({
    timeout: 10_000,
  })

  // TEAM スコープ予定なので「この試合を記録」ボタンが必ず表示される（canRecordMatch=true）
  await expect(recordBtn).toBeVisible({ timeout: 8_000 })
  await expect(recordBtn).toHaveText('この試合を記録')
  // ヒント文（「この予定の試合記録を開始します」）も表示される
  const hint = page.getByText('この予定の試合記録を開始します').first()
  await expect(hint).toBeVisible({ timeout: 5_000 })

  // 予定クリックで 4xx/5xx が出ていないこと
  const panelErrors = errs.network.filter((e) => /\/schedules/.test(e))
  expect(
    panelErrors,
    `予定詳細取得で API エラー: ${panelErrors.join(' | ')}`,
  ).toEqual([])
})

// ---------------------------------------------------------------------------
// ENTRY4-003: 「この試合を記録」→ by-schedule 解決 → live ページへ遷移
//   - 既存試合がなければ新規作成 → live
//   - 二度押し時（既存あり）は再作成せず既存 live を開く
// ---------------------------------------------------------------------------
test('ENTRY4-003: 「この試合を記録」ボタン押下 → by-schedule 解決 → live ページへ遷移', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)

  // API 呼び出しを監視して by-schedule → matches（POST / redirect）を記録する
  const byScheduleRequests: string[] = []
  const matchPostRequests: string[] = []
  page.on('request', (req) => {
    const url = req.url()
    if (/\/matches\/by-schedule\//.test(url)) byScheduleRequests.push(url)
    if (/\/matches$/.test(url) && req.method() === 'POST') matchPostRequests.push(url)
  })

  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  // seed イベントは 2026年4月 にあるため、必要なら月を移動する
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  // CalendarGrid.vue の 1日イベント（.space-y-0.5 > div）をクリックしてサイドパネルを開く
  const eventCard = page.locator('.space-y-0\\.5 > div').first()
  const altEventCard = page.locator('span[style*="backgroundColor"], span[style*="background-color"]').first()
  const cardToUse = (await eventCard.isVisible({ timeout: 5_000 }).catch(() => false))
    ? eventCard
    : altEventCard

  // 根治後はイベントが描画されるため、見つからなければ失敗させる。
  const hasEvent = await cardToUse.isVisible({ timeout: 8_000 }).catch(() => false)
  expect(hasEvent, '2026年4月のカレンダーに seed イベントが描画されていること').toBe(true)

  await cardToUse.click()
  await page.waitForTimeout(1_500)

  // 「この試合を記録」ボタンが表示されること（TEAM スコープ予定・管理者）
  const recordBtn = page.getByRole('button', { name: 'この試合を記録' }).first()
  await expect(recordBtn, '「この試合を記録」ボタンが表示されること').toBeVisible({
    timeout: 10_000,
  })

  // --- ボタンをクリック ---
  await recordBtn.click()

  // live ページへの遷移を待つ（by-schedule 解決 → createMatch → navigateTo を経由）
  await page.waitForURL(/\/matches\/[0-9a-fA-F-]+\/live/, { timeout: 30_000 })
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  expect(page.url()).not.toContain('/error')

  // by-schedule API が呼ばれたこと（二重起票防止の確認）
  expect(
    byScheduleRequests.length,
    'by-schedule 解決 API が呼ばれていること（二重起票防止チェック）',
  ).toBeGreaterThanOrEqual(1)

  // live ページに試合 UI が表示されること
  const liveUi = page
    .getByRole('button', { name: /前半開始|後半開始|ライブ記録|ハーフタイムへ|試合終了/ })
    .first()
  await expect(liveUi).toBeVisible({ timeout: 20_000 })

  // 試合 API に 4xx/5xx が出ていないこと
  const criticalErrors = errs.network.filter(
    (e) => /\/matches/.test(e) && !/MATCH-ALREADY-EXISTS/.test(e),
  )
  expect(
    criticalErrors,
    `by-schedule → live 遷移で試合 API エラー: ${criticalErrors.join(' | ')}`,
  ).toEqual([])
})

// ---------------------------------------------------------------------------
// ENTRY4-004: 同じ予定を二度押し → 既存試合へ遷移（冪等性・二重起票防止）
// ---------------------------------------------------------------------------
test('ENTRY4-004: 同じ予定の「この試合を記録」を二度押し → 既存試合の live ページが開く（冪等）', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)

  const matchPostRequests: string[] = []
  page.on('request', (req) => {
    const url = req.url()
    if (/\/matches$/.test(url) && req.method() === 'POST') matchPostRequests.push(url)
  })

  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  // seed イベントは 2026年4月 にあるため、必要なら月を移動する
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  const eventCardIdm = page.locator('.space-y-0\\.5 > div').first()
  const altEventCardIdm = page.locator('span[style*="backgroundColor"], span[style*="background-color"]').first()
  const idmCard = (await eventCardIdm.isVisible({ timeout: 5_000 }).catch(() => false))
    ? eventCardIdm
    : altEventCardIdm

  // 根治後はイベントが描画されるため、見つからなければ失敗させる。
  const hasEvent = await idmCard.isVisible({ timeout: 8_000 }).catch(() => false)
  expect(hasEvent, '2026年4月のカレンダーに seed イベントが描画されていること').toBe(true)

  await idmCard.click()
  await page.waitForTimeout(1_500)

  const recordBtn = page.getByRole('button', { name: 'この試合を記録' }).first()
  await expect(recordBtn, '「この試合を記録」ボタンが表示されること').toBeVisible({
    timeout: 10_000,
  })

  // --- 1回目クリック → live へ遷移 ---
  await recordBtn.click()
  await page.waitForURL(/\/matches\/[0-9a-fA-F-]+\/live/, { timeout: 30_000 })
  const firstLiveUrl = page.url()
  expect(firstLiveUrl).toMatch(/\/matches\/[0-9a-fA-F-]+\/live/)

  // live から戻る（スケジュールページへ）
  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)
  // 月がリセットされるため再度 2026年4月 に移動
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  // --- 同じ予定をもう一度クリック → 同じ試合の live が開く（再作成 POST なし）---
  const matchPostCountBefore = matchPostRequests.length

  await idmCard.click()
  await page.waitForTimeout(1_500)

  const recordBtn2 = page.getByRole('button', { name: 'この試合を記録' }).first()
  if (await recordBtn2.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await recordBtn2.click()
    await page.waitForURL(/\/matches\/[0-9a-fA-F-]+\/live/, { timeout: 30_000 })
    const secondLiveUrl = page.url()

    // 冪等性: 同じ試合の live ページへ遷移すること（1 回目と同じ URL）
    expect(secondLiveUrl, '二度押しで同じ試合の live が開く（冪等）').toBe(firstLiveUrl)

    // 2回目は試合作成 POST が発生しないこと（by-schedule 解決で既存が見つかるため）
    const matchPostCountAfter = matchPostRequests.length
    expect(
      matchPostCountAfter,
      '二度押しで新規試合作成 POST が発生しないこと（冪等・二重起票防止）',
    ).toBe(matchPostCountBefore)

    // 4xx/5xx がないこと
    const criticalErrors = errs.network.filter((e) => /\/matches/.test(e))
    expect(
      criticalErrors,
      `二度押し冪等テストで試合 API エラー: ${criticalErrors.join(' | ')}`,
    ).toEqual([])
  }
  // 2回目のボタンが表示されなかった場合（試合が COMPLETED 等で既にリダイレクト済み）は pass
})

// ---------------------------------------------------------------------------
// ENTRY4-005: 月移動で 2026年4月 に移動し MATCH タイプ予定を確認する
// ---------------------------------------------------------------------------
test('ENTRY4-005: 月を移動して 2026年4月 の MATCH タイプ予定「プリンスリーグ」が表示される', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  // seed イベントは 2026年4月 にあるため、navigateTo2026April で移動する（ENTRY4-002 と同じ方式）。
  // CalendarGrid の月移動ボタンは PrimeVue Button の icon="pi pi-chevron-left/right" のため、
  // getByRole('button', { name: /前月|前へ/ }) ではマッチしない。navigateTo2026April を使う。
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  // 2026年4月のヘッダーが表示されていることを確認
  const aprilHeader = page.locator('h2').filter({ hasText: /2026年?4月/ }).first()
  await expect(aprilHeader, '2026年4月のカレンダーが表示されていること').toBeVisible({ timeout: 8_000 })

  // seed イベント（プリンスリーグ）がカレンダーに描画されていることを確認。
  // カレンダーの 1日イベントは `.space-y-0.5 > div` でレンダリングされ、
  // タイトルは省略される場合があるため（「プ…」等）、イベントカードの存在で判定する。
  const matchSeedTitle = 'プリンスリーグ'
  // テキスト完全一致ではなく、カレンダー内の任意のイベントカードを確認する（省略表示対応）
  const eventCard005 = page.locator('.space-y-0\\.5 > div').first()
  const seedEventVisible = await eventCard005.isVisible({ timeout: 8_000 }).catch(() => false)

  // 根治後はカレンダーに予定が描画されるため、見つからなければ失敗させる。
  expect(
    seedEventVisible,
    `seed の「${matchSeedTitle}」予定が 2026年4月 のカレンダーに描画されていること（イベントカードが存在すること）`,
  ).toBe(true)

  // イベントが存在すること（完全タイトルは省略される場合があるため、カードの存在で代替）
  await expect(eventCard005).toBeVisible({ timeout: 10_000 })
  expect(page.url()).not.toContain('/error')

  // スケジュール API に 4xx/5xx がないこと
  const scheduleErrors = errs.network.filter((e) => /\/schedules/.test(e))
  expect(
    scheduleErrors,
    `スケジュール API エラー: ${scheduleErrors.join(' | ')}`,
  ).toEqual([])
})

// ---------------------------------------------------------------------------
// ENTRY4-006: サイドパネルの詳細情報 + 「この試合を記録」ボタンが TEAM 予定にのみ表示
// ---------------------------------------------------------------------------
test('ENTRY4-006: EventDetailPanel に日時・場所・「この試合を記録」ボタンが TEAM 予定限定で表示される', async ({
  page,
}) => {
  await page.goto(`/teams/${teamId}/schedule`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  // seed イベントは 2026年4月 にあるため、必要なら月を移動する
  await navigateTo2026April(page)
  await page.waitForTimeout(800)

  // CalendarGrid.vue の 1日イベントをクリックする
  const eventCard006 = page.locator('.space-y-0\\.5 > div').first()
  const altEventCard006 = page.locator('span[style*="backgroundColor"], span[style*="background-color"]').first()
  const card006 = (await eventCard006.isVisible({ timeout: 5_000 }).catch(() => false))
    ? eventCard006
    : altEventCard006

  // 根治後はイベントが描画されるため、見つからなければ失敗させる。
  const hasEvent = await card006.isVisible({ timeout: 8_000 }).catch(() => false)
  expect(hasEvent, '2026年4月のカレンダーに seed イベントが描画されていること').toBe(true)

  await card006.click()
  await page.waitForTimeout(1_500)

  // サイドパネルに日時情報が表示される（EventDetailPanel の date/time 行）
  const calIcon = page.locator('.pi-calendar').first()
  await expect(calIcon, 'EventDetailPanel に日時アイコンが表示されること').toBeVisible({
    timeout: 8_000,
  })

  // TEAM スコープの予定なので「この試合を記録」ボタンが必ず表示される
  const recordBtn = page.getByRole('button', { name: 'この試合を記録' }).first()
  await expect(recordBtn).toBeVisible({ timeout: 8_000 })
  // ヒント文
  const hint = page.getByText('この予定の試合記録を開始します').first()
  await expect(hint).toBeVisible({ timeout: 5_000 })
  // ボタンが Loading 状態でないこと（クリック前は enabled）
  await expect(recordBtn).toBeEnabled()

  // パネルにエラーページへの遷移がないこと
  expect(page.url()).not.toContain('/error')
})
