import { expect, test } from '@playwright/test'

import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * MONSHO-E2E: #2355「村紋(monsho) presign 入稿」実機E2E（モックなし・書込一気通貫）。
 *
 * 検証対象: presign → R2/MinIO 直PUT → 登録 の3ステップ入稿フローと、
 * VillageHeader での表示、**フルリロード後も表示され続けること（署名URL化の核心）**、
 * 他フィールド編集保存後の消失回帰（検分是正 a9994a6ff）、村アイコン/カバーの署名URL表示、異常系。
 *
 * 署名URL化（2026-07-19 マスター御裁可）以前は VillageResponse に村紋が一切載らず
 * 「書けるが読めない」状態だった（旧 buildR2Url + 未宣言 runtimeConfig.public.r2PublicUrl による
 * FAIL を本specの前バージョンで実測）。本バージョンは MediaUrlResolver 経由の署名URL方式に追従し、
 * 特にフルリロード後の永続表示を重点的に検証する。
 *
 * 使い捨て村: 既存村の seed membership 汚染を避けるため、村作成申請
 * (POST /villages/creation-requests) で毎回新規に村を作る。
 *
 * 実装調査で判明した事実（VillageCreationRequestService#createRequest 実測）:
 *   本 API は「申請 → 運営承認」の2段階ではなく、**同一トランザクション内で即時自動承認**する
 *   （申請者自身が reviewer として記録され、村レコード・HEADMAN membership もその場で作成される）。
 *   レスポンスの createdVillageId が即座に非 null で返るため、別途 SYSTEM_ADMIN による
 *   POST /admin/village-creation-requests/{id}/approve は不要（呼ぶと VILLAGE_033 既審査済みで 409 になる）。
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

// E2E 一般ユーザー（id=90209）。DB 実測: e2e-pwui-1782136885@test.mannschaft.local / Passw0rd!2026
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

interface CreationRequestBody {
  data: { id: string, createdVillageId: string | null }
}

