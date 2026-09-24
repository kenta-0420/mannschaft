/**
 * CMP-260907-0850 / 0851 / 0852 — 「UI操作だけで成立する」実機E2E。
 *
 * 前提（モックなし・実サーバー必須）:
 *   BE  http://localhost:8083   FE  http://localhost:3003
 *   実行例:
 *     cd frontend && BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8083 \
 *       npx playwright test tests/e2e/real/ui-navigation --config=playwright-real.config.ts --reporter=list
 *
 * なぜ別ファイルなのか:
 *   既存の tests/e2e/real/receipts/f084-issuer-settings.spec.ts は、各ケースが
 *   `page.addInitScript` で localStorage.currentScope を直接書いて前提を作る。
 *   そのため「スコープ切替UIがどこにもマウントされていない」「領収書への導線が無い」
 *   といった導線側の欠陥（CMP-260907-0850/0851）を構造的に検出できない。
 *   本ファイルは **localStorage.currentScope を一切書かない**。既定の個人スコープから始めて、
 *   実ブラウザのクリックだけでスコープ切替・画面遷移・編集を行う。
 *
 * 認証: playwright-real.config.ts の setup-real-user が作る storageState をそのまま使う
 *       （追加ログインは行わない: CMP-260905-0514）。
 */
import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8083'

/** user 23 が ADMIN の組織。 */
const ORG_ADMIN_ID = 9
/** user 23 が ADMIN のチーム。 */
const TEAM_ADMIN_ID = 197
/** 決済モジュール（領収書を含む）のモジュールID（`GET /api/v1/modules` で実測）。 */
const PAYMENT_MODULE_ID = 30

interface MyScope {
  id: number
  slug: string
  name: string
  role: string
}

type MyOrg = MyScope

/** `/api/v1/me/organizations` から対象組織を実測で引く（slug をハードコードしない）。 */
async function fetchMyOrg(page: Page, orgId: number): Promise<MyOrg> {
  const res = await page.request.get(`${API}/api/v1/me/organizations`)
  expect(res.status(), await res.text()).toBe(200)
  const orgs = (await res.json()).data as MyOrg[]
  const org = orgs.find(o => o.id === orgId)
  expect(org, `組織 ${orgId} が /api/v1/me/organizations に無い`).toBeTruthy()
  return org!
}

/** `/api/v1/me/teams` から対象チームを実測で引く（slug をハードコードしない）。 */
async function fetchMyTeam(page: Page, teamId: number): Promise<MyScope> {
  const res = await page.request.get(`${API}/api/v1/me/teams`)
  expect(res.status(), await res.text()).toBe(200)
  const teams = (await res.json()).data as MyScope[]
  const team = teams.find(t => t.id === teamId)
  expect(team, `チーム ${teamId} が /api/v1/me/teams に無い`).toBeTruthy()
  return team!
}

/**
 * 画面上で実際に見えている方のロケータ。
 * ScopeNavDropdown は AppHeader と GlobalSidebar の両方に置かれており、
 * ビューポート幅によってどちらかだけが表示される。
 */
function visible(page: Page, selector: string) {
  return page.locator(`${selector}:visible`).first()
}

/**
 * スコープページを開き、ハンバーガー（ScopePageShell の scope-sidebar-toggle）を
 * クリックしてスコープサイドバー Drawer を開く。
 * サイドバーのカテゴリが折り畳まれている場合に備え「運営・予算」カテゴリも展開する。
 */
