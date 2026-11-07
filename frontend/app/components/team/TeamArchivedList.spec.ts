import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamArchivedList from './TeamArchivedList.vue'
const mocks = vi.hoisted(() => ({ api: vi.fn(), handleError: vi.fn(), fetchMyTeams: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleError }))
mockNuxtImport('useTeamStore', () => () => ({ fetchMyTeamsWithResult: mocks.fetchMyTeams }))
const Row = { props: ['team', 'refresh'], template: '<button data-testid="restore-stub" @click="refresh()">{{ team.name }}</button>' }
const stubs = { SectionCard: { template: '<div><slot /></div>' },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' }, PageLoading: true,
  DashboardErrorState: { emits: ['retry'], template: '<button data-testid="error" @click="$emit(\'retry\')" />' },
  DashboardEmptyState: { template: '<div data-testid="empty" />' }, TeamUnarchiveButton: Row }
beforeEach(() => {
  vi.clearAllMocks()
  mocks.fetchMyTeams.mockResolvedValue({ ok: true })
  mocks.api.mockResolvedValue({ data: [{ id: 1, slug: 'active', name: '使用中', isArchived: false },
    { id: 2, slug: 'archived', name: 'アーカイブ済み', isArchived: true, canManageArchive: true }] })
})
describe('アーカイブ済みチーム一覧', () => {
  it('表示した時に正規一覧を一度取得しアーカイブ済みだけを表示する', async () => {
    const wrapper = await mountSuspended(TeamArchivedList, { global: { stubs } })
    expect(mocks.api).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="archived-teams-toggle"]').trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledExactlyOnceWith('/api/v1/me/teams', { query: { includeArchived: true } })
    expect(wrapper.findAllComponents(Row)).toHaveLength(1)
    expect(wrapper.findComponent(Row).props('team').slug).toBe('archived')
    wrapper.unmount()
  })
  it('解除後の再取得で消えた行と使用中一覧を同期する', async () => {
    const wrapper = await mountSuspended(TeamArchivedList, { global: { stubs } })
    await wrapper.get('[data-testid="archived-teams-toggle"]').trigger('click')
    await flushPromises()
    mocks.api.mockResolvedValueOnce({ data: [] })
    await wrapper.get('[data-testid="restore-stub"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents(Row)).toHaveLength(0)
    expect(mocks.api).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('取得失敗は空一覧にせずエラーと再試行を表示する', async () => {
    mocks.api.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountSuspended(TeamArchivedList, { global: { stubs } })
    await wrapper.get('[data-testid="archived-teams-toggle"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
    await wrapper.get('[data-testid="error"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents(Row)).toHaveLength(1)
    wrapper.unmount()
  })
})
