import { expect, test } from '@playwright/test'

import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * FESTIVAL-BANNER-E2E: 村のお祭りバナー画像の実機E2E（モックなし・実オブジェクト入稿）。
 *
 * 検証対象:
 *   AC-8  実オブジェクトを MinIO に置いたバナーが一覧・詳細で署名URL化され実表示されること
 *   AC-9  バナー未設定のお祭りは bannerUrl=null で代替表示（pi-image）になり壊れないこと
 *   AC-10 非HEADMAN/ELDER の作成・更新が 403 で拒否されること
 *   AC-11 非メンバーが他村のお祭りを読めないこと（別 test に分離・下記の注意書き参照）
 *
 * 【最重要・偽の検証を避けるための設計】
 *   MediaUrlResolver.resolve() は「オブジェクトが実在するか」を検証しない。存在しない
 *   ダミーキーを bannerR2Key に入れても署名URLは生成されてしまう。したがって
 *   「src に X-Amz- が付いているか」だけを見る検証は**通ってしまう偽の検証**である。
 *   本 spec は AC-8 で必ず
 *     (1) presign で得た URL へ実際に PNG バイト列を PUT して MinIO 上にオブジェクトを作り
 *     (2) そのキーを bannerR2Key に設定し
 *     (3) 表示された <img> の naturalWidth > 0（＝ブラウザがデコードできた実画像）
 *     (4) src への GET が 200
 *   まで確認する。
 *
 * 【お祭りバナーに presign EP は無い】
 *   祭は bannerR2Key（生キー）を作成/更新ボディに直接乗せる方式で、FE 入稿UIもテキスト入力。
 *   実オブジェクトを置くために村紋の presign EP
 *   （POST /api/v1/villages/{villageId}/monsho/upload-url）を流用して r2Key を得る。
 *
 * 使い捨て村: seed membership 汚染を避けるため、村作成申請
 *   (POST /api/v1/villages/creation-requests) で毎回新規に村を作る。
 *   本 API は申請と同時に即時自動承認され、申請者がその場で HEADMAN になる
 *   （village-monsho-presign-upload.spec.ts の実測に準拠）。
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

// 村を作る HEADMAN 側ユーザー（id=90209）
const HEADMAN_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const HEADMAN_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'Passw0rd!2026'

// 村に属さない「よそ者」ユーザー（id=23）。認可の否定側検証に使う。
const OUTSIDER_EMAIL = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const OUTSIDER_PASSWORD = process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!'

/** 1x1 透過 PNG（fixture ファイルを増やさないため直接埋め込み） */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

/**
 * BE の LocalDateTime（オフセット無し `YYYY-MM-DDTHH:mm:ss`）へ整形する。
 * BE は `LocalDateTime.now()`（サーバーのローカル時刻）で状態を決めるため、
 * WSL 側が UTC・こちらが JST といった最大 ±9h のズレがあっても必ず
 * 「開始済み かつ 未終了」= ACTIVE になるよう、開始を大きく過去・終了を大きく未来に採る。
 * （日時フィクスチャの TZ 境界事故を避ける定石）
 */
function toLocalDateTimeString(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const STARTS_AT = toLocalDateTimeString(new Date(Date.now() - 12 * 60 * 60 * 1000)) // 12時間前
const ENDS_AT = toLocalDateTimeString(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)) // 7日後

interface CreationRequestBody {
  data: { id: string, createdVillageId: string | null }
}

interface PresignBody {
  data: { uploadUrl: string, r2Key: string, expiresInSeconds: number }
}

interface FestivalBody {
  data: { id: string, title: string, bannerUrl: string | null, status: string }
}

