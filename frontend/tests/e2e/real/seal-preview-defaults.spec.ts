/**
 * F05.3 電子印鑑 — 実機 E2E テスト
 *
 * テストID: SEAL-REAL-001 〜 SEAL-REAL-003
 *
 * 対象:
 *   - PR #1921: sealId 不一致根治 / 0件時の初回自動生成
 *   - PR #1923: 印影の上下左右中央揃え（SVG width=100%）
 *   - PR #1924: PUT /scope-defaults エンドポイント追加
 *
 * 認証戦略:
 *   storageState の古いトークンをクリアしてから loginViaApi で毎回フレッシュログイン。
 *   BE の refresh_token ローテ競合を防ぐため clearCookies() が必須。
 *   （memory: feedback_e2e_real_single_session_token_rotation）
 *
 * 実行方法:
 *   cd frontend && BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/seal-preview-defaults.spec.ts --reporter=list
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const E2E_USER = {
  email: 'e2e-user@test.mannschaft.local',
  password: 'TestPass2026!',
}

// FE proxy が未設定の環境でも確実に BE に到達できるよう直指定
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

/**
 * WSL2 mirrored モード対応 API ブリッジ。
 *
 * FE dev サーバーが NUXT_API_PROXY=true なしで動いている場合、ブラウザからの
 * /api/v1/** リクエストは http://localhost:8080 に直接向かう。
 * しかし Windows 側の curl.exe / Node.js http は WSL2 localhost:8080 に
 * ECONNREFUSED になる（Chromium ブラウザは到達可能だが Set-Cookie domain が
 * WSL2 IP になるため Cookie が localhost:8080 宛に送られなくなる場合がある）。
 *
 * 対処: page.route() で http://localhost:8080/api/v1/** を Node.js fetch 経由で
 * API_BASE_URL (WSL2 IP) に中継する。レスポンスの Set-Cookie を
 * domain=localhost に書き換え、ブラウザの Cookie ジャーに正しく入れる。
 * ACAO ヘッダを FE オリジン (localhost:3000) に固定して CORS を通す。
 *
 * API_BASE_URL が localhost:8080 のままなら中継不要（環境が localhost で
 * 到達可能な場合）。WSL2 IP が設定されている場合のみブリッジを起動する。
 *
 * （memory: feedback_e2e_wsl2_cors_apibridge）
 */
async function installApibridge(page: import('@playwright/test').Page): Promise<void> {
  if (API_BASE_URL === 'http://localhost:8080') return

  const FE_ORIGIN = 'http://localhost:3000'

  await page.route('http://localhost:8080/api/v1/**', async (route) => {
    const req = route.request()
    const targetUrl = req.url().replace('http://localhost:8080', API_BASE_URL)

    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers())) {
      headers[k] = v
    }
    // CORS を通すために origin を FE オリジンに固定
    headers['origin'] = FE_ORIGIN

    const postData = req.postDataBuffer()
    const body = postData as BodyInit | null

    const beRes = await fetch(targetUrl, {
      method: req.method(),
      headers,
      body,
      redirect: 'manual',
    })

    // Set-Cookie の domain を localhost に書き換えてブラウザに正しく渡す
    const resHeadersRaw = beRes.headers
    const resHeaders: Record<string, string> = {}
    resHeadersRaw.forEach((v, k) => {
      if (k.toLowerCase() === 'set-cookie') {
        resHeaders[k] = v.replace(/;\s*domain=[^;,]*/gi, '') + '; Domain=localhost'
      } else {
        resHeaders[k] = v
      }
    })
    // ACAO を FE オリジンに固定
    resHeaders['access-control-allow-origin'] = FE_ORIGIN
    resHeaders['access-control-allow-credentials'] = 'true'

    const buf = Buffer.from(await beRes.arrayBuffer())
    await route.fulfill({
      status: beRes.status,
      headers: resHeaders,
      body: buf,
    })
  })
}

/**
 * e2e-user の印鑑を確実に用意する（なければ regenerate API で生成）。
 * 再生成テストが常に「印鑑あり」の状態でスタートできることを保証する。
 *
 * 注: テスト前に0件状態にしてから再生成するアプローチは避ける。
 * SealService.initializeSeals が soft delete 済みレコードのユニーク制約に
 * 引っかかる既知の問題（BE 修正対応済: SealService + ElectronicSealRepository 修正済）
 * があり、BE デプロイ完了後は0件テストも追加可能。
 */
