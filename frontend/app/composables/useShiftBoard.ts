import type { ShiftSlotResponse } from '~/types/shift'

export function useShiftBoard(scheduleId: Ref<number>, slots: Ref<ShiftSlotResponse[]>) {
  const shiftApi = useShiftApi()
  const localAssignments = ref<Record<number, number[]>>({})

  function syncAssignments(): void {
    localAssignments.value = Object.fromEntries(
      slots.value.map((slot) => [slot.id, [...slot.assignedUserIds]]),
    )
  }

  async function loadSlots(): Promise<void> {
    const requestedSchedule = scheduleId.value
    const before = slots.value
    const res = await shiftApi.getShiftSlots(requestedSchedule)
    // PATCH の確定応答や別スケジュールを、先に開始した GET で上書きしない。
    if (requestedSchedule !== scheduleId.value || slots.value !== before) return
    slots.value = res.data
    syncAssignments()
  }

  function versionOf(slotId: number): number {
    const version = slots.value.find((slot) => slot.id === slotId)?.version
    if (typeof version !== 'number' || !Number.isSafeInteger(version) || version < 0) {
      throw new Error('シフト枠の版を取得できません。再読み込みしてください。')
    }
    return version
  }

  function applySlot(slot: ShiftSlotResponse): void {
    // 警告・マスクを含む応答全体と、次の操作で送る実版を保持する。
    slots.value = slots.value.map((current) => current.id === slot.id ? slot : current)
    localAssignments.value[slot.id] = [...slot.assignedUserIds]
  }

  async function patch(
    slotId: number,
    slotVersion: number,
    change: { addUserIds?: number[]; removeUserIds?: number[] },
  ): Promise<void> {
    const res = await shiftApi.patchSlotAssignments(slotId, { ...change, slotVersion })
    applySlot(res.data)
  }

  async function moveUser(fromSlotId: number, toSlotId: number, userId: number): Promise<void> {
    if (fromSlotId === toSlotId) return
    // 両方の版を最初に確認し、片側の版不足で削除だけを実行しない。
    const fromVersion = versionOf(fromSlotId)
    const toVersion = versionOf(toSlotId)
    try {
      await patch(fromSlotId, fromVersion, { removeUserIds: [userId] })
      await patch(toSlotId, toVersion, { addUserIds: [userId] })
    } catch (error) {
      try {
        await loadSlots()
      } catch {
        // 再取得失敗でも、確定した第1 PATCH の状態と元の操作エラーを保持する。
      }
      throw error
    }
  }

  async function addUser(slotId: number, userId: number): Promise<void> {
    await patch(slotId, versionOf(slotId), { addUserIds: [userId] })
  }

  async function removeUser(slotId: number, userId: number): Promise<void> {
    await patch(slotId, versionOf(slotId), { removeUserIds: [userId] })
  }

  watch(scheduleId, () => {
    slots.value = []
    localAssignments.value = {}
  })

  return { localAssignments, loadSlots, moveUser, addUser, removeUser }
}