/** 使い捨ての村を作り、村ID を返す（作成者は HEADMAN になる）。 */
async function createDisposableVillage(
  request: import('@playwright/test').APIRequestContext,
  label: string,
): Promise<string> {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`
  const res = await request.post(`${API_BASE}/api/v1/villages/creation-requests`, {
    data: {
      name: `E2E祭バナー検証村${suffix}`,
      slug: `e2e-festival-${suffix}`,
      category: null,
      purpose: `お祭りバナー画像の実機E2E検証用（使い捨て村・${label}）`,
      guidelineAgreedAt: new Date().toISOString(),
      type: 'COMMUNITY',
      guidelineMd: null,
    },
  })
  expect(res.status(), `村作成申請が201で返ること: ${await res.text().catch(() => '')}`).toBe(201)
  const body = (await res.json()) as CreationRequestBody
  const villageId = body.data.createdVillageId ?? ''
  expect(villageId, '申請と同時に自動承認され createdVillageId が返ること').toBeTruthy()
  return villageId
}

test.describe('FESTIVAL-BANNER-E2E: 村のお祭りバナー画像 実機E2E', () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(240_000)

  // =======================================================================
  // AC-8 / AC-9 / AC-10
  // =======================================================================
  test('FESTIVAL-BANNER-01: 実オブジェクト入稿→一覧/詳細で実表示・バナー無しは代替表示・非権限者は403', async ({
    page,
  }) => {
    const consoleErrors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    // ---------------------------------------------------------------------
    // 1. HEADMAN としてログインし、使い捨て村を作る
    // ---------------------------------------------------------------------
    await loginViaApi(page, { email: HEADMAN_EMAIL, password: HEADMAN_PASSWORD }, { apiBaseUrl: API_BASE })
    const villageId = await createDisposableVillage(page.request, 'AC-8/9/10')
    console.log('=== 使い捨て村作成完了 === villageId:', villageId)

    // ---------------------------------------------------------------------
    // 2. 村紋の presign EP を流用して r2Key を取得し、MinIO へ実 PNG を直PUT。
    //    ここで「実在するオブジェクト」を作ることが本 spec の肝（偽検証の回避）。
    // ---------------------------------------------------------------------
    const presignRes = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/monsho/upload-url`,
      { data: { contentType: 'image/png', fileSize: PNG_1X1.length } },
    )
    expect(presignRes.status(), `presign が200で返ること: ${await presignRes.text().catch(() => '')}`).toBe(200)
    const presign = (await presignRes.json()) as PresignBody
    const bannerR2Key = presign.data.r2Key
    expect(bannerR2Key, 'presign が r2Key を返すこと').toBeTruthy()

    const putRes = await page.request.put(presign.data.uploadUrl, {
      headers: { 'Content-Type': 'image/png' },
      data: PNG_1X1,
    })
    console.log('MinIO 直PUT status:', putRes.status(), '| r2Key:', bannerR2Key)
    expect([200, 204], 'MinIO への直PUTが成功すること（＝実オブジェクトが存在する）').toContain(putRes.status())
    expect(presign.data.uploadUrl.startsWith(MINIO_ORIGIN), 'presign 先が MinIO であること').toBe(true)

    // ---------------------------------------------------------------------
    // 3. お祭りを2件作る: バナー有り（AC-8）／バナー無し（AC-9）
    //    どちらも ACTIVE になる期間にする（画面の既定フィルタが ACTIVE のため）
    // ---------------------------------------------------------------------
    const withBannerTitle = `バナー有り祭${Date.now()}`
    const noBannerTitle = `バナー無し祭${Date.now()}`

    const createWithBanner = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/festivals`,
      {
        data: {
          title: withBannerTitle,
          description: 'AC-8: 実オブジェクトのバナーを持つお祭り',
          startsAt: STARTS_AT,
          endsAt: ENDS_AT,
          bannerR2Key,
          themeColorHex: '#FF8800',
        },
      },
    )
    expect(
      createWithBanner.status(),
      `バナー有り祭の作成が201: ${await createWithBanner.text().catch(() => '')}`,
    ).toBe(201)
    const withBanner = (await createWithBanner.json()) as FestivalBody
    console.log('バナー有り祭 status:', withBanner.data.status, '| bannerUrl:', withBanner.data.bannerUrl)

    const createNoBanner = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/festivals`,
      {
        data: {
          title: noBannerTitle,
          description: 'AC-9: バナー未設定のお祭り',
          startsAt: STARTS_AT,
          endsAt: ENDS_AT,
          bannerR2Key: null,
          themeColorHex: null,
        },
      },
    )
    expect(createNoBanner.status(), 'バナー無し祭の作成が201').toBe(201)
    const noBanner = (await createNoBanner.json()) as FestivalBody

    // API 応答レベルの確認（AC-8 / AC-9 の前提）
    expect(withBanner.data.status, '期間指定どおり ACTIVE で作られること（TZズレ耐性）').toBe('ACTIVE')
    expect(withBanner.data.bannerUrl, 'バナー有り祭の bannerUrl が署名URL(X-Amz-)であること').toMatch(/X-Amz-/)
    expect(noBanner.data.bannerUrl, 'AC-9: バナー未設定なら bannerUrl は null であること').toBeNull()

    // ---------------------------------------------------------------------
    // 4. AC-8: 一覧画面でバナーが「実画像として」表示されること
    // ---------------------------------------------------------------------
    await page.goto(`/villages/${villageId}/festivals`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

    const bannerCard = page.locator('.village-festival__card').filter({ hasText: withBannerTitle })
    await expect(bannerCard, 'バナー有り祭のカードが一覧に出ること').toBeVisible({ timeout: 20_000 })

    const bannerImg = bannerCard.locator('img')
    await expect(bannerImg, 'AC-8: 一覧カードに <img> が描画されること').toBeVisible({ timeout: 15_000 })

    const listSrc = await bannerImg.getAttribute('src')
    console.log('一覧カード <img src>:', listSrc)
    expect(listSrc, '一覧の src が署名URL(X-Amz-)であること').toMatch(/X-Amz-/)
    const listHttpOccurrences = (listSrc?.match(/https?:\/\//g) ?? []).length
    expect(listHttpOccurrences, `URLが二重前置されていないこと（実値=${listSrc}）`).toBeLessThanOrEqual(1)

    // 【偽検証回避の核心①】ブラウザが実際にデコードできた画像であること。
    // 存在しないキーの署名URLなら MinIO が 404 を返し naturalWidth は 0 のままになる。
    await expect
      .poll(
        async () => bannerImg.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth),
        {
          message: 'AC-8: 一覧のバナー<img>が実画像としてデコードされること（naturalWidth>0）',
          timeout: 20_000,
        },
      )
      .toBeGreaterThan(0)

    // 【偽検証回避の核心②】その src への GET が実際に 200 を返すこと
    const listImgGet = await page.request.get(listSrc ?? '')
    console.log('一覧バナー src への GET status:', listImgGet.status())
    expect(listImgGet.status(), 'AC-8: 一覧バナーの署名URLへの GET が200であること').toBe(200)

    await page.screenshot({ path: 'test-results/festival-01-list-banner.png', fullPage: true })

    // ---------------------------------------------------------------------
    // 5. AC-9: バナー未設定の祭は <img> が無く、代替表示（pi-image）になること
    // ---------------------------------------------------------------------
    const noBannerCard = page.locator('.village-festival__card').filter({ hasText: noBannerTitle })
    await expect(noBannerCard, 'バナー無し祭のカードが一覧に出ること').toBeVisible({ timeout: 20_000 })
    expect(
      await noBannerCard.locator('img').count(),
      'AC-9: バナー未設定のカードには <img> が描画されないこと',
    ).toBe(0)
    await expect(
      noBannerCard.locator('.pi-image'),
      'AC-9: 代替アイコン（pi-image）が表示されること',
    ).toBeVisible()
    // カード自体は壊れず、タイトルは読めること
    await expect(noBannerCard, 'AC-9: バナー無しでもカードが壊れず内容が読めること').toContainText(noBannerTitle)

    // ---------------------------------------------------------------------
    // 6. AC-8（詳細）: カードを開いた詳細ダイアログでもバナーが実表示されること
    // ---------------------------------------------------------------------
    await bannerCard.click()
    const detailDialog = page.getByRole('dialog').filter({ hasText: withBannerTitle })
    await expect(detailDialog, '詳細ダイアログが開くこと').toBeVisible({ timeout: 15_000 })

    const detailImg = detailDialog.locator('img').first()
    await expect(detailImg, 'AC-8: 詳細ダイアログにバナー<img>が描画されること').toBeVisible({ timeout: 15_000 })
    const detailSrc = await detailImg.getAttribute('src')
    console.log('詳細ダイアログ <img src>:', detailSrc)
    expect(detailSrc, '詳細の src も署名URL(X-Amz-)であること').toMatch(/X-Amz-/)
    await expect
      .poll(
        async () => detailImg.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth),
        {
          message: 'AC-8: 詳細のバナー<img>も実画像としてデコードされること（naturalWidth>0）',
          timeout: 20_000,
        },
      )
      .toBeGreaterThan(0)

    await page.screenshot({ path: 'test-results/festival-02-detail-banner.png', fullPage: true })

    // 生の i18n キーが画面に漏れていないこと
    expect(
      await page.getByText('village.festival.', { exact: false }).count(),
      'i18nキーが生表示されていないこと',
    ).toBe(0)

    // ---------------------------------------------------------------------
    // 7. AC-10: 非HEADMAN/ELDER（村に属さないユーザー）の作成・更新が 403
    //    単一セッション設計のため、同じ page で「よそ者」へログインし直す
    //    （別 context を新規に開かない）。
    // ---------------------------------------------------------------------
    await loginViaApi(page, { email: OUTSIDER_EMAIL, password: OUTSIDER_PASSWORD }, { apiBaseUrl: API_BASE })

    const outsiderCreate = await page.request.post(
      `${API_BASE}/api/v1/villages/${villageId}/festivals`,
      {
        data: {
          title: '乗っ取り祭',
          description: null,
          startsAt: STARTS_AT,
          endsAt: ENDS_AT,
          bannerR2Key: null,
          themeColorHex: null,
        },
      },
    )
    console.log('AC-10 よそ者による作成 status:', outsiderCreate.status())
    expect(outsiderCreate.status(), 'AC-10: 非HEADMAN/ELDER の作成は 403').toBe(403)

    const outsiderUpdate = await page.request.patch(
      `${API_BASE}/api/v1/villages/${villageId}/festivals/${withBanner.data.id}`,
      { data: { title: '書き換え', description: null, startsAt: null, endsAt: null, bannerR2Key: null, themeColorHex: null } },
    )
    console.log('AC-10 よそ者による更新 status:', outsiderUpdate.status())
    expect(outsiderUpdate.status(), 'AC-10: 非HEADMAN/ELDER の更新は 403').toBe(403)

    console.log('=== console errors (先頭10件) ===', JSON.stringify(consoleErrors.slice(0, 10)))
  })

  // =======================================================================
  // AC-11
  // =======================================================================
  /**
   * AC-11: お祭りの読取（一覧・詳細）が村掲示板と同一の閲覧認可に従うこと。
   *
   * 期待仕様: 村の bulletin_visibility が MEMBERS_ONLY なら村メンバーまたは SYSTEM_ADMIN のみ
   * 参照でき、それ以外は 403 となる。村作成申請で作られる村は bulletin_visibility を
   * 明示指定しないため、Entity の @PrePersist により既定の MEMBERS_ONLY になる。
   *
   * 【red の場合について】
   *   本 spec が参照する BE（API_BASE）が本ブランチを反映していない場合、この test は red になる。
   *   その場合は BE を本ブランチ込みで起動し直せば green 化する。
   *   認可の実装自体は契約テスト `VillageFestivalControllerIntegrationTest`
   *   （Testcontainers・実DB・実メンバーシップ）で green を証明済み。
   *   red を隠すための skip / 削除はしない（実機で確認する関門を残すため）。
   */
  test('FESTIVAL-BANNER-02 [AC-11]: 非メンバーは他村のお祭りを読めない（BE反映後にgreen化）', async ({
    page,
  }) => {
    // 1. HEADMAN 側で使い捨て村とお祭りを用意する
    await loginViaApi(page, { email: HEADMAN_EMAIL, password: HEADMAN_PASSWORD }, { apiBaseUrl: API_BASE })
    const villageId = await createDisposableVillage(page.request, 'AC-11')

    const title = `非公開祭${Date.now()}`
    const createRes = await page.request.post(`${API_BASE}/api/v1/villages/${villageId}/festivals`, {
      data: {
        title,
        description: '非メンバーから見えてはいけないお祭り',
        startsAt: STARTS_AT,
        endsAt: ENDS_AT,
        bannerR2Key: null,
        themeColorHex: null,
      },
    })
    expect(createRes.status(), 'お祭りの作成が201').toBe(201)
    const festival = (await createRes.json()) as FestivalBody

    // 2. 村に属さない「よそ者」へログインし直す（単一セッション設計）
    await loginViaApi(page, { email: OUTSIDER_EMAIL, password: OUTSIDER_PASSWORD }, { apiBaseUrl: API_BASE })

    // 3. 一覧・詳細ともに読めないこと
    const listRes = await page.request.get(`${API_BASE}/api/v1/villages/${villageId}/festivals`)
    const listText = await listRes.text().catch(() => '')
    console.log('AC-11 よそ者による一覧取得 status:', listRes.status(), '| body:', listText.slice(0, 300))
    expect(
      listRes.status(),
      'AC-11: MEMBERS_ONLY 村の非メンバーは一覧を読めない（403）',
    ).toBe(403)

    const getRes = await page.request.get(
      `${API_BASE}/api/v1/villages/${villageId}/festivals/${festival.data.id}`,
    )
    const getText = await getRes.text().catch(() => '')
    console.log('AC-11 よそ者による詳細取得 status:', getRes.status(), '| body:', getText.slice(0, 300))
    expect(
      getRes.status(),
      'AC-11: MEMBERS_ONLY 村の非メンバーは詳細を読めない（403）',
    ).toBe(403)

    // 4. バナーの署名URLが漏れていないこと（読取が通ってしまった場合の被害を明示する）
    expect(getText, 'AC-11: 非メンバーへバナーの署名URLが漏れていないこと').not.toMatch(/X-Amz-/)
  })
})
