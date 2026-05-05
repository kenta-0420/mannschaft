import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useCorkboardDetail } from '~/composables/useCorkboardDetail'
import type { CorkboardDetail } from '~/types/corkboard'

/**
 * useCorkboardDetail ユニットテスト。
 *
 * モック方針:
 *  - `useI18n` は Vue setup コンテキスト内でのみ動作するため、
 *    `mountSuspended` + `defineComponent` ラッパーで setup コンテキストを確保する。
 *  - i18n の翻訳は実際の英語ロケール値を期待値として使用する（テスト環境デフォルト = en）。
 *  - `useCorkboardApi` / `useErrorReport` / `useAuthStore` は vi.mock でスタブ化する。
 *  - `useRoute` の変更は `mountSuspended({ route })` オプションで Nuxt 内部ルーターを更新する。
 *  - `useRouter` は globalThis に差し込む。
 *
 * テストケース:
 *  CORK-DETAIL-001: scopeLabel — PERSONAL ボードで正しいラベルが返る
 *  CORK-DETAIL-002: scopeLabel — TEAM ボードで正しいラベルが返る
 *  CORK-DETAIL-003: scopeLabel — ORGANIZATION ボードで正しいラベルが返る
 *  CORK-DETAIL-004: scopeLabel — board が null で scopeParam クエリに従う
 *  CORK-DETAIL-005: scopeLabel — 未知スコープで空文字
 *  CORK-DETAIL-006: scopeBadgeClass — PERSONAL のクラスが正しい
 *  CORK-DETAIL-007: scopeBadgeClass — TEAM のクラスが正しい
 *  CORK-DETAIL-008: scopeBadgeClass — ORGANIZATION のクラスが正しい
 *  CORK-DETAIL-009: scopeBadgeClass — 未知スコープでデフォルトクラス
 *  CORK-DETAIL-010: boardBackgroundClass — CORK がデフォルト
 *  CORK-DETAIL-011: boardBackgroundClass — WHITE
 *  CORK-DETAIL-012: boardBackgroundClass — DARK
 *  CORK-DETAIL-013: boardBackgroundClass — board が null で CORK にフォールバック
 *  CORK-DETAIL-014: canEdit — viewerCanEdit: true のとき true
 *  CORK-DETAIL-015: canEdit — viewerCanEdit: false のとき false
 *  CORK-DETAIL-016: canEdit — board が null のとき false
 *  CORK-DETAIL-017: canPin — PERSONAL + ownerId 一致で true
 *  CORK-DETAIL-018: canPin — TEAM ボードで false
 *  CORK-DETAIL-019: canPin — ownerId 不一致で false
 *  CORK-DETAIL-020: canPin — board が null で false
 *  CORK-DETAIL-021: load — 正常系（scope クエリあり）でボード取得成功
 *  CORK-DETAIL-022: load — boardId が 0 のときエラーメッセージを設定
 *  CORK-DETAIL-023: load — TEAM scope で scopeId なしのときエラーメッセージを設定
 *  CORK-DETAIL-024: load — 正常系（scope クエリなし）scope-agnostic API を使用
 */

// ============================================================
// モック変数
// ============================================================

const mockGetBoardDetail = vi.fn()
const mockGetBoardDetailByBoardId = vi.fn()
const mockCaptureQuiet = vi.fn()
const mockRouterBack = vi.fn()
const mockRouterPush = vi.fn()

let currentUserId: number | null = 99

// ============================================================
// vi.mock — Nuxt auto-import 対象の composable / store をスタブ
// ============================================================

vi.mock('~/composables/useCorkboardApi', () => ({
  useCorkboardApi: () => ({
    getBoardDetail: mockGetBoardDetail,
    getBoardDetailByBoardId: mockGetBoardDetailByBoardId,
    deleteCard: vi.fn(),
    archiveCard: vi.fn(),
    togglePinCard: vi.fn(),
    deleteGroup: vi.fn(),
    addCardToGroup: vi.fn(),
    removeCardFromGroup: vi.fn(),
    batchUpdateCardPositions: vi.fn(),
  }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mockCaptureQuiet }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    get isAuthenticated() {
      return true
    },
    get currentUser() {
      return currentUserId != null ? { id: currentUserId } : null
    },
    loadFromStorage: vi.fn(),
  }),
}))

// useRouter は globalThis に差し込む
// （useRoute は mountSuspended の route オプションで Nuxt 内部ルーターを操作する）
const g = globalThis as Record<string, unknown>
g.useRouter = () => ({
  back: mockRouterBack,
  push: mockRouterPush,
})

// Nuxt auto-import（ミドルウェア等）が globalThis の useAuthStore を使う場合のフォールバック
g.useAuthStore = () => ({
  isAuthenticated: true,
  get currentUser() {
    return currentUserId != null ? { id: currentUserId } : null
  },
  loadFromStorage: vi.fn(),
})

