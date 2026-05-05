import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARD_ID_MEMO,
  buildBoardDetail,
  buildCard,
  mockCatchAllApis,
  setupOwnerAuth,
  type E2eCard,
} from './_helpers'

/**
 * F09.8 コルクボード 件B 追補 — WebSocket イベント受信時の局所更新 E2E。
 *
 * <p>背景: 件B の本実装は完了 (commit 0f7542b3a) しており、
 * `useCorkboardEventListener` の onEvent からボード詳細ページの
 * {@code handleCorkboardEvent} が呼ばれて eventType ごとに
 * push / map / filter で局所更新する設計に変わっている。</p>
 *
 * <p>WebSocket メッセージを STOMP 経由で正確に再現するのは E2E では難しいため、
 * 本 spec は <strong>ページ側に最小限のテスト用フック</strong>
 * (`window.__corkboardE2eEmit`) を公開し、Playwright の {@code page.evaluate}
 * から {@code handleCorkboardEvent} を直接呼ぶ方針を採用する。
 * フック自体は {@code window.__E2E__ === true} を addInitScript で注入したときのみ
 * 公開されるため本番バンドルへの影響はない。</p>
 *
 * <p>STOMP 接続そのものは既存 spec
 * ({@code corkboard-websocket.spec.ts}) で配線確認済み。
 * eventType 別の細かなロジックは Vitest 単体テストでも併用網羅される設計。</p>
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-WS-LOCAL-001: CARD_CREATED 受信 → カード追加（API 再呼び出しなし）</li>
 *   <li>CORK-WS-LOCAL-002: CARD_UPDATED 受信 → カード更新</li>
 *   <li>CORK-WS-LOCAL-003: CARD_DELETED 受信 → カード削除</li>
 *   <li>CORK-WS-LOCAL-004: SECTION_DELETED 受信 → セクション削除 + 該当カード sectionId null</li>
 *   <li>CORK-WS-LOCAL-005: 旧 BE 互換 (card 同梱なし) → load() フォールバック</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md（件B: WebSocket 局所更新）</p>
 */

const SHARED_BOARD_ID = 800
const SHARED_TEAM_ID = 99
const NEW_CARD_ID = 950
const SECTION_ID = 33

/**
 * テストフック (`window.__E2E__`) を注入する。
 * これにより `pages/corkboard/[id].vue` 内の onMounted フックで
 * `window.__corkboardE2eEmit` が公開される。
 */
async function enableE2eHook(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    ;(window as unknown as { __E2E__: boolean }).__E2E__ = true
  })
}

/**
 * ページ側の `__corkboardE2eEmit` フックを介して WebSocket イベントをシミュレートする。
 *
 * `handleCorkboardEvent` は同期的に board.value を書き換えるため、
 * Promise を待つ必要はないが、Vue のリアクティブ反映のために
 * 直後に短い待機を入れる呼び出し側もある。
 */
async function emitCorkboardEvent(
  page: import('@playwright/test').Page,
  payload: Record<string, unknown>,
): Promise<void> {
  await page.evaluate((p) => {
    const w = window as unknown as {
      __corkboardE2eEmit?: (payload: unknown) => void
    }
    if (!w.__corkboardE2eEmit) {
      throw new Error('window.__corkboardE2eEmit が未公開: __E2E__ 注入済みか確認')
    }
    w.__corkboardE2eEmit(p)
  }, payload)
}

/**
 * 共有ボード (TEAM, ALL_MEMBERS) の雛形。
 * 件B の WebSocket 配信は共有ボードのみ対象なので TEAM スコープで揃える。
 */
function buildSharedBoard(cards: E2eCard[], groups: unknown[] = []) {
  return buildBoardDetail(cards, {
    id: SHARED_BOARD_ID,
    scopeType: 'TEAM',
    scopeId: SHARED_TEAM_ID,
    ownerId: null,
    editPolicy: 'ALL_MEMBERS',
    viewerCanEdit: true,
    groups,
  })
}

/**
 * GET /corkboards/{id} の呼び出し回数を計測しつつボード詳細を返す mock。
 *
 * mockBoardDetail と排他で利用する（こちらで route 設定するため
 * mockBoardDetail を呼ばない）。
 */
async function mockBoardDetailWithCounter(
  page: import('@playwright/test').Page,
  initialBoard: ReturnType<typeof buildSharedBoard>,
): Promise<{ getCount: () => number }> {
  let calls = 0
  await page.route(
    `**/api/v1/corkboards/${initialBoard.id}`,
    async (route) => {
      if (route.request().method() === 'GET') {
        calls++
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: initialBoard }),
        })
      } else {
        await route.continue()
      }
    },
  )
  return { getCount: () => calls }
}

