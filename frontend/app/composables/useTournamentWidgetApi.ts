import type {
  TeamTournamentStats,
  TeamTournamentHistory,
  TournamentStanding,
  OrganizationTournamentSummary,
} from '~/types/tournament'

/**
 * F08.7.1 / 02: 成績ウィジェット用 API クライアント。
 *
 * - 自チーム成績: tournament-stats + tournament-history（既存流用）
 * - 順位表: tournament-history → standings の 2 段（既存流用）
 * - 主催大会サマリ: organizations/{orgId}/tournaments/summary（新設・集約）
 */
export function useTournamentWidgetApi() {
  const api = useApi()

  /** チーム通算成績 */
  async function getTeamStats(teamId: number) {
    return api<{ data: TeamTournamentStats }>(`/api/v1/teams/${teamId}/tournament-stats`)
  }

  /** チーム大会参加履歴 */
  async function getTeamHistory(teamId: number) {
    return api<{ data: TeamTournamentHistory }>(`/api/v1/teams/${teamId}/tournament-history`)
  }

  /** ディビジョン順位表 */
  async function getStandings(orgId: number, tournamentId: number, divisionId: number) {
    return api<{ data: TournamentStanding[] }>(
      `/api/v1/organizations/${orgId}/tournaments/${tournamentId}/divisions/${divisionId}/standings`,
    )
  }

  /** 主催大会サマリ（集約） */
  async function getOrganizationSummary(orgId: number) {
    return api<{ data: OrganizationTournamentSummary }>(
      `/api/v1/organizations/${orgId}/tournaments/summary`,
    )
  }

  return { getTeamStats, getTeamHistory, getStandings, getOrganizationSummary }
}
