import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import RecruitCategoriesPage from '~/pages/villages/[id]/admin/recruit-categories.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/admin/recruit-categories.vue` は募集カテゴリ一覧の取得失敗時に
 * `categories` を空配列にリセットするだけで、PrimeVue DataTable の #empty スロット
 * （「カテゴリがありません」）をそのまま描画していた。エラー専用状態
 * （DashboardErrorState / recruit-category-error-state）で修正した。
 *
 * 検証観点:
 *   RC-001 取得失敗時に recruit-category-error-state が描画される
 *   RC-002（対照）取得成功・0件時はエラー状態を出さない（テーブルの空表示のみ）
 *   RC-003 再試行が初回表示と同じ取得関数（load）を呼ぶ
 */

const listCategories = vi.fn()

vi.mock('~/composables/village/useVillageRecruitCategoryApi', () => ({
  useVillageRecruitCategoryApi: () => ({
    listCategories,
    createCategory: vi.fn(),
    updateCategory: vi.fn(),
    deleteCategory: vi.fn(),
    reorderCategories: vi.fn(),
  }),
}))

vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: ref({ id: 'v1', name: 'テスト村' }),
    perms: ref({ isMember: true, isAdmin: true, isHeadman: true, myRole: 'HEADMAN' }),
    currentUserId: ref(1),
    refresh: vi.fn(async () => {}),
    myMembership: ref(null),
    openEditDialog: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn(), showSuccess: vi.fn(), showError: vi.fn(), showWarn: vi.fn(), showInfo: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listCategories.mockResolvedValue([])
  const warmup = await mountSuspended(RecruitCategoriesPage)
  warmup.unmount()
})

describe('pages/villages/[id]/admin/recruit-categories.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listCategories.mockReset()
    notificationMock.showError.mockClear()
  })

  it('RC-001: 取得失敗時に recruit-category-error-state が描画される', async () => {
    listCategories.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(RecruitCategoriesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="recruit-category-error-state"]').exists()).toBe(true)
  })

  it('RC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listCategories.mockResolvedValue([])
    const wrapper = await mountSuspended(RecruitCategoriesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="recruit-category-error-state"]').exists()).toBe(false)
  })

  it('RC-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listCategories.mockRejectedValueOnce(new Error('network error'))
    listCategories.mockResolvedValueOnce([{ id: 'c1', name: 'カテゴリA', isPreset: false }])
    const wrapper = await mountSuspended(RecruitCategoriesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="recruit-category-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="recruit-category-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listCategories).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="recruit-category-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
