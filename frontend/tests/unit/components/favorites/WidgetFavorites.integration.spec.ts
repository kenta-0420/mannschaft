import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { ref, nextTick, type Ref } from 'vue'
import type { UserFavoriteItem } from '~/types/favorite'

/**
 * F02.9 お気に入りウィジェット — 統合シナリオテスト（6 シナリオ）
 *
 * 設計書: docs/features/F02.9_favorites_widget.md §8
 *
 * 本テストは Playwright E2E のフォールバック（仕様書 §8 末尾参照）として、
 * WidgetFavorites + FavoriteCard + FavoriteQuickEditDialog を組合せた
 * コンポーネントレベル統合テストで以下の 6 シナリオを覆う。
 *
 *  1. お気に入り追加 → ウィジェットに表示される
 *  2. お気に入り削除 → カードが一覧から消える
 *  3. 15 件登録 → 先頭 10 件のみ表示 → 「さらに表示」で全件展開
 *  4. クイック編集 → カードの表示名が更新される
 *  5. 上限 20 件 → 21 件目追加で FAV_002 が発生する
 *  6. UNAVAILABLE エンティティ → グレーアウト + 削除ボタンのみ表示
 *
 * Playwright E2E（auth middleware + SSR + 多数の周辺 API）を環境依存なく
 * 動かすには storageState ファイルや dev サーバ全 API のモックが必要で
 * CI 安定化のコストが高い。本ファイルはモック差し替えで同じ仕様検証を高速・
 * 確実に行う。
 */

// ============================================================
// useFavoritesApi のモック（テスト本体から state を操作できるよう外出し）
// ============================================================

const itemsRef: Ref<UserFavoriteItem[]> = ref([])
const isLoadingRef = ref(false)
const errorRef = ref<unknown>(null)

const mockFetchFavorites = vi.fn(async () => itemsRef.value)
const mockAddFavorite = vi.fn()
const mockRemoveFavorite = vi.fn(async (favoriteId: string) => {
  itemsRef.value = itemsRef.value.filter((it) => it.favoriteId !== favoriteId)
})
const mockReorderFavorites = vi.fn(async () => {})

vi.mock('~/composables/useFavoritesApi', () => ({
  useFavoritesApi: () => ({
    items: itemsRef,
    totalCount: ref(itemsRef.value.length),
    isLoading: isLoadingRef,
    error: errorRef,
    fetchFavorites: mockFetchFavorites,
    addFavorite: mockAddFavorite,
    removeFavorite: mockRemoveFavorite,
    reorderFavorites: mockReorderFavorites,
    getFavoriteById: vi.fn(),
    check: vi.fn(),
  }),
}))

// ============================================================
// useNotification のモック
// ============================================================

const mockNotifSuccess = vi.fn()
const mockNotifError = vi.fn()

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockNotifSuccess,
    error: mockNotifError,
    info: vi.fn(),
    warn: vi.fn(),
  }),
}))

// ============================================================
// useUndoToast のモック
// ============================================================
// ADHD 配慮 AC-15（#2167）で、お気に入り削除は「確認ダイアログ → 削除 → 成功トースト」から
// 「即時削除 → Undo Toast」へ置き換わった。削除成功のフィードバックは useNotification.success
// ではなく showUndoToast で出るため、こちらを検証対象にする。

interface UndoToastCall {
  summary: string
  undoLabel: string
  onUndo: () => void | Promise<void>
}
const mockShowUndoToast = vi.fn<(opts: UndoToastCall) => void>()

vi.mock('~/composables/useUndoToast', () => ({
  useUndoToast: () => ({
    showUndoToast: mockShowUndoToast,
  }),
}))

// ============================================================
// useTeamApi / useOrganizationApi / useSocialProfileApi のモック
// （FavoriteQuickEditDialog 用）
// ============================================================

// teamId は slug（string）。useTeamCrud.updateTeam(teamSlug: string, ...) と一致させる。
const mockUpdateTeam = vi.fn(async (_id: string, _payload: { name?: string }) => ({ data: {} }))
const mockUpdateOrganization = vi.fn(async () => ({ data: {} }))
const mockUpdateMyProfile = vi.fn(async () => ({ data: {} }))

vi.mock('~/composables/useTeamApi', () => ({
  useTeamApi: () => ({ updateTeam: mockUpdateTeam }),
}))
vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ updateOrganization: mockUpdateOrganization }),
}))
vi.mock('~/composables/useSocialProfileApi', () => ({
  useSocialProfileApi: () => ({ updateMyProfile: mockUpdateMyProfile }),
}))

