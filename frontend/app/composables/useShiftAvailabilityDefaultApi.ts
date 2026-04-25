import type {
  AvailabilityDefaultResponse,
  BulkAvailabilityDefaultRequest,
} from '~/types/shift'

/**
 * F03.5 デフォルト勤務可能時間 API クライアント。
 *
 * エンドポイント:
 * - `GET    /api/v1/shifts/availability` デフォルト勤務可能時間取得
 * - `PUT    /api/v1/shifts/availability` デフォルト勤務可能時間一括設定
 * - `DELETE /api/v1/shifts/availability` デフォルト勤務可能時間削除
 */
export function useShiftAvailabilityDefaultApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/availability'

  /**
   * デフォルト勤務可能時間を取得する。
   * @param teamId チーム ID
   */
  async function getAvailabilityDefaults(teamId: number): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(`${BASE}?${query.toString()}`)
    return res.data
  }

  /**
   * デフォルト勤務可能時間を一括設定する（全件置き換え）。
   * @param teamId  チーム ID
   * @param payload 一括設定リクエスト
   */
  async function setAvailabilityDefaults(
    teamId: number,
    payload: BulkAvailabilityDefaultRequest,
  ): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(`${BASE}?${query.toString()}`, {
      method: 'PUT',
      body: payload,
    })
    return res.data
  }

  /**
   * デフォルト勤務可能時間を全件削除する。
   * @param teamId チーム ID
   */
  async function deleteAvailabilityDefaults(teamId: number): Promise<void> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    await api(`${BASE}?${query.toString()}`, { method: 'DELETE' })
  }

  return {
    getAvailabilityDefaults,
    setAvailabilityDefaults,
    deleteAvailabilityDefaults,
  }
}
