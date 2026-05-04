import type {
  AttendanceRequirementEvaluation,
  AtRiskStudentResponse,
  EvaluationStatus,
  ResolveEvaluationRequest,
  ResolveEvaluationResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceEvaluationApi() {
  const api = useApi()

  // GET /api/v1/students/{studentId}/attendance/requirements/evaluations
  async function getStudentEvaluations(
    studentId: number,
  ): Promise<AttendanceRequirementEvaluation[]> {
    const res = await api<ApiResponse<AttendanceRequirementEvaluation[]>>(
      `/api/v1/students/${studentId}/attendance/requirements/evaluations`,
    )
    return res.data
  }

  // GET /api/v1/teams/{teamId}/attendance/requirements/at-risk?status=RISK,VIOLATION
  async function getAtRiskStudents(
    teamId: number,
    statuses?: EvaluationStatus[],
  ): Promise<AtRiskStudentResponse[]> {
    const url = `/api/v1/teams/${teamId}/attendance/requirements/at-risk`
    if (statuses && statuses.length > 0) {
      const params = new URLSearchParams({ status: statuses.join(',') })
      const res = await api<ApiResponse<AtRiskStudentResponse[]>>(`${url}?${params}`)
      return res.data
    }
    const res = await api<ApiResponse<AtRiskStudentResponse[]>>(url)
    return res.data
  }

  // POST /api/v1/students/{studentId}/attendance/requirements/{ruleId}/evaluate
  async function evaluateStudent(
    studentId: number,
    ruleId: number,
  ): Promise<AttendanceRequirementEvaluation> {
    const res = await api<ApiResponse<AttendanceRequirementEvaluation>>(
      `/api/v1/students/${studentId}/attendance/requirements/${ruleId}/evaluate`,
      { method: 'POST' },
    )
    return res.data
  }

  // POST /api/v1/attendance/requirements/evaluations/{evaluationId}/resolve
  async function resolveViolation(
    evaluationId: number,
    req: ResolveEvaluationRequest,
  ): Promise<ResolveEvaluationResponse> {
    const res = await api<ApiResponse<ResolveEvaluationResponse>>(
      `/api/v1/attendance/requirements/evaluations/${evaluationId}/resolve`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  return { getStudentEvaluations, getAtRiskStudents, evaluateStudent, resolveViolation }
}
