/**
 * 合言葉「関所開き」— モジュール有効化バックフィル戦役 実機E2E最終確認。
 * PR-A(#2359 BE+migration) + PR-B(#2363 FE結線) 実装の実UI検証。モックなし。
 *
 * S1: 既存(backfill済)テナントで対象9機能中の sidebar 項目が可視。
 *     org=1(org-000001) は V158 backfill 対象・grandfather 済(tournament/timetable/
 *     committee/budget/form/workflow/blog_cms の7項目)。
 *     team=374(非削除・90209=ADMIN) も budget/workflow がgrandfather済み(ボーナス確認)。
 * S2: 新規作成テナント(V158後に作成・enable行なし)では対象項目が非表示。
 * S3: モジュール設定画面「有効な機能: {count} / {limit}」count が grandfather(7件)を
 *     含まない実カウント(org=1 は catalog API 実測 enabledCount=0 / planLimit=10)。
 *
 * 実行:
 *   cd frontend
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test tests/e2e/real/module-activation-backfill.spec.ts --reporter=list
 *
 * 認証: loginViaApi（別contextログイン禁止・単一セッション。
 *   memory feedback_e2e_single_session_token_rotation 準拠）。
 */

import { test, expect, type Page, type Locator } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8081'
const BE_API = `${API_BASE_URL}/api/v1`

// E2E固定ユーザー(memory project_e2e_test_user_provisioning_when_seed_creds_drift)。
// org=1(org-000001)にADMIN membership投入済み。team=374(非削除)にもADMIN。
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

const ORG_SLUG = 'org-000001'
// V158前から存在し非削除・90209=ADMIN・budget/workflowがgrandfather済み(実DB照合済)
const TEAM_SLUG = 'f0919spot-e2e-1784038537595'

// S1で可視を期待する org 対象7項目(labelKey→表示テキスト・所属カテゴリ)
// カテゴリ・moduleSlugは worktree の OrganizationSidebar.vue から直接確認した値
// (本陣は27コミット遅れのstaleな内容だったため使用していない)。
const ORG_TARGET_ITEMS: { text: string; category: string }[] = [
  { text: 'ブログ', category: 'ホーム' }, // home: 既定で開いている
  { text: '時間割', category: 'スケジュール' },
  { text: 'ワークフロー', category: '業務・運用' },
  { text: 'フォーム', category: '業務・運用' },
  { text: '委員会', category: '業務・運用' },
  { text: '予算', category: 'データ・分析' },
  { text: 'トーナメント', category: 'その他' },
]
const ORG_CATEGORIES_TO_EXPAND = ['スケジュール', '業務・運用', 'データ・分析', 'その他']

// team側(ボーナス確認): budget/workflowのみgrandfather済み
const TEAM_TARGET_ITEMS: { text: string; category: string }[] = [
  { text: '予算管理', category: '運営・予算' },
  { text: 'ワークフロー', category: 'タスク・進行' },
]
const TEAM_CATEGORIES_TO_EXPAND = ['運営・予算', 'タスク・進行']

/** 文字列を安全に正規表現へ埋め込むためのエスケープ。 */
function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 完全一致テキストの正規表現(前後空白は許容)。
 *
 * BaseSidebar.vue のカテゴリ/項目ボタンは PrimeIcons の <i> (aria-hidden なし) を
 * テキストの前後に伴う。Chromium の accessible name 計算に CSS 生成コンテンツの
 * 疑似グリフが混入し getByRole(..., {name, exact:true}) が実際に見えているテキストと
 * 一致しない(実機で実際に踏んだ: スクショで「ホーム」が見えているのに
 * getByRole('button',{name:'ホーム',exact:true}) が要素0件で失敗した)。
 * hasText は DOM の textContent ベースで CSS 生成コンテンツを含まないため、
 * role + hasText(完全一致正規表現) で確実に一致させる。
 */
function exactText(label: string): RegExp {
  return new RegExp(`^\\s*${escapeRegExp(label)}\\s*$`)
}

