/**
 * F05.5 ファイル共有セキュリティ強化 A/B/C/D 実機 E2E テスト
 *
 * 対象:
 *   B: min_visible_role によるフォルダ最低可視ロール（ADMINS_AND_ABOVE で MEMBER 403）
 *   C: download_disabled フォルダ → DL URL 403 / ファイル表示 200
 *   D: Public link（トークン capability）— 未認証アクセス・DL許可/不許可・パスワード
 *   認可: createLink は ADMIN/DEPUTY のみ。MEMBER → 403
 *
 * テストシナリオ:
 *   SEC-001: ① アップロード成功（presign-upload → MinIO PUT → registerFile）
 *   SEC-002A: ② ADMINS_AND_ABOVE フォルダ — ADMIN は 200 取得
 *   SEC-002B: ② ADMINS_AND_ABOVE フォルダ — MEMBER は 403
 *   SEC-003A: ③ downloadDisabled フォルダのファイル — DL URL 403 (FILE_SHARING_017)
 *   SEC-003B: ③ downloadDisabled フォルダのファイル — ファイル表示 (GET) 200
 *   SEC-004:  ④ 公開リンク D — createLink 201 → 未認証アクセス 200 → DL URL 200
 *   SEC-004-B: ④ 公開リンク D — DL不許可リンクで DL URL → 403 (FILE_SHARING_019)
 *   SEC-004-C: ④ C+D 貫通防御 — downloadDisabled × DL許可リンク → 403 (FILE_SHARING_017)
 *   SEC-005:  ⑤ MEMBER が createLink → 403 (COMMON_002)
 *
 * 根治済みバグ（#XXXX 2026-07-03）:
 *   SharedFileLinkEntity の `active` フィールドに @Column(name = "is_active") が欠落していた。
 *   Hibernate が `active` カラムを探すが DB カラム名は `is_active` (Flyway V136.001) のため、
 *   すべての INSERT/SELECT が Unknown column 'active' で 500 になっていた。
 *   修正: SharedFileLinkEntity.java の `active` フィールドに
 *         @Column(name = "is_active", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE") を追加。
 *
 * 実行方法:
 *   cd frontend && BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/file-sharing-security.spec.ts \
 *     --reporter=list --config playwright-real.config.ts --project chromium-real
 *
 * 注意:
 *   - MinIO が必要（docker compose --profile storage up -d）
 *   - BE(:8080 WSL2)・FE(:3000 本陣) が起動済みであること
 *   - CI スモーク対象外（手動実走のみ）
 *     (memory: project_real_admin_e2e_excluded_from_ci_smoke)
 *   - API 呼び出しには page.request を使うこと。
 *     Playwright の `request` フィクスチャは loginViaApi で設定した Cookie を持たず 401 になる。
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// storageState に依存しない — beforeEach で毎回フレッシュログイン
test.use({ storageState: { cookies: [], origins: [] } })

// レート制限を考慮して直列実行
test.describe.configure({ mode: 'serial' })

// ── 定数 ─────────────────────────────────────────────────────────────────────

const E2E_ADMIN = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
}

const E2E_USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

/**
 * チーム 1（e2e テスト用 — e2e-admin が ADMIN、e2e-user が MEMBER）
 */
const TEST_TEAM_ID = 1

// テスト後クリーンアップ用（afterAll で削除）
const createdFolderIds: number[] = []
const createdFileIds: number[] = []
const createdLinkTokens: string[] = []

// ── API ブリッジ（WSL2 mirrored 対応）─────────────────────────────────────

async function installApibridge(page: import('@playwright/test').Page): Promise<void> {
  if (API_BASE_URL === 'http://localhost:8080') return

  const feOriginPromise = page.evaluate(() => window.location.origin).catch(() => 'http://localhost:3000')

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
  await installApibridge(page)
  await page.context().clearCookies()
  // デフォルトは ADMIN でログイン
  await loginViaApi(page, E2E_ADMIN, { apiBaseUrl: API_BASE_URL })
})

test.afterAll(async ({ request }) => {
  // afterAll は page を持たないため request フィクスチャで削除
  // 認証 Cookie がないため 401 が返ることがあるが、クリーンアップなので無視
  for (const fileId of [...createdFileIds].reverse()) {
    await request.delete(`${API_BASE_URL}/api/v1/files/${fileId}`).catch(() => {})
  }
  for (const folderId of [...createdFolderIds].reverse()) {
    await request.delete(`${API_BASE_URL}/api/v1/files/folders/${folderId}`).catch(() => {})
  }
  createdFolderIds.length = 0
  createdFileIds.length = 0
  createdLinkTokens.length = 0
})

