// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import VotingSessionList from './VotingSessionList.vue'

const state = {
  sessions: ref<{ id: number; title: string; resolutionMode: string; motionCount: number; eligibleCount: number }[]>([]),
  loading: ref(false), error: ref<unknown>(null), canCreate: ref(false), showCreateDialog: ref(false), saving: ref(false),
  form: reactive({ title: '', description: '', resolutionMode: 'WRITTEN', meetingDate: null, isAnonymous: false }),
  errors: ref({}), load: vi.fn(), openCreateDialog: vi.fn(), handleCreate: vi.fn(),
}
mockNuxtImport('useVotingSessions', () => () => state)
mockNuxtImport('useI18n', () => () => ({ t: (key: string, params?: { count: number }) => params ? `${key}:${params.count}` : key }))
mockNuxtImport('useRelativeTime', () => () => ({ relativeTime: () => '' }))
mockNuxtImport('useHead', () => vi.fn())
const stubs = {
  PageHeader: { props: ['title'], template: '<h1>{{ title }}</h1>' },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  PageLoading: { template: '<div data-test="loading" />' },
  DashboardErrorState: { props: ['showRetry'], emits: ['retry'], template: '<button v-if="showRetry" data-test="error" @click="$emit(\'retry\')">retry</button>' },
  DashboardEmptyState: { props: ['message'], template: '<div data-test="empty">{{ message }}</div>' },
  SectionCard: { template: '<section><slot /></section>' },
  Dialog: { template: '<div />' },
}
function render() { return mount(VotingSessionList, { props: { scopeType: 'TEAM', slug: 'alpha' }, global: { stubs } }) }
beforeEach(() => {
  vi.clearAllMocks()
  state.sessions.value = []; state.loading.value = false; state.error.value = null; state.canCreate.value = false
})
describe('投票一覧の表示状態', () => {
  it('成功0件だけを空表示にし、エラーは再試行を表示する', async () => {
    const wrapper = render()
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true)
    state.error.value = new Error('403'); await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(false)
    await wrapper.get('[data-test="error"]').trigger('click')
    expect(state.load).toHaveBeenCalledOnce(); wrapper.unmount()
  })
  it('motionsなしの一覧DTOを表示し0議案・0人を省略しない', () => {
    state.sessions.value = [{ id: 1, title: '総会', resolutionMode: 'WRITTEN', motionCount: 0, eligibleCount: 0 }]
    const wrapper = render()
    expect(wrapper.text()).toContain('総会')
    expect(wrapper.text()).toContain('voting.motions:0'); expect(wrapper.text()).toContain('voting.eligible:0')
    expect(wrapper.find('[data-test="empty"]').exists()).toBe(false); wrapper.unmount()
  })
  it('作成資格がある場合だけ入口を表示する', async () => {
    const wrapper = render()
    expect(wrapper.findAll('button').find(button => button.text() === 'voting.create')).toBeUndefined()
    state.canCreate.value = true; await wrapper.vm.$nextTick()
    await wrapper.get('button').trigger('click')
    expect(state.openCreateDialog).toHaveBeenCalledOnce(); wrapper.unmount()
  })
})
