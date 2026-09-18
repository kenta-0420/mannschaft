import type { GamificationConfig, PointRule, Badge, UserBadge, PointSummary, PointHistory, RankingEntry, GamificationPrivacy } from '~/types/gamification'

// CMP-260918-0024: ゲーミフィケーションはチーム固有機能（マスター裁可）。
// backend/src/main/java/com/mannschaft/app/gamification/ 配下の全コントローラは
// /api/v1/teams/{teamId}/gamification/... のチームスコープ専用実装で、組織スコープの
// API は存在しない。以前は `scopeType: 'team' | 'organization'` を受け取り組織向けの
// パスを組み立てる分岐があったが、対応する BE が無い死んだ分岐だったため撤去しチーム固定にした。
export function useGamificationApi() {
  const api = useApi()

  function buildBase(teamId: string) {
    return `/api/v1/teams/${teamId}`
  }

  async function getConfig(teamId: string) {
    const res = await api<{ data: GamificationConfig }>(`${buildBase(teamId)}/gamification/config`)
    return res.data
  }

  async function updateConfig(teamId: string, config: GamificationConfig) {
    await api(`${buildBase(teamId)}/gamification/config`, { method: 'PUT', body: config })
  }

  async function listPointRules(teamId: string) {
    const res = await api<{ data: PointRule[] }>(`/api/v1/teams/${teamId}/gamification/point-rules`)
    return res.data
  }

  async function listBadges(teamId: string) {
    const res = await api<{ data: Badge[] }>(`/api/v1/teams/${teamId}/gamification/badges`)
    return res.data
  }

  async function getMyPoints(teamId: string) {
    const res = await api<{ data: PointSummary }>(`/api/v1/teams/${teamId}/gamification/points/me`)
    return res.data
  }

  async function getMyPointHistory(teamId: string, cursor?: string) {
    const qs = cursor ? `?cursor=${cursor}` : ''
    const res = await api<{ data: PointHistory[]; meta: { nextCursor: string | null } }>(
      `/api/v1/teams/${teamId}/gamification/points/me/history${qs}`,
    )
    return res
  }

  async function getRankings(teamId: string, period: 'WEEKLY' | 'MONTHLY' | 'YEARLY') {
    const res = await api<{ data: RankingEntry[] }>(`/api/v1/teams/${teamId}/gamification/rankings?period=${period}`)
    return res.data
  }

  async function getMyBadges(teamId: string) {
    const res = await api<{ data: UserBadge[] }>(`/api/v1/teams/${teamId}/gamification/badges/me`)
    return res.data
  }

  async function getPrivacy(teamId: string) {
    const res = await api<{ data: GamificationPrivacy }>(`/api/v1/teams/${teamId}/gamification/settings/me`)
    return res.data
  }

  async function updatePrivacy(teamId: string, settings: GamificationPrivacy) {
    await api(`/api/v1/teams/${teamId}/gamification/settings/me`, { method: 'PUT', body: settings })
  }

  return { getConfig, updateConfig, listPointRules, listBadges, getMyPoints, getMyPointHistory, getRankings, getMyBadges, getPrivacy, updatePrivacy }
}
