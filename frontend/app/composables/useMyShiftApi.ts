import type { MyConfirmedSlotResponse, ShiftRequestResponse } from '~/types/shift'

/**
 * F03.5 マイシフト API クライアント。
 *
 * エンドポイント:
 * - `GET /api/v1/shifts/my/requests`         自分のシフト希望一覧
 * - `GET /api/v1/shifts/my/confirmed-slots`  自分の確定シフト枠一覧（Phase 3 新規）
 */
export function useMyShiftApi() {
  const api = useApi()

  /**
   * 自分のシフト希望一覧を取得する（確定分 + 希望分）。
   *
   * TODO(F03.5 Phase2): useShiftRequestApi.listMyRequests と統合予定。
   * 現在は本 composable と {@link useShiftRequestApi} の両方に同等実装が存在する。
   */
  async function listMyRequests(): Promise<ShiftRequestResponse[]> {
    const res = await api<{ data: ShiftRequestResponse[] }>('/api/v1/shifts/my/requests')
    return res.data
  }

  /**
   * 自分の確定シフト枠一覧を取得する（F03.5 Phase 3 新規エンドポイント）。
   *
   * 部隊A が実装した `GET /api/v1/shifts/my/confirmed-slots` に対応。
   * スケジュールが PUBLISHED 状態のシフト枠のうち、ログインユーザーがアサインされているものを返す。
   *
   * @returns 確定シフト枠の配列（slotDate 昇順）
   */
  async function listMyConfirmedSlots(): Promise<MyConfirmedSlotResponse[]> {
    const res = await api<{ data: MyConfirmedSlotResponse[] }>(
      '/api/v1/shifts/my/confirmed-slots',
    )
    return res.data
  }

  return {
    listMyRequests,
    listMyConfirmedSlots,
  }
}
