export function useShiftBoard(scheduleId: Ref<number>) {
  const shiftApi = useShiftApi()
  // slotId -> userIds のローカル状態（楽観的更新用）
  const localAssignments = ref<Record<number, number[]>>({})
  const pendingOperations = ref<Map<string, AbortController>>(new Map())

  function initSlot(slotId: number, userIds: number[]): void {
    if (!localAssignments.value[slotId]) {
      localAssignments.value[slotId] = [...userIds]
    }
  }

  async function moveUser(
    fromSlotId: number,
    toSlotId: number,
    userId: number,
    toSlotVersion: number,
  ): Promise<void> {
    // 楽観的更新
    const prevFrom = [...(localAssignments.value[fromSlotId] ?? [])]
    const prevTo = [...(localAssignments.value[toSlotId] ?? [])]
    localAssignments.value[fromSlotId] = prevFrom.filter((id) => id !== userId)
    if (!localAssignments.value[toSlotId]) {
      localAssignments.value[toSlotId] = []
    }
    if (!localAssignments.value[toSlotId].includes(userId)) {
      localAssignments.value[toSlotId] = [...localAssignments.value[toSlotId], userId]
    }

    try {
      // 移動元から削除
      await shiftApi.patchSlotAssignments(fromSlotId, {
        removeUserIds: [userId],
        slotVersion: 0, // バックエンドはslotVersionをfromSlotIdのものとして処理
      })
      // 移動先に追加
      await shiftApi.patchSlotAssignments(toSlotId, {
        addUserIds: [userId],
        slotVersion: toSlotVersion,
      })
    } catch {
      // エラー時はロールバック
      localAssignments.value[fromSlotId] = prevFrom
      localAssignments.value[toSlotId] = prevTo
      throw new Error('シフトの移動に失敗しました')
    }
  }

  async function addUser(slotId: number, userId: number, slotVersion: number): Promise<void> {
    const prev = [...(localAssignments.value[slotId] ?? [])]
    // 楽観的更新
    if (!localAssignments.value[slotId]) {
      localAssignments.value[slotId] = []
    }
    if (!localAssignments.value[slotId].includes(userId)) {
      localAssignments.value[slotId] = [...localAssignments.value[slotId], userId]
    }

    try {
      await shiftApi.patchSlotAssignments(slotId, { addUserIds: [userId], slotVersion })
    } catch {
      localAssignments.value[slotId] = prev
      throw new Error('メンバーの追加に失敗しました')
    }
  }

  async function removeUser(slotId: number, userId: number, slotVersion: number): Promise<void> {
    const prev = [...(localAssignments.value[slotId] ?? [])]
    // 楽観的更新
    localAssignments.value[slotId] = (localAssignments.value[slotId] ?? []).filter(
      (id) => id !== userId,
    )

    try {
      await shiftApi.patchSlotAssignments(slotId, { removeUserIds: [userId], slotVersion })
    } catch {
      localAssignments.value[slotId] = prev
      throw new Error('メンバーの削除に失敗しました')
    }
  }

  // コンポーネントのアンマウント時にペンディング操作をキャンセル
  onUnmounted(() => {
    pendingOperations.value.forEach((controller) => controller.abort())
    pendingOperations.value.clear()
  })

  // scheduleId が変わったらローカル状態をリセット
  watch(scheduleId, () => {
    localAssignments.value = {}
  })

  return { localAssignments, initSlot, moveUser, addUser, removeUser }
}
