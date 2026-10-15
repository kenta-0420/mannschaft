import { test, expect, type Page } from '@playwright/test'
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
 * F09.8 コルクボード Phase D — カード D&D 位置移動 E2E テスト。
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-DND-001: 個人ボードのカードをドラッグ → batch-position API が呼ばれる</li>
 *   <li>CORK-DND-002: ピン止めカードはドラッグできない（座標が変化しない）</li>
 *   <li>CORK-DND-003: 共有ボードで edit_policy=ADMIN_ONLY → ドラッグ不可</li>
 *   <li>CORK-DND-004: 複数カードを順にドラッグ → 各々 API が呼ばれる</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md §4 PATCH /cards/batch-position</p>
 *
 * <p>useDraggable は実 DOM の pointerdown/pointermove/pointerup イベントで動作するため、
 * Playwright の {@code page.mouse.down/move/up} ではなく {@code dispatchEvent} で
 * PointerEvent を直接発火させる方式が確実。</p>
 */

const DETAIL_URL = `/corkboard/${PERSONAL_BOARD_ID}`
const BATCH_POSITION_URL_RE = new RegExp(
  `/api/v1/corkboards/${PERSONAL_BOARD_ID}/cards/batch-position`,
)

interface BatchPositionTracker {
  /** 受信したリクエストボディの履歴 */
  bodies: Array<Record<string, unknown>>
}

/**
 * batch-position API のモック + リクエスト記録。
 */
async function mockBatchPositionApi(
  page: Page,
  options: { shouldFail?: boolean } = {},
): Promise<BatchPositionTracker> {
  const tracker: BatchPositionTracker = { bodies: [] }
  await page.route(BATCH_POSITION_URL_RE, async (route) => {
    if (route.request().method() === 'PATCH') {
      const body = route.request().postDataJSON() as Record<string, unknown>
      tracker.bodies.push(body)
      if (options.shouldFail) {
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { updatedCount: 1 } }),
      })
    } else {
      await route.continue()
    }
  })
  return tracker
}

/**
 * useDraggable に対して実 DOM の PointerEvent を発火してドラッグを再現する。
 *
 * @vueuse/core の useDraggable は要素の `pointerdown` を起点に、
 * window へバインドした `pointermove` / `pointerup` で追従する。
 * Playwright の {@code page.mouse} では PointerEvent ではなく MouseEvent が
 * 飛ぶため、useDraggable の pointer イベントハンドラが反応せぬ場合がある。
 * そのため evaluate で `dispatchEvent(new PointerEvent(...))` を直接呼ぶ。
 */
async function dragCardByOffset(
  page: Page,
  cardSelector: string,
  deltaX: number,
  deltaY: number,
): Promise<void> {
  await page.evaluate(
    ({ selector, dx, dy }) => {
      const el = document.querySelector(selector) as HTMLElement | null
      if (!el) throw new Error(`drag target not found: ${selector}`)
      const rect = el.getBoundingClientRect()
      const startX = rect.left + rect.width / 2
      const startY = rect.top + rect.height / 2
      const endX = startX + dx
      const endY = startY + dy

      const opts = (x: number, y: number) => ({
        bubbles: true,
        cancelable: true,
        composed: true,
        clientX: x,
        clientY: y,
        screenX: x,
        screenY: y,
        button: 0,
        pointerType: 'mouse',
        pointerId: 1,
        isPrimary: true,
      })

      el.dispatchEvent(new PointerEvent('pointerdown', opts(startX, startY)))
      // useDraggable は window 上で move/up を listen している
      window.dispatchEvent(new PointerEvent('pointermove', opts(endX, endY)))
      window.dispatchEvent(new PointerEvent('pointerup', opts(endX, endY)))
    },
    { selector: cardSelector, dx: deltaX, dy: deltaY },
  )
}

