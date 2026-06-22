import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import { loginViaApi } from '../fixtures/auth'

/**
 * AVATAR-PRESIGN-01: presigned アバターアップロードの実機ブラウザ E2E。
 *
 * 検証対象（PR #1778 で根治した「ブラウザ直 PUT」）:
 *   1. FE → BE で presigned PUT URL を取得（POST /api/v1/users/me/profile-media/icon/upload-url）
 *   2. ブラウザが http://localhost:9000/<bucket>/<key> へ直接 fetch(PUT) する
 *   3. BE へ commit（PUT /api/v1/users/me/profile-media/icon）
 *
 * 観測する事実:
 *   - ブラウザの localhost:9000 への PUT が 200/204 で返る（403/AccessDenied でない）
 *   - CSP で弾かれない（"Refused to connect" / blocked-uri がコンソールに出ない）
 *   - 成功後 UI にアバター画像が表示される
 *
 * 単一セッション設計（feedback_e2e_real_single_session_token_rotation）:
 *   beforeEach で page context へ loginViaApi（page.request 統一）。別 context で login しない。
 */

const __dirname = dirname(fileURLToPath(import.meta.url))
const FIXTURE_PNG = resolve(__dirname, '../fixtures/avatar-1x1.png')

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// MinIO（ブラウザ直 PUT 先）のオリジン。
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

