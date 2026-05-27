/**
 * 掲示板 保管庫（アーカイブ）フォルダ機能の実機 E2E（設計書 F05.1 §4/§5）。
 *
 * このテストは API モックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用します。
 * 未生成の場合は loginIfNeeded() でフォールバックログインします。
 *
 * テストユーザー:
 *   - e2e-user@test.mannschaft.local / TestPass2026!  (一般ユーザー = TEAM 1 の MEMBER)
 *   - e2e-admin@test.mannschaft.local / TestPass2026! (管理者     = TEAM 1 の ADMIN)
 * 実機テストチーム: FC東京U-18（テスト）(id=1)
 *
 * 保管庫機能は掲示板ドメインの memberships 認可ガード配下にあるため、
 * 各 describe の beforeAll で「バックエンド起動 + テストユーザーが当該スコープの掲示板にアクセス可能か」を
 * プローブし、満たさない場合は対処療法的に握りつぶさず test.skip() で正直にスキップする。
 *
 * テストケース:
 *   ARCHIVE-001: スレッドを保管庫へ（通常一覧から消え、保管庫の「未分類」に出現）
 *   ARCHIVE-002: フォルダ作成（名前/色/アイコン）→ ツリーに表示
 *   ARCHIVE-003: 未分類スレッドをフォルダへ振り分け → threadCount 反映
 *   ARCHIVE-004: ネストフォルダ作成（depth 0→1→…）と深さ上限超過エラー
 *   ARCHIVE-005: フォルダ削除 → 配下スレッド未分類退避・子フォルダ繰り上げ
 *   ARCHIVE-006: アーカイブ解除 → スレッドが通常一覧に戻る
 *   ARCHIVE-007: 権限出し分け（MEMBER は作成/振り分け/「保管庫へ」不可・閲覧可）
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
const BACKEND_URL = 'http://localhost:8080'
const FRONTEND_URL = 'http://localhost:3000'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const TEAM_ID = 1
const SCOPE = 'teams'
const ARCHIVE_BASE = `${BACKEND_URL}/api/v1/${SCOPE}/${TEAM_ID}/bulletin/archive`
const THREADS_BASE = `${BACKEND_URL}/api/v1/${SCOPE}/${TEAM_ID}/bulletin/threads`

// ---------------------------------------------------------------------------
// 型（最小限）
// ---------------------------------------------------------------------------
interface ThreadDto {
  id: number
  title: string
  isArchived: boolean
  archiveFolderId: string | null
}
interface FolderNode {
  id: string
  parentId: string | null
  name: string
  color: string | null
  icon: string | null
  depth: number
  childCount: number
  threadCount: number
  children: FolderNode[]
}
interface FolderTree {
  data: FolderNode[]
  meta: { unfiledThreadCount: number; totalFolderCount: number; maxDepth: number; maxFolderCount: number }
}

// ---------------------------------------------------------------------------
// ヘルパー: 環境チェック
// ---------------------------------------------------------------------------
async function isBackendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    const body = await res.json()
    return body.status === 'UP'
  } catch {
    return false
  }
}

async function isFrontendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(FRONTEND_URL, { timeout: 5_000 })
    return res.status() < 600
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: 認証
// ---------------------------------------------------------------------------
async function getAuthToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
      data: { email, password },
      headers: { 'Content-Type': 'application/json' },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data?.accessToken ?? null
  } catch {
    return null
  }
}

function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

/**
 * テストユーザーが当該スコープの掲示板保管庫を閲覧できるか（= memberships 認可を通過するか）をプローブする。
 * 掲示板は memberships テーブル基盤の認可ガード配下にあるため、
 * 種データに memberships 行が無い環境では 403 になる。その場合は正直にスキップする。
 */
