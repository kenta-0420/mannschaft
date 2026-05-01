import type {
  DailyAttendanceListResponse,
  DailyAttendanceResponse,
  DailyAttendanceUpdateRequest,
  DailyRollCallRequest,
  DailyRollCallSummary,
  AttendanceHistoryItem,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useDailyRollCallApi() {
  const api = useApi()

  async function getDailyAttendance(
    teamId: number,
    date: string,
  ): Promise<DailyAttendanceListResponse> {
    const res = await api<ApiResponse<DailyAttendanceListResponse>>(
      `/api/v1/teams/${teamId}/attendance/daily?date=${date}`,
    )
    return res.data
  }

  async function submitRollCall(
    teamId: number,
    body: DailyRollCallRequest,
  ): Promise<DailyRollCallSummary> {
    const res = await api<ApiResponse<DailyRollCallSummary>>(
      `/api/v1/teams/${teamId}/attendance/daily/roll-call`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function updateDailyRecord(
    teamId: number,
    recordId: number,
    body: DailyAttendanceUpdateRequest,
  ): Promise<DailyAttendanceResponse> {
    const res = await api<ApiResponse<DailyAttendanceResponse>>(
      `/api/v1/teams/${teamId}/attendance/daily/${recordId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function getMyAttendanceHistory(
    from: string,
    to: string,
  ): Promise<AttendanceHistoryItem[]> {
    const res = await api<ApiResponse<AttendanceHistoryItem[]>>(
      `/api/v1/me/attendance/daily?from=${from}&to=${to}`,
    )
    return res.data
  }

  return {
    getDailyAttendance,
    submitRollCall,
    updateDailyRecord,
    getMyAttendanceHistory,
  }
}
