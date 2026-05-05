import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8.1 / F09.8 件3': useCorkboardPinManagement のユニットテスト。
 *
 * カバー範囲:
 *  - CORK-PIN-UNIT-001: togglePin — ピン済カードは即時 doTogglePin(false) が呼ばれる
 *  - CORK-PIN-UNIT-002: togglePin — 未ピンカードは pinPopoverVisible が true になる
 *  - CORK-PIN-UNIT-003: togglePin — 未ピンカードは pinPopoverTargetCard が設定される
 *  - CORK-PIN-UNIT-004: onPinNoteConfirm — pinPopoverTargetCard が null のときは何もしない
 *  - CORK-PIN-UNIT-005: onPinNoteConfirm — API を正しい引数（userNote / noteColor）で呼ぶ
 *  - CORK-PIN-UNIT-006: doTogglePin(true) — 成功時にローカル state の isPinned / userNote / noteColor を更新
 *  - CORK-PIN-UNIT-007: doTogglePin(true) — 成功後に pinPopoverTargetCard を null にリセット
 *  - CORK-PIN-UNIT-008: doTogglePin(false) — 成功時に isPinned を false に更新する
 *  - CORK-PIN-UNIT-009: doTogglePin(true) — 上限エラー（status 409）で warn toast が表示される
 *  - CORK-PIN-UNIT-010: doTogglePin(true) — 上限エラー（code CORKBOARD_013）で warn toast が表示される
 *  - CORK-PIN-UNIT-011: doTogglePin — 一般エラー時に error toast が表示される（warn は出ない）
 *  - CORK-PIN-UNIT-012: doTogglePin(false) — アンピン後は pinPopoverTargetCard をリセットしない
 *
 * モック方針:
 *  useI18n の t 関数は composable の引数として注入するため（設計メモ参照）、
 *  useI18n の vue-i18n setup 制約の問題を完全に回避できる。
 *  useToast / useErrorReport は vi.mock でスタブ化する。
 */

// ============================================================
// モック変数
// ============================================================

const mockTogglePinCard = vi.fn()
const mockToastAdd = vi.fn()
const mockCaptureQuiet = vi.fn()

/** テスト用スタブ t 関数（i18n キーをそのまま返す） */
const stubT = (key: string) => key

// ============================================================
// vi.mock
// ============================================================

vi.mock('~/composables/useCorkboardApi', () => ({
  useCorkboardApi: () => ({
    togglePinCard: mockTogglePinCard,
  }),
}))

vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: mockToastAdd }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mockCaptureQuiet }),
}))

// Nuxt auto-import 相当: globalThis にも同じモックを差し込む
;(globalThis as Record<string, unknown>).useCorkboardApi = () => ({
  togglePinCard: mockTogglePinCard,
})
;(globalThis as Record<string, unknown>).useToast = () => ({ add: mockToastAdd })
;(globalThis as Record<string, unknown>).useErrorReport = () => ({
  captureQuiet: mockCaptureQuiet,
})

// ============================================================
// 動的 import（モック適用後）
// ============================================================

const { useCorkboardPinManagement } = await import('~/composables/useCorkboardPinManagement')

// ============================================================
// テストヘルパ
// ============================================================

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

function makeBoard(cards: CorkboardCardDetail[] = []): CorkboardDetail {
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
    groups: [],
    createdAt: '2026-05-01T00:00:00',
    updatedAt: '2026-05-01T00:00:00',
    viewerCanEdit: true,
  }
}

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardPinManagement — togglePin', () => {
  beforeEach(() => {
    mockTogglePinCard.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
  })

  it('CORK-PIN-UNIT-001: ピン済カードは即時 API (isPinned=false) が呼ばれる', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: false, pinnedAt: null },
    })

    const card = makeCard({ id: 10, isPinned: true })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { togglePin } = useCorkboardPinManagement(board, boardId, stubT)

    togglePin(card)
    await new Promise((r) => setTimeout(r, 0))

    expect(mockTogglePinCard).toHaveBeenCalledTimes(1)
    expect(mockTogglePinCard).toHaveBeenCalledWith(100, 10, false, undefined, undefined)
  })

  it('CORK-PIN-UNIT-002: 未ピンカードは pinPopoverVisible が true になる', () => {
    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { togglePin, pinPopoverVisible } = useCorkboardPinManagement(board, boardId, stubT)

    expect(pinPopoverVisible.value).toBe(false)
    togglePin(card)
    expect(pinPopoverVisible.value).toBe(true)
    // API は呼ばれない（Popover を開くだけ）
    expect(mockTogglePinCard).not.toHaveBeenCalled()
  })

  it('CORK-PIN-UNIT-003: 未ピンカードは pinPopoverTargetCard が設定される', () => {
    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { togglePin, pinPopoverTargetCard } = useCorkboardPinManagement(board, boardId, stubT)

    expect(pinPopoverTargetCard.value).toBeNull()
    togglePin(card)
    expect(pinPopoverTargetCard.value).toEqual(card)
  })
})

