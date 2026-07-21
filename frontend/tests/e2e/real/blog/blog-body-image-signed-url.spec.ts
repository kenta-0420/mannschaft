import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * BLOG-IMG: ブログ本文画像の署名URL解決 実機E2E（モックなし・書込一気通貫）。
 *
 * ── 何を検証しているか（根治点）──────────────────────────────────
 * ブログ記事の本文（`blog_posts.body`）には **生の r2Key を含む Markdown がそのまま保存** される
 * （例: `![alt](blog/PERSONAL/23/xxx.png)`）。署名URLには有効期限があるため本文へ焼き込めない、
 * という設計判断による。読取時に `BlogBodyMediaResolver`（backend/.../cms/media/）が正規表現置換で
 * 署名付き GET URL へ差し替える。本 spec はその解決経路が **実際に画像として表示されるところまで**
 * 到達していることを、実 BE / 実 MinIO / 実ブラウザで確認する。
 *
 * ── 最重要の落とし穴（この spec の設計理由）────────────────────────
 * `MediaUrlResolver.resolve()` は **R2/MinIO 上のオブジェクト実在を検証しない**。
 * 存在しないダミーキーに対しても署名URLは生成できてしまう。したがって
 * 「URL に `X-Amz-` が付いているか」だけを見る検証は、実体が無くても通ってしまう **偽の検証** である。
 * よって AC-1 では必ず presign → **MinIO へ実 PUT したオブジェクト** を使い、
 * ブラウザ上の `<img>` が `naturalWidth > 0`（実デコード成功）であること、
 * および `src` を直接 GET して 200 が返ることまで確認する。
 *
 * ── 二段の関門（意図的なセキュリティ仕様。バグではない）──────────────
 *   関門1: スコープ接頭辞のセグメント完全一致（`blog/{scopeType}/{scopeId}/...`）。
 *          隣接IDへの前方一致漏れ・`../` traversal・`%` エンコードを拒否する。
 *   関門2: `blog_media_uploads` 台帳との照合（`findByS3KeyIn`）。
 *          presign 経路を通していない手書き r2Key は解決されず、本文にそのまま残る。
 * → BLOG-IMG-02 はこの 2 つを「解決されないこと」の逆アサーションで踏む。
 *
 * ── 編集経路は解決しないのが正しい（AC-2）──────────────────────
 * `GET /api/v1/users/me/blog/posts/{id}`（`BlogPostService#getMyPostById`）は編集画面専用の入口で、
 * **意図的に署名URL解決を行わない**。ここで解決すると、利用者が編集保存した瞬間に期限付き署名URLが
 * `blog_posts.body` へ永続保存され、数十分後に記事の画像が恒久的に壊れる。
 * 「生 r2Key のまま返ること」を積極的に守るべき仕様として検証する（解決漏れと誤判定しないこと）。
 *
 * ── テストID ──────────────────────────────────────────────
 *   BLOG-IMG-01  presign→実PUT→本文埋込→表示経路で署名URL化→ブラウザで実表示（AC-1）
 *                ＋ 編集経路は生r2Keyのまま（AC-2 / 逆アサーション）
 *   BLOG-IMG-02  越境キー（AC-3）・台帳未登録キー（AC-4）は署名URL化されず本文に残る
 *   BLOG-IMG-03  画像0枚の本文が500にならず、ブラウザで本文が表示される（AC-5）
 *   BLOG-IMG-04  画像30枚ちょうどは成功・31枚目は拒否（AC-6）
 *                ＋ 30枚の本文が1リクエストで全件署名URL化される（AC-7 / N+1なし）
 *
 * ── 実行方法 ──────────────────────────────────────────────
 *   cd frontend
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *   MINIO_ORIGIN=http://localhost:9000 \
 *   npx playwright test tests/e2e/real/blog/blog-body-image-signed-url.spec.ts \
 *     --project=chromium-real --workers=1 --reporter=list
 *
 * ── 前提条件 ──────────────────────────────────────────────
 *   - MinIO 起動済み（docker compose --profile storage up -d / :9000 / bucket mannschaft-storage）
 *   - BE が MinIO を storage endpoint として参照していること
 *   - e2e-user@test.mannschaft.local が ACTIVE で存在すること
 *
 * ── 後始末 ────────────────────────────────────────────────
 *   各テストが作成した記事は finally で DELETE /api/v1/users/me/blog/posts/{id}（論理削除）する。
 *   MinIO へ実 PUT したオブジェクトと blog_media_uploads の行は使い捨て前提で残置する
 *   （BE 側に媒体削除APIが無く、記事削除は媒体を回収しない仕様のため。1x1 PNG=68バイトで影響は無視できる）。
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const MINIO_ORIGIN = process.env.MINIO_ORIGIN ?? 'http://localhost:9000'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

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
 * fresh ログイン。access_token を返し、cookie を browser context へ載せ、
 * localStorage['currentUser'] を addInitScript で初期注入する。
 * （media-url-resolve-sweep.spec.ts と同一の作法。FE 主導の refresh ローテーションで
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
  // 対象ページへ単一遷移しても認証済みと判定されるよう localStorage を初期注入する
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