// ============================================================
// ラッパーコンポーネント
//
// useI18n は setup コンテキスト必須。mountSuspended でラップして実行する。
// setup を同期関数にし、外側から参照を受け取るために共有変数を使う。
// ============================================================

type UseCorkboardDetailResult = ReturnType<typeof useCorkboardDetail>

let composableResult: UseCorkboardDetailResult | null = null

const WrapperComponent = defineComponent({
  setup() {
    composableResult = useCorkboardDetail()
    return () => h('div')
  },
})

// ============================================================
// テストデータ ファクトリ
// ============================================================

function makeBoard(over: Partial<CorkboardDetail> = {}): CorkboardDetail {
  return {
    id: 1,
    scopeType: 'PERSONAL',
    scopeId: null,
    ownerId: 99,
    name: 'テストボード',
    backgroundStyle: 'CORK',
    editPolicy: 'ADMIN_ONLY',
    isDefault: false,
    version: 1,
    cards: [],
    groups: [],
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    viewerCanEdit: false,
    ...over,
  }
}

/**
 * 指定した route で WrapperComponent を再マウントし composableResult を更新して返すヘルパ。
 * mountSuspended の route オプションにより Nuxt 内部ルーターが更新される。
 */
async function mountWithRoute(
  routeLocation: Parameters<typeof mountSuspended>[1] extends { route?: infer R } ? R : never,
): Promise<UseCorkboardDetailResult> {
  composableResult = null
  await mountSuspended(WrapperComponent, { route: routeLocation })
  return composableResult!
}

// ============================================================
// テスト本体
// ============================================================

