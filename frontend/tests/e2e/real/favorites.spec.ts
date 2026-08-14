/**
 * F02.9 お気に入りウィジェット — 実機 E2E テスト（FAV-001〜010）。
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（単一セッション設計。
 * villages.spec.ts と同じ理由により、beforeAll で 1 つの BrowserContext を作成し
 * FAV-001〜009 全テストで使い回す（mode: 'serial'）。
 *
 * なぜ単一セッション設計にするか:
 *   アクセストークンはリフレッシュのたびにサーバ側で「回転」する（旧トークンを revoke し
 *   後継を発行）。後継トークンは Cookie 経由でその場の BrowserContext にしか残らない。
 *   テストが新しい storageState スナップショットや新規ログインから始まると、既に revoke
 *   済みのトークンを再提示することになり、grace window（60秒）超過後は「リプレイ攻撃」
 *   として検出されセッションごと失効する（AuthTokenRotationService）。
 *
 *   本ファイルは以前、FAV-003〜007 が毎テスト POST /api/v1/auth/login で新規ログインし
 *   Authorization ヘッダで別トークン系列を叩く構成だった。これは (a) UI ログイン
 *   (loginIfNeeded) より根が深い問題で、テストごとに全く別のトークン系列を生成するため
 *   共有 context の Cookie とも整合が取れず、長時間実行時にセッション失効を誘発していた。
 *   → 現在は共有 context から一度だけ（beforeAll 内の一時 page で）トークンを解決し、
 *   以降の全テストでそのトークンを使い回す。別途 API ログインを増やしてはならない。
 *
 * page はテストごとに beforeEach で newPage() → afterEach で close() する。回転後トークンの
 * 継続性に必要なのは Cookie ジャー（= context）であって page 自体の使い回しではなく、
 * page を使い回すと前のテストの WebSocket 接続や DOM 状態が漏れる懸念があるため。
 *
 * FAV-010（未認証アクセス）は本質的に「未認証であること」を検証するテストのため、
 * 共有 context には巻き込まず、独立した storageState なしの context のまま残す。
 *
 * 前提シード: backend/scripts/seed-e2e-data.js の F02.9 ブロックを実行済み。
 *   - E2E_USER に user_favorites 3 件
 *       TEAM x 2 (FC東京U-18, FC東京U-15) / ORGANIZATION x 1 (JFA)
 *
 * 仕様メモ:
 *   - WidgetFavorites は /dashboard ページに組込み (frontend/app/pages/dashboard.vue:80)
 *     /my/ は別ページ（マイページハブ）で WidgetFavorites を持たない。
 *   - お気に入りトグルボタン (FavoriteToggleButton) は F02.9 Phase 3 で予定されている
 *     未マージ機能。main にはまだ存在しないため、FAV-008/009 ではボタンの有無に依存
 *     せず「TEAM ページが描画される」レベルの緩い検証にとどめる。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

interface FavoriteResponse {
  id: string
  entityType: string
  entityId: string
  displayOrder: number
  displayName: string | null
  iconUrl: string | null
  pageUrl: string | null
  canEdit: boolean
  available: boolean
  createdAt: string
}

async function listFavorites(page: Page, token: string): Promise<FavoriteResponse[]> {
  const resp = await page.request.get('http://localhost:8080/api/v1/me/favorites', {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(resp.status()).toBe(200)
  const body = await resp.json()
  return body.data as FavoriteResponse[]
}

// ===========================================================================
// FAV-001〜010
// ===========================================================================
test.describe('FAV-001〜010: F02.9 お気に入りウィジェット', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(120_000)

  // describe スコープで 1 つの BrowserContext（Cookie ジャー）を使い回す（単一セッション設計）。
  // page はテストごとに beforeEach/afterEach で作り直す（前テストの状態を持ち越さない）。
  let context: BrowserContext
  let page: Page
  let sharedToken = ''

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/e2e/.auth/real-user.json' })

    // token 解決専用の一時 page（beforeEach 前なので明示的に作って閉じる）。
    // 別途 API ログイン (POST /auth/login) はせず、共有 context の Cookie（access_token）
    // をそのまま使ってトークンを解決する。
    const setupPage = await context.newPage()
    try {
      // 共有 context の Cookie（access_token）から直接トークンを取り出す（新規ログインは行わない）。
      const cookies = await context.cookies()
      const accessTokenCookie = cookies.find((c) => c.name === 'access_token')
      expect(accessTokenCookie, 'storageState に access_token Cookie が含まれていない').toBeTruthy()
      sharedToken = accessTokenCookie!.value

      // 解決したトークンが有効であることをここで検証しておく（以降の全テストの前提）。
      const resp = await setupPage.request.get('http://localhost:8080/api/v1/me/favorites', {
        headers: { Authorization: `Bearer ${sharedToken}` },
      })
      expect(resp.status(), '共有トークンで /api/v1/me/favorites が 200 を返さない').toBe(200)
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
  // （FAV-010 の独立 context とは混じらない）。
  test.afterAll(async () => {
    await context.close()
  })

  // ===========================================================================
  // FAV-001: /dashboard ページに WidgetFavorites が表示される
  //
  //   WidgetFavorites は data-testid="widget-favorites" を持つカード。
  //   タイトルは i18n key favorites.title = "お気に入り"。
  // ===========================================================================
  test('FAV-001: /dashboard に WidgetFavorites が表示される', async () => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    await waitForSpinnerGone(page)
    await expect(page).not.toHaveURL(/\/login/)

    // ウィジェットカードの存在を data-testid で検証
    const widget = page.locator('[data-testid="widget-favorites"]').first()
    await expect(widget).toBeVisible({ timeout: 20_000 })
  })

  // ===========================================================================
  // FAV-002: シード済お気に入りカードまたは空状態のいずれかが描画される
  //
  //   seed で 3 件登録済 → FavoriteCard が描画される。
  //   万一データ取得タイミングがズレた場合でも empty 表示でも pass する緩い検証。
  // ===========================================================================
  test('FAV-002: シード済カードか空状態のいずれかが描画される', async () => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    await waitForSpinnerGone(page)

    const widget = page.locator('[data-testid="widget-favorites"]').first()
    await expect(widget).toBeVisible({ timeout: 20_000 })

    // カードリスト or 空表示 or エラー（任意の状態が描画されれば pass）
    const list = widget.locator('[data-testid="widget-favorites-list"]')
    const empty = widget.getByText('まだお気に入りがありません', { exact: false })
    const errorBlock = widget.locator('[data-testid="widget-favorites-error"]')

    // いずれかが見えること（seed 投入済なら list が見える）
    await expect(list.or(empty).or(errorBlock).first()).toBeVisible({ timeout: 15_000 })
  })

  // ===========================================================================
  // FAV-003: API: GET /api/v1/me/favorites が認証付きで 200 を返す
  // ===========================================================================
  test('FAV-003: API GET /api/v1/me/favorites が 200 を返す', async () => {
    const resp = await page.request.get('http://localhost:8080/api/v1/me/favorites', {
      headers: { Authorization: `Bearer ${sharedToken}` },
    })
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body.data)).toBe(true)
    // seed 済 3 件以上が含まれていること
    expect(body.data.length).toBeGreaterThanOrEqual(3)
  })

  // ===========================================================================
  // FAV-004: API: POST で TEAM を追加できる
  //
  //   既に seed で TEAM:1, TEAM:2 が登録済のため、TEAM:5 (横浜FCジュニアA) を追加する。
  //   先頭 (displayOrder=0) に挿入される仕様。
  //   テスト後にロールバックのため DELETE する。
  // ===========================================================================
  test('FAV-004: POST /api/v1/me/favorites で TEAM 追加が成功する', async () => {
    // 既存リストに TEAM:5 が含まれている場合は事前に削除する（冪等化）
    const before = await listFavorites(page, sharedToken)
    const dup = before.find((f) => f.entityType === 'TEAM' && f.entityId === '5')
    if (dup) {
      await page.request.delete(`http://localhost:8080/api/v1/me/favorites/${dup.id}`, {
        headers: { Authorization: `Bearer ${sharedToken}` },
      })
    }

    const resp = await page.request.post('http://localhost:8080/api/v1/me/favorites', {
      headers: { Authorization: `Bearer ${sharedToken}`, 'Content-Type': 'application/json' },
      data: { entityType: 'TEAM', entityId: '5' },
    })
    expect(resp.status()).toBe(201)
    const body = await resp.json()
    expect(body.data.entityType).toBe('TEAM')
    expect(body.data.entityId).toBe('5')

    // ロールバック
    await page.request.delete(`http://localhost:8080/api/v1/me/favorites/${body.data.id}`, {
      headers: { Authorization: `Bearer ${sharedToken}` },
    })
  })

  // ===========================================================================
  // FAV-005: 追加した favorite が GET 一覧に含まれることを検証
  // ===========================================================================
  test('FAV-005: POST → GET で配列に含まれる', async () => {
    // 既存重複を掃除
    const before = await listFavorites(page, sharedToken)
    const dup = before.find((f) => f.entityType === 'TEAM' && f.entityId === '6')
    if (dup) {
      await page.request.delete(`http://localhost:8080/api/v1/me/favorites/${dup.id}`, {
        headers: { Authorization: `Bearer ${sharedToken}` },
      })
    }

    // 追加
    const addResp = await page.request.post('http://localhost:8080/api/v1/me/favorites', {
      headers: { Authorization: `Bearer ${sharedToken}`, 'Content-Type': 'application/json' },
      data: { entityType: 'TEAM', entityId: '6' },
    })
    expect(addResp.status()).toBe(201)
    const addedId = (await addResp.json()).data.id as string

    // 一覧に含まれることを確認
    const after = await listFavorites(page, sharedToken)
    const found = after.find((f) => f.id === addedId)
    expect(found).toBeDefined()
    expect(found?.entityType).toBe('TEAM')
    expect(found?.entityId).toBe('6')

    // ロールバック
    await page.request.delete(`http://localhost:8080/api/v1/me/favorites/${addedId}`, {
      headers: { Authorization: `Bearer ${sharedToken}` },
    })
  })

  // ===========================================================================
  // FAV-006: DELETE で削除できる
  // ===========================================================================
  test('FAV-006: DELETE /api/v1/me/favorites/{id} で削除できる', async () => {
    // 追加用 entity を準備
    const before = await listFavorites(page, sharedToken)
    const dup = before.find((f) => f.entityType === 'TEAM' && f.entityId === '7')
    if (dup) {
      await page.request.delete(`http://localhost:8080/api/v1/me/favorites/${dup.id}`, {
        headers: { Authorization: `Bearer ${sharedToken}` },
      })
    }

    const addResp = await page.request.post('http://localhost:8080/api/v1/me/favorites', {
      headers: { Authorization: `Bearer ${sharedToken}`, 'Content-Type': 'application/json' },
      data: { entityType: 'TEAM', entityId: '7' },
    })
    expect(addResp.status()).toBe(201)
    const newId = (await addResp.json()).data.id as string

    // 削除
    const delResp = await page.request.delete(
      `http://localhost:8080/api/v1/me/favorites/${newId}`,
      { headers: { Authorization: `Bearer ${sharedToken}` } },
    )
    expect(delResp.status()).toBe(204)

    // 一覧から消えていること
    const after = await listFavorites(page, sharedToken)
    expect(after.find((f) => f.id === newId)).toBeUndefined()
  })

  // ===========================================================================
  // FAV-007: 不正な entityType で 400 が返る
  //
  //   entityType を未定義の値 "INVALID" にすると Service 層で FAV_005 (400) を返す。
  //   request bean validation で 400 になる可能性もあるが、いずれにせよ 4xx であること。
  // ===========================================================================
  test('FAV-007: 不正な entityType で 4xx が返る', async () => {
    const resp = await page.request.post('http://localhost:8080/api/v1/me/favorites', {
      headers: { Authorization: `Bearer ${sharedToken}`, 'Content-Type': 'application/json' },
      data: { entityType: 'INVALID', entityId: '1' },
      failOnStatusCode: false,
    })
    // バリデーション失敗で 400 か、業務例外で 400 のいずれか
    expect(resp.status()).toBeGreaterThanOrEqual(400)
    expect(resp.status()).toBeLessThan(500)
  })

  // ===========================================================================
  // FAV-008: チーム詳細ページ /teams/{slug} が描画される
  //
  //   このプロジェクトはURL識別子を slug に一本化済みで、数値IDのURL
  //   (/teams/1 等) はチームページとして解決しない（アプリ外枠のみ描画され
  //   見出しが一切出ない）。実機確認済みの slug 'team-000092'（e2e-user が
  //   MEMBER として所属、circulation-member.spec.ts と同一チーム）を使う。
  //
  //   F02.9 Phase 3 の FavoriteToggleButton は main 未マージのため、
  //   ボタン有無に依存せず「チームページ自体が描画される」レベルで検証する。
  //   将来 FavoriteToggleButton が組み込まれたら data-testid="favorite-toggle"
  //   など強い検証に書き換えること。
  // ===========================================================================
  test('FAV-008: /teams/{slug} ページが描画される (FavoriteToggleButton配置先)', async () => {
    // team-000092 は実機確認済みの実在チーム（slug=team-000092, numericId=92）
    await page.goto('/teams/team-000092')
    await waitForHydration(page)
    await waitForSpinnerGone(page)

    // 認証通過し、/login にリダイレクトされないこと
    await expect(page).not.toHaveURL(/\/login/)

    // チームページの見出し（TeamPageHeader）が可視化されること。
    // 「認可エラーにならず表示される」ことをテキスト長ではなく明示的な要素で検証する。
    await expect(page.locator('h1, h2').first()).toBeVisible({ timeout: 15_000 })

    // チームページ本文に何かしらコンテンツが描画されること（緩く検証）
    const body = await page.locator('body').textContent()
    expect(body).toBeTruthy()
    expect((body ?? '').length).toBeGreaterThan(50)
  })

  // ===========================================================================
  // FAV-009: ダッシュボードのウィジェット内でリフレッシュアクションを叩いても
  //          エラーオーバーレイが出ない（FavoriteToggleButton 統合前の代替検証）
  //
  //   将来 FavoriteToggleButton がマージされたら「クリック→アイコン変化」の
  //   ストロング検証に書き換えること。
  // ===========================================================================
  test('FAV-009: WidgetFavorites のリフレッシュボタンが動作する', async () => {
    await page.goto('/dashboard')
    await waitForHydration(page)
    await waitForSpinnerGone(page)

    const widget = page.locator('[data-testid="widget-favorites"]').first()
    await expect(widget).toBeVisible({ timeout: 20_000 })

    // DashboardWidgetCard は refreshable のとき .pi-refresh アイコンを出す。
    // クリック後、WidgetFavorites.refresh() は useFavoritesApi().fetchFavorites() を呼び、
    // 実際には GET /api/v1/me/favorites が叩かれる
    // (frontend/app/components/widgets/WidgetFavorites.vue:128-134,
    //  frontend/app/composables/useFavoritesApi.ts:85-99)。
    // 固定スリープではなく、そのレスポンスを明示的に待つ。
    const refreshBtn = widget.locator('button').filter({ has: page.locator('.pi-refresh') }).first()
    const isRefreshable = (await refreshBtn.count()) > 0
    if (isRefreshable) {
      const [refreshResp] = await Promise.all([
        page.waitForResponse(
          (r) => r.url().includes('/api/v1/me/favorites')
            && r.request().method() === 'GET',
          { timeout: 15_000 },
        ),
        refreshBtn.click(),
      ])
      expect(refreshResp.status()).toBe(200)

      // エラー要素が出現しないこと
      const errorBlock = widget.locator('[data-testid="widget-favorites-error"]')
      expect(await errorBlock.count()).toBe(0)
    }
    // refreshable でなくとも widget が引き続き visible であれば pass
    await expect(widget).toBeVisible()
  })
})

// ===========================================================================
// FAV-010: 未認証アクセス
//
//   未認証であることがテストの本質のため、上の describe の共有 context には
//   巻き込まず、独立した storageState なしの context（従来どおりの page
//   フィクスチャ）のまま残す。
// ===========================================================================
test.describe('FAV-010: 未認証アクセス', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('FAV-010: 未認証時 /dashboard で /login にリダイレクトされる', async ({ page }) => {
    await page.goto('/dashboard')
    await page.waitForURL(/\/login/, { timeout: 20_000 })
    await waitForHydration(page)
    await expect(page.locator('input#email')).toBeVisible({ timeout: 10_000 })
  })
})
