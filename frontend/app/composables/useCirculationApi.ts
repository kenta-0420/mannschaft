import type {
  CirculationResponse,
  CirculationDetailResponse,
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
    return api<{ data: CirculationResponse[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/circulation?${qs}`)
  }

  async function getCirculation(id: number) {
    return api<CirculationDetailResponse>(`/api/v1/circulation/${id}`)
  }

  async function createCirculation(scopeType: string, scopeId: number, body: Record<string, unknown>) {
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
  }
}
