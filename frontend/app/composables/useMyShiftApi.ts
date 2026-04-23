import type { ShiftRequestResponse } from '~/types/shift'

/**
 * F03.5 マイシフト API クライアント。
 *
 * エンドポイント:
 * - `GET /api/v1/shifts/my/requests`  自分のシフト希望一覧
 */
export function useMyShiftApi() {
  const api = useApi()

  /**
   * 自分のシフト希望一覧を取得する（確定分 + 希望分）。
   */
  async function listMyRequests(): Promise<ShiftRequestResponse[]> {
    const res = await api<{ data: ShiftRequestResponse[] }>('/api/v1/shifts/my/requests')
    return res.data
  }

  return {
    listMyRequests,
  }
}
