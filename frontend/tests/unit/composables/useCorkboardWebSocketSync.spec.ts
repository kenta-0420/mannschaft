/**
 * F09.8 Phase F useCorkboardWebSocketSync — WebSocket 購読管理・イベントハンドラのユニットテスト。
 *
 * テストケース:
 *  1. CORK-SYNC-001: isSharedBoard が false（PERSONAL）のとき connect が呼ばれないこと
 *  2. CORK-SYNC-002: isSharedBoard が true（TEAM）のとき connect が呼ばれること
 *  3. CORK-SYNC-002b: isSharedBoard が true（ORGANIZATION）のとき connect が呼ばれること
 *  4. CORK-SYNC-003: CARD_CREATED — board.value.cards に新規追加されること
 *  5. CORK-SYNC-004: CARD_CREATED — 既に同 id のカードがあれば置換されること（多重 push 防止）
 *  6. CORK-SYNC-005: CARD_UPDATED — board.value.cards の該当カードが更新されること
 *  7. CORK-SYNC-006: CARD_DELETED — board.value.cards から削除されること
 *  8. CORK-SYNC-007: SECTION_CREATED — board.value.groups に追加されること
 *  9. CORK-SYNC-008: SECTION_DELETED — board.value.groups から削除され、関連カードの sectionId が null になること
 * 10. CORK-SYNC-009a: CARD_MOVED — カードが置換されること
 * 11. CORK-SYNC-009b: CARD_ARCHIVED — カードが置換されること
 * 12. CORK-SYNC-010: SECTION_UPDATED — セクションが置換されること
 * 13. CORK-SYNC-011: CARD_SECTION_CHANGED — 該当カードが更新されること
 * 14. CORK-SYNC-012: BOARD_DELETED — フルリロード関数が呼ばれること
 * 15. CORK-SYNC-013: 未知の eventType — フルリロード関数が呼ばれること
 * 16. CORK-SYNC-014: card ペイロードが null のとき CARD_CREATED がフルリロードにフォールバックすること
 * 17. CORK-SYNC-015: onUnmounted で disconnect が呼ばれること
 * 18. CORK-SYNC-016: board.value が null のとき handleCorkboardEvent は何もしないこと
 * 19. CORK-SYNC-017: SECTION_DELETED — sectionId が null のとき フルリロードにフォールバックすること
 * 20. CORK-SYNC-018: setReloadFn で注入した関数が BOARD_DELETED で呼ばれること
 *
 * モック方針:
 *  - `useCorkboardEventListener` を vi.mock で差し替え、connect / disconnect を追跡する。
 *  - `watch` の動作確認は `mountSuspended` + `defineComponent` でコンポーネントコンテキストを確保する。
 *  - `handleCorkboardEvent` を直接呼び出してイベント処理をテストする。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref, defineComponent, h, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { CorkboardDetail, CorkboardCardDetail, CorkboardGroupDetail, CorkboardEventPayload } from '~/types/corkboard'
import { useCorkboardWebSocketSync } from '~/composables/useCorkboardWebSocketSync'

// ============================================================
// useCorkboardEventListener のモック
// ============================================================

// vi.mock はホイストされるため、factory 内では外部変数を参照できない。
// vi.hoisted を使って事前にモック関数を定義する。
const { mockConnect, mockDisconnect, mockUseCorkboardEventListener } = vi.hoisted(() => {
  const mockConnect = vi.fn()
  const mockDisconnect = vi.fn()
  const mockUseCorkboardEventListener = vi.fn(() => ({
    connect: mockConnect,
    disconnect: mockDisconnect,
  }))
  return { mockConnect, mockDisconnect, mockUseCorkboardEventListener }
})

vi.mock('~/composables/useCorkboardEventListener', () => ({
  useCorkboardEventListener: mockUseCorkboardEventListener,
}))

// Nuxt auto-import 相当: globalThis にも同じモックを差し込む（composable が自動 import 経由で解決するため）
;(globalThis as Record<string, unknown>).useCorkboardEventListener = mockUseCorkboardEventListener

// ============================================================
// テスト用ヘルパー
// ============================================================

function makeBoard(
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION' = 'TEAM',
  overrides: Partial<CorkboardDetail> = {},
): CorkboardDetail {
  return {
    id: 42,
    scopeType,
    scopeId: scopeType === 'PERSONAL' ? null : 1,
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
    ...overrides,
  }
}

function makeCard(id: number, overrides: Partial<CorkboardCardDetail> = {}): CorkboardCardDetail {
  return {
    id,
    corkboardId: 42,
    sectionId: null,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: `カード${id}`,
    body: null,
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: 'YELLOW',
    cardSize: 'MEDIUM',
    positionX: 10,
    positionY: 20,
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

function makeSection(id: number, overrides: Partial<CorkboardGroupDetail> = {}): CorkboardGroupDetail {
  return {
    id,
    corkboardId: 42,
    name: `セクション${id}`,
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

// ============================================================
// コンポーネントラッパーを使った composable のマウント
// ============================================================

/**
 * `watch` / `onMounted` / `onUnmounted` を正しく動作させるために
 * defineComponent ラッパー + mountSuspended でコンポーネントコンテキストを確保する。
 */
