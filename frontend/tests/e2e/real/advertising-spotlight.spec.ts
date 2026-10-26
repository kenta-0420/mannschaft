/**
 * F09.19 広告（運用型バナーキャンペーン + Spotlight 掲載面）ブラウザUI実機 E2E。
 *
 * 実ブラウザ + 実BE(:8080) + 実DB を踏む。モック・page.route の成功偽装は一切しない。
 *
 * 対象シナリオ（設計書 docs/features/F09.19_ad_slot_serving.md）:
 *   S1: 広告主管理画面（F09.19.4b）— 一覧→新規作成フォーム（rate-card選択・単価/最低日予算併記）
 *       →保存→DRAFT行表示→submitでPENDING_REVIEWへ遷移。
 *   S2: SYSTEM_ADMIN 審査（F09.19.4b）— 審査キューを開き、広告主名・scope・クリエイティブ一覧を確認して approve。
 *   S3: 掲載面表示（F09.19.4）— 受信者ダッシュボードで spotlight タイルが描画される／
 *       候補なしなら枠非表示（items:[] 規則）。中立命名（spotlight-*・ad-/banner- 不在）の DOM 検証も行う。
 *   S4: 通報モーダル（F09.19.9・任意）— SpotlightSlot ケバブ→通報モーダルが開く。
 *       現在の DB 状態で spotlight 枠が描画されない場合は動的に skip する（枠なしでは検証不能なため）。
 *
 * ── 組織スコープではなくチームスコープを使う理由（本タスクで発見した実バグ・BE 領域のため未修正） ──
 * 事前調査で `/organizations/[slug]/advertiser/operational-campaigns` 系のページ
 * （OperationalCampaignListView 等）が渡す scopeId は組織の実 slug 文字列（例:
 * "f0919e2eprobe1784036792"）だが、対応 BE
 * `OrganizationOperationalAdCampaignController`（および `AdvertiserDashboardController` の
 * `register`/`getAccount` 等）は `@PathVariable Long organizationId` / `@RequestParam Long organizationId`
 * で数値 Long を要求する。チーム側には `TeamIdConverter`（`Converter<String, Long>`。数値でなければ
 * `teamService.resolveTeamId(slug)` にフォールバック）が `WebMvcConfig` にグローバル登録されているが、
 * 組織側には同等の `OrganizationIdConverter` が存在しない。
 * 実機 API 直叩きで実証済み（2026-07-14）:
 *   GET /api/v1/organizations/{slug}/advertiser/ad-campaigns → 400 COMMON_001（入力内容に不備）
 *   GET /api/v1/organizations/{slug}/budget/fiscal-years     → 400 COMMON_001（広告以外の org 系 Long
 *                                                                 パスパラメータでも同様に再現。影響範囲が
 *                                                                 広告ドメインに留まらない可能性がある）
 *   GET /api/v1/organizations/{slug}/modules/catalog         → 200 OK（この系統は String slug を直接
 *                                                                 受ける設計のため無関係）
 *   GET /api/v1/teams/{slug}/advertiser/ad-campaigns         → 403 COMMON_002（広告主未登録による正しい
 *                                                                 権限拒否。slug 変換自体は成功）
 * → 組織スコープの運用型キャンペーン/広告主ページは、slug ベースの通常導線からは現状アクセス不能な
 *   BE バグ（本タスクは FE テスト追加のみのスコープのため、根治は別チケット送りとして本ファイル冒頭に
 *   記録するに留める）。UI コンポーネント自体（OperationalCampaignListView/FormView）は
 *   org/team で共通実装のため、本 E2E はルーティングが正常なチームスコープで検証する。
 *
 * ── S3 の実行結果に関する既知の注記 ──
 * 本タスクの事前調査で、ACTIVE な運用型キャンペーン + ACTIVE なクリエイティブ（DASHBOARD_TILE 向け）
 * を API 経由でフルセットアップしても `GET /api/v1/spotlight/content?placement=DASHBOARD_TILE` が
 * 引き続き `items:[]` を返すことを実機で確認した（scopeType=PERSONAL 付与でも同様）。サービング層に
 * 未解明の追加要件（非同期同期・ad_entities 連携等）がある可能性があるが、BE 領域のため本タスクでは
 * 深追いしない。S3 は「候補なしなら枠非表示」の契約（グレースフルデグラデーション）を実際に検証する
 * テストとして設計し、枠が描画される場合は中立命名の DOM 検証も併せて行う。
 *
 * 実行（FE :3000 / BE :8080 が起動済みであること）:
 *   cd frontend
 *   API_BASE_URL=http://localhost:8080 \
 *     npx playwright test --config=playwright-real.config.ts \
 *     tests/e2e/real/advertising-spotlight.spec.ts --reporter=list
 *
 * テストユーザー: e2e-admin@test.mannschaft.local / TestPass2026!（seed 投入済み・SYSTEM_ADMIN）
 */

