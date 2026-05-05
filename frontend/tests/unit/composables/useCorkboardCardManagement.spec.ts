import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 Phase C リファクタリング: useCorkboardCardManagement のユニットテスト。
 *
 * テストケース:
 *  1. CORK-CARD-UNIT-001: openCreate() で editorVisible が true になり editorMode が 'create' になる
 *  2. CORK-CARD-UNIT-002: openEdit() で editorTarget が設定され editorMode が 'edit' になる
 *  3. CORK-CARD-UNIT-003: editorVisible を false にセットするとモーダルが閉じ editorTarget がリセットされる
 *  4. CORK-CARD-UNIT-004: doDelete() で board.value からカードが除去される（API モック）
 *  5. CORK-CARD-UNIT-005: doDelete() 成功時に toast.add が success で呼ばれる
 *  6. CORK-CARD-UNIT-006: doDelete() API 失敗時にカードが除去されず toast.add が error で呼ばれる
 *  7. CORK-CARD-UNIT-007: toggleArchive() 成功時にローカル state が res.data で置換される
 *  8. CORK-CARD-UNIT-008: toggleArchive() API 失敗時にローカル state が変化しない
 *  9. CORK-CARD-UNIT-009: editorDefaultPosition が既存カードを考慮した座標を返す
 * 10. CORK-CARD-UNIT-010: confirmDelete() が confirmAction を呼び出す
 */

// ============================================================
// useCorkboardApi のモック
// ============================================================
const mockDeleteCard = vi.fn()
const mockArchiveCard = vi.fn()

