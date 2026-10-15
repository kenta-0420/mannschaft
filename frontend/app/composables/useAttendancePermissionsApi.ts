import type { components } from '~/types/generated'

export type AttendancePermissions = components['schemas']['AttendancePermissionsResponse']

interface ApiResponse<T> {
  data: T
}

export function useAttendancePermissionsApi() {
  const api = useApi()

  /** 学校出欠の権限判定結果（閲覧・日次登録・時限登録）を取得する。権限なしでも 200 で全項目 false。 */
  async function getPermissions(teamId: string): Promise<AttendancePermissions> {
    const res = await api<ApiResponse<AttendancePermissions>>(
      `/api/v1/teams/${teamId}/attendance/permissions`,
    )
    return res.data
  }

  return { getPermissions }
}