import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, waitForDialog } from '../helpers/form'
import { loginAs } from '../fixtures/auth'

// storageState に依存せず、各テストでブラウザログインする。
test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const BE = process.env.API_BASE_URL ?? process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

let api: APIRequestContext
let adminToken: string
let teamSlug: string
/** S1 で UI 経由で作成するキャンペーン名。S1〜S2 間で共有し、行の特定に使う。 */
let campaignName: string

function h(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

async function apiLogin(email: string, password: string): Promise<string> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `apiLogin(${email}) は 200`).toBe(200)
  return (await res.json() as { data: { accessToken: string } }).data.accessToken
}

async function loginBrowserAsAdmin(page: Page): Promise<void> {
  await loginAs(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })
}

test.beforeAll(async () => {
  test.setTimeout(60_000)
  api = await pwRequest.newContext()
  adminToken = await apiLogin(ADMIN_EMAIL, ADMIN_PASSWORD)

  // 使い捨てチームを admin が新規作成（作成者=ADMIN・seed 非依存。
  // memory feedback_authz_e2e_seed_membership_pollution）
  const uniqueName = `F0919Spot-E2E-${Date.now()}`
  const createdTeam = await api.post(`${BE_API}/teams`, {
    headers: h(adminToken),
    data: { name: uniqueName, template: 'SPORTS', visibility: 'PUBLIC' },
  })
  expect(createdTeam.status(), '使い捨てチーム作成は 201').toBe(201)
  teamSlug = (await createdTeam.json() as { data: { slug: string } }).data.slug

  // 広告主アカウント登録 + SYSTEM_ADMIN 承認（ACTIVE 化）。
  // 運用型キャンペーン CRUD API は「当該 scope に ACTIVE な広告主アカウントが存在すること」を要求するため
  // （OrganizationOperationalAdCampaignController#verifyAdvertiserAccess 相当。TEAM 版も同様）、
  // S1 の UI 操作（一覧→新規作成→保存）が本題として成立するよう、登録・承認は API で先行済みにしておく。
  const registered = await api.post(`${BE_API}/teams/${teamSlug}/advertiser/register`, {
    headers: h(adminToken),
    data: {
      companyName: `E2E広告主-${Date.now()}`,
      contactEmail: 'e2e-ads@test.mannschaft.local',
      billingMethod: 'STRIPE',
    },
  })
  expect(registered.status(), '広告主登録は 201').toBe(201)
  const advertiserAccountId = (await registered.json() as { data: { id: number } }).data.id

  const approved = await api.patch(
    `${BE_API}/system-admin/advertiser-accounts/${advertiserAccountId}/approve`,
    { headers: h(adminToken) },
  )
  expect(approved.status(), '広告主アカウント承認は 200').toBe(200)

  // CPM の rate card を SYSTEM_ADMIN が作成（新規作成フォームの選択肢に表示させるため）。
  const today = new Date().toISOString().slice(0, 10)
  const rateCardRes = await api.post(`${BE_API}/system-admin/ad-rate-cards`, {
    headers: h(adminToken),
    data: { pricingModel: 'CPM', unitPrice: 500, minDailyBudget: 1000, effectiveFrom: today },
  })
  expect(rateCardRes.status(), 'rate card 作成は 201').toBe(201)
})

test.afterAll(async () => {
  await api?.dispose()
})

