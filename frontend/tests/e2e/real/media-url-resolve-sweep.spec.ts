import { expect, test, type Page, type APIRequestContext, type Locator } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readFileSync } from 'node:fs'

/**
 * MEDIA-URL-RESOLVE-SWEEP: 画像URL根治キャンペーン（共通部品 MediaUrlResolver）の網羅実機E2E。
 *
 * 根治点（このキャンペーンが直した本丸）:
 *   「GET 応答」の icon / avatar / banner が、生 R2 キー（例 "team/123/icon/xxx.png"）ではなく
 *   署名付き表示URL（絶対URL http://localhost:9000/...?X-Amz-...）で返ること。
 *   commit（PUT）の戻り値は元から ProfileMediaService が署名URLを返していたため、
 *   commit だけ確認しても不十分。アップロード後に「ページを再取得した時の GET 経路」を踏むのが核心。
 *   （TeamService#getTeam / OrganizationService / UserService#me / MemberQueryDispatcher /
 *     *FavoriteResolver が MediaUrlResolver#resolve で署名URL化する。）
 *
 * 各経路で次の 2 点を踏む:
 *   (a) API 応答のフィールド（metadata.iconUrl / avatarUrl / favorites[].iconUrl）が絶対署名URL
 *   (b) ブラウザで該当ページを新規ロードし、ProfileHeader の <img> が実際に表示され、
 *       その src が 200 で取得できる（404でない＝画像が本当に出る）
 *
 * 認証設計（feedback_e2e_real_single_session_token_rotation 対策）:
 *   - API 呼び出しは全て fresh ログインで得た access_token を Authorization: Bearer で付与する
 *     （FE 主導の refresh ローテーションで cookie が無効化されても 401 にならない）。
 *   - ブラウザ表示は addInitScript で localStorage['currentUser'] を初期注入し、
 *     '/'（ダッシュボード）を経由せず対象ページへ単一遷移する（refresh 二重発火 race を回避）。
 *
 * 起動前提（README / メモリ project_local_minio_image_storage）:
 *   - MinIO: docker compose --profile storage up -d（:9000・bucket mannschaft-storage）
 *   - 検証 BE: :8081（mannschaft.storage.endpoint=http://localhost:9000・allowed-origins に FE origin）
 *   - 検証 FE: :3001（NUXT_PUBLIC_API_BASE=http://localhost:8081・MinIO CORS は 3001 を許可）
 *   実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *         MINIO_ORIGIN=http://localhost:9000 npx playwright test \
 *         tests/e2e/real/media-url-resolve-sweep.spec.ts --project=chromium-real --workers=1
 */

const __dirname = dirname(fileURLToPath(import.meta.url))
const FIXTURE_PNG = resolve(__dirname, '../fixtures/avatar-1x1.png')
const PNG_BYTES = readFileSync(FIXTURE_PNG)

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

/** MinIO の署名付き GET URL であることの判定（絶対URL＋AWS SigV4 クエリ） */
function isSignedMinioUrl(url: string | null | undefined): boolean {
  if (!url) return false
  return url.startsWith(MINIO_ORIGIN) && /X-Amz-(Signature|Credential|Algorithm)=/.test(url)
}

type Me = {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

/**
 * fresh ログイン。access_token を返し、cookie を browser context へ載せ、
 * localStorage['currentUser'] を addInitScript で初期注入する（'/' 経由なしで認証状態を作る）。
 */
async function login(page: Page): Promise<{ token: string; me: Me }> {
  const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
    data: { email: USER_EMAIL, password: USER_PASSWORD },
  })
  if (!loginRes.ok()) {
    throw new Error(`ログイン失敗: ${loginRes.status()} ${await loginRes.text()}`)
  }
  const token = (await loginRes.json()).data.accessToken as string
  const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
  const me = (await meRes.json()).data as Me
  // 対象ページへ単一遷移しても認証済みと判定されるよう localStorage を初期注入
  await page.addInitScript((u) => {
    localStorage.setItem('currentUser', JSON.stringify(u))
  }, {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  })
  return { token, me }
}

