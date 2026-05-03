import type {
  AvailabilityDefaultResponse,
  BulkAvailabilityDefaultRequest,
  BulkCreateShiftSlotRequest,
  ChangeRequest,
  CreateChangeRequestPayload,
  CreateHourlyRateRequest,
  CreatePositionRequest,
  CreateShiftRequestRequest,
  CreateShiftScheduleRequest,
  CreateShiftSlotRequest,
  CreateSwapRequestRequest,
  ReviewChangeRequestPayload,
  ResolveSwapRequestRequest,
  ShiftHourlyRateResponse,
  ShiftPositionResponse,
  ShiftRequestResponse,
  ShiftRequestSummaryResponse,
  ShiftScheduleResponse,
  ShiftSlotResponse,
  SwapRequestResponse,
  UpdatePositionRequest,
  UpdateShiftRequestRequest,
  UpdateShiftScheduleRequest,
  UpdateShiftSlotRequest,
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
   * シフトスケジュール詳細を取得する（board.vue 互換エイリアス）。
   * @param scheduleId スケジュール ID
   */
  async function getShiftSchedule(scheduleId: number): Promise<{ data: ShiftScheduleResponse }> {
    return api<{ data: ShiftScheduleResponse }>(`${BASE}/${scheduleId}`)
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

  // === Positions ===
  /**
   * チームのポジション一覧を取得する（board.vue 互換）。
   * @param teamId チーム ID（省略時は teamId なしで取得）
   */
  async function getPositions(teamId?: number): Promise<{ data: ShiftPositionResponse[] }> {
    const query = new URLSearchParams()
    if (teamId) query.set('teamId', String(teamId))
    const qs = teamId ? `?${query.toString()}` : ''
    return api<{ data: ShiftPositionResponse[] }>(`/api/v1/shifts/positions${qs}`)
  }

  async function createPosition(
    teamId: number,
    payload: CreatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftPositionResponse }>(
      `/api/v1/shifts/positions?${query.toString()}`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  async function updatePosition(
    positionId: number,
    payload: UpdatePositionRequest,
  ): Promise<ShiftPositionResponse> {
    const res = await api<{ data: ShiftPositionResponse }>(
      `/api/v1/shifts/positions/${positionId}`,
      { method: 'PATCH', body: payload },
    )
    return res.data
  }

  async function deletePosition(positionId: number): Promise<void> {
    await api(`/api/v1/shifts/positions/${positionId}`, { method: 'DELETE' })
  }

  // === Slots ===
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

  // === Shift Requests ===
  async function listShiftRequests(scheduleId: number): Promise<ShiftRequestResponse[]> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestResponse[] }>(
      `/api/v1/shifts/requests?${query.toString()}`,
    )
    return res.data
  }

  async function submitShiftRequest(
    payload: CreateShiftRequestRequest,
  ): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>('/api/v1/shifts/requests', {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function updateShiftRequest(
    requestId: number,
    payload: UpdateShiftRequestRequest,
  ): Promise<ShiftRequestResponse> {
    const res = await api<{ data: ShiftRequestResponse }>(
      `/api/v1/shifts/requests/${requestId}`,
      { method: 'PATCH', body: payload },
    )
    return res.data
  }

  async function deleteShiftRequest(requestId: number): Promise<void> {
    await api(`/api/v1/shifts/requests/${requestId}`, { method: 'DELETE' })
  }

  async function getShiftRequestSummary(scheduleId: number): Promise<ShiftRequestSummaryResponse> {
    const query = new URLSearchParams()
    query.set('scheduleId', String(scheduleId))
    const res = await api<{ data: ShiftRequestSummaryResponse }>(
      `/api/v1/shifts/requests/summary?${query.toString()}`,
    )
    return res.data
  }

  async function getMyShiftRequests(): Promise<ShiftRequestResponse[]> {
    const res = await api<{ data: ShiftRequestResponse[] }>('/api/v1/shifts/my/requests')
    return res.data
  }

  // === Swap Requests ===
  async function listSwapRequests(status?: string): Promise<SwapRequestResponse[]> {
    const query = status ? `?status=${encodeURIComponent(status)}` : ''
    const res = await api<{ data: SwapRequestResponse[] }>(
      `/api/v1/shifts/swap-requests${query}`,
    )
    return res.data
  }

  async function createSwapRequest(
    payload: CreateSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>('/api/v1/shifts/swap-requests', {
      method: 'POST',
      body: payload,
    })
    return res.data
  }

  async function deleteSwapRequest(swapId: number): Promise<void> {
    await api(`/api/v1/shifts/swap-requests/${swapId}`, { method: 'DELETE' })
  }

  async function acceptSwap(swapId: number): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/accept`,
      { method: 'POST' },
    )
    return res.data
  }

  async function resolveSwap(
    swapId: number,
    payload: ResolveSwapRequestRequest,
  ): Promise<SwapRequestResponse> {
    const res = await api<{ data: SwapRequestResponse }>(
      `/api/v1/shifts/swap-requests/${swapId}/resolve`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  // === Availability ===
  async function getAvailability(teamId: number): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(
      `/api/v1/shifts/availability?${query.toString()}`,
    )
    return res.data
  }

  async function setAvailability(
    teamId: number,
    payload: BulkAvailabilityDefaultRequest,
  ): Promise<AvailabilityDefaultResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: AvailabilityDefaultResponse[] }>(
      `/api/v1/shifts/availability?${query.toString()}`,
      { method: 'PUT', body: payload },
    )
    return res.data
  }

  async function deleteAvailability(teamId: number): Promise<void> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    await api(`/api/v1/shifts/availability?${query.toString()}`, { method: 'DELETE' })
  }

  // === Hourly Rate ===
  async function getHourlyRate(
    teamId: number,
    userId: number,
    date?: string,
  ): Promise<ShiftHourlyRateResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    query.set('userId', String(userId))
    if (date) query.set('date', date)
    const res = await api<{ data: ShiftHourlyRateResponse[] }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
    )
    return res.data
  }

  async function setHourlyRate(
    teamId: number,
    payload: CreateHourlyRateRequest,
  ): Promise<ShiftHourlyRateResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftHourlyRateResponse }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  // === Auto Assign ===
  async function runAutoAssign(
    scheduleId: number,
    body: { strategy: string; parameters?: Record<string, unknown> },
  ) {
    return api<{ data: unknown }>(`${BASE}/${scheduleId}/auto-assign`, {
      method: 'POST',
      body,
    })
  }

  async function confirmAutoAssign(
    scheduleId: number,
    req: { runId: number; assignmentIds: number[]; scheduleVersion: number },
  ) {
    return api<{ data: unknown }>(`${BASE}/${scheduleId}/auto-assign/confirm`, {
      method: 'POST',
      body: req,
    })
  }

  async function revokeAutoAssign(scheduleId: number) {
    return api(`${BASE}/${scheduleId}/auto-assign`, { method: 'DELETE' })
  }

  async function getAssignmentRuns(scheduleId: number) {
    return api<{ data: unknown[] }>(`${BASE}/${scheduleId}/assignment-runs`)
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
    const res = await api<{ data: ChangeRequest[] }>(
      `${BASE}/change-requests?scheduleId=${scheduleId}`,
    )
    return res.data
  }

  async function getChangeRequest(id: number): Promise<ChangeRequest> {
    const res = await api<{ data: ChangeRequest }>(`${BASE}/change-requests/${id}`)
    return res.data
  }

  async function reviewChangeRequest(
    id: number,
    payload: ReviewChangeRequestPayload,
  ): Promise<ChangeRequest> {
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
      body: { claimedBy },
    })
  }

  // === PDF ===
  async function downloadShiftPdf(scheduleId: number, layout: 'team' | 'personal'): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    return $fetch<Blob>(`${config.public.apiBase}/api/v1${BASE}/${scheduleId}/pdf?layout=${layout}`, {
      responseType: 'blob',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  }

  return {
    listSchedules,
    getSchedule,
    getShiftSchedule,
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
