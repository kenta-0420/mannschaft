import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useOrgTeamMembershipApi } from './useOrgTeamMembershipApi'

const api = vi.hoisted(() => vi.fn())
mockNuxtImport('useApi', () => () => api)

describe('useOrgTeamMembershipApi.removeTeam', () => {
  beforeEach(() => {
    api.mockReset()
  })

  it('組織とチームの slug をエンコードして body なし DELETE を送り、204 を扱う', async () => {
    api.mockResolvedValue(undefined)
    await expect(useOrgTeamMembershipApi().removeTeam('org/a', 'team b')).resolves.toBeUndefined()
    expect(api).toHaveBeenCalledWith('/api/v1/organizations/org%2Fa/teams/team%20b', {
      method: 'DELETE',
    })
  })

  it('未加盟404は成功に変換せず呼び出し元へ返す', async () => {
    const error = { statusCode: 404, data: { error: { code: 'TEAM_070' } } }
    api.mockRejectedValue(error)
    await expect(useOrgTeamMembershipApi().removeTeam('org', 'team')).rejects.toBe(error)
  })
})
