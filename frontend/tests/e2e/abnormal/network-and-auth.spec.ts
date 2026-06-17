import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// mock tier: chromium project の storageState(user.json)に依存せず未認証状態から開始する。
// 各テストは page.evaluate で localStorage['currentUser'] を自前で偽装するため空 state でよい
// （auth-flow.spec.ts と同じ作法）。これにより .auth/user.json 未生成の worktree でも実行可能。
test.use({ storageState: { cookies: [], origins: [] } })

/**
 * P2 異常系 E2E — mock tier（バックエンド不要・全 API を page.route でスタブ）
 *
 * ## P2-NET-01: write 操作中のネットワーク失敗
 *   - /action-memo ページでメモを入力し、POST API を abort する
 *   - エラーバナー（data-testid="action-memo-error-banner"）が表示され、
 *     「メモの保存に失敗しました」というテキストを含むことを hard assert
 *   - 画面が壊れていないこと（テキストエリアが存在し続ける）を hard assert
 *
 * ## P2-AUTH-EXPIRE: セッション期限切れ → /login リダイレクト
 *   - localStorage に currentUser を設定して認証済み状態を偽装
 *   - ページロード時に呼ばれる全 API を 401 でスタブ
 *   - /api/v1/auth/refresh も 401 でスタブ（refresh 失敗を強制）
 *   - useApi.ts の interceptor が refresh 失敗 → logout() → navigateTo('/login') を実行
 *   - URL が /login になることを hard assert
 *
 * ⚠️ UNVERIFIED: エラートーストの具体的な文言は i18n キー（action_memo.error.save_failed）から
 * 導出。実機動作は未確認（mock tier のため）。
 */

// === 共通モックデータ ===

const MOCK_USER = {
  id: 1,
  email: 'test@example.com',
  fullName: 'テスト ユーザー',
  profileImageUrl: null,
  systemRole: null,
  timezone: 'Asia/Tokyo',
}

const MOCK_SETTINGS = {
  data: {
    mood_enabled: false,
    default_category: 'OTHER',
    default_post_team_id: null,
  },
}

const MOCK_MEMOS_EMPTY = {
  data: [],
  meta: { nextCursor: null, hasNext: false },
}

const MOCK_TEAMS_EMPTY = { data: [] }

const MOCK_ORGS_EMPTY = { data: [] }

const MOCK_OFFLINE_QUEUE = { count: 0 }

// === P2-NET-01: write 操作中のネットワーク失敗 ===

test.describe('P2-NET-01: write 操作中のネットワーク失敗でエラーバナーが出る', () => {
  /**
   * /action-memo ページを全 API スタブで表示し、POST のみ abort してエラー表示を確認する。
   *
   * 触る API:
   *   GET  /api/v1/action-memo-settings     → 200 (MOCK_SETTINGS)
   *   GET  /api/v1/action-memos**           → 200 (MOCK_MEMOS_EMPTY)
   *   GET  /api/v1/teams** (available)      → 200 (MOCK_TEAMS_EMPTY)
   *   GET  /api/v1/organizations** (avail)  → 200 (MOCK_ORGS_EMPTY)
   *   GET  offline-queue-count             -> 200 (count 0)
   *   GET  /api/v1/users/me                 → 200 (MOCK_USER) ※auth plugin 先回りrefresh用
   *   POST /api/v1/auth/refresh             → 200 (dummy) ※auth plugin 先回りrefresh用
   *   POST /api/v1/action-memos             → abort (ネットワーク失敗シミュレート)
   */
  test('P2-NET-01-01: POST abort → エラーバナーが "メモの保存に失敗しました" を表示し画面が壊れない', async ({
    page,
  }) => {
    // auth.client.ts プラグインの先回り refresh 呼び出しをスタブ（成功させる）
    await page.route('**/api/v1/auth/refresh', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { accessToken: 'dummy-access-token', refreshToken: 'dummy-refresh-token' },
          }),
        })
      } else {
        await route.continue()
      }
    })

    // /api/v1/users/me をスタブ（認証確認・SSR ハイドレーション用）
    await page.route('**/api/v1/users/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_USER }),
      })
    })

    // action-memo-settings（GET）
    await page.route('**/api/v1/action-memo-settings', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_SETTINGS),
        })
      } else {
        await route.continue()
      }
    })

    // action-memos: GET は空リスト、POST は abort（write 失敗）
    await page.route('**/api/v1/action-memos**', async (route) => {
      if (route.request().method() === 'POST') {
        // ネットワーク失敗をシミュレート
        await route.abort('failed')
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_MEMOS_EMPTY),
        })
      }
    })

    // teams（available チーム一覧）
    await page.route('**/api/v1/teams**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_TEAMS_EMPTY),
      })
    })

    // organizations（available 組織一覧）
    await page.route('**/api/v1/organizations**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_ORGS_EMPTY),
      })
    })

    // offline-queue-count などその他の API はすべて 200 で返す
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: null }),
      })
    })

    // localStorage に currentUser を設定して認証済み状態を偽装
    await page.goto('/')
    await page.evaluate((user) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
    }, MOCK_USER)

    // /action-memo ページへ遷移
    await page.goto('/action-memo')
    await waitForHydration(page)

    // テキストエリアが表示されるまで待機
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 10_000 })

    // テキストを入力
    await textarea.click()
    await textarea.fill('ネットワーク失敗テスト用メモ')

    // 送信ボタンをクリック（POST が abort されてエラーになるはず）
    const submitBtn = page.locator('[data-testid="action-memo-input-submit"]')
    await expect(submitBtn).toBeEnabled({ timeout: 5_000 })
    await submitBtn.click()

    // エラーバナーが表示されることを hard assert（waitForTimeout 禁止 → web-first assertion）
    const errorBanner = page.locator('[data-testid="action-memo-error-banner"]')
    await expect(errorBanner).toBeVisible({ timeout: 10_000 })

    // store.error は _handleError のフォールバックで 'action_memo.error.save_failed' になる。
    // mock dev サーバーは i18n lazy-load が未適用でキー文字列がそのまま描画されるため、
    // 翻訳済み文言・i18nキーの両対応で検証する（ロード状態に依存しない堅牢なアサート）。
    await expect(errorBanner).toContainText(
      /メモの保存に失敗しました|action_memo\.error\.save_failed/,
      { timeout: 5_000 },
    )

    // 画面が壊れていないこと: テキストエリアが引き続き存在する（部分 state なし）
    await expect(textarea).toBeVisible({ timeout: 5_000 })

    // ページが白画面になっていないこと
    const heading = page.locator('h1')
    await expect(heading).toBeVisible({ timeout: 5_000 })
  })
})

