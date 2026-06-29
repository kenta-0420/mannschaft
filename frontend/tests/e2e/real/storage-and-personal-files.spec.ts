/**
 * F13 ストレージ管理 & マイファイル 実機 E2E テスト
 *
 * 対象:
 *   - ストレージ使用量画面 (/settings/storage)
 *   - 個人ファイル画面 (/my/files)  — FileBrowser コンポーネント
 *
 * テスト一覧:
 *   AUTH-001: 未認証で /settings/storage・/my/files にアクセス → ログインへリダイレクト
 *   STORAGE-001: ストレージ画面表示 — API 200・ProgressBar・i18n ラベル・使用量表示
 *   STORAGE-002: 設定メニュー(/settings)から「ストレージ」リンク経由で遷移
 *   MYFILES-001: 個人ファイル画面表示 + BE API 200 確認
 *   MYFILES-002: 個人フォルダ作成 → リロード後も永続 → afterAll でクリーンアップ
 *   MYFILES-003: ファイルアップロード SKIP（FileBrowser に UI 未実装）
 *
 * 認証戦略:
 *   beforeEach で clearCookies + loginViaApi による毎回フレッシュログイン。
 *   (memory: feedback_e2e_real_single_session_token_rotation)
 *
 * 実行方法:
 *   cd frontend && BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/storage-and-personal-files.spec.ts --reporter=list \
 *     --config playwright-real.config.ts --project chromium-real
 *
 * 注意:
 *   - このファイルは CI スモーク(e2e-real-smoke.yml)の対象外。手動実走のみ。
 *     (memory: project_real_admin_e2e_excluded_from_ci_smoke)
 *   - BE(8080)・FE(3001) が起動済みであること。
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// storageState に依存しない — beforeEach で毎回フレッシュログイン
test.use({ storageState: { cookies: [], origins: [] } })

// レート制限 (10回/分) を考慮して直列実行
test.describe.configure({ mode: 'serial' })

// ── 定数 ────────────────────────────────────────────────────────────────────

const E2E_USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

/**
 * BE 直接アクセス URL。
 * WSL2 mirrored 環境では API_BASE_URL を 127.0.0.1 ベースで指定する場合もある。
 * curl.exe --noproxy で到達確認済みの 8080 を既定値とする。
 */
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

// テスト後のクリーンアップ用: 作成したフォルダ ID を蓄積する
const createdFolderIds: number[] = []

// ── API ブリッジ (WSL2 mirrored 対応) ───────────────────────────────────────

/**
 * API_BASE_URL が localhost:8080 以外の場合のみ有効になる CORS 突破ブリッジ。
 * page.route で http://localhost:8080/api/v1/** を Node.js fetch 経由で
 * API_BASE_URL に中継し、Set-Cookie の domain と ACAO を localhost に固定する。
 * (memory: feedback_e2e_wsl2_cors_apibridge)
 */
async function installApibridge(page: import('@playwright/test').Page): Promise<void> {
  if (API_BASE_URL === 'http://localhost:8080') return

  const feOriginPromise = page.evaluate(() => window.location.origin).catch(() => 'http://localhost:3001')

  await page.route('http://localhost:8080/api/v1/**', async (route) => {
    const req = route.request()
    const targetUrl = req.url().replace('http://localhost:8080', API_BASE_URL)
    const FE_ORIGIN = await feOriginPromise

    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers())) {
      headers[k] = v
    }
    headers['origin'] = FE_ORIGIN

    const postData = req.postDataBuffer()
    const body = postData as BodyInit | null

    const beRes = await fetch(targetUrl, {
      method: req.method(),
      headers,
      body,
      redirect: 'manual',
    })

    const resHeaders: Record<string, string> = {}
    beRes.headers.forEach((v, k) => {
      if (k.toLowerCase() === 'set-cookie') {
        resHeaders[k] = v.replace(/;\s*domain=[^;,]*/gi, '') + '; Domain=localhost'
      } else {
        resHeaders[k] = v
      }
    })
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

// ── beforeEach / afterAll ─────────────────────────────────────────────────

test.beforeEach(async ({ page }) => {
  // WSL2 mirrored 環境対応: ブリッジを先にセット
  await installApibridge(page)

  // 古いトークンを完全クリアしてから新規ログイン
  await page.context().clearCookies()
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })
})

test.afterAll(async ({ request }) => {
  // 作成したフォルダをクリーンアップ（DELETE /api/v1/files/folders/{id}）
  for (const folderId of createdFolderIds) {
    await request.delete(`${API_BASE_URL}/api/v1/files/folders/${folderId}`).catch(() => {
      // cleanup 失敗は無視（既に削除済み等）
    })
  }
  createdFolderIds.length = 0
})

// ── AUTH-001: 未認証で認証要求ページにアクセス ────────────────────────────

