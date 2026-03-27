import type { ServiceRecordResponse, ServiceRecordTemplate, ServiceHistorySummary } from '~/types/service'

export function useServiceRecordApi() {
  const api = useApi()

  async function getRecords(teamId: number, params?: Record<string, unknown>) {
    const query = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) query.set(k, String(v)) }
    return api<{ data: ServiceRecordResponse[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/teams/${teamId}/service-records?${query}`)
  }

  async function getRecord(teamId: number, recordId: number) {
    return api<{ data: ServiceRecordResponse }>(`/api/v1/teams/${teamId}/service-records/${recordId}`)
  }

  async function createRecord(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordResponse }>(`/api/v1/teams/${teamId}/service-records`, { method: 'POST', body })
  }

  async function updateRecord(teamId: number, recordId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordResponse }>(`/api/v1/teams/${teamId}/service-records/${recordId}`, { method: 'PUT', body })
  }

  async function confirmRecord(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/confirm`, { method: 'PATCH' })
  }

  async function deleteRecord(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}`, { method: 'DELETE' })
  }

  async function getMemberHistory(teamId: number, userId: number) {
    return api<{ data: ServiceRecordResponse[] }>(`/api/v1/teams/${teamId}/members/${userId}/service-history`)
  }

  async function getMemberSummary(teamId: number, userId: number) {
    return api<{ data: ServiceHistorySummary }>(`/api/v1/teams/${teamId}/members/${userId}/service-history/summary`)
  }

  async function getMyHistory() {
    return api<{ data: ServiceRecordResponse[] }>('/api/v1/service-records/me')
  }

  async function getTemplates(teamId: number) {
    return api<{ data: ServiceRecordTemplate[] }>(`/api/v1/teams/${teamId}/service-records/templates`)
  }

  return { getRecords, getRecord, createRecord, updateRecord, confirmRecord, deleteRecord, getMemberHistory, getMemberSummary, getMyHistory, getTemplates }
}
