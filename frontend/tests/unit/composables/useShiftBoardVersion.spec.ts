import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { ShiftSlotResponse } from '~/types/shift'

const { api } = vi.hoisted(() => ({
  api: { getShiftSlots: vi.fn(), patchSlotAssignments: vi.fn() },
}))
mockNuxtImport('useShiftApi', () => () => api)
const { useShiftBoard } = await import('~/composables/useShiftBoard')

function slot(id: number, version: number, users: number[] = []): ShiftSlotResponse {
  return {
    id, scheduleId: 1, version,
    time: { slotDate: '2026-10-08', startTime: '09:00', endTime: '10:00', endsNextDay: false },
    position: { positionId: 1, positionName: '所有', requiredCount: 2 },
    assignedUserIds: users, assignmentMasked: false, note: null,
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
beforeEach(() => vi.resetAllMocks())

describe('CMP-261008-1253 ボードの実版契約', () => {
  it.each([0, 1, 2_147_483_648])('取得した版 %s で追加し、応答版で解除する', async (version) => {
    const slots = ref<ShiftSlotResponse[]>([])
    api.getShiftSlots.mockResolvedValue({ data: [slot(1, version)] })
    const state = useShiftBoard(ref(1), slots)
    await state.loadSlots()
    const added = { ...slot(1, version + 1, [4]), assignmentMasked: true,
      warnings: [{ code: 'ASSIGNMENT_OVERLAP', conflictingSlotIds: [2] }] }
    api.patchSlotAssignments.mockResolvedValueOnce({ data: added })
      .mockResolvedValueOnce({ data: slot(1, version + 2) })
    await state.addUser(1, 4)
    expect(slots.value[0]).toEqual(added)
    expect(state.localAssignments.value[1]).toEqual([4])
    await state.removeUser(1, 4)
    expect(api.patchSlotAssignments.mock.calls).toEqual([
      [1, { addUserIds: [4], slotVersion: version }],
      [1, { removeUserIds: [4], slotVersion: version + 1 }],
    ])
    expect(slots.value[0].version).toBe(version + 2)
    expect(state.localAssignments.value[1]).toEqual([])
  })

  it('移動は両方の実版を送り、それぞれの確定応答を保存する', async () => {
    const slots = ref([slot(1, 3, [4]), slot(2, 8)])
    const state = useShiftBoard(ref(1), slots)
    api.patchSlotAssignments.mockResolvedValueOnce({ data: slot(1, 4) })
      .mockResolvedValueOnce({ data: slot(2, 9, [4]) })
    await state.moveUser(1, 2, 4)
    expect(api.patchSlotAssignments.mock.calls).toEqual([
      [1, { removeUserIds: [4], slotVersion: 3 }],
      [2, { addUserIds: [4], slotVersion: 8 }],
    ])
    expect(slots.value).toEqual([slot(1, 4), slot(2, 9, [4])])
  })

  it('第2 PATCH と再取得が失敗しても第1成功を保持し、元409を返す', async () => {
    const slots = ref([slot(1, 3, [4]), slot(2, 8)])
    const state = useShiftBoard(ref(1), slots)
    const conflict = { data: { error: { code: 'SHIFT_018' } } }
    api.patchSlotAssignments.mockResolvedValueOnce({ data: slot(1, 4) })
      .mockRejectedValueOnce(conflict)
    api.getShiftSlots.mockRejectedValue(new Error('再取得失敗'))
    await expect(state.moveUser(1, 2, 4)).rejects.toBe(conflict)
    expect(slots.value).toEqual([slot(1, 4), slot(2, 8)])
    expect(state.localAssignments.value[1]).toEqual([])
    expect(api.patchSlotAssignments).toHaveBeenCalledTimes(2)
  })

  it('途中失敗後の GET は実状態へ同期し、元エラーを維持する', async () => {
    const slots = ref([slot(1, 3, [4]), slot(2, 8)])
    const state = useShiftBoard(ref(1), slots)
    const conflict = { data: { error: { code: 'SHIFT_018' } } }
    api.patchSlotAssignments.mockResolvedValueOnce({ data: slot(1, 4) })
      .mockRejectedValueOnce(conflict)
    api.getShiftSlots.mockResolvedValue({ data: [slot(1, 4), slot(2, 9, [5])] })
    await expect(state.moveUser(1, 2, 4)).rejects.toBe(conflict)
    expect(slots.value[1]).toEqual(slot(2, 9, [5]))
    expect(state.localAssignments.value[2]).toEqual([5])
  })

  it.each([undefined, null, NaN, Infinity, -1, 0.5, Number.MAX_SAFE_INTEGER + 1])(
    '不正な版 %s は追加・解除・移動の HTTP を送らない', async (version) => {
      // 境界の不正応答だけを局所的に型外として表す。
      const invalid = { ...slot(2, 0), version } as unknown as ShiftSlotResponse
      const state = useShiftBoard(ref(1), ref([slot(1, 1, [4]), invalid]))
      await expect(state.addUser(2, 4)).rejects.toThrow()
      await expect(state.removeUser(2, 4)).rejects.toThrow()
      await expect(state.moveUser(1, 2, 4)).rejects.toThrow()
      expect(api.patchSlotAssignments).not.toHaveBeenCalled()
    },
  )

  it('存在しない枠は送信せず、同じ枠への移動も送信しない', async () => {
    const state = useShiftBoard(ref(1), ref([slot(1, 1, [4])]))
    await expect(state.addUser(2, 4)).rejects.toThrow()
    await state.moveUser(1, 1, 4)
    expect(api.patchSlotAssignments).not.toHaveBeenCalled()
  })

  it('追加の409は状態と元例外を保持し、自動再送しない', async () => {
    const before = slot(1, 1)
    const slots = ref([before])
    const state = useShiftBoard(ref(1), slots)
    const conflict = { data: { error: { code: 'SHIFT_018' } } }
    api.patchSlotAssignments.mockRejectedValue(conflict)
    await expect(state.addUser(1, 4)).rejects.toBe(conflict)
    expect(slots.value).toEqual([before])
    expect(api.patchSlotAssignments).toHaveBeenCalledTimes(1)
  })

  it('古い GET 応答で確定 PATCH 応答を上書きしない', async () => {
    const slots = ref([slot(1, 1)])
    const state = useShiftBoard(ref(1), slots)
    const get = deferred<{ data: ShiftSlotResponse[] }>()
    api.getShiftSlots.mockReturnValue(get.promise)
    const loading = state.loadSlots()
    api.patchSlotAssignments.mockResolvedValue({ data: slot(1, 2, [4]) })
    await state.addUser(1, 4)
    get.resolve({ data: [slot(1, 1)] })
    await loading
    expect(slots.value).toEqual([slot(1, 2, [4])])
    expect(state.localAssignments.value[1]).toEqual([4])
  })

  it('再取得は既存ローカル割当も同期し、別scheduleの旧応答を破棄する', async () => {
    const schedule = ref(1)
    const slots = ref([slot(1, 1)])
    const state = useShiftBoard(schedule, slots)
    api.getShiftSlots.mockResolvedValueOnce({ data: [slot(1, 2, [4])] })
    await state.loadSlots()
    expect(state.localAssignments.value[1]).toEqual([4])
    const get = deferred<{ data: ShiftSlotResponse[] }>()
    api.getShiftSlots.mockReturnValue(get.promise)
    const loading = state.loadSlots()
    schedule.value = 2
    await nextTick()
    get.resolve({ data: [slot(1, 2, [4])] })
    await loading
    expect(slots.value).toEqual([])
    expect(state.localAssignments.value).toEqual({})
  })
})