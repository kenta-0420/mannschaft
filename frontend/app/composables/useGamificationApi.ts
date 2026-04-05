import type { GamificationConfig, PointRule, Badge, UserBadge, PointSummary, PointHistory, RankingEntry, GamificationPrivacy } from '~/types/gamification'

export function useGamificationApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function getConfig(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: GamificationConfig }>(`${base}/gamification/config`)
    return res.data
  }

  async function updateConfig(scopeType: 'team' | 'organization', scopeId: number, config: GamificationConfig) {
    const base = buildBase(scopeType, scopeId)
    await api(`${base}/gamification/config`, { method: 'PUT', body: config })
  }

  async function listPointRules(teamId: number) {
    const res = await api<{ data: PointRule[] }>(`/api/v1/teams/${teamId}/gamification/point-rules`)
    return res.data
  }

  async function listBadges(teamId: number) {
    const res = await api<{ data: Badge[] }>(`/api/v1/teams/${teamId}/gamification/badges`)
    return res.data
  }

  async function getMyPoints(teamId: number) {
    const res = await api<{ data: PointSummary }>(`/api/v1/teams/${teamId}/gamification/points/me`)
    return res.data
  }

  async function getMyPointHistory(teamId: number, cursor?: string) {
    const qs = cursor ? `?cursor=${cursor}` : ''
    const res = await api<{ data: PointHistory[]; meta: { nextCursor: string | null } }>(
      `/api/v1/teams/${teamId}/gamification/points/me/history${qs}`,
    )
    return res
  }

  async function getRankings(teamId: number, period: 'WEEKLY' | 'MONTHLY' | 'YEARLY') {
    const res = await api<{ data: RankingEntry[] }>(`/api/v1/teams/${teamId}/gamification/rankings?period=${period}`)
    return res.data
  }

  async function getMyBadges(teamId: number) {
    const res = await api<{ data: UserBadge[] }>(`/api/v1/teams/${teamId}/gamification/badges/me`)
    return res.data
  }

  async function getPrivacy(teamId: number) {
    const res = await api<{ data: GamificationPrivacy }>(`/api/v1/teams/${teamId}/gamification/settings/me`)
    return res.data
  }

  async function updatePrivacy(teamId: number, settings: GamificationPrivacy) {
    await api(`/api/v1/teams/${teamId}/gamification/settings/me`, { method: 'PUT', body: settings })
  }

  return { getConfig, updateConfig, listPointRules, listBadges, getMyPoints, getMyPointHistory, getRankings, getMyBadges, getPrivacy, updatePrivacy }
}
