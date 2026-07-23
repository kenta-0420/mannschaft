import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * TL-IMG: タイムライン投稿画像の署名URL解決 実機E2E（モックなし・書込一気通貫）。
 *
 * ── 何を検証しているか（issue #2424 / PR #2452 の根治点）──────────────────
 * タイムライン添付（`timeline_post_attachments`）には R2 生キー（`file.fileKey`）しか保存されない。
 * 従来 `AttachmentResponse.AttachmentImageDto` は画像の表示URLを一切持たず、FE の
 * `TimelinePostCard.vue` は `att.image?.thumbnailUrl || att.image?.url` を描画しようとしても
 * 常に undefined で画像が表示できなかった（issue #2424）。#2424 は `TimelinePostService` が
 * `MediaUrlResolver` で生キーを署名付き GET URL に解決し `image.url` / `image.thumbnailUrl`
 * （画像は別サムネイルを持たないため同一値）へ埋め込むよう是正した。
 * 本 spec はその解決経路が **実際に画像として表示されるところまで** 到達していることを、
 * 実 BE / 実 MinIO / 実ブラウザで確認する（`blog-body-image-signed-url.spec.ts` の作法を踏襲）。
 *
 * ── 最重要の落とし穴（この spec の設計理由）────────────────────────
 * `MediaUrlResolver.resolve()` は R2/MinIO 上のオブジェクト実在を検証しない。
 * 存在しないダミーキーに対しても署名URLは生成できてしまうため、「URL に `X-Amz-` が
 * 付いているか」だけを見る検証は実体が無くても通ってしまう偽の検証である。
 * よって TL-IMG-01/02 では必ず presign → **MinIO へ実 PUT したオブジェクト** を使い、
 * ブラウザ上の `<img>` が `naturalWidth > 0`（実デコード成功）であること、
 * および `src` を直接 GET して 200 が返ることまで確認する。
 *
 * ── スコープ選定の理由 ──────────────────────────────────────
 * TL-IMG-01/02 は PUBLIC スコープ（`scopeId=0`・誰でも読み書き可）を使う。#2424 の署名URL契約は
 * スコープ非依存で同一経路（`AttachmentResponse` 組み立て）を通るため、村/チームメンバーシップ等の
 * 重いセットアップは不要。TL-IMG-03（越境認可）のみ PERSONAL スコープを使い、
 * 「投稿者本人以外は詳細取得できない」ことを検証する。
 *
 * ── 越境防御の設計（TL-IMG-03）───────────────────────────────
 * timeline の presign / MediaUrlResolver 自体には fileKey のスコープ prefix 検証は無い
 * （`TimelineAttachmentController` は無条件署名）。越境防御は **投稿可視性（BOLA）層** で行われる:
 * `TimelinePostService#isPostVisible` が PERSONAL スコープでは「呼び出し元 userId ==
 * post.userId」のみ許可し、不一致なら `getPostDetail` は {@code TimelineErrorCode#POST_NOT_FOUND}
 * （404・対象IDの実在を秘匿）を投げる。よって越境ケースは「fileKey を手書きして直接 GET する」の
 * ではなく「非公開投稿の詳細を別ユーザーが取得しようとして 404 になり、添付（署名URL含む）が
 * 一切返らないこと」を検証する。
 *
 * ── テストID ──────────────────────────────────────────────
 *   TL-IMG-01  presign→実PUT→投稿作成→詳細取得(GET /posts/{id})で署名URL化→ブラウザ実表示（AC-1）
 *   TL-IMG-02  同投稿がスコープフィード(GET /feed?scopeType=PUBLIC)にも署名URLで現れブラウザ実表示（AC-2）
 *   TL-IMG-03  PERSONAL投稿の詳細取得は別ユーザーからは404（POST_NOT_FOUND）＝添付が越境露出しない（AC-3）
 *
 * ── 実行方法 ──────────────────────────────────────────────
 *   cd frontend
 *   BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *   MINIO_ORIGIN=http://localhost:9000 \
 *   node node_modules/@playwright/test/cli.js test tests/e2e/real/timeline/timeline-image-signed-url.spec.ts \
 *     --project=chromium-real --workers=1 --reporter=list
 *
 * ── 前提条件 ──────────────────────────────────────────────
 *   - MinIO 起動済み（docker compose --profile storage up -d / :9000 / bucket mannschaft-storage）
 *   - BE が MinIO を storage endpoint として参照していること（local プロファイル既定で自動）
 *   - TEST_USER_EMAIL（既定 e2e-user@test.mannschaft.local）が ACTIVE で存在すること
 *   - SECOND_USER_EMAIL（既定 e2e-pwui-1782136885@test.mannschaft.local）が ACTIVE で存在すること
 *     （TL-IMG-03 の越境アクセス役。TEST_USER_EMAIL とは別ユーザーであること）
 *
 * ── 後始末 ────────────────────────────────────────────────
 *   各テストが作成した投稿は finally で DELETE /api/v1/timeline/posts/{id}（論理削除）する。
 *   MinIO へ実 PUT したオブジェクトは使い捨て前提で残置する（1x1 PNG=68バイトで影響は無視できる。
 *   BE 側に添付単体の削除APIが無いため）。
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

/** 越境認可（TL-IMG-03）用の別ユーザー。TEST_USER_EMAIL とは異なるアカウントであること。 */
const SECOND_USER_EMAIL = process.env.SECOND_USER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local'
const SECOND_USER_PASSWORD = process.env.SECOND_USER_PASSWORD ?? 'Passw0rd!2026'

/** 1x1 透過 PNG（68バイト）。MinIO へ実際に PUT する本物の画像バイト列。 */
const PNG_BYTES = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

/** MinIO の署名付き GET URL であることの判定（絶対URL＋AWS SigV4 クエリ）。 */
function isSignedMinioUrl(url: string | null | undefined): boolean {
  if (!url) return false
  return url.startsWith(MINIO_ORIGIN) && /X-Amz-(Signature|Credential|Algorithm)=/.test(url)
}

interface Me {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

/** Authorization: Bearer 付きヘッダ。 */
function auth(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/**
 * fresh ログインしトークンのみ返す（ブラウザ描画を伴わない用途向け）。
 * TL-IMG-03 の越境役ユーザーのように、そのユーザーとしてブラウザ遷移しない場合に使う。
 */
async function loginToken(req: APIRequestContext, email: string, password: string): Promise<{ token: string, userId: number }> {
  const res = await req.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  if (!res.ok()) throw new Error(`ログイン失敗(${email}): ${res.status()} ${await res.text()}`)
  const data = (await res.json()).data as { accessToken: string, userId: number }
  return { token: data.accessToken, userId: data.userId }
}

/**
 * fresh ログイン。access_token を返し、cookie を browser context へ載せ、
 * localStorage['currentUser'] を addInitScript で初期注入する。
 * （blog-body-image-signed-url.spec.ts と同一の作法。FE 主導の refresh ローテーションで
 *   cookie が無効化されても API 呼び出しが 401 にならないよう Bearer を必ず併用する。）
 */
async function login(page: Page): Promise<{ token: string, me: Me }> {
  const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
    data: { email: USER_EMAIL, password: USER_PASSWORD },
  })
  if (!loginRes.ok()) {
    throw new Error(`ログイン失敗: ${loginRes.status()} ${await loginRes.text()}`)
  }
  const token = (await loginRes.json()).data.accessToken as string
  const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`, auth(token))
  if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
  const me = (await meRes.json()).data as Me
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

interface PresignResult {
  fileKey: string
  uploadUrl: string
}

/**
 * 画像 presign を発行する。リクエスト／レスポンスとも camelCase（{@link ImageUploadUrlRequest} /
 * {@link ImageUploadUrlResponse} が Jackson デフォルトの camelCase で扱う）。
 * `scopeId` は presign 経路では Long（JSON number）であり、投稿作成の String とは異なる点に注意。
 */
async function presignImage(
  req: APIRequestContext,
  token: string,
  params: { scopeType: string, scopeId: number },
): Promise<PresignResult> {
  const res = await req.post(`${API_BASE}/api/v1/timeline/attachments/upload-image-url`, {
    data: { contentType: 'image/png', scopeType: params.scopeType, scopeId: params.scopeId },
    ...auth(token),
  })
  expect(res.status(), `presign が200で返ること: ${res.ok() ? '' : await res.text()}`).toBe(200)
  const data = (await res.json()).data as { fileKey: string, uploadUrl: string, expiresInSeconds: number }
  expect(data.fileKey, 'presign が fileKey を返すこと').toBeTruthy()
  return { fileKey: data.fileKey, uploadUrl: data.uploadUrl }
}

/**
 * presign → MinIO 直 PUT まで行い、投稿に添付する fileKey を返す。
 * ★ ここで実際に PUT することが本 spec の肝。ダミーキーでも署名URLは作れてしまうため、
 *   実オブジェクトを置いて初めて「画像が本当に表示される」ことを検証できる。
 */
async function uploadImage(
  req: APIRequestContext,
  token: string,
  params: { scopeType: string, scopeId: number },
): Promise<string> {
  const presign = await presignImage(req, token, params)
  expect(
    presign.fileKey.startsWith(`timeline/${params.scopeType}/${params.scopeId}/`),
    `スコープ接頭辞の r2Key であること（実値=${presign.fileKey}）`,
  ).toBeTruthy()

  // 署名は content-type;host を含むため Content-Type ヘッダ必須
  const putRes = await req.put(presign.uploadUrl, {
    data: PNG_BYTES,
    headers: { 'Content-Type': 'image/png' },
  })
  expect([200, 204], `MinIO 直PUT が 200/204 であること（実値=${putRes.status()}）`).toContain(putRes.status())
  return presign.fileKey
}

interface CreatedPost {
  id: number
}

/**
 * タイムライン投稿を作成する。`scopeId` は投稿作成経路では String（slug/数値文字列いずれも可）。
 */
async function createPost(
  req: APIRequestContext,
  token: string,
  params: {
    content: string
    scopeType: string
    scopeId: string
    attachments?: Array<{ attachmentType: string, fileKey: string, imageWidth: number, imageHeight: number, sortOrder: number }>
  },
): Promise<CreatedPost> {
  const res = await req.post(`${API_BASE}/api/v1/timeline/posts`, {
    data: {
      content: params.content,
      scopeType: params.scopeType,
      scopeId: params.scopeId,
      attachments: params.attachments,
    },
    ...auth(token),
  })
  expect(res.status(), `投稿作成が201で返ること: ${res.ok() ? '' : await res.text()}`).toBe(201)
  const data = (await res.json()).data as { id: number }
  return { id: data.id }
}

interface AttachmentImage {
  url: string | null
  thumbnailUrl: string | null
}
interface Attachment {
  attachmentType: string
  file: { fileKey: string }
  image: AttachmentImage
}
interface PostDetail {
  id: number
  attachments: Attachment[]
}

/** 投稿詳細を取得する（表示経路。署名URL解決あり）。 */
async function getPostDetail(
  req: APIRequestContext,
  token: string,
  postId: number,
): Promise<{ status: number, body: string, detail: PostDetail | null }> {
  const res = await req.get(`${API_BASE}/api/v1/timeline/posts/${postId}`, auth(token))
  const bodyText = await res.text()
  if (!res.ok()) {
    return { status: res.status(), body: bodyText, detail: null }
  }
  const detail = (JSON.parse(bodyText)).data as PostDetail
  return { status: res.status(), body: bodyText, detail }
}

/** スコープ別フィードを取得する（表示経路。署名URL解決あり）。 */
async function getFeed(
  req: APIRequestContext,
  token: string,
  params: { scopeType: string, scopeId: string, size: number },
): Promise<Array<{ id: number, attachments: Attachment[] }>> {
  const res = await req.get(
    `${API_BASE}/api/v1/timeline/feed?scopeType=${params.scopeType}&scopeId=${params.scopeId}&size=${params.size}`,
    auth(token),
  )
  expect(res.status(), `フィード取得が200で返ること: ${res.ok() ? '' : await res.text()}`).toBe(200)
  const data = (await res.json()).data as { pinned: unknown[], posts: Array<{ id: number, attachments: Attachment[] }> }
  return data.posts
}

/** 使い捨て投稿を論理削除する（後始末）。 */
async function deletePost(req: APIRequestContext, token: string, postId: number): Promise<void> {
  const res = await req.delete(`${API_BASE}/api/v1/timeline/posts/${postId}`, auth(token))
  console.log(`[cleanup] DELETE post ${postId}: ${res.status()}`)
}

test.describe('TL-IMG: タイムライン投稿画像の署名URL解決 実機E2E', () => {
  // storageState を空にして各テストで fresh ログインする（トークンローテーション事故の回避）
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)

  // ===========================================================================
  // TL-IMG-01: AC-1（投稿詳細で署名URL化＋ブラウザで実表示）
  // ===========================================================================
  test('TL-IMG-01: presign→実PUT→投稿作成→詳細取得(GET /posts/{id})で署名URL化されブラウザに画像が実表示される（AC-1）', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)
    let postId: number | undefined

    try {
      // ---- 1. presign → MinIO へ実 PUT（実オブジェクトを置く）----
      const fileKey = await uploadImage(page.request, token, { scopeType: 'PUBLIC', scopeId: 0 })
      console.log('[TL-IMG-01] 実PUT済み r2Key:', fileKey)

      // ---- 2. 投稿を作成し、添付として fileKey を紐付ける ----
      const post = await createPost(page.request, token, {
        content: `E2E TL-IMG-01 署名URL検証 ${ts}`,
        scopeType: 'PUBLIC',
        scopeId: '0',
        attachments: [{ attachmentType: 'IMAGE', fileKey, imageWidth: 1, imageHeight: 1, sortOrder: 0 }],
      })
      postId = post.id
      console.log('[TL-IMG-01] 作成投稿 id:', postId)

      // ---- 3. ★根治点: 投稿詳細の attachments[0].image.url が署名URLであること ----
      const { status, detail } = await getPostDetail(page.request, token, postId)
      expect(status, '投稿詳細 GET が200で返ること').toBe(200)
      expect(detail, '投稿詳細のボディが取得できること').not.toBeNull()
      expect(detail!.attachments.length, '添付が1件であること').toBe(1)
      const att = detail!.attachments[0]!
      expect(att.attachmentType, '添付種別がIMAGEであること').toBe('IMAGE')
      expect(att.file.fileKey, '添付の生fileKeyが一致すること').toBe(fileKey)
      console.log('[TL-IMG-01] image.url:', att.image.url)
      expect(isSignedMinioUrl(att.image.url),
        `★ attachments[0].image.url が署名URLであること（実値=${att.image.url}）`).toBeTruthy()
      expect(att.image.thumbnailUrl,
        '画像は別サムネイルを持たないため thumbnailUrl は url と同一値であること').toBe(att.image.url)

      // ---- 4. 署名URLが実オブジェクトへ到達すること（ダミーキーでも署名は作れるため必須）----
      const signedGet = await page.request.get(att.image.url!)
      console.log('[TL-IMG-01] 署名URL GET:', signedGet.status())
      expect(signedGet.status(), '署名URLが200で実バイトを返すこと（オブジェクトが実在すること）').toBe(200)
      expect((await signedGet.body()).length, '取得したバイト列が空でないこと').toBeGreaterThan(0)

      // ---- 5. ★ブラウザで投稿詳細ページを開き、<img> が実際に描画・デコードされること ----
      await page.goto(`/timeline/${postId}`, { waitUntil: 'domcontentloaded' })
      const img = page.locator(`img[src^="${MINIO_ORIGIN}"]`).first()
      await expect(img, '添付画像 <img> が表示されること').toBeVisible({ timeout: 45_000 })
      const imgSrc = await img.getAttribute('src')
      console.log('[TL-IMG-01] ブラウザ img src:', imgSrc)
      expect(isSignedMinioUrl(imgSrc), `<img src> が署名URLであること（実値=${imgSrc}）`).toBeTruthy()
      // ★ naturalWidth>0 = 画像バイトのデコードに成功した証拠。toBeVisible はレイアウト確定で
      //   先に解決してしまうため、これが無いと「壊れた画像でも通る」偽の検証になる。
      await expect
        .poll(async () => img.evaluate((el) => (el as HTMLImageElement).naturalWidth), {
          message: '画像が実デコードされること（naturalWidth>0）',
          timeout: 20_000,
        })
        .toBeGreaterThan(0)
      const imgGet = await page.request.get(imgSrc!)
      expect(imgGet.status(), 'ブラウザが読んだ src が 200 で取得できること（404でない）').toBe(200)
      await page.screenshot({ path: `test-results/timeline-img-01-detail-${ts}.png`, fullPage: true })
    } finally {
      if (postId) await deletePost(page.request, token, postId)
    }
  })

  // ===========================================================================
  // TL-IMG-02: AC-2（スコープフィードでも署名URL化＋ブラウザで実表示）
  // ===========================================================================
  test('TL-IMG-02: 投稿がスコープフィード(GET /feed?scopeType=PUBLIC)にも署名URLで現れブラウザに実表示される（AC-2）', async ({ page }) => {
    const ts = Date.now()
    const { token } = await login(page)
    let postId: number | undefined

    try {
      const fileKey = await uploadImage(page.request, token, { scopeType: 'PUBLIC', scopeId: 0 })
      const post = await createPost(page.request, token, {
        content: `E2E TL-IMG-02 フィード署名URL検証 ${ts}`,
        scopeType: 'PUBLIC',
        scopeId: '0',
        attachments: [{ attachmentType: 'IMAGE', fileKey, imageWidth: 1, imageHeight: 1, sortOrder: 0 }],
      })
      postId = post.id
      console.log('[TL-IMG-02] 作成投稿 id:', postId)

      // ---- ★根治点: GET /feed の該当投稿でも attachments[0].image.url が署名URLであること ----
      // 新着順（created_at DESC）のため直近作成分は先頭付近に来るが、並行実行での取り違えを避け
      // 「一覧内に該当 id を探す」形で判定する。
      const posts = await getFeed(page.request, token, { scopeType: 'PUBLIC', scopeId: '0', size: 20 })
      const found = posts.find((p) => p.id === postId)
      expect(found, `フィードに作成した投稿(id=${postId})が含まれること`).toBeTruthy()
      expect(found!.attachments.length, 'フィード内投稿の添付が1件であること').toBe(1)
      const att = found!.attachments[0]!
      console.log('[TL-IMG-02] feed image.url:', att.image.url)
      expect(isSignedMinioUrl(att.image.url),
        `★ フィード内 attachments[0].image.url が署名URLであること（実値=${att.image.url}）`).toBeTruthy()

      const signedGet = await page.request.get(att.image.url!)
      expect(signedGet.status(), 'フィードが返した署名URLが200で実バイトを返すこと').toBe(200)

      // ---- ★ブラウザで /timeline（PUBLIC スコープ集約ページ）を開き実表示を確認する ----
      await page.goto('/timeline', { waitUntil: 'domcontentloaded' })
      // 同一スコープに他の投稿の画像も並ぶため、実 fileKey を含む署名URLで対象を一意に絞る。
      // 署名URLは fileKey をパスの一部として必ず含む（例 .../timeline/PUBLIC/0/images/<uuid>.png?X-Amz-...）。
      const keyPathSegment = fileKey.split('/').slice(-1)[0]! // "<uuid>.png"
      const img = page.locator(`img[src*="${keyPathSegment}"]`).first()
      await expect(img, '対象投稿の添付画像 <img> がフィードに表示されること').toBeVisible({ timeout: 45_000 })
      const imgSrc = await img.getAttribute('src')
      console.log('[TL-IMG-02] ブラウザ img src:', imgSrc)
      expect(isSignedMinioUrl(imgSrc), `<img src> が署名URLであること（実値=${imgSrc}）`).toBeTruthy()
      await expect
        .poll(async () => img.evaluate((el) => (el as HTMLImageElement).naturalWidth), {
          message: '画像が実デコードされること（naturalWidth>0）',
          timeout: 20_000,
        })
        .toBeGreaterThan(0)
      await page.screenshot({ path: `test-results/timeline-img-02-feed-${ts}.png`, fullPage: true })
    } finally {
      if (postId) await deletePost(page.request, token, postId)
    }
  })

  // ===========================================================================
  // TL-IMG-03: AC-3（越境認可・BOLA）PERSONAL投稿の詳細取得は投稿者本人以外は404
  // ===========================================================================
  test('TL-IMG-03: PERSONAL投稿の詳細取得は別ユーザーからは404(POST_NOT_FOUND)＝添付が越境露出しない（AC-3）', async ({ page }) => {
    const ts = Date.now()
    const { token, me } = await login(page)
    const { token: otherToken, userId: otherUserId } = await loginToken(page.request, SECOND_USER_EMAIL, SECOND_USER_PASSWORD)
    expect(otherUserId, '越境役ユーザーは投稿者本人と別ユーザーであること（前提が壊れていないこと）')
      .not.toBe(me.id)
    let postId: number | undefined

    try {
      // ---- 1. id23（本人）が PERSONAL スコープに画像付き投稿を作成する ----
      // PERSONAL の presign scopeId は「投稿者自身の userId」を使う
      // （TimelinePostService#requireSelfScope が resolvedScopeId==userId を要求するため）。
      const fileKey = await uploadImage(page.request, token, { scopeType: 'PERSONAL', scopeId: me.id })
      const post = await createPost(page.request, token, {
        content: `E2E TL-IMG-03 越境認可検証 ${ts}`,
        scopeType: 'PERSONAL',
        scopeId: String(me.id),
        attachments: [{ attachmentType: 'IMAGE', fileKey, imageWidth: 1, imageHeight: 1, sortOrder: 0 }],
      })
      postId = post.id
      console.log('[TL-IMG-03] 作成投稿(PERSONAL) id:', postId)

      // ---- 2. 前提確認: 投稿者本人からは 200 で取得でき、署名URLも正しく載ること ----
      const selfResult = await getPostDetail(page.request, token, postId)
      expect(selfResult.status, '前提: 投稿者本人は自分のPERSONAL投稿を取得できること').toBe(200)
      expect(selfResult.detail!.attachments.length, '前提: 添付が1件であること').toBe(1)
      expect(isSignedMinioUrl(selfResult.detail!.attachments[0]!.image.url),
        '前提: 本人からは署名URLが正しく解決されること（解決処理自体が壊れていないことの確認）').toBeTruthy()

      // ---- 3. ★根治点: 別ユーザー(id=90209)からは 404 POST_NOT_FOUND で、添付が一切露出しないこと ----
      const otherResult = await getPostDetail(page.request, otherToken, postId)
      console.log('[TL-IMG-03] 別ユーザーからの GET status:', otherResult.status)
      console.log('[TL-IMG-03] 別ユーザーからの GET body:', otherResult.body)
      expect(otherResult.status, '★ 別ユーザーからのPERSONAL投稿詳細取得が404であること（BOLA）').toBe(404)
      expect(otherResult.detail, '404レスポンスに data（attachments含む）が含まれないこと').toBeNull()
      expect(otherResult.body, 'エラーコードが TIMELINE_001(POST_NOT_FOUND) であること').toContain('TIMELINE_001')
      // 越境時のレスポンスに署名URL・生fileKeyのいずれも一切含まれないこと（漏洩していないこと）
      expect(otherResult.body, '★ 越境レスポンスに署名パラメータ(X-Amz-)が含まれないこと').not.toContain('X-Amz-')
      expect(otherResult.body, '★ 越境レスポンスに生fileKeyが含まれないこと').not.toContain(fileKey)
    } finally {
      if (postId) await deletePost(page.request, token, postId)
    }
  })
})
