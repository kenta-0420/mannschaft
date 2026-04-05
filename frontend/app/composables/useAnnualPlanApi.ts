import type { EventCategory, AnnualViewMonth, CopyPreview } from '~/types/annual-plan'

export function useAnnualPlanApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function listCategories(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: EventCategory[] }>(`${base}/event-categories`)
    return res.data
  }

  async function createCategory(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: { name: string; color: string },
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: EventCategory }>(`${base}/event-categories`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function getAnnualView(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: { year?: number; categoryId?: number; termId?: number },
  ) {
    const base = buildBase(scopeType, scopeId)
    const query = new URLSearchParams()
    if (params?.year) query.set('year', String(params.year))
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    if (params?.termId) query.set('termId', String(params.termId))
    const qs = query.toString()
    const res = await api<{ data: AnnualViewMonth[] }>(
      `${base}/schedules/annual${qs ? `?${qs}` : ''}`,
    )
    return res.data
  }

  async function getCopyPreview(
    scopeType: 'team' | 'organization',
    scopeId: number,
    sourceYear: number,
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: CopyPreview }>(
      `${base}/schedules/annual/preview-copy?sourceYear=${sourceYear}`,
    )
    return res.data
  }

  async function executeCopy(
    scopeType: 'team' | 'organization',
    scopeId: number,
    sourceYear: number,
  ) {
    const base = buildBase(scopeType, scopeId)
    await api(`${base}/schedules/annual/copy`, { method: 'POST', body: { sourceYear } })
  }

  async function getCopyLogs(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    return api(`${base}/schedules/annual/copy-logs`)
  }

  async function updateCategory(categoryId: number, body: { name?: string; color?: string }) {
    const res = await api<{ data: EventCategory }>(`/api/v1/event-categories/${categoryId}`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  async function deleteCategory(categoryId: number) {
    await api(`/api/v1/event-categories/${categoryId}`, { method: 'DELETE' })
  }

  return {
    listCategories,
    createCategory,
    updateCategory,
    deleteCategory,
    getAnnualView,
    getCopyPreview,
    executeCopy,
    getCopyLogs,
  }
}