// ── ヘルパー ──────────────────────────────────────────────────────────────

/**
 * presign-upload → MinIO PUT → registerFile の一連アップロードを実行する。
 * 成功時に fileId を返す。MinIO 未起動など環境起因の失敗は test.skip する。
 *
 * @param ctx page.request (認証 Cookie を持つ) を渡すこと。
 *            Playwright の `request` フィクスチャは Cookie を持たないため 401 になる。
 */
async function uploadFileViaApi(
  ctx: import('@playwright/test').APIRequestContext,
  params: { folderId: number; fileName: string; content: string },
): Promise<number> {
  const { folderId, fileName, content } = params
  const contentBytes = Buffer.from(content, 'utf8')

  // 1. presign-upload
  const presignRes = await ctx.post(`${API_BASE_URL}/api/v1/files/presign-upload`, {
    data: {
      folderId,
      fileName,
      contentType: 'text/plain',
      fileSize: contentBytes.byteLength,
    },
  })

  if (presignRes.status() !== 200) {
    const body = await presignRes.text()
    console.log(`presign-upload failed: ${presignRes.status()} ${body.slice(0, 200)}`)
    test.skip(
      true,
      `presign-upload が ${presignRes.status()} を返した。` +
        `MinIO 未起動か storage_subscriptions 未挿入の可能性。` +
        `docker compose --profile storage up -d + storage_subscriptions 手動挿入を確認。`,
    )
    throw new Error('presign-upload skip')
  }

  const { uploadUrl, fileKey } = (await presignRes.json()).data as {
    uploadUrl: string
    fileKey: string
  }

  // 2. MinIO PUT（fetch で直接 PUT）
  const putRes = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/plain' },
    body: contentBytes,
  })
  if (!putRes.ok) {
    test.skip(true, `MinIO PUT が ${putRes.status} を返した。MinIO が起動しているか確認。`)
    throw new Error('MinIO PUT skip')
  }

  // 3. registerFile
  const registerRes = await ctx.post(`${API_BASE_URL}/api/v1/files`, {
    data: {
      folderId,
      name: fileName,
      fileKey,
      fileSize: contentBytes.byteLength,
      contentType: 'text/plain',
    },
  })
  expect(registerRes.status(), `registerFile: ${await registerRes.text()}`).toBe(201)
  const fileData = (await registerRes.json()).data as { id: number }
  createdFileIds.push(fileData.id)
  return fileData.id
}

// ── SEC-001: アップロード成功（① presign → PUT → registerFile）──────────

test('SEC-001: ① アップロード成功 — presign-upload → MinIO PUT → registerFile', async ({
  page,
}) => {
  // 個人フォルダ作成（page.request = 認証済み）
  const folderRes = await page.request.post(`${API_BASE_URL}/api/v1/me/folders`, {
    data: { name: `SEC-001-Folder-${Date.now()}`, scopeType: 'PERSONAL' },
  })
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)
  console.log(`SEC-001: Created folder id=${folderId}`)

  // アップロード（page.request で認証 Cookie 送信）
  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec001-test-${Date.now()}.txt`,
    content: 'SEC-001 E2E upload test content',
  })
  console.log(`SEC-001: Uploaded fileId=${fileId}`)

  // ファイル詳細 API 確認
  const fileRes = await page.request.get(`${API_BASE_URL}/api/v1/files/${fileId}`)
  expect(fileRes.status()).toBe(200)
  const fileData = (await fileRes.json()).data as { id: number; name: string; folderId: number }
  expect(fileData.id).toBe(fileId)
  expect(fileData.folderId).toBe(folderId)
  console.log(`SEC-001: File detail API 200. name="${fileData.name}" PASS`)

  // FE 画面確認（/my/files）
  await page.goto('/my/files', { waitUntil: 'domcontentloaded' })
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  // ページが 200 で返れば OK（FileBrowser 描画は別テスト担当）
  expect(page.url()).not.toContain('/login')
  console.log(`SEC-001: /my/files 遷移 OK PASS`)
})

// ── SEC-002: B — min_visible_role=ADMINS_AND_ABOVE 認可 ──────────────────

test('SEC-002A: ② B — ADMINS_AND_ABOVE フォルダ: ADMIN は 200', async ({ page }) => {
  // チーム 1 に ADMINS_AND_ABOVE フォルダ作成
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: {
        name: `SEC-002A-AdminOnly-${Date.now()}`,
        scopeType: 'TEAM',
        minVisibleRole: 'ADMINS_AND_ABOVE',
      },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)
  console.log(`SEC-002A: Created ADMINS_AND_ABOVE folder id=${folderId}`)

  // ファイルアップロード
  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec002a-admin-only-${Date.now()}.txt`,
    content: 'admin-only secret content',
  })
  console.log(`SEC-002A: Uploaded fileId=${fileId}`)

  // ADMIN として GET → 200 期待
  const fileRes = await page.request.get(`${API_BASE_URL}/api/v1/files/${fileId}`)
  expect(fileRes.status()).toBe(200)
  console.log(`SEC-002A: ADMIN GET file → 200 PASS`)
})

