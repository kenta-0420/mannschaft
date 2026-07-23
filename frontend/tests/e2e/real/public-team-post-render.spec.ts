/**
 * 課題 #2456 実機 E2E: 公開チーム投稿詳細ページの陽性描画検証。
 *
 * ── 背景 ────────────────────────────────────────────────────
 * #2425（親子ルート地雷の index.vue 兄弟化）で
 * `/public/teams/[slug]/posts/[postId]/index.vue` のルート解決自体は
 * 確認済みだが、「200 かつ実際に投稿本文が描画される」陽性ケースは未踏だった。
 * 本 spec はこの陽性描画のみを対象にする（404 でないことだけでなく、
 * タイトル・本文テキストが実際に DOM に現れることまで確認する）。
 *
 * 対象ページ: frontend/app/pages/public/teams/[slug]/posts/[postId].vue
 * 対象 API  : GET /api/v1/public/teams/{teamId}/posts/{postId}
 *             （backend/.../publicview/controller/PublicTeamPostController.java）
 * 設計書    : docs/features/F19.1_public_pages_identity_disclosure.md §4.3 / §9.1
 *
 * ── 注意: URL の `[slug]` は実体が数値チーム ID ──────────────────
 * PublicTeamPostController#getPublicPostDetail は `@PathVariable Long teamId` を
 * 受け取る。フロントの動的ルートパラメータ名は `[slug]` だが実際には
 * チームの数値 ID をそのまま渡す設計（slug 文字列ではない）。
 *
 * ── 実データ準備（seed）────────────────────────────────────────
 * このテストはモックを使わない実機テストのため、beforeAll で
 * 実際に PUBLIC 可視性のチームを新規作成し、そのチームに
 * visibility=PUBLIC / status=PUBLISHED のブログ記事を 1 件投稿してから
 * 未認証ブラウザで公開ページへアクセスする
 * （memory: feedback_authz_e2e_seed_membership_pollution に従い、
 *   既存 seed チームを汚さない使い捨てチーム方式を採用）。
 *
 * 実行方法:
 *   cd frontend
 *   BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *     node node_modules/@playwright/test/cli.js test tests/e2e/real/public-team-post-render.spec.ts \
 *     --project=chromium-real --workers=1 --reporter=list
 */

import { test, expect } from '@playwright/test'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// 全テストを未認証状態で実行する（公開ページは未ログインで閲覧できることが要件）
test.use({ storageState: { cookies: [], origins: [] } })

const UNIQUE_SUFFIX = Date.now()
const POST_TITLE = `F19.1公開投稿描画検証タイトル${UNIQUE_SUFFIX}`
const POST_BODY_MARKER = `F19.1公開チーム投稿詳細ページの陽性描画E2E検証マーカー本文${UNIQUE_SUFFIX}`

let publicTeamId: number
let publicPostId: number

/**
 * API ログインして Bearer アクセストークンを取得する。
 * このヘルパーは Node（サーバーサイド）から直接 BE を叩くための seed 専用で、
 * ブラウザコンテキストとは独立している（Playwright request fixture 不要）。
 */
async function loginBearer(email: string, password: string): Promise<string> {
  const res = await fetch(`${API_BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) {
    throw new Error(`seed用ログイン失敗: ${res.status} ${await res.text()}`)
  }
  const json = await res.json()
  return json.data.accessToken as string
}

test.beforeAll(async () => {
  const token = await loginBearer(USER_EMAIL, USER_PASSWORD)
  const authHeaders = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  }

  // 1. 使い捨ての PUBLIC チームを新規作成
  const teamRes = await fetch(`${API_BASE}/api/v1/teams`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({
      name: `E2E公開投稿検証チーム${UNIQUE_SUFFIX}`,
      visibility: 'PUBLIC',
    }),
  })
  if (!teamRes.ok) {
    throw new Error(`seed用チーム作成失敗: ${teamRes.status} ${await teamRes.text()}`)
  }
  const teamJson = await teamRes.json()
  publicTeamId = teamJson.data.numericId as number
  if (!Number.isFinite(publicTeamId)) {
    throw new Error(`チーム作成レスポンスに numericId が含まれていません: ${JSON.stringify(teamJson)}`)
  }

  // 2. そのチームに visibility=PUBLIC のブログ記事を作成
  const postRes = await fetch(`${API_BASE}/api/v1/blog/posts`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({
      teamId: String(publicTeamId),
      title: POST_TITLE,
      body: POST_BODY_MARKER,
      visibility: 'PUBLIC',
      postType: 'BLOG',
    }),
  })
  if (!postRes.ok) {
    throw new Error(`seed用投稿作成失敗: ${postRes.status} ${await postRes.text()}`)
  }
  const postJson = await postRes.json()
  publicPostId = postJson.data.id as number

  // 3. PUBLISHED へステータス変更（DRAFT のままでは公開 API から見えない）
  const publishRes = await fetch(`${API_BASE}/api/v1/blog/posts/${publicPostId}/publish`, {
    method: 'PATCH',
    headers: authHeaders,
    body: JSON.stringify({ status: 'PUBLISHED' }),
  })
  if (!publishRes.ok) {
    throw new Error(`seed用投稿公開失敗: ${publishRes.status} ${await publishRes.text()}`)
  }

  // 4. 公開 API（未認証相当）で 200 + 本文が返ることを事前検証（ブラウザ検証の土台裏取り）
  const publicRes = await fetch(`${API_BASE}/api/v1/public/teams/${publicTeamId}/posts/${publicPostId}`)
  if (publicRes.status !== 200) {
    throw new Error(
      `公開投稿APIが200を返しません（team=${publicTeamId}, post=${publicPostId}）: ${publicRes.status}`,
    )
  }
  const publicJson = await publicRes.json()
  if (!String(publicJson.bodyHtml ?? '').includes(POST_BODY_MARKER)) {
    throw new Error(`公開投稿APIのbodyHtmlにマーカー本文が含まれていません: ${JSON.stringify(publicJson)}`)
  }
})

test.describe('#2456: 公開チーム投稿詳細ページ 陽性描画', () => {
  test('PUBLIC チーム × PUBLISHED 記事が未認証で 200 描画される', async ({ page }) => {
    const response = await page.goto(`${BASE_URL}/public/teams/${publicTeamId}/posts/${publicPostId}`)

    // 200（404 リダイレクトではない）であること
    expect(response?.status()).toBe(200)

    // タイトルが見出しとして実描画されること
    await expect(page.getByRole('heading', { name: POST_TITLE, level: 1 })).toBeVisible({
      timeout: 15_000,
    })

    // 本文（サニタイズ済み HTML）に投入したマーカーテキストが実描画されること
    await expect(page.getByTestId('public-post-body')).toContainText(POST_BODY_MARKER, {
      timeout: 5_000,
    })

    // 404 / Not Found ページに落ちていないことの追加裏取り
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).not.toMatch(/404|見つかりません|Not Found/i)

    await page.screenshot({
      path: `test-results/public-post-2456-${publicTeamId}-${publicPostId}.png`,
      fullPage: true,
    })
  })
})
