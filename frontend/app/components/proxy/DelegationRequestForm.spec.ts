// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import DelegationRequestForm from './DelegationRequestForm.vue'

const mocks = vi.hoisted(() => ({ team: vi.fn(), org: vi.fn(), members: vi.fn(), list: vi.fn(), error: vi.fn() }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.team, getMembers: mocks.members }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: mocks.org, getMembers: mocks.members }))
mockNuxtImport('useVotingApi', () => () => ({ getSessions: mocks.list }))
mockNuxtImport('useScheduleDelegationApi', () => () => ({ createDelegation: vi.fn() }))
mockNuxtImport('useEventDelegationApi', () => () => ({ createDelegation: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), warn: vi.fn(), success: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const stubs = {
  Select: { name: 'SelectStub', props: ['options'], template: '<div />' },
  Checkbox: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button data-test="proxy-toggle" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  Textarea: true,
  Button: true,
  DashboardErrorState: { props: ['showRetry'], emits: ['retry'], template: '<button v-if="showRetry" data-test="retry" @click="$emit(\'retry\')" />' },
}
function render(scopeType: 'team' | 'organization' = 'team', targetType: 'event' | 'schedule' = 'event') {
  return mount(DelegationRequestForm, {
    props: { scopeType, scopeId: 'alpha-slug', targetType, targetId: 9 },
    global: { stubs, mocks: { $t: (key: string) => key } },
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.team.mockResolvedValue({ data: { numericId: 42 } })
  mocks.org.mockResolvedValue({ data: { numericId: 71 } })
  mocks.members.mockResolvedValue({ data: [] })
  mocks.list.mockResolvedValue({ data: [{ id: 5, title: '公開総会', resolutionMode: 'MEETING', motionCount: 0, eligibleCount: 0 }], meta: { total: 1 } })
})
describe('代理出席フォームの既存投票セッション選択', () => {
  it.each(['team', 'organization'] as const)('%sのslugを内部IDに解決してOPEN一覧を取得する', async scopeType => {
    const wrapper = render(scopeType); await flushPromises()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith(scopeType === 'team'
      ? { scope_type: 'TEAM', team_id: 42, status: 'OPEN' }
      : { scope_type: 'ORGANIZATION', organization_id: 71, status: 'OPEN' })
    expect(scopeType === 'team' ? mocks.team : mocks.org).toHaveBeenCalledExactlyOnceWith('alpha-slug')
    await wrapper.get('[data-test="proxy-toggle"]').trigger('click')
    expect(wrapper.findAllComponents({ name: 'SelectStub' })[1]!.props('options')).toEqual([{ label: '公開総会', value: 5 }])
    wrapper.unmount()
  })
  it('scheduleでは投票スコープ解決も一覧も呼ばない', async () => {
    const wrapper = render('team', 'schedule'); await flushPromises()
    expect(mocks.team).not.toHaveBeenCalled(); expect(mocks.list).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('ID解決失敗は一覧を呼ばず、再試行で解決からやり直す', async () => {
    mocks.team.mockRejectedValueOnce(new Error('404'))
    const wrapper = render(); await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled(); expect(mocks.error).toHaveBeenCalledOnce()
    await wrapper.get('[data-test="retry"]').trigger('click'); await flushPromises()
    expect(mocks.team).toHaveBeenCalledTimes(2); expect(mocks.list).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-test="retry"]').exists()).toBe(false); wrapper.unmount()
  })
  it('成功0件はエラーにせず、失敗は候補なしと区別する', async () => {
    mocks.list.mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('[data-test="proxy-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="retry"]').exists()).toBe(false)
    mocks.list.mockRejectedValueOnce(new Error('500'))
    await wrapper.setProps({ scopeId: 'beta-slug' }); await flushPromises()
    expect(wrapper.find('[data-test="retry"]').exists()).toBe(true); wrapper.unmount()
  })
  it('遅い旧scopeの一覧を新scopeへ反映しない', async () => {
    let finish!: (value: { data: { id: number; title: string }[] }) => void
    mocks.list.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = render(); await flushPromises()
    mocks.list.mockResolvedValueOnce({ data: [{ id: 8, title: '新総会' }], meta: { total: 1 } })
    await wrapper.setProps({ scopeType: 'organization', scopeId: 'beta-slug' }); await flushPromises()
    finish({ data: [{ id: 5, title: '旧総会' }] }); await flushPromises()
    await wrapper.get('[data-test="proxy-toggle"]').trigger('click')
    expect(wrapper.findAllComponents({ name: 'SelectStub' })[1]!.props('options')).toEqual([{ label: '新総会', value: 8 }])
    wrapper.unmount()
  })
})