async function canAccessArchive(request: APIRequestContext, token: string): Promise<boolean> {
  try {
    const res = await request.get(`${ARCHIVE_BASE}/folders`, { headers: authHeaders(token) })
    return res.ok()
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: ログイン（storageState フォールバック）
// ---------------------------------------------------------------------------
async function loginIfNeeded(page: Page, email = E2E_USER.email, password = E2E_USER.password): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)
  if (!page.url().includes('/login')) {
    return
  }
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL(/.*\/my\/.*|.*\/dashboard.*/, { timeout: 30_000 })
}

// ---------------------------------------------------------------------------
// ヘルパー: 掲示板スレッド / 保管庫フォルダ API
// ---------------------------------------------------------------------------
async function createThread(request: APIRequestContext, token: string, title: string): Promise<ThreadDto | null> {
  try {
    // priority は省略するとサーバ側で INFO 既定。Priority enum は INFO/NOTICE/IMPORTANT/URGENT。
    const res = await request.post(THREADS_BASE, {
      headers: authHeaders(token),
      data: { title, body: 'E2Eテスト用スレッド本文' },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data ?? null
  } catch {
    return null
  }
}

/** スレッドのアーカイブ状態を変更する（is_archived true=保管庫へ / false=解除）。任意で振り分け先フォルダ指定。 */
async function setArchived(
  request: APIRequestContext,
  token: string,
  threadId: number,
  isArchived: boolean,
  archiveFolderId?: string | null,
): Promise<ThreadDto | null> {
  try {
    const data: Record<string, unknown> = { isArchived }
    if (archiveFolderId !== undefined) data.archiveFolderId = archiveFolderId
    const res = await request.post(`${THREADS_BASE}/${threadId}/archive`, {
      headers: authHeaders(token),
      data,
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data ?? null
  } catch {
    return null
  }
}

async function getFolderTree(request: APIRequestContext, token: string): Promise<FolderTree | null> {
  try {
    const res = await request.get(`${ARCHIVE_BASE}/folders`, { headers: authHeaders(token) })
    if (!res.ok()) return null
    return (await res.json()) as FolderTree
  } catch {
    return null
  }
}

async function createFolder(
  request: APIRequestContext,
  token: string,
  payload: { name: string; parentFolderId?: string; color?: string; icon?: string },
): Promise<{ status: number; body: { data?: FolderNode; error?: { code: string } } }> {
  const res = await request.post(`${ARCHIVE_BASE}/folders`, {
    headers: authHeaders(token),
    data: payload,
  })
  return { status: res.status(), body: await res.json().catch(() => ({})) }
}

async function deleteFolder(
  request: APIRequestContext,
  token: string,
  folderId: string,
): Promise<{ status: number; data?: { movedThreadCount: number; promotedFolderCount: number; message: string } }> {
  const res = await request.delete(`${ARCHIVE_BASE}/folders/${folderId}`, { headers: authHeaders(token) })
  const body = await res.json().catch(() => ({}))
  return { status: res.status(), data: body?.data }
}

async function moveThreadToFolder(
  request: APIRequestContext,
  token: string,
  threadId: number,
  folderId: string | null,
): Promise<{ status: number; data?: ThreadDto }> {
  const res = await request.patch(`${ARCHIVE_BASE}/threads/${threadId}/folder`, {
    headers: authHeaders(token),
    data: { archiveFolderId: folderId },
  })
  const body = await res.json().catch(() => ({}))
  return { status: res.status(), data: body?.data }
}

/** 保管庫スレッド一覧（folder_id 省略=未分類 / 'all'=全件 / UUID=指定フォルダ）。 */
async function listArchiveThreads(
  request: APIRequestContext,
  token: string,
  folderId?: string,
): Promise<ThreadDto[]> {
  try {
    const q = folderId ? `?folder_id=${folderId}` : ''
    const res = await request.get(`${ARCHIVE_BASE}/threads${q}`, { headers: authHeaders(token) })
    if (!res.ok()) return []
    const body = await res.json()
    return (body?.data ?? []) as ThreadDto[]
  } catch {
    return []
  }
}

/** 通常スレッド一覧（保管庫済みは含まれない想定）。 */
async function listNormalThreadIds(request: APIRequestContext, token: string): Promise<number[]> {
  try {
    const res = await request.get(`${THREADS_BASE}?size=100`, { headers: authHeaders(token) })
    if (!res.ok()) return []
    const body = await res.json()
    const items = (body?.data ?? []) as ThreadDto[]
    return items.map((t) => t.id)
  } catch {
    return []
  }
}

/** 指定フォルダの threadCount をツリーから探す。 */
function findFolder(tree: FolderTree | null, folderId: string): FolderNode | null {
  if (!tree) return null
  const walk = (nodes: FolderNode[]): FolderNode | null => {
    for (const n of nodes) {
      if (n.id === folderId) return n
      const hit = walk(n.children ?? [])
      if (hit) return hit
    }
    return null
  }
  return walk(tree.data)
}

// ---------------------------------------------------------------------------
// ARCHIVE-001〜006: 管理者（ADMIN）による保管庫フォルダ機能の全ライフサイクル
// ---------------------------------------------------------------------------
test.describe('ARCHIVE-001〜006: 保管庫フォルダ機能（管理者）', () => {
  test.describe.configure({ mode: 'serial' })

  let adminToken: string | null = null
  let backendAlive = false
  let frontendAlive = false
  let archiveAccessible = false

  // 作成した一時リソース（afterAll でクリーンアップ）
  const createdFolderIds: string[] = []
  const createdThreadIds: number[] = []

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)
    if (!backendAlive) {
      console.warn('バックエンド未起動のため保管庫テストをスキップします')
      return
    }
    adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    if (!adminToken) {
      console.warn('e2e-admin ログイン失敗')
      return
    }
    archiveAccessible = await canAccessArchive(request, adminToken)
    if (!archiveAccessible) {
      console.warn(
        'e2e-admin が掲示板保管庫にアクセスできません（COMMON_002）。'
        + 'この環境の種データに TEAM 1 の memberships 行が無い可能性があります。'
        + 'CI 環境では memberships がシードされるため通過します。',
      )
    }
  })

  test.afterAll(async ({ request }) => {
    if (!backendAlive || !adminToken) return
    // スレッドのアーカイブ解除（通常一覧へ戻す）+ 物理削除
    for (const id of createdThreadIds) {
      await setArchived(request, adminToken, id, false).catch(() => null)
      await request.delete(`${THREADS_BASE}/${id}`, { headers: authHeaders(adminToken) }).catch(() => null)
    }
    // 残ったフォルダを削除（子から繰り上げられても重複削除は 404 で無害）
    for (const fid of createdFolderIds) {
      await deleteFolder(request, adminToken, fid).catch(() => null)
    }
  })

  function ensureReady() {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
    }
    if (!adminToken) {
      test.skip(true, 'e2e-admin ログイン失敗のためスキップ')
    }
    if (!archiveAccessible) {
      test.skip(true, '掲示板保管庫にアクセス不可（memberships 種データ不足の可能性）のためスキップ')
    }
  }

  test('ARCHIVE-001: スレッドを保管庫へ送ると通常一覧から消え、保管庫の未分類に出現する', async ({ request }) => {
    ensureReady()

    const thread = await createThread(request, adminToken!, `ARCHIVE-001 ${Date.now()}`)
    expect(thread, 'スレッド作成に失敗').not.toBeNull()
    createdThreadIds.push(thread!.id)

    // 作成直後は通常一覧に存在する
    const normalBefore = await listNormalThreadIds(request, adminToken!)
    expect(normalBefore).toContain(thread!.id)

    // 保管庫へ（is_archived=true, フォルダ未指定 = 未分類）
    const archived = await setArchived(request, adminToken!, thread!.id, true)
    expect(archived, 'アーカイブに失敗').not.toBeNull()
    expect(archived!.isArchived).toBe(true)
    expect(archived!.archiveFolderId).toBeNull()

    // 通常一覧から消える
    const normalAfter = await listNormalThreadIds(request, adminToken!)
    expect(normalAfter).not.toContain(thread!.id)

    // 保管庫の未分類（folder_id 省略）に出現する
    const unfiled = await listArchiveThreads(request, adminToken!)
    expect(unfiled.map((t) => t.id)).toContain(thread!.id)
  })

  test('ARCHIVE-002: フォルダを作成（名前/色/アイコン）するとツリーに表示される', async ({ request }) => {
    ensureReady()

    const name = `重要連絡 ${Date.now()}`
    const { status, body } = await createFolder(request, adminToken!, {
      name,
      color: '#FF5733',
      icon: 'pi-star',
    })
    expect(status, `フォルダ作成失敗: ${JSON.stringify(body)}`).toBe(201)
    expect(body.data).toBeTruthy()
    const folderId = body.data!.id
    createdFolderIds.push(folderId)
    expect(body.data!.name).toBe(name)
    expect(body.data!.color).toBe('#FF5733')
    expect(body.data!.icon).toBe('pi-star')
    expect(body.data!.depth).toBe(0)

    // ツリーに反映される
    const tree = await getFolderTree(request, adminToken!)
    const found = findFolder(tree, folderId)
    expect(found, 'ツリーに作成フォルダが見つからない').not.toBeNull()
    expect(found!.name).toBe(name)
  })

  test('ARCHIVE-003: 未分類スレッドをフォルダへ振り分けると threadCount に反映される', async ({ request }) => {
    ensureReady()

    // 振り分け先フォルダを作成
    const { status: fs, body: fb } = await createFolder(request, adminToken!, { name: `振り分け先 ${Date.now()}` })
    expect(fs).toBe(201)
    const folderId = fb.data!.id
    createdFolderIds.push(folderId)
    expect(fb.data!.threadCount).toBe(0)

    // アーカイブ済み（未分類）スレッドを用意
    const thread = await createThread(request, adminToken!, `ARCHIVE-003 ${Date.now()}`)
    expect(thread).not.toBeNull()
    createdThreadIds.push(thread!.id)
    await setArchived(request, adminToken!, thread!.id, true)

    // フォルダへ振り分け
    const move = await moveThreadToFolder(request, adminToken!, thread!.id, folderId)
    expect(move.status, 'フォルダ振り分け失敗').toBe(200)
    expect(move.data!.archiveFolderId).toBe(folderId)

    // 当該フォルダの threadCount が 1 になる
    const tree = await getFolderTree(request, adminToken!)
    const found = findFolder(tree, folderId)
    expect(found!.threadCount).toBe(1)

    // 当該フォルダのスレッド一覧に出現する
    const inFolder = await listArchiveThreads(request, adminToken!, folderId)
    expect(inFolder.map((t) => t.id)).toContain(thread!.id)
  })

  test('ARCHIVE-004: ネストフォルダ作成（depth 0→1→…）と深さ上限超過でエラーになる', async ({ request }) => {
    ensureReady()

    // depth 0..4（計5階層 = 上限）まで連鎖作成
    let parentId: string | undefined
    const depthChain: string[] = []
    for (let depth = 0; depth <= 4; depth++) {
      const { status, body } = await createFolder(request, adminToken!, {
        name: `nest-${depth}-${Date.now()}`,
        parentFolderId: parentId,
      })
      expect(status, `depth=${depth} の作成に失敗: ${JSON.stringify(body)}`).toBe(201)
      expect(body.data!.depth).toBe(depth)
      parentId = body.data!.id
      depthChain.push(body.data!.id)
    }
    // 先頭ルートだけ登録すれば afterAll の削除で子も繰り上げ削除されるが、念のため全 ID 登録
    createdFolderIds.push(...depthChain)

    // depth 5（上限 4 超過）はエラー（400 + BULLETIN_017 = ARCHIVE_FOLDER_DEPTH_EXCEEDED）
    const { status, body } = await createFolder(request, adminToken!, {
      name: `nest-5-overflow-${Date.now()}`,
      parentFolderId: parentId,
    })
    expect(status, '深さ上限超過なのに作成できてしまった').toBe(400)
    expect(body.error?.code).toBe('BULLETIN_017')
  })

  test('ARCHIVE-005: フォルダ削除で配下スレッドが未分類へ退避し子フォルダが繰り上がる', async ({ request }) => {
    ensureReady()

    // 親フォルダ + 子フォルダ
    const { body: pb } = await createFolder(request, adminToken!, { name: `del-parent-${Date.now()}` })
    const parentId = pb.data!.id
    const { body: cb } = await createFolder(request, adminToken!, {
      name: `del-child-${Date.now()}`,
      parentFolderId: parentId,
    })
    const childId = cb.data!.id
    // 子は繰り上げで残るので登録、親は削除で消える
    createdFolderIds.push(childId)

    // 親フォルダ直下にアーカイブ済みスレッドを1件配置
    const thread = await createThread(request, adminToken!, `ARCHIVE-005 ${Date.now()}`)
    createdThreadIds.push(thread!.id)
    await setArchived(request, adminToken!, thread!.id, true, parentId)

    // 親フォルダ削除
    const del = await deleteFolder(request, adminToken!, parentId)
    expect(del.status, 'フォルダ削除失敗').toBe(200)
    // 退避結果メッセージ + 件数
    expect(del.data!.movedThreadCount).toBe(1)
    expect(del.data!.promotedFolderCount).toBe(1)
    expect(del.data!.message).toContain('スレッド')

    // 配下スレッドは未分類（archiveFolderId=null・isArchived 維持）へ退避
    const unfiled = await listArchiveThreads(request, adminToken!)
    const moved = unfiled.find((t) => t.id === thread!.id)
    expect(moved, 'スレッドが未分類へ退避していない').toBeTruthy()
    expect(moved!.archiveFolderId).toBeNull()

    // 子フォルダはルートへ繰り上げ（depth 0）
    const tree = await getFolderTree(request, adminToken!)
    const promoted = findFolder(tree, childId)
    expect(promoted, '子フォルダが消えている（繰り上げ失敗）').not.toBeNull()
    expect(promoted!.depth).toBe(0)
  })

  test('ARCHIVE-006: アーカイブ解除でスレッドが通常一覧に戻る', async ({ request }) => {
    ensureReady()

    const thread = await createThread(request, adminToken!, `ARCHIVE-006 ${Date.now()}`)
    createdThreadIds.push(thread!.id)
    await setArchived(request, adminToken!, thread!.id, true)

    // アーカイブ済みであることを確認
    const unfiled = await listArchiveThreads(request, adminToken!)
    expect(unfiled.map((t) => t.id)).toContain(thread!.id)

    // 解除
    const restored = await setArchived(request, adminToken!, thread!.id, false)
    expect(restored, 'アーカイブ解除失敗').not.toBeNull()
    expect(restored!.isArchived).toBe(false)

    // 通常一覧へ復帰
    const normal = await listNormalThreadIds(request, adminToken!)
    expect(normal).toContain(thread!.id)

    // 保管庫からは消える
    const unfiledAfter = await listArchiveThreads(request, adminToken!)
    expect(unfiledAfter.map((t) => t.id)).not.toContain(thread!.id)
  })

  test('ARCHIVE-006b: 保管庫タブ UI が表示され、未分類/フォルダ作成導線が見える（管理者）', async ({ page, request }) => {
    ensureReady()
    if (!frontendAlive) {
      test.skip(true, 'フロントエンド未起動のためスキップ')
    }

    await loginIfNeeded(page, E2E_ADMIN.email, E2E_ADMIN.password)
    await page.goto(`/teams/${TEAM_ID}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 「保管庫」タブをクリック
    const archiveTab = page.getByRole('button', { name: /保管庫/ })
    await expect(archiveTab).toBeVisible({ timeout: 15_000 })
    await archiveTab.click()

    // 保管庫ビュー: 「フォルダ」見出し + 「未分類」 + 管理者向け「フォルダを作成」導線
    await expect(page.getByText('未分類').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button', { name: /フォルダを作成/ })).toBeVisible({ timeout: 10_000 })
  })
})

// ---------------------------------------------------------------------------
// ARCHIVE-007: 権限出し分け（MEMBER は管理操作不可・閲覧可）
// ---------------------------------------------------------------------------
test.describe('ARCHIVE-007: 保管庫フォルダ 権限出し分け（一般メンバー）', () => {
  test.describe.configure({ mode: 'serial' })

  let userToken: string | null = null
  let backendAlive = false
  let frontendAlive = false
  let archiveReadable = false

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)
    if (!backendAlive) return
    userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)
    if (!userToken) return
    // MEMBER は閲覧可（getFolderTree は checkMembership のみ）
    archiveReadable = await canAccessArchive(request, userToken)
    if (!archiveReadable) {
      console.warn(
        'e2e-user が掲示板保管庫を閲覧できません（COMMON_002）。'
        + 'この環境の種データに TEAM 1 の memberships 行が無い可能性があります。',
      )
    }
  })

  function ensureReady() {
    if (!backendAlive) test.skip(true, 'バックエンド未起動のためスキップ')
    if (!userToken) test.skip(true, 'e2e-user ログイン失敗のためスキップ')
    if (!archiveReadable) test.skip(true, '掲示板保管庫を閲覧不可（memberships 種データ不足の可能性）のためスキップ')
  }

  test('ARCHIVE-007a: MEMBER は保管庫フォルダを閲覧できる（API）', async ({ request }) => {
    ensureReady()
    const tree = await getFolderTree(request, userToken!)
    // 閲覧自体は成功する（ツリーが取得できる = data 配列を持つ）
    expect(tree, 'MEMBER がフォルダツリーを取得できない').not.toBeNull()
    expect(Array.isArray(tree!.data)).toBe(true)
  })

  test('ARCHIVE-007b: MEMBER はフォルダ作成できない（403）', async ({ request }) => {
    ensureReady()
    const { status, body } = await createFolder(request, userToken!, { name: `member-deny-${Date.now()}` })
    expect(status, 'MEMBER がフォルダを作成できてしまった').toBe(403)
    expect(body.error?.code).toBe('COMMON_002')
  })

  test('ARCHIVE-007c: MEMBER はスレッドのフォルダ振り分けができない（403）', async ({ request }) => {
    ensureReady()
    // 存在しなくても認可ガードが先に評価されるため 403 を期待（threadId は適当な値で可）
    const move = await moveThreadToFolder(request, userToken!, 999_999, null)
    expect(move.status, 'MEMBER が振り分けできてしまった').toBe(403)
  })

  test('ARCHIVE-007d: UI 上で MEMBER には「フォルダを作成」「保管庫へ」等の管理導線が出ない（閲覧は可）', async ({
    page,
  }) => {
    ensureReady()
    if (!frontendAlive) test.skip(true, 'フロントエンド未起動のためスキップ')

    await loginIfNeeded(page, E2E_USER.email, E2E_USER.password)
    await page.goto(`/teams/${TEAM_ID}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 保管庫タブへ切替（閲覧は可能）
    const archiveTab = page.getByRole('button', { name: /保管庫/ })
    await expect(archiveTab).toBeVisible({ timeout: 15_000 })
    await archiveTab.click()

    // 「未分類」など閲覧用 UI は見える
    await expect(page.getByText('未分類').first()).toBeVisible({ timeout: 15_000 })

    // 管理者専用ボタンは出ない（canManage=false で v-if 非表示）
    await expect(page.getByRole('button', { name: /フォルダを作成/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /フォルダへ移動/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /保管庫から戻す/ })).toHaveCount(0)
  })
})
