import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 Phase D リファクタリング: useCorkboardDragDrop のユニットテスト。
 *
 * テストケース:
 *  1. CORK-DND-UNIT-001: cardSizePixels が MEMO/MEDIUM カードに 200x150 を返す
 *  2. CORK-DND-UNIT-002: cardSizePixels が SMALL カードに 150x100 を返す
 *  3. CORK-DND-UNIT-003: cardSizePixels が LARGE カードに 300x200 を返す
 *  4. CORK-DND-UNIT-004: cardSizePixels が SECTION_HEADER カードに 320x40 を返す
 *  5. CORK-DND-UNIT-005: onPositionChange が楽観的更新を行う（API 成功前にローカル state が更新される）
 *  6. CORK-DND-UNIT-006: onPositionChange API 成功時、最終的な座標が新座標のまま保持される
 *  7. CORK-DND-UNIT-007: onPositionChange API エラー時にロールバックされる（旧座標に戻る）
 *  8. CORK-DND-UNIT-008: onPositionChange API エラー時に toast が severity: error で表示される
 *  9. CORK-DND-UNIT-009: boardContentSize が最低 1200x800 を返す（カードなし）
 * 10. CORK-DND-UNIT-010: boardContentSize がカードの座標を考慮して更新される
 * 11. CORK-DND-UNIT-011: board が null の場合 onPositionChange は何もしない
 * 12. CORK-DND-UNIT-012: 対象カードが見つからない場合 onPositionChange は何もしない
 */

// ============================================================
// useCorkboardApi のモック
// ============================================================
const mockBatchUpdateCardPositions = vi.fn()

vi.mock('~/composables/useCorkboardApi', () => ({
  useCorkboardApi: () => ({
    batchUpdateCardPositions: mockBatchUpdateCardPositions,
  }),
}))

// ============================================================
// useErrorReport のモック
// ============================================================
const mockCaptureQuiet = vi.fn()

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: mockCaptureQuiet,
  }),
}))

// ============================================================
// PrimeVue useToast のモック
// ============================================================
const mockToastAdd = vi.fn()

vi.mock('primevue/usetoast', () => ({
  useToast: () => ({
    add: mockToastAdd,
  }),
}))

// ============================================================
// useI18n のモック（Nuxt auto-import を模倣）
// ============================================================
const mockT = vi.fn((key: string) => key)

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: mockT }),
}))

;(globalThis as Record<string, unknown>).useI18n = () => ({ t: mockT })

// ============================================================
// テスト用ヘルパー
// ============================================================

function makeCard(over: Partial<CorkboardCardDetail> = {}): CorkboardCardDetail {
  return {
    id: 1,
    corkboardId: 100,
    sectionId: null,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: 'テストカード',
    body: null,
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: 'YELLOW',
    cardSize: 'MEDIUM',
    positionX: 100,
    positionY: 200,
    zIndex: 1,
    userNote: null,
    noteColor: null,
    autoArchiveAt: null,
    isArchived: false,
    isPinned: false,
    pinnedAt: null,
    isRefDeleted: false,
    createdBy: null,
    createdAt: '2026-05-01T00:00:00',
    updatedAt: '2026-05-01T00:00:00',
    ...over,
  }
}

function makeBoard(over: Partial<CorkboardDetail> = {}): CorkboardDetail {
  return {
    id: 100,
    scopeType: 'PERSONAL',
    scopeId: null,
    ownerId: 1,
    name: 'テストボード',
    backgroundStyle: 'CORK',
    editPolicy: 'ALL_MEMBERS',
    isDefault: false,
    version: 1,
    cards: [],
    groups: [],
    createdAt: '2026-05-01T00:00:00',
    updatedAt: '2026-05-01T00:00:00',
    viewerCanEdit: true,
    ...over,
  }
}

