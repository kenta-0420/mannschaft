import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { ref } from 'vue'

/**
 * F02.9 Phase 3 — FavoriteToggleButton.vue のユニットテスト。
 *
 * <p>任意ページに設置するお気に入りトグルボタンの挙動を検証する。
 * - 未認証時の非表示
 * - マウント時の check API 呼び出し
 * - check 結果に応じた ☆ / ★ 表示分岐
 * - クリック時の addFavorite / removeFavorite 呼び出しと toggled emit
 * - FAV_002（上限超過）エラー時の disabled 化とトースト
 * - ARIA 属性（aria-pressed / aria-label）</p>
 *
 * <p>モック方針:
 *  - useFavoritesApi: check / addFavorite / removeFavorite を vi.fn() に差し替え
 *  - useAuthStore: isAuthenticated を ref で動的に切替可能にする
 *  - useNotification: success / error を vi.fn() に差し替え
 *
 * テストケース一覧:
 *  FAV-TGL-001: 未認証時はボタンが描画されない
 *  FAV-TGL-002: マウント時に check API が呼ばれる
 *  FAV-TGL-003: check 結果 isFavorited=false のとき ☆ 表示
 *  FAV-TGL-004: check 結果 isFavorited=true のとき ★ 表示
 *  FAV-TGL-005: ☆クリックで addFavorite 呼ばれ ★に切替 + toggled emit
 *  FAV-TGL-006: ★クリックで removeFavorite 呼ばれ ☆に切替 + toggled emit
 *  FAV-TGL-007: FAV_002 エラー時に disabled + エラートースト
 *  FAV-TGL-008: ARIA 属性（aria-pressed, aria-label）が正しい
 */

// === モック ===

const mockCheck = vi.fn()
const mockAddFavorite = vi.fn()
const mockRemoveFavorite = vi.fn()

vi.mock('~/composables/useFavoritesApi', () => ({
  useFavoritesApi: () => ({
    check: mockCheck,
    addFavorite: mockAddFavorite,
    removeFavorite: mockRemoveFavorite,
    // 他のメソッドは本テストでは未使用だがインタフェース互換のため stub を返す
    items: ref([]),
    totalCount: ref(0),
    isLoading: ref(false),
    error: ref(null),
    fetchFavorites: vi.fn(),
    reorderFavorites: vi.fn(),
    getFavoriteById: vi.fn(),
  }),
}))

// useAuthStore は ref ベースで isAuthenticated を切替できるようにする
const mockIsAuthenticated = ref(true)
vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正）。
  useAuthStore: () => ({
    get isAuthenticated() {
      return mockIsAuthenticated.value
    },
    loadFromStorage: vi.fn(),
    // isAuthenticated=true の既定値だと auth.client.ts の armProactiveRefresh が発火し、
    // トークン更新失敗時に authStore.logout() を呼ぶ（#2609是正: 未モックで Unhandled Rejection）。
    // このテストでは検証対象外の副作用のため無害化する。
    logout: vi.fn(),
  }),
}))

const mockNotificationSuccess = vi.fn()
const mockNotificationError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockNotificationSuccess,
    error: mockNotificationError,
    info: vi.fn(),
    warn: vi.fn(),
  }),
}))

const FavoriteToggleButton = (
  await import('~/components/favorites/FavoriteToggleButton.vue')
).default

beforeEach(() => {
  setActivePinia(createPinia())
  mockIsAuthenticated.value = true
  mockCheck.mockReset()
  mockAddFavorite.mockReset()
  mockRemoveFavorite.mockReset()
  mockNotificationSuccess.mockReset()
  mockNotificationError.mockReset()
})