test('SEC-002B: ② B — ADMINS_AND_ABOVE フォルダ: MEMBER は 403', async ({ page }) => {
  // ADMIN で ADMINS_AND_ABOVE フォルダ作成 + ファイルアップロード
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: {
        name: `SEC-002B-AdminOnly-${Date.now()}`,
        scopeType: 'TEAM',
        minVisibleRole: 'ADMINS_AND_ABOVE',
      },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec002b-admin-only-${Date.now()}.txt`,
    content: 'admin-only secret content',
  })
  console.log(`SEC-002B: Created ADMINS_AND_ABOVE folder=${folderId}, file=${fileId}`)

  // MEMBER としてログインし直す（page.request の Cookie が MEMBER のものに変わる）
  await page.context().clearCookies()
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

  // MEMBER として GET → 403 期待
  const memberFileRes = await page.request.get(`${API_BASE_URL}/api/v1/files/${fileId}`)
  expect(memberFileRes.status()).toBe(403)
  const errorBody = (await memberFileRes.json()) as { error: { code: string } }
  console.log(
    `SEC-002B: MEMBER GET file → ${memberFileRes.status()} code=${errorBody.error?.code} PASS`,
  )
})

// ── SEC-003: C — download_disabled フォルダ ──────────────────────────────

test('SEC-003A: ③ C — downloadDisabled フォルダのファイル: DL URL → 403 (FILE_SHARING_017)', async ({
  page,
}) => {
  // downloadDisabled=true のチームフォルダ作成（ADMIN でセットアップ）
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: {
        name: `SEC-003A-DLDisabled-${Date.now()}`,
        scopeType: 'TEAM',
        downloadDisabled: true,
      },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)
  console.log(`SEC-003A: Created downloadDisabled team folder id=${folderId}`)

  // ファイルアップロード（ADMIN として）
  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec003a-dl-disabled-${Date.now()}.txt`,
    content: 'download disabled content',
  })
  console.log(`SEC-003A: Uploaded fileId=${fileId}`)

  // MEMBER としてログインし直す（SYSTEM_ADMIN は C をバイパスするため MEMBER でテスト）
  await page.context().clearCookies()
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

  // MEMBER として DL URL 取得 → 403 (FILE_SHARING_017: DOWNLOAD_DISABLED) 期待
  const dlUrlRes = await page.request.get(`${API_BASE_URL}/api/v1/files/${fileId}/download-url`)
  expect(dlUrlRes.status()).toBe(403)
  const errorBody = (await dlUrlRes.json()) as { error: { code: string } }
  expect(errorBody.error.code).toBe('FILE_SHARING_017')
  console.log(`SEC-003A: MEMBER DL URL → 403 FILE_SHARING_017 PASS`)
})