describe('useCorkboardPinManagement — onPinNoteConfirm', () => {
  beforeEach(() => {
    mockTogglePinCard.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
  })

  it('CORK-PIN-UNIT-004: pinPopoverTargetCard が null のときは何もしない', async () => {
    const board = ref(makeBoard())
    const boardId = ref(100)
    const { onPinNoteConfirm } = useCorkboardPinManagement(board, boardId, stubT)

    onPinNoteConfirm('メモ', 'YELLOW')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockTogglePinCard).not.toHaveBeenCalled()
  })

  it('CORK-PIN-UNIT-005: API を正しい引数（userNote / noteColor）で呼ぶ', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: true, pinnedAt: '2026-05-01T00:00:00' },
    })

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { togglePin, onPinNoteConfirm } = useCorkboardPinManagement(board, boardId, stubT)

    // Popover を開く
    togglePin(card)
    // Popover 確定
    onPinNoteConfirm('仕事メモ', 'BLUE')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockTogglePinCard).toHaveBeenCalledWith(100, 10, true, '仕事メモ', 'BLUE')
  })
})

describe('useCorkboardPinManagement — doTogglePin', () => {
  beforeEach(() => {
    mockTogglePinCard.mockReset()
    mockToastAdd.mockReset()
    mockCaptureQuiet.mockReset()
  })

  it('CORK-PIN-UNIT-006: doTogglePin(true) 成功時にローカル state の isPinned / userNote / noteColor を更新する', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: true, pinnedAt: '2026-05-01T00:00:00' },
    })

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    await doTogglePin(card, true, '覚書', 'YELLOW')

    const updated = board.value!.cards.find((c) => c.id === 10)
    expect(updated?.isPinned).toBe(true)
    expect(updated?.userNote).toBe('覚書')
    expect(updated?.noteColor).toBe('YELLOW')
  })

  it('CORK-PIN-UNIT-007: doTogglePin(true) 成功後に pinPopoverTargetCard が null にリセット', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: true, pinnedAt: '2026-05-01T00:00:00' },
    })

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { togglePin, pinPopoverTargetCard, doTogglePin } = useCorkboardPinManagement(
      board,
      boardId,
      stubT,
    )

    // Popover を開いて targetCard を設定
    togglePin(card)
    expect(pinPopoverTargetCard.value).not.toBeNull()

    await doTogglePin(card, true, null, null)

    expect(pinPopoverTargetCard.value).toBeNull()
  })

  it('CORK-PIN-UNIT-008: doTogglePin(false) 成功時に isPinned を false に更新する', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: false, pinnedAt: null },
    })

    const card = makeCard({ id: 10, isPinned: true, pinnedAt: '2026-05-01T00:00:00' })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    await doTogglePin(card, false, null, null)

    const updated = board.value!.cards.find((c) => c.id === 10)
    expect(updated?.isPinned).toBe(false)
    expect(updated?.pinnedAt).toBeNull()
  })

  it('CORK-PIN-UNIT-009: 上限エラー（status 409）で warn toast が表示される', async () => {
    const err = { status: 409, message: 'pin limit' }
    mockTogglePinCard.mockRejectedValueOnce(err)

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    await doTogglePin(card, true, null, null)

    const warnCall = mockToastAdd.mock.calls.find(
      (call) => (call[0] as { severity: string }).severity === 'warn',
    )
    expect(warnCall).toBeDefined()
    const errorCall = mockToastAdd.mock.calls.find(
      (call) => (call[0] as { severity: string }).severity === 'error',
    )
    expect(errorCall).toBeUndefined()
  })

  it('CORK-PIN-UNIT-010: 上限エラー（code CORKBOARD_013）で warn toast が表示される', async () => {
    const err = { status: 400, data: { code: 'CORKBOARD_013' } }
    mockTogglePinCard.mockRejectedValueOnce(err)

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    await doTogglePin(card, true, null, null)

    const warnCall = mockToastAdd.mock.calls.find(
      (call) => (call[0] as { severity: string }).severity === 'warn',
    )
    expect(warnCall).toBeDefined()
  })

  it('CORK-PIN-UNIT-011: 一般エラー時に error toast が表示される（warn は出ない）', async () => {
    mockTogglePinCard.mockRejectedValueOnce(new Error('ネットワークエラー'))

    const card = makeCard({ id: 10, isPinned: false })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    await doTogglePin(card, true, null, null)

    const errorCall = mockToastAdd.mock.calls.find(
      (call) => (call[0] as { severity: string }).severity === 'error',
    )
    expect(errorCall).toBeDefined()
    const warnCall = mockToastAdd.mock.calls.find(
      (call) => (call[0] as { severity: string }).severity === 'warn',
    )
    expect(warnCall).toBeUndefined()
  })

  it('CORK-PIN-UNIT-012: doTogglePin(false) — アンピン後は pinPopoverTargetCard をリセットしない', async () => {
    mockTogglePinCard.mockResolvedValueOnce({
      data: { id: 10, isPinned: false, pinnedAt: null },
    })

    const card = makeCard({ id: 10, isPinned: true })
    const board = ref(makeBoard([card]))
    const boardId = ref(100)
    const { pinPopoverTargetCard, doTogglePin } = useCorkboardPinManagement(board, boardId, stubT)

    // 手動で targetCard を設定（Popover が開いていた状態を模倣）
    pinPopoverTargetCard.value = card

    await doTogglePin(card, false, null, null)

    // アンピン(next=false)なので finally ブロックの reset は走らない
    expect(pinPopoverTargetCard.value).not.toBeNull()
  })
})
