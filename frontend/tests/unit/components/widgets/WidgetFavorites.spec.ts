import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { ref, defineComponent, h, readonly } from 'vue'
import type { UserFavoriteItem } from '~/types/favorite'

/**
 * F02.9 WidgetFavorites.vue のユニットテスト。
 *
 * <p>お気に入りウィジェット本体の挙動を検証する。
 * - マウント時の自動 fetch
 * - 0 件時の空状態コンポーネント表示
 * - 10 件以下/11 件以上の表示分岐
 * - 「さらに表示」ボタンの折り畳み制御
 * - カードの remove イベントから削除 API までの結線</p>
 *
 * <p>モック方針:
 *  - useFavoritesApi をモック化（items ref + 各メソッド）
 *  - useConfirmDialog は onAccept を即時実行する単純スタブに置き換え
 *  - vuedraggable は slot だけ描画する stub に差し替え</p>
 *
 * テストケース一覧:
 *  FAV-WIDGET-001: マウント時に fetchFavorites が呼ばれる
 *  FAV-WIDGET-002: items が空のとき空状態コンポーネントが表示される
 *  FAV-WIDGET-003: items が 5 件のとき 5 個カードが表示される
 *  FAV-WIDGET-004: items が 15 件のとき先頭 10 件 + 「さらに 5 件」ボタンが見える
 *  FAV-WIDGET-005: 「さらに表示」クリックで 15 件すべて表示 + 折りたたむボタンに変化
 *  FAV-WIDGET-006: カードの remove で confirm 経由 removeFavorite が呼ばれる
 */

// === モック ===

const mockItems = ref<UserFavoriteItem[]>([])
const mockIsLoading = ref(false)
const mockError = ref<unknown>(null)
const mockFetchFavorites = vi.fn()
const mockRemoveFavorite = vi.fn()
const mockReorderFavorites = vi.fn()

vi.mock('~/composables/useFavoritesApi', () => ({
  useFavoritesApi: () => ({
    items: readonly(mockItems),
    isLoading: readonly(mockIsLoading),
    error: readonly(mockError),
    fetchFavorites: mockFetchFavorites,
    removeFavorite: mockRemoveFavorite,
    reorderFavorites: mockReorderFavorites,
  }),
}))

// useConfirmDialog: onAccept を即時実行（テストでは確認ダイアログをスキップ）
vi.mock('~/composables/useConfirmDialog', () => ({
  useConfirmDialog: () => ({
    confirmAction: (opts: { onAccept: () => void | Promise<void> }) => {
      void opts.onAccept()
    },
  }),
}))

// notification は副作用ゼロの stub
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
  }),
}))

// vuedraggable は slot をそのまま描画する stub に置き換える
vi.mock('vuedraggable', () => ({
  default: defineComponent({
    props: ['modelValue', 'itemKey'],
    setup(props, { slots }) {
      return () =>
        h(
          'div',
          { class: 'mock-draggable' },
          (props.modelValue as unknown[] | undefined ?? []).map((element, i) =>
            h(
              'div',
              { key: i, class: 'mock-draggable-item' },
              slots.item ? slots.item({ element }) : [],
            ),
          ),
        )
    },
  }),
}))

const WidgetFavorites = (
  await import('~/components/widgets/WidgetFavorites.vue')
).default

function createItem(i: number): UserFavoriteItem {
  return {
    favoriteId: `fav-${i}`,
    entityType: 'TEAM',
    entityId: String(i),
    displayOrder: i,
    createdAt: '2026-05-15T00:00:00Z',
    entity: {
      name: `Team ${i}`,
      description: null,
      iconUrl: null,
      pageUrl: `/teams/${i}`,
      status: 'AVAILABLE',
      canEdit: true,
      editableFields: [],
    },
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockItems.value = []
  mockIsLoading.value = false
  mockError.value = null
  mockFetchFavorites.mockReset()
  mockRemoveFavorite.mockReset()
  mockReorderFavorites.mockReset()
})

describe('WidgetFavorites.vue', () => {
  it('FAV-WIDGET-001: マウント時に fetchFavorites が呼ばれる', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    await mountSuspended(WidgetFavorites)

    expect(mockFetchFavorites).toHaveBeenCalledTimes(1)
  })

  it('FAV-WIDGET-002: items が空のとき空状態コンポーネントが表示される', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    mockItems.value = []

    const wrapper = await mountSuspended(WidgetFavorites)

    expect(wrapper.find('[data-testid="favorites-widget-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="widget-favorites-list"]').exists()).toBe(false)
  })

  it('FAV-WIDGET-003: items が 5 件のとき 5 個カードが表示される', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    mockItems.value = Array.from({ length: 5 }, (_, i) => createItem(i + 1))

    const wrapper = await mountSuspended(WidgetFavorites)

    const cards = wrapper.findAll('[data-testid^="favorite-card-"]')
    expect(cards.length).toBe(5)
    // 折り畳みトグルボタンは存在しない（10 件未満）
    expect(wrapper.find('[data-testid="widget-favorites-toggle"]').exists()).toBe(false)
  })

  it('FAV-WIDGET-004: items が 15 件のとき先頭 10 件 + 「さらに 5 件」ボタンが表示される', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    mockItems.value = Array.from({ length: 15 }, (_, i) => createItem(i + 1))

    const wrapper = await mountSuspended(WidgetFavorites)

    const cards = wrapper.findAll('[data-testid^="favorite-card-"]')
    expect(cards.length).toBe(10)
    const toggle = wrapper.find('[data-testid="widget-favorites-toggle"]')
    expect(toggle.exists()).toBe(true)
    // ラベル文言（en: Show 5 more）に件数 5 が含まれる
    expect(toggle.text()).toContain('5')
  })

  it('FAV-WIDGET-005: 「さらに表示」クリックで 15 件すべて表示 + 折りたたむボタンに変化', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    mockItems.value = Array.from({ length: 15 }, (_, i) => createItem(i + 1))

    const wrapper = await mountSuspended(WidgetFavorites)
    const toggle = wrapper.find('[data-testid="widget-favorites-toggle"]')
    await toggle.trigger('click')

    const cards = wrapper.findAll('[data-testid^="favorite-card-"]')
    expect(cards.length).toBe(15)
    // 折りたたむ（en: Collapse）に変化
    expect(toggle.text()).toContain('Collapse')
  })

  it('FAV-WIDGET-006: カードの remove イベントから confirm 経由で removeFavorite が呼ばれる', async () => {
    mockFetchFavorites.mockResolvedValue(undefined)
    mockRemoveFavorite.mockResolvedValue(undefined)
    mockItems.value = [createItem(1)]

    const wrapper = await mountSuspended(WidgetFavorites)
    // カード内の Remove ボタンを直接トリガー
    const removeBtn = wrapper.find('[aria-label="Remove Team 1"]')
    expect(removeBtn.exists()).toBe(true)
    await removeBtn.trigger('click')
    // 非同期解決を待つ
    await new Promise((r) => setTimeout(r, 0))

    expect(mockRemoveFavorite).toHaveBeenCalledWith('fav-1')
  })
})