describe('FavoriteToggleButton.vue', () => {
  it('FAV-TGL-001: 未認証時はボタンが描画されない', async () => {
    mockIsAuthenticated.value = false
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })

    expect(wrapper.find('button').exists()).toBe(false)
    // check も呼ばれないはず
    expect(mockCheck).not.toHaveBeenCalled()
  })

  it('FAV-TGL-002: マウント時に check API が呼ばれる', async () => {
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })

    await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })

    expect(mockCheck).toHaveBeenCalledTimes(1)
    expect(mockCheck).toHaveBeenCalledWith('TEAM', '123')
  })

  it('FAV-TGL-003: check 結果 isFavorited=false のとき ☆ 表示', async () => {
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })
    // onMounted の非同期解決を待つ
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.text()).toContain('☆')
    expect(wrapper.text()).not.toContain('★')
    const btn = wrapper.find('button')
    expect(btn.attributes('aria-pressed')).toBe('false')
  })

  it('FAV-TGL-004: check 結果 isFavorited=true のとき ★ 表示', async () => {
    mockCheck.mockResolvedValue({ isFavorited: true, favoriteId: 'fav-001' })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.text()).toContain('★')
    const btn = wrapper.find('button')
    expect(btn.attributes('aria-pressed')).toBe('true')
  })

  it('FAV-TGL-005: ☆クリックで addFavorite が呼ばれ、★に切替 + toggled emit', async () => {
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })
    mockAddFavorite.mockResolvedValue({
      favoriteId: 'fav-new-001',
      entityType: 'TEAM',
      entityId: '123',
      displayOrder: 0,
      createdAt: '2026-05-15T00:00:00Z',
      entity: {
        name: 'Test Team',
        description: null,
        iconUrl: null,
        pageUrl: '/teams/123',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })
    await new Promise((r) => setTimeout(r, 0))

    await wrapper.find('button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockAddFavorite).toHaveBeenCalledWith('TEAM', '123')
    expect(wrapper.text()).toContain('★')
    expect(wrapper.emitted('toggled')).toBeTruthy()
    const ev = wrapper.emitted('toggled')!
    expect(ev[0]?.[0]).toEqual({ isFavorited: true })
    expect(mockNotificationSuccess).toHaveBeenCalled()
  })

  it('FAV-TGL-006: ★クリックで removeFavorite が呼ばれ、☆に切替 + toggled emit', async () => {
    mockCheck.mockResolvedValue({ isFavorited: true, favoriteId: 'fav-001' })
    mockRemoveFavorite.mockResolvedValue(undefined)

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })
    await new Promise((r) => setTimeout(r, 0))

    await wrapper.find('button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockRemoveFavorite).toHaveBeenCalledWith('fav-001')
    expect(wrapper.text()).toContain('☆')
    expect(wrapper.emitted('toggled')).toBeTruthy()
    const ev = wrapper.emitted('toggled')!
    expect(ev[0]?.[0]).toEqual({ isFavorited: false })
    expect(mockNotificationSuccess).toHaveBeenCalled()
  })

  it('FAV-TGL-007: FAV_002 エラー時に disabled + エラートースト', async () => {
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })
    mockAddFavorite.mockRejectedValue({
      data: { error: { code: 'FAV_002', message: 'limit exceeded' } },
    })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'Test Team' },
    })
    await new Promise((r) => setTimeout(r, 0))

    await wrapper.find('button').trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockAddFavorite).toHaveBeenCalled()
    expect(mockNotificationError).toHaveBeenCalled()
    const btn = wrapper.find('button')
    expect(btn.attributes('disabled')).toBeDefined()
    // 状態は未登録のままで ☆ 表示が継続する
    expect(wrapper.text()).toContain('☆')
  })

  it('FAV-TGL-008: ARIA 属性（aria-pressed, aria-label）が正しい', async () => {
    mockCheck.mockResolvedValue({ isFavorited: false, favoriteId: null })

    const wrapper = await mountSuspended(FavoriteToggleButton, {
      props: { entityType: 'TEAM', entityId: '123', entityName: 'My Team' },
    })
    await new Promise((r) => setTimeout(r, 0))

    const btn = wrapper.find('button')
    expect(btn.attributes('aria-pressed')).toBe('false')
    // aria-label には entityName が含まれる（言語にかかわらず {name} 展開済み）
    const label = btn.attributes('aria-label') ?? ''
    expect(label).toContain('My Team')
    // data-testid もチェック
    expect(btn.attributes('data-testid')).toBe('favorite-toggle-TEAM-123')
  })
})
