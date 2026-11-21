import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useVotingApi } from './useVotingApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
beforeEach(() => { vi.clearAllMocks() })

describe('投票APIのwire契約', () => {
  it.each([
    [{ scope_type: 'TEAM' as const, team_id: 42 }, '/api/v1/proxy-votes?scope_type=TEAM&team_id=42'],
    [{ scope_type: 'ORGANIZATION' as const, organization_id: 71 }, '/api/v1/proxy-votes?scope_type=ORGANIZATION&organization_id=71'],
    [{ scope_type: 'TEAM' as const, team_id: 42, status: 'OPEN' as const }, '/api/v1/proxy-votes?scope_type=TEAM&team_id=42&status=OPEN'],
  ])('正しいスコープ別queryを送る', async (query, url) => {
    const response = { data: [{ id: 1, resolutionMode: 'WRITTEN', motionCount: 0, eligibleCount: 0 }], meta: { total: 1, page: 0, size: 20, totalPages: 1 } }
    mocks.api.mockResolvedValueOnce(response)
    expect(await useVotingApi().getSessions(query)).toBe(response)
    expect(mocks.api).toHaveBeenCalledExactlyOnceWith(url)
  })
  it('作成DTOのcamelCase bodyを変換せずPOSTする', async () => {
    const body = { scopeType: 'TEAM', teamId: 42, resolutionMode: 'MEETING', title: '総会', meetingDate: '2026-11-10', isAnonymous: false }
    await useVotingApi().createSession(body)
    expect(mocks.api).toHaveBeenCalledExactlyOnceWith('/api/v1/proxy-votes', { method: 'POST', body })
  })
})
