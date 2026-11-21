// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SignageScopePage from './SignageScopePage.vue'

const { getTeam, getOrganization } = vi.hoisted(() => ({
  getTeam: vi.fn(),
  getOrganization: vi.fn(),
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization }))
const Manager = defineComponent({
  props: ['scopeType', 'scopeId'],
  setup: (props) => () => h('p', { 'data-testid': 'manager' }, `${props.scopeType}:${props.scopeId}`),
})
const ErrorState = defineComponent({
  emits: ['retry'],
  setup: (_, { emit }) => () => h('button', { onClick: () => emit('retry') }, 'retry'),
})

function render(scopeType: 'TEAM' | 'ORGANIZATION' = 'TEAM') {
  return mount(SignageScopePage, {
    props: { scopeType, slug: 'scope-slug' },
    global: { stubs: { SignageManager: Manager, DashboardErrorState: ErrorState, PageLoading: true } },
  })
}

beforeEach(() => {
  getTeam.mockReset()
  getOrganization.mockReset()
})

describe('サイネージ管理のslug解決', () => {
  it.each(['TEAM', 'ORGANIZATION'] as const)('%sの詳細APIのnumericIdだけをManagerへ渡す', async (scopeType) => {
    const api = scopeType === 'TEAM' ? getTeam : getOrganization
    api.mockResolvedValue({ data: { id: 'scope-slug', numericId: 42 } })
    const wrapper = render(scopeType)
    expect(wrapper.findComponent(Manager).exists()).toBe(false)
    await flushPromises()
    expect(api).toHaveBeenCalledWith('scope-slug')
    expect(wrapper.findComponent(Manager).props('scopeId')).toBe('42')
    wrapper.unmount()
  })

  it.each([null, undefined, 0, -1, 1.5, '42'])('不正numericId=%sではManagerを起動せず再試行できる', async (numericId) => {
    getTeam.mockResolvedValueOnce({ data: { numericId } }).mockResolvedValueOnce({ data: { numericId: 43 } })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findComponent(Manager).exists()).toBe(false)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(Manager).props('scopeId')).toBe('43')
    wrapper.unmount()
  })

  it('取得失敗を表示し、同じ解決処理で再試行する', async () => {
    getTeam.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: { numericId: 44 } })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findComponent(Manager).exists()).toBe(false)
    expect(wrapper.findComponent(ErrorState).exists()).toBe(true)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(getTeam).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(Manager).props('scopeId')).toBe('44')
    wrapper.unmount()
  })

  it('slug切替後は旧IDを破棄し、遅れた応答を採用しない', async () => {
    let finishOld!: (value: { data: { numericId: number } }) => void
    let finishNext!: (value: { data: { numericId: number } }) => void
    getTeam.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishNext = resolve }))
    const wrapper = render()
    await wrapper.setProps({ slug: 'next-slug' })
    expect(wrapper.findComponent(Manager).exists()).toBe(false)
    finishNext({ data: { numericId: 45 } })
    await flushPromises()
    expect(wrapper.findComponent(Manager).props('scopeId')).toBe('45')
    finishOld({ data: { numericId: 42 } })
    await flushPromises()
    expect(wrapper.findComponent(Manager).props('scopeId')).toBe('45')
    wrapper.unmount()
  })
})