/**
 * サイドバーがマウントされ「ホーム」カテゴリ(既定open)が見えるまで待つ。
 * 開いた PrimeVue Drawer(role="dialog"・modal既定true)のスコープ Locator を返す。
 *
 * この worktree の現行実装(ScopePageShell.vue)では Team/OrganizationSidebar は
 * 常設 <aside> ではなく、タブバー右端の「メニュー」ハンバーガーボタンで開く
 * <Drawer> の中に描画される(sidebar prop は Drawer 内でのみマウント)。
 * そのため先にハンバーガーを開かないと、いつまで待っても「ホーム」ボタンは現れない。
 *
 * ダッシュボード本文にも同名リンク(例: 「ブログ」ウィジェット)が存在し得るため、
 * 以降の項目検索は role="dialog" のこの Drawer 内に必ずスコープすること
 * (実機で strict mode violation: 本文と Drawer の2箇所に「ブログ」リンクが一致、を実際に踏んだ)。
 *
 * マウント確認シグナルには「スケジュール」カテゴリを使う。「ホーム」はモジュール未有効の
 * 新規テナント(S2)だと配下5項目が全て moduleSlug 必須で1件も可視にならず
 * isCategoryVisible が false になりカテゴリ自体が消える(実機で実際に踏んだ)。
 * 「スケジュール」配下には org の annualPlan・team の duties/annual_plan という
 * moduleSlug:null の項目が必ず1つ以上あるため、モジュール有効化状況に関わらず
 * カテゴリ自体は org/team のどちらでも必ず描画される。
 */
async function waitForSidebarMounted(page: Page): Promise<Locator> {
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  // count() は auto-wait しないため、タブバー描画前に判定して click をスキップする罠がある
  // (実機E2Eで実際に踏んだ: ボタンは数百ms後に出現するが count()==0 のまま素通りしていた)。
  // click() 自体に auto-wait させて確実に描画完了を待つ。
  // 「メニュー」はアイコンのみのボタンで aria-label="メニュー" を直接持つため
  // (テキストコンテンツが無く hasText では拾えない)、accessible name の exact 一致で問題ない。
  const menuButton = page.getByRole('button', { name: 'メニュー', exact: true })
  await menuButton.click({ timeout: 20_000 })
  const drawer = page.getByRole('dialog')
  await expect(
    drawer.getByRole('button').filter({ hasText: exactText('スケジュール') }).first(),
    'サイドバーの「スケジュール」カテゴリが表示される',
  ).toBeVisible({ timeout: 20_000 })
  return drawer
}

/** 既定で閉じているカテゴリのアコーディオンを開く(既に開いているカテゴリは呼ばないこと)。 */
async function expandCategories(scope: Locator, categoryLabels: string[]): Promise<void> {
  for (const label of categoryLabels) {
    await scope.getByRole('button').filter({ hasText: exactText(label) }).first().click({ timeout: 10_000 })
  }
}

test.beforeEach(async ({ page }) => {
  await loginViaApi(
    page,
    { email: USER_EMAIL, password: USER_PASSWORD },
    { apiBaseUrl: API_BASE_URL },
  )
})

// ── S1: org=1 は backfill済み7項目が可視 ──────────────────────────────
test('S1: 既存(backfill済) org=1 で対象7項目の sidebar リンクが可視', async ({ page }) => {
  test.setTimeout(60_000)
  await page.goto(`/organizations/${ORG_SLUG}`)
  await waitForHydration(page)
  const drawer = await waitForSidebarMounted(page)
  await expandCategories(drawer, ORG_CATEGORIES_TO_EXPAND)

  for (const item of ORG_TARGET_ITEMS) {
    await expect(
      drawer.getByRole('link').filter({ hasText: exactText(item.text) }),
      `org=1: 「${item.text}」(${item.category})が可視`,
    ).toBeVisible({ timeout: 10_000 })
  }
})

