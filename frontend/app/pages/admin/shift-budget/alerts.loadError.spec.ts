// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AlertsPage from './alerts.vue'

/**
 * CMP-260909-0543: 警告一覧の取得失敗を、「警告なし」の空状態と区別する。
 */
const api = vi.hoisted(() => ({ listAlerts: vi.fn(), acknowledgeAlert: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: ref(true), ensureLoaded: vi.fn() }),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: '10' } }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useShiftBudgetApi', () => () => api)

const ListStub = defineComponent({
  name: 'ThresholdAlertList',
  props: { alerts: { type: Array, default: () => [] }, canAcknowledge: Boolean },
  setup() {
    return () => h('div', { 'data-test': 'alert-list' })
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
  return mount(AlertsPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        Message: true,
        Dialog: true,
        Button: true,
        Textarea: true,
        ThresholdAlertList: ListStub,
        DashboardErrorState: ErrorStateStub,
      },
    },
  })
}

describe('警告履歴ページ 取得失敗の表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each([
    ['500', { status: 500 }],
    ['403', { status: 403 }],
    ['ネットワーク断', new TypeError('Failed to fetch')],
  ])('%s で失敗したら一覧(空状態)でなくエラー表示になる', async (_label, error) => {
    api.listAlerts.mockRejectedValue(error)
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="alert-list"]').exists()).toBe(false)
  })

  it('成功0件は従来どおり一覧（空状態）で、エラー表示は出ない', async () => {
    api.listAlerts.mockResolvedValue([])
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="alert-list"]').exists()).toBe(true)
  })

  it('再試行で成功すればエラー表示が消える', async () => {
    api.listAlerts.mockRejectedValueOnce({ status: 500 })
    api.listAlerts.mockResolvedValueOnce([])
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[data-test="error-state"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="error-state"]').exists()).toBe(false)
    expect(api.listAlerts).toHaveBeenCalledTimes(2)
  })
})