// vuedraggable は jsdom で SVG 計算等が動かないので軽量スタブに差し替える
vi.mock('vuedraggable', () => ({
  default: {
    name: 'draggable',
    props: ['modelValue', 'itemKey', 'animation', 'ghostClass', 'handle'],
    emits: ['update:modelValue', 'end'],
    setup(_props: unknown, { slots }: { slots: Record<string, (ctx: { element: UserFavoriteItem }) => unknown> }) {
      return () => {
        const items = (_props as { modelValue?: UserFavoriteItem[] }).modelValue ?? []
        return items.map((element) => slots.item?.({ element }))
      }
    },
  },
}))

// ============================================================
// ヘルパ
// ============================================================

function makeItem(over: Partial<UserFavoriteItem> = {}): UserFavoriteItem {
  return {
    favoriteId: over.favoriteId ?? `fav-${Math.random().toString(36).slice(2, 8)}`,
    entityType: over.entityType ?? 'TEAM',
    entityId: over.entityId ?? '1',
    displayOrder: over.displayOrder ?? 0,
    createdAt: over.createdAt ?? new Date().toISOString(),
    entity: {
      name: over.entity?.name ?? 'お気に入り項目',
      description: over.entity?.description ?? null,
      iconUrl: over.entity?.iconUrl ?? null,
      pageUrl: over.entity?.pageUrl ?? '/teams/1',
      status: over.entity?.status ?? 'AVAILABLE',
      canEdit: over.entity?.canEdit ?? true,
      editableFields: over.entity?.editableFields ?? [],
    },
  }
}

const WidgetFavorites = (
  await import('~/components/widgets/WidgetFavorites.vue')
).default

beforeEach(() => {
  setActivePinia(createPinia())
  itemsRef.value = []
  isLoadingRef.value = false
  errorRef.value = null
  mockFetchFavorites.mockClear()
  mockAddFavorite.mockReset()
  mockRemoveFavorite.mockClear()
  mockReorderFavorites.mockClear()
  mockNotifSuccess.mockReset()
  mockNotifError.mockReset()
  mockShowUndoToast.mockReset()
  mockUpdateTeam.mockClear()
  mockUpdateOrganization.mockClear()
  mockUpdateMyProfile.mockClear()
})

// ============================================================
// シナリオ 1: お気に入り追加 → ウィジェットに表示される
// ============================================================