async function openScopeSidebar(page: Page, scopePath: string) {
  await page.goto(scopePath)
  await waitForHydration(page)

  const toggle = page.getByTestId('scope-sidebar-toggle')
  await expect(toggle, 'スコープサイドバーを開くボタンが無い').toBeVisible({ timeout: 30_000 })
  await toggle.click()

  const nav = page.locator('.p-drawer nav')
  await expect(nav).toBeVisible({ timeout: 20_000 })

  // カテゴリの開閉状態は localStorage に保存されるため、閉じている場合だけ開く
  const receiptsLink = nav.locator('a[href="/admin/receipts"]')
  if (!(await receiptsLink.isVisible())) {
    const category = nav.locator('button', { hasText: '運営・予算' }).first()
    await expect(category, 'サイドバーに「運営・予算」カテゴリが無い').toBeVisible({
      timeout: 20_000,
    })
    await category.click()
  }
}

test.describe('CMP-260907-0850/0851/0852 UI操作起点の導線（実機）', () => {
  test.setTimeout(180_000)

  // ─────────────────────────────────────────────────────────────
  // ケース1: CMP-260907-0850
  //   個人スコープ（localStorage 未設定）から、ヘッダのスコープ切替メニューを
  //   実際にクリックして組織へ切り替えると /admin/receipts が組織スコープで開く。
  // ─────────────────────────────────────────────────────────────
  test('ケース1: ヘッダのスコープ切替メニューで組織へ切り替えると /admin/receipts が組織スコープで開く', async ({
    page,
  }) => {
    // --- 切替前: 既定の個人スコープでは案内（ScopeSwitchHint）が出るだけ ---
    await page.goto('/admin/receipts')
    await waitForHydration(page)
    await expect(page.getByTestId('scope-switch-hint')).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('.p-datatable')).toHaveCount(0)

    // 現在スコープが個人であること（アプリ自身の永続化状態を読み取るだけ。書き込みはしない）
    const beforeScope = await page.evaluate(() => window.localStorage.getItem('currentScope'))
    expect(
      beforeScope === null || JSON.parse(beforeScope).type === 'personal',
      `開始時点で個人スコープではない: ${beforeScope}`,
    ).toBe(true)

    const org = await fetchMyOrg(page, ORG_ADMIN_ID)

    // --- 実操作: ヘッダの「組織」ドロップダウンを開いて対象組織を選ぶ ---
    await visible(page, '[data-testid="scope-nav-dropdown-toggle-ORGANIZATION"]').click()
    const jumpItem = visible(page, `[data-testid="scope-nav-dropdown-scope-${ORG_ADMIN_ID}"]`)
    await expect(jumpItem).toBeVisible({ timeout: 20_000 })
    await jumpItem.click()

    // ドロップダウンは router.push するだけ。現在スコープの同期は useScopeRouteSync が行う。
    await expect(page).toHaveURL(new RegExp(`/organizations/${org.slug}(\\?|$|/)`), {
      timeout: 20_000,
    })
    await waitForHydration(page)
    await expect
      .poll(
        async () => {
          const raw = await page.evaluate(() => window.localStorage.getItem('currentScope'))
          return raw ? JSON.parse(raw) : null
        },
        {
          timeout: 20_000,
          message: '組織ページを開いても現在スコープが組織に切り替わらない',
        },
      )
      .toMatchObject({ type: 'organization', id: String(ORG_ADMIN_ID) })

    // --- 切替後: /admin/receipts が組織スコープで開く ---
    const calls: string[] = []
    page.on('response', (r) => {
      if (r.url().includes('/api/v1/admin/receipts')) calls.push(`${r.status()} ${r.url()}`)
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)

    // 個人スコープの案内は出ない
    await expect(page.getByTestId('scope-switch-hint')).toHaveCount(0)
    // 一覧（DataTable）が描画される
    await expect(page.locator('.p-datatable')).toBeVisible({ timeout: 20_000 })

    await expect
      .poll(() => calls.length, { timeout: 20_000, message: '一覧APIが一度も呼ばれていない' })
      .toBeGreaterThan(0)
    expect(
      calls.filter(c => c.includes(`scopeId=${ORG_ADMIN_ID}`)),
      `組織 ${ORG_ADMIN_ID} のスコープで一覧APIが呼ばれていない: ${calls.join(' / ')}`,
    ).not.toHaveLength(0)
    expect(
      calls.filter(c => Number(c.split(' ')[0]) >= 400),
      `一覧APIが失敗している: ${calls.join(' / ')}`,
    ).toHaveLength(0)
  })

  // ─────────────────────────────────────────────────────────────
  // ケース2: CMP-260907-0851
  //   サイドバーの「領収書」「領収書の発行者設定」リンクをクリックして到達する
  //   （URL 直打ちをしない）。
  //
  //   なぜ組織ではなくチームなのか（実測に基づく）:
  //     項目は moduleSlug='payment' を要求するが、決済モジュール（module id 30）の
  //     `levelAvailability` は ORGANIZATION=false / TEAM=true である
  //     （`GET /api/v1/modules` で実測）。すなわち組織では payment を有効化できず、
  //     組織サイドバーの領収書項目は構造上どの組織でも表示されない。
  //     この事実自体は製品側の欠陥として別途報告し、本ケースは実際に到達可能な
  //     チームサイドバー（TeamSidebar.vue）で導線を検証する。
  //
  //   前提: 対象チームの payment を API で有効化し、終了時に元の状態へ戻す（共有DBのため）。
  // ─────────────────────────────────────────────────────────────
  test('ケース2: チームサイドバーの「領収書」リンクから領収書一覧・発行者設定へ到達できる', async ({
    page,
  }) => {
    await page.goto('/')
    const team = await fetchMyTeam(page, TEAM_ADMIN_ID)

    // --- 前提づくり: payment モジュールの状態を実測し、無効なら有効化する ---
    // チームモジュール一覧は「有効なもの」だけを返すため、不在＝無効とみなす。
    const modulesRes = await page.request.get(`${API}/api/v1/teams/${team.slug}/modules`)
    expect(modulesRes.status(), await modulesRes.text()).toBe(200)
    const modules = (await modulesRes.json()).data as Array<{
      moduleId: number
      moduleSlug: string
      isEnabled: boolean
    }>
    const paymentWasEnabled = modules.some(m => m.moduleSlug === 'payment' && m.isEnabled)

    if (!paymentWasEnabled) {
      const toggled = await page.request.patch(
        `${API}/api/v1/teams/${team.slug}/modules/${PAYMENT_MODULE_ID}/toggle`,
        { data: { moduleId: PAYMENT_MODULE_ID, enabled: true } },
      )
      expect(toggled.status(), await toggled.text()).toBeLessThan(300)
      const after = await page.request.get(`${API}/api/v1/teams/${team.slug}/modules`)
      const enabledSlugs = ((await after.json()).data as Array<{ moduleSlug: string }>).map(
        m => m.moduleSlug,
      )
      expect(enabledSlugs, 'payment モジュールを有効化できなかった').toContain('payment')
    }

    try {
      await openScopeSidebar(page, `/teams/${team.slug}`)

      // --- 実操作①: 「領収書」リンクをクリック ---
      const receiptsLink = visible(page, 'nav a[href="/admin/receipts"]')
      await expect(
        receiptsLink,
        'サイドバーに領収書一覧への導線が無い（CMP-260907-0851 の退行）',
      ).toBeVisible({ timeout: 20_000 })
      await receiptsLink.click()

      await expect(page).toHaveURL(/\/admin\/receipts$/, { timeout: 20_000 })
      await waitForHydration(page)
      // サイドバー経由なので現在スコープは組織のまま → 一覧が描画される
      await expect(page.getByTestId('scope-switch-hint')).toHaveCount(0)
      await expect(page.locator('.p-datatable')).toBeVisible({ timeout: 20_000 })

      // --- 実操作②: 発行者設定へも同じくサイドバーから到達できる ---
      await openScopeSidebar(page, `/teams/${team.slug}`)
      const settingsLink = visible(page, 'nav a[href="/admin/receipt-settings"]')
      await expect(
        settingsLink,
        'サイドバーに発行者設定への導線が無い（CMP-260907-0851 の退行）',
      ).toBeVisible({ timeout: 20_000 })
      await settingsLink.click()

      await expect(page).toHaveURL(/\/admin\/receipt-settings$/, { timeout: 20_000 })
      await waitForHydration(page)
      await expect(page.getByRole('heading', { name: '発行者設定' }).first()).toBeVisible({
        timeout: 20_000,
      })
      await expect(page.getByText('この操作を行う権限がありません')).toHaveCount(0)
    } finally {
      // 共有DBなので前提づくりで変えた状態は必ず戻す
      if (!paymentWasEnabled) {
        await page.request.patch(
          `${API}/api/v1/teams/${team.slug}/modules/${PAYMENT_MODULE_ID}/toggle`,
          { data: { moduleId: PAYMENT_MODULE_ID, enabled: false } },
        )
      }
    }
  })

  // ─────────────────────────────────────────────────────────────
  // ケース3: CMP-260907-0852
  //   組織「基本情報」タブの編集ボタンから基本情報を編集して保存できる。
  //   共有DBなので、一意なサフィックスを付けて変更し、必ず元の名前へ戻す。
  // ─────────────────────────────────────────────────────────────
  test('ケース3: 組織「基本情報」タブの編集ボタンから組織名を編集して保存できる', async ({ page }) => {
    await page.goto('/')
    const org = await fetchMyOrg(page, ORG_ADMIN_ID)

    await page.goto(`/organizations/${org.slug}/info`)
    await waitForHydration(page)

    const editButton = page.getByTestId('org-basic-info-edit-button')
    await expect(
      editButton,
      '基本情報タブに編集ボタンが無い（CMP-260907-0852 の退行）',
    ).toBeVisible({ timeout: 20_000 })

    await editButton.click()
    await expect(page.getByTestId('org-basic-info-edit-dialog')).toBeVisible({ timeout: 10_000 })

    const nameInput = page.getByTestId('org-basic-info-name')
    const originalName = await nameInput.inputValue()
    expect(originalName, 'ダイアログに現在の組織名がプリフィルされていない').toBeTruthy()

    const editedName = `${originalName} E2E${Date.now()}`

    try {
      await nameInput.fill(editedName)
      await page.getByTestId('org-basic-info-save').click()

      // 保存成功トースト → ダイアログが閉じる → 画面表示に反映される
      await expect(page.getByText('基本情報を更新しました')).toBeVisible({ timeout: 20_000 })
      await expect(page.getByTestId('org-basic-info-edit-dialog')).toBeHidden({ timeout: 10_000 })
      await expect(page.getByText(editedName, { exact: false }).first()).toBeVisible({
        timeout: 20_000,
      })

      // リロードしても永続していること（画面から確認）
      await page.reload()
      await waitForHydration(page)
      await expect(page.getByText(editedName, { exact: false }).first()).toBeVisible({
        timeout: 20_000,
      })
    } finally {
      // 共有DBなので元の名前へ必ず戻す（戻す操作も UI から行う）
      await page.goto(`/organizations/${org.slug}/info`)
      await waitForHydration(page)
      await page.getByTestId('org-basic-info-edit-button').click()
      await expect(page.getByTestId('org-basic-info-edit-dialog')).toBeVisible({ timeout: 10_000 })
      await page.getByTestId('org-basic-info-name').fill(originalName)
      await page.getByTestId('org-basic-info-save').click()
      await expect(page.getByTestId('org-basic-info-edit-dialog')).toBeHidden({ timeout: 20_000 })
    }

    // 復元できたことを実測で確かめる
    const restored = await fetchMyOrg(page, ORG_ADMIN_ID)
    expect(restored.name, '組織名を元に戻せていない（共有DBを汚したまま）').toBe(originalName)
  })
})
