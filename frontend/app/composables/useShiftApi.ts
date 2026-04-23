import type {
  CreateShiftScheduleRequest,
  ShiftScheduleResponse,
  UpdateShiftScheduleRequest,
} from '~/types/shift'

/**
 * F03.5 シフトスケジュール API クライアント。
 *
 * エンドポイントベース: `GET/POST /api/v1/shifts/schedules`
 */
export function useShiftApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  /**
   * チームのシフトスケジュール一覧を取得する。
   * @param teamId チーム ID
   * @param from  期間開始日（YYYY-MM-DD）省略可
   * @param to    期間終了日（YYYY-MM-DD）省略可
   */
  async function listSchedules(
    teamId: number,
    from?: string,
    to?: string,
  ): Promise<ShiftScheduleResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    const res = await api<{ data: ShiftScheduleResponse[] }>(`${BASE}?${query.toString()}`)
    return res.data
  }

  /**
   * シフトスケジュール詳細を取得する。
   * @param scheduleId スケジュール ID
   */
  async function getSchedule(scheduleId: number): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}`)
    return res.data
  }

  /**
   * シフトスケジュールを作成する。
   * @param teamId  チーム ID
   * @param payload 作成リクエスト
   */
  async function createSchedule(
    teamId: number,
    payload: CreateShiftScheduleRequest,
  ): Promise<ShiftScheduleResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}?${query.toString()}`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * シフトスケジュールを更新する。
   * @param scheduleId スケジュール ID
   * @param payload    更新リクエスト
   */
  async function updateSchedule(
    scheduleId: number,
    payload: UpdateShiftScheduleRequest,
  ): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  /**
   * シフトスケジュールを削除する（論理削除）。
   * @param scheduleId スケジュール ID
   */
  async function deleteSchedule(scheduleId: number): Promise<void> {
    await api(`${BASE}/${scheduleId}`, { method: 'DELETE' })
  }

  /**
   * シフトスケジュールのステータスを遷移する。
   * @param scheduleId スケジュール ID
   * @param status     遷移先ステータス
   */
  async function transitionStatus(
    scheduleId: number,
    status: string,
  ): Promise<ShiftScheduleResponse> {
    const query = new URLSearchParams()
    query.set('status', status)
    const res = await api<{ data: ShiftScheduleResponse }>(
      `${BASE}/${scheduleId}/transition?${query.toString()}`,
      { method: 'POST' },
    )
    return res.data
  }

  /**
   * シフトスケジュールを複製する。
   * @param scheduleId 複製元スケジュール ID
   */
  async function duplicateSchedule(scheduleId: number): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}/duplicate`, {
      method: 'POST',
    })
    return res.data
  }

  return {
    listSchedules,
    getSchedule,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    transitionStatus,
    duplicateSchedule,
  }
}
