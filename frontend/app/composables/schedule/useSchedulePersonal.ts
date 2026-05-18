/**
 * 個人スケジュール（/me/schedules）と /my/calendar 関連 API。
 *
 * 提供する関数:
 * - 個人スケジュール（旧 API）: listPersonalSchedules / createPersonalSchedule / updatePersonalSchedule / deletePersonalSchedule
 * - 個人スケジュール（新 API）: getMySchedules / getMyScheduleDetail / createMySchedule / updateMySchedule / deleteMySchedule / batchDeleteMySchedules
 * - マイカレンダー:             getMyCalendar
 */
export function useSchedulePersonal() {
  const api = useApi()

  // === Personal Schedule ===
  async function listPersonalSchedules(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown[] }>(`/api/v1/me/schedules?${query}`)
  }

  async function createPersonalSchedule(body: Record<string, unknown>) {
    return api<{ data: unknown }>('/api/v1/me/schedules', { method: 'POST', body })
  }

  async function updatePersonalSchedule(scheduleId: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`/api/v1/me/schedules/${scheduleId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deletePersonalSchedule(scheduleId: number) {
    return api(`/api/v1/me/schedules/${scheduleId}`, { method: 'DELETE' })
  }

  // === My Calendar ===
  async function getMyCalendar(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown[] }>(`/api/v1/my/calendar?${query}`)
  }

  // === Me Schedules ===
  async function getMySchedules(params?: {
    from?: string
    to?: string
    q?: string
    eventType?: string
    cursor?: string
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.q) query.set('q', params.q)
    if (params?.eventType) query.set('eventType', params.eventType)
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    return api<{ data: unknown[] }>(`/api/v1/me/schedules?${query}`)
  }

  async function getMyScheduleDetail(id: number) {
    return api<{ data: unknown }>(`/api/v1/me/schedules/${id}`)
  }

  async function createMySchedule(body: Record<string, unknown>) {
    return api<{ data: unknown }>('/api/v1/me/schedules', { method: 'POST', body })
  }

  async function updateMySchedule(id: number, body: Record<string, unknown>) {
    return api<{ data: unknown }>(`/api/v1/me/schedules/${id}`, { method: 'PATCH', body })
  }

  async function deleteMySchedule(id: number, updateScope?: string) {
    const query = updateScope ? `?updateScope=${updateScope}` : ''
    return api(`/api/v1/me/schedules/${id}${query}`, { method: 'DELETE' })
  }

  async function batchDeleteMySchedules(ids: number[]) {
    return api('/api/v1/me/schedules/batch', { method: 'DELETE', body: { ids } })
  }

  return {
    listPersonalSchedules,
    createPersonalSchedule,
    updatePersonalSchedule,
    deletePersonalSchedule,
    getMyCalendar,
    getMySchedules,
    getMyScheduleDetail,
    createMySchedule,
    updateMySchedule,
    deleteMySchedule,
    batchDeleteMySchedules,
  }
}
