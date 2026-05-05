import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref } from 'vue'
import type { CorkboardDetail, CorkboardCardDetail, CorkboardGroupDetail } from '~/types/corkboard'

/**
 * F09.8 Phase E: useCorkboardSectionManagement のユニットテスト。
 *
 * カバー範囲:
 *  - CORK-SEC-UNIT-001: toggleSection — 折りたたみ状態のトグルと localStorage 保存
 *  - CORK-SEC-UNIT-002: toggleSection — 2 回トグルで元の状態（false）に戻る
 *  - CORK-SEC-UNIT-003: loadCollapsedState — localStorage から状態を復元する
 *  - CORK-SEC-UNIT-004: isSectionCollapsed — DTO の isCollapsed を初期値として使う
 *  - CORK-SEC-UNIT-005: isSectionCollapsed — localStorage 値が DTO より優先される
 *  - CORK-SEC-UNIT-006: openCreateSection — sectionEditorVisible が true、mode が create
 *  - CORK-SEC-UNIT-007: openCreateSection — sectionEditorTarget が null になる
 *  - CORK-SEC-UNIT-008: openEditSection — sectionEditorTarget が設定され、mode が edit
 *  - CORK-SEC-UNIT-009: sectionEditorVisible を false に設定すると mode と target がリセット
 *  - CORK-SEC-UNIT-010: addCardToSection — 正しい引数で addCardToGroup API を呼ぶ
 *  - CORK-SEC-UNIT-011: addCardToSection — ローカル state の sectionId を更新する
 *  - CORK-SEC-UNIT-012: addCardToSection — 旧セクションがある場合は先に removeCardFromGroup を呼ぶ
 *  - CORK-SEC-UNIT-013: removeCardFromSection — sectionId が null のカードは何もしない
 *  - CORK-SEC-UNIT-014: removeCardFromSection — API を呼んで sectionId を null にする
 *  - CORK-SEC-UNIT-015: confirmDeleteSection — 承認後に API を呼びボードの groups / cards を更新する
 *
 * モック方針:
 *  useI18n の t 関数は composable の引数として注入するため（設計メモ参照）、
 *  useI18n の vue-i18n setup 制約の問題を完全に回避できる。
 *  useToast / useErrorReport / useConfirmDialog は vi.mock でスタブ化する。
 */

// ============================================================
// モック変数
// ============================================================

const mockDeleteGroup = vi.fn()
const mockAddCardToGroup = vi.fn()
const mockRemoveCardFromGroup = vi.fn()
const mockToastAdd = vi.fn()
const mockCaptureQuiet = vi.fn()
const mockConfirmAction = vi.fn()

/** テスト用スタブ t 関数（i18n キーをそのまま返す） */
const stubT = (key: string) => key

// ============================================================
// vi.mock
// ============================================================

vi.mock('~/composables/useCorkboardApi', () => ({
  useCorkboardApi: () => ({
    deleteGroup: mockDeleteGroup,
    addCardToGroup: mockAddCardToGroup,
    removeCardFromGroup: mockRemoveCardFromGroup,
  }),
}))

vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: mockToastAdd }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mockCaptureQuiet }),
}))

vi.mock('~/composables/useConfirmDialog', () => ({
  useConfirmDialog: () => ({ confirmAction: mockConfirmAction }),
}))

// Nuxt auto-import 相当: globalThis にも同じモックを差し込む
;(globalThis as Record<string, unknown>).useCorkboardApi = () => ({
  deleteGroup: mockDeleteGroup,
  addCardToGroup: mockAddCardToGroup,
  removeCardFromGroup: mockRemoveCardFromGroup,
})
;(globalThis as Record<string, unknown>).useToast = () => ({ add: mockToastAdd })
;(globalThis as Record<string, unknown>).useErrorReport = () => ({
  captureQuiet: mockCaptureQuiet,
})
;(globalThis as Record<string, unknown>).useConfirmDialog = () => ({
  confirmAction: mockConfirmAction,
})