async function ensureSealsExist(
  page: import('@playwright/test').Page,
  userId: number,
): Promise<void> {
  // 再生成 API を呼んで既存の印鑑を UPDATE（なければ INSERT）
  const regenRes = await page.request.post(
    `${API_BASE_URL}/api/v1/users/${userId}/seals/regenerate`,
  )
  if (!regenRes.ok()) {
    throw new Error(`印鑑の準備失敗: ${regenRes.status()} ${await regenRes.text()}`)
  }
}

test.describe('F05.3 電子印鑑 実機E2E', () => {
  // BE の refresh_token ローテ競合を防ぐため直列実行
  test.describe.configure({ mode: 'serial' })

  let userId: number

  test.beforeEach(async ({ page }) => {
    // WSL2 mirrored 環境で FE ブラウザが localhost:8080 にアクセスできるよう API ブリッジを設置
    // （loginViaApi より先に呼ぶことで、ブラウザからの全 API コールがブリッジ経由になる）
    await installApibridge(page)

    // 古いトークンを完全クリアしてから新規ログイン
    await page.context().clearCookies()
    await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

    // userId を取得（loginViaApi と同じ API_BASE_URL で呼ぶことで Cookie が確実に送られる）
    const meRes = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
    if (!meRes.ok()) {
      throw new Error(`/api/v1/users/me 取得失敗: ${meRes.status()} ${await meRes.text()}`)
    }
    const me = (await meRes.json()).data as { id: number }
    userId = me.id

    // 印鑑が確実に存在する状態にする（再生成 API で UPDATE/生成）
    await ensureSealsExist(page, userId)

    // WSL2 環境では loginViaApi が WSL2 IP 経由でログインするため、
    // Set-Cookie の domain が WSL2 IP になる。ブラウザが localhost:8080 に
    // アクセスするときにも Cookie が送られるよう、domain を localhost に書き換える。
    // （ensureSealsExist 等の page.request コールは上で完了済み）
    if (API_BASE_URL !== 'http://localhost:8080') {
      const cookies = await page.context().cookies()
      await page.context().clearCookies()
      await page.context().addCookies(
        cookies.map((c) => ({
          ...c,
          domain: 'localhost',
        })),
      )
    }
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-001: 再生成ボタンを押すと印影が3件プレビューに表示される（PR #1921 検証）
  //   beforeEach で ensureSealsExist を呼んでいるため「既存印鑑の UPDATE」経路を検証する。
  //   ページを開いて再度「印鑑を再生成」ボタンを押し、toast + 3件表示を確認する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-001: 再生成ボタンを押すと印影が3件プレビューに表示される', async ({
    page,
  }) => {
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // ローディング完了を待つ
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    // 認証失敗 → /login リダイレクトの場合はフェイル
    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-001: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // 「印鑑を再生成」ボタンが表示されること
    const regenerateButton = page.getByRole('button', { name: '印鑑を再生成' })
    await expect(regenerateButton).toBeVisible({ timeout: 15_000 })
    await regenerateButton.scrollIntoViewIfNeeded()
    await regenerateButton.click()

    // 再生成完了を待つ（ローディングスピナーが消える）
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    // 「印鑑を再生成しました」toast が表示されること
    // seals.vue: notification.success('印鑑を再生成しました')
    const toastSummary = page
      .locator('.p-toast-summary')
      .filter({ hasText: '印鑑を再生成しました' })
    await expect(toastSummary).toBeVisible({ timeout: 15_000 })

    // 印鑑カードが3件表示されること（SealPreview.vue: div.grid > div ×3）
    // SealPreview は sm:grid-cols-3 の grid レイアウトで各印鑑を div に描画する
    const sealCards = page.locator('div.grid.gap-4 > div')
    await expect(sealCards).toHaveCount(3, { timeout: 15_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-002: 印鑑プレビューに SVG 印影が表示される（PR #1923: 中央揃え確認）
  //   beforeEach で ensureSealsExist を呼んでいるため印鑑は確実に存在する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-002: 印鑑プレビューに SVG 印影が表示される（v-html）', async ({ page }) => {
    // 印鑑設定ページへ遷移
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-002: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // SealPreview 内に SVG 要素が存在すること（PR #1923: v-html で印影を描画）
    // SealPreview.vue: <div v-html="sanitizeHtml(seal.svgData, { allowSvg: true })" />
    const svgEl = page.locator('div.grid.gap-4 svg').first()
    await expect(svgEl).toBeAttached({ timeout: 15_000 })
    await expect(svgEl).toBeVisible({ timeout: 10_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-003: デフォルト設定タブで保存が成功する（PR #1924: PUT /scope-defaults）
  //   beforeEach で ensureSealsExist を呼んでいるため印鑑は確実に存在する。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-003: デフォルト設定タブで保存が成功する', async ({ page }) => {
    // 印鑑設定ページへ遷移
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(
        `SEAL-REAL-003: /settings/seals が /login にリダイレクト。URL: ${page.url()}`,
      )
    }

    // 「デフォルト設定」タブをクリック（value="1" の Tab）
    // seals.vue: <Tab value="1">デフォルト設定</Tab>
    const defaultsTab = page.getByRole('tab', { name: 'デフォルト設定' })
    await expect(defaultsTab).toBeVisible({ timeout: 15_000 })
    await defaultsTab.click()

    // タブが選択状態になるまで待つ
    await expect(defaultsTab).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 })

    // 「保存」ボタンが表示されること（SealScopeDefaults.vue: <Button label="保存" />）
    const saveButton = page.getByRole('button', { name: '保存' })
    await expect(saveButton).toBeVisible({ timeout: 15_000 })

    // 保存ボタンをクリック
    await saveButton.click()

    // 「デフォルト設定を保存しました」toast が表示されること
    // seals.vue: notification.success('デフォルト設定を保存しました')
    // PrimeVue Toast の summary テキストは .p-toast-summary に入る
    const toastSummary = page
      .locator('.p-toast-summary')
      .filter({ hasText: 'デフォルト設定を保存しました' })
    await expect(toastSummary).toBeVisible({ timeout: 15_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-004: 使い方ボタンをクリックするとガイドモーダルが開く（PR #1945 検証）
  //   PageHeader の help フラグで「使い方」ボタンが追加されている。
  //   SealGuideModal.vue: data-testid="seal-guide-modal"
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-004: 使い方ボタンでガイドモーダルが開く', async ({ page }) => {
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(`SEAL-REAL-004: /settings/seals が /login にリダイレクト。URL: ${page.url()}`)
    }

    // PageHeader の「使い方」ボタン（aria-label="使い方" または role=button name="使い方"）
    // seals.vue: <PageHeader title="電子印鑑" help @help="showGuide = true" />
    const helpButton = page.getByRole('button', { name: '使い方' })
    await expect(helpButton).toBeVisible({ timeout: 15_000 })
    await helpButton.click()

    // SealGuideModal が開くこと（data-testid="seal-guide-modal"）
    const modal = page.locator('[data-testid="seal-guide-modal"]')
    await expect(modal).toBeVisible({ timeout: 10_000 })

    // モーダル内に「印鑑プレビュー」見出しが存在すること
    // SealGuideContent.vue: <h2>{{ t('settings.seal_guide.preview.title') }}</h2>
    // = 「印鑑プレビュー」
    const previewHeading = modal.getByRole('heading', { name: '印鑑プレビュー' })
    await expect(previewHeading).toBeVisible({ timeout: 5_000 })

    // モーダル内に「印鑑の再生成」見出しが存在すること
    // SealGuideContent.vue: <h2>{{ t('settings.seal_guide.regenerate.title') }}</h2>
    // = 「印鑑の再生成」
    const regenerateHeading = modal.getByRole('heading', { name: '印鑑の再生成' })
    await expect(regenerateHeading).toBeVisible({ timeout: 5_000 })

    // フッターの「閉じる」ボタンで閉じられること
    // SealGuideModal.vue: <template #footer><Button :label="$t('button.close')" .../></template>
    // common.json: button.close = "閉じる"
    // ダイアログには右上のXボタン（アイコンのみ）とフッターの「閉じる」テキストボタンの2つがあるため
    // フッター内のボタンを限定する（p-dialog-footer 配下）
    const closeBtn = modal.locator('[data-pc-section="footer"]').getByRole('button', { name: '閉じる' })
    await expect(closeBtn).toBeVisible({ timeout: 5_000 })
    await closeBtn.click()
    await expect(modal).not.toBeVisible({ timeout: 5_000 })
  })

  // ---------------------------------------------------------------------------
  // SEAL-REAL-005: デフォルト設定タブでチームスコープを追加→保存→削除できる（PR #1952 検証）
  //   SealScopeDefaults.vue: addScope() → emit('save') → seals.vue handleSaveDefaults()
  //   追加時と削除時どちらも PUT /scope-defaults を呼び toast が出る。
  // ---------------------------------------------------------------------------
  test('SEAL-REAL-005: チームスコープのデフォルトを追加・削除できる', async ({ page }) => {
    await page.goto('/settings/seals', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page
      .locator('.pi-spin')
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})

    if (page.url().includes('/login')) {
      throw new Error(`SEAL-REAL-005: /settings/seals が /login にリダイレクト。URL: ${page.url()}`)
    }

    // 「デフォルト設定」タブをクリック（value="1"）
    // seals.vue: <Tab value="1">デフォルト設定</Tab>
    const defaultsTab = page.getByRole('tab', { name: 'デフォルト設定' })
    await expect(defaultsTab).toBeVisible({ timeout: 15_000 })
    await defaultsTab.click()
    await expect(defaultsTab).toHaveAttribute('aria-selected', 'true', { timeout: 10_000 })

    // 「スコープを追加」ボタンが表示されること
    // SealScopeDefaults.vue: t('settings.seal.scope_defaults.add_button') = "スコープを追加"
    const addButton = page.getByRole('button', { name: 'スコープを追加' })
    await expect(addButton).toBeVisible({ timeout: 15_000 })
    await addButton.click()

    // スコープ追加ダイアログが開くこと
    // SealScopeDefaults.vue: <Dialog v-model:visible="showAddDialog" modal ...>
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // デフォルトでチーム選択モードになっているため、チーム選択ドロップダウンをクリック
    // SealScopeDefaults.vue: <Select v-model="newScopeId" :options="availableTargets" option-label="name" ...>
    // openAddDialog() 内で /api/v1/me/teams を呼ぶため、API レスポンスを待つ必要がある
    //
    // ダイアログ内に2つの .p-select がある（対象 / 使用する印鑑）。
    // 「対象」ドロップダウンが ".p-select" の最初、「使用する印鑑」が2番目。
    // ただし :loading="loadingTargets" 中はドロップダウンが loading 状態なので、
    // ロード完了後に再クリックする（loading state が detached になるまで待つ）。
    const targetSelect = dialog.locator('.p-select').first()

    // ローディング完了を待ってからクリック（/api/v1/me/teams 取得完了まで）
    await dialog
      .locator('.p-select-loading')
      .waitFor({ state: 'detached', timeout: 20_000 })
      .catch(() => {})
    await targetSelect.click()

    // PrimeVue Select のドロップダウンパネル（listbox）が表示されるまで待つ
    const listbox = page.locator('[role="listbox"]')
    await expect(listbox).toBeVisible({ timeout: 15_000 })

    // チームオプションが表示されるまで待つ
    const firstOption = page.getByRole('option').first()
    await expect(firstOption).toBeVisible({ timeout: 15_000 })
    await firstOption.click()

    // 「追加」ボタンが有効になること
    // SealScopeDefaults.vue: t('settings.seal.scope_defaults.add_scope_button') = "追加"
    const addScopeBtn = dialog.getByRole('button', { name: '追加' })
    await expect(addScopeBtn).toBeEnabled({ timeout: 5_000 })
    await addScopeBtn.click()

    // ダイアログが閉じること
    await expect(dialog).not.toBeVisible({ timeout: 10_000 })

    // 追加後に PUT /scope-defaults が呼ばれ「デフォルト設定を保存しました」toast が表示されること
    // SealScopeDefaults.vue: addScope() → emit('save') → seals.vue handleSaveDefaults()
    // → notification.success('デフォルト設定を保存しました')
    const addToast = page.locator('.p-toast-summary').filter({ hasText: 'デフォルト設定を保存しました' })
    await expect(addToast).toBeVisible({ timeout: 15_000 })

    // 追加されたスコープに「このスコープを削除」ボタン（ゴミ箱アイコン）があること
    // SealScopeDefaults.vue: :aria-label="t('settings.seal.scope_defaults.delete_tooltip')"
    // = 「このスコープを削除」
    const deleteButtons = page.locator('[aria-label="このスコープを削除"]')
    const count = await deleteButtons.count()
    expect(count).toBeGreaterThan(0)

    // 追加したスコープを削除し、toast が出ること
    // SealScopeDefaults.vue: removeScope() → emit('save') → seals.vue handleSaveDefaults()
    await deleteButtons.first().click()

    const deleteToast = page.locator('.p-toast-summary').filter({ hasText: 'デフォルト設定を保存しました' })
    await expect(deleteToast).toBeVisible({ timeout: 15_000 })
  })
})
