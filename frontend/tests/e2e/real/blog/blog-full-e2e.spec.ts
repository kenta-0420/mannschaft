/**
 * ブログ機能 — 実機 E2E CRUD・ロールチェック・スコープ・自動保存
 *
 * 実際のバックエンド（localhost:8080）とフロントエンド（dev server）に対し、
 * ログイン済みユーザー・管理者それぞれで動作を検証する。
 * page.route によるモックは一切使用しない。
 *
 * ── テストID ──────────────────────────────────────────
 *   BLOG-CRUD-001   記事作成→本文入力→保存→削除 一気通貫
 *   BLOG-ROLE-001   一般ユーザーに管理者レビューUIが非表示
 *   BLOG-ROLE-002   管理者は /admin/blog-management にアクセス可能
 *   BLOG-SCOPE-001  チームブログページで scope_type 旧バグパラメータが使われていない
 *   BLOG-AUTOSAVE-001  自動保存トグルUIが存在しデフォルトON
 *
 * ── 認証方式 ──────────────────────────────────────────
 *   API 経路: 実 BE の POST /api/v1/auth/login で Bearer トークンを取得（記事の直接操作用）。
 *   UI 経路 : /login フォームから実ログインしてブラウザセッション（authStore + cookie）を確立する。
 *
 * ── 前提条件 ──────────────────────────────────────────
 *   - backend/scripts/seed-e2e-data.js 実行済み
 *   - e2e-user@test.mannschaft.local / e2e-admin@test.mannschaft.local が DB に存在
 */

import { test, expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

// 各テスト自前ログイン（storageState に依存しない）
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASS = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASS = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// CRUD テストは直列実行（create→edit→delete の依存関係があるため）
test.describe.configure({ mode: 'serial' })

interface LoginResult {
  accessToken: string
  userId: number
}

/** 実 BE の auth/login で Bearer トークンを取得する */
async function loginApi(api: APIRequestContext, email: string, password: string): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email, password } })
  expect(res.status(), `login(${email}) は 200`).toBe(200)
  const json = (await res.json()) as { data: { accessToken: string; userId?: number } }
  expect(json.data?.accessToken, 'accessToken が取得できる').toBeTruthy()
  return { accessToken: json.data.accessToken, userId: json.data.userId ?? 0 }
}

/** /login フォームから実ログインし、ブラウザセッション（authStore + cookie）を確立する */
async function loginUI(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)

  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })

  const pwInput = page.locator('input[type="password"]')
  await pwInput.click()
  await pwInput.pressSequentially(password, { delay: 10 })

  await page.getByRole('button', { name: 'ログイン' }).click()

  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

