export function useShiftAssignmentApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  // === Slot Assignments (D&D) ===
  async function patchSlotAssignments(
    slotId: number,
    req: { addUserIds?: number[]; removeUserIds?: number[]; slotVersion: number },
  ) {
    return api<{ data: unknown }>(`${BASE}/slots/${slotId}/assignments`, {
      method: 'PATCH',
      body: req,
    })
  }

  return {
    patchSlotAssignments,
  }
}
