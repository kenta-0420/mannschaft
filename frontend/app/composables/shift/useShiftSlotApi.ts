import type {
  BulkCreateShiftSlotRequest,
  CreateShiftSlotRequest,
  ShiftSlotResponse,
  UpdateShiftSlotRequest,
} from '~/types/shift'

export function useShiftSlotApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  /**
   * スケジュールのシフト枠一覧を取得する（board.vue 互換エイリアス）。
   * @param scheduleId スケジュール ID
   */
  async function getShiftSlots(scheduleId: number): Promise<{ data: ShiftSlotResponse[] }> {
    return api<{ data: ShiftSlotResponse[] }>(`${BASE}/${scheduleId}/slots`)
  }

  async function createShiftSlot(
    scheduleId: number,
    payload: CreateShiftSlotRequest,
  ): Promise<ShiftSlotResponse> {
    const res = await api<{ data: ShiftSlotResponse }>(`${BASE}/${scheduleId}/slots`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function bulkCreateSlots(
    scheduleId: number,
    payload: BulkCreateShiftSlotRequest,
  ): Promise<ShiftSlotResponse[]> {
    const res = await api<{ data: ShiftSlotResponse[] }>(`${BASE}/${scheduleId}/slots/bulk`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

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

  async function deleteSlot(slotId: number): Promise<void> {
    await api(`/api/v1/shifts/slots/${slotId}`, { method: 'DELETE' })
  }

  return {
    getShiftSlots,
    createShiftSlot,
    bulkCreateSlots,
    updateSlot,
    deleteSlot,
  }
}