describe('F02.9-1: お気に入り追加 → ウィジェットに表示される', () => {
  it('addFavorite で items が更新されると新しいカードが描画される', async () => {
    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()

    // 初期は空状態
    expect(wrapper.find('[data-testid^="favorite-card-"]').exists()).toBe(false)

    // バックエンド経由で追加された想定で items を更新
    itemsRef.value = [
      makeItem({
        favoriteId: 'fav-added-1',
        entityType: 'TEAM',
        entityId: '777',
        entity: {
          name: '追加されたチーム',
          description: null,
          iconUrl: null,
          pageUrl: '/teams/777',
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
    ]
    await nextTick()
    await nextTick()

    const card = wrapper.find('[data-testid="favorite-card-fav-added-1"]')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('追加されたチーム')
  })
})

// ============================================================
// シナリオ 2: お気に入り削除 → カードが一覧から消える
// ============================================================

describe('F02.9-2: お気に入り削除 → カードが一覧から消える', () => {
  it('削除ボタン → removeFavorite が呼ばれて消え、Undo Toast が出る', async () => {
    itemsRef.value = [
      makeItem({
        favoriteId: 'fav-del-1',
        entityId: '100',
        entity: {
          name: '削除対象',
          description: null,
          iconUrl: null,
          pageUrl: '/teams/100',
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
      makeItem({
        favoriteId: 'fav-keep-1',
        entityId: '200',
        entity: {
          name: '残す項目',
          description: null,
          iconUrl: null,
          pageUrl: '/teams/200',
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
    ]
    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()
    await nextTick()

    expect(wrapper.find('[data-testid="favorite-card-fav-del-1"]').exists()).toBe(true)

    // 削除イベントを子コンポーネントから発火
    const targetCard = wrapper.findComponent({ name: 'FavoriteCard', ref: undefined })
    // FavoriteCard が複数あるので、props.item.favoriteId で削除対象を特定
    const cards = wrapper.findAllComponents({ name: 'FavoriteCard' })
    const delCard = cards.find(
      (c) => (c.props('item') as UserFavoriteItem).favoriteId === 'fav-del-1',
    )
    expect(delCard).toBeDefined()
    delCard!.vm.$emit('remove')
    await nextTick()
    await nextTick()
    void targetCard

    expect(mockRemoveFavorite).toHaveBeenCalledWith('fav-del-1')
    // 削除成功のフィードバックは Undo Toast（AC-15）。summary / undoLabel は i18n 済み文字列で
    // 環境ロケールに依存するため、空でないことと Undo が実際に復元を呼ぶことを検証する。
    expect(mockShowUndoToast).toHaveBeenCalledTimes(1)
    const undoCall = mockShowUndoToast.mock.calls[0]![0]
    expect(undoCall.summary).toBeTruthy()
    expect(undoCall.undoLabel).toBeTruthy()
    await undoCall.onUndo()
    expect(mockAddFavorite).toHaveBeenCalledWith('TEAM', '100')
    // 残った方は表示継続
    expect(wrapper.find('[data-testid="favorite-card-fav-keep-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="favorite-card-fav-del-1"]').exists()).toBe(false)
  })
})

// ============================================================
// シナリオ 3: 15 件登録 → 折りたたみ → 「さらに表示」で展開
// ============================================================

describe('F02.9-3: 15 件登録 → 先頭 10 件のみ表示 → 「さらに 5 件表示」で展開', () => {
  it('折りたたみ時は先頭 10 件のみ、展開で全 15 件表示', async () => {
    itemsRef.value = Array.from({ length: 15 }, (_, i) =>
      makeItem({
        favoriteId: `fav-${String(i + 1).padStart(2, '0')}`,
        entityId: String(i + 1),
        displayOrder: i,
        entity: {
          name: `お気に入り ${i + 1}`,
          description: null,
          iconUrl: null,
          pageUrl: `/teams/${i + 1}`,
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
    )
    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()
    await nextTick()

    // 折りたたみ時: 1〜10 表示、11〜15 非表示
    expect(wrapper.find('[data-testid="favorite-card-fav-01"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="favorite-card-fav-10"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="favorite-card-fav-11"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="favorite-card-fav-15"]').exists()).toBe(false)

    // 「さらに 5 件表示」ボタン
    const toggle = wrapper.find('[data-testid="widget-favorites-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('5')

    // 展開
    await toggle.trigger('click')
    await nextTick()

    expect(wrapper.find('[data-testid="favorite-card-fav-11"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="favorite-card-fav-15"]').exists()).toBe(true)
    // 「折りたたむ」/「Collapse」のいずれか（環境ロケール非依存）
    expect(toggle.text()).toMatch(/折りたたむ|Collapse/)
  })
})

// ============================================================
// シナリオ 4: クイック編集 → カードの表示名が更新される
// ============================================================

describe('F02.9-4: クイック編集 → カードの表示名が更新される', () => {
  it('FavoriteCard の edit を発火 → updateTeam → fetchFavorites 再取得', async () => {
    itemsRef.value = [
      makeItem({
        favoriteId: 'fav-edit-1',
        entityType: 'TEAM',
        entityId: '555',
        entity: {
          name: '編集前',
          description: null,
          iconUrl: null,
          pageUrl: '/teams/555',
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
    ]
    // updateTeam が呼ばれたら items を更新する想定
    mockUpdateTeam.mockImplementation(async (_id: string, payload: { name?: string }) => {
      if (payload.name) {
        itemsRef.value = itemsRef.value.map((it) =>
          it.favoriteId === 'fav-edit-1'
            ? { ...it, entity: { ...it.entity, name: payload.name as string } }
            : it,
        )
      }
      return { data: {} }
    })

    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()
    await nextTick()

    // FavoriteCard の edit を発火
    const cards = wrapper.findAllComponents({ name: 'FavoriteCard' })
    const editCard = cards.find(
      (c) => (c.props('item') as UserFavoriteItem).favoriteId === 'fav-edit-1',
    )
    expect(editCard).toBeDefined()
    editCard!.vm.$emit('edit')
    await nextTick()

    // ダイアログが開いたことを確認（FavoriteQuickEditDialog の input が出現）
    const dialog = wrapper.findComponent({ name: 'FavoriteQuickEditDialog' })
    expect(dialog.exists()).toBe(true)

    // dialog の modelValue が編集対象になっていることを確認
    expect((dialog.props('modelValue') as UserFavoriteItem | null)?.favoriteId).toBe('fav-edit-1')

    // dialog の name input を更新（FavoriteQuickEditDialog 内部の formName）→ TEAM テンプレート
    const inputs = dialog.findAll('input[type="text"]')
    expect(inputs.length).toBeGreaterThan(0)
    await inputs[0]!.setValue('編集後の名前')

    // 保存ボタンを押下（「保存」/ 「Save」のいずれか）
    const saveButton = dialog
      .findAll('button')
      .find((b) => /保存|Save/.test(b.text()))
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await nextTick()
    // 非同期 await を flush
    await new Promise((r) => setTimeout(r, 0))
    await nextTick()

    // チーム識別子は URL 用 slug（string）に一本化されている（#1345）。数値ではなく文字列で渡る。
    expect(mockUpdateTeam).toHaveBeenCalledWith('555', expect.objectContaining({ name: '編集後の名前' }))
    expect(mockFetchFavorites).toHaveBeenCalled() // saved → onFavoriteSaved → refresh
    // items も更新されている
    expect(itemsRef.value[0]!.entity.name).toBe('編集後の名前')
  })
})

// ============================================================
// シナリオ 5: 上限 20 件 → 21 件目追加で FAV_002 発生
// ============================================================

describe('F02.9-5: 上限 20 件 → 21 件目追加で FAV_002', () => {
  it('addFavorite が FAV_002 で reject される（バックエンド契約）', async () => {
    // 20 件埋まっている状態
    itemsRef.value = Array.from({ length: 20 }, (_, i) =>
      makeItem({
        favoriteId: `fav-full-${i + 1}`,
        entityId: String(i + 1),
        displayOrder: i,
      }),
    )
    mockAddFavorite.mockRejectedValue({
      data: { error: { code: 'FAV_002', message: 'limit exceeded' } },
    })

    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()
    await nextTick()

    // 20 件登録 → 折りたたみで先頭 10 件のみ表示、「さらに 10 件表示」ボタンあり
    expect(wrapper.find('[data-testid="favorite-card-fav-full-1"]').exists()).toBe(true)
    const toggle = wrapper.find('[data-testid="widget-favorites-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('10')

    // 直接 useFavoritesApi.addFavorite を試行 → FAV_002 reject
    await expect(mockAddFavorite('TEAM', '999')).rejects.toMatchObject({
      data: { error: { code: 'FAV_002' } },
    })
  })
})

// ============================================================
// シナリオ 6: UNAVAILABLE エンティティ → グレーアウト + 削除のみ
// ============================================================

describe('F02.9-6: UNAVAILABLE エンティティ → グレーアウト + 削除ボタンのみ', () => {
  it('available=false のカードは opacity-60 と「開く/編集」非表示、削除ボタンのみ', async () => {
    itemsRef.value = [
      makeItem({
        favoriteId: 'fav-unavail-1',
        entityId: '404',
        entity: {
          name: '削除されたチーム',
          description: null,
          iconUrl: null,
          pageUrl: '',
          status: 'UNAVAILABLE',
          canEdit: false,
          editableFields: [],
        },
      }),
      makeItem({
        favoriteId: 'fav-avail-1',
        entityId: '500',
        entity: {
          name: '正常な組織',
          description: null,
          iconUrl: null,
          pageUrl: '/organizations/500',
          status: 'AVAILABLE',
          canEdit: true,
          editableFields: [],
        },
      }),
    ]
    const wrapper = await mountSuspended(WidgetFavorites)
    await nextTick()
    await nextTick()

    const unavailCard = wrapper.find('[data-testid="favorite-card-fav-unavail-1"]')
    expect(unavailCard.exists()).toBe(true)
    expect(unavailCard.classes()).toContain('opacity-60')

    // 「このアイテムは利用できなくなりました」メッセージ（ロケール非依存）
    expect(unavailCard.text()).toMatch(/このアイテムは利用できなくなりました|This item is no longer available/)

    // UNAVAILABLE カード内: 「開く/Open」「編集/Edit」aria-label のボタンがない、「削除/Remove」だけ存在
    // PrimeVue Button は aria-label を継承するため findAll('button') で取得できる。
    // ロケール非依存にするため、エンティティ名（削除されたチーム）を含む aria-label の数で判定する。
    const unavailButtons = unavailCard.findAll('button')
    const labelsContainingName = unavailButtons
      .map((b) => b.attributes('aria-label') ?? '')
      .filter((l) => l.includes('削除されたチーム'))
    // 削除ボタンのみ存在（1 個）= 開く/編集ボタンがないことを意味する
    expect(labelsContainingName.length).toBe(1)

    // AVAILABLE カード側は 2 個（開く + 削除）または 3 個（開く + 編集 + 削除）= UNAVAILABLE より多い
    const availCard = wrapper.find('[data-testid="favorite-card-fav-avail-1"]')
    expect(availCard.exists()).toBe(true)
    expect(availCard.classes()).not.toContain('opacity-60')
    const availLabels = availCard
      .findAll('button')
      .map((b) => b.attributes('aria-label') ?? '')
      .filter((l) => l.includes('正常な組織'))
    expect(availLabels.length).toBeGreaterThanOrEqual(2)
  })
})
