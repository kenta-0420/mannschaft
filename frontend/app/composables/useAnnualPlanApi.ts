import type {
  EventCategory,
  AnnualEventViewResponse,
  CopyPreview,
  CopyExecuteRequest,
  CopyExecuteResponse,
  CopyLog,
} from '~/types/annual-plan'

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
    body: { name: string; color: string; icon?: string; isDayOffCategory?: boolean; sortOrder?: number },
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
    params?: {
      academicYear?: number
      categoryIds?: number[]
      eventType?: string
      termStartDate?: string
      termEndDate?: string
    },
  ) {
    const base = buildBase(scopeType, scopeId)
    const query = new URLSearchParams()
    const currentAcademicYear =
      new Date().getMonth() >= 3 ? new Date().getFullYear() : new Date().getFullYear() - 1
    query.set('academic_year', String(params?.academicYear ?? currentAcademicYear))
    if (params?.categoryIds && params.categoryIds.length > 0) {
      params.categoryIds.forEach((id) => query.append('category_id', String(id)))
    }
    if (params?.eventType) query.set('event_type', params.eventType)
    if (params?.termStartDate) query.set('term_start_date', params.termStartDate)
    if (params?.termEndDate) query.set('term_end_date', params.termEndDate)
    const res = await api<{ data: AnnualEventViewResponse }>(
      `${base}/schedules/annual?${query.toString()}`,
    )
    return res.data
  }

  async function getCopyPreview(
    scopeType: 'team' | 'organization',
    scopeId: number,
    sourceYear: number,
    targetYear: number,
    dateShiftMode: string = 'SAME_WEEKDAY',
    categoryIds?: number[],
  ) {
    const base = buildBase(scopeType, scopeId)
    const query = new URLSearchParams({
      source_year: String(sourceYear),
      target_year: String(targetYear),
      date_shift_mode: dateShiftMode,
    })
    if (categoryIds && categoryIds.length > 0) {
      categoryIds.forEach((id) => query.append('category_id', String(id)))
    }
    const res = await api<{ data: CopyPreview }>(
      `${base}/schedules/annual/preview-copy?${query.toString()}`,
    )
    return res.data
  }

  async function executeCopy(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CopyExecuteRequest,
  ) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: CopyExecuteResponse }>(`${base}/schedules/annual/copy`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function getCopyLogs(scopeType: 'team' | 'organization', scopeId: number) {
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: CopyLog[] }>(`${base}/schedules/annual/copy-logs`)
    return res.data
  }

  async function updateCategory(
    categoryId: number,
    body: { name?: string; color?: string; icon?: string; isDayOffCategory?: boolean; sortOrder?: number },
  ) {
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