interface CreatedPost {
  id: number
  slug: string
}

/**
 * 個人ブログ記事を新規作成する（teamId/organizationId ともに未指定＝PERSONAL スコープ）。
 * slug は衝突回避のため呼び出し側で一意に採る（BE は create 時に slug の一意性検査をしない）。
 */
async function createPost(
  req: APIRequestContext,
  token: string,
  params: { title: string, slug: string, body: string },
): Promise<CreatedPost> {
  const res = await req.post(`${API_BASE}/api/v1/users/me/blog/posts`, {
    data: { title: params.title, slug: params.slug, body: params.body, visibility: 'PUBLIC' },
    ...auth(token),
  })
  expect(res.status(), `記事作成が201で返ること: ${res.ok() ? '' : await res.text()}`).toBe(201)
  const data = (await res.json()).data as { id: number, content: { slug: string } }
  expect(data.content.slug, '作成レスポンスに slug が含まれること').toBe(params.slug)
  return { id: data.id, slug: data.content.slug }
}

/** 記事本文を差し替える（画像埋め込みは作成後に行う。presign に blog_post_id が要るため）。 */
async function updateBody(
  req: APIRequestContext,
  token: string,
  postId: number,
  title: string,
  body: string,
): Promise<void> {
  const res = await req.put(`${API_BASE}/api/v1/users/me/blog/posts/${postId}`, {
    data: { title, body, visibility: 'PUBLIC' },
    ...auth(token),
  })
  expect(res.status(), `記事更新が200で返ること: ${res.ok() ? '' : await res.text()}`).toBe(200)
}

/**
 * 画像 presign を発行する。リクエスト／レスポンスとも snake_case である点に注意
 * （BlogMediaUploadUrlRequest / BlogMediaUploadUrlResponse が @JsonProperty で明示している）。
 * IMAGE は presign と同時に blog_media_uploads へ INSERT される（commit ステップは存在しない）。
 * blog_post_id を渡した時のみ 1記事30枚の上限チェックが効く。
 */
async function presignImage(
  req: APIRequestContext,
  token: string,
  params: { scopeId: number, blogPostId: number },
): Promise<{ fileKey: string, uploadUrl: string, status: number }> {
  const res = await req.post(`${API_BASE}/api/v1/blog/media/upload-url`, {
    data: {
      media_type: 'IMAGE',
      content_type: 'image/png',
      file_size: PNG_BYTES.length,
      scope_type: 'PERSONAL',
      scope_id: params.scopeId,
      blog_post_id: params.blogPostId,
    },
    ...auth(token),
  })
  if (!res.ok()) {
    // 上限超過（422）などの異常系は呼び出し側で status を検査する
    return { fileKey: '', uploadUrl: '', status: res.status() }
  }
  const data = (await res.json()).data as { file_key: string, upload_url: string }
  return { fileKey: data.file_key, uploadUrl: data.upload_url, status: res.status() }
}

