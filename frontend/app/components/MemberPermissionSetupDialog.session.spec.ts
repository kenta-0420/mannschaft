// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import MemberPermissionSetupDialog from './MemberPermissionSetupDialog.vue'
import common from '~/locales/ja/common.json'
import guide from '~/locales/ja/scope_guide.json'

const boundary = vi.hoisted(() => ({ api: vi.fn(), error: vi.fn() }))
mockNuxtImport('useApi', () => () => boundary.api)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: boundary.error }))
const initial = { data: { permissions: [
  { name: 'MANAGE_SCHEDULES', enabled: false, inherited: true },
  { name: 'MANAGE_FILES', enabled: false, inherited: true },
  { name: 'MANAGE_POSTS', enabled: false, inherited: true },
] } }
const Button = defineComponent({
  props: { label: String, disabled: Boolean }, emits: ['click'],
  setup: (props, { emit }) => () => h('button', {
    disabled: props.disabled, onClick: () => emit('click'),
  }, props.label),
})
const Dialog = defineComponent({
  props: { visible: Boolean },
  setup: (props, { slots }) => () => props.visible
    ? h('section', { role: 'dialog' }, [slots.default?.(), slots.footer?.()]) : null,
})
interface ScopeProps {
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId?: number
  slug: string
  roleName: string | null
}
function render(props: Partial<ScopeProps> = {}) {
  const i18n = createI18n({
    legacy: false, locale: 'ja',
    messages: { ja: { ...common, ...guide }, en: {}, zh: {}, ko: {}, es: {}, de: {} },
  })
  const wrapper = mount(MemberPermissionSetupDialog, {
    props: { scopeType: 'TEAM', scopeId: 12, slug: 'scope-12', roleName: 'ADMIN', ...props },
    global: { plugins: [i18n], stubs: { Dialog, Button, ToggleSwitch: true, NuxtLink: true } },
  })
  return { wrapper, later: i18n.global.t('scopeGuide.setup.later') }
}
beforeEach(() => {
  sessionStorage.clear()
  boundary.api.mockReset().mockResolvedValue(initial)
  boundary.error.mockReset()
})
afterEach(() => { sessionStorage.clear() })

// 既存sessionStorage契約のcharacterization。初回greenが正常で、製品redを主張しない。
describe('初期設定のあとで操作とsession境界（AC3/4/5）', () => {
  it.each(['TEAM', 'ORGANIZATION'] as const)('%sで通常あとで操作後、実unmount/remountでも再取得・再表示せず、新sessionでは開く', async scopeType => {
    const first = render({ scopeType })
    await flushPromises()
    expect(first.wrapper.find('[role="dialog"]').exists()).toBe(true)
    await first.wrapper.findAll('button').find(button => button.text() === first.later)!.trigger('click')
    expect(first.wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(boundary.api).toHaveBeenCalledTimes(1)
    first.wrapper.unmount()
    const remounted = render({ scopeType })
    await flushPromises()
    expect(remounted.wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(boundary.api).toHaveBeenCalledTimes(1)
    remounted.wrapper.unmount()
    // 同tab再mountでは保持。新session相当は保存領域を空にして明示的に分離する。
    sessionStorage.clear()
    const fresh = render({ scopeType })
    await flushPromises()
    expect(fresh.wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(boundary.api).toHaveBeenCalledTimes(2)
    expect(boundary.api.mock.calls.every(call => call[1]?.method === undefined)).toBe(true)
    fresh.wrapper.unmount()
  })

  it('同じ数値IDの別scopeTypeと別IDを抑止しない', async () => {
    const first = render()
    await flushPromises()
    await first.wrapper.findAll('button').find(button => button.text() === first.later)!.trigger('click')
    first.wrapper.unmount()
    for (const props of [{ scopeType: 'ORGANIZATION', scopeId: 12 }, { scopeType: 'TEAM', scopeId: 13 }] as const) {
      const next = render(props)
      await flushPromises()
      expect(next.wrapper.find('[role="dialog"]').exists()).toBe(true)
      next.wrapper.unmount()
    }
    expect(boundary.api).toHaveBeenCalledTimes(3)
  })

  it.each([{ scopeId: undefined }, { scopeId: 0 }, { roleName: null }, { roleName: 'SUPPORTER' }])('未解決ID/0/非管理者ではGETも表示もしない: %j', async props => {
    const { wrapper } = render(props)
    await flushPromises()
    expect(boundary.api).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('空設定では開かず、GET失敗をdismiss記録にせず次のmountで再取得できる', async () => {
    boundary.api.mockResolvedValueOnce({ data: { permissions: [] } })
    const empty = render()
    await flushPromises()
    expect(empty.wrapper.find('[role="dialog"]').exists()).toBe(false)
    empty.wrapper.unmount()
    boundary.api.mockRejectedValueOnce(new Error('controlled-read-failure'))
    const failed = render()
    await flushPromises()
    expect(failed.wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(boundary.error).toHaveBeenCalledOnce()
    expect(sessionStorage.length).toBe(0)
    failed.wrapper.unmount()
    const retry = render()
    await flushPromises()
    expect(retry.wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(boundary.api).toHaveBeenCalledTimes(3)
    retry.wrapper.unmount()
  })

  it('旧scopeの遅延GETが新scopeの設定済み状態を上書きしない', async () => {
    let finish!: (response: typeof initial) => void
    boundary.api.mockImplementationOnce(() => new Promise<typeof initial>(resolve => { finish = resolve }))
    const { wrapper } = render()
    boundary.api.mockResolvedValueOnce({ data: { permissions: initial.data.permissions.map(p => ({ ...p, inherited: false })) } })
    await wrapper.setProps({ scopeId: 13, slug: 'scope-13' })
    await flushPromises()
    finish(initial)
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(boundary.api).toHaveBeenCalledTimes(2)
    expect(boundary.api).toHaveBeenLastCalledWith('/api/v1/admin/member-permissions', { query: { scopeType: 'TEAM', scopeId: 13 } })
    wrapper.unmount()
  })
})
