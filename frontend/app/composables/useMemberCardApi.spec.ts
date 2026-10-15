import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useMemberCardApi } from './useMemberCardApi'
const api = vi.hoisted(() => vi.fn())
mockNuxtImport('useApi', () => () => api)

describe('useMemberCardApi 管理契約', () => {
  beforeEach(() => api.mockReset())
  it('会員証一覧に状態を明示して停止中の会員証も取得できる', async () => {
    api.mockResolvedValue({ data: [] })
    await useMemberCardApi().listByTeam('12', 'SUSPENDED')
    await useMemberCardApi().listByOrg('34', 'REVOKED')
    expect(api).toHaveBeenCalledWith('/api/v1/teams/12/member-cards?status=SUSPENDED')
    expect(api).toHaveBeenCalledWith('/api/v1/organizations/34/member-cards?status=REVOKED')
  })
  it('統計に必須の開始日と終了日を送る', async () => {
    api.mockResolvedValue({ data: { totalCheckins: 0 } })
    await useMemberCardApi().getTeamCheckinStats('12', { from: '2026-10-01', to: '2026-10-15' })
    expect(api).toHaveBeenCalledWith('/api/v1/teams/12/checkins/stats?from=2026-10-01&to=2026-10-15')
  })
})
