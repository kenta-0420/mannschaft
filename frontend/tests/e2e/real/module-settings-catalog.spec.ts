/**
 * #2021(BE catalog API) + #2029(FE 新UI) — 機能設定タブ「全機能カタログ＋スイッチ式」実機 E2E。
 *
 * 実ブラウザ + 実BE(:8080) + 実DB を踏む。モック・page.route の成功偽装は一切しない。
 *
 * 構成:
 *   - セットアップ/後始末は Bearer トークンの独立 APIRequestContext（BE直叩き :8080）。
 *     → ブラウザの Cookie セッションとは別系統。refresh ローテで相互無効化する罠を回避
 *       （memory feedback_e2e_real_single_session_token_rotation）。
 *   - 画面操作はブラウザ（canonical real config の :3000＝BE の Origin 許可リスト内）。
 *     ADMIN は e2e-admin を /login フォームでログインして Cookie セッションを確立する。
 *   - チームは seed 汚染を避けるため admin が使い捨て新規作成（作成者=ADMIN）。
 *     （memory feedback_authz_e2e_seed_membership_pollution）
 *
 * 実行（FE :3000 / BE :8080 が起動済みであること）:
 *   cd frontend
 *   API_BASE_URL=http://localhost:8080 \
 *     npx playwright test --config=playwright-real.config.ts \
 *     tests/e2e/real/module-settings-catalog.spec.ts --reporter=list
 *
 * 注: BASE_URL は省略時 playwright-real.config.ts の既定（http://localhost:3000）。
 *     :3000 以外の検証ポートで動かす場合、BE の Origin 許可リスト（既定 localhost:3000/:8080）に
 *     当該オリジンを含めるか、同一オリジンプロキシ経由にすること（CORS/Origin 検証のため）。
 *
 * テストユーザー: e2e-admin@test.mannschaft.local / TestPass2026!（seed 投入済み）
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// storageState に依存せず、各テストでブラウザログインする。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

interface ModuleItem {
  moduleId: number
  name: string
  slug: string
  isEnabled: boolean
  requiresPaidPlan: boolean
  levelAvailable: boolean
}
interface Catalog {
  planLimit: number
  enabledCount: number
  hasPaidPlan: boolean
  modules: ModuleItem[]
}

let api: APIRequestContext
let adminToken: string
let teamSlug: string
let orgSlug: string
let catalog: Catalog
let orgCatalog: Catalog

function h(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function apiLogin(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

/**
 * ブラウザ Cookie セッションを確立する。
 * アプリ自身の /login フォームを駆動する（同一オリジンプロキシ経由で :8080 へ）。
 * page.request で先にセッションを作る方式は、ブラウザ側の proactive refresh が
 * 手動注入した refresh cookie をローテして後続の mutating 要求(PATCH/POST)が 403 になる
 * 罠（memory feedback_e2e_real_single_session_token_rotation）を踏むため、
 * トークンライフサイクルを useApi 一本に閉じる UI フォームログインを採用する。
 */
async function loginBrowser(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/api/v1/auth/login') && r.request().method() === 'POST',
      { timeout: 30_000 },
    ),
    page.getByRole('button', { name: 'ログイン', exact: true }).click(),
  ])
  expect(resp.status(), `login(${email}) API は 200`).toBe(200)
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 30_000 })
}

/** 機能設定タブを開いてカタログ描画完了を待つ。card 群の locator を返す。 */
async function openModuleSettingsTab(page: Page, scopePath: string = `/teams/${teamSlug}`) {
  await page.goto(scopePath)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  const tab = page.getByRole('tab', { name: '機能設定' })
  await expect(tab, '機能設定タブが ADMIN に表示される').toBeVisible({ timeout: 20_000 })
  await tab.click()
  // カタログ fetch + 描画完了を「実カード(.pi-puzzle)が出る」ことで待つ。
  // ヘッダー「有効な機能: N / M」は loading 中(0/0)にも出るため待機条件にしない。
  const cards = page.locator('div.rounded-xl:has(.pi-puzzle)')
  await expect(cards.first(), 'モジュールカードが描画される').toBeVisible({ timeout: 20_000 })
  return cards
}

