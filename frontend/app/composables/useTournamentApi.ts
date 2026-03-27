import type { TournamentResponse, TournamentDivision, TournamentParticipant, TournamentMatchday, TournamentStanding, IndividualRanking, TournamentTemplate } from '~/types/tournament'

export function useTournamentApi() {
  const api = useApi()
  const b = (orgId: number) => `/api/v1/organizations/${orgId}`

  // === Tournaments ===
  async function getTournaments(orgId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) q.set(k, String(v)) }
    return api<{ data: TournamentResponse[] }>(`${b(orgId)}/tournaments?${q}`)
  }
  async function getTournament(orgId: number, id: number) { return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments/${id}`) }
  async function createTournament(orgId: number, body: Record<string, unknown>) { return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments`, { method: 'POST', body }) }
  async function updateTournament(orgId: number, id: number, body: Record<string, unknown>) { return api(`${b(orgId)}/tournaments/${id}`, { method: 'PUT', body }) }
  async function deleteTournament(orgId: number, id: number) { return api(`${b(orgId)}/tournaments/${id}`, { method: 'DELETE' }) }

  // === Divisions ===
  async function getDivisions(orgId: number, tournamentId: number) { return api<{ data: TournamentDivision[] }>(`${b(orgId)}/tournaments/${tournamentId}/divisions`) }
  async function createDivision(orgId: number, tournamentId: number, body: Record<string, unknown>) { return api(`${b(orgId)}/tournaments/${tournamentId}/divisions`, { method: 'POST', body }) }

  // === Participants ===
  async function getParticipants(orgId: number, tournamentId: number, divisionId: number) { return api<{ data: TournamentParticipant[] }>(`${b(orgId)}/tournaments/${tournamentId}/divisions/${divisionId}/participants`) }
  async function addParticipant(orgId: number, tournamentId: number, divisionId: number, teamId: number) { return api(`${b(orgId)}/tournaments/${tournamentId}/divisions/${divisionId}/participants`, { method: 'POST', body: { teamId } }) }
  async function removeParticipant(orgId: number, tournamentId: number, divisionId: number, participantId: number) { return api(`${b(orgId)}/tournaments/${tournamentId}/divisions/${divisionId}/participants/${participantId}`, { method: 'DELETE' }) }

  // === Matchdays & Matches ===
  async function getMatchdays(orgId: number, tournamentId: number, divisionId: number) { return api<{ data: TournamentMatchday[] }>(`${b(orgId)}/tournaments/${tournamentId}/divisions/${divisionId}/matchdays`) }
  async function updateMatchResult(orgId: number, tournamentId: number, matchId: number, body: Record<string, unknown>) { return api(`${b(orgId)}/tournaments/${tournamentId}/matches/${matchId}`, { method: 'PUT', body }) }

  // === Standings & Rankings ===
  async function getStandings(orgId: number, tournamentId: number, divisionId: number) { return api<{ data: TournamentStanding[] }>(`${b(orgId)}/tournaments/${tournamentId}/divisions/${divisionId}/standings`) }
  async function getIndividualRankings(orgId: number, tournamentId: number, statKey: string) { return api<{ data: IndividualRanking[] }>(`${b(orgId)}/tournaments/${tournamentId}/rankings/${statKey}`) }

  // === Templates ===
  async function getTemplates(orgId: number) { return api<{ data: TournamentTemplate[] }>(`${b(orgId)}/tournament-templates`) }
  async function createTemplate(orgId: number, body: Record<string, unknown>) { return api(`${b(orgId)}/tournament-templates`, { method: 'POST', body }) }

  return { getTournaments, getTournament, createTournament, updateTournament, deleteTournament, getDivisions, createDivision, getParticipants, addParticipant, removeParticipant, getMatchdays, updateMatchResult, getStandings, getIndividualRankings, getTemplates, createTemplate }
}
