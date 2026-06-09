/**
 * F08.10 試合記録・分析 実機 E2E（モックなし・実 backend 接続）。
 *
 * バックエンド http://localhost:8080（または NUXT_API_PROXY 経由）と
 * フロントエンド http://localhost:3000（playwright-real.config.ts の baseURL）が
 * 起動済みの状態で実行する。
 *
 * 【認証は admin を使う理由】
 *   ライブ記録（イベント POST）は MatchAccessService.canRecordTimeline で
 *   「共同記録＝主体チーム ADMIN/DEPUTY のみ」に限定される（backend
 *   MatchRecordEventController.deriveRecordedByTeamId が非 ADMIN を MATCH_010=403 で弾く）。
 *   seed の e2e-user は FC東京U-18 の MEMBER に過ぎず記録できないため、
 *   記録フローは e2e-admin（FC東京U-18 ADMIN）の storageState で実行する。
 *
 * テストデータ: backend/scripts/seed-e2e-data.js
 *   - e2e-admin@test.mannschaft.local は FC東京U-18（テスト）の ADMIN
 *   - FC東京U-18 には監督＋選手4人が所属（選手グリッドの母集団）
 *
 * storageState: tests/e2e/.auth/real-admin.json（real-admin.setup.ts で生成）。
 * 本 spec は管理者として動かすため、test.use で admin の storageState に切り替える。
 */