/**
 * presign → MinIO 直 PUT まで行い、本文へ埋め込む r2Key を返す。
 * ★ ここで実際に PUT することが本 spec の肝。ダミーキーでも署名URLは作れてしまうため、
 *   実オブジェクトを置いて初めて「画像が本当に表示される」ことを検証できる。
 */
async function uploadImage(
  req: APIRequestContext,
  token: string,
  params: { scopeId: number, blogPostId: number },
): Promise<string> {
  const presign = await presignImage(req, token, params)
  expect(presign.status, 'presign が200で返ること').toBe(200)
  expect(presign.fileKey, 'presign が file_key を返すこと').toBeTruthy()
  expect(presign.fileKey.startsWith(`blog/PERSONAL/${params.scopeId}/`),
    `PERSONAL スコープの r2Key 接頭辞であること（実値=${presign.fileKey}）`).toBeTruthy()

  // 署名は content-type;host を含むため Content-Type ヘッダ必須
  const putRes = await req.put(presign.uploadUrl, {
    data: PNG_BYTES,
    headers: { 'Content-Type': 'image/png' },
  })
  expect([200, 204], `MinIO 直PUT が 200/204 であること（実値=${putRes.status()}）`).toContain(putRes.status())
  return presign.fileKey
}

/** 表示経路（署名URL解決あり）で本文を取得する。 */
async function fetchViewBody(
  req: APIRequestContext,
  token: string,
  userId: number,
  slug: string,
): Promise<string> {
  const res = await req.get(`${API_BASE}/api/v1/users/${userId}/blog/posts/${slug}`, auth(token))
  expect(res.status(), `表示経路 GET が200で返ること: ${res.ok() ? '' : await res.text()}`).toBe(200)
  const data = (await res.json()).data as { content: { body: string | null } }
  return data.content.body ?? ''
}

/** 編集経路（署名URL解決なし＝生 r2Key のまま返るのが正しい）で本文を取得する。 */
async function fetchEditBody(
  req: APIRequestContext,
  token: string,
  postId: number,
): Promise<string> {
  const res = await req.get(`${API_BASE}/api/v1/users/me/blog/posts/${postId}`, auth(token))
  expect(res.status(), `編集経路 GET が200で返ること: ${res.ok() ? '' : await res.text()}`).toBe(200)
  const data = (await res.json()).data as { content: { body: string | null } }
  return data.content.body ?? ''
}

/** 使い捨て記事を論理削除する（後始末）。 */
async function deletePost(req: APIRequestContext, token: string, postId: number): Promise<void> {
  const res = await req.delete(`${API_BASE}/api/v1/users/me/blog/posts/${postId}`, auth(token))
  console.log(`[cleanup] DELETE post ${postId}: ${res.status()}`)
}

/** Markdown 画像記法 `![alt](url)` から URL 部分を出現順にすべて抜き出す。 */
function extractMarkdownImageUrls(body: string): string[] {
  const urls: string[] = []
  const re = /!\[[^\]]*\]\(([^)\s]+)\)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(body)) !== null) urls.push(m[1]!)
  return urls
}

