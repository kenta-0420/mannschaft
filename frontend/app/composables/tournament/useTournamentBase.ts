// トーナメント本体・区分・テンプレート・プリセット・公開エンドポイントを担当
import type {
  TournamentResponse,
  TournamentDivision,
  TournamentTemplate,
  TournamentPreset,
  TournamentStanding,
  TournamentMatrix,
  IndividualRanking,
} from '~/types/tournament'

export function useTournamentBase() {
  const api = useApi()
  const b = (orgId: string) => `/api/v1/organizations/${orgId}`

  // === Tournaments ===
  async function getTournaments(orgId: string, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) q.set(k, String(v))
      }
    return api<{ data: TournamentResponse[] }>(`${b(orgId)}/tournaments?${q}`)
  }
  async function getTournament(orgId: string, id: number) {
    return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments/${id}`)
  }
  async function createTournament(orgId: string, body: Record<string, unknown>) {
    return api<{ data: TournamentResponse }>(`${b(orgId)}/tournaments`, { method: 'POST', body })
  }
  async function updateTournament(orgId: string, id: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${id}`, { method: 'PATCH', body })
  }
  async function deleteTournament(orgId: string, id: number) {
    return api(`${b(orgId)}/tournaments/${id}`, { method: 'DELETE' })
  }
  async function continueTournament(orgId: string, previousTournamentId: number) {
    return api<{ data: TournamentResponse }>(
      `${b(orgId)}/tournaments/continue/${previousTournamentId}`,
      { method: 'POST' },
    )
  }
  async function updateTournamentStatus(orgId: string, id: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${id}/status`, { method: 'PATCH', body })
  }

  // === Divisions ===
  async function getDivisions(orgId: string, tId: number) {
    return api<{ data: TournamentDivision[] }>(`${b(orgId)}/tournaments/${tId}/divisions`)
  }
  async function createDivision(orgId: string, tId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions`, { method: 'POST', body })
  }
  async function updateDivision(
    orgId: string,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}`, { method: 'PATCH', body })
  }
  async function deleteDivision(orgId: string, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}`, { method: 'DELETE' })
  }

  // === Templates ===
  async function getTemplates(orgId: string) {
    return api<{ data: TournamentTemplate[] }>(`${b(orgId)}/tournament-templates`)
  }
  async function getTemplate(orgId: string, templateId: number) {
    return api<{ data: TournamentTemplate }>(`${b(orgId)}/tournament-templates/${templateId}`)
  }
  async function createTemplate(orgId: string, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournament-templates`, { method: 'POST', body })
  }
  async function updateTemplate(orgId: string, templateId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournament-templates/${templateId}`, { method: 'PATCH', body })
  }
  async function deleteTemplate(orgId: string, templateId: number) {
    return api(`${b(orgId)}/tournament-templates/${templateId}`, { method: 'DELETE' })
  }
  async function cloneTemplate(orgId: string, presetId: number) {
    return api<{ data: TournamentTemplate }>(`${b(orgId)}/tournament-templates/clone/${presetId}`, {
      method: 'POST',
    })
  }

  // === Presets ===
  async function getPresets() {
    return api<{ data: TournamentPreset[] }>('/api/v1/tournament-presets')
  }

  // === Public (no auth) ===
  async function getPublicTournaments(orgId: string) {
    return api<{ data: TournamentResponse[] }>(`/api/v1/public/organizations/${orgId}/tournaments`)
  }
  async function getPublicTournament(orgId: string, tId: number) {
    return api<{ data: TournamentResponse }>(
      `/api/v1/public/organizations/${orgId}/tournaments/${tId}`,
    )
  }
  async function getPublicStandings(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentStanding[] }>(
      `/api/v1/public/organizations/${orgId}/tournaments/${tId}/divisions/${divId}/standings`,
    )
  }
  async function getPublicMatrix(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentMatrix }>(
      `/api/v1/public/organizations/${orgId}/tournaments/${tId}/divisions/${divId}/matrix`,
    )
  }
  async function getPublicRankings(orgId: string, tId: number, statKey: string) {
    return api<{ data: IndividualRanking[] }>(
      `/api/v1/public/organizations/${orgId}/tournaments/${tId}/rankings/${statKey}`,
    )
  }
  async function getPublicBracket(orgId: string, tId: number) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/public/organizations/${orgId}/tournaments/${tId}/bracket`,
    )
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
    getTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    cloneTemplate,
    getPresets,
    getPublicTournaments,
    getPublicTournament,
    getPublicStandings,
    getPublicMatrix,
    getPublicRankings,
    getPublicBracket,
  }
}