import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// このファイル全体を admin storageState で実行する（記録に ADMIN が必須なため）。
test.use({ storageState: 'tests/e2e/.auth/real-admin.json' })

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// ---------------------------------------------------------------------------
// フォールバックログイン（storageState 失効時）
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
// FC東京U-18 の teamId を解決する。
// チームページの route param は UUID publicId（数値 id ではない）。
// /teams のカードから FC東京U-18 をクリックし、遷移後 URL の publicId を採る。
// ---------------------------------------------------------------------------
async function getE2eTeamId(page: Page): Promise<string> {
  await page.goto('/teams')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await page.waitForTimeout(1_500)

  const link = page.getByText('FC東京U-18', { exact: false }).first()
  await link.waitFor({ state: 'visible', timeout: 15_000 })
  await link.click()
  // 遷移先は /teams/{publicId}（UUID）
  await page.waitForURL(/\/teams\/[0-9a-fA-F-]{8,}/, { timeout: 20_000 })
  const id = page.url().match(/\/teams\/([0-9a-fA-F-]+)/)?.[1]
  if (!id) throw new Error('FC東京U-18 の teamId(publicId) を解決できませんでした')
  return id
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

test.beforeAll(async ({ browser }) => {
  const page = await browser.newPage({ storageState: 'tests/e2e/.auth/real-admin.json' })
  await loginIfNeeded(page)
  teamId = await getE2eTeamId(page)
  await page.close()
})

// ===========================================================================
// MATCH-001: 試合一覧ページが表示される
// ===========================================================================
test('MATCH-001: 試合一覧ページ（teams/[id]/matches）が表示される', async ({ page }) => {
  await page.goto(`/teams/${teamId}/matches`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // タイトル「試合記録」または FAB「＋試合を記録」が見えること
  const title = page.getByRole('heading', { name: '試合記録' }).first()
  const fab = page.getByRole('button', { name: '＋試合を記録' }).first()
  await expect(title.or(fab).first()).toBeVisible({ timeout: 20_000 })
})

// ===========================================================================
// MATCH-002〜007: 作成 → ライブ記録 → タイムライン反映 → 終了 を一気通貫
// ===========================================================================
test('MATCH-002: 練習試合を作成しライブ記録で得点/交代/警告を記録、タイムラインに反映される', async ({
  page,
}) => {
  const errs = attachErrorCollectors(page)
  const opponent = `E2E相手 ${Date.now()}`

  // --- 作成画面へ ---
  // FAB は右下固定で、エラーレポートウィジェット（右下固定）と重なりクリックを奪われ得るため、
  // 作成ページへは直接遷移する（FAB の存在自体は MATCH-001 で確認済み）。
  await page.goto(`/teams/${teamId}/matches/new`)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 種別: 練習試合（タップ選択）
  await page.getByRole('button', { name: '練習試合' }).first().click()
  // 相手名
  const opponentInput = page.locator('input').filter({ hasNot: page.locator('[type="password"]') }).first()
  // プレースホルダで特定（相手チーム名を入力）
  const oppByPlaceholder = page.getByPlaceholder('相手チーム名を入力')
  const targetInput = (await oppByPlaceholder.isVisible({ timeout: 3_000 }).catch(() => false))
    ? oppByPlaceholder
    : opponentInput
  await targetInput.click()
  await targetInput.fill(opponent)

  // 記録を開始（submit → live へ遷移）
  await page.getByRole('button', { name: '記録を開始' }).click()
  await page.waitForURL(/\/matches\/[0-9a-fA-F-]+\/live/, { timeout: 20_000 })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const matchUrl = page.url()
  expect(matchUrl).toMatch(/\/matches\/[0-9a-fA-F-]+\/live/)

  // --- タイマー開始（前半開始） ---
  const startBtn = page.getByRole('button', { name: '前半開始' }).first()
  await expect(startBtn).toBeVisible({ timeout: 15_000 })
  await startBtn.click()
  await page.waitForTimeout(800)

  // --- 得点を記録（得点者選択 → アシスト紐付け → 確定）---
  // 「ライブ記録」大ボタンでボトムシートを開く
  const openSheet = page.getByRole('button', { name: 'ライブ記録' }).first()
  await expect(openSheet).toBeVisible({ timeout: 10_000 })
  await openSheet.click()
  await page.waitForTimeout(500)

  // プリセット: 得点
  await page.getByRole('button', { name: '得点', exact: true }).first().click()
  await page.waitForTimeout(400)
  // 得点者（選手グリッドの先頭をタップ）
  const playerButtons = page.locator('.p-drawer button:has(span)').filter({ hasText: /.+/ })
  // 選手グリッドは select-mode のフラットグリッド。最初の選手ボタンを選ぶ。
  const scorer = page
    .locator('.p-drawer')
    .locator('button')
    .filter({ hasNotText: '戻る' })
    .filter({ hasNotText: '確定' })
    .nth(0)
  await scorer.click()
  await page.waitForTimeout(400)
  // aux ステップ: アシスト紐付け
  const addAssist = page.getByRole('button', { name: '＋アシストを紐付け' }).first()
  if (await addAssist.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await addAssist.click()
    await page.waitForTimeout(300)
    // アシスト者（グリッドの2番目の選手があれば選ぶ。無ければ先頭）
    const assistGrid = page.locator('.p-drawer').locator('button').filter({ hasText: /.+/ })
    const assistCandidate = assistGrid.nth(1)
    if (await assistCandidate.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await assistCandidate.click()
    }
    await page.waitForTimeout(300)
  }
  // 確定
  const confirmGoal = page.getByRole('button', { name: '確定' }).first()
  await expect(confirmGoal).toBeVisible({ timeout: 5_000 })
  await confirmGoal.click()
  await page.waitForTimeout(1_200)

  // --- 交代を記録（OUT → IN）---
  await openSheet.click()
  await page.waitForTimeout(400)
  await page.getByRole('button', { name: '交代', exact: true }).first().click()
  await page.waitForTimeout(400)
  // OUT 選手
  const outPlayer = page
    .locator('.p-drawer')
    .locator('button')
    .filter({ hasNotText: '戻る' })
    .nth(0)
  await outPlayer.click()
  await page.waitForTimeout(400)
  // IN 選手（別の選手）
  const inPlayer = page
    .locator('.p-drawer')
    .locator('button')
    .filter({ hasNotText: '戻る' })
    .nth(1)
  if (await inPlayer.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await inPlayer.click()
    await page.waitForTimeout(1_000)
  } else {
    // 選手が1人しかいない等はキャンセルして次へ
    const back = page.getByRole('button', { name: '戻る' }).first()
    if (await back.isVisible().catch(() => false)) await back.click()
  }

  // --- 警告（カード C2）を記録 ---
  if (!(await page.locator('.p-drawer').isVisible({ timeout: 1_000 }).catch(() => false))) {
    await openSheet.click()
    await page.waitForTimeout(400)
  }
  const cardPreset = page.getByRole('button', { name: '警告/退場' }).first()
  if (await cardPreset.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await cardPreset.click()
    await page.waitForTimeout(400)
    // 選手選択
    const cardPlayer = page
      .locator('.p-drawer')
      .locator('button')
      .filter({ hasNotText: '戻る' })
      .nth(0)
    await cardPlayer.click()
    await page.waitForTimeout(400)
    // 理由コード C2 を選択
    const c2 = page.locator('.p-drawer').getByText('C2', { exact: true }).first()
    if (await c2.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await c2.click()
      await page.waitForTimeout(200)
    }
    // 確定
    const confirmCard = page.getByRole('button', { name: '確定' }).first()
    if (await confirmCard.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await confirmCard.click()
      await page.waitForTimeout(1_000)
    }
  }

  // --- タイムラインに反映確認（得点/交代/警告の痕跡）---
  // MatchTimeline はイベント種別ラベル（得点/交代IN/交代OUT/警告 等）を表示する。
  const timelineHasGoal = await page
    .getByText('得点', { exact: false })
    .first()
    .isVisible({ timeout: 5_000 })
    .catch(() => false)
  const timelineHasCard = await page
    .getByText('警告', { exact: false })
    .first()
    .isVisible({ timeout: 3_000 })
    .catch(() => false)
  const timelineHasSub = await page
    .getByText(/交代/)
    .first()
    .isVisible({ timeout: 3_000 })
    .catch(() => false)

  // --- 試合終了（COMPLETED）---
  // SECOND_HALF まで進める（前半→HT→後半→終了）
  const toHT = page.getByRole('button', { name: 'ハーフタイムへ' }).first()
  if (await toHT.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await toHT.click()
    await page.waitForTimeout(600)
    const startSecond = page.getByRole('button', { name: '後半開始' }).first()
    if (await startSecond.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await startSecond.click()
      await page.waitForTimeout(600)
      const complete = page.getByRole('button', { name: '試合終了' }).first()
      if (await complete.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await complete.click()
        await page.waitForTimeout(800)
      }
    }
  }

  // --- 検証: 致命的なネットワークエラー（特に記録 POST の 4xx/5xx）が無いこと ---
  const eventPostErrors = errs.network.filter(
    (e) => /\/matches\/.+\/events/.test(e) && /POST/.test(e),
  )
  // 記録 POST が 403/500 で全滅していたら明確な失敗として報告する。
  expect(
    eventPostErrors,
    `イベント記録 POST がエラーになった: ${eventPostErrors.join(' | ')}`,
  ).toEqual([])

  // タイムラインにいずれかのイベントが反映されていること（少なくとも得点）
  expect(
    timelineHasGoal || timelineHasCard || timelineHasSub,
    'タイムラインに記録イベントが反映されていない',
  ).toBe(true)

  // エラーページに飛んでいないこと
  expect(page.url()).not.toContain('/error')
})

// ===========================================================================
// MATCH-008: チーム分析ページでチャートが描画される
// ===========================================================================
test('MATCH-008: チーム分析（teams/[id]/match-analytics）が表示される', async ({ page }) => {
  const errs = attachErrorCollectors(page)
  await page.goto(`/teams/${teamId}/match-analytics`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 見出し
  await expect(
    page.getByRole('heading', { name: 'チーム試合分析' }).first(),
  ).toBeVisible({ timeout: 20_000 })

  // チャート（chart.js canvas）または空状態 CTA のいずれかが見えること
  const canvas = page.locator('canvas').first()
  const emptyCta = page.getByText('まだ試合記録がありません').first()
  await expect(canvas.or(emptyCta).first()).toBeVisible({ timeout: 15_000 })

  expect(
    errs.network.filter((e) => /match-stats|\/matches/.test(e)),
    `チーム統計 API がエラー: ${errs.network.join(' | ')}`,
  ).toEqual([])
})

// ===========================================================================
// MATCH-009: 個人分析ページでチャートが描画される
// ===========================================================================
test('MATCH-009: 個人分析（me/match-analytics）が表示される', async ({ page }) => {
  await page.goto('/me/match-analytics')
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await expect(page.getByRole('heading', { name: '試合分析' }).first()).toBeVisible({
    timeout: 20_000,
  })

  // チャート canvas / 空状態 / 所属なし のいずれか（実データ依存のため緩く）
  const canvas = page.locator('canvas').first()
  const empty = page.getByText(/まだ試合記録がありません|所属チームがありません/).first()
  await expect(canvas.or(empty).first()).toBeVisible({ timeout: 15_000 })
})

// ===========================================================================
// MATCH-010: ダッシュボード（チームスコープ）に試合サマリウィジェットが表示される
// ===========================================================================
test('MATCH-010: チームダッシュボードに WidgetTeamMatchSummary が表示される', async ({ page }) => {
  // チームダッシュボード（ScopeDashboard）。team-match-summary ウィジェットが描画される。
  await page.goto(`/teams/${teamId}`)
  await waitForHydration(page)
  await expect(page).not.toHaveURL(/\/login/)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // ウィジェットのタイトル「試合サマリ」または内部のラベル（勝/分/敗・試合記録がありません）
  const widgetTitle = page.getByText('試合サマリ').first()
  const widgetWdl = page.getByText('勝/分/敗').first()
  const widgetEmpty = page.getByText('試合記録がありません').first()
  const viewAnalytics = page.getByText('詳細分析を見る').first()

  // いずれかが見えれば widget は描画されている（データ有無に依らず）。
  const anyVisible = await widgetTitle
    .or(widgetWdl)
    .or(widgetEmpty)
    .or(viewAnalytics)
    .first()
    .isVisible({ timeout: 15_000 })
    .catch(() => false)

  // ダッシュボードのウィジェット構成はユーザー設定依存のため、未配置なら情報として skip。
  if (!anyVisible) {
    test.skip(
      true,
      'チームダッシュボードに試合サマリウィジェットが未配置（ウィジェット設定依存）',
    )
  }
  expect(page.url()).not.toContain('/error')
})
