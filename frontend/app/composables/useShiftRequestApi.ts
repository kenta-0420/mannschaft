import type {
  CreateShiftRequestRequest,
  ShiftRequestResponse,
  ShiftRequestSummaryResponse,
  UpdateShiftRequestRequest,
} from '~/types/shift'

/**
 * F03.5 シフト希望 API クライアント。
 *
 * エンドポイント:
 * - `GET    /api/v1/shifts/requests`           希望一覧（管理者用、scheduleId クエリ必須）
 * - `GET    /api/v1/shifts/my/requests`        マイ希望一覧
 * - `POST   /api/v1/shifts/requests`           希望提出
 * - `PATCH  /api/v1/shifts/requests/{id}`      希望更新
 * - `DELETE /api/v1/shifts/requests/{id}`      希望削除
 * - `GET    /api/v1/shifts/requests/summary`   提出サマリー
 */
export function useShiftRequestApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts'

  /**
   * スケジュールのシフト希望一覧を取得する（管理者用）。
   * @param scheduleId スケジュール ID
   */
  async function listRequests(scheduleId: number): Promise<ShiftRequestResponse[]> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestResponse[] }>(
      `${BASE}/requests?${query.toString()}`,
    )
    return res.data
  }

  /**
   * 自分のシフト希望一覧を取得する。
   *
   * TODO(F03.5 Phase2): useMyShiftApi.listMyRequests と統合予定。
   * 現在は本 composable と {@link useMyShiftApi} の両方に同等実装が存在する。
   */
  async function listMyRequests(): Promise<ShiftRequestResponse[]> {
    const res = await api<{ data: ShiftRequestResponse[] }>(`${BASE}/my/requests`)
    return res.data
  }

  /**
   * シフト希望を提出する。
   * @param payload 提出リクエスト
   */
  async function submitRequest(payload: CreateShiftRequestRequest): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>(`${BASE}/requests`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * シフト希望を更新する。
   * @param requestId 希望 ID
   * @param payload   更新リクエスト
   */
  async function updateRequest(
    requestId: number,
    payload: UpdateShiftRequestRequest,
  ): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>(`${BASE}/requests/${requestId}`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  /**
   * シフト希望を削除する。
   * @param requestId 希望 ID
   */
  async function deleteRequest(requestId: number): Promise<void> {
    await api(`${BASE}/requests/${requestId}`, { method: 'DELETE' })
  }

  /**
   * シフト希望提出サマリーを取得する（v2: 5段階カウンタ付き）。
   * @param scheduleId スケジュール ID
   */
  async function getRequestSummary(scheduleId: number): Promise<ShiftRequestSummaryResponse> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestSummaryResponse }>(
      `${BASE}/requests/summary?${query.toString()}`,
    )
    return res.data
  }

  return {
    listRequests,
    listMyRequests,
    submitRequest,
    updateRequest,
    deleteRequest,
    getRequestSummary,
  }
}
