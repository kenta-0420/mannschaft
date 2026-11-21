// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AllocationsPage from './allocations.vue'

/**
 * CMP-260909-0543: 一覧取得の失敗を「割当が登録されていません」の空状態と区別し、再試行できること。
 */
const api = vi.hoisted(() => ({
  listAllocations: vi.fn(),
  createAllocation: vi.fn(),
  updateAllocation: vi.fn(),
  deleteAllocation: vi.fn(),
}))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: ref(true), ensureLoaded: vi.fn() }),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: '10' } }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useShiftBudgetApi', () => () => api)

const DataTableStub = defineComponent({
  name: 'DataTable',
  props: { value: { type: Array, default: () => [] } },
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

function mountPage() {
  return mount(AllocationsPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        Message: true,
        Column: true,
        Dialog: true,
        Button: true,
        AllocationFormModal: true,
        ConsumptionRateBadge: true,
        DataTable: DataTableStub,
        DashboardErrorState: ErrorStateStub,
      },
    },
  })
}

describe('予算割当ページ 一覧取得失敗の表示', () => {
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