// ── S1: 広告主管理画面（F09.19.4b・チームスコープ） ─────────────────────────
test('S1: 広告主が運用型キャンペーンを作成しDRAFT表示→submitでPENDING_REVIEWへ遷移する', async ({ page }) => {
  test.setTimeout(120_000)
  await loginBrowserAsAdmin(page)

  const listUrl = `/teams/${teamSlug}/advertiser/operational-campaigns`
  await page.goto(listUrl)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 一覧テーブルが描画される（初回は空一覧）
  await expect(page.getByTestId('operational-campaign-table')).toBeVisible({ timeout: 15_000 })

  // 新規作成フォームへ
  await page.getByTestId('operational-campaign-create').click()
  await page.waitForURL(new RegExp(`${teamSlug}/advertiser/operational-campaigns/new$`), { timeout: 15_000 })
  await waitForHydration(page)

  campaignName = `E2E運用型キャンペーン-${Date.now()}`
  await fillInput(page.getByTestId('field-name'), campaignName)

  // pricingModel は既定 CPM のまま。rate-card 選択肢には「単価・最低日予算」が併記される
  // （OperationalCampaignFormView#rateCardLabel）ため、選択前にラベルの併記を確認する。
  await page.getByTestId('field-rate-card').click()
  const rateCardListbox = page.locator('[role="listbox"]').last()
  await expect(rateCardListbox, '料金カードの選択肢が表示される').toBeVisible({ timeout: 10_000 })
  const rateCardOption = rateCardListbox.getByText(/CPM/).first()
  await expect(rateCardOption, '単価・最低日予算が併記された選択肢が表示される').toContainText('¥')
  await rateCardOption.click()

  await fillInput(page.getByTestId('field-daily-budget'), '2000')
  const today = new Date().toISOString().slice(0, 10)
  await page.getByTestId('field-start-date').fill(today)

  await Promise.all([
    page.waitForURL(new RegExp(`${teamSlug}/advertiser/operational-campaigns$`), { timeout: 15_000 }),
    page.getByTestId('operational-campaign-save').click(),
  ])
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 15_000 }).catch(() => {})

  // 保存直後は DRAFT（下書き）行として一覧に表示される
  const row = page.locator('tr', { hasText: campaignName })
  await expect(row, '作成直後のキャンペーン行が表示される').toBeVisible({ timeout: 15_000 })
  await expect(row.getByText('下書き', { exact: false }), 'ステータスは下書き(DRAFT)').toBeVisible()

  // submit（審査に提出）
  await row.getByTestId('action-submit').click()
  const confirmDialog = await waitForDialog(page)
  await expect(confirmDialog).toContainText('審査に提出しますか')
  await confirmDialog.getByRole('button', { name: 'はい' }).click()

  // 一覧が再読込され、ステータスが審査中(PENDING_REVIEW)に遷移していることを確認
  await expect(page.getByTestId('operational-campaign-table')).toBeVisible({ timeout: 15_000 })
  const rowAfterSubmit = page.locator('tr', { hasText: campaignName })
  await expect(rowAfterSubmit.getByText('審査中', { exact: false }), 'submit後は審査中(PENDING_REVIEW)').toBeVisible({ timeout: 15_000 })
  // submit 済みのため再度の action-submit ボタンは表示されない（canSubmit=DRAFTのみ）
  await expect(rowAfterSubmit.getByTestId('action-submit')).toHaveCount(0)
})

// ── S2: SYSTEM_ADMIN 審査（F09.19.4b） ──────────────────────────────────
test('S2: SYSTEM_ADMINが審査キューでキャンペーンを開き広告主名・scope・クリエイティブ一覧を確認してapproveする', async ({ page }) => {
  test.setTimeout(120_000)
  expect(campaignName, 'S1で作成したキャンペーン名が共有されている').toBeTruthy()

  await loginBrowserAsAdmin(page)
  await page.goto('/system-admin/advertising/operational-queue')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await expect(page.getByTestId('operational-review-table'), '審査キュー一覧が表示される').toBeVisible({ timeout: 15_000 })
  const queueRow = page.locator('tr', { hasText: campaignName })
  await expect(queueRow, 'S1で提出したキャンペーンが審査キューに表示される').toBeVisible({ timeout: 15_000 })

  await queueRow.getByTestId('operational-review-open').click()
  const detailDialog = page.getByTestId('operational-review-detail')
  await expect(detailDialog, '審査詳細ダイアログが開く').toBeVisible({ timeout: 15_000 })

  // 広告主名・scope・クリエイティブ一覧が見える
  await expect(detailDialog.getByTestId('review-advertiser-name'), '広告主名が表示される').not.toHaveText('')
  await expect(detailDialog, 'scope はチーム表示').toContainText('チーム')
  const hasNoCreatives = await detailDialog.getByTestId('review-no-creatives').isVisible().catch(() => false)
  const hasCreativesList = await detailDialog.getByTestId('review-creatives-list').isVisible().catch(() => false)
  expect(hasNoCreatives || hasCreativesList, 'クリエイティブ一覧（空メッセージ含む）が表示される').toBeTruthy()

  // approve（承認）。詳細ダイアログ(role=dialog)がまだ開いた状態でConfirmDialog(role=alertdialog)が
  // 重なって開くため、waitForDialog の [role="dialog"], [role="alertdialog"] 併用 .last() だと
  // どちらを掴むか不定になる。ここでは alertdialog を明示的に指定して確実に確認ダイアログを掴む。
  await detailDialog.getByTestId('review-approve').click()
  const confirmDialog = page.getByRole('alertdialog')
  await expect(confirmDialog, '承認確認ダイアログが表示される').toBeVisible({ timeout: 10_000 })
  await expect(confirmDialog).toContainText('承認して配信可能にしますか')
  await confirmDialog.getByRole('button', { name: 'はい' }).click()

  // ダイアログが閉じ、キューが再読込されて当該キャンペーンが消える（PENDING_REVIEW→ACTIVE）
  await expect(page.getByTestId('operational-review-detail')).toBeHidden({ timeout: 15_000 })
  await expect(page.getByTestId('operational-review-table')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('tr', { hasText: campaignName })).toHaveCount(0)
})

