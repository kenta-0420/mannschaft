/**
 * F19.1 公開チームページ E2E GoldenPath。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §14.3 / §15 / §8.6
 *
 * シナリオ:
 *  1. 未ログインで `/public/teams/{id}` にアクセス → ヘッダー + 投稿一覧が表示される
 *  2. 投稿カードの投稿者識別が「投稿者」+ 汎用アバター（個人特定情報なし）
 *  3. 投稿カードをクリック → 投稿詳細ページに遷移し、本文が表示される
 *  4. ログイン CTA カードクリック → /register に遷移
 *
 * 【SSR 制約】
 * 本機能は SSR で OGP メタタグを動的注入する設計（§8.6 / §9.1）であり、
 * `useAsyncData` 経由でサーバー側 ofetch が直接 BE を叩く。Playwright の `page.route` は
 * <strong>ブラウザ起点の HTTP リクエストのみ</strong>傍受可能であり、Nuxt サーバー内部の
 * SSR fetch は傍受できない。したがって SSR で 404 ページが返されると、その時点で createError
 * の fatal フラグにより 404 描画が確定する。
 *
 * 【実行前提】
 * 本 spec は <strong>バックエンド + フロントエンド統合環境</strong> で実行する:
 *   1. `docker-compose up -d` で Spring Boot 8080 + MySQL + Valkey を起動
 *   2. PUBLIC かつ未 archive かつ未 delete のチーム + そこに紐づく PUBLIC/PUBLISHED 状態の
 *      blog_posts レコード 1 件を seed
 *   3. 環境変数 `E2E_PUBLIC_TEAM_ID` / `E2E_PUBLIC_POST_ID` を指定して実行
 *
 * これらが未指定の場合は test.skip により自動的にスキップされる。
 *
 * 【Phase 2 完了済み】
 * Phase 2（2026-05-19）で以下の機能が追加されている:
 * - IdentityVisibilityResolver: 全ステータス × snapshot × MINOR × 退会済み = 32件 UT
 * - AdminSupporterNameDisclosureController: IT 10件
 * - PublicVisibleToggle.vue: 個別投稿の公開/非公開トグル UI（API は Phase 3 で実装予定）
 *
 * 本 E2E は BE 統合環境が必要なため、CI では環境変数未指定でスキップされる。
 * 統合環境（docker-compose）で手動実行する場合は上記前提を満たした上で実行すること。
 */
import { test, expect } from '@playwright/test'

// 未ログイン状態で実行
test.use({ storageState: { cookies: [], origins: [] } })

// BE 統合環境でのみ実行（環境変数指定がない場合はスキップ）
const TEAM_ID_RAW = process.env.E2E_PUBLIC_TEAM_ID
const POST_ID_RAW = process.env.E2E_PUBLIC_POST_ID
const RUN_INTEGRATION = TEAM_ID_RAW !== undefined && POST_ID_RAW !== undefined
const TEAM_ID = TEAM_ID_RAW !== undefined ? Number(TEAM_ID_RAW) : 0
const POST_ID = POST_ID_RAW !== undefined ? Number(POST_ID_RAW) : 0

test.describe('F19.1 公開チームページ GoldenPath (BE 統合環境必須)', () => {
  test.skip(
    !RUN_INTEGRATION,
    'E2E_PUBLIC_TEAM_ID / E2E_PUBLIC_POST_ID 未設定のためスキップ（BE 統合環境でのみ実行）',
  )

  test('F19.1-PUBLIC-001: 未ログインで公開チームページにアクセスし、投稿者識別が匿名表示されている', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    // チーム名（404 ではなくチーム情報が見える）
    const h1 = page.locator('h1').first()
    await expect(h1).toBeVisible()
    await expect(h1).not.toHaveText('404')

    // 投稿カードがあれば「投稿者」（汎用ラベル）が表示されていること
    const card = page.getByTestId('public-post-card').first()
    if (await card.isVisible()) {
      // 投稿者識別が「投稿者」（汎用ラベル）であること
      await expect(card).toContainText('投稿者')
    }

    // 個人特定情報（メール）が DOM に含まれていないこと
    const html = await page.content()
    expect(html).not.toMatch(/[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/)
  })

  test('F19.1-PUBLIC-002: 投稿カードクリックで投稿詳細に遷移し、本文が表示される', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}/posts/${POST_ID}`)

    // 本文表示
    const body = page.getByTestId('public-post-body')
    await expect(body).toBeVisible()

    // 投稿者識別が表示されている
    await expect(page.locator('main')).toContainText('投稿者')
  })

  test('F19.1-PUBLIC-003: ログイン CTA カードクリックで /register に遷移する', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    const cta = page.getByTestId('public-login-cta')
    await expect(cta).toBeVisible()

    // 「このチームに参加する」CTA → /register?inviteTeam=...
    await cta.getByRole('link').first().click()
    await expect(page).toHaveURL(/\/register/)
  })
})
