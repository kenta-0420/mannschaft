import type {
  Chart,
  ChartTemplate,
  CreateChartRequest,
  ChartListParams,
  ChartFormula,
  ChartIntakeForm,
  ChartBodyMark,
  ChartCustomField,
  ChartRecordTemplate,
  ChartSectionSettings,
  ChartCustomerProgress,
} from '~/types/chart'

export function useChartApi() {
  const api = useApi()

  // === Team-scoped chart list / CRUD ===
  async function list(teamId: number, params?: ChartListParams) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    if (params?.staffId != null) query.set('staffId', String(params.staffId))
    if (params?.clientName) query.set('clientName', params.clientName)
    if (params?.dateFrom) query.set('dateFrom', params.dateFrom)
    if (params?.dateTo) query.set('dateTo', params.dateTo)
    const qs = query.toString()
    return api<{ data: Chart[]; meta: { totalElements: number } }>(
      `/api/v1/teams/${teamId}/charts${qs ? `?${qs}` : ''}`,
    )
  }

  async function get(teamId: number, chartId: number) {
    return api<{ data: Chart }>(`/api/v1/teams/${teamId}/charts/${chartId}`)
  }

  async function create(teamId: number, body: CreateChartRequest) {
    return api<{ data: Chart }>(`/api/v1/teams/${teamId}/charts`, { method: 'POST', body })
  }

  async function update(
    teamId: number,
    chartId: number,
    body: Partial<CreateChartRequest> & { version: number },
  ) {
    return api<{ data: Chart }>(`/api/v1/teams/${teamId}/charts/${chartId}`, {
      method: 'PUT',
      body,
    })
  }

  async function remove(teamId: number, chartId: number) {
    await api(`/api/v1/teams/${teamId}/charts/${chartId}`, { method: 'DELETE' })
  }

  // === My Charts ===
  async function listMyCharts(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{ data: Chart[]; meta: { totalElements: number } }>(
      `/api/v1/charts/me${qs ? `?${qs}` : ''}`,
    )
  }

  // === Customer Charts ===
  async function getCustomerCharts(
    teamId: number,
    userId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    const qs = query.toString()
    return api<{ data: Chart[]; meta: { totalElements: number } }>(
      `/api/v1/teams/${teamId}/charts/customer/${userId}${qs ? `?${qs}` : ''}`,
    )
  }

  async function getCustomerProgress(
    teamId: number,
    userId: number,
    params?: { fieldIds?: string; visitDateFrom?: string; visitDateTo?: string },
  ) {
    const query = new URLSearchParams()
    if (params?.fieldIds) query.set('fieldIds', params.fieldIds)
    if (params?.visitDateFrom) query.set('visitDateFrom', params.visitDateFrom)
    if (params?.visitDateTo) query.set('visitDateTo', params.visitDateTo)
    const qs = query.toString()
    return api<{ data: ChartCustomerProgress[] }>(
      `/api/v1/teams/${teamId}/charts/customer/${userId}/progress${qs ? `?${qs}` : ''}`,
    )
  }

  // === Body Marks ===
  async function updateBodyMarks(
    teamId: number,
    chartId: number,
    body: { marks: ChartBodyMark[] },
  ) {
    return api(`/api/v1/teams/${teamId}/charts/${chartId}/body-marks`, { method: 'PUT', body })
  }

  // === Copy ===
  async function copyChart(teamId: number, chartId: number, body?: Record<string, unknown>) {
    return api<{ data: Chart }>(`/api/v1/teams/${teamId}/charts/${chartId}/copy`, {
      method: 'POST',
      body,
    })
  }

  // === Formulas ===
  async function getFormulas(teamId: number, chartId: number) {
    return api<{ data: ChartFormula[] }>(`/api/v1/teams/${teamId}/charts/${chartId}/formulas`)
  }

  async function createFormula(teamId: number, chartId: number, body: Record<string, unknown>) {
    return api<{ data: ChartFormula }>(`/api/v1/teams/${teamId}/charts/${chartId}/formulas`, {
      method: 'POST',
      body,
    })
  }

  async function updateFormula(teamId: number, formulaId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/charts/formulas/${formulaId}`, { method: 'PUT', body })
  }

  async function deleteFormula(teamId: number, formulaId: number) {
    await api(`/api/v1/teams/${teamId}/charts/formulas/${formulaId}`, { method: 'DELETE' })
  }

  // === Intake Form ===
  async function getIntakeForm(teamId: number, chartId: number) {
    return api<{ data: ChartIntakeForm }>(`/api/v1/teams/${teamId}/charts/${chartId}/intake-form`)
  }

  async function updateIntakeForm(teamId: number, chartId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/charts/${chartId}/intake-form`, { method: 'PUT', body })
  }

  // === PDF ===
  async function getChartPdf(teamId: number, chartId: number) {
    return api<Blob>(`/api/v1/teams/${teamId}/charts/${chartId}/pdf`)
  }

  // === Photos ===
  async function uploadPhoto(teamId: number, chartId: number, file: File, photoType: string) {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('photoType', photoType)
    return api<{ data: { photoUrl: string } }>(`/api/v1/teams/${teamId}/charts/${chartId}/photos`, {
      method: 'POST',
      body: formData,
    })
  }

  async function deletePhoto(teamId: number, photoId: number) {
    await api(`/api/v1/teams/${teamId}/charts/photos/${photoId}`, { method: 'DELETE' })
  }

  // === Pin & Share ===
  async function togglePin(teamId: number, chartId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/charts/${chartId}/pin`, { method: 'PATCH', body })
  }

  async function share(teamId: number, chartId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/charts/${chartId}/share`, { method: 'PATCH', body })
  }

  // === Templates (legacy) ===
  async function listTemplates(teamId: number) {
    return api<{ data: ChartTemplate[] }>(`/api/v1/teams/${teamId}/chart-templates`)
  }

  // === Settings: Custom Fields ===
  async function getCustomFields(teamId: number) {
    return api<{ data: ChartCustomField[] }>(
      `/api/v1/teams/${teamId}/charts/settings/custom-fields`,
    )
  }

  async function createCustomField(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ChartCustomField }>(
      `/api/v1/teams/${teamId}/charts/settings/custom-fields`,
      { method: 'POST', body },
    )
  }

  async function updateCustomField(teamId: number, fieldId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/charts/settings/custom-fields/${fieldId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteCustomField(teamId: number, fieldId: number) {
    await api(`/api/v1/teams/${teamId}/charts/settings/custom-fields/${fieldId}`, {
      method: 'DELETE',
    })
  }

  // === Settings: Record Templates ===
  async function getRecordTemplates(teamId: number) {
    return api<{ data: ChartRecordTemplate[] }>(
      `/api/v1/teams/${teamId}/charts/settings/record-templates`,
    )
  }

  async function createRecordTemplate(teamId: number, body: Record<string, unknown>) {
    return api<{ data: ChartRecordTemplate }>(
      `/api/v1/teams/${teamId}/charts/settings/record-templates`,
      { method: 'POST', body },
    )
  }

  async function updateRecordTemplate(
    teamId: number,
    templateId: number,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/teams/${teamId}/charts/settings/record-templates/${templateId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteRecordTemplate(teamId: number, templateId: number) {
    await api(`/api/v1/teams/${teamId}/charts/settings/record-templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  // === Settings: Sections ===
  async function getSectionSettings(teamId: number) {
    return api<{ data: ChartSectionSettings }>(`/api/v1/teams/${teamId}/charts/settings/sections`)
  }

  async function updateSectionSettings(teamId: number, body: ChartSectionSettings) {
    return api(`/api/v1/teams/${teamId}/charts/settings/sections`, { method: 'PUT', body })
  }

  return {
    list,
    get,
    create,
    update,
    remove,
    listMyCharts,
    getCustomerCharts,
    getCustomerProgress,
    updateBodyMarks,
    copyChart,
    getFormulas,
    createFormula,
    updateFormula,
    deleteFormula,
    getIntakeForm,
    updateIntakeForm,
    getChartPdf,
    uploadPhoto,
    deletePhoto,
    togglePin,
    share,
    listTemplates,
    getCustomFields,
    createCustomField,
    updateCustomField,
    deleteCustomField,
    getRecordTemplates,
    createRecordTemplate,
    updateRecordTemplate,
    deleteRecordTemplate,
    getSectionSettings,
    updateSectionSettings,
  }
}