test.describe('CORK-DND: コルクボードカード D&D', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  // ---------------------------------------------------------------------
  // CORK-DND-001
  // ---------------------------------------------------------------------
  test('CORK-DND-001: 個人ボードのカードをドラッグすると位置更新 API が呼ばれる', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'D&D 対象',
      positionX: 100,
      positionY: 100,
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockBatchPositionApi(page)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await expect(cardEl).toBeVisible()
    await expect(cardEl).toHaveAttribute('data-draggable', 'true')

    await dragCardByOffset(
      page,
      `[data-testid="corkboard-card-${CARD_ID_MEMO}"]`,
      150,
      80,
    )

    await expect.poll(() => tracker.bodies.length, { timeout: 5_000 }).toBeGreaterThan(0)

    const body = tracker.bodies[0]!
    expect(body).toBeTruthy()
    const positions = body.positions as Array<Record<string, unknown>>
    expect(Array.isArray(positions)).toBe(true)
    expect(positions.length).toBe(1)
    expect(positions[0]!.cardId).toBe(CARD_ID_MEMO)
    // バックエンド DTO 必須キーが揃っていること
    expect(positions[0]!).toHaveProperty('positionX')
    expect(positions[0]!).toHaveProperty('positionY')
    expect(positions[0]!).toHaveProperty('zIndex')
    // 期待: 元 100 + 150 = 250、元 100 + 80 = 180（多少の誤差は許容）
    expect(Number(positions[0]!.positionX)).toBeGreaterThanOrEqual(240)
    expect(Number(positions[0]!.positionY)).toBeGreaterThanOrEqual(170)
  })

  // ---------------------------------------------------------------------
  // CORK-DND-002
  // ---------------------------------------------------------------------
  test('CORK-DND-002: ピン止めカードはドラッグできない', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'pinned',
      isPinned: true,
      pinnedAt: '2026-05-03T00:00:00Z',
      positionX: 100,
      positionY: 100,
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockBatchPositionApi(page)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await expect(cardEl).toBeVisible()
    // 移動不可属性 + ロックアイコン
    await expect(cardEl).toHaveAttribute('data-draggable', 'false')
    await expect(
      page.getByTestId(`corkboard-card-lock-icon-${CARD_ID_MEMO}`),
    ).toBeVisible()

    await dragCardByOffset(
      page,
      `[data-testid="corkboard-card-${CARD_ID_MEMO}"]`,
      200,
      150,
    )

    // useDraggable が disabled なので API は呼ばれない
    await page.waitForTimeout(500)
    expect(tracker.bodies.length).toBe(0)
  })

  // ---------------------------------------------------------------------
  // CORK-DND-003
  // ---------------------------------------------------------------------
  test('CORK-DND-003: 共有ボードで edit_policy=ADMIN_ONLY + 非 ADMIN だとドラッグ不可', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'team-card',
      positionX: 100,
      positionY: 100,
    })
    const board = buildBoardDetail([card], {
      scopeType: 'TEAM',
      scopeId: 1,
      ownerId: null,
      editPolicy: 'ADMIN_ONLY',
    })
    await mockBoardDetail(page, board)
    const tracker = await mockBatchPositionApi(page)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await expect(cardEl).toBeVisible()
    await expect(cardEl).toHaveAttribute('data-draggable', 'false')
    // 編集権限なしロックアイコン
    await expect(
      page.getByTestId(`corkboard-card-lock-icon-${CARD_ID_MEMO}`),
    ).toBeVisible()

    await dragCardByOffset(
      page,
      `[data-testid="corkboard-card-${CARD_ID_MEMO}"]`,
      200,
      150,
    )

    await page.waitForTimeout(500)
    expect(tracker.bodies.length).toBe(0)
  })

  // ---------------------------------------------------------------------
  // CORK-DND-004
  // ---------------------------------------------------------------------
  test('CORK-DND-004: 複数カードを順にドラッグすると各々 API が呼ばれる', async ({ page }) => {
    const cardA = buildCard(CARD_ID_MEMO, {
      body: 'A',
      positionX: 50,
      positionY: 50,
    })
    const cardB = buildCard(CARD_ID_MEMO + 1, {
      body: 'B',
      positionX: 400,
      positionY: 50,
    })
    const board = buildBoardDetail([cardA, cardB])
    await mockBoardDetail(page, board)
    const tracker = await mockBatchPositionApi(page)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId(`corkboard-card-${cardA.id}`)).toBeVisible()
    await expect(page.getByTestId(`corkboard-card-${cardB.id}`)).toBeVisible()

    await dragCardByOffset(
      page,
      `[data-testid="corkboard-card-${cardA.id}"]`,
      80,
      40,
    )
    await expect.poll(() => tracker.bodies.length, { timeout: 5_000 }).toBe(1)

    await dragCardByOffset(
      page,
      `[data-testid="corkboard-card-${cardB.id}"]`,
      -120,
      90,
    )
    await expect.poll(() => tracker.bodies.length, { timeout: 5_000 }).toBe(2)

    const positionsA = tracker.bodies[0]!.positions as Array<Record<string, unknown>>
    const positionsB = tracker.bodies[1]!.positions as Array<Record<string, unknown>>
    expect(positionsA[0]!.cardId).toBe(cardA.id)
    expect(positionsB[0]!.cardId).toBe(cardB.id)
  })
})
