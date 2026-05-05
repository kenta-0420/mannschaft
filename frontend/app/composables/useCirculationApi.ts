import type {
  CirculationResponse,
  CirculationDetailResponse,
  CirculationComment,
  CirculationStampRequest,
  CirculationStatsResponse,
  CirculationAttachment,
  AddRecipientsRequest,
  CirculationRecipient,
  CirculationAttachmentPresignRequest,
  CirculationAttachmentPresignResponse,
} from '~/types/circulation'

interface CirculationListParams {
  scopeType: string
  scopeId: number
  status?: string
  keyword?: string
  overdueOnly?: boolean
  page?: number
  size?: number
}

export function useCirculationApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        query.set(key, String(value))
      }
    }
    return query.toString()
  }

  async function getCirculations(params: CirculationListParams) {
    const qs = buildQuery({
      scope_type: params.scopeType,
      scope_id: params.scopeId,
      status: params.status,
      keyword: params.keyword,
      overdue_only: params.overdueOnly,
      page: params.page ?? 0,
      size: params.size ?? 20,
    })
    return api<{
      data: CirculationResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/circulation?${qs}`)
  }

  async function getCirculation(id: number) {
    return api<CirculationDetailResponse>(`/api/v1/circulation/${id}`)
  }

  async function createCirculation(
    scopeType: string,
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: CirculationResponse }>('/api/v1/circulation', {
      method: 'POST',
      body: { ...body, scopeType, scopeId },
    })
  }

  async function updateCirculation(id: number, body: Record<string, unknown>) {
    return api<{ data: CirculationResponse }>(`/api/v1/circulation/${id}`, { method: 'PUT', body })
  }

  async function deleteCirculation(id: number) {
    return api(`/api/v1/circulation/${id}`, { method: 'DELETE' })
  }

  async function startCirculation(id: number) {
    return api(`/api/v1/circulation/${id}/start`, { method: 'POST' })
  }

  async function cancelCirculation(id: number) {
    return api(`/api/v1/circulation/${id}/cancel`, { method: 'POST' })
  }

  async function forceComplete(id: number) {
    return api(`/api/v1/circulation/${id}/force-complete`, { method: 'POST' })
  }

  async function stamp(id: number, comment?: string) {
    return api(`/api/v1/circulation/${id}/stamp`, {
      method: 'POST',
      body: comment ? { comment } : {},
    })
  }

  async function correctStamp(id: number) {
    return api(`/api/v1/circulation/${id}/stamp/correct`, { method: 'POST' })
  }

  async function skipRecipient(id: number, userId: number) {
    return api(`/api/v1/circulation/${id}/recipients/${userId}/skip`, { method: 'PATCH' })
  }

  async function sendReminder(id: number) {
    return api(`/api/v1/circulation/${id}/remind`, { method: 'POST' })
  }

  async function getStampStatus(id: number) {
    return api(`/api/v1/circulation/${id}/status`)
  }

  async function exportPdf(id: number) {
    return api(`/api/v1/circulation/${id}/export`)
  }

  async function getMyCirculations(filter?: 'pending' | 'completed') {
    const qs = filter ? `?filter=${filter}` : ''
    return api<{ data: CirculationResponse[] }>(`/api/v1/circulation/my${qs}`)
  }

  // === Scoped Circulation CRUD ===
  async function listScopedCirculations(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: { page?: number; size?: number },
  ) {
    const base =
      scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: CirculationResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${base}/circulations?${query}`)
  }

  async function createScopedCirculation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    const base =
      scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    return api<{ data: CirculationResponse }>(`${base}/circulations`, { method: 'POST', body })
  }

  async function getScopedCirculation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    documentId: number,
  ) {
    const base =
      scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    return api<CirculationDetailResponse>(`${base}/circulations/${documentId}`)
  }

  async function updateScopedCirculation(
    teamId: number,
    documentId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: CirculationResponse }>(
      `/api/v1/teams/${teamId}/circulations/${documentId}`,
      { method: 'PATCH', body },
    )
  }

  async function deleteScopedCirculation(teamId: number, documentId: number) {
    return api(`/api/v1/teams/${teamId}/circulations/${documentId}`, { method: 'DELETE' })
  }

  async function activateCirculation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    documentId: number,
  ) {
    const base =
      scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    return api(`${base}/circulations/${documentId}/activate`, { method: 'POST' })
  }

  async function cancelScopedCirculation(teamId: number, documentId: number) {
    return api(`/api/v1/teams/${teamId}/circulations/${documentId}/cancel`, { method: 'POST' })
  }

  async function getCirculationStats(teamId: number) {
    return api<{ data: CirculationStatsResponse }>(`/api/v1/teams/${teamId}/circulations/stats`)
  }

  async function getMyCreatedCirculations(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page) query.set('page', String(params.page))
    if (params?.size) query.set('size', String(params.size))
    return api<{ data: CirculationResponse[] }>(`/api/v1/me/circulations/created?${query}`)
  }

  // === Global Circulation Sub-resources ===
  async function getAttachments(documentId: number) {
    return api<{ data: CirculationAttachment[] }>(`/api/v1/circulations/${documentId}/attachments`)
  }

  async function createAttachment(documentId: number, body: Record<string, unknown>) {
    return api<{ data: CirculationAttachment }>(`/api/v1/circulations/${documentId}/attachments`, {
      method: 'POST',
      body,
    })
  }

  /**
   * F13 Phase 5-a: 回覧板添付ファイルアップロード用の Presigned URL をサーバー側で生成する。
   * 返却された uploadUrl で R2 に直接 PUT し、完了後に fileKey を createAttachment に渡す。
   */
  async function presignAttachmentUpload(
    documentId: number,
    request: CirculationAttachmentPresignRequest,
  ) {
    return api<{ data: CirculationAttachmentPresignResponse }>(
      `/api/v1/circulations/${documentId}/attachments/upload-url`,
      { method: 'POST', body: request },
    )
  }

  async function getComments(documentId: number, params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page) query.set('page', String(params.page))
    if (params?.size) query.set('size', String(params.size))
    return api<{
      data: CirculationComment[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/circulations/${documentId}/comments?${query}`)
  }

  async function createComment(documentId: number, body: string) {
    return api<{ data: CirculationComment }>(`/api/v1/circulations/${documentId}/comments`, {
      method: 'POST',
      body: { body },
    })
  }

  async function getRecipients(documentId: number) {
    return api<{ data: CirculationRecipient[] }>(`/api/v1/circulations/${documentId}/recipients`)
  }

  async function addRecipients(documentId: number, body: AddRecipientsRequest) {
    return api(`/api/v1/circulations/${documentId}/recipients`, { method: 'POST', body })
  }

  async function removeRecipient(documentId: number, recipientId: number) {
    return api(`/api/v1/circulations/${documentId}/recipients/${recipientId}`, { method: 'DELETE' })
  }

  async function stampDocument(documentId: number, body: CirculationStampRequest) {
    return api(`/api/v1/circulations/${documentId}/stamp`, { method: 'POST', body })
  }

  async function rejectStamp(documentId: number) {
    return api(`/api/v1/circulations/${documentId}/stamp/reject`, { method: 'POST' })
  }

  async function skipStamp(documentId: number) {
    return api(`/api/v1/circulations/${documentId}/stamp/skip`, { method: 'POST' })
  }

  return {
    getCirculations,
    getCirculation,
    createCirculation,
    updateCirculation,
    deleteCirculation,
    startCirculation,
    cancelCirculation,
    forceComplete,
    stamp,
    correctStamp,
    skipRecipient,
    sendReminder,
    getStampStatus,
    exportPdf,
    getMyCirculations,
    listScopedCirculations,
    createScopedCirculation,
    getScopedCirculation,
    updateScopedCirculation,
    deleteScopedCirculation,
    activateCirculation,
    cancelScopedCirculation,
    getCirculationStats,
    getMyCreatedCirculations,
    getAttachments,
    createAttachment,
    presignAttachmentUpload,
    getComments,
    createComment,
    getRecipients,
    addRecipients,
    removeRecipient,
    stampDocument,
    rejectStamp,
    skipStamp,
  }
}
