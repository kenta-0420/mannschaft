import type { SurveyResponse, SurveyDetailResponse, SurveyResultSummary } from '~/types/survey'

export function useSurveyApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  /** Convert 'TEAM'/'ORGANIZATION' or 'teams'/'organizations' to path segment */
  function toPathSegment(scopeType: string): string {
    const s = scopeType.toLowerCase()
    if (s === 'team' || s === 'teams') return 'teams'
    if (s === 'organization' || s === 'organizations') return 'organizations'
    return s
  }

  // === Surveys CRUD ===
  async function getSurveys(
    scopeType: string,
    scopeId: number,
    params?: Record<string, unknown> | string,
  ) {
    const resolvedParams = typeof params === 'string' ? { status: params } : params || {}
    const qs = buildQuery(resolvedParams)
    return api<{
      data: SurveyResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys?${qs}`)
  }

  async function getSurveyStats(scopeType: string, scopeId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/stats`)
  }

  async function getSurvey(scopeType: string, scopeId: number, surveyId: number) {
    return api<SurveyDetailResponse>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`,
    )
  }

  async function createSurvey(scopeType: string, scopeId: number, body: Record<string, unknown>) {
    return api<{ data: SurveyResponse }>(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys`, {
      method: 'POST',
      body,
    })
  }

  async function updateSurvey(
    scopeType: string,
    scopeId: number,
    surveyId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SurveyResponse }>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteSurvey(scopeType: string, scopeId: number, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`, {
      method: 'DELETE',
    })
  }

  async function publishSurvey(scopeType: string, scopeId: number, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/publish`, {
      method: 'POST',
    })
  }

  async function closeSurvey(scopeType: string, scopeId: number, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/close`, {
      method: 'POST',
    })
  }

  // === Questions ===
  async function addQuestion(
    scopeType: string,
    scopeId: number,
    surveyId: number,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/questions`, {
      method: 'POST',
      body,
    })
  }

  async function deleteQuestion(
    scopeType: string,
    scopeId: number,
    surveyId: number,
    questionId: number,
  ) {
    return api(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/questions/${questionId}`,
      { method: 'DELETE' },
    )
  }

  // === Responses ===
  async function submitResponse(surveyId: number, body: Record<string, unknown>) {
    return api(`/api/v1/surveys/${surveyId}/responses`, { method: 'POST', body })
  }

  async function getMyResponse(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}/responses/me`)
  }

  // === Targets ===
  async function setTargets(surveyId: number, body: Record<string, unknown>) {
    return api(`/api/v1/surveys/${surveyId}/targets`, { method: 'POST', body })
  }

  // === Result Viewers ===
  async function setResultViewers(surveyId: number, body: Record<string, unknown>) {
    return api(`/api/v1/surveys/${surveyId}/result-viewers`, { method: 'POST', body })
  }

  // === Results ===
  async function getResults(surveyId: number) {
    return api<{ data: SurveyResultSummary[] }>(`/api/v1/surveys/${surveyId}/results`)
  }

  return {
    getSurveys,
    getSurveyStats,
    getSurvey,
    createSurvey,
    updateSurvey,
    deleteSurvey,
    publishSurvey,
    closeSurvey,
    addQuestion,
    deleteQuestion,
    submitResponse,
    getMyResponse,
    setTargets,
    setResultViewers,
    getResults,
  }
}
