import type {
  IncidentSummaryResponse,
  IncidentResponse,
  IncidentCategoryResponse,
  IncidentCommentResponse,
  ReportIncidentRequest,
  UpdateIncidentRequest,
  ChangeStatusRequest,
  AssignIncidentRequest,
  CreateIncidentCategoryRequest,
  UpdateIncidentCategoryRequest,
} from '~/types/incident'

export function useIncidentApi() {
  const api = useApi()
  const BASE = '/api/incidents'

  // === Incidents ===

  async function listIncidents(
    scopeType: string,
    scopeId: number,
    params?: { status?: string; page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('scopeType', scopeType)
    query.set('scopeId', String(scopeId))
    if (params) {
      if (params.status) query.set('status', params.status)
      if (params.page !== undefined) query.set('page', String(params.page))
      if (params.size !== undefined) query.set('size', String(params.size))
    }
    return api<{
      data: IncidentSummaryResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${BASE}?${query}`)
  }

  async function getIncident(id: number) {
    return api<{ data: IncidentResponse }>(`${BASE}/${id}`)
  }

  async function reportIncident(body: ReportIncidentRequest) {
    return api<{ data: IncidentResponse }>(BASE, { method: 'POST', body })
  }

  async function updateIncident(id: number, body: UpdateIncidentRequest) {
    return api<{ data: IncidentResponse }>(`${BASE}/${id}`, { method: 'PUT', body })
  }

  async function deleteIncident(id: number) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  async function changeStatus(id: number, body: ChangeStatusRequest) {
    return api<{ data: IncidentResponse }>(`${BASE}/${id}/status`, { method: 'PATCH', body })
  }

  async function assignIncident(id: number, body: AssignIncidentRequest) {
    return api<{ data: IncidentResponse }>(`${BASE}/${id}/assign`, { method: 'POST', body })
  }

  async function getComments(id: number) {
    return api<{ data: IncidentCommentResponse[] }>(`${BASE}/${id}/comments`)
  }

  // === Categories ===

  async function listCategories(scopeType: string, scopeId: number) {
    const query = new URLSearchParams()
    query.set('scopeType', scopeType)
    query.set('scopeId', String(scopeId))
    return api<{ data: IncidentCategoryResponse[] }>(`${BASE}/categories?${query}`)
  }

  async function createCategory(body: CreateIncidentCategoryRequest) {
    return api<{ data: IncidentCategoryResponse }>(`${BASE}/categories`, { method: 'POST', body })
  }

  async function updateCategory(id: number, body: UpdateIncidentCategoryRequest) {
    return api<{ data: IncidentCategoryResponse }>(`${BASE}/categories/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteCategory(id: number) {
    return api(`${BASE}/categories/${id}`, { method: 'DELETE' })
  }

  return {
    listIncidents,
    getIncident,
    reportIncident,
    updateIncident,
    deleteIncident,
    changeStatus,
    assignIncident,
    getComments,
    listCategories,
    createCategory,
    updateCategory,
    deleteCategory,
  }
}