/** モジュール名から該当カード(rounded-xl)を一意に取る。 */
function cardByName(page: Page, name: string) {
  return page.locator('div.rounded-xl:has(.pi-puzzle)').filter({ hasText: name })
}

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  adminToken = await apiLogin(ADMIN_EMAIL, ADMIN_PASSWORD)

  // 使い捨てチームを admin が新規作成（作成者=ADMIN・seed 非依存）
  const uniqueName = `ModCatalog-E2E-${Date.now()}`
  const created = await api.post(`${BE_API}/teams`, {
    headers: h(adminToken),
    data: { name: uniqueName, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(created.status(), '使い捨てチーム作成は 201').toBe(201)
  teamSlug = (await created.json() as { data: { slug: string } }).data.slug

  // カタログ初期状態を取得（モジュール名・分類を以降の DOM 照合に使う）
  const catRes = await api.get(`${BE_API}/teams/${teamSlug}/modules/catalog`, {
    headers: h(adminToken),
  })
  expect(catRes.status(), 'catalog API は 200').toBe(200)
  catalog = (await catRes.json() as { data: Catalog }).data
  expect(catalog.modules.length, 'カタログは複数モジュールを返す').toBeGreaterThan(1)

  // S8 用: 使い捨て組織も admin が新規作成（作成者=ADMIN）
  const createdOrg = await api.post(`${BE_API}/organizations`, {
    headers: h(adminToken),
    data: { name: `ModCatalog-E2E-Org-${Date.now()}`, orgType: 'OTHER', visibility: 'PUBLIC' },
  })
  expect(createdOrg.status(), '使い捨て組織作成は 201').toBe(201)
  orgSlug = (await createdOrg.json() as { data: { slug: string } }).data.slug
  const orgCatRes = await api.get(`${BE_API}/organizations/${orgSlug}/modules/catalog`, {
    headers: h(adminToken),
  })
  expect(orgCatRes.status(), 'org catalog API は 200').toBe(200)
  orgCatalog = (await orgCatRes.json() as { data: Catalog }).data
  expect(orgCatalog.modules.length, '組織カタログは複数モジュールを返す').toBeGreaterThan(1)
})

test.afterAll(async () => {
  await api?.dispose()
})

// ── シナリオ1〜3: タブを開いて全件カード表示 ───────────────────────────
test('S1-S3: 機能設定タブを開くと全機能カードが空でなく描画される', async ({ page }) => {
  test.setTimeout(120_000)
  await loginBrowser(page, ADMIN_EMAIL, ADMIN_PASSWORD)
  const cards = await openModuleSettingsTab(page)

  // 全件カードが描画される（旧バグ=空表示が直っている）
  const cardCount = await cards.count()
  expect(cardCount, 'モジュールカードが複数枚描画される').toBeGreaterThan(1)
  // BE カタログ件数と一致（全件表示）
  expect(cardCount, 'カード枚数はカタログ件数と一致').toBe(catalog.modules.length)

  // トグルスイッチが各カードに存在
  const switches = cards.getByRole('switch')
  expect(await switches.count(), 'ToggleSwitch がカード分存在').toBe(catalog.modules.length)

  // カード名（任意の OPTIONAL モジュール名）が画面に見える
  const sample = catalog.modules[0]!
  await expect(cardByName(page, sample.name).first(), 'カード名が表示される').toBeVisible()
  // 初期カウンタ "有効な機能: 0 / planLimit"
  await expect(
    page.getByText(`有効な機能: ${catalog.enabledCount} / ${catalog.planLimit}`),
    '有効な機能カウンタが初期値で表示',
  ).toBeVisible()
})

// ── シナリオ4-6: スイッチ ON→通知/カウンタ増、再読込で永続、OFF で戻す ──
test('S4-S6: OPTIONAL機能のスイッチ ON/OFF＋通知＋カウンタ＋永続化', async ({ page }) => {
  test.setTimeout(120_000)
  // 無効化条件に当たらない OPTIONAL モジュール（非有料・レベルOK・未有効）を選ぶ。reservation 優先。
  const target =
    catalog.modules.find((m) => m.slug === 'reservation' && !m.requiresPaidPlan && m.levelAvailable && !m.isEnabled) ??
    catalog.modules.find((m) => !m.requiresPaidPlan && m.levelAvailable && !m.isEnabled)
  expect(target, 'トグル可能な OPTIONAL モジュールが存在する').toBeTruthy()
  const name = target!.name

  await loginBrowser(page, ADMIN_EMAIL, ADMIN_PASSWORD)
  await openModuleSettingsTab(page)

  const card = cardByName(page, name)
  await expect(card.first(), `対象カード(${name})が表示`).toBeVisible()
  const sw = card.getByRole('switch').first()

  // ── ON ──
  await sw.click()
  // 成功トースト「<name> を有効にしました」（FE i18n・握り潰さず実通知）
  await expect(
    page.getByText(/を有効にしました/),
    'ON 成功トーストが表示',
  ).toBeVisible({ timeout: 10_000 })
  // カウンタが +1
  await expect(
    page.getByText(`有効な機能: ${catalog.enabledCount + 1} / ${catalog.planLimit}`),
    'ON でカウンタが +1',
  ).toBeVisible({ timeout: 10_000 })
  await expect(sw, 'スイッチが ON 状態').toBeChecked()

  // ── 永続化: reload 後も ON のまま（実DB往復を踏む） ──
  await openModuleSettingsTab(page)
  const cardAfter = cardByName(page, name)
  const swAfter = cardAfter.getByRole('switch').first()
  await expect(swAfter, 'reload 後もスイッチ ON が保持').toBeChecked({ timeout: 15_000 })
  await expect(
    page.getByText(`有効な機能: ${catalog.enabledCount + 1} / ${catalog.planLimit}`),
    'reload 後もカウンタ +1 が保持',
  ).toBeVisible({ timeout: 15_000 })

  // ── OFF で後始末 ──
  await swAfter.click()
  await expect(
    page.getByText(/を無効にしました/),
    'OFF 成功トーストが表示',
  ).toBeVisible({ timeout: 10_000 })
  await expect(
    page.getByText(`有効な機能: ${catalog.enabledCount} / ${catalog.planLimit}`),
    'OFF でカウンタが元に戻る',
  ).toBeVisible({ timeout: 10_000 })
  await expect(swAfter, 'スイッチが OFF 状態').not.toBeChecked()
})

// ── シナリオ7: 有料プラン必須モジュールはバッジ＋トグル disabled ──
test('S7: requires_paid_plan モジュールに「有料プラン」バッジ＋トグル無効', async ({ page }) => {
  test.setTimeout(120_000)
  // 無料チームで disabledReason='paid' になるのは requiresPaidPlan && levelAvailable のモジュール
  // （levelAvailable=false だと 'level' バッジが優先される）。
  const paid = catalog.modules.find((m) => m.requiresPaidPlan && m.levelAvailable && !catalog.hasPaidPlan)
  test.skip(!paid, '有料プラン必須かつレベル利用可のモジュールがカタログに無いためスキップ')

  await loginBrowser(page, ADMIN_EMAIL, ADMIN_PASSWORD)
  await openModuleSettingsTab(page)

  const card = cardByName(page, paid!.name)
  await expect(card.first(), `有料モジュールカード(${paid!.name})が表示`).toBeVisible()
  // 「有料プラン」バッジ（FE i18n: module_settings.badge.paid_plan）
  await expect(card.getByText('有料プラン').first(), '有料プランバッジが表示').toBeVisible()
  // トグルは disabled
  const sw = card.getByRole('switch').first()
  await expect(sw, '有料モジュールのトグルは無効').toBeDisabled()
})

// ── シナリオ8: 組織スコープでも機能設定タブが全件カード表示される ──
test('S8: 組織の機能設定タブも全機能カードが空でなく描画される', async ({ page }) => {
  test.setTimeout(120_000)
  await loginBrowser(page, ADMIN_EMAIL, ADMIN_PASSWORD)
  const cards = await openModuleSettingsTab(page, `/organizations/${orgSlug}`)

  const cardCount = await cards.count()
  expect(cardCount, '組織でもモジュールカードが複数枚描画される').toBeGreaterThan(1)
  expect(cardCount, '組織のカード枚数はカタログ件数と一致').toBe(orgCatalog.modules.length)

  const switches = cards.getByRole('switch')
  expect(await switches.count(), '組織でも ToggleSwitch がカード分存在').toBe(orgCatalog.modules.length)

  await expect(
    page.getByText(`有効な機能: ${orgCatalog.enabledCount} / ${orgCatalog.planLimit}`),
    '組織でも有効な機能カウンタが初期値で表示',
  ).toBeVisible()
})