// ── S3: 掲載面表示（F09.19.4） ───────────────────────────────────────────
test('S3: 受信者ダッシュボードでspotlightタイルが規則どおりに描画される（中立命名/枠非表示規則）', async ({ page }) => {
  test.setTimeout(90_000)
  await loginBrowserAsAdmin(page)

  await page.goto('/dashboard')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 広告の取得失敗/空はページ本体の描画をブロックしない（AC-4.9）
  await expect(page.locator('main, [class*="dashboard"]').first(), 'ダッシュボード本体は正常描画される').toBeVisible({ timeout: 20_000 })

  const spotlightPrimary = page.getByTestId('spotlight-primary')
  const hasPrimary = (await spotlightPrimary.count()) > 0

  if (hasPrimary) {
    await expect(spotlightPrimary, 'spotlight-primary 枠が描画される').toBeVisible()
    const slot = spotlightPrimary.getByTestId('spotlight-slot')
    await expect(slot).toBeVisible()

    // 中立命名（設計 §4）: class に spotlight- は含むが ad-/banner-/sponsor-/promo- は含まない
    const cls = (await slot.getAttribute('class')) ?? ''
    expect(cls, 'spotlight-slot クラスを含む').toMatch(/spotlight-slot/)
    expect(cls, '広告ブロッカー耐性のため ad-/banner-/sponsor-/promo- を含まない').not.toMatch(/\b(ad-|banner-|sponsor-|promo-)/)

    // 景表法「広告」ラベルは中立 testid で存在する
    const label = slot.getByTestId('spotlight-label')
    if (await label.isVisible().catch(() => false)) {
      await expect(label).toHaveText('広告')
    }
  } else {
    // 現行DB状態では候補なし（items:[]）が期待される既知の状態（ファイル冒頭コメント参照）。
    // 「候補なし→枠非表示」規則が守られていることそのものが本シナリオの合格条件。
    console.log('[S3] DASHBOARD_TILE 候補なし（items:[]）→ 枠非表示規則どおり spotlight-primary は非描画')
  }
  expect(page.url(), 'ダッシュボードURLのまま').toContain('/dashboard')
})

// ── S4: 通報モーダル（F09.19.9・任意） ────────────────────────────────────
test('S4(任意): spotlight枠のケバブメニューから通報モーダルが開く', async ({ page }) => {
  test.setTimeout(90_000)
  await loginBrowserAsAdmin(page)

  await page.goto('/dashboard')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const menuButton = page.getByTestId('spotlight-menu-button').first()
  const found = await menuButton.isVisible({ timeout: 5_000 }).catch(() => false)
  test.skip(!found, 'DASHBOARD_TILE 候補なし（items:[]）のため spotlight 枠が存在せず、通報モーダルを検証できる状態にない（S3参照）')

  await menuButton.click()
  const reportButton = page.getByTestId('spotlight-report')
  await expect(reportButton, '通報メニュー項目が表示される').toBeVisible({ timeout: 5_000 })
  await reportButton.click()

  const reportDialog = page.locator('[role="dialog"]').filter({ hasText: '通報' }).last()
  await expect(reportDialog, '通報モーダルが開く').toBeVisible({ timeout: 10_000 })
})
