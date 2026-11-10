import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useChatScopeIds } from '~/composables/useChatScopeIds'

const getTeam = vi.fn()
const getOrganization = vi.fn()
mockNuxtImport('useTeamApi', () => () => ({ getTeam }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization }))

beforeEach(() => {
  getTeam.mockReset()
  getOrganization.mockReset()
})

describe('useChatScopeIds.resolveScopeIds', () => {
  it('チームの slug を数値 ID（numericId）へ解決する', async () => {
    getTeam.mockResolvedValue({ data: { id: 'team-a', numericId: 12 } })
    expect(await useChatScopeIds().resolveScopeIds({ teamId: 'team-a' })).toEqual({ teamId: 12 })
    expect(getTeam).toHaveBeenCalledWith('team-a')
  })

  it('組織の slug を数値 ID へ解決する', async () => {
    getOrganization.mockResolvedValue({ data: { id: 'org-a', numericId: 34 } })
    expect(await useChatScopeIds().resolveScopeIds({ organizationId: 'org-a' })).toEqual({ organizationId: 34 })
  })

  it('どちらも無ければ空（横断チャット）', async () => {
    expect(await useChatScopeIds().resolveScopeIds({})).toEqual({})
  })

  it('numericId が無ければ握りつぶさず例外にする', async () => {
    getTeam.mockResolvedValue({ data: { id: 'team-a' } })
    await expect(useChatScopeIds().resolveScopeIds({ teamId: 'team-a' })).rejects.toThrow()
  })
})
