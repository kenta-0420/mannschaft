import type {
  PeriodAttendanceListResponse,
  PeriodAttendanceResponse,
  PeriodAttendanceRequest,
  PeriodAttendanceSummary,
  PeriodAttendanceUpdateRequest,
  PeriodCandidatesResponse,
  StudentTimelineResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function usePeriodAttendanceApi() {
  const api = useApi()

  async function getPeriodAttendance(
    teamId: number,
    date: string,
    periodNumber: number,
  ): Promise<PeriodAttendanceListResponse> {
    const res = await api<ApiResponse<PeriodAttendanceListResponse>>(
      `/api/v1/teams/${teamId}/attendance/periods?date=${date}&periodNumber=${periodNumber}`,
    )
    return res.data
  }

  async function getPeriodCandidates(
    teamId: number,
    periodNumber: number,
    date: string,
  ): Promise<PeriodCandidatesResponse> {
    const res = await api<ApiResponse<PeriodCandidatesResponse>>(
      `/api/v1/teams/${teamId}/attendance/periods/${periodNumber}/candidates?date=${date}`,
    )
    return res.data
  }

  async function submitPeriodAttendance(
    teamId: number,
    periodNumber: number,
    body: PeriodAttendanceRequest,
  ): Promise<PeriodAttendanceSummary> {
    const res = await api<ApiResponse<PeriodAttendanceSummary>>(
      `/api/v1/teams/${teamId}/attendance/periods/${periodNumber}`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function updatePeriodRecord(
    teamId: number,
    recordId: number,
    body: PeriodAttendanceUpdateRequest,
  ): Promise<PeriodAttendanceResponse> {
    const res = await api<ApiResponse<PeriodAttendanceResponse>>(
      `/api/v1/teams/${teamId}/attendance/periods/${recordId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function getMyTimeline(date: string): Promise<StudentTimelineResponse> {
    const res = await api<ApiResponse<StudentTimelineResponse>>(
      `/api/v1/me/attendance/timeline?date=${date}`,
    )
    return res.data
  }

  return {
    getPeriodAttendance,
    getPeriodCandidates,
    submitPeriodAttendance,
    updatePeriodRecord,
    getMyTimeline,
  }
}