async function setupComposable(
  boardRef: ReturnType<typeof ref<CorkboardDetail | null>>,
) {
  let composableResult!: ReturnType<typeof useCorkboardWebSocketSync>

  const Wrapper = defineComponent({
    setup() {
      composableResult = useCorkboardWebSocketSync(boardRef)
      return () => h('div')
    },
  })

  const wrapper = await mountSuspended(Wrapper)
  await nextTick()
  await nextTick()

  return { result: composableResult, wrapper }
}

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardWebSocketSync', () => {
  beforeEach(() => {
    mockConnect.mockClear()
    mockDisconnect.mockClear()
    mockUseCorkboardEventListener.mockClear()
  })

  // ============================================================
  // isSharedBoard の購読制御
  // ============================================================

  describe('購読制御 (isSharedBoard / watch)', () => {
    it('CORK-SYNC-001: isSharedBoard が false（PERSONAL）のとき connect が呼ばれないこと', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('PERSONAL'))
      const { result } = await setupComposable(board)

      expect(result.isSharedBoard.value).toBe(false)
      expect(mockConnect).not.toHaveBeenCalled()
    })

    it('CORK-SYNC-002: isSharedBoard が true（TEAM）のとき connect が呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM'))
      const { result } = await setupComposable(board)

      expect(result.isSharedBoard.value).toBe(true)
      expect(mockConnect).toHaveBeenCalledTimes(1)
    })

    it('CORK-SYNC-002b: isSharedBoard が true（ORGANIZATION）のとき connect が呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('ORGANIZATION'))
      const { result } = await setupComposable(board)

      expect(result.isSharedBoard.value).toBe(true)
      expect(mockConnect).toHaveBeenCalledTimes(1)
    })

    it('CORK-SYNC-015: onUnmounted で disconnect が呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM'))
      const { wrapper } = await setupComposable(board)

      expect(mockConnect).toHaveBeenCalledTimes(1)

      wrapper.unmount()

      expect(mockDisconnect).toHaveBeenCalledTimes(1)
    })
  })

  // ============================================================
  // CARD イベント処理
  // ============================================================

  describe('CARD_CREATED', () => {
    it('CORK-SYNC-003: card ペイロードあり — board.value.cards に新規追加されること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [] }))
      const newCard = makeCard(100)

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_CREATED',
        cardId: 100,
        sectionId: null,
        card: newCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards).toHaveLength(1)
      expect(board.value?.cards[0]?.id).toBe(100)
    })

    it('CORK-SYNC-004: 既に同 id のカードがあれば置換されること（多重 push 防止）', async () => {
      const existingCard = makeCard(100, { title: '旧タイトル' })
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [existingCard] }))
      const updatedCard = makeCard(100, { title: '新タイトル' })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_CREATED',
        cardId: 100,
        sectionId: null,
        card: updatedCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards).toHaveLength(1)
      expect(board.value?.cards[0]?.title).toBe('新タイトル')
    })

    it('CORK-SYNC-014: card ペイロードが null のとき フルリロードにフォールバックすること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [] }))
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_CREATED',
        cardId: 100,
        sectionId: null,
        card: null,
      }

      result.handleCorkboardEvent(event)

      expect(reloadFn).toHaveBeenCalledTimes(1)
      expect(board.value?.cards).toHaveLength(0)
    })
  })

  describe('CARD_UPDATED', () => {
    it('CORK-SYNC-005: board.value.cards の該当カードが更新されること', async () => {
      const card1 = makeCard(1, { title: '旧タイトル' })
      const card2 = makeCard(2)
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [card1, card2] }))
      const updatedCard = makeCard(1, { title: '新タイトル' })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_UPDATED',
        cardId: 1,
        sectionId: null,
        card: updatedCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards).toHaveLength(2)
      expect(board.value?.cards[0]?.title).toBe('新タイトル')
      expect(board.value?.cards[1]?.id).toBe(2)
    })
  })

  describe('CARD_MOVED / CARD_ARCHIVED', () => {
    it('CORK-SYNC-009a: CARD_MOVED — カードが置換されること', async () => {
      const card = makeCard(1, { positionX: 10, positionY: 20 })
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [card] }))
      const movedCard = makeCard(1, { positionX: 100, positionY: 200 })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_MOVED',
        cardId: 1,
        sectionId: null,
        card: movedCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards[0]?.positionX).toBe(100)
      expect(board.value?.cards[0]?.positionY).toBe(200)
    })

    it('CORK-SYNC-009b: CARD_ARCHIVED — カードが置換されること', async () => {
      const card = makeCard(1, { isArchived: false })
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [card] }))
      const archivedCard = makeCard(1, { isArchived: true })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_ARCHIVED',
        cardId: 1,
        sectionId: null,
        card: archivedCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards[0]?.isArchived).toBe(true)
    })
  })

  describe('CARD_DELETED', () => {
    it('CORK-SYNC-006: board.value.cards から削除されること', async () => {
      const card1 = makeCard(1)
      const card2 = makeCard(2)
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [card1, card2] }))
      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_DELETED',
        cardId: 1,
        sectionId: null,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards).toHaveLength(1)
      expect(board.value?.cards[0]?.id).toBe(2)
    })
  })

  describe('CARD_SECTION_CHANGED', () => {
    it('CORK-SYNC-011: 該当カードが更新されること', async () => {
      const card = makeCard(1, { sectionId: null })
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { cards: [card] }))
      const changedCard = makeCard(1, { sectionId: 10 })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_SECTION_CHANGED',
        cardId: 1,
        sectionId: 10,
        card: changedCard,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.cards[0]?.sectionId).toBe(10)
    })
  })

  // ============================================================
  // SECTION イベント処理
  // ============================================================

  describe('SECTION_CREATED', () => {
    it('CORK-SYNC-007: board.value.groups に追加されること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { groups: [] }))
      const newSection = makeSection(10)

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'SECTION_CREATED',
        cardId: null,
        sectionId: 10,
        section: newSection,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.groups).toHaveLength(1)
      expect(board.value?.groups[0]?.id).toBe(10)
    })
  })

  describe('SECTION_UPDATED', () => {
    it('CORK-SYNC-010: セクションが置換されること', async () => {
      const section = makeSection(10, { name: '旧セクション名' })
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { groups: [section] }))
      const updatedSection = makeSection(10, { name: '新セクション名' })

      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'SECTION_UPDATED',
        cardId: null,
        sectionId: 10,
        section: updatedSection,
      }

      result.handleCorkboardEvent(event)

      expect(board.value?.groups[0]?.name).toBe('新セクション名')
    })
  })

  describe('SECTION_DELETED', () => {
    it('CORK-SYNC-008: board.value.groups から削除され、関連カードの sectionId が null になること', async () => {
      const section = makeSection(10)
      const card1 = makeCard(1, { sectionId: 10 })
      const card2 = makeCard(2, { sectionId: 20 })
      const card3 = makeCard(3, { sectionId: 10 })
      const board = ref<CorkboardDetail | null>(
        makeBoard('TEAM', {
          groups: [section],
          cards: [card1, card2, card3],
        }),
      )
      const { result } = await setupComposable(board)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'SECTION_DELETED',
        cardId: null,
        sectionId: 10,
      }

      result.handleCorkboardEvent(event)

      // セクションが削除されること
      expect(board.value?.groups).toHaveLength(0)

      // sectionId が 10 だったカードは null になること
      expect(board.value?.cards[0]?.sectionId).toBeNull()
      // sectionId が 20 だったカードは変わらないこと
      expect(board.value?.cards[1]?.sectionId).toBe(20)
      // sectionId が 10 だったカードは null になること
      expect(board.value?.cards[2]?.sectionId).toBeNull()
    })

    it('CORK-SYNC-017: sectionId が null のとき フルリロードにフォールバックすること', async () => {
      const section = makeSection(10)
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM', { groups: [section] }))
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'SECTION_DELETED',
        cardId: null,
        sectionId: null,
      }

      result.handleCorkboardEvent(event)

      expect(reloadFn).toHaveBeenCalledTimes(1)
    })
  })

  // ============================================================
  // BOARD_DELETED・未知 eventType
  // ============================================================

  describe('BOARD_DELETED / 未知 eventType', () => {
    it('CORK-SYNC-012: BOARD_DELETED — フルリロード関数が呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM'))
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'BOARD_DELETED',
        cardId: null,
        sectionId: null,
      }

      result.handleCorkboardEvent(event)

      expect(reloadFn).toHaveBeenCalledTimes(1)
    })

    it('CORK-SYNC-013: 未知の eventType — フルリロード関数が呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM'))
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      // 型アサーションで未知の eventType を模倣
      const event = {
        boardId: 42,
        eventType: 'UNKNOWN_EVENT_TYPE' as 'BOARD_DELETED',
        cardId: null,
        sectionId: null,
      } as CorkboardEventPayload

      result.handleCorkboardEvent(event)

      expect(reloadFn).toHaveBeenCalledTimes(1)
    })

    it('CORK-SYNC-018: setReloadFn で注入した関数が BOARD_DELETED で呼ばれること', async () => {
      const board = ref<CorkboardDetail | null>(makeBoard('TEAM'))
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      result.handleCorkboardEvent({
        boardId: 42,
        eventType: 'BOARD_DELETED',
        cardId: null,
        sectionId: null,
      })

      expect(reloadFn).toHaveBeenCalledTimes(1)

      // 再度注入したとき正しく切り替わること
      const newReloadFn = vi.fn()
      result.setReloadFn(newReloadFn)

      result.handleCorkboardEvent({
        boardId: 42,
        eventType: 'BOARD_DELETED',
        cardId: null,
        sectionId: null,
      })

      expect(reloadFn).toHaveBeenCalledTimes(1)  // 旧関数は呼ばれない
      expect(newReloadFn).toHaveBeenCalledTimes(1)  // 新関数のみ呼ばれる
    })
  })

  // ============================================================
  // board が null のとき
  // ============================================================

  describe('board が null のとき', () => {
    it('CORK-SYNC-016: board.value が null のとき handleCorkboardEvent は何もしないこと', async () => {
      const board = ref<CorkboardDetail | null>(null)
      const reloadFn = vi.fn()

      const { result } = await setupComposable(board)
      result.setReloadFn(reloadFn)

      const event: CorkboardEventPayload = {
        boardId: 42,
        eventType: 'CARD_CREATED',
        cardId: 100,
        sectionId: null,
        card: makeCard(100),
      }

      result.handleCorkboardEvent(event)

      // board が null なのでカードが追加されるべきではない、かつリロードも呼ばれない
      expect(board.value).toBeNull()
      expect(reloadFn).not.toHaveBeenCalled()
    })
  })
})
