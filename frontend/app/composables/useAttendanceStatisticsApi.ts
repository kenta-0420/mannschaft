import type { MonthlyStatisticsResponse, StudentTermStatisticsResponse } from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceStatisticsApi() {
  const api = useApi()

  async function getMonthlyStatistics(
    teamId: number,
    year: number,
    month: number,
  ): Promise<MonthlyStatisticsResponse> {
    const res = await api<ApiResponse<MonthlyStatisticsResponse>>(
      `/api/v1/teams/${teamId}/attendance/statistics/monthly?year=${year}&month=${month}`,
    )
    return res.data
  }

  async function getTermStatistics(
    teamId: number,
    from: string,
    to: string,
  ): Promise<StudentTermStatisticsResponse> {
    const res = await api<ApiResponse<StudentTermStatisticsResponse>>(
      `/api/v1/me/attendance/statistics/term?teamId=${teamId}&from=${from}&to=${to}`,
    )
    return res.data
  }

  function exportCsv(teamId: number, from: string, to: string): void {
    const link = document.createElement('a')
    link.href = `/api/v1/teams/${teamId}/attendance/export?from=${from}&to=${to}`
    link.download = `attendance_${from}_${to}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  }

  return { getMonthlyStatistics, getTermStatistics, exportCsv }
}
