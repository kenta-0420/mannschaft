import type {
  ChangeRequest,
  CreateChangeRequestPayload,
  ReviewChangeRequestPayload,
  CreateShiftScheduleRequest,
  ShiftScheduleResponse,
  UpdateShiftScheduleRequest,
} from '~/types/shift'

export function useShiftApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  /**
   * チームのシフトスケジュール一覧を取得する。
   * @param teamId チーム ID
   * @param from  期間開始日（YYYY-MM-DD）省略可
   * @param to    期間終了日（YYYY-MM-DD）省略可
   */
  async function listSchedules(
    teamId: number,
    from?: string,
    to?: string,
  ): Promise<ShiftScheduleResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    const res = await api<{ data: ShiftScheduleResponse[] }>(`${BASE}?${query.toString()}`)
    return res.data
  }

  /**
   * シフトスケジュール詳細を取得する。
   * @param scheduleId スケジュール ID
   */
  async function getSchedule(scheduleId: number): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}`)
    return res.data
  }

  /**
   * シフトスケジュールを作成する。
   * @param teamId  チーム ID
   * @param payload 作成リクエスト
   */
  async function createSchedule(
    teamId: number,
    payload: CreateShiftScheduleRequest,
  ): Promise<ShiftScheduleResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}?${query.toString()}`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  /**
   * シフトスケジュールを更新する。
   * @param scheduleId スケジュール ID
   * @param payload    更新リクエスト
   */
  async function updateSchedule(
    scheduleId: number,
    payload: UpdateShiftScheduleRequest,
  ): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  /**
   * シフトスケジュールを削除する（論理削除）。
   * @param scheduleId スケジュール ID
   */
  async function deleteSchedule(scheduleId: number): Promise<void> {
    await api(`${BASE}/${scheduleId}`, { method: 'DELETE' })
  }

  /**
   * シフトスケジュールのステータスを遷移する。
   * @param scheduleId スケジュール ID
   * @param status     遷移先ステータス
   */
  async function transitionStatus(
    scheduleId: number,
    status: string,
  ): Promise<ShiftScheduleResponse> {
    const query = new URLSearchParams()
    query.set('status', status)
    const res = await api<{ data: ShiftScheduleResponse }>(
      `${BASE}/${scheduleId}/transition?${query.toString()}`,
      { method: 'POST' },
    )
    return res.data
  }

  /**
   * シフトスケジュールを複製する。
   * @param scheduleId 複製元スケジュール ID
   */
  async function duplicateSchedule(scheduleId: number): Promise<ShiftScheduleResponse> {
    const res = await api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}/duplicate`, {
      method: 'POST',
    })
    return res.data
  }

  // === Auto Assign ===
  async function runAutoAssign(
    scheduleId: number,
    body: { strategy: string; parameters?: Record<string, unknown> },
  ) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/auto-assign`, {
      method: 'POST',
      body,
    })
  }

  async function confirmAutoAssign(
    scheduleId: number,
    req: { runId: number; assignmentIds: number[]; scheduleVersion: number },
  ) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/auto-assign/confirm`, {
      method: 'POST',
      body: req,
    })
  }

  async function revokeAutoAssign(scheduleId: number) {
    return api(`${BASE}/schedules/${scheduleId}/auto-assign`, { method: 'DELETE' })
  }

  async function getAssignmentRuns(scheduleId: number) {
    return api<{ data: unknown[] }>(`${BASE}/schedules/${scheduleId}/assignment-runs`)
  }

  async function getAssignmentRunDetail(runId: number) {
    return api<{ data: unknown }>(`${BASE}/assignment-runs/${runId}`)
  }

  async function confirmVisualReview(runId: number, note?: string) {
    return api(`${BASE}/assignment-runs/${runId}/confirm-visual-review`, {
      method: 'POST',
      body: { note },
    })
  }

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

  // === Work Constraints ===
  async function getWorkConstraints(teamId: number) {
    return api<{ data: unknown[] }>(`${BASE}/teams/${teamId}/work-constraints`)
  }

  async function upsertDefaultConstraint(teamId: number, req: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints`, {
      method: 'PUT',
      body: req,
    })
  }

  async function upsertMemberConstraint(
    teamId: number,
    userId: number,
    req: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints/${userId}`, {
      method: 'PUT',
      body: req,
    })
  }

  async function deleteMemberConstraint(teamId: number, userId: number) {
    return api(`${BASE}/teams/${teamId}/work-constraints/${userId}`, { method: 'DELETE' })
  }

  // === 変更依頼 ===
  async function createChangeRequest(payload: CreateChangeRequestPayload): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests`, {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function listChangeRequests(scheduleId: number): Promise<ChangeRequest[]> {
    const res = await api<{ data: ChangeRequest[] }>(`${BASE}/change-requests?scheduleId=${scheduleId}`)
    return res.data
  }

  async function getChangeRequest(id: number): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests/${id}`)
    return res.data
  }

  async function reviewChangeRequest(id: number, payload: ReviewChangeRequestPayload): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests/${id}/review`, {
      method: 'PATCH',
      body: payload,
    })
    return res.data
  }

  async function withdrawChangeRequest(id: number): Promise<void> {
    await api(`${BASE}/change-requests/${id}`, { method: 'DELETE' })
  }

  // === オープンコール ===
  async function claimOpenCall(swapRequestId: number): Promise<void> {
    await api(`${BASE}/swap-requests/${swapRequestId}/claim`, { method: 'POST' })
  }

  async function selectClaimer(swapRequestId: number, claimedBy: number): Promise<void> {
    await api(`${BASE}/swap-requests/${swapRequestId}/select-claimer`, {
      method: 'POST',
      body: claimedBy,
    })
  }

  // === PDF ===
  async function downloadShiftPdf(scheduleId: number, layout: 'team' | 'personal'): Promise<Blob> {
    const res = await api<Blob>(`${BASE}/schedules/${scheduleId}/pdf?layout=${layout}`, {
      responseType: 'blob',
    })
    return res
  }

  return {
    listSchedules,
    getSchedule,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    transitionStatus,
    duplicateSchedule,
    getPositions,
    createPosition,
    updatePosition,
    deletePosition,
    getShiftSlots,
    createShiftSlot,
    bulkCreateSlots,
    updateSlot,
    deleteSlot,
    listShiftRequests,
    submitShiftRequest,
    updateShiftRequest,
    deleteShiftRequest,
    getShiftRequestSummary,
    getMyShiftRequests,
    listSwapRequests,
    createSwapRequest,
    deleteSwapRequest,
    acceptSwap,
    resolveSwap,
    getAvailability,
    setAvailability,
    deleteAvailability,
    getHourlyRate,
    setHourlyRate,
    runAutoAssign,
    confirmAutoAssign,
    revokeAutoAssign,
    getAssignmentRuns,
    getAssignmentRunDetail,
    confirmVisualReview,
    patchSlotAssignments,
    getWorkConstraints,
    upsertDefaultConstraint,
    upsertMemberConstraint,
    deleteMemberConstraint,
    createChangeRequest,
    listChangeRequests,
    getChangeRequest,
    reviewChangeRequest,
    withdrawChangeRequest,
    claimOpenCall,
    selectClaimer,
    downloadShiftPdf,
  }
}
