import type {
  DisclosureRequest,
  WithholdRequest,
  DisclosureResponse,
  DisclosedEvaluationResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceDisclosureApi() {
  const api = useApi()

  // POST /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclose
  async function disclose(
    teamId: number,
    evaluationId: number,
    req: DisclosureRequest,
  ): Promise<DisclosureResponse> {
    const res = await api<ApiResponse<DisclosureResponse>>(
      `/api/v1/teams/${teamId}/attendance/requirements/evaluations/${evaluationId}/disclose`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  // POST /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/withhold
  async function withhold(
    teamId: number,
    evaluationId: number,
    req: WithholdRequest,
  ): Promise<DisclosureResponse> {
    const res = await api<ApiResponse<DisclosureResponse>>(
      `/api/v1/teams/${teamId}/attendance/requirements/evaluations/${evaluationId}/withhold`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  // GET /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclosure-history
  async function getDisclosureHistory(
    teamId: number,
    evaluationId: number,
  ): Promise<DisclosureResponse[]> {
    const res = await api<ApiResponse<DisclosureResponse[]>>(
      `/api/v1/teams/${teamId}/attendance/requirements/evaluations/${evaluationId}/disclosure-history`,
    )
    return res.data
  }

  // GET /api/v1/me/attendance/requirements/disclosed
  async function getMyDisclosedEvaluations(): Promise<DisclosedEvaluationResponse[]> {
    const res = await api<ApiResponse<DisclosedEvaluationResponse[]>>(
      `/api/v1/me/attendance/requirements/disclosed`,
    )
    return res.data
  }

  return { disclose, withhold, getDisclosureHistory, getMyDisclosedEvaluations }
}
