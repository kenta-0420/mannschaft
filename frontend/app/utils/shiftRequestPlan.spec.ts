import { describe, expect, it } from 'vitest'
import type { ShiftPreference, ShiftRequestResponse, ShiftSlotResponse } from '~/types/shift'
import { planShiftRequestWrites } from './shiftRequestPlan'

function slot(id: number, slotDate: string): ShiftSlotResponse {
  return { id, time: { slotDate } } as unknown as ShiftSlotResponse
}

function existing(
  id: number,
  slotId: number,
  preference: ShiftPreference,
  note: string | null = null,
): ShiftRequestResponse {
  return { id, slotId, preference, note } as unknown as ShiftRequestResponse
}

describe('planShiftRequestWrites（CMP-261120-1051）', () => {
  const slots = [slot(1, '2026-12-01'), slot(2, '2026-12-02'), slot(3, '2026-12-03')]

  it('未変更の既存希望は更新送信しない（既存値を保つ）', () => {
    const plan = planShiftRequestWrites({
      scheduleId: 10,
      slots,
      existingRequests: [existing(100, 1, 'PREFERRED', '夜勤可'), existing(101, 2, 'AVAILABLE')],
      preferences: new Map<number, ShiftPreference>([
        [1, 'PREFERRED'],
        [2, 'AVAILABLE'],
        [3, 'STRONG_REST'],
      ]),
      notes: new Map([[1, '夜勤可']]),
      untouchedPreference: 'STRONG_REST',
    })
    expect(plan.updates).toEqual([])
    expect(plan.creates.map((c) => c.slotId)).toEqual([3])
  })

  it('利用者が変更した既存希望だけ更新する', () => {
    const plan = planShiftRequestWrites({
      scheduleId: 10,
      slots,
      existingRequests: [existing(100, 1, 'PREFERRED'), existing(101, 2, 'AVAILABLE')],
      preferences: new Map<number, ShiftPreference>([
        [1, 'ABSOLUTE_REST'],
        [2, 'AVAILABLE'],
        [3, 'AVAILABLE'],
      ]),
      notes: new Map(),
      untouchedPreference: 'STRONG_REST',
    })
    expect(plan.updates).toEqual([
      { requestId: 100, payload: { preference: 'ABSOLUTE_REST', note: undefined } },
    ])
    expect(plan.creates).toHaveLength(1)
    expect(plan.creates[0]).toMatchObject({ scheduleId: 10, slotId: 3, preference: 'AVAILABLE' })
  })

  it('メモだけ変えた既存希望も更新する', () => {
    const plan = planShiftRequestWrites({
      scheduleId: 10,
      slots: [slot(1, '2026-12-01')],
      existingRequests: [existing(100, 1, 'WEAK_REST', '旧')],
      preferences: new Map<number, ShiftPreference>([[1, 'WEAK_REST']]),
      notes: new Map([[1, '新']]),
      untouchedPreference: 'STRONG_REST',
    })
    expect(plan.updates).toEqual([
      { requestId: 100, payload: { preference: 'WEAK_REST', note: '新' } },
    ])
  })
})
