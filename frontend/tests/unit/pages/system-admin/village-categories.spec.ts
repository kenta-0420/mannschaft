import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import VillageCategoriesPage from '~/pages/system-admin/village-categories.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/system-admin/village-categories.vue` は村カテゴリ一覧の取得失敗時に
 * トーストは出すものの、`rawCategories` を空配列にリセットするだけで
 * 「カテゴリはまだ登録されていません」の空状態へ落ちていた。エラー専用状態
 * （DashboardErrorState / village-categories-error-state）で修正した。
 *
 * 検証観点:
 *   VC-001 取得失敗時に village-categories-error-state が描画される
 *   VC-002（対照）取得成功・0件時はエラー状態を出さない
 */

const fetchAdminCategories = vi.fn()

vi.mock('~/composables/useVillageCategoryApi', () => ({
  useVillageCategoryApi: () => ({
    fetchAdminCategories,
    createCategory: vi.fn(),
    updateCategory: vi.fn(),
    deleteCategory: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true }))

beforeAll(async () => {
  fetchAdminCategories.mockResolvedValue([])
  const warmup = await mountSuspended(VillageCategoriesPage)
  warmup.unmount()
})

describe('pages/system-admin/village-categories.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    fetchAdminCategories.mockReset()
    notificationMock.error.mockClear()
  })

  it('VC-001: 取得失敗時に village-categories-error-state が描画される', async () => {
    fetchAdminCategories.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(VillageCategoriesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-categories-error-state"]').exists()).toBe(true)
  })

  it('VC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    fetchAdminCategories.mockResolvedValue([])
    const wrapper = await mountSuspended(VillageCategoriesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-categories-error-state"]').exists()).toBe(false)
  })

  it('VC-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    fetchAdminCategories.mockRejectedValueOnce(new Error('network error'))
    fetchAdminCategories.mockResolvedValueOnce([])
    const wrapper = await mountSuspended(VillageCategoriesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-categories-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="village-categories-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(fetchAdminCategories).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="village-categories-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
