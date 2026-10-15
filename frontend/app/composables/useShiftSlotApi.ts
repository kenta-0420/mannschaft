import type {
  BulkCreateShiftSlotRequest,
  CreateShiftSlotRequest,
  ShiftSlotResponse,
  UpdateShiftSlotRequest,
} from '~/types/shift'

/**
 * F03.5 シフト枠 API クライアント。
 *
 * エンドポイント:
 * - `GET/POST /api/v1/shifts/schedules/{scheduleId}/slots`
 * - `POST     /api/v1/shifts/schedules/{scheduleId}/slots/bulk`
 * - `PATCH    /api/v1/shifts/slots/{slotId}`
 * - `DELETE   /api/v1/shifts/slots/{slotId}`
 */
export function useShiftSlotApi() {
  const api = useApi()

  /**
   * スケジュールのシフト枠一覧を取得する。
   * @param scheduleId スケジュール ID
   */
  async function listSlots(scheduleId: number): Promise<ShiftSlotResponse[]> {
    const res = await api<{ data: ShiftSlotResponse[] }>(
      `/api/v1/shifts/schedules/${scheduleId}/slots`,
    )
    return res.data
  }

  /**
   * シフト枠を作成する。
   * @param scheduleId スケジュール ID
   * @param payload    作成リクエスト
   */
  async function createSlot(
    scheduleId: number,
    payload: CreateShiftSlotRequest,
  ): Promise<ShiftSlotResponse> {
    const res = await api<{ data: ShiftSlotResponse }>(
      `/api/v1/shifts/schedules/${scheduleId}/slots`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  /**
   * シフト枠を一括作成する。
   * @param scheduleId スケジュール ID
   * @param payload    一括作成リクエスト
   */
  async function bulkCreateSlots(
    scheduleId: number,
    payload: BulkCreateShiftSlotRequest,
  ): Promise<ShiftSlotResponse[]> {
    const res = await api<{ data: ShiftSlotResponse[] }>(
      `/api/v1/shifts/schedules/${scheduleId}/slots/bulk`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  /**
   * シフト枠を更新する。
   * @param slotId  枠 ID
   * @param payload 更新リクエスト
   */
  async function updateSlot(
    slotId: number,
    payload: UpdateShiftSlotRequest,
  ): Promise<ShiftSlotResponse> {
    const res = await api<{ data: ShiftSlotResponse }>(`/api/v1/shifts/slots/${slotId}`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  /**
   * シフト枠を削除する（物理削除）。
   * @param slotId 枠 ID
   */
  async function deleteSlot(slotId: number): Promise<void> {
    await api(`/api/v1/shifts/slots/${slotId}`, { method: 'DELETE' })
  }

  return {
    listSlots,
    createSlot,
    bulkCreateSlots,
    updateSlot,
    deleteSlot,
  }
}