// ── S1ボーナス: team=374(非削除・grandfather済) の budget/workflow も可視 ──
test('S1-bonus: 既存(backfill済) team=374 で budget/workflow の sidebar リンクが可視', async ({ page }) => {
  test.setTimeout(60_000)
  await page.goto(`/teams/${TEAM_SLUG}`)
  await waitForHydration(page)
  const drawer = await waitForSidebarMounted(page)
  await expandCategories(drawer, TEAM_CATEGORIES_TO_EXPAND)

  for (const item of TEAM_TARGET_ITEMS) {
    await expect(
      drawer.getByRole('link').filter({ hasText: exactText(item.text) }),
      `team=374: 「${item.text}」(${item.category})が可視`,
    ).toBeVisible({ timeout: 10_000 })
  }
})

// ── S2: 新規作成テナント(enable行なし)は対象項目が非表示 ──────────────
test('S2: 新規作成 org(V158後・enable行なし)は対象7項目が非表示', async ({ page }) => {
  test.setTimeout(60_000)

  const uniqueName = `ModuleBackfillE2E-${Date.now()}`
  const created = await page.request.post(`${BE_API}/organizations`, {
    data: { name: uniqueName, orgType: 'OTHER', visibility: 'PUBLIC' },
  })
  expect(created.status(), '新規組織作成は201').toBe(201)
  const newOrgSlug = (await created.json() as { data: { slug: string } }).data.slug

  await page.goto(`/organizations/${newOrgSlug}`)
  await waitForHydration(page)
  const drawer = await waitForSidebarMounted(page)
  await expandCategories(drawer, ORG_CATEGORIES_TO_EXPAND)

  for (const item of ORG_TARGET_ITEMS) {
    await expect(
      drawer.getByRole('link').filter({ hasText: exactText(item.text) }),
      `新規org: 「${item.text}」(${item.category})は非表示`,
    ).not.toBeVisible()
  }

  // 後始末(可能なら削除。失敗しても致命ではないので握り潰す)
  await page.request.delete(`${BE_API}/organizations/${newOrgSlug}`).catch(() => {})
})

// ── S3: org=1 の「有効な機能」カウントは grandfather(7件)を含まない ──
test('S3: org=1 の有効な機能カウントは grandfather を含まない(実測 0/10)', async ({ page }) => {
  test.setTimeout(60_000)

  // 実DB/実API事前照合値: catalog API で planLimit=10, enabledCount=0
  // (grandfather 7件は isEnabled=true だが enabledCount には計上されない設計。
  //  ModuleService.java の enabledCount 集計コメント「grandfather 行は数えない」に一致)
  const meRes = await page.request.get(`${BE_API}/users/me`)
  expect(meRes.status(), '/users/me は200').toBe(200)

  const catalogRes = await page.request.get(`${BE_API}/organizations/${ORG_SLUG}/modules/catalog`)
  expect(catalogRes.status(), 'catalog API は200').toBe(200)
  const catalog = (await catalogRes.json() as {
    data: { planLimit: number; enabledCount: number }
  }).data
  expect(catalog.enabledCount, 'API実測: enabledCountはgrandfather(7件)を含まず0').toBe(0)
  expect(catalog.planLimit, 'API実測: planLimitは無料枠10').toBe(10)

  await page.goto(`/organizations/${ORG_SLUG}/modules`)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  // カード描画完了を待つ(ModuleSettingsPanel: .pi-puzzle アイコン付きカード)
  await expect(
    page.locator('div.rounded-xl:has(.pi-puzzle)').first(),
    'モジュールカードが描画される',
  ).toBeVisible({ timeout: 20_000 })

  await expect(
    page.getByText(`有効な機能: ${catalog.enabledCount} / ${catalog.planLimit}`),
    `画面表示: 「有効な機能: ${catalog.enabledCount} / ${catalog.planLimit}」(grandfather除外)`,
  ).toBeVisible({ timeout: 10_000 })
})