/** Authorization: Bearer 付きの page.request ラッパ群 */
function auth(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/** presign → MinIO 直PUT → commit。commit 応答の表示URLを返す。 */
async function uploadIconViaApi(
  req: APIRequestContext,
  token: string,
  basePath: string, // 例: /api/v1/teams/123 または /api/v1/users/me
): Promise<{ commitUrl: string; r2Key: string }> {
  const presignRes = await req.post(`${API_BASE}${basePath}/profile-media/icon/upload-url`, {
    data: { contentType: 'image/png', fileSize: PNG_BYTES.length },
    ...auth(token),
  })
  expect(presignRes.ok(), `presign 成功 (${basePath}): ${presignRes.status()} ${presignRes.ok() ? '' : await presignRes.text()}`).toBeTruthy()
  const presign = (await presignRes.json()).data as { r2Key: string; uploadUrl: string }
  expect(presign.uploadUrl && presign.r2Key, 'presign uploadUrl / r2Key が返ること').toBeTruthy()

  // MinIO 直 PUT（署名は content-type;host を含むため Content-Type 必須）
  const putRes = await req.put(presign.uploadUrl, {
    data: PNG_BYTES,
    headers: { 'Content-Type': 'image/png' },
  })
  expect([200, 204], `MinIO 直PUT が 200/204 (実値=${putRes.status()})`).toContain(putRes.status())

  const commitRes = await req.put(`${API_BASE}${basePath}/profile-media/icon`, {
    data: { r2Key: presign.r2Key },
    ...auth(token),
  })
  expect(commitRes.ok(), `commit 成功 (${basePath}): ${commitRes.status()} ${commitRes.ok() ? '' : await commitRes.text()}`).toBeTruthy()
  const commit = (await commitRes.json()).data as { url: string; mediaRole: string }
  return { commitUrl: commit.url, r2Key: presign.r2Key }
}

/** ブラウザに表示された <img> の src を取得し、実際にデコード（naturalWidth>0）＋200 GET を確認する。 */
async function assertImgDisplaysAndLoads(page: Page, imgLocator: Locator, label: string): Promise<string> {
  // ProfileHeader の <img> 出現を待つ（dev サーバの初回コンパイル/ハイドレーションを考慮し長め）
  await expect(imgLocator, `${label}: <img> が表示されること`).toBeVisible({ timeout: 45_000 })
  const src = await imgLocator.getAttribute('src')
  console.log(`[${label}] img src:`, src)
  expect(src, `${label}: img src が空でないこと`).toBeTruthy()
  expect(isSignedMinioUrl(src), `${label}: img src が署名URLであること（実値=${src}）`).toBeTruthy()
  // 画像バイトのロード完了を待つ（naturalWidth は非同期に >0 になる。toBeVisible はレイアウト確定で先に解決する）
  await expect
    .poll(
      async () => imgLocator.evaluate((el) => (el as HTMLImageElement).naturalWidth),
      { message: `${label}: 画像が実デコードされること（naturalWidth>0）`, timeout: 20_000 },
    )
    .toBeGreaterThan(0)
  const naturalWidth = await imgLocator.evaluate((el) => (el as HTMLImageElement).naturalWidth)
  console.log(`[${label}] img naturalWidth:`, naturalWidth)
  // src を直接 GET して到達性を裏取り（MinIO にオブジェクト実在＝署名有効）
  const getRes = await page.request.get(src as string)
  console.log(`[${label}] img src GET status:`, getRes.status())
  expect(getRes.status(), `${label}: img src が 200 で取得できること（404でない）`).toBe(200)
  return src as string
}

test.describe('MEDIA-URL-RESOLVE-SWEEP: 画像URL根治の網羅実機E2E', () => {
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(200_000)

  test('SWEEP-01 チームアイコン: GET /teams/{slug} の metadata.iconUrl が署名URL＋詳細ページで画像表示', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)

    // 1. 使い捨てチーム作成（作成者＝ADMIN）
    const createRes = await page.request.post(`${API_BASE}/api/v1/teams`, {
      data: { name: `E2E Media Team ${ts}` },
      ...auth(token),
    })
    expect(createRes.ok(), `チーム作成: ${createRes.status()} ${createRes.ok() ? '' : await createRes.text()}`).toBeTruthy()
    const team = (await createRes.json()).data as { id: string; slug: string }
    const slug = team.slug ?? team.id
    console.log('[team] created slug:', slug)

    // 2. 数値IDを /me/teams から解決（profile-media は数値IDを取る）
    const myTeams = (await (await page.request.get(`${API_BASE}/api/v1/me/teams?size=100`, auth(token))).json()).data as Array<{ id: number; slug: string }>
    const numericId = myTeams.find((t) => t.slug === slug)?.id
    expect(numericId, `作成チームの数値IDが /me/teams で解決できること（slug=${slug}）`).toBeTruthy()

    // 3. アイコンアップロード（presign→PUT→commit）
    const up = await uploadIconViaApi(page.request, token, `/api/v1/teams/${numericId}`)
    console.log('[team] commit url:', up.commitUrl)
    expect(isSignedMinioUrl(up.commitUrl), `commit url が署名URL: ${up.commitUrl}`).toBeTruthy()

    // 4. ★根治点: GET /teams/{slug} の metadata.iconUrl が署名付き絶対URL
    const detail = (await (await page.request.get(`${API_BASE}/api/v1/teams/${slug}`, auth(token))).json()).data as { metadata?: { iconUrl?: string } }
    const apiIconUrl = detail.metadata?.iconUrl
    console.log('[team] GET metadata.iconUrl:', apiIconUrl)
    expect(isSignedMinioUrl(apiIconUrl), `★ GET /teams/{slug} の metadata.iconUrl が生キーでなく署名URLであること（実値=${apiIconUrl}）`).toBeTruthy()

    // 5. ブラウザでチーム詳細ページを新規ロード → ProfileHeader のアイコン画像表示
    await page.goto(`/teams/${slug}`, { waitUntil: 'domcontentloaded' })
    const iconImg = page.locator(`img[src^="${MINIO_ORIGIN}"]`).first()
    await assertImgDisplaysAndLoads(page, iconImg, 'team-icon')
    await page.screenshot({ path: `test-results/media-sweep-team-${ts}.png`, fullPage: true })
  })

  test('SWEEP-02 組織アイコン: GET /organizations/{slug} の metadata.iconUrl が署名URL＋組織ページで画像表示', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)

    const createRes = await page.request.post(`${API_BASE}/api/v1/organizations`, {
      data: { name: `E2E Media Org ${ts}`, orgType: 'OTHER' },
      ...auth(token),
    })
    expect(createRes.ok(), `組織作成: ${createRes.status()} ${createRes.ok() ? '' : await createRes.text()}`).toBeTruthy()
    const org = (await createRes.json()).data as { id: string; slug: string }
    const slug = org.slug ?? org.id
    console.log('[org] created slug:', slug)

    const myOrgs = (await (await page.request.get(`${API_BASE}/api/v1/me/organizations?size=100`, auth(token))).json()).data as Array<{ id: number; slug: string }>
    const numericId = myOrgs.find((o) => o.slug === slug)?.id
    expect(numericId, `作成組織の数値IDが /me/organizations で解決できること（slug=${slug}）`).toBeTruthy()

    const up = await uploadIconViaApi(page.request, token, `/api/v1/organizations/${numericId}`)
    expect(isSignedMinioUrl(up.commitUrl), `commit url が署名URL: ${up.commitUrl}`).toBeTruthy()

    const detail = (await (await page.request.get(`${API_BASE}/api/v1/organizations/${slug}`, auth(token))).json()).data as { metadata?: { iconUrl?: string } }
    const apiIconUrl = detail.metadata?.iconUrl
    console.log('[org] GET metadata.iconUrl:', apiIconUrl)
    expect(isSignedMinioUrl(apiIconUrl), `★ GET /organizations/{slug} の metadata.iconUrl が署名URLであること（実値=${apiIconUrl}）`).toBeTruthy()

    await page.goto(`/organizations/${slug}`, { waitUntil: 'domcontentloaded' })
    const iconImg = page.locator(`img[src^="${MINIO_ORIGIN}"]`).first()
    await assertImgDisplaysAndLoads(page, iconImg, 'org-icon')
    await page.screenshot({ path: `test-results/media-sweep-org-${ts}.png`, fullPage: true })
  })

  test('SWEEP-03 ユーザーアバター: GET /users/me の avatarUrl が署名URL＋プロフィールページで画像表示', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)

    const up = await uploadIconViaApi(page.request, token, `/api/v1/users/me`)
    expect(isSignedMinioUrl(up.commitUrl), `commit url が署名URL: ${up.commitUrl}`).toBeTruthy()

    const me = (await (await page.request.get(`${API_BASE}/api/v1/users/me`, auth(token))).json()).data as { avatarUrl?: string }
    console.log('[user] GET avatarUrl:', me.avatarUrl)
    expect(isSignedMinioUrl(me.avatarUrl), `★ GET /users/me の avatarUrl が署名URLであること（実値=${me.avatarUrl}）`).toBeTruthy()

    await page.goto('/settings/profile', { waitUntil: 'domcontentloaded' })
    const avatarImg = page.locator(`img[src^="${MINIO_ORIGIN}"]`).first()
    await assertImgDisplaysAndLoads(page, avatarImg, 'user-avatar')
    await page.screenshot({ path: `test-results/media-sweep-user-${ts}.png`, fullPage: true })
  })

  test('SWEEP-04 お気に入り: GET /me/favorites の iconUrl が署名URL（Phase3）', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)

    const createRes = await page.request.post(`${API_BASE}/api/v1/teams`, {
      data: { name: `E2E Favorite Team ${ts}` },
      ...auth(token),
    })
    expect(createRes.ok(), `チーム作成: ${createRes.status()}`).toBeTruthy()
    const team = (await createRes.json()).data as { id: string; slug: string }
    const slug = team.slug ?? team.id

    const myTeams = (await (await page.request.get(`${API_BASE}/api/v1/me/teams?size=100`, auth(token))).json()).data as Array<{ id: number; slug: string }>
    const numericId = myTeams.find((t) => t.slug === slug)?.id
    expect(numericId, '数値ID解決').toBeTruthy()
    await uploadIconViaApi(page.request, token, `/api/v1/teams/${numericId}`)

    // お気に入り登録（entityId は数値ID文字列）
    const favRes = await page.request.post(`${API_BASE}/api/v1/me/favorites`, {
      data: { entityType: 'TEAM', entityId: String(numericId) },
      ...auth(token),
    })
    expect(favRes.ok(), `お気に入り登録: ${favRes.status()} ${favRes.ok() ? '' : await favRes.text()}`).toBeTruthy()

    // ★根治点: GET /me/favorites の該当 iconUrl が署名URL
    const favs = (await (await page.request.get(`${API_BASE}/api/v1/me/favorites`, auth(token))).json()).data as Array<{ entityType: string; entityId: string; iconUrl?: string }>
    const target = favs.find((f) => f.entityType === 'TEAM' && f.entityId === String(numericId))
    expect(target, `登録したお気に入りが一覧に存在すること（id=${numericId}）`).toBeTruthy()
    console.log('[favorite] iconUrl:', target?.iconUrl)
    expect(isSignedMinioUrl(target?.iconUrl), `★ GET /me/favorites の iconUrl が署名URLであること（実値=${target?.iconUrl}）`).toBeTruthy()
    // 裏取り: お気に入りの署名URLが実画像を返す
    const getRes = await page.request.get(target!.iconUrl as string)
    expect(getRes.status(), 'お気に入り iconUrl が 200 で取得できること').toBe(200)
  })

  test('SWEEP-05 メンバー一覧: GET /teams/{slug}/members の avatarUrl が署名URL（Phase2/4）', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)

    // 自分のアバターを最新化（メンバー一覧に署名URLで現れる）
    await uploadIconViaApi(page.request, token, `/api/v1/users/me`)

    const createRes = await page.request.post(`${API_BASE}/api/v1/teams`, {
      data: { name: `E2E Member Team ${ts}` },
      ...auth(token),
    })
    expect(createRes.ok(), `チーム作成: ${createRes.status()}`).toBeTruthy()
    const team = (await createRes.json()).data as { id: string; slug: string }
    const slug = team.slug ?? team.id

    const membersRes = await page.request.get(`${API_BASE}/api/v1/teams/${slug}/members`, auth(token))
    expect(membersRes.ok(), `GET /teams/${slug}/members: ${membersRes.status()}`).toBeTruthy()
    const body = await membersRes.json()
    const members = (body.data ?? body.items ?? []) as Array<{ avatarUrl?: string }>
    const withAvatar = members.filter((m) => m.avatarUrl)
    console.log('[members] count:', members.length, 'withAvatar:', withAvatar.length)
    expect(withAvatar.length, 'avatarUrl を持つメンバーが1人以上いること').toBeGreaterThan(0)
    for (const m of withAvatar) {
      expect(isSignedMinioUrl(m.avatarUrl), `★ メンバー avatarUrl が署名URLであること（実値=${m.avatarUrl}）`).toBeTruthy()
    }
  })
})
