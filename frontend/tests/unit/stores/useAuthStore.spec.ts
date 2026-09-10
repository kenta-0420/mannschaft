import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '../../../app/stores/useAuthStore'

const { teamClear, organizationClear, chatClear, navigateToMock } = vi.hoisted(() => ({
  teamClear: vi.fn(),
  organizationClear: vi.fn(),
  chatClear: vi.fn(),
  navigateToMock: vi.fn(),
}))

vi.mock('../../../app/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: teamClear }) }))
vi.mock('../../../app/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: organizationClear }) }))
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigateToMock(...args))

describe('useAuthStore.logout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    teamClear.mockClear()
    organizationClear.mockClear()
    chatClear.mockClear()
    navigateToMock.mockReset()
    vi.stubGlobal('useChatTabsStore', () => ({ clearAll: chatClear }))
    vi.stubGlobal('history', { state: null, pushState: vi.fn(), replaceState: vi.fn() })
    vi.stubGlobal('disarmProactiveRefresh', vi.fn())
  })

  it('ユーザー切替前に所属チーム・組織ストアを同期的に破棄する', async () => {
    await useAuthStore().logout()

    expect(teamClear).toHaveBeenCalledOnce()
    expect(organizationClear).toHaveBeenCalledOnce()
    expect(navigateToMock).toHaveBeenCalledWith('/login')
  })
})
