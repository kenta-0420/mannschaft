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

  async function getSurveys(scopeType: string, scopeId: number, status?: string, page = 0) {
    const qs = buildQuery({ scope_type: scopeType, scope_id: scopeId, status, page, size: 20 })
    return api<{ data: SurveyResponse[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/surveys?${qs}`)
  }

  async function getSurvey(surveyId: number) {
    return api<SurveyDetailResponse>(`/api/v1/surveys/${surveyId}`)
  }

  async function createSurvey(body: Record<string, unknown>) {
    return api<{ data: SurveyResponse }>('/api/v1/surveys', { method: 'POST', body })
  }

  async function updateSurvey(surveyId: number, body: Record<string, unknown>) {
    return api<{ data: SurveyResponse }>(`/api/v1/surveys/${surveyId}`, { method: 'PUT', body })
  }

  async function deleteSurvey(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}`, { method: 'DELETE' })
  }

  async function publishSurvey(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}/publish`, { method: 'PATCH' })
  }

  async function closeSurvey(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}/close`, { method: 'PATCH' })
  }

  async function submitResponse(surveyId: number, answers: Array<Record<string, unknown>>) {
    return api(`/api/v1/surveys/${surveyId}/responses`, { method: 'POST', body: { answers } })
  }

  async function getMyResponse(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}/responses/my`)
  }

  async function getResults(surveyId: number) {
    return api<{ data: SurveyResultSummary[] }>(`/api/v1/surveys/${surveyId}/results`)
  }

  async function exportResults(surveyId: number) {
    return api(`/api/v1/surveys/${surveyId}/results/export`)
  }

  return {
    getSurveys, getSurvey, createSurvey, updateSurvey, deleteSurvey,
    publishSurvey, closeSurvey, submitResponse, getMyResponse, getResults, exportResults,
  }
}
