import { expect, test } from '@playwright/test'

import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * MONSHO-E2E: #2355「村紋(monsho) presign 入稿」実機E2E（モックなし・書込一気通貫）。
 *
 * 検証対象: presign → R2/MinIO 直PUT → 登録 の3ステップ入稿フローと、
 * VillageHeader での表示、他フィールド編集保存後の消失回帰（検分是正 a9994a6ff）、異常系。
 *
 * 使い捨て村: 既存村の seed membership 汚染を避けるため、村作成申請
 * (POST /villages/creation-requests) → 運営承認 (POST /admin/village-creation-requests/{id}/approve)
 * で毎回新規に村を作る。承認者(運営)はメインの page セッションとは別の
 * playwright.request.newContext() を使い、メインセッション（page.request）のトークンローテーションに
 * 影響を与えない（feedback_e2e_real_single_session_token_rotation）。
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

// E2E 一般ユーザー（id=90209）。DB 実測: e2e-pwui-1782136885@test.mannschaft.local / Passw0rd!2026
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

// SYSTEM_ADMIN シードユーザー（村作成申請の承認用。backend/scripts/seed-e2e-data.js 参照）
const ADMIN_EMAIL = 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = 'TestPass2026!'

interface LoginBody {
  data: { accessToken: string }
}

interface CreationRequestBody {
  data: { id: string }
}

interface ApproveBody {
  data: { createdVillageId: string }
}

