import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  PERSONAL_BOARD_ID,
  buildBoardDetail,
  buildCard,
  mockBoardDetail,
  mockCardCrudApis,
  mockCatchAllApis,
  setupOwnerAuth,
} from './_helpers'

/**
 * F09.8 件3' (V9.098): ピン止め時付箋メモ機能 E2E。
 *
 * 個人ボード詳細ページ ({@code /corkboard/{boardId}}) における 📌 ボタン挙動を検証する。
 *
 * シナリオ:
 *  - CORK-PIN-004: 📌 押下 → Popover 表示 → メモ入力 → 色選択 → 保存 →
 *                   PATCH /pin に userNote/noteColor 含めて送られる
 *  - CORK-PIN-005: 既ピンカード再押下 → アンピン（Popover 出ない、即時）
 *  - CORK-PIN-006: 色未選択時はデフォルト = colorLabel と一致する noteColor が送られる
 */

const CARD_ID_UNPINNED = 301
const CARD_ID_PINNED = 302

test.describe('CORK-PIN-NOTE: ピン止め時付箋メモ (F09.8 件3\')', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  test('CORK-PIN-004: 📌 → Popover → メモ入力 → 色選択 → 保存で userNote/noteColor が送られる', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_UNPINNED, {
      cardType: 'MEMO',
      title: 'ピン候補カード',
      body: '本文',
      colorLabel: 'YELLOW',
      isPinned: false,
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onPinnedCard: {
        id: CARD_ID_UNPINNED,
        isPinned: true,
        pinnedAt: '2026-05-03T14:23:00Z',
      },
    })

    await page.goto(`/corkboard/${PERSONAL_BOARD_ID}`)
    await waitForHydration(page)

    // 📌 ボタンを押下
    await expect(page.getByText('ピン候補カード')).toBeVisible({ timeout: 10_000 })
    await page
      .locator(`[data-testid="corkboard-card-pin-button-${CARD_ID_UNPINNED}"]`)
      .click()

    // Popover が表示される
    const popover = page.locator(
      `[data-testid="pin-note-popover-${CARD_ID_UNPINNED}"]`,
    )
    await expect(popover).toBeVisible({ timeout: 5_000 })

    // 付箋メモ入力
    const textarea = page.locator(
      `[data-testid="pin-note-textarea-${CARD_ID_UNPINNED}"]`,
    )
    await textarea.fill('重要: 来週までに対応')

    // 色選択（BLUE）
    await page.locator('[data-testid="pin-note-color-BLUE"]').click()

    // 「ピン止めする」確定
    await page
      .locator(`[data-testid="pin-note-confirm-${CARD_ID_UNPINNED}"]`)
      .click()

    // PATCH /pin に userNote/noteColor が含まれて送られる
    await expect.poll(() => tracker.pinBody, { timeout: 5_000 }).not.toBeNull()
    expect(tracker.pinBody).toMatchObject({
      isPinned: true,
      userNote: '重要: 来週までに対応',
      noteColor: 'BLUE',
    })
  })

  test('CORK-PIN-005: 既ピンカード再押下 → アンピン（Popover 出ない・即時）', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_PINNED, {
      cardType: 'MEMO',
      title: 'ピン済みカード',
      body: '本文',
      colorLabel: 'GREEN',
      userNote: '既存メモ',
      noteColor: 'GREEN',
      isPinned: true,
      pinnedAt: '2026-05-01T12:00:00Z',
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onPinnedCard: {
        id: CARD_ID_PINNED,
        isPinned: false,
        pinnedAt: null,
      },
    })

    await page.goto(`/corkboard/${PERSONAL_BOARD_ID}`)
    await waitForHydration(page)

    await expect(page.getByText('ピン済みカード')).toBeVisible({ timeout: 10_000 })
    await page
      .locator(`[data-testid="corkboard-card-pin-button-${CARD_ID_PINNED}"]`)
      .click()

    // Popover は出ない
    await expect(
      page.locator(`[data-testid="pin-note-popover-${CARD_ID_PINNED}"]`),
    ).toHaveCount(0)

    // PATCH /pin が isPinned=false で呼ばれる（userNote/noteColor は送られない or undefined）
    await expect.poll(() => tracker.pinBody, { timeout: 5_000 }).not.toBeNull()
    const body = tracker.pinBody as Record<string, unknown>
    expect(body.isPinned).toBe(false)
    expect(body.userNote).toBeUndefined()
    expect(body.noteColor).toBeUndefined()
  })

  test('CORK-PIN-006: 色未選択時はデフォルト = カードの colorLabel が noteColor として送られる', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_UNPINNED, {
      cardType: 'MEMO',
      title: '色デフォルト確認カード',
      body: '本文',
      colorLabel: 'PURPLE',
      isPinned: false,
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onPinnedCard: {
        id: CARD_ID_UNPINNED,
        isPinned: true,
        pinnedAt: '2026-05-03T15:00:00Z',
      },
    })

    await page.goto(`/corkboard/${PERSONAL_BOARD_ID}`)
    await waitForHydration(page)

    await expect(page.getByText('色デフォルト確認カード')).toBeVisible({
      timeout: 10_000,
    })
    await page
      .locator(`[data-testid="corkboard-card-pin-button-${CARD_ID_UNPINNED}"]`)
      .click()

    // Popover が開いている
    await expect(
      page.locator(`[data-testid="pin-note-popover-${CARD_ID_UNPINNED}"]`),
    ).toBeVisible({ timeout: 5_000 })

    // 色を変更せずそのまま「ピン止めする」を押す
    await page
      .locator(`[data-testid="pin-note-confirm-${CARD_ID_UNPINNED}"]`)
      .click()

    // PATCH /pin に noteColor=PURPLE (= colorLabel) が含まれて送られる
    await expect.poll(() => tracker.pinBody, { timeout: 5_000 }).not.toBeNull()
    const body = tracker.pinBody as Record<string, unknown>
    expect(body.isPinned).toBe(true)
    expect(body.noteColor).toBe('PURPLE')
  })
})