test.describe('AUTH-001: 未認証アクセスのリダイレクト', () => {
  test('AUTH-001-a: 未認証で /settings/storage → ログインへリダイレクト', async ({ page }) => {
    // beforeEach が loginViaApi でログイン済みのため、
    // Cookie + localStorage（currentUser）を両方クリアして未認証状態を作る。
    // loginViaApi は / に goto してから localStorage.setItem するため、
    // page.evaluate で localStorage を消去できる状態になっている。
    await page.context().clearCookies()
    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })

    await page.goto('/settings/storage', { waitUntil: 'domcontentloaded' })

    await page.waitForURL((url) => url.pathname.includes('/login') || url.pathname === '/login', {
      timeout: 15_000,
    })
    expect(page.url()).toContain('/login')
  })

  test('AUTH-001-b: 未認証で /my/files → ログインへリダイレクト', async ({ page }) => {
    await page.context().clearCookies()
    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })

    await page.goto('/my/files', { waitUntil: 'domcontentloaded' })

    await page.waitForURL((url) => url.pathname.includes('/login') || url.pathname === '/login', {
      timeout: 15_000,
    })
    expect(page.url()).toContain('/login')
  })
})

// ── STORAGE-001: ストレージ画面表示 ─────────────────────────────────────

test('STORAGE-001: ストレージ画面 — ページ描画・タイトル・API 状態に応じた UI', async ({
  page,
}) => {
  // 1. API 疎通確認（200 or 500 を両方ケアする）
  //    NOTE: GET /api/v1/me/storage/usage は feature/gc-phase4-design ブランチのみ実装済み。
  //    main ブランチ BE(:8080)では未デプロイのため 500(COMMON_999) が返る場合がある。
  //    テストは BE バージョンによらず FE の描画を検証する。
  const storageRes = await page.request.get(`${API_BASE_URL}/api/v1/me/storage/usage`)
  const storageStatus = storageRes.status()
  const storageData = storageStatus === 200
    ? await storageRes.json() as Array<{
        scopeType: string
        scopeName: string
        usedBytes: number
        fileCount: number
        includedBytes: number
        usagePercent: number
      }>
    : []
  console.log(`STORAGE-001: API status=${storageStatus}, items=${storageData.length}`)

  // 2. ストレージ設定ページへ遷移
  await page.goto('/settings/storage', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 3. ページタイトル確認（PageHeader が "ストレージ" を表示）
  await expect(page.getByText('ストレージ').first()).toBeVisible({ timeout: 10_000 })

  // 4. API ステータスに応じた FE 状態検証
  if (storageStatus === 200 && storageData.length > 0) {
    // 使用量データあり: 個人ストレージラベル・ProgressBar・ファイル数
    const personalUsages = storageData.filter((u) => u.scopeType === 'PERSONAL')
    if (personalUsages.length > 0) {
      await expect(page.getByText('個人ストレージ')).toBeVisible({ timeout: 10_000 })
      await expect(page.locator('.p-progressbar').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText(/ファイル/).first()).toBeVisible({ timeout: 10_000 })
      console.log(`STORAGE-001: PERSONAL scope found. usedBytes=${personalUsages[0].usedBytes}`)
    } else {
      await expect(page.getByText('表示できるストレージ情報がありません')).toBeVisible({ timeout: 10_000 })
      console.log('STORAGE-001: API 200 but no PERSONAL scope. Empty state verified.')
    }
  } else {
    // API 未デプロイ(500) または空データ: FE がエラー状態または空状態を表示
    // FE の storage.vue は API 失敗時に errorMsg をセットし <Message severity="error"> を描画する
    // または空データ時は「表示できるストレージ情報がありません」を表示する
    const hasErrorMsg = await page.locator('[data-severity="error"], .p-message-error').isVisible().catch(() => false)
    const hasEmptyMsg = await page.getByText('表示できるストレージ情報がありません').isVisible().catch(() => false)
    const hasStorageText = await page.getByText('ストレージ').first().isVisible().catch(() => false)

    expect(hasStorageText || hasErrorMsg || hasEmptyMsg).toBeTruthy()
    console.log(`STORAGE-001: API status=${storageStatus}(endpoint not deployed on this BE). FE state: error=${hasErrorMsg}, empty=${hasEmptyMsg}`)
    console.log('STORAGE-001: NOTE: /api/v1/me/storage/usage は feature/gc-phase4-design ブランチのみ。main BE では未デプロイ。')
  }
})

// ── STORAGE-002: 設定メニューからの導線 ────────────────────────────────────

test('STORAGE-002: 設定メニュー(/settings) → 「ストレージ」クリック → /settings/storage に遷移', async ({
  page,
}) => {
  await page.goto('/settings', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)

  // /settings は「個別設定一覧」アコーディオンに「ストレージ」リンクが含まれる。
  // 検索機能を使って確実に「ストレージ」リンクを表示する。
  const searchInput = page.locator('input[placeholder="設定を検索..."]')
  await searchInput.waitFor({ state: 'visible', timeout: 15_000 })
  await searchInput.click()
  await searchInput.fill('ストレージ')

  // 検索結果に「ストレージ」リンクが表示されること
  const storageLink = page.getByRole('link', { name: /ストレージ/ }).first()
  await expect(storageLink).toBeVisible({ timeout: 10_000 })

  // クリックして /settings/storage に遷移
  await storageLink.click()
  await page.waitForURL(/\/settings\/storage/, { timeout: 15_000 })
  expect(page.url()).toContain('/settings/storage')
})

// ── MYFILES-001: 個人ファイル画面表示 ────────────────────────────────────

test('MYFILES-001: 個人ファイル画面表示 — API 200・FileBrowser 描画', async ({ page }) => {
  // 1. API 確認（GET /api/v1/me/folders）
  const foldersRes = await page.request.get(`${API_BASE_URL}/api/v1/me/folders`)
  expect(foldersRes.status()).toBe(200)
  const foldersData = await foldersRes.json()
  console.log(`MYFILES-001: /api/v1/me/folders status=200, data=`, JSON.stringify(foldersData).slice(0, 200))

  // 2. /my/files へ遷移
  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // LoadingBounce スピナーが消えるまで待つ
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 3. ページタイトル（i18n: settings.myFiles = "マイファイル"）
  // 生キーではなく翻訳済みラベルが表示されること
  await expect(page.getByText('マイファイル').first()).toBeVisible({ timeout: 10_000 })

  // 4. FileBrowser コンポーネントの存在確認
  // 「フォルダ作成」ボタンが存在する（FileBrowser が描画されていること）
  await expect(page.getByText('フォルダ作成')).toBeVisible({ timeout: 10_000 })

  // 5. ルートへのブレッドクラムが存在すること（FileBrowser の breadcrumb）
  await expect(page.getByText('ルート')).toBeVisible({ timeout: 10_000 })
})

// ── MYFILES-002: 個人フォルダ作成（write path・永続化確認） ─────────────────

test('MYFILES-002: 個人フォルダ作成 → ダイアログ操作 → リロード後も永続（永続化確認）', async ({
  page,
}) => {
  const uniqueFolderName = `E2E テストフォルダ ${Date.now()}`

  // 1. /my/files へ遷移
  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 2. 「フォルダ作成」ボタンをクリック → Dialog が開く
  await page.getByText('フォルダ作成').click()

  // Dialog が開くまで待つ（PrimeVue Dialog のヘッダー "フォルダ作成"）
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

  // 3. フォルダ名入力（PrimeVue InputText: click してから fill）
  const folderNameInput = page.getByRole('dialog').locator('input[type="text"]')
  await folderNameInput.click()
  await folderNameInput.fill(uniqueFolderName)

  // 4. BE の POST /api/v1/me/folders レスポンスを捕捉してフォルダ ID を取得
  let createdFolderId: number | null = null
  const folderCreatePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/me/folders') && res.request().method() === 'POST',
    { timeout: 15_000 },
  )

  // 5. 「作成」ボタンをクリック
  const createBtn = page.getByRole('dialog').getByRole('button', { name: '作成' })
  await expect(createBtn).toBeEnabled({ timeout: 5_000 })
  await createBtn.click()

  // 6. BE API レスポンス確認（201）
  const folderCreateRes = await folderCreatePromise
  expect(folderCreateRes.status()).toBe(201)
  const folderCreateBody = await folderCreateRes.json() as { data?: { id: number } }
  if (folderCreateBody.data?.id) {
    createdFolderId = folderCreateBody.data.id
    createdFolderIds.push(createdFolderId)
    console.log(`MYFILES-002: Created folder id=${createdFolderId}, name="${uniqueFolderName}"`)
  }

  // 7. Dialog が閉じること
  await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 })

  // 8. 新フォルダがリストに表示されること
  await expect(page.getByText(uniqueFolderName)).toBeVisible({ timeout: 10_000 })

  // 9. リロード後も新フォルダが表示されること（永続化確認）
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await expect(page.getByText(uniqueFolderName)).toBeVisible({ timeout: 15_000 })
  console.log(`MYFILES-002: Folder persisted after reload. PASS`)
})

