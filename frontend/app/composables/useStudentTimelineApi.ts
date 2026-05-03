import type { StudentTimelineResponse } from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useStudentTimelineApi() {
  const api = useApi()

  async function getMyTimeline(date: string): Promise<StudentTimelineResponse> {
    const res = await api<ApiResponse<StudentTimelineResponse>>(
      `/api/v1/me/attendance/timeline?date=${date}`,
    )
    return res.data
  }

  return { getMyTimeline }
}