// === P2-AUTH-EXPIRE: セッション期限切れ → /login リダイレクト ===

test.describe('P2-AUTH-EXPIRE: 認証済みセッション中にトークン期限切れ → /login リダイレクト', () => {
  /**
   * ブラウザセッションが認証済み状態（localStorage.currentUser あり）で、
   * 全 API が 401 を返す（＋ /auth/refresh も 401）状況をシミュレートする。
   *
   * useApi.ts の interceptor:
   *   1. API が 401 → authStore.user があるので performTokenRefresh を呼ぶ
   *   2. /auth/refresh も 401 → performTokenRefresh が false を返す
   *   3. authStore.logout() → navigateTo('/login')
   *   4. URL が /login になる
   *
   * 触る API:
   *   POST /api/v1/auth/refresh    → 401 (refresh 失敗)
   *   GET  /api/v1/**              → 401 (全 API 期限切れ)
   */
  test('P2-AUTH-EXPIRE-01: API 全 401 + refresh 失敗 → /login にリダイレクトされる', async ({
    page,
  }) => {
    // auth.client.ts プラグインの先回り refresh を 401 でスタブ
    // （refresh 失敗時は logout せず interceptor に委ねる設計のため、ここは 401 でよい）
    await page.route('**/api/v1/auth/refresh', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Unauthorized', code: 'AUTH_002' }),
      })
    })

    // 全 API を 401 でスタブ（セッション期限切れ状態）
    await page.route('**/api/v1/**', async (route) => {
      // /auth/refresh は上の route で先にマッチするため、ここでは残りの全 API を 401 に
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Unauthorized', code: 'AUTH_002' }),
      })
    })

    // "/" に goto して localStorage に currentUser を設定
    // （page.route が効いている状態で認証済みユーザーを偽装）
    await page.goto('/')
    await page.evaluate((user) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
      // tokenExpiresAt を設定しない → auth.client.ts が refresh を試みる（→ 401 で失敗）
    }, MOCK_USER)

    // 認証保護ページへ遷移（auth middleware が通過 → ページロード時に API を呼び出す）
    await page.goto('/action-memo')

    // useApi.ts の 401 interceptor が走り、refresh 失敗 → logout → /login へリダイレクト
    // ⚠️ UNVERIFIED: リダイレクトのタイムアウトは navigateTo 完了を待つ
    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })

    // /login ページが表示されていること（白画面でないこと）
    await waitForHydration(page)
    const loginPage = page.locator('body')
    const bodyText = await loginPage.textContent()
    expect((bodyText ?? '').length).toBeGreaterThan(0)
  })
})