test.describe('CORK-WS-LOCAL: WebSocket イベント局所更新', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
    await enableE2eHook(page)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-LOCAL-001
  // ---------------------------------------------------------------------
  test('CORK-WS-LOCAL-001: CARD_CREATED 受信 → カードが追加され、API は再呼び出しされない', async ({
    page,
  }) => {
    const initial = buildSharedBoard([
      buildCard(CARD_ID_MEMO, { body: '既存カード', corkboardId: SHARED_BOARD_ID }),
    ])
    const counter = await mockBoardDetailWithCounter(page, initial)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toBeVisible({ timeout: 10_000 })

    const initialCalls = counter.getCount()
    expect(initialCalls).toBeGreaterThanOrEqual(1)

    // CARD_CREATED イベントを発火
    const newCard = buildCard(NEW_CARD_ID, {
      corkboardId: SHARED_BOARD_ID,
      body: 'WebSocket 経由で追加された新カード',
    })
    await emitCorkboardEvent(page, {
      boardId: SHARED_BOARD_ID,
      eventType: 'CARD_CREATED',
      cardId: NEW_CARD_ID,
      sectionId: null,
      card: newCard,
    })

    // 新カードが描画される
    await expect(
      page.getByTestId(`corkboard-card-${NEW_CARD_ID}`),
    ).toBeVisible({ timeout: 5_000 })

    // 既存カードもそのまま残っている
    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toBeVisible()

    // フルリロード API は呼ばれていない（局所更新であることの確認）
    expect(counter.getCount()).toBe(initialCalls)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-LOCAL-002
  // ---------------------------------------------------------------------
  test('CORK-WS-LOCAL-002: CARD_UPDATED 受信 → カード本文が局所更新される', async ({
    page,
  }) => {
    const original = buildCard(CARD_ID_MEMO, {
      title: '更新前タイトル',
      body: '更新前本文',
      corkboardId: SHARED_BOARD_ID,
    })
    const initial = buildSharedBoard([original])
    const counter = await mockBoardDetailWithCounter(page, initial)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(page.getByText('更新前タイトル')).toBeVisible({ timeout: 10_000 })

    const callsBefore = counter.getCount()

    const updated = buildCard(CARD_ID_MEMO, {
      title: 'WS 由来の更新後タイトル',
      body: '更新後本文',
      corkboardId: SHARED_BOARD_ID,
    })
    await emitCorkboardEvent(page, {
      boardId: SHARED_BOARD_ID,
      eventType: 'CARD_UPDATED',
      cardId: CARD_ID_MEMO,
      sectionId: null,
      card: updated,
    })

    await expect(page.getByText('WS 由来の更新後タイトル')).toBeVisible({
      timeout: 5_000,
    })
    expect(counter.getCount()).toBe(callsBefore)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-LOCAL-003
  // ---------------------------------------------------------------------
  test('CORK-WS-LOCAL-003: CARD_DELETED 受信 → カードが削除される', async ({
    page,
  }) => {
    const initial = buildSharedBoard([
      buildCard(CARD_ID_MEMO, { body: '消える対象', corkboardId: SHARED_BOARD_ID }),
    ])
    const counter = await mockBoardDetailWithCounter(page, initial)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toBeVisible({ timeout: 10_000 })

    const callsBefore = counter.getCount()

    await emitCorkboardEvent(page, {
      boardId: SHARED_BOARD_ID,
      eventType: 'CARD_DELETED',
      cardId: CARD_ID_MEMO,
      sectionId: null,
    })

    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toHaveCount(0, { timeout: 5_000 })
    expect(counter.getCount()).toBe(callsBefore)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-LOCAL-004
  // ---------------------------------------------------------------------
  test('CORK-WS-LOCAL-004: SECTION_DELETED 受信 → セクションと所属カードの sectionId がクリアされる', async ({
    page,
  }) => {
    const cardInSection = buildCard(CARD_ID_MEMO, {
      body: 'セクションに紐付いたカード',
      corkboardId: SHARED_BOARD_ID,
      sectionId: SECTION_ID,
    })
    const section = {
      id: SECTION_ID,
      corkboardId: SHARED_BOARD_ID,
      name: '消えるセクション',
      isCollapsed: false,
      positionX: 50,
      positionY: 50,
      width: 400,
      height: 200,
      displayOrder: 1,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    }
    const initial = buildSharedBoard([cardInSection], [section])
    const counter = await mockBoardDetailWithCounter(page, initial)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(
      page.getByTestId(`corkboard-section-${SECTION_ID}`),
    ).toBeVisible({ timeout: 10_000 })

    const callsBefore = counter.getCount()

    await emitCorkboardEvent(page, {
      boardId: SHARED_BOARD_ID,
      eventType: 'SECTION_DELETED',
      cardId: null,
      sectionId: SECTION_ID,
    })

    // セクションが消える
    await expect(
      page.getByTestId(`corkboard-section-${SECTION_ID}`),
    ).toHaveCount(0, { timeout: 5_000 })

    // 紐付いていたカードは残る（sectionId が null になるだけ）
    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toBeVisible()

    expect(counter.getCount()).toBe(callsBefore)
  })

  // ---------------------------------------------------------------------
  // CORK-WS-LOCAL-005
  // ---------------------------------------------------------------------
  test('CORK-WS-LOCAL-005: 旧 BE 互換 — card 同梱なし CARD_CREATED → load() フルリロードへフォールバック', async ({
    page,
  }) => {
    const initial = buildSharedBoard([
      buildCard(CARD_ID_MEMO, { body: '初期カード', corkboardId: SHARED_BOARD_ID }),
    ])
    const counter = await mockBoardDetailWithCounter(page, initial)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)
    await expect(
      page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`),
    ).toBeVisible({ timeout: 10_000 })

    const callsBefore = counter.getCount()

    // 旧 BE 互換ペイロード: card フィールドが null
    await emitCorkboardEvent(page, {
      boardId: SHARED_BOARD_ID,
      eventType: 'CARD_CREATED',
      cardId: 999,
      sectionId: null,
      card: null,
    })

    // フルリロードが発生する → GET /corkboards/{id} が増えている
    await expect
      .poll(() => counter.getCount(), { timeout: 5_000 })
      .toBeGreaterThan(callsBefore)
  })
})