// ============================================================
// テスト対象を動的 import
// ============================================================
const { useCorkboardDragDrop } = await import('~/composables/useCorkboardDragDrop')

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardDragDrop', () => {
  beforeEach(() => {
    mockBatchUpdateCardPositions.mockReset()
    mockCaptureQuiet.mockReset()
    mockToastAdd.mockReset()
  })

  // ---- cardSizePixels ----

  describe('cardSizePixels()', () => {
    it('CORK-DND-UNIT-001: MEMO/MEDIUM カードに 200x150 を返す', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { cardSizePixels } = useCorkboardDragDrop(board, boardId)
      const card = makeCard({ cardType: 'MEMO', cardSize: 'MEDIUM' })

      expect(cardSizePixels(card)).toEqual({ width: 200, height: 150 })
    })

    it('CORK-DND-UNIT-002: SMALL カードに 150x100 を返す', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { cardSizePixels } = useCorkboardDragDrop(board, boardId)
      const card = makeCard({ cardType: 'MEMO', cardSize: 'SMALL' })

      expect(cardSizePixels(card)).toEqual({ width: 150, height: 100 })
    })

    it('CORK-DND-UNIT-003: LARGE カードに 300x200 を返す', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { cardSizePixels } = useCorkboardDragDrop(board, boardId)
      const card = makeCard({ cardType: 'MEMO', cardSize: 'LARGE' })

      expect(cardSizePixels(card)).toEqual({ width: 300, height: 200 })
    })

    it('CORK-DND-UNIT-004: SECTION_HEADER カードに 320x40 を返す（サイズ指定無視）', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { cardSizePixels } = useCorkboardDragDrop(board, boardId)
      const card = makeCard({ cardType: 'SECTION_HEADER', cardSize: 'LARGE' })

      expect(cardSizePixels(card)).toEqual({ width: 320, height: 40 })
    })

    it('cardSize が null の場合は MEDIUM（200x150）として扱う', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { cardSizePixels } = useCorkboardDragDrop(board, boardId)
      const card = makeCard({ cardType: 'MEMO', cardSize: null })

      expect(cardSizePixels(card)).toEqual({ width: 200, height: 150 })
    })
  })

  // ---- boardContentSize ----

  describe('boardContentSize', () => {
    it('CORK-DND-UNIT-009: カードもセクションもない場合は最低 1200x800 を返す', () => {
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [], groups: [] }))
      const boardId = ref(100)
      const { boardContentSize } = useCorkboardDragDrop(board, boardId)

      expect(boardContentSize.value).toEqual({ width: 1200, height: 800 })
    })

    it('CORK-DND-UNIT-010: カードが右下に配置されている場合はサイズが更新される', () => {
      const card = makeCard({ positionX: 1500, positionY: 1000, cardSize: 'MEDIUM' })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { boardContentSize } = useCorkboardDragDrop(board, boardId)

      // positionX(1500) + width(200) + margin(40) = 1740
      // positionY(1000) + height(150) + margin(40) = 1190
      expect(boardContentSize.value).toEqual({ width: 1740, height: 1190 })
    })

    it('board が null の場合は 1200x800 を返す', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { boardContentSize } = useCorkboardDragDrop(board, boardId)

      expect(boardContentSize.value).toEqual({ width: 1200, height: 800 })
    })
  })

  // ---- onPositionChange ----

  describe('onPositionChange()', () => {
    it('CORK-DND-UNIT-005: API 呼び出し前にローカル state が新座標へ楽観的更新される', async () => {
      const card = makeCard({ id: 1, positionX: 100, positionY: 200 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      // API は解決されないままにしておき、呼び出し直後の state を確認する
      let resolveApi!: () => void
      mockBatchUpdateCardPositions.mockReturnValueOnce(
        new Promise<void>((resolve) => {
          resolveApi = resolve
        }),
      )

      const promise = onPositionChange(1, 300, 400)

      // API 完了前でも楽観的更新が適用されている
      const found = board.value?.cards.find((c) => c.id === 1)
      expect(found?.positionX).toBe(300)
      expect(found?.positionY).toBe(400)

      // API を解決してテスト後処理をクリーンアップ
      resolveApi()
      await promise
    })

    it('CORK-DND-UNIT-006: API 成功時に新座標がそのまま保持される', async () => {
      const card = makeCard({ id: 2, positionX: 100, positionY: 200 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      mockBatchUpdateCardPositions.mockResolvedValueOnce({ data: { updatedCount: 1 } })

      await onPositionChange(2, 500, 600)

      const found = board.value?.cards.find((c) => c.id === 2)
      expect(found?.positionX).toBe(500)
      expect(found?.positionY).toBe(600)
      // 成功時はサイレント（toast なし）
      expect(mockToastAdd).not.toHaveBeenCalled()
    })

    it('CORK-DND-UNIT-007: API エラー時に旧座標へロールバックされる', async () => {
      const card = makeCard({ id: 3, positionX: 100, positionY: 200 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      mockBatchUpdateCardPositions.mockRejectedValueOnce(new Error('API Error'))

      await onPositionChange(3, 500, 600)

      const found = board.value?.cards.find((c) => c.id === 3)
      // ロールバックで旧座標に戻っている
      expect(found?.positionX).toBe(100)
      expect(found?.positionY).toBe(200)
    })

    it('CORK-DND-UNIT-008: API エラー時に toast が severity: error で表示される', async () => {
      const card = makeCard({ id: 4, positionX: 100, positionY: 200 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      mockBatchUpdateCardPositions.mockRejectedValueOnce(new Error('API Error'))

      await onPositionChange(4, 500, 600)

      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'error' }),
      )
      expect(mockCaptureQuiet).toHaveBeenCalledTimes(1)
    })

    it('CORK-DND-UNIT-011: board が null の場合は何もしない', async () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      await onPositionChange(1, 300, 400)

      expect(mockBatchUpdateCardPositions).not.toHaveBeenCalled()
    })

    it('CORK-DND-UNIT-012: 対象カードが見つからない場合は何もしない', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      await onPositionChange(999, 300, 400)

      expect(mockBatchUpdateCardPositions).not.toHaveBeenCalled()
    })

    it('zIndex が null のカードは zIndex=1 として API に送信される', async () => {
      const card = makeCard({ id: 5, positionX: 100, positionY: 200, zIndex: null })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { onPositionChange } = useCorkboardDragDrop(board, boardId)

      mockBatchUpdateCardPositions.mockResolvedValueOnce({ data: { updatedCount: 1 } })

      await onPositionChange(5, 300, 400)

      expect(mockBatchUpdateCardPositions).toHaveBeenCalledWith(100, [
        { cardId: 5, positionX: 300, positionY: 400, zIndex: 1 },
      ])
    })
  })
})
