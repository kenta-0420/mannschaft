import type {
  CreateShiftRequestRequest,
  ShiftPreference,
  ShiftRequestResponse,
  ShiftSlotResponse,
} from '~/types/shift'

/** 既存希望の更新（PATCH）1 件分 */
export interface ShiftRequestUpdate {
  requestId: number
  payload: { preference: ShiftPreference; note: string | undefined }
}

export interface ShiftRequestWritePlan {
  /** 未提出スロットの新規作成 */
  creates: CreateShiftRequestRequest[]
  /** 利用者が実際に変更した既存希望の更新 */
  updates: ShiftRequestUpdate[]
}

interface PlanInput {
  scheduleId: number
  slots: ShiftSlotResponse[]
  existingRequests: ShiftRequestResponse[]
  preferences: Map<number, ShiftPreference>
  notes: Map<number, string>
  /** 未提出かつ未入力のスロットへ送る既定値 */
  untouchedPreference: ShiftPreference
}

/**
 * 送信内容を組み立てる（CMP-261120-1051）。
 *
 * 既存の提出済み希望は、利用者が希望またはメモを変更した場合のみ更新する。
 * 変更のない既存希望を再送すると、他経路での更新や既定値で意図せず上書きする
 * 恐れがあるため、既存値を保つ（送信しない）。
 */
export function planShiftRequestWrites(input: PlanInput): ShiftRequestWritePlan {
  const existingBySlot = new Map<number, ShiftRequestResponse>()
  for (const r of input.existingRequests) {
    if (r.slotId != null) existingBySlot.set(r.slotId, r)
  }
  const creates: CreateShiftRequestRequest[] = []
  const updates: ShiftRequestUpdate[] = []
  for (const slot of input.slots) {
    const pref = input.preferences.get(slot.id) ?? input.untouchedPreference
    const note = input.notes.get(slot.id)
    const existing = existingBySlot.get(slot.id)
    if (existing) {
      const unchanged = existing.preference === pref && (existing.note ?? '') === (note ?? '')
      if (!unchanged) updates.push({ requestId: existing.id, payload: { preference: pref, note } })
      continue
    }
    creates.push({
      scheduleId: input.scheduleId,
      slotId: slot.id,
      slotDate: slot.time.slotDate,
      preference: pref,
      note,
    })
  }
  return { creates, updates }
}
