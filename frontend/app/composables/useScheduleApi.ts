export function useScheduleApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Shared Schedule CRUD ===
  async function listSchedules(scopeType: 'team' | 'organization', scopeId: number, params?: {
    from?: string; to?: string; status?: string; categoryId?: number; page?: number; size?: number
  }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.status) query.set('status', params.status)
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<{ data: unknown[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(
      `${buildBase(scopeType, scopeId)}/schedules?${query}`
    )
  }

  async function getSchedule(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`)
  }

  async function createSchedule(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules`, { method: 'POST', body })
  }

  async function updateSchedule(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`, { method: 'PATCH', body })
  }

  async function deleteSchedule(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number, editScope?: string) {
    const query = editScope ? `?editScope=${editScope}` : ''
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}${query}`, { method: 'DELETE' })
  }

  async function cancelSchedule(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/cancel`, { method: 'PATCH' })
  }

  // === Attendance ===
  async function getAttendances(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances`)
  }

  async function respondAttendance(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number, body: { status: string; comment?: string }) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/me`, { method: 'PUT', body })
  }

  async function exportAttendances(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/export`, { responseType: 'blob' })
  }

  // === Personal Schedule ===
  async function listPersonalSchedules(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown[] }>(`/api/v1/schedules/personal?${query}`)
  }

  async function createPersonalSchedule(body: Record<string, unknown>) {
    return api<{ data: unknown }>('/api/v1/schedules/personal', { method: 'POST', body })
  }

  async function updatePersonalSchedule(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`/api/v1/schedules/personal/${scheduleId}`, { method: 'PATCH', body })
  }

  async function deletePersonalSchedule(scheduleId: number) {
    return api(`/api/v1/schedules/personal/${scheduleId}`, { method: 'DELETE' })
  }

  // === Calendar View ===
  async function getCalendarMonth(year: number, month: number, scopeType?: string, scopeId?: number) {
    const query = new URLSearchParams()
    query.set('year', String(year))
    query.set('month', String(month))
    if (scopeType) query.set('scopeType', scopeType)
    if (scopeId) query.set('scopeId', String(scopeId))
    return api<{ data: unknown }>(`/api/v1/schedules/calendar?${query}`)
  }

  // === Event Categories ===
  async function getCategories(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/event-categories`)
  }

  async function createCategory(scopeType: 'team' | 'organization', scopeId: number, body: { name: string; color: string }) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/event-categories`, { method: 'POST', body })
  }

  // === Duplicate ===
  async function duplicateSchedule(scopeType: 'team' | 'organization', scopeId: number, scheduleId: number) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/duplicate`, { method: 'POST' })
  }

  return {
    listSchedules,
    getSchedule,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    cancelSchedule,
    getAttendances,
    respondAttendance,
    exportAttendances,
    listPersonalSchedules,
    createPersonalSchedule,
    updatePersonalSchedule,
    deletePersonalSchedule,
    getCalendarMonth,
    getCategories,
    createCategory,
    duplicateSchedule,
  }
}