describe('useCorkboardDetail', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    currentUserId = 99

    mockGetBoardDetail.mockReset()
    mockGetBoardDetailByBoardId.mockReset()
    mockCaptureQuiet.mockReset()
    mockRouterBack.mockReset()
    mockRouterPush.mockReset()

    g.useAuthStore = () => ({
      get currentUser() {
        return currentUserId != null ? { id: currentUserId } : null
      },
    })

    composableResult = null
    // デフォルト: /corkboard/42 （boardId = 42、scope クエリなし）
    await mountSuspended(WrapperComponent, { route: '/corkboard/42' })
  })

  // ============================================================
  // scopeLabel
  // ============================================================

  describe('scopeLabel', () => {
    it('CORK-DETAIL-001: PERSONAL ボードで Personal を返す', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'PERSONAL' })
      expect(composable.scopeLabel.value).toBe('Personal')
    })

    it('CORK-DETAIL-002: TEAM ボードで Team を返す', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'TEAM' })
      expect(composable.scopeLabel.value).toBe('Team')
    })

    it('CORK-DETAIL-003: ORGANIZATION ボードで Organization を返す', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'ORGANIZATION' })
      expect(composable.scopeLabel.value).toBe('Organization')
    })

    it('CORK-DETAIL-004: board が null のとき scopeParam クエリに従う', async () => {
      // scope=team クエリで再マウント
      const composable = await mountWithRoute({
        path: '/corkboard/42',
        query: { scope: 'team' },
      })
      // board は null のまま → scopeParam（TEAM）にフォールバック
      expect(composable.scopeLabel.value).toBe('Team')
    })

    it('CORK-DETAIL-005: 未知スコープは空文字を返す', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'UNKNOWN' as 'PERSONAL' })
      expect(composable.scopeLabel.value).toBe('')
    })
  })

  // ============================================================
  // scopeBadgeClass
  // ============================================================

  describe('scopeBadgeClass', () => {
    it('CORK-DETAIL-006: PERSONAL — 青系クラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'PERSONAL' })
      expect(composable.scopeBadgeClass.value).toBe(
        'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-200',
      )
    })

    it('CORK-DETAIL-007: TEAM — 緑系クラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'TEAM' })
      expect(composable.scopeBadgeClass.value).toBe(
        'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-200',
      )
    })

    it('CORK-DETAIL-008: ORGANIZATION — 紫系クラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'ORGANIZATION' })
      expect(composable.scopeBadgeClass.value).toBe(
        'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-200',
      )
    })

    it('CORK-DETAIL-009: 未知スコープ — デフォルトクラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'UNKNOWN' as 'PERSONAL' })
      expect(composable.scopeBadgeClass.value).toBe('bg-surface-100 text-surface-600')
    })
  })

  // ============================================================
  // boardBackgroundClass
  // ============================================================

  describe('boardBackgroundClass', () => {
    it('CORK-DETAIL-010: CORK — コルク風クラス（デフォルト）', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ backgroundStyle: 'CORK' })
      expect(composable.boardBackgroundClass.value).toBe(
        'bg-amber-50 dark:bg-amber-900/30 corkboard-cork-texture',
      )
    })

    it('CORK-DETAIL-011: WHITE — 白系クラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ backgroundStyle: 'WHITE' })
      expect(composable.boardBackgroundClass.value).toBe('bg-white dark:bg-surface-900')
    })

    it('CORK-DETAIL-012: DARK — ダーク系クラス', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ backgroundStyle: 'DARK' })
      expect(composable.boardBackgroundClass.value).toBe('bg-surface-800 dark:bg-surface-950')
    })

    it('CORK-DETAIL-013: board が null のとき CORK にフォールバック', () => {
      const composable = composableResult!
      // board は初期値 null のまま
      expect(composable.boardBackgroundClass.value).toBe(
        'bg-amber-50 dark:bg-amber-900/30 corkboard-cork-texture',
      )
    })
  })

  // ============================================================
  // canEdit
  // ============================================================

  describe('canEdit', () => {
    it('CORK-DETAIL-014: viewerCanEdit: true のとき true', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ viewerCanEdit: true })
      expect(composable.canEdit.value).toBe(true)
    })

    it('CORK-DETAIL-015: viewerCanEdit: false のとき false', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ viewerCanEdit: false })
      expect(composable.canEdit.value).toBe(false)
    })

    it('CORK-DETAIL-016: board が null のとき false', () => {
      const composable = composableResult!
      expect(composable.canEdit.value).toBe(false)
    })
  })

  // ============================================================
  // canPin
  // ============================================================

  describe('canPin', () => {
    it('CORK-DETAIL-017: PERSONAL + ownerId が currentUser.id と一致 → true', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'PERSONAL', ownerId: 99 })
      expect(composable.canPin.value).toBe(true)
    })

    it('CORK-DETAIL-018: TEAM ボードは canPin が false', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'TEAM', ownerId: 99 })
      expect(composable.canPin.value).toBe(false)
    })

    it('CORK-DETAIL-019: ownerId が currentUser.id と不一致 → false', () => {
      const composable = composableResult!
      composable.board.value = makeBoard({ scopeType: 'PERSONAL', ownerId: 1 }) // 別ユーザー
      expect(composable.canPin.value).toBe(false)
    })

    it('CORK-DETAIL-020: board が null のとき false', () => {
      const composable = composableResult!
      expect(composable.canPin.value).toBe(false)
    })
  })

  // ============================================================
  // load 関数
  // ============================================================

  describe('load()', () => {
    it('CORK-DETAIL-021: scope クエリあり → getBoardDetail が呼ばれ board が設定される', async () => {
      // scope=PERSONAL クエリで再マウント（boardId = 42）
      const composable = await mountWithRoute({
        path: '/corkboard/42',
        query: { scope: 'PERSONAL' },
      })

      const fakeBoard = makeBoard({ id: 42, scopeType: 'PERSONAL', viewerCanEdit: true })
      mockGetBoardDetail.mockResolvedValue({ data: fakeBoard })

      await composable.load()

      expect(mockGetBoardDetail).toHaveBeenCalledWith('PERSONAL', null, 42)
      expect(mockGetBoardDetailByBoardId).not.toHaveBeenCalled()
      expect(composable.board.value).toEqual(fakeBoard)
      expect(composable.loading.value).toBe(false)
      expect(composable.errorMessage.value).toBeNull()
    })

    it('CORK-DETAIL-022: boardId が 0（無効）のとき errorMessage が設定される', async () => {
      // 無効な id（NaN → 0）で再マウント
      const composable = await mountWithRoute('/corkboard/invalid')

      await composable.load()

      expect(mockGetBoardDetail).not.toHaveBeenCalled()
      expect(mockGetBoardDetailByBoardId).not.toHaveBeenCalled()
      expect(composable.board.value).toBeNull()
      expect(composable.loading.value).toBe(false)
      // テスト環境 (en ロケール) での翻訳値
      expect(composable.errorMessage.value).toBe('Board not found')
    })

    it('CORK-DETAIL-023: TEAM scope で scopeId がないとき errorMessage が設定される', async () => {
      // scope=TEAM, scopeId なしで再マウント
      const composable = await mountWithRoute({
        path: '/corkboard/42',
        query: { scope: 'TEAM' },
      })

      await composable.load()

      expect(mockGetBoardDetail).not.toHaveBeenCalled()
      expect(composable.board.value).toBeNull()
      expect(composable.loading.value).toBe(false)
      // テスト環境 (en ロケール) での翻訳値
      expect(composable.errorMessage.value).toBe('Scope information is missing')
    })

    it('CORK-DETAIL-024: scope クエリなし → getBoardDetailByBoardId が呼ばれる', async () => {
      // scope クエリなしで id=55 に再マウント
      const composable = await mountWithRoute('/corkboard/55')

      const fakeBoard = makeBoard({ id: 55 })
      mockGetBoardDetailByBoardId.mockResolvedValue({ data: fakeBoard })

      await composable.load()

      expect(mockGetBoardDetailByBoardId).toHaveBeenCalledWith(55)
      expect(mockGetBoardDetail).not.toHaveBeenCalled()
      expect(composable.board.value).toEqual(fakeBoard)
    })
  })
})