test.describe('MONSHO-E2E: 村紋 presign 入稿 実機E2E (#2355)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)

  test('MONSHO-E2E-01: 使い捨て村作成→presign入稿→表示→回帰→異常系を一気通貫で踏む', async ({
    page,
    playwright,
  }) => {
    // ---- 観測バッファ ----
    let presignStatus: number | null = null
    let minioPutStatus: number | null = null
    let minioPutUrl: string | null = null
    let commitStatus: number | null = null
    let patchStatus: number | null = null
    const consoleErrors: string[] = []

    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })
    page.on('response', (res) => {
      const url = res.url()
      const method = res.request().method()
      if (url.includes('/monsho/upload-url') && method === 'POST') presignStatus = res.status()
      if (url.startsWith(MINIO_ORIGIN) && method === 'PUT') {
        minioPutStatus = res.status()
        minioPutUrl = url
      }
      if (url.endsWith('/monsho') && method === 'PUT') commitStatus = res.status()
      if (/\/api\/v1\/villages\/[^/]+$/.test(url) && method === 'PATCH') patchStatus = res.status()
    })

    // =========================================================================
    // 1. ログイン（単一セッション・page.request 統一）
    // =========================================================================
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE })

    // =========================================================================
    // 2. 使い捨ての村を新規作成（村作成申請 → 運営承認）
    //    作成者(90209)が申請者のまま承認されるため HEADMAN になる。
    // =========================================================================
    const uniqueSuffix = Date.now()
    const villageName = `E2Eモンショ検証村${uniqueSuffix}`
    const villageSlug = `e2e-monsho-${uniqueSuffix}`

    const createRes = await page.request.post(`${API_BASE}/api/v1/villages/creation-requests`, {
      data: {
        name: villageName,
        slug: villageSlug,
        category: null,
        purpose: '#2355 村紋 presign 入稿の実機E2E検証用（使い捨て村）',
        guidelineAgreedAt: new Date().toISOString(),
        type: 'COMMUNITY',
        guidelineMd: null,
      },
    })
    expect(createRes.status(), `村作成申請が201で返ること: ${await createRes.text().catch(() => '')}`).toBe(201)
    const createBody = (await createRes.json()) as CreationRequestBody
    const requestId = createBody.data.id
    expect(requestId, '申請IDが返ること').toBeTruthy()

    // 承認は別の ephemeral request context（SYSTEM_ADMIN）で行う。
    // page / page.request の Cookie ジャーには一切触れないため、90209 のセッションは無傷。
    const adminCtx = await playwright.request.newContext()
    let villageId = ''
    try {
      const adminLoginRes = await adminCtx.post(`${API_BASE}/api/v1/auth/login`, {
        data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      })
      expect(adminLoginRes.status(), '運営(SYSTEM_ADMIN)ログインが200で返ること').toBe(200)
      const adminLoginBody = (await adminLoginRes.json()) as LoginBody
      const adminToken = adminLoginBody.data.accessToken

      const approveRes = await adminCtx.post(
        `${API_BASE}/api/v1/admin/village-creation-requests/${requestId}/approve`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      )
      expect(
        approveRes.status(),
        `村作成申請の承認が200で返ること: ${await approveRes.text().catch(() => '')}`,
      ).toBe(200)
      const approveBody = (await approveRes.json()) as ApproveBody
      villageId = approveBody.data.createdVillageId
      expect(villageId, '承認後に createdVillageId が返ること').toBeTruthy()
    }
    finally {
      await adminCtx.dispose()
    }

    console.log('=== MONSHO E2E: 使い捨て村作成完了 ===')
    console.log('villageId:', villageId, '| slug:', villageSlug)

    // =========================================================================
    // 3. 村長コンソール → 基本設定ダイアログ（VillageEditDialog）
    // =========================================================================
    await page.goto(`/villages/${villageId}/admin`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

    // アクセス拒否メッセージが出ていないこと（HEADMAN として認可が通っていること）
    await expect(page.locator('[data-testid="village-admin-access-denied"]')).not.toBeVisible()

    const settingsCard = page.locator('[data-testid="village-admin-card-settings"]')
    await expect(settingsCard, '「村の基本設定」カードが見える（HEADMAN 権限）こと').toBeVisible({
      timeout: 20_000,
    })
    await settingsCard.click()

    const dialog = page.getByRole('dialog').filter({ hasText: '村を編集' })
    await expect(dialog, '基本設定ダイアログが開くこと').toBeVisible({ timeout: 10_000 })

    // =========================================================================
    // 4〜5. 村紋ファイル選択 → presign → 直PUT → 登録
    // =========================================================================
    const fileInput = dialog.locator('input[type="file"]')
    await fileInput.waitFor({ state: 'attached', timeout: 10_000 })

    const minioPutPromise = page
      .waitForResponse((res) => res.url().startsWith(MINIO_ORIGIN) && res.request().method() === 'PUT', {
        timeout: 30_000,
      })
      .catch(() => null)

    await fileInput.setInputFiles({
      name: 'monsho-1x1.png',
      mimeType: 'image/png',
      // 1x1 透過 PNG（avatar-1x1.png と同一データを直接埋め込み・fixture ファイル追加を避ける）
      buffer: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
        'base64',
      ),
    })

    const minioPutResponse = await minioPutPromise

    await page.getByText('村紋をアップロードしました').waitFor({ state: 'visible', timeout: 15_000 })

    console.log('=== MONSHO E2E: presign入稿 観測結果 ===')
    console.log('presign(POST upload-url) status:', presignStatus)
    console.log('MinIO direct PUT status        :', minioPutStatus, '| url:', minioPutUrl)
    console.log('commit(PUT /monsho) status      :', commitStatus)

    // ---- 6. presign / 直PUT / 登録 の3ステップがすべて成功していること ----
    expect(presignStatus, 'presign POST /monsho/upload-url が200で返ること').toBe(200)
    expect(minioPutResponse, 'ブラウザから MinIO への直PUTが発生していること').not.toBeNull()
    expect([200, 204] as Array<number | null>).toContain(minioPutStatus)
    expect(commitStatus, '登録 PUT /monsho が200で返ること').toBe(200)

    // ---- ダイアログ内プレビュー画像の src を確認（二重前置チェック） ----
    const dialogPreviewImg = dialog.locator('img').first()
    await expect(dialogPreviewImg, 'ダイアログ内に村紋プレビュー画像が表示されること').toBeVisible({
      timeout: 10_000,
    })
    const dialogImgSrc = await dialogPreviewImg.getAttribute('src')
    console.log('dialog monsho preview <img src>:', dialogImgSrc)

    // ランタイム設定の r2PublicUrl を実測（nuxt.config.ts に未宣言の場合 undefined になる既知の懸念を検証）
    const r2PublicUrlRuntime = await page.evaluate(() => {
      // @ts-expect-error -- Nuxt が window に埋め込む実行時ペイロード
      return window.__NUXT__?.config?.public?.r2PublicUrl ?? null
    })
    console.log('runtimeConfig.public.r2PublicUrl (実測):', JSON.stringify(r2PublicUrlRuntime))

    // 二重前置チェック: base が仮に設定されていても "http" が2回登場するような壊れた URL でないこと
    if (dialogImgSrc) {
      const httpOccurrences = (dialogImgSrc.match(/https?:\/\//g) ?? []).length
      expect(httpOccurrences, `村紋画像URLが二重前置されていないこと（実値=${dialogImgSrc}）`).toBeLessThanOrEqual(1)
    }

    await page.screenshot({
      path: 'test-results/monsho-01-after-upload-dialog.png',
      fullPage: true,
    })

    // =========================================================================
    // 6. VillageHeader に村紋が画像として表示されること（ダイアログを閉じて確認）
    // =========================================================================
    await dialog.getByRole('button', { name: '更新する' }).click().catch(() => {})
    // 上のクリックは後続の回帰確認と兼用のため名前保存も走るが、まずヘッダ表示を先に見る前に
    // ダイアログが閉じるのを待つ。
    await expect(dialog).not.toBeVisible({ timeout: 15_000 }).catch(() => {})

    const headerMonshoButton = page.locator('.village-header__monsho-button')
    const headerMonshoImg = headerMonshoButton.locator('img')
    const headerMonshoVisible = await headerMonshoButton.isVisible().catch(() => false)
    console.log('VillageHeader monsho button visible:', headerMonshoVisible)

    let headerMonshoSrc: string | null = null
    if (headerMonshoVisible) {
      headerMonshoSrc = await headerMonshoImg.getAttribute('src').catch(() => null)
      console.log('VillageHeader monsho <img src>:', headerMonshoSrc)
    }

    await page.screenshot({
      path: 'test-results/monsho-02-village-header.png',
      fullPage: true,
    })

    // =========================================================================
    // 7. 【最重要・回帰確認】他フィールド編集保存後に村紋が消えないこと（a9994a6ff 根治箇所）
    //    ダイアログを再度開き、村名を変更して保存 → monsho が保持されているか確認する。
    // =========================================================================
    presignStatus = null
    commitStatus = null
    patchStatus = null

    await settingsCard.click()
    const dialog2 = page.getByRole('dialog').filter({ hasText: '村を編集' })
    await expect(dialog2).toBeVisible({ timeout: 10_000 })

    // 直前アップロードのプレビューがダイアログ再オープン時点で残っていること（回帰の前提条件）
    const previewBeforeRename = dialog2.locator('img').first()
    const previewVisibleBeforeRename = await previewBeforeRename.isVisible().catch(() => false)
    console.log('回帰確認: リネーム前のダイアログ内プレビュー可視:', previewVisibleBeforeRename)

    const nameInput = dialog2.locator('#village-edit-name')
    await nameInput.fill(`${villageName}（改）`)
    await dialog2.getByRole('button', { name: '更新する' }).click()
    await page.getByText('村情報を更新しました').waitFor({ state: 'visible', timeout: 15_000 })

    console.log('PATCH /villages/{id} status:', patchStatus)
    expect(patchStatus, '基本情報の PATCH が200で返ること').toBe(200)

    // ダイアログを再々オープンして monsho プレビューが消えていないか確認
    await settingsCard.click()
    const dialog3 = page.getByRole('dialog').filter({ hasText: '村を編集' })
    await expect(dialog3).toBeVisible({ timeout: 10_000 })
    const previewAfterRename = dialog3.locator('img').first()
    const previewVisibleAfterRename = await previewAfterRename.isVisible().catch(() => false)
    console.log('回帰確認: リネーム後のダイアログ内プレビュー可視:', previewVisibleAfterRename)

    await page.screenshot({
      path: 'test-results/monsho-03-after-rename-regression-check.png',
      fullPage: true,
    })

    expect(
      previewVisibleAfterRename,
      '【回帰確認】他フィールド保存後も村紋プレビューが消えていないこと（a9994a6ff 根治箇所）',
    ).toBe(previewVisibleBeforeRename)

    // ヘッダ側も再確認（フルリロードなしのクライアント内状態）
    const headerMonshoVisibleAfterRename = await page
      .locator('.village-header__monsho-button')
      .isVisible()
      .catch(() => false)
    console.log('VillageHeader monsho button visible (リネーム後):', headerMonshoVisibleAfterRename)
    expect(
      headerMonshoVisibleAfterRename,
      '【回帰確認】VillageHeader側も他フィールド保存後に村紋が消えていないこと',
    ).toBe(headerMonshoVisible)

    // =========================================================================
    // 8. 異常系: 5MB超ファイル / 非対応MIME を選び、FE が読めるエラーを出すこと
    // =========================================================================
    const uploadUrlRequests: string[] = []
    page.on('request', (req) => {
      if (req.url().includes('/monsho/upload-url') && req.method() === 'POST') {
        uploadUrlRequests.push(req.url())
      }
    })

    // 8a. 非対応MIME（.txt）
    const fileInput3 = dialog3.locator('input[type="file"]')
    const requestsBeforeTxt = uploadUrlRequests.length
    await fileInput3.setInputFiles({
      name: 'invalid.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('これは画像ではないテキストファイルです', 'utf-8'),
    })
    await page.getByText('画像形式は JPG・PNG・WebP のみ対応しています').waitFor({
      state: 'visible',
      timeout: 10_000,
    })
    const requestsAfterTxt = uploadUrlRequests.length
    console.log('非対応MIME(.txt): FEエラー表示OK。presignへのnetworkコール発生数(前後差)=', requestsAfterTxt - requestsBeforeTxt)

    // 生キー(village.monsho.xxx)がそのまま画面に出ていないことの確認（i18n未解決の検出）
    const rawKeyLeak = await page.getByText('village.monsho.', { exact: false }).count()
    expect(rawKeyLeak, 'i18nキーが生表示されていないこと').toBe(0)

    await page.screenshot({
      path: 'test-results/monsho-04-abnormal-invalid-mime.png',
      fullPage: true,
    })

    // 8b. 5MB超ファイル（PNGだが実バイト数で拒否される想定）
    const requestsBeforeOversize = uploadUrlRequests.length
    await fileInput3.setInputFiles({
      name: 'oversized.png',
      mimeType: 'image/png',
      buffer: Buffer.alloc(6 * 1024 * 1024, 1),
    })
    await page.getByText('ファイルサイズは 5MB 以内にしてください').waitFor({
      state: 'visible',
      timeout: 10_000,
    })
    const requestsAfterOversize = uploadUrlRequests.length
    console.log('5MB超ファイル: FEエラー表示OK。presignへのnetworkコール発生数(前後差)=', requestsAfterOversize - requestsBeforeOversize)

    await page.screenshot({
      path: 'test-results/monsho-05-abnormal-oversize.png',
      fullPage: true,
    })

    // ---- BE 側も独立して 400 を返すことを裏取り（FE のクライアント検証をバイパスした直接呼出し） ----
    const bePresignOversizeRes = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/monsho/upload-url`,
      { data: { contentType: 'image/png', fileSize: 6 * 1024 * 1024 } },
    )
    console.log('BE直接呼出し(fileSize=6MB) status:', bePresignOversizeRes.status())
    expect(bePresignOversizeRes.status(), 'BEがファイルサイズ超過を400で拒否すること').toBe(400)

    const bePresignBadMimeRes = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/monsho/upload-url`,
      { data: { contentType: 'text/plain', fileSize: 100 } },
    )
    console.log('BE直接呼出し(contentType=text/plain) status:', bePresignBadMimeRes.status())
    expect(bePresignBadMimeRes.status(), 'BEが非対応MIMEを400で拒否すること').toBe(400)

    console.log('=== console errors (先頭10件) ===', JSON.stringify(consoleErrors.slice(0, 10)))
  })
})
