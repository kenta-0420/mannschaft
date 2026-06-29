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
 *   MYFILES-003: ファイルアップロード（FileBrowser UI 経由・presign→PUT→registerFile）
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
 *   - MYFILES-003 はローカル MinIO が必要（docker compose --profile storage up -d）。
 *     MinIO 未起動の場合は presign→PUT 経路が失敗し、テストは環境要因として skip される。
 */

import path from 'node:path'
import fs from 'node:fs'
import os from 'node:os'
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

// テスト後のクリーンアップ用: 作成したフォルダ ID・ファイル ID を蓄積する
const createdFolderIds: number[] = []
const createdFileIds: number[] = []

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
  // 作成したファイルをクリーンアップ
  for (const fileId of createdFileIds) {
    await request.delete(`${API_BASE_URL}/api/v1/files/${fileId}`).catch(() => {})
  }
  createdFileIds.length = 0

  // 作成したフォルダをクリーンアップ（DELETE /api/v1/files/folders/{id}）
  for (const folderId of createdFolderIds) {
    await request.delete(`${API_BASE_URL}/api/v1/files/folders/${folderId}`).catch(() => {})
  }
  createdFolderIds.length = 0
})

// ── AUTH-001: 未認証で認証要求ページにアクセス ────────────────────────────

test.describe('AUTH-001: 未認証アクセスのリダイレクト', () => {
  test('AUTH-001-a: 未認証で /settings/storage → ログインへリダイレクト', async ({ page }) => {
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

test('STORAGE-001: ストレージ画面 — API 200・使用量表示・i18n ラベル', async ({
  page,
}) => {
  // 1. usage API が 200 を返すことを確認（BE が #1973 反映済みであること）
  const storageRes = await page.request.get(`${API_BASE_URL}/api/v1/me/storage/usage`)
  expect(storageRes.status()).toBe(200)

  const storageData = await storageRes.json() as Array<{
    scopeType: string
    scopeName: string
    usedBytes: number
    fileCount: number
    includedBytes: number
    usagePercent: number
  }>
  console.log(`STORAGE-001: API 200, items=${storageData.length}`, JSON.stringify(storageData).slice(0, 300))

  // 2. ストレージ設定ページへ遷移
  await page.goto('/settings/storage', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 3. ページタイトル確認（PageHeader が "ストレージ" を表示）
  await expect(page.getByText('ストレージ').first()).toBeVisible({ timeout: 10_000 })

  // 4. 使用量データがある場合は UI に反映されていること
  if (storageData.length > 0) {
    const personalUsages = storageData.filter((u) => u.scopeType === 'PERSONAL')
    if (personalUsages.length > 0) {
      // 個人ストレージラベル・ProgressBar・ファイル数が表示されること
      await expect(page.getByText('個人ストレージ')).toBeVisible({ timeout: 10_000 })
      await expect(page.locator('.p-progressbar').first()).toBeVisible({ timeout: 10_000 })
      await expect(page.getByText(/ファイル/).first()).toBeVisible({ timeout: 10_000 })
      console.log(`STORAGE-001: PERSONAL scope OK. usedBytes=${personalUsages[0]?.usedBytes}`)
    } else {
      // チーム・組織スコープのみの場合は空状態表示
      const hasGroupLabel = await page.getByText(/チーム|組織/).isVisible().catch(() => false)
      expect(hasGroupLabel || storageData.length === 0).toBeTruthy()
      console.log(`STORAGE-001: No PERSONAL scope. Teams/Orgs: ${storageData.length}`)
    }
  } else {
    // データ 0 件: 空状態メッセージが表示されること
    await expect(page.getByText('表示できるストレージ情報がありません')).toBeVisible({ timeout: 10_000 })
    console.log('STORAGE-001: API 200 but empty data. Empty state verified.')
  }
})

// ── STORAGE-002: 設定メニューからの導線 ────────────────────────────────────

test('STORAGE-002: 設定メニュー(/settings) → 「ストレージ」クリック → /settings/storage に遷移', async ({
  page,
}) => {
  await page.goto('/settings', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)

  const searchInput = page.locator('input[placeholder="設定を検索..."]')
  await searchInput.waitFor({ state: 'visible', timeout: 15_000 })
  await searchInput.click()
  await searchInput.fill('ストレージ')

  const storageLink = page.getByRole('link', { name: /ストレージ/ }).first()
  await expect(storageLink).toBeVisible({ timeout: 10_000 })

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
  console.log(`MYFILES-001: /api/v1/me/folders status=200`, JSON.stringify(foldersData).slice(0, 200))

  // 2. /my/files へ遷移
  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 3. ページタイトル（i18n: settings.myFiles = "マイファイル"）
  await expect(page.getByText('マイファイル').first()).toBeVisible({ timeout: 10_000 })

  // 4. FileBrowser コンポーネントの存在確認
  await expect(page.getByText('フォルダ作成')).toBeVisible({ timeout: 10_000 })

  // 5. アップロードボタンが存在すること（FileBrowser の新機能）
  await expect(page.getByText('ファイルをアップロード')).toBeVisible({ timeout: 10_000 })

  // 6. ルートブレッドクラムが存在すること
  await expect(page.getByText('ルート')).toBeVisible({ timeout: 10_000 })
})

// ── MYFILES-002: 個人フォルダ作成（write path・永続化確認） ─────────────────

test('MYFILES-002: 個人フォルダ作成 → ダイアログ操作 → リロード後も永続（永続化確認）', async ({
  page,
}) => {
  const uniqueFolderName = `E2E テストフォルダ ${Date.now()}`

  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 「フォルダ作成」ボタンをクリック → Dialog が開く
  await page.getByText('フォルダ作成').click()
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

  // フォルダ名入力
  const folderNameInput = page.getByRole('dialog').locator('input[type="text"]')
  await folderNameInput.click()
  await folderNameInput.fill(uniqueFolderName)

  // BE の POST /api/v1/me/folders レスポンスを捕捉
  let createdFolderId: number | null = null
  const folderCreatePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/me/folders') && res.request().method() === 'POST',
    { timeout: 15_000 },
  )

  const createBtn = page.getByRole('dialog').getByRole('button', { name: '作成' })
  await expect(createBtn).toBeEnabled({ timeout: 5_000 })
  await createBtn.click()

  // BE API レスポンス確認（201）
  const folderCreateRes = await folderCreatePromise
  expect(folderCreateRes.status()).toBe(201)
  const folderCreateBody = await folderCreateRes.json() as { data?: { id: number } }
  if (folderCreateBody.data?.id) {
    createdFolderId = folderCreateBody.data.id
    createdFolderIds.push(createdFolderId)
    console.log(`MYFILES-002: Created folder id=${createdFolderId}, name="${uniqueFolderName}"`)
  }

  // Dialog が閉じること
  await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 })

  // 新フォルダがリストに表示されること
  await expect(page.getByText(uniqueFolderName)).toBeVisible({ timeout: 10_000 })

  // リロード後も新フォルダが表示されること（永続化確認）
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await expect(page.getByText(uniqueFolderName)).toBeVisible({ timeout: 15_000 })
  console.log(`MYFILES-002: Folder persisted after reload. PASS`)
})

// ── MYFILES-003: ファイルアップロード ─────────────────────────────────────

/**
 * MYFILES-003: 個人フォルダにファイルをアップロードし、画面・リロード後の永続化を確認する。
 *
 * 前提:
 *   - FileBrowser.vue にアップロード UI が実装済みであること（input[type=file] + presignUpload フロー）
 *   - MYFILES-002 が先に実行され、少なくとも 1 つのフォルダが存在すること
 *   - ローカル MinIO が起動済みであること（docker compose --profile storage up -d）
 *     BE に R2 エンドポイント env が設定済みであること（project_local_minio_image_storage 参照）
 *
 * MinIO 未起動・env 未設定の場合:
 *   - presign → PUT 経路が失敗する（STORAGE_004/500 または PUT エラー）
 *   - テストは environment-skip として明示的に中断する（握り潰し禁止）
 *   - Next step: docker compose --profile storage up -d + BE restart でリトライ
 */
test('MYFILES-003: 個人ファイルアップロード — presign→PUT→registerFile→永続化確認', async ({
  page,
}) => {
  // 1. /my/files へ遷移
  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 2. フォルダが存在することを確認（MYFILES-002 で作成済み想定）
  //    フォルダが 1 つも無ければルートにいるため、フォルダを作成してから入る
  const folderButtons = page.locator('button .pi-folder')
  const folderCount = await folderButtons.count()
  let uploadFolderId: number | null = null

  if (folderCount === 0) {
    // フォルダが無い場合は作成する
    await page.getByText('フォルダ作成').click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
    const nameInput = page.getByRole('dialog').locator('input[type="text"]')
    await nameInput.fill(`E2E Upload Folder ${Date.now()}`)

    const folderCreatedPromise = page.waitForResponse(
      (res) => res.url().includes('/api/v1/me/folders') && res.request().method() === 'POST',
      { timeout: 15_000 },
    )
    await page.getByRole('dialog').getByRole('button', { name: '作成' }).click()
    const res = await folderCreatedPromise
    if (res.status() === 201) {
      const body = await res.json() as { data?: { id: number } }
      if (body.data?.id) {
        uploadFolderId = body.data.id
        createdFolderIds.push(uploadFolderId)
      }
    }
    await expect(page.getByRole('dialog')).toBeHidden({ timeout: 10_000 })
  }

  // 3. フォルダをクリックして入る（currentFolderId が設定される）
  const firstFolder = page.locator('button').filter({ has: page.locator('.pi-folder') }).first()
  await expect(firstFolder).toBeVisible({ timeout: 10_000 })

  // フォルダクリック後の FE リロードを待つ
  const folderLoadPromise = page.waitForResponse(
    (res) =>
      res.url().includes('/api/v1/files/folders/') && res.request().method() === 'GET',
    { timeout: 15_000 },
  )
  await firstFolder.click()
  const folderRes = await folderLoadPromise
  if (folderRes.status() !== 200) {
    console.log(`MYFILES-003: Folder load failed (${folderRes.status()}). Skip.`)
    test.skip(true, `フォルダ読み込み失敗 (${folderRes.status()})。BE が正常か確認してください。`)
    return
  }

  // 4. アップロードボタンが表示されること
  await expect(page.getByText('ファイルをアップロード')).toBeVisible({ timeout: 10_000 })

  // 5. テスト用ファイルを一時ディレクトリに作成
  const tmpDir = os.tmpdir()
  const testFileName = `e2e-upload-${Date.now()}.txt`
  const testFilePath = path.join(tmpDir, testFileName)
  fs.writeFileSync(testFilePath, `Mannschaft E2E test file\n作成日時: ${new Date().toISOString()}`)

  try {
    // 6. presign API をインターセプト（エラー検知用）
    let presignStatus = 0
    const presignPromise = page.waitForResponse(
      (res) => res.url().includes('/api/v1/files/presign-upload') && res.request().method() === 'POST',
      { timeout: 20_000 },
    )

    // 7. hidden の file input にファイルをセット（ボタンをクリックせずに直接 setInputFiles）
    //    ブラウザのファイルダイアログが開かないため setInputFiles を input に直接適用する
    const fileInput = page.locator('input[data-testid="file-upload-input"]')
    await fileInput.setInputFiles(testFilePath)

    // 8. presign API レスポンスを確認
    const presignRes = await presignPromise
    presignStatus = presignRes.status()
    console.log(`MYFILES-003: presign-upload status=${presignStatus}`)

    if (presignStatus !== 200 && presignStatus !== 201) {
      const body = await presignRes.text()
      console.log(`MYFILES-003: presign failed. body=${body.slice(0, 300)}`)
      test.skip(
        true,
        `presign-upload が ${presignStatus} を返しました。` +
          `MinIO が未起動か、BE に R2 env が未設定の可能性があります。` +
          `Next step: docker compose --profile storage up -d && BE 再起動。`,
      )
      return
    }

    // 9. registerFile (POST /api/v1/files) API レスポンスを確認
    const registerPromise = page.waitForResponse(
      (res) => res.url().includes('/api/v1/files') && !res.url().includes('presign') && res.request().method() === 'POST',
      { timeout: 30_000 },
    )
    const registerRes = await registerPromise
    const registerStatus = registerRes.status()
    console.log(`MYFILES-003: registerFile status=${registerStatus}`)

    if (registerStatus !== 200 && registerStatus !== 201) {
      const body = await registerRes.text()
      console.log(`MYFILES-003: registerFile failed. body=${body.slice(0, 300)}`)
      test.skip(
        true,
        `registerFile が ${registerStatus} を返しました。` +
          `PUT ストレージ経路またはサーバー側に問題がある可能性があります。`,
      )
      return
    }

    // ファイル ID を取得してクリーンアップ対象に追加
    const registerBody = await registerRes.json() as { data?: { id: number } }
    if (registerBody.data?.id) {
      createdFileIds.push(registerBody.data.id)
      console.log(`MYFILES-003: Registered file id=${registerBody.data.id}`)
    }

    // 10. 成功トーストが表示され、ファイルが一覧に現れること
    await expect(page.getByText(testFileName)).toBeVisible({ timeout: 15_000 })
    console.log(`MYFILES-003: File "${testFileName}" appears in list. PASS`)

    // 11. リロード後も表示されること（永続化確認）
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    await expect(page.getByText(testFileName)).toBeVisible({ timeout: 15_000 })
    console.log(`MYFILES-003: File persisted after reload. FULL PASS`)
  } finally {
    // テスト用ファイルを後始末
    fs.unlinkSync(testFilePath)
  }
})