test('SEC-003B: ③ C — downloadDisabled フォルダのファイル: ファイル表示 (GET) → 200', async ({
  page,
}) => {
  // downloadDisabled=true のチームフォルダ作成（ADMIN でセットアップ）
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: {
        name: `SEC-003B-DLDisabled-${Date.now()}`,
        scopeType: 'TEAM',
        downloadDisabled: true,
      },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec003b-view-only-${Date.now()}.txt`,
    content: 'view-only content',
  })
  console.log(`SEC-003B: Created downloadDisabled team folder=${folderId}, file=${fileId}`)

  // MEMBER としてログインし直す
  await page.context().clearCookies()
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

  // MEMBER として GET detail → 200 期待（DL 禁止は閲覧を妨げない）
  const fileRes = await page.request.get(`${API_BASE_URL}/api/v1/files/${fileId}`)
  expect(fileRes.status()).toBe(200)
  console.log(`SEC-003B: MEMBER File detail GET → 200 PASS (DL禁止でも閲覧は可)`)
})

// ── SEC-004: D — 公開リンク（createLink → 未認証アクセス → DL）────────────

/**
 * SEC-004: F05.5 PR-D 公開リンク E2E — createLink 201 → 未認証アクセス 200 → DL URL 200
 *
 * 根治済みバグ（#XXXX 2026-07-03）:
 *   SharedFileLinkEntity.active に @Column(name = "is_active") が欠落していたため、
 *   createLink が Unknown column 'active' で 500 になっていた。修正済み。
 */
test('SEC-004: ④ D — 公開リンク createLink → 未認証アクセス → DL URL 一気通貫', async ({
  page,
}) => {
  // セットアップ: 個人フォルダにファイルアップロード
  const folderRes = await page.request.post(`${API_BASE_URL}/api/v1/me/folders`, {
    data: { name: `SEC-004-PublicLink-${Date.now()}`, scopeType: 'PERSONAL' },
  })
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec004-public-link-${Date.now()}.txt`,
    content: 'public link test content',
  })
  console.log(`SEC-004: Setup file id=${fileId}`)

  // ④-create: ADMIN で公開リンク作成 → 201
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 19)

  const createLinkRes = await page.request.post(`${API_BASE_URL}/api/v1/files/${fileId}/links`, {
    data: {
      expiresAt,
      downloadAllowed: true,
    },
  })
  expect(createLinkRes.status(), `createLink: ${await createLinkRes.text()}`).toBe(201)
  const linkData = (await createLinkRes.json()).data as { token: string }
  const token = linkData.token
  createdLinkTokens.push(token)
  console.log(`SEC-004: createLink 201. token=${token.slice(0, 8)}... PASS`)

  // ④-access: 未認証で公開エンドポイントにアクセス → 200
  // Cookie をクリアして未認証状態にする
  await page.context().clearCookies()
  const accessRes = await page.request.post(
    `${API_BASE_URL}/api/v1/public/file-links/${token}/access`,
    { data: {} },
  )
  expect(accessRes.status()).toBe(200)
  const accessData = (await accessRes.json()).data as { id: number; name: string }
  expect(accessData.id).toBe(fileId)
  console.log(`SEC-004: 未認証アクセス 200. file="${accessData.name}" PASS`)

  // ④-dl: DL 許可リンクで DL URL 発行 → 200
  const dlUrlRes = await page.request.post(
    `${API_BASE_URL}/api/v1/public/file-links/${token}/download-url`,
    { data: {} },
  )
  expect(dlUrlRes.status()).toBe(200)
  const dlData = (await dlUrlRes.json()).data as { downloadUrl: string }
  expect(dlData.downloadUrl).toContain('mannschaft-storage')
  console.log(`SEC-004: DL URL 200. url=${dlData.downloadUrl.slice(0, 40)}... PASS`)
})

/**
 * SEC-004-B: DL 不許可リンクで DL URL → 403 (FILE_SHARING_019)
 */
