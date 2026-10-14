import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { TeamResponse } from '~/types/team'
import TeamPageHeader from './TeamPageHeader.vue'
const mocks = vi.hoisted(() => ({ request: vi.fn(), available: true }))
mockNuxtImport('useTeamArchiveAction', () => () => ({ available: ref(mocks.available), busy: ref(false), syncError: ref(false), retrySync: vi.fn(), request: mocks.request }))
const MenuStub = { props: ['model'], template: '<div />' }
const stubs = { ProfileHeader: { template: '<div><slot /></div>' }, FavoriteToggleButton: true,
  RoleBadge: true, Tag: true, Menu: MenuStub, BroadcastWizard: true,
  Button: { props: ['label', 'disabled'], template: '<button :disabled="disabled">{{ label }}</button>' } }
async function mountHeader() {
  return mountSuspended(TeamPageHeader, { props: {
    team: { id: 1, slug: 'team-a', timestamps: { archivedAt: null } } as unknown as TeamResponse,
    displayName: 'チーム', roleName: 'ADMIN', isAdmin: true, isAdminOrDeputy: true,
    followStatus: 'NONE', followLoading: false, followPermissionSyncError: false,
    joinRequestStatus: 'NONE', joinRequestLoading: false, templateLabel: {},
  }, global: { stubs } })
}
beforeEach(() => { vi.clearAllMocks(); mocks.available = true })
describe('チームヘッダーのアーカイブ導線', () => {
  it('デスクトップボタンとモバイルその他メニューから同じ確認処理へ到達する', async () => {
    const wrapper = await mountHeader()
    const button = wrapper.get('[data-testid="team-archive-button"]')
    expect(button.classes()).toContain('sm:inline-flex')
    await button.trigger('click')
    const items = wrapper.findComponent(MenuStub).props('model') as { icon: string, command: () => void }[]
    items.find(item => item.icon === 'pi pi-box')!.command()
    expect(mocks.request).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('操作権限のない人にはボタンとメニュー項目を出さない', async () => {
    mocks.available = false
    const wrapper = await mountHeader()
    expect(wrapper.find('[data-testid="team-archive-button"]').exists()).toBe(false)
    const items = wrapper.findComponent(MenuStub).props('model') as { icon: string }[]
    expect(items.some(item => item.icon === 'pi pi-box')).toBe(false)
    wrapper.unmount()
  })
})
