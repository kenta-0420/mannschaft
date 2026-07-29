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
    } catch (e) {
      // エラー時はロールバック
      localAssignments.value[fromSlotId] = prevFrom
      localAssignments.value[toSlotId] = prevTo
      // 元のエラーをそのまま再スローする。素の Error に差し替えると
      // data.error.code / message（例: SHIFT_017 シフト枠の必要人数を超過しています）が
      // 失われ、呼び出し元が利用者に理由を伝えられなくなる。
      throw e
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
    } catch (e) {
      localAssignments.value[slotId] = prev
      // 元のエラーをそのまま再スロー（理由を握りつぶさない）
      throw e
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
    } catch (e) {
      localAssignments.value[slotId] = prev
      // 元のエラーをそのまま再スロー（理由を握りつぶさない）
      throw e
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
