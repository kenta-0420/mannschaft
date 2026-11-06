// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useScopeFeatureVisibility } from './useScopeFeatureVisibility'
import type { FeatureDisplayRole } from '~/types/feature-display'

const { api } = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
const shared = ref({})
const app = {}
mockNuxtImport('useNuxtApp', () => () => app)
mockNuxtImport('useState', () => () => shared)
const auth = reactive({
  user: { id: 'viewer-a' },
  isAuthenticated: false,
  loadFromStorage: vi.fn(),
})
mockNuxtImport('useAuthStore', () => () => auth)

function setup(role: FeatureDisplayRole = 'MEMBER') {
  const scope = ref('scope-a')
  const viewer = ref(role)
  const lens = ref(false)
  let policy!: ReturnType<typeof useScopeFeatureVisibility>
  const wrapper = mount(
    defineComponent({
      setup() {
        policy = useScopeFeatureVisibility('team', scope, viewer, lens)
        return () => null
      },
    }),
  )
  return {
    scope,
    viewer,
    lens,
    wrapper,
    get policy() {
      return policy
    },
  }
}
const response = (memberVisible: boolean, supporterVisible: boolean) => ({
  data: { features: [{ featureKey: 'chat', memberVisible, supporterVisible }] },
})

describe('useScopeFeatureVisibility', () => {
  beforeEach(() => {
    shared.value = {}
    auth.user.id = 'viewer-a'
    api.mockReset()
    api.mockResolvedValue(response(true, true))
  })

  it.each([
    [true, true],
    [true, false],
    [false, true],
    [false, false],
  ])('MEMBER/SUPPORTERの独立bool %s/%sを適用する', async (member, supporter) => {
    api.mockResolvedValue(response(member, supporter))
    const context = setup()
    await flushPromises()
    expect(context.policy.isVisible('chat')).toBe(member)
    context.viewer.value = 'SUPPORTER'
    expect(context.policy.isVisible('chat')).toBe(supporter)
    context.lens.value = true
    expect(context.policy.isVisible('chat')).toBe(supporter)
    context.lens.value = false
    context.viewer.value = 'ADMIN'
    expect(context.policy.isVisible('chat')).toBe(true)
    context.lens.value = true
    expect(context.policy.isVisible('chat')).toBe(member)
    expect(api).toHaveBeenCalledTimes(1)
    context.wrapper.unmount()
  })

  it('同一scopeは共有1GETで取得し未設定キーは従来表示を保つ', async () => {
    const first = setup()
    const second = setup()
    await flushPromises()
    expect(api).toHaveBeenCalledTimes(1)
    expect(second.policy.isVisible('schedule')).toBe(true)
    first.wrapper.unmount()
    second.wrapper.unmount()
  })

  it.each<FeatureDisplayRole>(['PUBLIC', null, 'GUEST'])(
    '%sはpolicy APIを呼ばず公開表示を維持する',
    async (role) => {
      const context = setup(role)
      await flushPromises()
      expect(api).not.toHaveBeenCalled()
      expect(context.policy.isVisible('chat')).toBe(true)
      context.wrapper.unmount()
    },
  )

  it('PUT前に開始した古いGETは保存後の再取得を上書きしない', async () => {
    const context = setup()
    await flushPromises()
    let finishOld!: (value: ReturnType<typeof response>) => void
    api.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    const old = context.policy.refresh(true)
    await flushPromises()
    api
      .mockResolvedValueOnce({ data: { features: [] } })
      .mockResolvedValueOnce(response(false, true))
    await context.policy.save([
      { featureKey: 'chat', memberVisible: false, supporterVisible: true },
    ])
    expect(context.policy.isVisible('chat')).toBe(false)
    finishOld(response(true, true))
    await old
    expect(context.policy.isVisible('chat')).toBe(false)
    expect(api).toHaveBeenCalledTimes(4)
    context.wrapper.unmount()
  })

  it('失敗は既定trueへ戻さず再試行で回復する', async () => {
    api.mockRejectedValueOnce(new Error('offline'))
    const context = setup()
    expect(context.policy.ready.value).toBe(false)
    expect(context.policy.isVisible('chat')).toBe(false)
    await flushPromises()
    expect(context.policy.error.value).toBeInstanceOf(Error)
    expect(context.policy.isVisible('chat')).toBe(false)
    await context.policy.refresh(true)
    expect(context.policy.ready.value).toBe(true)
    context.wrapper.unmount()
  })

  it('scopeとユーザーの切替直後に旧設定を捨て遅延応答を破棄する', async () => {
    let finish!: (value: ReturnType<typeof response>) => void
    api.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const context = setup()
    await flushPromises()
    context.scope.value = 'scope-b'
    expect(context.policy.ready.value).toBe(false)
    await flushPromises()
    expect(context.policy.isVisible('chat')).toBe(true)
    finish(response(false, false))
    await flushPromises()
    expect(context.policy.isVisible('chat')).toBe(true)
    api.mockResolvedValue(response(false, true))
    auth.user.id = 'viewer-b'
    expect(context.policy.ready.value).toBe(false)
    await flushPromises()
    expect(context.policy.isVisible('chat')).toBe(false)
    expect(api).toHaveBeenCalledTimes(3)
    context.wrapper.unmount()
  })
})