vi.mock('~/composables/useCorkboardApi', () => ({
  useCorkboardApi: () => ({
    deleteCard: mockDeleteCard,
    archiveCard: mockArchiveCard,
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

// Nuxt auto-import として参照される useI18n をグローバルに注入
;(globalThis as Record<string, unknown>).useI18n = () => ({ t: mockT })

// ============================================================
// useConfirmDialog のモック
// ============================================================
const mockConfirmAction = vi.fn()
vi.mock('~/composables/useConfirmDialog', () => ({
  useConfirmDialog: () => ({ confirmAction: mockConfirmAction }),
}))
;(globalThis as Record<string, unknown>).useConfirmDialog = () => ({
  confirmAction: mockConfirmAction,
})

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
    body: 'テスト本文',
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
const { useCorkboardCardManagement } = await import(
  '~/composables/useCorkboardCardManagement'
)

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardCardManagement', () => {
  beforeEach(() => {
    mockDeleteCard.mockReset()
    mockArchiveCard.mockReset()
    mockCaptureQuiet.mockReset()
    mockToastAdd.mockReset()
    mockConfirmAction.mockReset()
  })

  // ---- モーダル開閉・モード制御 ----

  describe('openCreate()', () => {
    it('CORK-CARD-UNIT-001: editorVisible が true になり editorMode が create になる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorVisible, editorMode, openCreate } = useCorkboardCardManagement(board, boardId, (k) => k)

      openCreate()

      expect(editorVisible.value).toBe(true)
      expect(editorMode.value).toBe('create')
    })

    it('openCreate() を呼ぶと editorTarget が null になる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorTarget, openCreate } = useCorkboardCardManagement(board, boardId, (k) => k)

      openCreate()

      expect(editorTarget.value).toBeNull()
    })
  })

  describe('openEdit()', () => {
    it('CORK-CARD-UNIT-002: editorTarget が設定され editorMode が edit になる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorTarget, editorMode, editorVisible, openEdit } = useCorkboardCardManagement(board, boardId, (k) => k)
      const card = makeCard({ id: 42 })

      openEdit(card)

      expect(editorMode.value).toBe('edit')
      expect(editorVisible.value).toBe(true)
      expect(editorTarget.value).toEqual(card)
    })
  })

  describe('editorVisible setter', () => {
    it('CORK-CARD-UNIT-003: false にセットするとモーダルが閉じ editorTarget がリセットされる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorVisible, editorMode, editorTarget, openEdit } = useCorkboardCardManagement(board, boardId, (k) => k)
      const card = makeCard()

      openEdit(card)
      expect(editorVisible.value).toBe(true)

      editorVisible.value = false

      expect(editorVisible.value).toBe(false)
      expect(editorMode.value).toBeNull()
      expect(editorTarget.value).toBeNull()
    })
  })

  describe('editorDefaultPosition', () => {
    it('CORK-CARD-UNIT-009: 既存カードがない場合は { x: 40, y: 40 } を返す', () => {
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [] }))
      const boardId = ref(100)
      const { editorDefaultPosition } = useCorkboardCardManagement(board, boardId, (k) => k)

      expect(editorDefaultPosition.value).toEqual({ x: 40, y: 40 })
    })

    it('既存カードがある場合はそのカードの右下方向にずれた座標を返す', () => {
      const board = ref<CorkboardDetail | null>(
        makeBoard({
          cards: [makeCard({ positionX: 500, positionY: 400 })],
        }),
      )
      const boardId = ref(100)
      const { editorDefaultPosition } = useCorkboardCardManagement(board, boardId, (k) => k)

      // positionX + 40 = 540, positionY + 40 = 440
      expect(editorDefaultPosition.value).toEqual({ x: 540, y: 440 })
    })

    it('座標の上限 (x: 1000, y: 600) を超えない', () => {
      const board = ref<CorkboardDetail | null>(
        makeBoard({
          cards: [makeCard({ positionX: 2000, positionY: 1500 })],
        }),
      )
      const boardId = ref(100)
      const { editorDefaultPosition } = useCorkboardCardManagement(board, boardId, (k) => k)

      expect(editorDefaultPosition.value).toEqual({ x: 1000, y: 600 })
    })
  })

  // ---- カード削除 ----

  describe('doDelete()', () => {
    it('CORK-CARD-UNIT-004: API 成功時に board.value からカードが除去される', async () => {
      const card = makeCard({ id: 5 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockDeleteCard.mockResolvedValueOnce(undefined)

      await doDelete(card)

      expect(board.value?.cards).toHaveLength(0)
      expect(mockDeleteCard).toHaveBeenCalledWith(100, 5)
    })

    it('CORK-CARD-UNIT-005: 削除成功時に toast が severity: success で表示される', async () => {
      const card = makeCard({ id: 5 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockDeleteCard.mockResolvedValueOnce(undefined)

      await doDelete(card)

      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'success' }),
      )
    })

    it('CORK-CARD-UNIT-006: API 失敗時にカードが除去されず toast が severity: error で表示される', async () => {
      const card = makeCard({ id: 5 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockDeleteCard.mockRejectedValueOnce(new Error('API Error'))

      await doDelete(card)

      // カードはまだ残っている
      expect(board.value?.cards).toHaveLength(1)
      // エラートーストが表示される
      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'error' }),
      )
      // captureQuiet でエラーが報告される
      expect(mockCaptureQuiet).toHaveBeenCalledTimes(1)
    })
  })

  describe('confirmDelete()', () => {
    it('CORK-CARD-UNIT-010: confirmAction が呼ばれ onAccept に doDelete が渡される', () => {
      const card = makeCard({ id: 5 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { confirmDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      confirmDelete(card)

      expect(mockConfirmAction).toHaveBeenCalledTimes(1)
      expect(mockConfirmAction).toHaveBeenCalledWith(
        expect.objectContaining({
          onAccept: expect.any(Function),
        }),
      )
    })
  })

  // ---- アーカイブ切り替え ----

  describe('toggleArchive()', () => {
    it('CORK-CARD-UNIT-007: 成功時にローカル state が res.data（更新済みカード）で置換される', async () => {
      const card = makeCard({ id: 7, isArchived: false })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 7, isArchived: true })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      await toggleArchive(card)

      const found = board.value?.cards.find((c) => c.id === 7)
      expect(found?.isArchived).toBe(true)
      expect(mockArchiveCard).toHaveBeenCalledWith(100, 7, true)
    })

    it('アンアーカイブ操作で archived=false が API に渡される', async () => {
      const card = makeCard({ id: 8, isArchived: true })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 8, isArchived: false })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      await toggleArchive(card)

      expect(mockArchiveCard).toHaveBeenCalledWith(100, 8, false)
    })

    it('CORK-CARD-UNIT-008: API 失敗時にローカル state が変化しない', async () => {
      const card = makeCard({ id: 9, isArchived: false })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockArchiveCard.mockRejectedValueOnce(new Error('API Error'))

      await toggleArchive(card)

      // アーカイブ状態が変化していない
      const found = board.value?.cards.find((c) => c.id === 9)
      expect(found?.isArchived).toBe(false)
      // エラートーストが表示される
      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'error' }),
      )
      // captureQuiet でエラーが報告される
      expect(mockCaptureQuiet).toHaveBeenCalledTimes(1)
    })

    it('アーカイブ成功時に toast が severity: success で表示される', async () => {
      const card = makeCard({ id: 10, isArchived: false })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 10, isArchived: true })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      await toggleArchive(card)

      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'success' }),
      )
    })

    it('board が null の場合は何もしない', async () => {
      const card = makeCard({ id: 11, isArchived: false })
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 11, isArchived: true })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      // board が null でも例外が発生しないこと
      await expect(toggleArchive(card)).resolves.toBeUndefined()
    })
  })
})