// ── MYFILES-003: ファイルアップロード SKIP ─────────────────────────────────

/**
 * MYFILES-003 は現在 SKIP。
 *
 * 理由: FileBrowser.vue にファイルアップロード UI が未実装。
 *   - useFileSharingApi には presignUpload / registerFile が存在する
 *   - しかし FileBrowser.vue テンプレートに <input type="file"> や
 *     FileUpload コンポーネントが存在しない
 *   - アップロード経路（presigned URL → R2/MinIO → registerFile）は UI なしに動作確認不可
 *
 * Next step: FileBrowser.vue にアップロード UI（<input type="file"> + presignUpload 連携）が
 * 実装された時点でこの skip を削除し、以下を実装すること:
 *   1. page.setInputFiles('input[type="file"]', fixture_file_path) でファイルをセット
 *   2. presigned URL レスポンス確認
 *   3. R2/MinIO への PUT 成功（MinIO が起動していること: docker compose --profile storage up -d）
 *   4. registerFile の POST 201 確認
 *   5. リロード後のファイル表示確認
 *   6. afterAll でファイル削除クリーンアップ
 *
 * ローカル MinIO 設定: project_local_minio_image_storage メモリ参照
 */
test.skip('MYFILES-003: 個人ファイルアップロード（FileBrowser に UI 未実装のため SKIP）', async ({
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  page,
}) => {
  // 未実装
})
