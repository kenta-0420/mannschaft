import type {
  TournamentResponse,
  TournamentDivision,
  TournamentParticipant,
  TournamentMatchday,
  TournamentStanding,
  IndividualRanking,
  TournamentTemplate,
  TournamentPreset,
  TournamentMatrix,
  TournamentPromotion,
  TournamentRoster,
  TournamentMatch,
  TournamentHistory,
  TournamentTeamStats,
} from '~/types/tournament'

export function useTournamentApi() {
  const api = useApi()
  const b = (orgId: number) => `/api/v1/organizations/${orgId}`

  // === Tournaments ===
  async function getTournaments(orgId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) q.set(k, String(v))
      }
    return api<{ data: TournamentResponse[] }>(`${b(orgId)}/tournaments?${q}`)
  }
  async function getTournament(orgId: number, id: number) {
    return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments/${id}`)
  }
  async function createTournament(orgId: number, body: Record<string, unknown>) {
    return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments`, { method: 'POST', body })
  }
  async function updateTournament(orgId: number, id: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${id}`, { method: 'PATCH', body })
  }
  async function deleteTournament(orgId: number, id: number) {
    return api(`${b(orgId)}/tournaments/${id}`, { method: 'DELETE' })
  }
  async function continueTournament(orgId: number, previousTournamentId: number) {
    return api<{ data: TournamentResponse }>(
      `${b(orgId)}/tournaments/continue/${previousTournamentId}`,
      { method: 'POST' },
    )
  }
  async function updateTournamentStatus(orgId: number, id: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${id}/status`, { method: 'PATCH', body })
  }

  // === Divisions ===
  async function getDivisions(orgId: number, tId: number) {
    return api<{ data: TournamentDivision[] }>(`${b(orgId)}/tournaments/${tId}/divisions`)
  }
  async function createDivision(orgId: number, tId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions`, { method: 'POST', body })
  }
  async function updateDivision(
    orgId: number,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}`, { method: 'PATCH', body })
  }
  async function deleteDivision(orgId: number, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}`, { method: 'DELETE' })
  }

  // === Participants ===
  async function getParticipants(orgId: number, tId: number, divId: number) {
    return api<{ data: TournamentParticipant[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants`,
    )
  }
  async function addParticipant(
    orgId: number,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants`, {
      method: 'POST',
      body,
    })
  }
  async function updateParticipant(
    orgId: number,
    tId: number,
    divId: number,
    pId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}`, {
      method: 'PATCH',
      body,
    })
  }
  async function removeParticipant(orgId: number, tId: number, divId: number, pId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}`, {
      method: 'DELETE',
    })
  }

  // === Matchdays ===
  async function getMatchdays(orgId: number, tId: number, divId: number) {
    return api<{ data: TournamentMatchday[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`,
    )
  }
  async function createMatchday(
    orgId: number,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`, {
      method: 'POST',
      body,
    })
  }
  async function generateMatchdays(orgId: number, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/generate`, {
      method: 'POST',
    })
  }
  async function batchUpdateScores(
    orgId: number,
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
    orgId: number,
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
  async function getMatrix(orgId: number, tId: number, divId: number) {
    return api<{ data: TournamentMatrix }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix`,
    )
  }
  async function getMatrixPdf(orgId: number, tId: number, divId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix/pdf`)
  }

  // === Matches ===
  async function getMatch(orgId: number, tId: number, matchId: number) {
    return api<{ data: TournamentMatch }>(`${b(orgId)}/tournaments/${tId}/matches/${matchId}`)
  }
  async function updateMatchScore(
    orgId: number,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/score`, { method: 'PATCH', body })
  }
  async function updateMatchStatus(
    orgId: number,
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
    orgId: number,
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
  async function getRosters(orgId: number, tId: number, matchId: number) {
    return api<{ data: TournamentRoster[] }>(
      `${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`,
    )
  }
  async function addRoster(
    orgId: number,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`, {
      method: 'POST',
      body,
    })
  }
  async function removeRoster(orgId: number, tId: number, matchId: number, rosterId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters/${rosterId}`, {
      method: 'DELETE',
    })
  }

  // === Standings ===
  async function getStandings(orgId: number, tId: number, divId: number) {
    return api<{ data: TournamentStanding[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings`,
    )
  }
  async function getStandingsPdf(orgId: number, tId: number, divId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/pdf`)
  }
  async function recalculateStandings(orgId: number, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/recalculate`, {
      method: 'POST',
    })
  }

  // === Promotions ===
  async function getPromotions(orgId: number, tId: number) {
    return api<{ data: TournamentPromotion[] }>(`${b(orgId)}/tournaments/${tId}/promotions`)
  }
  async function createPromotion(orgId: number, tId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${tId}/promotions`, { method: 'POST', body })
  }
  async function previewPromotions(orgId: number, tId: number) {
    return api<{ data: TournamentPromotion[] }>(
      `${b(orgId)}/tournaments/${tId}/promotions/preview`,
      { method: 'POST' },
    )
  }

  // === Rankings ===
  async function getRankings(orgId: number, tId: number) {
    return api<{ data: IndividualRanking[] }>(`${b(orgId)}/tournaments/${tId}/rankings`)
  }
  async function getIndividualRankings(orgId: number, tId: number, statKey: string) {
    return api<{ data: IndividualRanking[] }>(`${b(orgId)}/tournaments/${tId}/rankings/${statKey}`)
  }
  async function getRankingsPdf(orgId: number, tId: number, statKey: string) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/rankings/${statKey}/pdf`)
  }

  // === Bracket PDF ===
  async function getBracketPdf(orgId: number, tId: number) {
    return api<Blob>(`${b(orgId)}/tournaments/${tId}/bracket/pdf`)
  }

  // === Templates ===
  async function getTemplates(orgId: number) {
    return api<{ data: TournamentTemplate[] }>(`${b(orgId)}/tournament-templates`)
  }
  async function getTemplate(orgId: number, templateId: number) {
    return api<{ data: TournamentTemplate }>(`${b(orgId)}/tournament-templates/${templateId}`)
  }
  async function createTemplate(orgId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournament-templates`, { method: 'POST', body })
  }
  async function updateTemplate(orgId: number, templateId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournament-templates/${templateId}`, { method: 'PATCH', body })
  }
  async function deleteTemplate(orgId: number, templateId: number) {
    return api(`${b(orgId)}/tournament-templates/${templateId}`, { method: 'DELETE' })
  }
  async function cloneTemplate(orgId: number, presetId: number) {
    return api<{ data: TournamentTemplate }>(`${b(orgId)}/tournament-templates/clone/${presetId}`, {
      method: 'POST',
    })
  }

  // === Presets ===
  async function getPresets() {
    return api<{ data: TournamentPreset[] }>('/api/v1/tournament-presets')
  }

  // === Team-scoped ===
  async function getTeamTournamentHistory(teamId: number) {
    return api<{ data: TournamentHistory[] }>(`/api/v1/teams/${teamId}/tournament-history`)
  }
  async function getTeamTournamentStats(teamId: number) {
    return api<{ data: TournamentTeamStats }>(`/api/v1/teams/${teamId}/tournament-stats`)
  }

  return {
    getTournaments,
    getTournament,
    createTournament,
    updateTournament,
    deleteTournament,
    continueTournament,
    updateTournamentStatus,
    getDivisions,
    createDivision,
    updateDivision,
    deleteDivision,
    getParticipants,
    addParticipant,
    updateParticipant,
    removeParticipant,
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
    getTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    cloneTemplate,
    getPresets,
    getTeamTournamentHistory,
    getTeamTournamentStats,
  }
}