// ============================================================
// 動的 import（モック適用後）
// ============================================================

const { useCorkboardSectionManagement } = await import(
  '~/composables/useCorkboardSectionManagement'
)

// ============================================================
// テストヘルパ: makeSection / makeCard / makeBoard
// ============================================================

function makeSection(overrides: Partial<CorkboardGroupDetail> = {}): CorkboardGroupDetail {
  return {
    id: 1,
    corkboardId: 100,
    name: 'テストセクション',
    isCollapsed: false,
    positionX: 0,
    positionY: 0,
    width: 400,
    height: 300,
    displayOrder: 0,
    createdAt: '2026-05-01T00:00:00',
    updatedAt: '2026-05-01T00:00:00',
    ...overrides,
  }
}

function makeCard(overrides: Partial<CorkboardCardDetail> = {}): CorkboardCardDetail {
  return {
    id: 10,
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
    positionY: 100,
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
    ...overrides,
  }
}

function makeBoard(
  cards: CorkboardCardDetail[] = [],
  groups: CorkboardGroupDetail[] = [],
): CorkboardDetail {
  return {
    id: 100,
    scopeType: 'PERSONAL',
    scopeId: null,
    ownerId: 1,
    name: 'テストボード',
    backgroundStyle: 'CORK',
    editPolicy: 'ADMIN_ONLY',
    isDefault: false,
    version: 1,
    cards,
    groups,
    createdAt: '2026-05-01T00:00:00',
    updatedAt: '2026-05-01T00:00:00',
    viewerCanEdit: true,
  }
}

// ============================================================
// localStorage モック ヘルパ
// ============================================================

let localStorageMock: Record<string, string>

function stubLocalStorage() {
  localStorageMock = {}
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => localStorageMock[key] ?? null,
    setItem: (key: string, value: string) => {
      localStorageMock[key] = value
    },
    removeItem: (key: string) => {
      localStorageMock = Object.fromEntries(
        Object.entries(localStorageMock).filter(([k]) => k !== key),
      )
    },
  })
}

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardSectionManagement — 折りたたみ管理', () => {
  beforeEach(() => {
    mockDeleteGroup.mockReset()
    mockAddCardToGroup.mockReset()
    mockRemoveCardFromGroup.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
    mockConfirmAction.mockReset()
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('CORK-SEC-UNIT-001: toggleSection — 折りたたみ状態をトグルし localStorage に保存する', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { collapsedSections, toggleSection } = useCorkboardSectionManagement(
      board,
      boardId,
      stubT,
    )

    expect(collapsedSections.value[1]).toBeUndefined()
    toggleSection(1)
    expect(collapsedSections.value[1]).toBe(true)

    const stored = localStorageMock['corkboard:collapse:100']
    expect(stored).toBeDefined()
    const parsed = JSON.parse(stored) as Record<string, boolean>
    expect(parsed['1']).toBe(true)
  })

  it('CORK-SEC-UNIT-002: toggleSection — 2 回トグルで元の状態（false）に戻る', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { collapsedSections, toggleSection } = useCorkboardSectionManagement(
      board,
      boardId,
      stubT,
    )

    toggleSection(2)
    expect(collapsedSections.value[2]).toBe(true)

    toggleSection(2)
    expect(collapsedSections.value[2]).toBe(false)
  })

  it('CORK-SEC-UNIT-003: loadCollapsedState — localStorage から状態を復元する', () => {
    localStorageMock['corkboard:collapse:100'] = JSON.stringify({ '5': true, '6': false })

    const board = ref(makeBoard())
    const boardId = ref(100)
    const { collapsedSections, loadCollapsedState } = useCorkboardSectionManagement(
      board,
      boardId,
      stubT,
    )

    loadCollapsedState()

    expect(collapsedSections.value[5]).toBe(true)
    expect(collapsedSections.value[6]).toBe(false)
  })

  it('CORK-SEC-UNIT-004: isSectionCollapsed — localStorage 未設定時は DTO の isCollapsed を使う', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { isSectionCollapsed } = useCorkboardSectionManagement(board, boardId, stubT)

    const collapsedSection = makeSection({ id: 10, isCollapsed: true })
    const expandedSection = makeSection({ id: 11, isCollapsed: false })

    expect(isSectionCollapsed(collapsedSection)).toBe(true)
    expect(isSectionCollapsed(expandedSection)).toBe(false)
  })

  it('CORK-SEC-UNIT-005: isSectionCollapsed — localStorage 値が DTO より優先される', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { isSectionCollapsed, toggleSection } = useCorkboardSectionManagement(
      board,
      boardId,
      stubT,
    )

    const section = makeSection({ id: 20, isCollapsed: false })

    // まだ localStorage に値がないので DTO の false が使われる
    expect(isSectionCollapsed(section)).toBe(false)

    // toggleSection でローカル state に true が書かれる（undefined → true）
    // DTO は false だがローカル値 true が優先される
    toggleSection(20)
    expect(isSectionCollapsed(section)).toBe(true)
  })
})