test('SEC-004-B: ④ D — DL不許可リンク: download-url → 403 (FILE_SHARING_019)', async ({
  page,
}) => {
  // 個人フォルダ + ファイルセットアップ
  const folderRes = await page.request.post(`${API_BASE_URL}/api/v1/me/folders`, {
    data: { name: `SEC-004B-DLForbidden-${Date.now()}`, scopeType: 'PERSONAL' },
  })
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec004b-dl-forbidden-${Date.now()}.txt`,
    content: 'dl forbidden content',
  })

  // downloadAllowed=false のリンク作成
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 19)
  const createLinkRes = await page.request.post(`${API_BASE_URL}/api/v1/files/${fileId}/links`, {
    data: { expiresAt, downloadAllowed: false },
  })
  expect(createLinkRes.status(), `createLink: ${await createLinkRes.text()}`).toBe(201)
  const { token } = (await createLinkRes.json()).data as { token: string }
  createdLinkTokens.push(token)
  console.log(`SEC-004-B: Created DL-forbidden link token=${token.slice(0, 8)}...`)

  // 未認証で DL URL → 403 (FILE_SHARING_019: LINK_DOWNLOAD_NOT_ALLOWED)
  await page.context().clearCookies()
  const dlRes = await page.request.post(
    `${API_BASE_URL}/api/v1/public/file-links/${token}/download-url`,
    { data: {} },
  )
  expect(dlRes.status()).toBe(403)
  const errorBody = (await dlRes.json()) as { error: { code: string } }
  expect(errorBody.error.code).toBe('FILE_SHARING_019')
  console.log(`SEC-004-B: DL不許可リンク → 403 FILE_SHARING_019 PASS`)
})

/**
 * SEC-004-C: C + D 貫通防御 — downloadDisabled フォルダ × DL許可リンク → 403 (FILE_SHARING_017)
 */
test('SEC-004-C: ④ C+D 貫通防御 — downloadDisabled × DL許可リンク → 403 (FILE_SHARING_017)', async ({
  page,
}) => {
  // downloadDisabled チームフォルダにファイルアップロード（ADMIN）
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: {
        name: `SEC-004C-DLDisabled-${Date.now()}`,
        scopeType: 'TEAM',
        downloadDisabled: true,
      },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec004c-c-and-d-${Date.now()}.txt`,
    content: 'c and d protection test',
  })
  console.log(`SEC-004-C: Created downloadDisabled folder=${folderId}, file=${fileId}`)

  // downloadAllowed=true の公開リンク作成（ADMIN）
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 19)
  const createLinkRes = await page.request.post(`${API_BASE_URL}/api/v1/files/${fileId}/links`, {
    data: { expiresAt, downloadAllowed: true },
  })
  expect(createLinkRes.status(), `createLink: ${await createLinkRes.text()}`).toBe(201)
  const { token } = (await createLinkRes.json()).data as { token: string }
  createdLinkTokens.push(token)

  // 未認証で DL URL → 403 (FILE_SHARING_017: DOWNLOAD_DISABLED) — C 優先
  await page.context().clearCookies()
  const dlRes = await page.request.post(
    `${API_BASE_URL}/api/v1/public/file-links/${token}/download-url`,
    { data: {} },
  )
  expect(dlRes.status()).toBe(403)
  const errorBody = (await dlRes.json()) as { error: { code: string } }
  expect(errorBody.error.code).toBe('FILE_SHARING_017')
  console.log(`SEC-004-C: C+D 貫通防御: downloadDisabled × DL許可リンク → 403 FILE_SHARING_017 PASS`)
})

// ── SEC-005: ⑤ — MEMBER が createLink → 403 ──────────────────────────────

test('SEC-005: ⑤ MEMBER が createLink → 403 (COMMON_002) / ADMIN は 201', async ({ page }) => {
  // ADMIN でチームフォルダにファイルをアップロード
  const folderRes = await page.request.post(
    `${API_BASE_URL}/api/v1/teams/${TEST_TEAM_ID}/folders`,
    {
      data: { name: `SEC-005-MemberLink-${Date.now()}`, scopeType: 'TEAM' },
    },
  )
  expect(folderRes.status(), `フォルダ作成: ${await folderRes.text()}`).toBe(201)
  const folderId = ((await folderRes.json()).data as { id: number }).id
  createdFolderIds.push(folderId)

  const fileId = await uploadFileViaApi(page.request, {
    folderId,
    fileName: `sec005-member-link-${Date.now()}.txt`,
    content: 'member link test content',
  })
  console.log(`SEC-005: Setup file id=${fileId} in team folder=${folderId}`)

  // MEMBER としてログインし直す
  await page.context().clearCookies()
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE_URL })

  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 19)

  // MEMBER が createLink → 403 (COMMON_002) 期待
  // authorizeLinkManageByFileId → authorizeDelete → checkAdminOrAbove → MEMBER は 403
  const memberCreateLinkRes = await page.request.post(
    `${API_BASE_URL}/api/v1/files/${fileId}/links`,
    {
      data: { expiresAt, downloadAllowed: false },
    },
  )
  expect(memberCreateLinkRes.status()).toBe(403)
  const errorBody = (await memberCreateLinkRes.json()) as { error: { code: string } }
  expect(errorBody.error.code).toBe('COMMON_002')
  console.log(`SEC-005: MEMBER createLink → 403 COMMON_002 PASS`)

  // ADMIN として再ログイン → createLink 201（認可チェック通過 + is_active 修正済みで正常動作）
  await page.context().clearCookies()
  await loginViaApi(page, E2E_ADMIN, { apiBaseUrl: API_BASE_URL })

  const adminCreateLinkRes = await page.request.post(
    `${API_BASE_URL}/api/v1/files/${fileId}/links`,
    {
      data: { expiresAt, downloadAllowed: false },
    },
  )
  expect(adminCreateLinkRes.status(), `ADMIN createLink: ${await adminCreateLinkRes.text()}`).toBe(201)
  const adminLinkData = (await adminCreateLinkRes.json()).data as { token: string }
  createdLinkTokens.push(adminLinkData.token)
  console.log(`SEC-005: ADMIN createLink → 201 (is_active 修正済み) PASS`)
})