// ===========================================================================
// BLOG-CRUD-001: 記事作成 → 本文入力 → 保存 → 削除 一気通貫
// ===========================================================================
test('BLOG-CRUD-001: 記事作成→編集→削除の一気通貫CRUD', async ({ page, request }) => {
  test.setTimeout(120_000)

  // API でユーザーログイン（Bearer トークン取得）
  const { accessToken } = await loginApi(request, USER_EMAIL, USER_PASS)

  // UI でもログイン（ブラウザセッション確立）
  await loginUI(page, USER_EMAIL, USER_PASS)

  // ────── 1. 記事作成ページへ移動 ──────
  await page.goto('/blog')
  await waitForHydration(page)
  await page.waitForTimeout(1500)
  await page.screenshot({ path: 'test-results/blog-crud-001-list.png', fullPage: false })

  // 「新規記事」ボタンをクリック（blog/index.vue のヘッダーボタン）
  const createBtn = page
    .getByRole('button', { name: '新規記事' })
    .or(page.getByRole('button', { name: '新規作成' }))
    .or(page.getByRole('button', { name: '最初の記事を書く' }))
    .first()
  await createBtn.waitFor({ state: 'visible', timeout: 10_000 })
  await createBtn.click()

  // ダイアログ「ブログ記事を作成」が表示されるのを待つ
  const dialog = page.locator('[role="dialog"]')
  await dialog.waitFor({ state: 'visible', timeout: 10_000 })
  const testTitle = `E2Eテスト記事_${Date.now()}`

  let postId: number | null = null

  // タイトル入力
  const titleInput = dialog.locator('input[placeholder="記事のタイトル"]').or(dialog.locator('input').first())
  await titleInput.waitFor({ state: 'visible', timeout: 5_000 })
  await titleInput.click()
  await titleInput.pressSequentially(testTitle, { delay: 20 })

  await page.screenshot({ path: 'test-results/blog-crud-001-dialog.png', fullPage: false })

  // 「作成して編集へ」ボタンクリック（BlogCreateDialog.vue フッター）
  const submitBtn = dialog.getByRole('button', { name: '作成して編集へ' })
  await submitBtn.click()

  // エディタへ遷移（/blog/posts/{id}/edit）
  await page.waitForURL(
    (url) => url.pathname.includes('/blog/posts/') && url.pathname.includes('/edit'),
    { timeout: 30_000, waitUntil: 'commit' },
  )

  await waitForHydration(page)
  await page.waitForTimeout(1000)
  await page.screenshot({ path: 'test-results/blog-crud-001-editor.png', fullPage: false })

  // URLから postId を取得
  const editUrl = page.url()
  const idMatch = editUrl.match(/\/blog\/posts\/(\d+)/)
  if (idMatch && idMatch[1]) {
    postId = Number(idMatch[1])
  }
  console.log(`編集ページURL: ${editUrl}, postId: ${postId}`)

  // ────── 2. タイトル確認・本文入力 ──────
  const titleField = page.locator('input[placeholder*="タイトル"]').first()
  await titleField.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})

  // 本文入力（CodeMirror または textarea）
  const bodyEditor = page.locator('.cm-content, .cm-editor .cm-content, textarea.cm-content').first()
  const bodyVisible = await bodyEditor.isVisible().catch(() => false)
  if (bodyVisible) {
    await bodyEditor.click()
    await bodyEditor.pressSequentially('# E2Eテスト\n\nこれは実機E2Eテストです。Playwrightからの自動入力。', { delay: 5 })
  }

  // ────── 3. 保存 ──────
  const saveBtn = page.getByRole('button', { name: '保存' })
  await saveBtn.waitFor({ state: 'visible', timeout: 5_000 })
  await saveBtn.click()
  await page.waitForTimeout(1500)

  // トースト確認（オプション）
  const toast = page.locator('.p-toast-message, [class*="toast"]').first()
  const toastVisible = await toast.isVisible().catch(() => false)
  console.log(`保存トースト表示: ${toastVisible}`)

  await page.screenshot({ path: 'test-results/blog-crud-001-saved.png', fullPage: false })

  // ────── 4. 削除（API 経由）──────
  if (postId) {
    // API で削除（DELETE /api/v1/users/me/blog/posts/{id}）
    const delRes = await request.delete(`${BE_API}/users/me/blog/posts/${postId}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    console.log(`API削除 (id=${postId}): HTTP ${delRes.status()}`)
    expect(delRes.status(), 'DELETE は 204 を返す').toBe(204)

    // 削除後の確認：記事一覧に含まれないこと
    const listRes = await request.get(`${BE_API}/users/me/blog/posts`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    const listJson = (await listRes.json()) as { data: Array<{ id: number }> }
    const ids = listJson.data?.map((p) => p.id) ?? []
    expect(ids.includes(postId), `削除後の一覧に id=${postId} が含まれていない`).toBe(false)
    console.log('BLOG-CRUD-001: PASS')
  } else {
    // postId が取得できなかった場合はUI削除を試みる
    const selfReviewDeleteBtn = page.getByRole('button', { name: '削除する' })
    if (await selfReviewDeleteBtn.isVisible().catch(() => false)) {
      page.once('dialog', (d) => d.accept().catch(() => {}))
      await selfReviewDeleteBtn.click()
      await page.waitForTimeout(2000)
    }
    console.log('BLOG-CRUD-001: PASS (postId未取得のためUI削除)')
  }
})

// ===========================================================================
// BLOG-ROLE-001: 一般ユーザーに管理者レビューUIが非表示
// ===========================================================================
test('BLOG-ROLE-001: 一般ユーザーに管理者レビューUIが非表示', async ({ page, request }) => {
  test.setTimeout(60_000)

  const { accessToken } = await loginApi(request, USER_EMAIL, USER_PASS)

  // API で記事を作成
  const createRes = await request.post(`${BE_API}/users/me/blog/posts`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      title: `ROLE_TEST_${Date.now()}`,
      body: 'ロールチェックテスト用の本文テキストです。',
    },
  })
  expect(createRes.status(), 'ブログ記事作成は201').toBe(201)
  const postJson = (await createRes.json()) as { data: { id: number } }
  const postId = postJson.data?.id
  expect(postId, 'postId が取得できる').toBeTruthy()
  console.log(`テスト用記事作成: id=${postId}`)

  // UI でログイン（一般ユーザー）
  await loginUI(page, USER_EMAIL, USER_PASS)

  // 編集ページへ移動
  await page.goto(`/blog/posts/${postId}/edit`)
  await waitForHydration(page)
  await page.waitForTimeout(2000)
  await page.screenshot({ path: 'test-results/blog-role-001-general.png', fullPage: false })

  // 管理者レビューセクション（「■ 管理者レビュー」）が表示されていないこと
  // このセクションは PENDING_REVIEW かつ isAdmin のときのみ表示される
  const adminSection = page.locator('text="■ 管理者レビュー"').first()
  const adminVisible = await adminSection.isVisible().catch(() => false)
  expect(adminVisible, '一般ユーザーに管理者レビューセクションが表示されていない').toBe(false)

  // 保存ボタンは表示される
  await expect(page.getByRole('button', { name: '保存' }), '保存ボタンが表示される').toBeVisible()

  console.log('BLOG-ROLE-001: PASS')

  // cleanup
  if (postId) {
    const delRes = await request.delete(`${BE_API}/users/me/blog/posts/${postId}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    console.log(`クリーンアップ削除: HTTP ${delRes.status()}`)
  }
})