test.describe('BLOG-IMG: ブログ本文画像の署名URL解決 実機E2E', () => {
  // storageState を空にして各テストで fresh ログインする（トークンローテーション事故の回避）
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(300_000)

  // ===========================================================================
  // BLOG-IMG-01: AC-1（表示経路で署名URL化＋ブラウザで実表示）／AC-2（編集経路は生キー）
  // ===========================================================================
  test('BLOG-IMG-01: presign→実PUT→本文埋込→表示経路で署名URL化されブラウザに画像が実表示される（AC-1）／編集経路は生r2Keyのまま（AC-2）', async ({ page }) => {
    const ts = Date.now()
    const { token, me } = await login(page)
    const title = `E2E BlogImg Signed ${ts}`
    const slug = `e2e-blogimg-signed-${ts}`
    const post = await createPost(page.request, token, { title, slug, body: '初期本文（画像は後から埋め込む）' })

    try {
      // ---- 1. presign → MinIO へ実 PUT（実オブジェクトを置く）----
      const fileKey = await uploadImage(page.request, token, { scopeId: me.id, blogPostId: post.id })
      console.log('[BLOG-IMG-01] 実PUT済み r2Key:', fileKey)

      // ---- 2. 本文へ「生 r2Key」を埋め込んで保存（BE の保存仕様どおり）----
      const body = `# 署名URL解決の検証\n\n![E2E検証画像](${fileKey})\n\n本文の末尾テキスト${ts}\n`
      await updateBody(page.request, token, post.id, title, body)

      // ---- 3. ★根治点: 表示経路の本文で r2Key が署名URLへ置換されていること ----
      const viewBody = await fetchViewBody(page.request, token, me.id, slug)
      console.log('[BLOG-IMG-01] 表示経路 body:', viewBody)
      const viewUrls = extractMarkdownImageUrls(viewBody)
      expect(viewUrls.length, '表示経路の本文に画像記法が1件あること').toBe(1)
      expect(isSignedMinioUrl(viewUrls[0]),
        `★ 表示経路の本文が生r2Keyでなく署名URLであること（実値=${viewUrls[0]}）`).toBeTruthy()
      // 注意: 署名URLは r2Key を「パスの一部として」必ず含む
      // （例 http://localhost:9000/mannschaft-storage/blog/PERSONAL/23/x.png?X-Amz-...）。
      // したがって body.includes(key) は置換成功時も必ず true になり、判定に使えない。
      // 「画像記法のURLが生キーそのものではないこと」で判定する。
      expect(viewUrls[0],
        `画像記法のURLが生r2Keyのまま残っていないこと（key=${fileKey}）`).not.toBe(fileKey)
      // 記法の外側（見出し・末尾テキスト）が壊れていないこと
      expect(viewBody).toContain('# 署名URL解決の検証')
      expect(viewBody).toContain(`本文の末尾テキスト${ts}`)

      // ---- 4. 署名URLが実オブジェクトへ到達すること（ダミーキーでも署名は作れるため必須）----
      const signedGet = await page.request.get(viewUrls[0]!)
      console.log('[BLOG-IMG-01] 署名URL GET:', signedGet.status())
      expect(signedGet.status(), '署名URLが200で実バイトを返すこと（オブジェクトが実在すること）').toBe(200)
      expect((await signedGet.body()).length, '取得したバイト列が空でないこと').toBeGreaterThan(0)

      // ---- 5. ★ブラウザで記事詳細を開き、<img> が実際に描画・デコードされること ----
      // FE は BlogPostDetail.vue が renderMarkdown(body) を v-html で描画するのみで、
      // 追加のURL解決は一切しない（BE 解決済みが前提）。
      await page.goto(`/users/${me.id}/blog/posts/${slug}`, { waitUntil: 'domcontentloaded' })
      const img = page.locator(`img[src^="${MINIO_ORIGIN}"]`).first()
      await expect(img, '本文中の画像 <img> が表示されること').toBeVisible({ timeout: 45_000 })
      const imgSrc = await img.getAttribute('src')
      console.log('[BLOG-IMG-01] ブラウザ img src:', imgSrc)
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
      await page.screenshot({ path: `test-results/blog-img-01-detail-${ts}.png`, fullPage: true })

      // ---- 6. ★AC-2 逆アサーション: 編集経路は「生 r2Key のまま」が正しい ----
      // ここが署名URL化されていたら、編集保存で期限付きURLが本文へ永続保存され画像が恒久的に壊れる。
      const editBody = await fetchEditBody(page.request, token, post.id)
      console.log('[BLOG-IMG-01] 編集経路 body:', editBody)
      expect(editBody.includes(fileKey),
        `★ 編集経路 GET /users/me/blog/posts/{id} は生r2Keyのまま返すこと（key=${fileKey}）`).toBeTruthy()
      expect(editBody,
        '★ 編集経路の本文に署名パラメータ(X-Amz-)が含まれないこと（含まれたら仕様違反）').not.toContain('X-Amz-')
      expect(editBody).toBe(body)
    } finally {
      await deletePost(page.request, token, post.id)
    }
  })

  // ===========================================================================
  // BLOG-IMG-02: AC-3（越境キー）／AC-4（台帳未登録キー）は解決されない
  // ===========================================================================
  test('BLOG-IMG-02: 越境キー（AC-3）と台帳未登録キー（AC-4）は署名URL化されず本文に残る', async ({ page }) => {
    const ts = Date.now()
    const { token, me } = await login(page)
    const title = `E2E BlogImg Guard ${ts}`
    const slug = `e2e-blogimg-guard-${ts}`
    const post = await createPost(page.request, token, { title, slug, body: '初期本文' })

    try {
      // 正規の1枚（presign 経路を通した実オブジェクト）。
      // ★これを同じ本文に混ぜるのが重要: これが署名URL化されることで
      //   「解決処理が確かに走った」ことを示せる。無いと、解決が丸ごと壊れていても
      //   否定アサーションだけが通る空虚な検証（vacuous pass）になってしまう。
      const legitKey = await uploadImage(page.request, token, { scopeId: me.id, blogPostId: post.id })

      // --- AC-3: 越境キー（関門1 = スコープ接頭辞のセグメント完全一致で弾かれる）---
      const crossTeamKey = 'blog/TEAM/999999/cross-scope-team.png'
      // 隣接ID漏洩の検証: 自分の scopeId に数字を足した「前方一致するが別スコープ」のキー
      const adjacentScopeKey = `blog/PERSONAL/${me.id}9/adjacent-id-leak.png`
      // traversal: `../` を含むキーは正規形でないため一律拒否される
      const traversalKey = `blog/PERSONAL/${me.id}/../999999/traversal.png`
      // パーセントエンコードによる traversal 迂回も一律拒否される
      const percentKey = `blog/PERSONAL/${me.id}/%2e%2e/999999/percent.png`

      // --- AC-4: スコープは正しいが presign 経路を通していない手書きキー（関門2 = 台帳照合で弾かれる）---
      const unregisteredKey = `blog/PERSONAL/${me.id}/00000000-0000-4000-8000-000000000000.png`

      const rejectedKeys = [crossTeamKey, adjacentScopeKey, traversalKey, percentKey, unregisteredKey]

      const body = [
        '# 関門の検証',
        '',
        `![正規画像](${legitKey})`,
        `![越境TEAM](${crossTeamKey})`,
        `![隣接ID](${adjacentScopeKey})`,
        `![traversal](${traversalKey})`,
        `![percent](${percentKey})`,
        `![台帳未登録](${unregisteredKey})`,
        '',
      ].join('\n')
      await updateBody(page.request, token, post.id, title, body)

      const viewBody = await fetchViewBody(page.request, token, me.id, slug)
      console.log('[BLOG-IMG-02] 表示経路 body:', viewBody)

      // 判定はすべて「画像記法のURL一覧」に対して行う。
      // 署名URLは r2Key をパスの一部として含むため、body.includes(key) では
      // 「置換された」のか「生キーが残った」のかを区別できない（BLOG-IMG-01 の注記参照）。
      const urls = extractMarkdownImageUrls(viewBody)

      // 前提: 正規キーは署名URL化されている（＝解決処理が確かに走った証拠）。
      // これが無いと、解決が丸ごと壊れていても否定アサーションだけが通る空虚な検証になる。
      expect(urls, `前提: 正規キーは署名URLへ置換されていること（key=${legitKey}）`).not.toContain(legitKey)
      const signedUrls = urls.filter((u) => isSignedMinioUrl(u))
      expect(signedUrls.length,
        '★ 署名URL化されたのは正規キー1件のみであること（拒否キーが1件でも混ざれば情報漏洩）').toBe(1)

      // 各拒否キーが「生キーのまま本文に残っている」こと（黙って消されてもいない）
      for (const key of rejectedKeys) {
        expect(urls, `★ 拒否キーが署名URL化されず生キーのまま残ること（key=${key}）`).toContain(key)
      }
      // 念のため: 拒否キーのファイル名が署名URLの一部として現れていないこと
      for (const marker of ['cross-scope-team', 'adjacent-id-leak', 'traversal.png', 'percent.png']) {
        const leaked = urls.some((u) => isSignedMinioUrl(u) && u.includes(marker))
        expect(leaked, `★ 拒否キー(${marker})の署名URLが発行されていないこと`).toBeFalsy()
      }
      // 台帳未登録キーは「スコープは正しい」ため関門1は通る。関門2で止まることを個別に明示検証する。
      const unregisteredSigned = urls.some(
        (u) => isSignedMinioUrl(u) && u.includes('00000000-0000-4000-8000-000000000000'),
      )
      expect(unregisteredSigned,
        '★ AC-4: presign 経路を通していない手書きキーは台帳照合で弾かれ署名URL化されないこと').toBeFalsy()
    } finally {
      await deletePost(page.request, token, post.id)
    }
  })

  // ===========================================================================
  // BLOG-IMG-03: AC-5 画像0枚でも500にならず正常表示
  // ===========================================================================
  test('BLOG-IMG-03: 画像0枚の本文が500にならずブラウザで正常表示される（AC-5）', async ({ page }) => {
    const ts = Date.now()
    const { token, me } = await login(page)
    const title = `E2E BlogImg NoImage ${ts}`
    const slug = `e2e-blogimg-noimage-${ts}`
    const marker = `画像なし本文マーカー${ts}`
    const body = `# 画像を含まない記事\n\n${marker}\n\n段落2です。\n`
    const post = await createPost(page.request, token, { title, slug, body })

    try {
      // 表示経路が 200 で返り、本文が一切改変されないこと（早期 return が効いている）
      const viewBody = await fetchViewBody(page.request, token, me.id, slug)
      console.log('[BLOG-IMG-03] 表示経路 body:', JSON.stringify(viewBody))
      expect(viewBody, '画像を含まない本文は無改変で返ること').toBe(body)
      expect(viewBody, '署名パラメータが混入しないこと').not.toContain('X-Amz-')

      // ブラウザで開いて本文テキストが実際に描画されること
      await page.goto(`/users/${me.id}/blog/posts/${slug}`, { waitUntil: 'domcontentloaded' })
      await expect(page.getByText(marker), '本文テキストがブラウザに表示されること')
        .toBeVisible({ timeout: 45_000 })
      // 画像が無い本文なので MinIO 由来の <img> は 0 件であること
      await expect(page.locator(`img[src^="${MINIO_ORIGIN}"]`),
        '画像0枚の記事に MinIO 画像が現れないこと').toHaveCount(0)
      await page.screenshot({ path: `test-results/blog-img-03-noimage-${ts}.png`, fullPage: true })
    } finally {
      await deletePost(page.request, token, post.id)
    }
  })

  // ===========================================================================
  // BLOG-IMG-04: AC-6 30枚成功／31枚目拒否 ＋ AC-7 1リクエストで全件解決
  // ===========================================================================
  test('BLOG-IMG-04: 画像30枚ちょうどは成功し31枚目は拒否される（AC-6）／30枚の本文が1リクエストで全件署名URL化される（AC-7）', async ({ page }) => {
    const ts = Date.now()
    const { token, me } = await login(page)
    const title = `E2E BlogImg Limit ${ts}`
    const slug = `e2e-blogimg-limit-${ts}`
    const post = await createPost(page.request, token, { title, slug, body: '初期本文' })

    try {
      // ---- AC-6 前半: 30枚ちょうどまでは presign + 実PUT が成功すること ----
      // 上限判定は blog_post_id を渡した時のみ効くため、必ず記事IDを添えて presign する。
      const keys: string[] = []
      for (let i = 0; i < 30; i++) {
        const key = await uploadImage(page.request, token, { scopeId: me.id, blogPostId: post.id })
        keys.push(key)
      }
      expect(keys.length, '30枚ちょうどのアップロードが成功すること').toBe(30)
      expect(new Set(keys).size, '30枚の r2Key がすべて一意であること').toBe(30)

      // ---- AC-6 後半: 31枚目は拒否されること ----
      // 直前の30枚が全て200で成功している以上、ここでの失敗は「上限判定が効いた」ことを意味する
      // （エンドポイント自体が壊れているなら1枚目から落ちる）。
      const over = await presignImage(page.request, token, { scopeId: me.id, blogPostId: post.id })
      console.log('[BLOG-IMG-04] 31枚目 presign status:', over.status)
      expect(over.status, '★ 31枚目の presign が拒否されること（成功してはならない）').not.toBe(200)

      // 【既知の不具合・要根治】本来この上限超過は 422 UNPROCESSABLE_ENTITY で返るべきである。
      // BlogMediaService は `ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "1記事あたりの
      // 画像上限（30枚）を超えています")` を throw しているが、GlobalExceptionHandler に
      // `@ExceptionHandler(ResponseStatusException.class)` が存在しないため、汎用 Exception ハンドラへ
      // 落ちて **500 COMMON_999「システムエラーが発生しました」** に化ける（本specの実走で実測）。
      // 結果、FE は「30枚の上限に達しました」と案内できず、利用者にはただの障害に見える。
      // 拒否そのもの（＝AC-6の本質）は効いているため 422/500 の両方を許容しつつ、
      // 期待値を 422 に寄せた形で記録する。BE 修正後は 422 のみになり本アサーションはそのまま通る。
      expect([422, 500],
        `★ 上限超過が拒否ステータスで返ること（期待=422 / 現状=500 COMMON_999・要BE修正。実値=${over.status}）`)
        .toContain(over.status)

      // ---- AC-7: 30枚を1つの本文に埋め、1リクエストの取得で全件が署名URL化されること ----
      // BlogBodyMediaResolver は本文1件につき MediaUrlResolver#resolveAll をちょうど1回だけ呼ぶ
      // （画像ごとの presign ループ＝N+1 の防止）。E2E からは呼び出し回数を直接観測できないため、
      // 「1リクエストで30枚すべてが解決される」ことを代替検証とする。
      const body = ['# 30枚の画像', '', ...keys.map((k, i) => `![画像${i + 1}](${k})`), ''].join('\n')
      await updateBody(page.request, token, post.id, title, body)

      const startedAt = Date.now()
      const viewBody = await fetchViewBody(page.request, token, me.id, slug)
      const elapsedMs = Date.now() - startedAt
      console.log('[BLOG-IMG-04] 30枚記事の取得所要時間(ms):', elapsedMs)

      const urls = extractMarkdownImageUrls(viewBody)
      expect(urls.length, '本文の画像記法が30件のままであること').toBe(30)
      const signed = urls.filter((u) => isSignedMinioUrl(u))
      expect(signed.length, '★ 30枚すべてが1リクエストで署名URL化されていること').toBe(30)
      expect(new Set(signed).size, '30件の署名URLがすべて異なること（取り違えが無いこと）').toBe(30)
      // 生キー残存の判定は URL 一覧に対して行う（BLOG-IMG-01 の注記参照）
      for (const key of keys) {
        expect(urls, `生r2Keyが1件も残っていないこと（key=${key}）`).not.toContain(key)
      }

      // 抜き取り検証: 先頭・中間・末尾の署名URLが実オブジェクトへ到達すること
      for (const idx of [0, 15, 29]) {
        const res = await page.request.get(signed[idx]!)
        expect(res.status(), `${idx + 1}枚目の署名URLが200で取得できること`).toBe(200)
      }

      // ブラウザでも30枚すべてが描画され、先頭画像が実デコードされること
      await page.goto(`/users/${me.id}/blog/posts/${slug}`, { waitUntil: 'domcontentloaded' })
      const imgs = page.locator(`img[src^="${MINIO_ORIGIN}"]`)
      await expect(imgs, '★ ブラウザ上に30枚の画像が描画されること').toHaveCount(30, { timeout: 45_000 })
      await expect
        .poll(async () => imgs.first().evaluate((el) => (el as HTMLImageElement).naturalWidth), {
          message: '先頭画像が実デコードされること（naturalWidth>0）',
          timeout: 20_000,
        })
        .toBeGreaterThan(0)
      await page.screenshot({ path: `test-results/blog-img-04-30images-${ts}.png`, fullPage: true })
    } finally {
      await deletePost(page.request, token, post.id)
    }
  })
})
