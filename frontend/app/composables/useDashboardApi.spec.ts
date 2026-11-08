// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDashboardApi } from './useDashboardApi'

const api = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
beforeEach(() => {
  api.mockReset()
  vi.stubGlobal('useApi', () => api)
})
afterEach(() => vi.unstubAllGlobals())

describe('スコープDashboardのwire契約', () => {
  it('TEAMのviewer_roleをviewerRoleへ変換し既存データと取得条件を保持する', async () => {
    const teamNotices = [{ id: 12 }]
    api.mockResolvedValue({ data: { viewer_role: 'MEMBER', teamNotices }, meta: { requestId: 'team' } })

    const response = await useDashboardApi().getTeamDashboard('team-a', 'month')

    expect(response.data.viewerRole).toBe('MEMBER')
    expect(response.data.teamNotices).toBe(teamNotices)
    expect(response).toHaveProperty('meta.requestId', 'team')
    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/dashboard/team/team-a?statsPeriod=month')
  })

  it('ORGのviewer_roleをviewerRoleへ変換し既存データと取得条件を保持する', async () => {
    const orgNotices = [{ id: 22 }]
    api.mockResolvedValue({ data: { viewer_role: 'SUPPORTER', orgNotices }, meta: { requestId: 'org' } })

    const response = await useDashboardApi().getOrganizationDashboard('org-a')

    expect(response.data.viewerRole).toBe('SUPPORTER')
    expect(response.data.orgNotices).toBe(orgNotices)
    expect(response).toHaveProperty('meta.requestId', 'org')
    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/dashboard/organization/org-a')
  })
})