test.describe('AVATAR-PRESIGN: presigned アバターアップロード 実機E2E', () => {
  // storageState に依存せず、各テストで fresh login する。
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(120_000)

  test('AVATAR-PRESIGN-01: ブラウザ直 PUT が end-to-end で通る（CSPブロック/403でない）', async ({
    page,
  }) => {
    // ---- 観測バッファ ----
    const cspViolations: string[] = []
    const consoleErrors: string[] = []
    const requestFailures: string[] = []
    /** localhost:9000 への PUT を観測した結果 */
    let minioPutStatus: number | null = null
    let minioPutUrl: string | null = null
    /** presign / commit の BE 応答 */
    let uploadUrlStatus: number | null = null
    let commitStatus: number | null = null

    // コンソール（CSP違反は securitypolicyviolation か console で "Refused to connect" として出る）
    page.on('console', (msg) => {
      const text = msg.text()
      if (msg.type() === 'error') consoleErrors.push(text)
      if (/refused to connect|content security policy|blocked-uri|violat/i.test(text)) {
        cspViolations.push(text)
      }
    })

    // requestfailed: CSP ブロックや CORS 失敗はここに現れる（失敗理由付き）
    page.on('requestfailed', (req) => {
      const url = req.url()
      if (url.includes('localhost:9000') || url.includes('/profile-media/')) {
        requestFailures.push(`${req.method()} ${url} -> ${req.failure()?.errorText ?? 'unknown'}`)
      }
    })

    // response: presign / 直PUT / commit のステータスを捕捉
    page.on('response', (res) => {
      const url = res.url()
      const method = res.request().method()
      if (url.startsWith(MINIO_ORIGIN) && method === 'PUT') {
        minioPutStatus = res.status()
        minioPutUrl = url
      }
      if (url.includes('/profile-media/icon/upload-url') && method === 'POST') {
        uploadUrlStatus = res.status()
      }
      if (url.endsWith('/profile-media/icon') && method === 'PUT') {
        commitStatus = res.status()
      }
    })

    // CSP violation イベント（ブラウザネイティブ）も明示的に拾う
    await page.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (e) => {
        // @ts-expect-error -- テスト用にグローバルへ集約
        ;(window.__cspViolations ??= []).push(`${e.violatedDirective}: ${e.blockedURI}`)
      })
    })

    // ---- 1. fresh login（単一セッション・page.request 統一） ----
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD })

    // ---- 2. アバターアップロード画面へ ----
    await page.goto('/settings/profile', { waitUntil: 'domcontentloaded' })
    // ハイドレーション完了（file input の @change ハンドラがバインドされるまで）
    await page.locator('input[type="file"]').waitFor({ state: 'attached', timeout: 30_000 })

    const pageOrigin = await page.evaluate(() => window.location.origin)
    console.log('page origin (browser):', pageOrigin)

    // ---- 3. ファイルをセット → アップロード実行 ----
    // 直 PUT のレスポンス到来を待つための Promise（タイムアウトで握りつぶさず明示判定）
    const minioPutPromise = page
      .waitForResponse(
        (res) =>
          res.url().startsWith(MINIO_ORIGIN) && res.request().method() === 'PUT',
        { timeout: 30_000 },
      )
      .catch(() => null)

    await page.setInputFiles('input[type="file"]', FIXTURE_PNG)

    const minioPutResponse = await minioPutPromise

    // ---- 4. 成功トースト or アバター表示を待つ ----
    // 成功時は notification.success('アバターを更新しました') が出て avatarUrl が更新される。
    await page
      .getByText('アバターを更新しました')
      .waitFor({ state: 'visible', timeout: 15_000 })
      .catch(() => {})

    // ネイティブ CSP violation を回収
    const nativeCsp = (await page.evaluate(
      // @ts-expect-error -- addInitScript で集約したグローバル
      () => (window.__cspViolations as string[] | undefined) ?? [],
    )) as string[]

    // ---- 観測結果ログ（エビデンス） ----
    console.log('=== AVATAR PRESIGN E2E 観測結果 ===')
    console.log('upload-url(POST) status:', uploadUrlStatus)
    console.log('MinIO direct PUT status:', minioPutStatus, '| url:', minioPutUrl)
    console.log('commit(PUT) status     :', commitStatus)
    console.log('CSP violations(native) :', JSON.stringify(nativeCsp))
    console.log('CSP-ish console        :', JSON.stringify(cspViolations))
    console.log('requestfailed          :', JSON.stringify(requestFailures))
    console.log('console errors         :', JSON.stringify(consoleErrors.slice(0, 10)))

    // スクショ（エビデンス）
    await page.screenshot({
      path: 'test-results/avatar-presign-after-upload.png',
      fullPage: true,
    })

    // ---- アサーション ----
    // (a) presign 発行が成功している
    expect(uploadUrlStatus, 'presign upload-url POST が成功すること').toBe(200)

    // (b) CSP/CORS でブロックされていない。
    //   - 真の CORS ブロックは failure.errorText が CORS 由来（"cors"/"access control"/"ERR_FAILED"）になる。
    //   - 直 PUT は 200 を受信済みでもアプリが fetch のレスポンスボディを読まないと Chromium が
    //     後追いで requestfailed(net::ERR_ABORTED) を発火する（200 受信後の良性アーティファクト）。
    //     これは CORS ブロックではないため除外する（実値の minioPutStatus=200 が成功の一次証拠）。
    const minioRealCorsFailures = requestFailures.filter(
      (f) =>
        f.includes('localhost:9000') &&
        f.includes('PUT') &&
        /cors|access control|err_failed/i.test(f),
    )
    expect(
      minioRealCorsFailures,
      `localhost:9000 への直PUTがCORSでブロックされていないこと: ${JSON.stringify(minioRealCorsFailures)}`,
    ).toEqual([])
    // 直 PUT（connect-src）に対する CSP 違反が無いこと。
    const minioConnectCsp = nativeCsp.filter(
      (v) => v.includes('9000') && /connect-src/i.test(v),
    )
    expect(
      minioConnectCsp,
      `MinIO 直PUT(connect-src)に対する CSP 違反が無いこと: ${JSON.stringify(nativeCsp)}`,
    ).toEqual([])
    // アップロード後の画像表示（img-src）に対する CSP 違反が無いこと。
    //   #1778 は connect-src のみ修正し img-src に MinIO origin を入れ忘れていた退行を捕捉する。
    const minioImgCsp = nativeCsp.filter((v) => v.includes('9000') && /img-src/i.test(v))
    expect(
      minioImgCsp,
      `MinIO 画像表示(img-src)に対する CSP 違反が無いこと: ${JSON.stringify(nativeCsp)}`,
    ).toEqual([])

    // (c) ブラウザの直 PUT が 200/204 で着地（403/AccessDenied でない）
    expect(minioPutResponse, 'ブラウザから localhost:9000 への PUT が発生していること').not.toBeNull()
    expect(
      minioPutStatus,
      `ブラウザ直PUTが 200/204 で返ること（実値=${minioPutStatus}）`,
    ).not.toBeNull()
    expect([200, 204]).toContain(minioPutStatus as number)

    // (d) commit が成功
    expect(commitStatus, 'commit(PUT /profile-media/icon) が 200 で返ること').toBe(200)

    // (e) UI にアバター画像が表示される
    const avatarImg = page.locator('img[alt="アバター"]')
    await expect(avatarImg, 'アバター画像が表示されること').toBeVisible({ timeout: 10_000 })
    const src = await avatarImg.getAttribute('src')
    console.log('avatar img src:', src)
    expect(src, 'アバター画像 src が空でないこと').toBeTruthy()

    // (f) 裏取り: presign された GET（または公開URL）が 200 を返す＝MinIOにオブジェクトが実在
    //     commit の応答 url を GET（page.request 経由＝同一セッション）
    if (src) {
      // src は BE 経由の表示URL or 署名URL の可能性。素で GET して到達性を確認する。
      const getRes = await page.request.get(src).catch(() => null)
      console.log('avatar src GET status:', getRes ? getRes.status() : 'fetch-failed')
    }
  })
})
