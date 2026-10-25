// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { reactive, ref } from 'vue'
import FeatureDisplaySettingsPanel from './FeatureDisplaySettingsPanel.vue'

const { save, refresh, loadPermissions, success } = vi.hoisted(() => ({
  save: vi.fn(),
  refresh: vi.fn(),
  loadPermissions: vi.fn(),
  success: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
const features = ref([{ featureKey: 'chat', memberVisible: true, supporterVisible: false }])
const role = ref('ADMIN')
const auth = reactive({
  user: { id: 'viewer-a' },
  isAuthenticated: false,
  loadFromStorage: vi.fn(),
})
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: role,
  loading: ref(false),
  loadPermissions,
}))
mockNuxtImport('useScopeFeatureVisibility', () => () => ({
  features,
  ready: ref(true),
  loading: ref(false),
  error: ref(null),
  save,
  refresh,
}))
mockNuxtImport('useNotification', () => () => ({ success }))

function render() {
  return mount(FeatureDisplaySettingsPanel, {
    props: { scopeType: 'team', scopeId: 'team-a' },
    global: {
      mocks: { $t: (key: string) => key },
      stubs: {
        SectionCard: { template: '<section><slot /></section>' },
        PageLoading: true,
        DashboardErrorState: {
          props: ['error'],
          emits: ['retry'],
          template: `<button data-testid="retry" @click="$emit('retry')">Retry</button>`,
        },
        ToggleSwitch: {
          props: ['modelValue', 'disabled', 'inputId'],
          emits: ['update:modelValue'],
          template: `<input type="checkbox" :id="inputId" :checked="modelValue" :disabled="disabled" @change="$emit('update:modelValue', $event.target.checked)" />`,
        },
        Button: {
          props: ['label', 'loading'],
          template: '<button :disabled="loading">{{ label }}</button>',
        },
      },
    },
  })
}

describe('FeatureDisplaySettingsPanel', () => {
  beforeEach(() => {
    auth.user.id = 'viewer-a'
    role.value = 'ADMIN'
    features.value = [{ featureKey: 'chat', memberVisible: true, supporterVisible: false }]
    save.mockReset().mockResolvedValue(undefined)
    refresh.mockReset().mockImplementation(async () => {
      features.value = features.value.map((feature) => ({ ...feature }))
    })
    loadPermissions.mockReset().mockResolvedValue({ ok: true })
    success.mockReset()
  })

  it('独立2列を表示しfalse/trueを保存できる', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('#feature-display-chat-member').setValue(false)
    await wrapper.get('#feature-display-chat-supporter').setValue(true)
    await wrapper.get('[data-testid="feature-display-save"]').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith([
      { featureKey: 'chat', memberVisible: false, supporterVisible: true },
    ])
    expect(success).toHaveBeenCalledOnce()
  })

  it('保存失敗では編集中の値を保持し再試行できる', async () => {
    save.mockRejectedValueOnce(new Error('offline'))
    const wrapper = render()
    await flushPromises()
    await wrapper.get('#feature-display-chat-member').setValue(false)
    await wrapper.get('[data-testid="feature-display-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(true)
    expect((wrapper.get('#feature-display-chat-member').element as HTMLInputElement).checked).toBe(
      false,
    )
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(false)
  })

  it('保存中scopeを切り替えると新scopeで再び保存でき旧応答は通知しない', async () => {
    let finish!: () => void
    save.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[data-testid="feature-display-save"]').trigger('click')
    expect(wrapper.get('[data-testid="feature-display-save"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ scopeId: 'team-b' })
    await flushPromises()
    expect(
      wrapper.get('[data-testid="feature-display-save"]').attributes('disabled'),
    ).toBeUndefined()
    finish()
    await flushPromises()
    expect(success).not.toHaveBeenCalled()
  })

  it('保存中に本人が変わると旧保存の通知と状態を新しい本人へ反映しない', async () => {
    let finish!: () => void
    save.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[data-testid="feature-display-save"]').trigger('click')
    auth.user.id = 'viewer-b'
    await flushPromises()
    expect(
      wrapper.get('[data-testid="feature-display-save"]').attributes('disabled'),
    ).toBeUndefined()
    finish()
    await flushPromises()
    expect(success).not.toHaveBeenCalled()
  })

  it('旧scopeの権限取得失敗は新scope成功後にエラーを混入させない', async () => {
    let finish!: (value: { ok: boolean; error: Error }) => void
    loadPermissions.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = render()
    await wrapper.setProps({ scopeId: 'team-b' })
    await flushPromises()
    finish({ ok: false, error: new Error('old scope failed') })
    await flushPromises()
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(false)
  })

  it.each(['DEPUTY_ADMIN', 'SYSTEM_ADMIN'])('%sは設定編集と保存ができない', async (roleName) => {
    role.value = roleName
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('#feature-display-chat-member').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="feature-display-save"]').exists()).toBe(false)
  })
})
