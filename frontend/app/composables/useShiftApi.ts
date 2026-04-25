import type {
  ChangeRequest,
  CreateChangeRequestPayload,
  ReviewChangeRequestPayload,
} from '~/types/shift'

export function useShiftApi() {
  const api = useApi()

  const BASE = '/api/v1/shifts'

  // === Schedule ===
  async function listShiftSchedules(params?: { status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}/schedules?${query}`)
  }

  async function createShiftSchedule(body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules`, { method: 'POST', body })
  }

  async function getShiftSchedule(scheduleId: number) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}`)
  }

  async function updateShiftSchedule(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}`, { method: 'PATCH', body })
  }

  async function deleteShiftSchedule(scheduleId: number) {
    return api(`${BASE}/schedules/${scheduleId}`, { method: 'DELETE' })
  }

  async function duplicateShiftSchedule(scheduleId: number) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/duplicate`, { method: 'POST' })
  }

  async function transitionShiftSchedule(scheduleId: number, body?: Record<string, unknown>) {
    return api(`${BASE}/schedules/${scheduleId}/transition`, { method: 'POST', body })
  }

  // === Positions ===
  async function getPositions() {
    return api<{ data: unknown[] }>(`${BASE}/positions`)
  }

  async function createPosition(body: {
    name: string
    description?: string
    requiredCount: number
    color?: string
  }) {
    return api<{ data: unknown }>(`${BASE}/positions`, { method: 'POST', body })
  }

  async function updatePosition(positionId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/positions/${positionId}`, { method: 'PATCH', body })
  }

  async function deletePosition(positionId: number) {
    return api(`${BASE}/positions/${positionId}`, { method: 'DELETE' })
  }

  // === Slots ===
  async function getShiftSlots(
    scheduleId: number,
    params?: { date?: string; positionId?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.date) query.set('date', params.date)
    if (params?.positionId) query.set('positionId', String(params.positionId))
    return api<{ data: unknown[] }>(`${BASE}/schedules/${scheduleId}/slots?${query}`)
  }

  async function createShiftSlot(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/schedules/${scheduleId}/slots`, { method: 'POST', body })
  }

  async function bulkCreateSlots(scheduleId: number, body: Array<Record<string, unknown>>) {
    return api(`${BASE}/schedules/${scheduleId}/slots/bulk`, { method: 'POST', body })
  }

  async function updateSlot(slotId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/slots/${slotId}`, { method: 'PATCH', body })
  }

  async function deleteSlot(slotId: number) {
    return api(`${BASE}/slots/${slotId}`, { method: 'DELETE' })
  }

  // === Requests ===
  async function listShiftRequests(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{ data: unknown[] }>(`${BASE}/requests${qs ? `?${qs}` : ''}`)
  }

  async function submitShiftRequest(body: Record<string, unknown>) {
    return api(`${BASE}/requests`, { method: 'POST', body })
  }

  async function updateShiftRequest(requestId: number, body: Record<string, unknown>) {
    return api(`${BASE}/requests/${requestId}`, { method: 'PATCH', body })
  }

  async function deleteShiftRequest(requestId: number) {
    return api(`${BASE}/requests/${requestId}`, { method: 'DELETE' })
  }

  async function getShiftRequestSummary() {
    return api<{ data: unknown }>(`${BASE}/requests/summary`)
  }

  async function getMyShiftRequests() {
    return api<{ data: unknown[] }>(`${BASE}/my/requests`)
  }

  // === Swap Requests ===
  async function listSwapRequests(params?: { status?: string }) {
    const query = params?.status ? `?status=${params.status}` : ''
    return api<{ data: unknown[] }>(`${BASE}/swap-requests${query}`)
  }

  async function createSwapRequest(body: {
    slotId: number
    targetUserId: number
    reason?: string
  }) {
    return api<{ data: unknown }>(`${BASE}/swap-requests`, { method: 'POST', body })
  }

  async function deleteSwapRequest(swapId: number) {
    return api(`${BASE}/swap-requests/${swapId}`, { method: 'DELETE' })
  }

  async function acceptSwap(swapId: number) {
    return api(`${BASE}/swap-requests/${swapId}/accept`, { method: 'POST' })
  }

  async function resolveSwap(swapId: number, body?: Record<string, unknown>) {
    return api(`${BASE}/swap-requests/${swapId}/resolve`, { method: 'POST', body })
  }

  // === Availability ===
  async function getAvailability(params?: Record<string, string>) {
    const query = new URLSearchParams(params)
    const qs = query.toString()
    return api<{ data: unknown[] }>(`${BASE}/availability${qs ? `?${qs}` : ''}`)
  }

  async function setAvailability(
    body: Array<{
      dayOfWeek: number
      startTime: string | null
      endTime: string | null
      isAvailable: boolean
    }>,
  ) {
    return api(`${BASE}/availability`, { method: 'PUT', body })
  }

  async function deleteAvailability() {
    return api(`${BASE}/availability`, { method: 'DELETE' })
  }

  // === Hourly Rate ===
  async function getHourlyRate() {
    return api<{ data: unknown }>(`${BASE}/hourly-rate`)
  }

  async function setHourlyRate(body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/hourly-rate`, { method: 'POST', body })
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
    listShiftSchedules,
    createShiftSchedule,
    getShiftSchedule,
    updateShiftSchedule,
    deleteShiftSchedule,
    duplicateShiftSchedule,
    transitionShiftSchedule,
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
