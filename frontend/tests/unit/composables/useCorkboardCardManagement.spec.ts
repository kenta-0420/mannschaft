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

function makeCard(over: {
  id?: number
  positionX?: number
  positionY?: number
  isArchived?: boolean
} = {}): CorkboardCardDetail {
  return {
    id: over.id ?? 1,
    corkboardId: 100,
    reference: {
      sectionId: null,
      cardType: 'MEMO',
      referenceType: null,
      referenceId: null,
      contentSnapshot: null,
    },
    content: {
      title: 'テストカード',
      body: 'テスト本文',
      url: null,
      ogTitle: null,
      ogImageUrl: null,
      ogDescription: null,
    },
    layout: {
      positionX: over.positionX ?? 100,
      positionY: over.positionY ?? 200,
      zIndex: 1,
      cardSize: 'MEDIUM',
    },
    style: {
      colorLabel: 'YELLOW',
      noteColor: null,
    },
    state: {
      isArchived: over.isArchived ?? false,
      isPinned: false,
      pinnedAt: null,
      autoArchiveAt: null,
      isRefDeleted: false,
    },
    audit: {
      userNote: null,
      createdBy: null,
      createdAt: '2026-05-01T00:00:00',
      updatedAt: '2026-05-01T00:00:00',
    },
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
      expect(found?.state?.isArchived).toBe(true)
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
      expect(found?.state?.isArchived).toBe(false)
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

  // ---- エッジケース追加テスト ----

  describe('doDelete() — エッジケース', () => {
    it('CORK-CARD-UNIT-011: API エラー時に captureQuiet がエラーを記録する', async () => {
      const card = makeCard({ id: 20 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      const apiError = new Error('削除失敗')
      mockDeleteCard.mockRejectedValueOnce(apiError)

      await doDelete(card)

      expect(mockCaptureQuiet).toHaveBeenCalledWith(
        apiError,
        expect.objectContaining({ context: expect.any(String) }),
      )
    })

    it('CORK-CARD-UNIT-012: API エラー時に toast が severity: error で life: 3500 で表示される', async () => {
      const card = makeCard({ id: 21 })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockDeleteCard.mockRejectedValueOnce(new Error('API Error'))

      await doDelete(card)

      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'error', life: 3500 }),
      )
    })

    it('CORK-CARD-UNIT-013: board が null のときに doDelete を呼んでも例外が発生しない', async () => {
      const card = makeCard({ id: 22 })
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { doDelete } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockDeleteCard.mockResolvedValueOnce(undefined)

      await expect(doDelete(card)).resolves.toBeUndefined()
    })
  })

  describe('toggleArchive() — isArchived=true のカードに対するアーカイブ操作', () => {
    it('CORK-CARD-UNIT-014: isArchived=true のカードに対して unarchive リクエスト（false）を送る', async () => {
      const card = makeCard({ id: 30, isArchived: true })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 30, isArchived: false })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      await toggleArchive(card)

      expect(mockArchiveCard).toHaveBeenCalledWith(100, 30, false)
    })

    it('CORK-CARD-UNIT-015: isArchived=false のカードに対して archive リクエスト（true）を送る', async () => {
      const card = makeCard({ id: 31, isArchived: false })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      const updatedCard = makeCard({ id: 31, isArchived: true })
      mockArchiveCard.mockResolvedValueOnce({ data: updatedCard })

      await toggleArchive(card)

      expect(mockArchiveCard).toHaveBeenCalledWith(100, 31, true)
    })

    it('CORK-CARD-UNIT-016: アーカイブ失敗時に error toast の summary キーが unarchiveError キーを含む', async () => {
      const card = makeCard({ id: 32, isArchived: true })
      const board = ref<CorkboardDetail | null>(makeBoard({ cards: [card] }))
      const boardId = ref(100)
      const { toggleArchive } = useCorkboardCardManagement(board, boardId, (k) => k)

      mockArchiveCard.mockRejectedValueOnce(new Error('API Error'))

      await toggleArchive(card)

      expect(mockToastAdd).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'error', summary: 'corkboard.toast.unarchiveError' }),
      )
    })
  })

  describe('openEdit() / openCreate() — editorMode 状態確認', () => {
    it('CORK-CARD-UNIT-017: openEdit() 後に editorMode が edit になる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorMode, openEdit } = useCorkboardCardManagement(board, boardId, (k) => k)
      const card = makeCard({ id: 40 })

      openEdit(card)

      expect(editorMode.value).toBe('edit')
    })

    it('CORK-CARD-UNIT-018: openCreate() 後に editorMode が create になる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorMode, openCreate } = useCorkboardCardManagement(board, boardId, (k) => k)

      openCreate()

      expect(editorMode.value).toBe('create')
    })

    it('CORK-CARD-UNIT-019: editorVisible が false のとき editor が閉じている（editorMode が null）', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorMode, editorVisible } = useCorkboardCardManagement(board, boardId, (k) => k)

      // 初期状態では editorVisible が false であること
      expect(editorVisible.value).toBe(false)
      expect(editorMode.value).toBeNull()
    })

    it('CORK-CARD-UNIT-020: openEdit() 後に editorVisible を false にするとエディタが閉じる', () => {
      const board = ref<CorkboardDetail | null>(null)
      const boardId = ref(100)
      const { editorMode, editorVisible, openEdit } = useCorkboardCardManagement(
        board,
        boardId,
        (k) => k,
      )
      const card = makeCard({ id: 41 })

      openEdit(card)
      expect(editorVisible.value).toBe(true)

      editorVisible.value = false

      expect(editorVisible.value).toBe(false)
      expect(editorMode.value).toBeNull()
    })
  })
})
