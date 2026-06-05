// チームの移籍状況APIを担当
// GET /api/v1/teams/{teamId}/league-transfers
import type { components } from '~/types/generated/index'

export type LeagueTransferResponse = components['schemas']['LeagueTransferResponse']

export function useTeamLeagueTransfer(teamId: string) {
  const api = useApi()

  /**
   * チームの移籍状況一覧
   * GET /teams/{teamId}/league-transfers
   */
  async function getTeamTransfers() {
    return api<{ data: LeagueTransferResponse[] }>(`/api/v1/teams/${teamId}/league-transfers`)
  }

  return {
    getTeamTransfers,
  }
}
