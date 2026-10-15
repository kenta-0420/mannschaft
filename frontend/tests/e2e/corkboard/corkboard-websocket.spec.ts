import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARD_ID_MEMO,
  PERSONAL_BOARD_ID,
  buildBoardDetail,
  buildCard,
  mockBoardDetail,
  mockCatchAllApis,
  setupOwnerAuth,
} from './_helpers'

/**
 * F09.8 コルクボード Phase F — WebSocket リアルタイム同期 E2E テスト。
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-WS-001: 個人ボード（PERSONAL）を開いても WebSocket 接続を試行しない</li>
 *   <li>CORK-WS-002: 共有ボード（TEAM）を開くと WebSocket 接続を試行する</li>
 * </ul>
 *
 * <p>方針: 実際の STOMP メッセージ受信は単体テスト
 * （{@code tests/unit/composables/useCorkboardEventListener.spec.ts}）で詳細検証する。
 * E2E では「個人ボードでは購読がスキップされる」「共有ボードでは購読が試行される」
 * という配線レベルのスモークテストに絞る。</p>
 *
 * <p>WebSocket の接続検知には Playwright の {@code page.waitForEvent('websocket')} を使用。
 * タイムアウト時間で「試行されたか / されなかったか」を判定する。</p>
 *
 * <p>注: バックエンド WS エンドポイントは {@code /ws}。auth エラー等で即座に切断される
 * 場合でも、フロントが WebSocket オブジェクトを生成するだけで本テストの意図
 * （配線確認）は満たされる。</p>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md §5「リアルタイム同期 / WebSocket」</p>
 */

const SHARED_BOARD_ID = 500
const SHARED_TEAM_ID = 77

test.describe('CORK-WS: WebSocket リアルタイム同期', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-001: 個人ボードでは購読をスキップする
  // ---------------------------------------------------------------------
  test('CORK-WS-001: 個人ボードでは WebSocket 接続を試みない', async ({ page }) => {
    const board = buildBoardDetail([buildCard(CARD_ID_MEMO, { body: '個人メモ' })])
    await mockBoardDetail(page, board)

    // WebSocket 接続が発生したかを記録するフラグ
    let wsAttempted = false
    page.on('websocket', () => {
      wsAttempted = true
    })

    await page.goto(`/corkboard/${PERSONAL_BOARD_ID}`)
    await waitForHydration(page)

    // ページ表示完了を待ってから一定時間 idle にして判定（接続試行の機会を与える）
    await expect(page.getByTestId('corkboard-detail-page')).toBeVisible({
      timeout: 10_000,
    })
    await page.waitForTimeout(800)

    expect(wsAttempted).toBe(false)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-002: 共有ボード（TEAM）では購読を試みる
  // ---------------------------------------------------------------------
  test('CORK-WS-002: 共有ボード（TEAM）では WebSocket 接続を試みる', async ({ page }) => {
    const sharedBoard = buildBoardDetail([], {
      id: SHARED_BOARD_ID,
      scopeType: 'TEAM',
      scopeId: SHARED_TEAM_ID,
      ownerId: null,
      name: 'E2E 用チームボード',
      editPolicy: 'ALL_MEMBERS',
    })
    await mockBoardDetail(page, sharedBoard)

    // WebSocket 接続を Promise で待ち受ける（最大 5 秒）
    const wsPromise = page
      .waitForEvent('websocket', { timeout: 5_000 })
      .then((ws) => ws)
      .catch(() => null)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(page.getByTestId('corkboard-detail-page')).toBeVisible({
      timeout: 10_000,
    })

    const ws = await wsPromise
    // STOMP クライアントは /ws に対して WebSocket を生成する
    expect(ws).not.toBeNull()
    if (ws) {
      expect(ws.url()).toContain('/ws')
    }
  })
})
