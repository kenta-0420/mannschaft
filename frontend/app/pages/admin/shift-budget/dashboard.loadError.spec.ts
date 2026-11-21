// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import DashboardPage from './dashboard.vue'

/**
 * CMP-260909-0543: ダッシュボードの一覧取得失敗を、KPI 0 件・「割当が登録されていません」と区別する。
 */
const api = vi.hoisted(() => ({ listAllocations: vi.fn(), executeMonthlyClose: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: ref(true), ensureLoaded: vi.fn() }),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: '10' } }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useShiftBudgetApi', () => () => api)
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const DataTableStub = defineComponent({
  name: 'DataTable',
  setup(_, { slots }) {
    return () => h('div', { 'data-test': 'table' }, slots.empty?.())
  },
})
const ErrorStateStub = defineComponent({
  name: 'DashboardErrorState',
  props: { error: null, testid: String },
  emits: ['retry'],
  setup(p, { emit }) {
    return () => h('button', { 'data-test': 'error-state', onClick: () => emit('retry') }, p.testid)
  },
})
// Card はスロットを描画するスタブ（失敗時に KPI や一覧が出ないことを確認するため）
const CardStub = defineComponent({
  name: 'Card',
  setup(_, { slots }) {
    return () => h('div', [slots.title?.(), slots.content?.()])
  },
})

function mountPage() {
  return mount(DashboardPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        Message: true,
        Column: true,
        Dialog: true,
        Button: true,
        InputText: true,
        NuxtLink: true,
        ConsumptionRateBadge: true,
        Card: CardStub,
        DataTable: DataTableStub,
        DashboardErrorState: ErrorStateStub,
      },
    },
  })
}

describe('予算ダッシュボード 取得失敗の表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each([
    ['500', { status: 500 }],
    ['403', { status: 403 }],
    ['ネットワーク断', new TypeError('Failed to fetch')],
  ])('%s で失敗したら空状態でなくエラー表示になる', async (_label, error) => {
    api.listAllocations.mockRejectedValue(error)
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="table"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('shiftBudget.allocation.empty')
    expect(wrapper.text()).not.toContain('shiftBudget.dashboard.kpi.ok')
  })

  it('成功0件は従来どおり空状態で、エラー表示は出ない', async () => {
    api.listAllocations.mockResolvedValue({ items: [], total: 0 })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('shiftBudget.allocation.empty')
  })

  it('再試行で成功すればエラー表示が消える', async () => {
    api.listAllocations.mockRejectedValueOnce({ status: 500 })
    api.listAllocations.mockResolvedValueOnce({ items: [], total: 0 })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[data-test="error-state"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(false)
    expect(api.listAllocations).toHaveBeenCalledTimes(2)
  })
})