describe('useCorkboardSectionManagement — セクション CRUD モーダル制御', () => {
  beforeEach(() => {
    mockDeleteGroup.mockReset()
    mockToastAdd.mockReset()
    mockConfirmAction.mockReset()
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('CORK-SEC-UNIT-006: openCreateSection — sectionEditorVisible が true、mode が create', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { sectionEditorVisible, sectionEditorMode, openCreateSection } =
      useCorkboardSectionManagement(board, boardId, stubT)

    expect(sectionEditorVisible.value).toBe(false)
    openCreateSection()

    expect(sectionEditorVisible.value).toBe(true)
    expect(sectionEditorMode.value).toBe('create')
  })

  it('CORK-SEC-UNIT-007: openCreateSection — sectionEditorTarget が null になる', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { sectionEditorTarget, openCreateSection, openEditSection } =
      useCorkboardSectionManagement(board, boardId, stubT)

    const section = makeSection({ id: 99 })
    openEditSection(section)
    expect(sectionEditorTarget.value).not.toBeNull()

    openCreateSection()
    expect(sectionEditorTarget.value).toBeNull()
  })

  it('CORK-SEC-UNIT-008: openEditSection — sectionEditorTarget が設定され、mode が edit', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { sectionEditorTarget, sectionEditorMode, openEditSection } =
      useCorkboardSectionManagement(board, boardId, stubT)

    const section = makeSection({ id: 50, name: '編集対象' })
    openEditSection(section)

    expect(sectionEditorMode.value).toBe('edit')
    expect(sectionEditorTarget.value).toEqual(section)
  })

  it('CORK-SEC-UNIT-009: sectionEditorVisible を false に設定すると mode と target がリセット', () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { sectionEditorVisible, sectionEditorMode, sectionEditorTarget, openEditSection } =
      useCorkboardSectionManagement(board, boardId, stubT)

    openEditSection(makeSection({ id: 30 }))
    expect(sectionEditorVisible.value).toBe(true)
    expect(sectionEditorMode.value).toBe('edit')

    sectionEditorVisible.value = false

    expect(sectionEditorMode.value).toBeNull()
    expect(sectionEditorTarget.value).toBeNull()
    expect(sectionEditorVisible.value).toBe(false)
  })
})