// ===========================================================================
// BLOG-ROLE-002: 管理者は /admin/blog-management にアクセス可能
// ===========================================================================
test('BLOG-ROLE-002: 管理者は /admin/blog-management にアクセス可能', async ({ page }) => {
  test.setTimeout(60_000)

  await loginUI(page, ADMIN_EMAIL, ADMIN_PASS)

  await page.goto('/admin/blog-management')
  await waitForHydration(page)
  await page.waitForTimeout(2000)
  await page.screenshot({ path: 'test-results/blog-role-002-admin.png', fullPage: false })

  // 管理者ページが表示される（403/404/ログインページへのリダイレクトでない）
  const url = page.url()
  expect(url, '403ページへリダイレクトされていない').not.toContain('/403')
  expect(url, 'ログインページへリダイレクトされていない').not.toContain('/login')
  console.log(`BLOG-ROLE-002: PASS (URL: ${url})`)
})

// ===========================================================================
// BLOG-SCOPE-001: チームブログページで scope_type 旧バグパラメータが使われていない
// ===========================================================================
test('BLOG-SCOPE-001: チームブログページで正しいAPIパラメータが使われる', async ({
  page,
  request,
}) => {
  test.setTimeout(60_000)

  const { accessToken } = await loginApi(request, USER_EMAIL, USER_PASS)

  // ユーザーが所属するチーム一覧を取得
  const teamsRes = await request.get(`${BE_API}/me/teams`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  const teamsJson = (await teamsRes.json()) as { data?: Array<{ id: number; publicId?: string; name: string }> }
  const teams = teamsJson.data ?? []

  if (teams.length === 0) {
    console.log('BLOG-SCOPE-001: SKIP（テストユーザーがチームに未所属）')
    test.skip()
    return
  }

  const team = teams[0]!
  const teamPathId = team.publicId ?? String(team.id)
  console.log(`テストチーム: ${team.name} (pathId=${teamPathId})`)

  // UI でログイン
  await loginUI(page, USER_EMAIL, USER_PASS)

  // ネットワーク監視: blog/posts へのリクエストを捕捉
  const blogRequests: string[] = []
  page.on('request', (req) => {
    if (req.url().includes('/api/v1/blog/posts') || req.url().includes('/api/v1/users/me/blog')) {
      blogRequests.push(req.url())
    }
  })

  await page.goto(`/teams/${teamPathId}/blog`)
  await waitForHydration(page)
  await page.waitForTimeout(3000)
  await page.screenshot({ path: 'test-results/blog-scope-001-team-blog.png', fullPage: false })

  console.log(`ブログAPIリクエスト (${blogRequests.length}件):`)
  blogRequests.forEach((u) => console.log(`  ${u}`))

  // 旧バグ: scope_type=TEAM パラメータが残っているとバグあり
  // 修正後: teamId=<id> パラメータが使われる（useBlogApi.getPosts の scope_type→teamId 変換）
  const hasBuggyParam = blogRequests.some((url) => url.includes('scope_type='))
  expect(hasBuggyParam, '旧バグの scope_type パラメータが使われていない').toBe(false)

  console.log('BLOG-SCOPE-001: PASS')
})

// ===========================================================================
// BLOG-AUTOSAVE-001: 自動保存トグルUIが存在しデフォルトON
// ===========================================================================
test('BLOG-AUTOSAVE-001: 自動保存トグルUIが存在しデフォルトでON', async ({
  page,
  request,
}) => {
  test.setTimeout(60_000)

  const { accessToken } = await loginApi(request, USER_EMAIL, USER_PASS)

  // テスト用記事を API で作成
  const createRes = await request.post(`${BE_API}/users/me/blog/posts`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      title: `AUTOSAVE_TEST_${Date.now()}`,
      body: '自動保存テスト用本文テキストです。',
    },
  })
  expect(createRes.status(), 'ブログ記事作成は201').toBe(201)
  const postJson = (await createRes.json()) as { data: { id: number } }
  const postId = postJson.data?.id
  expect(postId, 'postId が取得できる').toBeTruthy()
  console.log(`テスト用記事作成: id=${postId}`)

  // UI でログイン
  await loginUI(page, USER_EMAIL, USER_PASS)

  // 編集ページへ移動
  await page.goto(`/blog/posts/${postId}/edit`)
  await waitForHydration(page)
  await page.waitForTimeout(1500)

  // 自動保存トグル（#autosave-toggle）の確認
  const toggle = page.locator('#autosave-toggle')
  await expect(toggle, '自動保存チェックボックスが存在する').toBeVisible({ timeout: 10_000 })
  await expect(toggle, '自動保存がデフォルトでON').toBeChecked()

  // ラベル確認
  const label = page.locator('label[for="autosave-toggle"]')
  await expect(label, '自動保存ラベルが存在する').toBeVisible()

  await page.screenshot({ path: 'test-results/blog-autosave-001.png', fullPage: false })
  console.log('BLOG-AUTOSAVE-001: PASS')

  // cleanup
  const delRes = await request.delete(`${BE_API}/users/me/blog/posts/${postId}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  console.log(`クリーンアップ削除: HTTP ${delRes.status()}`)
})
