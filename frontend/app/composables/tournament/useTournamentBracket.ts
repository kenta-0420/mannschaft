// ブラケット生成・試合進行（試合日・対戦表・試合・ロースター・順位表・昇降格・ランキング）を担当
import type {
  TournamentMatchday,
  TournamentMatrix,
  TournamentMatch,
  TournamentRoster,
  TournamentStanding,
  PromotionRecord,
  IndividualRanking,
} from '~/types/tournament'

export function useTournamentBracket() {
  const api = useApi()
  const b = (orgId: string) => `/api/v1/organizations/${orgId}`

  // === Matchdays ===
  async function getMatchdays(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentMatchday[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`,
    )
  }
  async function createMatchday(
    orgId: string,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`, {
      method: 'POST',
      body,
    })
  }
  async function generateMatchdays(orgId: string, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/generate`, {
      method: 'POST',
    })
  }
  async function batchUpdateScores(
    orgId: string,
    tId: number,
    divId: number,
    mdId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/${mdId}/scores/batch`, {
      method: 'POST',
      body,
    })
  }
  async function importScores(
    orgId: string,
    tId: number,
    divId: number,
    mdId: number,
    body: Record<string, unknown>,
  ) {
    return api(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/${mdId}/scores/import`,
      { method: 'POST', body },
    )
  }

  // === Matrix ===
  async function getMatrix(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentMatrix }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix`,
    )
  }
  async function getMatrixPdf(orgId: string, tId: number, divId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix/pdf`)
  }

  // === Matches ===
  async function getMatch(orgId: string, tId: number, matchId: number) {
    return api<{ data: TournamentMatch }>(`${b(orgId)}/tournaments/${tId}/matches/${matchId}`)
  }
  async function updateMatchScore(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/score`, { method: 'PATCH', body })
  }
  async function updateMatchStatus(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/status`, {
      method: 'PATCH',
      body,
    })
  }
  async function updatePlayerStats(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/player-stats`, {
      method: 'PATCH',
      body,
    })
  }

  // === Rosters ===
  async function getRosters(orgId: string, tId: number, matchId: number) {
    return api<{ data: TournamentRoster[] }>(
      `${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`,
    )
  }
  async function addRoster(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`, {
      method: 'POST',
      body,
    })
  }
  async function removeRoster(orgId: string, tId: number, matchId: number, rosterId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters/${rosterId}`, {
      method: 'DELETE',
    })
  }

  // === Standings ===
  async function getStandings(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentStanding[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings`,
    )
  }
  async function getStandingsPdf(orgId: string, tId: number, divId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/pdf`)
  }
  async function recalculateStandings(orgId: string, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/recalculate`, {
      method: 'POST',
    })
  }

  // === Promotions ===
  async function getPromotions(orgId: string, tId: number) {
    return api<{ data: PromotionRecord[] }>(`${b(orgId)}/tournaments/${tId}/promotions`)
  }
  async function createPromotion(orgId: string, tId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${tId}/promotions`, { method: 'POST', body })
  }
  async function previewPromotions(orgId: string, tId: number) {
    return api<{ data: PromotionRecord[] }>(
      `${b(orgId)}/tournaments/${tId}/promotions/preview`,
      { method: 'POST' },
    )
  }

  // === Rankings ===
  async function getRankings(orgId: string, tId: number) {
    return api<{ data: IndividualRanking[] }>(`${b(orgId)}/tournaments/${tId}/rankings`)
  }
  async function getIndividualRankings(orgId: string, tId: number, statKey: string) {
    return api<{ data: IndividualRanking[] }>(`${b(orgId)}/tournaments/${tId}/rankings/${statKey}`)
  }
  async function getRankingsPdf(orgId: string, tId: number, statKey: string) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/rankings/${statKey}/pdf`)
  }

  // === Bracket PDF ===
  async function getBracketPdf(orgId: string, tId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/bracket/pdf`)
  }

  return {
    getMatchdays,
    createMatchday,
    generateMatchdays,
    batchUpdateScores,
    importScores,
    getMatrix,
    getMatrixPdf,
    getMatch,
    updateMatchScore,
    updateMatchStatus,
    updatePlayerStats,
    getRosters,
    addRoster,
    removeRoster,
    getStandings,
    getStandingsPdf,
    recalculateStandings,
    getPromotions,
    createPromotion,
    previewPromotions,
    getRankings,
    getIndividualRankings,
    getRankingsPdf,
    getBracketPdf,
  }
}