describe('useCorkboardSectionManagement — カード紐付け', () => {
  beforeEach(() => {
    mockAddCardToGroup.mockReset()
    mockRemoveCardFromGroup.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('CORK-SEC-UNIT-010: addCardToSection — 正しい引数で addCardToGroup API を呼ぶ', async () => {
    mockAddCardToGroup.mockResolvedValueOnce(undefined)

    const card = makeCard({ id: 10, sectionId: null })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { addCardToSection } = useCorkboardSectionManagement(board, boardId, stubT)

    await addCardToSection(card, 55)

    expect(mockAddCardToGroup).toHaveBeenCalledTimes(1)
    expect(mockAddCardToGroup).toHaveBeenCalledWith(100, 55, 10)
  })

  it('CORK-SEC-UNIT-011: addCardToSection — ローカル state の sectionId を更新する', async () => {
    mockAddCardToGroup.mockResolvedValueOnce(undefined)

    const card = makeCard({ id: 10, sectionId: null })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { addCardToSection } = useCorkboardSectionManagement(board, boardId, stubT)

    await addCardToSection(card, 55)

    const updated = board.value!.cards.find((c) => c.id === 10)
    expect(updated?.sectionId).toBe(55)
  })

  it('CORK-SEC-UNIT-012: addCardToSection — 旧セクションがある場合は先に removeCardFromGroup を呼ぶ', async () => {
    mockRemoveCardFromGroup.mockResolvedValueOnce(undefined)
    mockAddCardToGroup.mockResolvedValueOnce(undefined)

    const card = makeCard({ id: 10, sectionId: 33 })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { addCardToSection } = useCorkboardSectionManagement(board, boardId, stubT)

    await addCardToSection(card, 55)

    // 旧セクション(33)からの解除が先に呼ばれる
    expect(mockRemoveCardFromGroup).toHaveBeenCalledWith(100, 33, 10)
    // その後 新セクション(55)に追加
    expect(mockAddCardToGroup).toHaveBeenCalledWith(100, 55, 10)
  })

  it('CORK-SEC-UNIT-013: removeCardFromSection — sectionId が null のカードは何もしない', async () => {
    const card = makeCard({ id: 20, sectionId: null })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { removeCardFromSection } = useCorkboardSectionManagement(board, boardId, stubT)

    await removeCardFromSection(card)

    expect(mockRemoveCardFromGroup).not.toHaveBeenCalled()
  })

  it('CORK-SEC-UNIT-014: removeCardFromSection — API を呼んで sectionId を null にする', async () => {
    mockRemoveCardFromGroup.mockResolvedValueOnce(undefined)

    const card = makeCard({ id: 20, sectionId: 77 })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { removeCardFromSection } = useCorkboardSectionManagement(board, boardId, stubT)

    await removeCardFromSection(card)

    expect(mockRemoveCardFromGroup).toHaveBeenCalledWith(100, 77, 20)
    const updated = board.value!.cards.find((c) => c.id === 20)
    expect(updated?.sectionId).toBeNull()
  })
})

describe('useCorkboardSectionManagement — confirmDeleteSection', () => {
  beforeEach(() => {
    mockDeleteGroup.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
    mockConfirmAction.mockReset()
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('CORK-SEC-UNIT-015: confirmDeleteSection — 承認後に API を呼びボードの groups と cards を更新する', async () => {
    // confirmAction が即時 onAccept を呼ぶようにモック
    mockConfirmAction.mockImplementation(({ onAccept }: { onAccept: () => void }) => {
      onAccept()
    })
    mockDeleteGroup.mockResolvedValueOnce(undefined)

    const section = makeSection({ id: 5 })
    const cardInSection = makeCard({ id: 10, sectionId: 5 })
    const cardNotInSection = makeCard({ id: 11, sectionId: null })
    const board = ref(makeBoard([cardInSection, cardNotInSection], [section]))
    const boardId = ref(100)
    const { confirmDeleteSection } = useCorkboardSectionManagement(board, boardId, stubT)

    confirmDeleteSection(section)
    // doDeleteSection は async なので microtask を flush
    await new Promise((r) => setTimeout(r, 0))

    expect(mockDeleteGroup).toHaveBeenCalledWith(100, 5)
    // groups からセクションが消えている
    expect(board.value!.groups.find((g) => g.id === 5)).toBeUndefined()
    // section に属していたカードの sectionId が null になっている
    const updated = board.value!.cards.find((c) => c.id === 10)
    expect(updated?.sectionId).toBeNull()
    // 無関係なカードは変更なし
    const unchanged = board.value!.cards.find((c) => c.id === 11)
    expect(unchanged?.sectionId).toBeNull()
  })
})
