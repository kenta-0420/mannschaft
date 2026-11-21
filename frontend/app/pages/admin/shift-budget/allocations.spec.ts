// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AllocationsPage from './allocations.vue'

/**
 * CMP-260910-1602: 保存 API の失敗時、BE が返した理由を握りつぶさず画面（トースト＋ダイアログ）へ渡す。
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

const Modal = defineComponent({
  name: 'AllocationFormModal',
  props: { visible: Boolean, allocation: null, fieldErrors: null, formError: null },
  emits: ['update:visible', 'submit-create', 'submit-update'],
  setup(p, { emit }) {
    return () => h('button', {
      'data-test': 'modal',
      'data-form-error': p.formError ?? '',
      'data-field-errors': JSON.stringify(p.fieldErrors ?? {}),
      onClick: () => emit('submit-create', {}),
    })
  },
})

function mountPage() {
  return mount(AllocationsPage, {
    global: {
      stubs: {
        AllocationFormModal: Modal,
        PageHeader: true,
        PageLoading: true,
        Message: true,
        DataTable: true,
        Column: true,
        Dialog: true,
        Button: true,
        ConsumptionRateBadge: true,
      },
    },
  })
}

describe('予算割当ページ 保存失敗時のエラー表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listAllocations.mockResolvedValue({ items: [], total: 0 })
  })

  it('作成失敗時、BE の message をトーストとダイアログに出し、fieldErrors を渡す', async () => {
    api.createAllocation.mockRejectedValue({
      status: 409,
      data: {
        error: {
          code: 'SHIFT_BUDGET_011',
          message: '同一スコープのシフト予算割当が既に存在します',
          fieldErrors: [{ field: 'periodStart', message: '開始日は必須です' }],
        },
      },
    })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[data-test="modal"]').trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalledWith('同一スコープのシフト予算割当が既に存在します')
    const modal = wrapper.find('[data-test="modal"]')
    expect(modal.attributes('data-form-error')).toBe('同一スコープのシフト予算割当が既に存在します')
    expect(JSON.parse(modal.attributes('data-field-errors')!)).toEqual({ periodStart: '開始日は必須です' })
  })

  it('BE が理由を返さないときだけ汎用文言を出す', async () => {
    api.createAllocation.mockRejectedValue(new Error('network'))
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.find('[data-test="modal"]').trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalledWith('shiftBudget.allocation.createError')
  })
})