test.describe('MONSHO-E2E: 村紋 presign 入稿 実機E2E (#2355)', () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)

  test('MONSHO-E2E-01: 使い捨て村作成→presign入稿→表示→回帰→異常系を一気通貫で踏む', async ({
    page,
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
    let presignR2Key: string | null = null
    page.on('response', (res) => {
      const url = res.url()
      const method = res.request().method()
      if (url.includes('/monsho/upload-url') && method === 'POST') {
        presignStatus = res.status()
        res.json().then((b) => { presignR2Key = b?.data?.r2Key ?? null }).catch(() => {})
      }
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
    // 2. 使い捨ての村を新規作成（村作成申請 = 同一トランザクションで即時自動承認・実測）
    //    作成者(90209)が申請者のまま自動承認されるため HEADMAN になる。
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
    const villageId = createBody.data.createdVillageId ?? ''
    expect(villageId, '申請と同時に自動承認され createdVillageId が返ること').toBeTruthy()

    console.log('=== MONSHO E2E: 使い捨て村作成完了（即時自動承認） ===')
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

    // ---- ダイアログ内プレビュー画像が署名URL方式で表示されること ----
    // #2355以降、村紋の読取は BE が MediaUrlResolver で presign した署名URL(monshoUrl)を
    // 返し、FE はそれをそのまま <img src> に渡すだけ（buildR2Url 等の公開URL組み立ては
    // FEから物理削除済み）。presign→直PUT→登録が全て200で成功した以上、
    // プレビュー <img> は必ず描画されるためハード assert とする。
    const dialogPreviewImg = dialog.locator('img').first()
    await expect(
      dialogPreviewImg,
      '村紋プレビュー<img>が描画されること（署名URL方式・v-if="monshoPreviewUrl"）',
    ).toBeVisible({ timeout: 15_000 })
    const dialogImgSrc = await dialogPreviewImg.getAttribute('src')
    console.log('dialog monsho preview <img src>:', dialogImgSrc)
    expect(dialogImgSrc, '村紋プレビューのsrcが署名付きURLとして返っていること').toBeTruthy()
    expect(dialogImgSrc, '署名付きURLはhttp(s)スキームで始まること').toMatch(/^https?:\/\//)
    // 二重前置チェック: "http" が2回登場するような壊れた URL でないこと
    const httpOccurrences = (dialogImgSrc?.match(/https?:\/\//g) ?? []).length
    expect(httpOccurrences, `村紋画像URLが二重前置されていないこと（実値=${dialogImgSrc}）`).toBeLessThanOrEqual(1)
    // 実際に署名付きURL(presigned GET)であること — R2/MinIO(S3互換)の presign は
    // X-Amz-Algorithm/X-Amz-Signature 等のクエリパラメータを持つ。生r2Keyそのままではないこと。
    expect(dialogImgSrc, '署名パラメータ(X-Amz-...)を含む presigned GET URL であること').toMatch(/X-Amz-/)
    console.log('presign応答のr2Key(参考・icon/cover検証で再利用):', presignR2Key)

    await page.screenshot({
      path: 'test-results/monsho-01-after-upload-dialog.png',
      fullPage: true,
    })

    // =========================================================================
    // 6. VillageHeader に村紋が画像として表示されること（ダイアログを「キャンセル」で閉じて確認）
    //    ※ ここでは保存操作をしない（保存は §7 の回帰確認で1回だけ行い、
    //      toast文言("村情報を更新しました")の使い回しによる待機の取り違えを避ける）。
    // =========================================================================
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(dialog).not.toBeVisible({ timeout: 15_000 })

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

    expect(headerMonshoVisible, 'VillageHeaderに村紋バッジが表示されること（ダイアログを閉じた直後）').toBe(true)
    expect(headerMonshoSrc, 'VillageHeader側の村紋<img src>が空でないこと').toBeTruthy()
    const headerHttpOccurrences = (headerMonshoSrc?.match(/https?:\/\//g) ?? []).length
    expect(headerHttpOccurrences, `VillageHeader側URLも二重前置でないこと（実値=${headerMonshoSrc}）`).toBeLessThanOrEqual(1)

    // =========================================================================
    // 6. 【最重要・回帰確認】ページをフルリロードしても村紋が表示され続けること。
    //    今回の根治の核心: VillageResponse(GET /villages/{id}) に monshoUrl が
    //    署名URLとして載るようになったため、クライアント内state（emitマージ）に依存せず
    //    フルリロード後の新規フェッチでも表示されるはず。
    // =========================================================================
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

    const headerMonshoButtonAfterReload = page.locator('.village-header__monsho-button')
    await expect(
      headerMonshoButtonAfterReload,
      '【回帰確認・核心】フルリロード後もVillageHeaderに村紋バッジが表示されること',
    ).toBeVisible({ timeout: 20_000 })
    const headerMonshoSrcAfterReload = await headerMonshoButtonAfterReload.locator('img').getAttribute('src')
    console.log('VillageHeader monsho <img src>（リロード後）:', headerMonshoSrcAfterReload)
    expect(headerMonshoSrcAfterReload, 'リロード後の村紋URLが空でないこと').toBeTruthy()
    expect(headerMonshoSrcAfterReload, 'リロード後も署名URL(X-Amz-...)であること').toMatch(/X-Amz-/)
    const reloadHttpOccurrences = (headerMonshoSrcAfterReload?.match(/https?:\/\//g) ?? []).length
    expect(reloadHttpOccurrences, `リロード後URLも二重前置でないこと（実値=${headerMonshoSrcAfterReload}）`).toBeLessThanOrEqual(1)

    await page.screenshot({
      path: 'test-results/monsho-02b-after-reload.png',
      fullPage: true,
    })

    // =========================================================================
    // 7. 【回帰確認】他フィールド編集保存後に村紋が消えないこと（a9994a6ff 根治箇所）
    //    ダイアログを再度開き、村名を変更して保存 → monsho が保持されているか確認する。
    //    PATCH 完了は toast 文言の待機ではなく page.waitForResponse で直接同期する
    //    （toast は §6/§7 で同一文言のため待機の取り違えリスクがある）。
    // =========================================================================
    await settingsCard.click()
    const dialog2 = page.getByRole('dialog').filter({ hasText: '村を編集' })
    await expect(dialog2).toBeVisible({ timeout: 10_000 })

    // 直前アップロードのプレビューがダイアログ再オープン時点で残っていること（回帰の前提条件）
    const previewBeforeRename = dialog2.locator('img').first()
    const previewVisibleBeforeRename = await previewBeforeRename.isVisible().catch(() => false)
    console.log('回帰確認: リネーム前のダイアログ内プレビュー可視:', previewVisibleBeforeRename)

    const nameInput = dialog2.locator('#village-edit-name')
    await nameInput.fill(`${villageName}（改）`)

    const patchResponsePromise = page.waitForResponse(
      (res) => /\/api\/v1\/villages\/[^/]+$/.test(res.url()) && res.request().method() === 'PATCH',
      { timeout: 15_000 },
    )
    await dialog2.getByRole('button', { name: '更新する' }).click()
    const patchResponse = await patchResponsePromise
    patchStatus = patchResponse.status()

    console.log('PATCH /villages/{id} status:', patchStatus, '| body:', await patchResponse.text().catch(() => ''))
    expect(patchStatus, '基本情報の PATCH が200で返ること').toBe(200)

    await expect(dialog2, '保存成功後ダイアログが閉じること').not.toBeVisible({ timeout: 15_000 })

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
    // 8. 村アイコン・カバーも同じ MediaUrlResolver 経路で署名URL表示されるか確認。
    //    monsho 用に presign 発行済みの r2Key（実オブジェクトが MinIO に存在）を
    //    そのままアイコン/カバーの R2 キー欄に設定して保存 → iconUrl/coverUrl が
    //    署名付きURLとして返り、VillageHeader に表示されることを確認する。
    // =========================================================================
    expect(presignR2Key, 'アイコン/カバー検証用に monsho presign の r2Key が取得できていること').toBeTruthy()

    const iconInput = dialog3.locator('#village-edit-icon')
    const coverInput = dialog3.locator('#village-edit-cover')
    await iconInput.fill(presignR2Key ?? '')
    await coverInput.fill(presignR2Key ?? '')

    const iconPatchPromise = page.waitForResponse(
      (res) => /\/api\/v1\/villages\/[^/]+$/.test(res.url()) && res.request().method() === 'PATCH',
      { timeout: 15_000 },
    )
    await dialog3.getByRole('button', { name: '更新する' }).click()
    const iconPatchResponse = await iconPatchPromise
    const iconPatchStatus = iconPatchResponse.status()
    console.log('アイコン/カバー設定 PATCH status:', iconPatchStatus)
    expect(iconPatchStatus, 'アイコン/カバーR2Key設定のPATCHが200で返ること').toBe(200)
    const iconPatchBody = await iconPatchResponse.json().catch(() => null)
    const iconUrlFromPatch = iconPatchBody?.data?.iconUrl ?? null
    const coverUrlFromPatch = iconPatchBody?.data?.coverUrl ?? null
    console.log('PATCH応答 iconUrl:', iconUrlFromPatch)
    console.log('PATCH応答 coverUrl:', coverUrlFromPatch)
    expect(iconUrlFromPatch, 'PATCH応答のiconUrlが署名URLであること').toMatch(/X-Amz-/)
    expect(coverUrlFromPatch, 'PATCH応答のcoverUrlが署名URLであること').toMatch(/X-Amz-/)

    await expect(dialog3).not.toBeVisible({ timeout: 15_000 })

    // icon/cover は alt が村名で共通のため DOM 構造で判別する（VillageHeader.vue 実装準拠）。
    // cover: 上部 h-40 の全幅コンテナ内。icon: rounded-full border-4 の円形コンテナ内。
    const headerCoverImg = page.locator('.village-header > div.relative.w-full img').first()
    const headerIconImg = page.locator('.village-header .rounded-full.border-4 img').first()
    const iconVisible = await headerIconImg.isVisible().catch(() => false)
    const coverVisible = await headerCoverImg.isVisible().catch(() => false)
    console.log('VillageHeader icon 表示:', iconVisible, '| cover 表示:', coverVisible)

    await page.screenshot({
      path: 'test-results/monsho-06-icon-cover.png',
      fullPage: true,
    })

    expect(iconVisible, 'VillageHeaderに村アイコンが署名URLで表示されること').toBe(true)
    expect(coverVisible, 'VillageHeaderに村カバー画像が署名URLで表示されること').toBe(true)
    const iconSrc = await headerIconImg.getAttribute('src')
    const coverSrc = await headerCoverImg.getAttribute('src')
    console.log('icon <img src>:', iconSrc)
    console.log('cover <img src>:', coverSrc)
    expect(iconSrc, 'アイコンURLも署名URL(X-Amz-...)であること').toMatch(/X-Amz-/)
    expect(coverSrc, 'カバーURLも署名URL(X-Amz-...)であること').toMatch(/X-Amz-/)

    // =========================================================================
    // 9. 異常系: 5MB超ファイル / 非対応MIME を選び、FE が読めるエラーを出すこと
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
