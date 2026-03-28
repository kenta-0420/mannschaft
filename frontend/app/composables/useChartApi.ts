import type { Chart, ChartTemplate, CreateChartRequest, ChartListParams } from '~/types/chart'

export function useChartApi() {
  const api = useApi()

  async function list(teamId: number, params?: ChartListParams) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    if (params?.staffId != null) query.set('staffId', String(params.staffId))
    if (params?.clientName) query.set('clientName', params.clientName)
    if (params?.dateFrom) query.set('dateFrom', params.dateFrom)
    if (params?.dateTo) query.set('dateTo', params.dateTo)
    const qs = query.toString()
    const res = await api<{ data: Chart[]; meta: { totalElements: number } }>(
      `/api/v1/teams/${teamId}/charts${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function get(chartId: number) {
    const res = await api<{ data: Chart }>(`/api/v1/charts/${chartId}`)
    return res.data
  }

  async function create(teamId: number, body: CreateChartRequest) {
    const res = await api<{ data: Chart }>(`/api/v1/teams/${teamId}/charts`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function update(chartId: number, body: Partial<CreateChartRequest> & { version: number }) {
    const res = await api<{ data: Chart }>(`/api/v1/charts/${chartId}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function remove(chartId: number) {
    await api(`/api/v1/charts/${chartId}`, { method: 'DELETE' })
  }

  async function uploadPhoto(chartId: number, file: File, photoType: string) {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('photoType', photoType)
    const res = await api<{ data: { photoUrl: string } }>(`/api/v1/charts/${chartId}/photos`, {
      method: 'POST',
      body: formData,
    })
    return res.data
  }

  async function togglePin(chartId: number) {
    await api(`/api/v1/charts/${chartId}/pin`, { method: 'POST' })
  }

  async function share(chartId: number, shareWithClient: boolean) {
    await api(`/api/v1/charts/${chartId}/share`, {
      method: 'POST',
      body: { shareWithClient },
    })
  }

  async function listTemplates(teamId: number) {
    const res = await api<{ data: ChartTemplate[] }>(`/api/v1/teams/${teamId}/chart-templates`)
    return res.data
  }

  async function listMyCharts(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: Chart[]; meta: { totalElements: number } }>(
      `/api/v1/charts/me${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  return { list, get, create, update, remove, uploadPhoto, togglePin, share, listTemplates, listMyCharts }
}
