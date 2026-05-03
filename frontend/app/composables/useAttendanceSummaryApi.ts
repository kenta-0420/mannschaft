import type {
  StudentSummaryResponse,
  ClassSummaryListResponse,
  RecalculateSummaryRequest,
  RecalculateSummaryResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceSummaryApi() {
  const api = useApi()

  async function getStudentSummary(
    studentId: number,
    teamId: number,
    academicYear: number,
    termId?: number,
  ): Promise<StudentSummaryResponse> {
    const params = new URLSearchParams({ teamId: String(teamId), academicYear: String(academicYear) })
    if (termId !== undefined) params.append('termId', String(termId))
    const res = await api<ApiResponse<StudentSummaryResponse>>(
      `/api/v1/students/${studentId}/attendance/summary?${params}`,
    )
    return res.data
  }

  async function getClassSummaries(
    teamId: number,
    academicYear: number,
    termId?: number,
  ): Promise<ClassSummaryListResponse> {
    const params = new URLSearchParams({ academicYear: String(academicYear) })
    if (termId !== undefined) params.append('termId', String(termId))
    const res = await api<ApiResponse<ClassSummaryListResponse>>(
      `/api/v1/teams/${teamId}/attendance/summaries?${params}`,
    )
    return res.data
  }

  async function recalculate(
    studentId: number,
    req: RecalculateSummaryRequest,
  ): Promise<RecalculateSummaryResponse> {
    const res = await api<ApiResponse<RecalculateSummaryResponse>>(
      `/api/v1/students/${studentId}/attendance/summary/recalculate`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  return { getStudentSummary, getClassSummaries, recalculate }
}
