import type {
  MatchRequestResponse,
  MatchProposalResponse,
  MatchReviewSummary,
  NgTeamResponse,
  MatchRequestTemplateResponse,
  MatchRequestSearchParams,
  PrefectureResponse,
  CityResponse,
} from '~/types/matching'

export function useMatchingApi() {
  const api = useApi()

  function qs(params: Record<string, unknown>): string {
    const q = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) q.set(k, String(v))
    }
    return q.toString()
  }

  // === Requests ===
  async function searchRequests(params: MatchRequestSearchParams) {
    return api<{ data: MatchRequestResponse[]; meta: Record<string, unknown> }>(
      `/api/v1/matching/requests?${qs(params as Record<string, unknown>)}`,
    )
  }
  async function getRequest(id: number) {
    return api<{ data: MatchRequestResponse }>(`/api/v1/matching/requests/${id}`)
  }
  async function getTeamRequests(teamId: number) {
    return api<{ data: MatchRequestResponse[] }>(`/api/v1/teams/${teamId}/matching/requests`)
  }
  async function createRequest(teamId: number, body: Record<string, unknown>) {
    return api<{ data: MatchRequestResponse }>(`/api/v1/teams/${teamId}/matching/requests`, {
      method: 'POST',
      body,
    })
  }
  async function updateRequest(id: number, body: Record<string, unknown>) {
    return api<{ data: MatchRequestResponse }>(`/api/v1/matching/requests/${id}`, {
      method: 'PUT',
      body,
    })
  }
  async function deleteRequest(id: number) {
    return api(`/api/v1/matching/requests/${id}`, { method: 'DELETE' })
  }

  // === Proposals ===
  async function propose(teamId: number, requestId: number, body: Record<string, unknown>) {
    return api<{ data: MatchProposalResponse }>(
      `/api/v1/teams/${teamId}/matching/requests/${requestId}/propose`,
      { method: 'POST', body },
    )
  }
  async function getProposals(requestId: number) {
    return api<{ data: MatchProposalResponse[] }>(
      `/api/v1/matching/requests/${requestId}/proposals`,
    )
  }
  async function getTeamProposals(teamId: number) {
    return api<{ data: MatchProposalResponse[] }>(`/api/v1/teams/${teamId}/matching/proposals`)
  }
  async function acceptProposal(id: number, body?: Record<string, unknown>) {
    return api(`/api/v1/matching/proposals/${id}/accept`, { method: 'PATCH', body })
  }
  async function rejectProposal(id: number, body?: Record<string, unknown>) {
    return api(`/api/v1/matching/proposals/${id}/reject`, { method: 'PATCH', body })
  }
  async function withdrawProposal(id: number) {
    return api(`/api/v1/matching/proposals/${id}/withdraw`, { method: 'PATCH' })
  }
  async function cancelProposal(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/matching/proposals/${id}/cancel`, { method: 'PATCH', body })
  }
  async function agreeCancelProposal(id: number) {
    return api(`/api/v1/matching/proposals/${id}/agree-cancel`, { method: 'PATCH' })
  }

  // === Reviews ===
  async function createReview(body: Record<string, unknown>) {
    return api('/api/v1/matching/reviews', { method: 'POST', body })
  }
  async function getTeamReviews(teamId: number) {
    return api<{ data: MatchReviewSummary }>(`/api/v1/teams/${teamId}/matching/reviews`)
  }

  // === NG Teams ===
  async function getNgTeams(teamId: number) {
    return api<{ data: NgTeamResponse[] }>(`/api/v1/teams/${teamId}/matching/ng-teams`)
  }
  async function addNgTeam(teamId: number, blockedTeamId: number, reason?: string) {
    return api(`/api/v1/teams/${teamId}/matching/ng-teams`, {
      method: 'POST',
      body: { blocked_team_id: blockedTeamId, reason },
    })
  }
  async function removeNgTeam(teamId: number, blockedTeamId: number) {
    return api(`/api/v1/teams/${teamId}/matching/ng-teams/${blockedTeamId}`, { method: 'DELETE' })
  }

  // === Templates ===
  async function getTemplates(teamId: number) {
    return api<{ data: MatchRequestTemplateResponse[] }>(
      `/api/v1/teams/${teamId}/matching/templates`,
    )
  }
  async function createTemplate(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/matching/templates`, { method: 'POST', body })
  }
  async function updateTemplate(teamId: number, id: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/matching/templates/${id}`, { method: 'PUT', body })
  }
  async function deleteTemplate(teamId: number, id: number) {
    return api(`/api/v1/teams/${teamId}/matching/templates/${id}`, { method: 'DELETE' })
  }

  // === Cancellations ===
  async function getTeamCancellations(teamId: number) {
    return api(`/api/v1/teams/${teamId}/matching/cancellations`)
  }

  // === Activity Suggestions ===
  async function getActivitySuggestions() {
    return api('/api/v1/matching/activity-suggestions')
  }

  // === Master data ===
  async function getPrefectures() {
    return api<{ data: PrefectureResponse[] }>('/api/v1/master/prefectures')
  }
  async function getCities(prefCode: string) {
    return api<{ data: CityResponse[] }>(`/api/v1/master/prefectures/${prefCode}/cities`)
  }

  return {
    searchRequests,
    getRequest,
    getTeamRequests,
    createRequest,
    updateRequest,
    deleteRequest,
    propose,
    getProposals,
    getTeamProposals,
    acceptProposal,
    rejectProposal,
    withdrawProposal,
    cancelProposal,
    agreeCancelProposal,
    createReview,
    getTeamReviews,
    getNgTeams,
    addNgTeam,
    removeNgTeam,
    getTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    getTeamCancellations,
    getActivitySuggestions,
    getPrefectures,
    getCities,
  }
}
