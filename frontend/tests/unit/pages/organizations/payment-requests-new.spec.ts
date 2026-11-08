import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref, computed } from 'vue'
import NewPaymentRequestPage from '~/pages/organizations/[slug]/payment-requests/new.vue'

/**
 * F01.2.1 8-C 検分3巡目 — 請求作成画面は、親シェルの所属チーム一覧（支払元の選択肢）を使う。
 * 一覧の取得中・取得失敗のときに、古い候補で送信させない。
 *
 *   PR-01 取得中は支払元の選択と送信ができない
 *   PR-02 取得失敗はエラーと再試行を出し、古い候補でも送信させない（API を呼ばない）
 *   PR-03 再試行で回復したら選択肢が出て、送信ボタンが有効になる
 *   PR-04 正常時は支払元を選べて、送信ボタンが有効
 */

const shell = {
  orgTeams: ref([{ id: 11, slug: 't1', name: 'チーム1', nickname1: null }] as Array<Record<string, unknown>>),
  orgTeamsError: ref(false),
  orgTeamsLoading: ref(false),
}
const refreshOrgTeams = vi.fn()
const createPaymentRequest = vi.fn()

vi.mock('~/composables/useOrgShellContext', () => ({
  useOrgShellContext: () => ({
    isAdminOrDeputy: computed(() => true),
    orgTeams: shell.orgTeams,
    orgTeamsError: shell.orgTeamsError,
    orgTeamsLoading: shell.orgTeamsLoading,
    refreshOrgTeams,
  }),
}))
mockNuxtImport('useActivityScopeId', () => () => ({ resolveScopeId: async () => 1 }))
mockNuxtImport('usePaymentRequestApi', () => () => ({ createPaymentRequest }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn(), handleError: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' }, query: {} }))

const stubs = {
  PageHeader: true,
  PageLoading: true,
  SectionCard: { template: '<div><slot /></div>' },
  Message: { template: '<div><slot /></div>' },
  Select: {
    props: ['modelValue', 'options', 'disabled'],
    emits: ['update:modelValue'],
    template: `<select data-testid="payer-select" :disabled="disabled" @change="$emit('update:modelValue', Number($event.target.value))">
      <option value="">-</option>
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>`,
  },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Textarea: { template: '<textarea />' },
  InputNumber: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input data-testid="amount" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
  },
  DatePicker: {
    emits: ['update:modelValue'],
    template: '<button type="button" data-testid="due" @click="$emit(\'update:modelValue\', new Date(2026, 11, 31))" />',
  },
  Button: {
    props: ['label', 'type', 'disabled', 'loading'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

async function mountPage() {
  const wrapper = await mountSuspended(NewPaymentRequestPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('payment-requests/new.vue', () => {
  beforeEach(() => {
    shell.orgTeams.value = [{ id: 11, slug: 't1', name: 'チーム1', nickname1: null }]
    shell.orgTeamsError.value = false
    shell.orgTeamsLoading.value = false
    refreshOrgTeams.mockReset()
    refreshOrgTeams.mockResolvedValue(undefined)
    createPaymentRequest.mockReset()
    createPaymentRequest.mockResolvedValue(undefined)
  })

  it('PR-01: 取得中は支払元の選択と送信ができない', async () => {
    shell.orgTeamsLoading.value = true
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="payer-select"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('PR-02: 取得失敗はエラーと再試行を出し、古い候補でも送信させない', async () => {
    shell.orgTeamsError.value = true
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="payer-teams-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="payer-teams-retry"]').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    // 古い候補は選択肢として出さない（選べない）
    expect(wrapper.find('[data-testid="payer-select"]').attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(createPaymentRequest).not.toHaveBeenCalled()
  })

  it('PR-03: 再試行で回復したら選択肢が出て、送信できる', async () => {
    shell.orgTeamsError.value = true
    refreshOrgTeams.mockImplementation(async () => {
      shell.orgTeamsError.value = false
    })
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="payer-teams-retry"]').trigger('click')
    await flushPromises()
    expect(refreshOrgTeams).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="payer-teams-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="payer-select"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="payer-select"]').text()).toContain('チーム1')
  })

  it('PR-04: 正常時は支払元を選べて、送信ボタンが有効', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="payer-teams-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="payer-select"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
