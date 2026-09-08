import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../../app/stores/useAuthStore'

const { teamClear, organizationClear, chatClear } = vi.hoisted(() => ({
  teamClear: vi.fn(),
  organizationClear: vi.fn(),
  chatClear: vi.fn(),
}))

vi.mock('../../../app/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: teamClear }) }))
vi.mock('../../../app/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: organizationClear }) }))

describe('useAuthStore.logout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    teamClear.mockClear()
    organizationClear.mockClear()
    chatClear.mockClear()
    vi.stubGlobal('useChatTabsStore', () => ({ clearAll: chatClear }))
    vi.stubGlobal('navigateTo', vi.fn())
    vi.stubGlobal('disarmProactiveRefresh', vi.fn())
  })

  it('ユーザー切替前に所属チーム・組織ストアを同期的に破棄する', async () => {
    await useAuthStore().logout()

    expect(teamClear).toHaveBeenCalledOnce()
    expect(organizationClear).toHaveBeenCalledOnce()
  })
})
