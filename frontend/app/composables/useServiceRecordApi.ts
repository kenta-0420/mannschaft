import type { ServiceRecordResponse, ServiceRecordTemplate } from '~/types/service'

interface ServiceRecordField {
  id: number
  fieldName: string
  fieldType: string
  isRequired: boolean
  sortOrder: number
}

interface ServiceRecordSettings {
  requireConfirmation: boolean
  allowSelfRecord: boolean
}

export function useServiceRecordApi() {
  const api = useApi()

  function buildQuery(params?: Record<string, unknown>): string {
    const query = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) query.set(k, String(v))
      }
    return query.toString()
  }

  // === Records ===
  async function getRecords(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{
      data: ServiceRecordResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/teams/${teamId}/service-records?${qs}`)
  }

  async function getRecord(teamId: number, recordId: number) {
    return api<{ data: ServiceRecordResponse }>(
      `/api/v1/teams/${teamId}/service-records/${recordId}`,
    )
  }

  async function createRecord(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordResponse }>(`/api/v1/teams/${teamId}/service-records`, {
      method: 'POST',
      body,
    })
  }

  async function bulkCreateRecords(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-records/bulk`, { method: 'POST', body })
  }

  async function updateRecord(teamId: number, recordId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordResponse }>(
      `/api/v1/teams/${teamId}/service-records/${recordId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteRecord(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}`, { method: 'DELETE' })
  }

  async function confirmRecord(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/confirm`, { method: 'PATCH' })
  }

  async function duplicateRecord(teamId: number, recordId: number) {
    return api<{ data: ServiceRecordResponse }>(
      `/api/v1/teams/${teamId}/service-records/${recordId}/duplicate`,
      { method: 'POST' },
    )
  }

  async function exportRecords(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/teams/${teamId}/service-records/export?${qs}`)
  }

  // === Attachments ===
  async function addAttachment(
    teamId: number,
    recordId: number,
    body: FormData | Record<string, unknown>,
  ) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/attachments`, {
      method: 'POST',
      body,
    })
  }

  async function getAttachmentUploadUrl(
    teamId: number,
    recordId: number,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/attachments/upload-url`, {
      method: 'POST',
      body,
    })
  }

  async function deleteAttachment(teamId: number, recordId: number, attachmentId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/attachments/${attachmentId}`, {
      method: 'DELETE',
    })
  }

  // === Reactions ===
  async function addReaction(teamId: number, recordId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/reactions`, {
      method: 'POST',
      body,
    })
  }

  async function removeReaction(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/${recordId}/reactions`, {
      method: 'DELETE',
    })
  }

  // === Settings ===
  async function getSettings(teamId: number) {
    return api<{ data: ServiceRecordSettings }>(`/api/v1/teams/${teamId}/service-records/settings`)
  }

  async function updateSettings(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-records/settings`, { method: 'PUT', body })
  }

  // === Fields ===
  async function getFields(teamId: number) {
    return api<{ data: ServiceRecordField[] }>(`/api/v1/teams/${teamId}/service-record-fields`)
  }

  async function createField(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordField }>(`/api/v1/teams/${teamId}/service-record-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateField(teamId: number, fieldId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-record-fields/${fieldId}`, { method: 'PUT', body })
  }

  async function deleteField(teamId: number, fieldId: number) {
    return api(`/api/v1/teams/${teamId}/service-record-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function updateFieldSortOrder(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-record-fields/sort-order`, {
      method: 'PATCH',
      body,
    })
  }

  // === Templates (team) ===
  async function getTemplates(teamId: number) {
    return api<{ data: ServiceRecordTemplate[] }>(
      `/api/v1/teams/${teamId}/service-records/templates`,
    )
  }

  async function getTemplate(teamId: number, templateId: number) {
    return api<{ data: ServiceRecordTemplate }>(
      `/api/v1/teams/${teamId}/service-records/templates/${templateId}`,
    )
  }

  async function createTemplate(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordTemplate }>(
      `/api/v1/teams/${teamId}/service-records/templates`,
      { method: 'POST', body },
    )
  }

  async function updateTemplate(teamId: number, templateId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/service-records/templates/${templateId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTemplate(teamId: number, templateId: number) {
    return api(`/api/v1/teams/${teamId}/service-records/templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  // === Templates (organization) ===
  async function getOrgTemplates(orgId: number) {
    return api<{ data: ServiceRecordTemplate[] }>(
      `/api/v1/organizations/${orgId}/service-records/templates`,
    )
  }

  async function createOrgTemplate(orgId: number, body: Record<string, unknown>) {
    return api<{ data: ServiceRecordTemplate }>(
      `/api/v1/organizations/${orgId}/service-records/templates`,
      { method: 'POST', body },
    )
  }

  async function updateOrgTemplate(
    orgId: number,
    templateId: number,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/organizations/${orgId}/service-records/templates/${templateId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteOrgTemplate(orgId: number, templateId: number) {
    return api(`/api/v1/organizations/${orgId}/service-records/templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  // === Member History ===
  async function getMemberHistory(
    teamId: number,
    userId: number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery(params)
    return api<{ data: ServiceRecordResponse[] }>(
      `/api/v1/teams/${teamId}/members/${userId}/service-history${qs ? `?${qs}` : ''}`,
    )
  }

  async function getMemberSummary(teamId: number, userId: number) {
    return api<{ data: Record<string, unknown> }>(
      `/api/v1/teams/${teamId}/members/${userId}/service-history/summary`,
    )
  }

  // === My History ===
  async function getMyHistory() {
    return api<{ data: ServiceRecordResponse[] }>('/api/v1/service-records/me')
  }

  return {
    getRecords,
    getRecord,
    createRecord,
    bulkCreateRecords,
    updateRecord,
    deleteRecord,
    confirmRecord,
    duplicateRecord,
    exportRecords,
    addAttachment,
    getAttachmentUploadUrl,
    deleteAttachment,
    addReaction,
    removeReaction,
    getSettings,
    updateSettings,
    getFields,
    createField,
    updateField,
    deleteField,
    updateFieldSortOrder,
    getTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    getOrgTemplates,
    createOrgTemplate,
    updateOrgTemplate,
    deleteOrgTemplate,
    getMemberHistory,
    getMemberSummary,
    getMyHistory,
  }
}
