import type {
  FamilyAttendanceNoticeRequest,
  FamilyAttendanceNoticeResponse,
  FamilyNoticeListResponse,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useFamilyAttendanceNoticeApi() {
  const api = useApi()

  /** 保護者: 連絡を送信する */
  async function submitNotice(
    body: FamilyAttendanceNoticeRequest,
  ): Promise<FamilyAttendanceNoticeResponse> {
    const res = await api<ApiResponse<FamilyAttendanceNoticeResponse>>(
      '/api/v1/me/attendance/notices',
      { method: 'POST', body },
    )
    return res.data
  }

  /** 保護者: 送信履歴一覧を取得する */
  async function getMyNotices(
    from: string,
    to: string,
  ): Promise<FamilyAttendanceNoticeResponse[]> {
    const res = await api<ApiResponse<FamilyAttendanceNoticeResponse[]>>(
      `/api/v1/me/attendance/notices?from=${from}&to=${to}`,
    )
    return res.data
  }

  /** 担任: 当日の保護者連絡一覧を取得する */
  async function getTeamNotices(teamId: number, date: string): Promise<FamilyNoticeListResponse> {
    const res = await api<ApiResponse<FamilyNoticeListResponse>>(
      `/api/v1/teams/${teamId}/attendance/notices?date=${date}`,
    )
    return res.data
  }

  /** 担任: 連絡を確認済みにする */
  async function acknowledgeNotice(
    teamId: number,
    noticeId: number,
  ): Promise<FamilyAttendanceNoticeResponse> {
    const res = await api<ApiResponse<FamilyAttendanceNoticeResponse>>(
      `/api/v1/teams/${teamId}/attendance/notices/${noticeId}/acknowledge`,
      { method: 'POST' },
    )
    return res.data
  }

  /** 担任: 連絡を出欠レコードへ反映する */
  async function applyNotice(
    teamId: number,
    noticeId: number,
  ): Promise<FamilyAttendanceNoticeResponse> {
    const res = await api<ApiResponse<FamilyAttendanceNoticeResponse>>(
      `/api/v1/teams/${teamId}/attendance/notices/${noticeId}/apply`,
      { method: 'POST' },
    )
    return res.data
  }

  return {
    submitNotice,
    getMyNotices,
    getTeamNotices,
    acknowledgeNotice,
    applyNotice,
  }
}
