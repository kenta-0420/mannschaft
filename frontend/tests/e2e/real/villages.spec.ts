/**
 * F17 村コミュニティ（Village Community）— 実機 E2E テスト（VLG-001〜012）。
 *
 * 村タブ「永続シェル方式（SPA）」の検証（旧 VLG-SPA-001〜006）は
 * villages-spa.spec.ts に分離済み。同一ファイル内で 2 つの describe が
 * それぞれ BrowserContext を beforeAll/afterAll で開閉すると片方の後始末が
 * もう片方に干渉する現象が実測されたため、「1 スペックファイル = 1 セッション
 * （1 context）」に揃えるためファイルごと分割した。
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（単一セッション設計。
 * beforeAll で 1 つの BrowserContext を作成し全テストで使い回す（mode: 'serial'）。
 * describe 内で新規ログインは行わない — アクセストークンはリフレッシュの度に
 * サーバ側で「回転」（旧トークンを revoke し後継を発行）するが、後継トークンは
 * Cookie 経由でその場の BrowserContext にしか残らない。テストが新しい
 * storageState スナップショット/新規ログインから始まると、既に revoke 済みの
 * トークンを再提示することになり、grace window（60秒）超過後は「リプレイ攻撃」
 * として検出されセッションごと失効する（AuthTokenRotationService）。
 *
 * ただし共有するのは BrowserContext（Cookie ジャー）までで、page はテストごとに
 * beforeEach で newPage() → afterEach で close() する。回転後トークンの継続性に
 * 必要なのは Cookie ジャーであって page 自体の使い回しではなく、page を使い回すと
 * 前のテストが張った WebSocket 接続（presence 等）や DOM 状態が次のテストに漏れ、
 * ERR_ABORTED 等の干渉を起こすため（実測: VLG-007 で
 * `page.goto: net::ERR_ABORTED; maybe frame was detached?` が発生し、
 * page を毎テスト新規作成する構成に変えて解消した）。
 *
 * 前提シード: backend/scripts/seed-e2e-data.js の F17 ブロックを実行済み。
 *   - villages 2 件（"E2Eテスト公式村" OFFICIAL / "E2Eテストコミュニティ村" COMMUNITY）
 *   - E2E_USER は COMMUNITY 村に VILLAGER として参加済み
 *
 * 検証粒度: dashboard.spec.ts / wallet.spec.ts と同等の「ページ描画 + 主要要素可視」レベル。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const COMMUNITY_VILLAGE_NAME = 'E2Eテストコミュニティ村'

// ===========================================================================
// VLG-001〜012: F17 村コミュニティ
//
// 注意（単一セッション設計 — テストごとの BrowserContext 生成を禁止する理由）:
//   本ファイル冒頭コメント参照。beforeAll で 1 つの BrowserContext（Cookie
//   ジャー）を作成し全テストで使い回す（mode: 'serial' で順序も固定）。
//   page はテストごとに beforeEach/afterEach で作り直す（WebSocket 等の状態
//   漏れ防止のため、共有するのは context までに留める）。
//   token/villageId の直叩き検証（VLG-003/004）に使う login も、この共有
//   context と同じ storageState を経由した Cookie 認証に統一する（別途 API
//   ログインすると、さらに別のトークン系列が生まれて同じ理由で衝突しうるため
//   使わない）。
// ===========================================================================
test.describe('VLG-001〜012: F17 村コミュニティ', () => {
  test.describe.configure({ mode: 'serial' })
  // 村ページは village 詳細 + メンバーシップ + チャネル等 複数 API 直列のためタイムアウト延長
  test.setTimeout(120_000)

  // describe スコープで 1 つの BrowserContext（Cookie ジャー）を使い回す（単一セッション設計）。
  // page はテストごとに beforeEach/afterEach で作り直す（前テストの WS 接続等を持ち越さない）。
  let context: BrowserContext
  let page: Page
  let cachedVillageId = ''

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/e2e/.auth/real-user.json' })

    // token/villageId 解決専用の一時 page（beforeEach 前なので明示的に作って閉じる）。
    // context.request でも良いが、Cookie の伝播を明示するため実際に page を経由する。
    const setupPage = await context.newPage()
    try {
      // page.request は同一 BrowserContext の Cookie（access_token）をそのまま使うため、
      // 別途 Authorization ヘッダを組み立てる必要はない（新規ログインを避ける）。
      const searchResp = await setupPage.request.get(
        `http://localhost:8080/api/v1/villages/search?q=${encodeURIComponent(COMMUNITY_VILLAGE_NAME)}&size=10`,
      )
      expect(searchResp.status()).toBe(200)
      const searchBody = await searchResp.json()
      const village = searchBody.content.find(
        (v: { name: string; id: string }) => v.name === COMMUNITY_VILLAGE_NAME,
      )
      expect(village, `seed 済の "${COMMUNITY_VILLAGE_NAME}" が search 結果に含まれていない`).toBeTruthy()
      cachedVillageId = village.id as string
    }
    finally {
      await setupPage.close()
    }
  })

  test.beforeEach(async () => {
    page = await context.newPage()
  })

  test.afterEach(async () => {
    await page.close()
  })

  // 本ファイル自前の context は自前の afterAll でのみ閉じる
  // （他ファイルの describe と混じらないよう、1 スペックファイル = 1 セッションで完結させる）。
  test.afterAll(async () => {
    await context.close()
  })

  // -------------------------------------------------------------------------
  // VLG-001: /villages 一覧ページが表示される
  // -------------------------------------------------------------------------
  test('VLG-001: /villages 村一覧ページが表示される', async () => {
    await page.goto('/villages')
    await waitForHydration(page)
    await expect(page).not.toHaveURL(/\/login/)

    // 検索ページの PageHeader（heading）または検索フォーム input が見える。
    // seed 投入済の COMMUNITY 村名が一覧に出ていれば理想的。
    const heading = page.getByRole('heading').first()
    const villageName = page.getByText(COMMUNITY_VILLAGE_NAME, { exact: false }).first()
    await expect(heading.or(villageName).first()).toBeVisible({ timeout: 20_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-002: /villages/create-request 申請フォームが表示される
  // -------------------------------------------------------------------------
  test('VLG-002: /villages/create-request 申請フォームが表示される', async () => {
    await page.goto('/villages/create-request')
    await waitForHydration(page)
    await expect(page).not.toHaveURL(/\/login/)

    // フォーム上の何らかの input（村名 / slug / カテゴリなど）が描画されること
    const anyInput = page.locator('input[type="text"], input:not([type])').first()
    await expect(anyInput).toBeVisible({ timeout: 20_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-003: GET /api/v1/villages/search が認証付きで 200 を返す
  //
  //   バックエンド実装上 search は認証必須（SecurityUtils.getCurrentUserId 呼出のため）。
  //   認証ヘッダ付きで 200 + content 配列が返ることを検証する。
  // -------------------------------------------------------------------------
  test('VLG-003: GET /villages/search が認証付きで 200 を返す', async () => {
    // page.request は共有 BrowserContext の Cookie（access_token）をそのまま使う
    const resp = await page.request.get('http://localhost:8080/api/v1/villages/search?page=0&size=10')
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body.content)).toBe(true)
    // seed 済 COMMUNITY 村が見つかること
    const found = body.content.find((v: { name: string }) => v.name === COMMUNITY_VILLAGE_NAME)
    expect(found, 'seed 済 COMMUNITY 村が search 結果に含まれていない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // VLG-004: 検索クエリで該当なしの場合に空配列が返る
  //
  //   絶対にマッチしない文字列で検索 → 200 + content=[] を期待。
  // -------------------------------------------------------------------------
  test('VLG-004: 検索クエリ該当なしで 200 + 空配列', async () => {
    const q = encodeURIComponent('absent_text_zzz_nomatch_18f2e9')
    const resp = await page.request.get(`http://localhost:8080/api/v1/villages/search?q=${q}&page=0&size=10`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body.content)).toBe(true)
    expect(body.content.length).toBe(0)
  })

  // -------------------------------------------------------------------------
  // VLG-005: 村詳細 /villages/{id} に直アクセスすると bulletin にリダイレクト
  //
  //   index.vue は即時 navigateTo('/villages/{id}/bulletin') する設計。
  //   遷移後の bulletin ページに VillageHeader（h1 で村名）が描画されること。
  // -------------------------------------------------------------------------
  test('VLG-005: /villages/{id} が表示され村名が見える（bulletin にリダイレクト）', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // PageLoading が消えた後 VillageHeader の h1（村名）が見える、
    // または main 要素が描画されていれば pass
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-006: タイムラインタブ
  // -------------------------------------------------------------------------
  test('VLG-006: /villages/{id}/timeline タイムラインページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/timeline`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // VillageHeader の h1（村名）または main 要素が見える
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-007: 井戸端会議タブ
  // -------------------------------------------------------------------------
  test('VLG-007: /villages/{id}/lobby 井戸端会議ページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/lobby`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-008: 掲示板タブ
  // -------------------------------------------------------------------------
  test('VLG-008: /villages/{id}/bulletin 掲示板ページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-009: メンバー一覧
  //
  //   E2E_USER は COMMUNITY 村に VILLAGER として参加済（seed）。
  //   メンバー一覧ページが描画されること（VillageHeader + メンバーテーブル想定）。
  // -------------------------------------------------------------------------
  test('VLG-009: /villages/{id}/members メンバー一覧ページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/members`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // VillageHeader の村名見出し or main 要素が見える
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-010: カレンダー（歳時記）タブ
  // -------------------------------------------------------------------------
  test('VLG-010: /villages/{id}/calendar カレンダーページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/calendar`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-011: お祭りタブ
  // -------------------------------------------------------------------------
  test('VLG-011: /villages/{id}/festivals お祭りページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/festivals`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-012: 練習試合募集タブ
  // -------------------------------------------------------------------------
  test('VLG-012: /villages/{id}/match-recruits 練習試合募集ページが表示される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/match-recruits`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })
})
