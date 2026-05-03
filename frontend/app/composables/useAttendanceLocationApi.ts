import type {
  LocationChangeRequest,
  LocationChangeResponse,
  LocationListResponse,
  LocationTimelineResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceLocationApi() {
  const api = useApi()

  // POST /api/v1/teams/{teamId}/attendance/locations/changes
  async function recordLocationChange(
    teamId: number,
    request: LocationChangeRequest,
  ): Promise<LocationChangeResponse> {
    const res = await api<ApiResponse<LocationChangeResponse>>(
      `/api/v1/teams/${teamId}/attendance/locations/changes`,
      { method: 'POST', body: request },
    )
    return res.data
  }

  // GET /api/v1/teams/{teamId}/attendance/locations?date=YYYY-MM-DD
  async function getTeamLocations(
    teamId: number,
    date: string,
  ): Promise<LocationListResponse> {
    const res = await api<ApiResponse<LocationListResponse>>(
      `/api/v1/teams/${teamId}/attendance/locations?date=${date}`,
    )
    return res.data
  }

  // GET /api/v1/students/{studentUserId}/attendance/locations/timeline?date=YYYY-MM-DD
  async function getLocationTimeline(
    studentUserId: number,
    date: string,
  ): Promise<LocationTimelineResponse> {
    const res = await api<ApiResponse<LocationTimelineResponse>>(
      `/api/v1/students/${studentUserId}/attendance/locations/timeline?date=${date}`,
    )
    return res.data
  }

  return {
    recordLocationChange,
    getTeamLocations,
    getLocationTimeline,
  }
}
