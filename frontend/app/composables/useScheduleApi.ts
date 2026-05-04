import type {
  AnnualScheduleParams,
  AnnualCopyPreviewParams,
  ExecuteCopyRequest,
  BulkAttendanceRequest,
  CrossInviteRequest,
  ScheduleBulkRecordRequest,
  ScheduleInvitationResponse,
  ScheduleStatsResponse,
} from '~/types/schedule'

export function useScheduleApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Shared Schedule CRUD ===
  async function listSchedules(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: {
      from?: string
      to?: string
      status?: string
      categoryId?: number
      page?: number
      size?: number
    },
  ) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.status) query.set('status', params.status)
    if (params?.categoryId) query.set('categoryId', String(params.categoryId))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 50))
    return api<{
      data: unknown[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`${buildBase(scopeType, scopeId)}/schedules?${query}`)
  }

  async function getSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`)
  }

  async function createSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules`, {
      method: 'POST',
      body,
    })
  }

  async function updateSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
    editScope?: string,
  ) {
    const query = editScope ? `?editScope=${editScope}` : ''
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}${query}`, {
      method: 'DELETE',
    })
  }

  async function cancelSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/cancel`, {
      method: 'POST',
    })
  }

  // === Attendance ===
  async function getAttendances(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
  ) {
    return api<{ data: unknown[] }>(
      `${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances`,
    )
  }

  async function respondAttendance(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
    body: { status: string; comment?: string },
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/me`, {
      method: 'PUT',
      body,
    })
  }

  async function exportAttendances(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/attendances/export`, {
      responseType: 'blob',
    })
  }

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

  // === Calendar View ===
  async function getCalendarMonth(
    year: number,
    month: number,
    scopeType?: string,
    scopeId?: number,
  ) {
    const pad = (n: number) => String(n).padStart(2, '0')
    const lastDay = new Date(year, month, 0).getDate()
    const from = `${year}-${pad(month)}-01T00:00:00`
    const to = `${year}-${pad(month)}-${pad(lastDay)}T23:59:59`
    if (scopeType === 'TEAM' && scopeId) {
      const query = new URLSearchParams()
      query.set('from', from)
      query.set('to', to)
      return api<{ data: unknown }>(`/api/v1/teams/${scopeId}/schedules?${query}`)
    }
    const query = new URLSearchParams()
    query.set('from', from)
    query.set('to', to)
    return api<{ data: unknown }>(`/api/v1/my/calendar?${query}`)
  }

  // === Event Categories ===
  async function getCategories(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/event-categories`)
  }

  async function createCategory(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: { name: string; color: string },
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/event-categories`, {
      method: 'POST',
      body,
    })
  }

  // === Bulk Attendance (teams only) ===
  async function bulkUpdateAttendances(
    teamId: number,
    scheduleId: number,
    body: BulkAttendanceRequest,
  ) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/attendances/bulk`, {
      method: 'PATCH',
      body,
    })
  }

  // === Duplicate ===
  async function duplicateSchedule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    scheduleId: number,
  ) {
    return api<{ data: unknown }>(
      `${buildBase(scopeType, scopeId)}/schedules/${scheduleId}/duplicate`,
      { method: 'POST' },
    )
  }

  // === Annual Schedule ===
  async function getAnnualSchedules(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: AnnualScheduleParams,
  ) {
    const query = new URLSearchParams()
    if (params?.academicYear) query.set('academic_year', String(params.academicYear))
    if (params?.categoryId) query.set('category_id', String(params.categoryId))
    if (params?.eventType) query.set('event_type', params.eventType)
    if (params?.termStartDate) query.set('term_start_date', params.termStartDate)
    if (params?.termEndDate) query.set('term_end_date', params.termEndDate)
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/schedules/annual?${query}`)
  }

  async function previewAnnualCopy(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params: AnnualCopyPreviewParams,
  ) {
    const query = new URLSearchParams()
    query.set('source_year', String(params.sourceYear))
    query.set('target_year', String(params.targetYear))
    if (params.dateShiftMode) query.set('date_shift_mode', params.dateShiftMode)
    if (params.categoryId) query.set('category_id', String(params.categoryId))
    return api<{ data: unknown[] }>(
      `${buildBase(scopeType, scopeId)}/schedules/annual/preview-copy?${query}`,
    )
  }

  async function executeAnnualCopy(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: ExecuteCopyRequest,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/annual/copy`, {
      method: 'POST',
      body,
    })
  }

  async function getAnnualCopyLogs(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/schedules/annual/copy-logs`)
  }

  // === Cross Invite (teams only) ===
  async function createCrossInvite(teamId: number, scheduleId: number, body: CrossInviteRequest) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/cross-invite`, {
      method: 'POST',
      body,
    })
  }

  async function deleteCrossInvite(teamId: number, scheduleId: number, invitationId: number) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/cross-invite/${invitationId}`, {
      method: 'DELETE',
    })
  }

  // === Performance (teams only) ===
  async function getSchedulePerformance(teamId: number, scheduleId: number) {
    return api<{ data: unknown }>(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance`)
  }

  async function bulkCreatePerformanceRecords(
    teamId: number,
    scheduleId: number,
    body: ScheduleBulkRecordRequest,
  ) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance/records/bulk`, {
      method: 'POST',
      body,
    })
  }

  // === Global Schedule Actions ===
  async function remindSchedule(scheduleId: number) {
    return api(`/api/v1/schedules/${scheduleId}/remind`, { method: 'POST' })
  }

  async function respondToSchedule(scheduleId: number, body: { status: string; comment?: string }) {
    return api(`/api/v1/schedules/${scheduleId}/responses`, { method: 'PATCH', body })
  }

  async function getScheduleStats(scheduleId: number) {
    return api<{ data: ScheduleStatsResponse }>(`/api/v1/schedules/${scheduleId}/stats`)
  }

  // === Schedule Invitations ===
  async function getScheduleInvitations(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: ScheduleInvitationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/schedule-invitations`,
    )
  }

  async function acceptScheduleInvitation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    invitationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-invitations/${invitationId}/accept`, {
      method: 'POST',
    })
  }

  async function rejectScheduleInvitation(
    scopeType: 'team' | 'organization',
    scopeId: number,
    invitationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/schedule-invitations/${invitationId}/reject`, {
      method: 'POST',
    })
  }

  async function confirmScheduleInvitation(teamId: number, invitationId: number) {
    return api(`/api/v1/teams/${teamId}/schedule-invitations/${invitationId}/confirm`, {
      method: 'POST',
    })
  }

  // === Attendance Stats ===
  async function getTeamAttendanceStats(teamId: number, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown }>(`/api/v1/teams/${teamId}/attendance-stats?${query}`)
  }

  async function exportTeamAttendanceStats(
    teamId: number,
    params?: { from?: string; to?: string },
  ) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api(`/api/v1/teams/${teamId}/attendance-stats/export?${query}`, { responseType: 'blob' })
  }

  async function getOrgAttendanceStats(orgId: number, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown }>(`/api/v1/organizations/${orgId}/attendance-stats?${query}`)
  }

  async function exportOrgAttendanceStats(orgId: number, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api(`/api/v1/organizations/${orgId}/attendance-stats/export?${query}`, {
      responseType: 'blob',
    })
  }

  async function getMyAttendanceStats(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown }>(`/api/v1/me/attendance-stats?${query}`)
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
    listSchedules,
    getSchedule,
    createSchedule,
    updateSchedule,
    deleteSchedule,
    cancelSchedule,
    getAttendances,
    respondAttendance,
    exportAttendances,
    bulkUpdateAttendances,
    listPersonalSchedules,
    createPersonalSchedule,
    updatePersonalSchedule,
    deletePersonalSchedule,
    getCalendarMonth,
    getCategories,
    createCategory,
    duplicateSchedule,
    getAnnualSchedules,
    previewAnnualCopy,
    executeAnnualCopy,
    getAnnualCopyLogs,
    createCrossInvite,
    deleteCrossInvite,
    getSchedulePerformance,
    bulkCreatePerformanceRecords,
    remindSchedule,
    respondToSchedule,
    getScheduleStats,
    getScheduleInvitations,
    acceptScheduleInvitation,
    rejectScheduleInvitation,
    confirmScheduleInvitation,
    getTeamAttendanceStats,
    exportTeamAttendanceStats,
    getOrgAttendanceStats,
    exportOrgAttendanceStats,
    getMyAttendanceStats,
    getMyCalendar,
    getMySchedules,
    getMyScheduleDetail,
    createMySchedule,
    updateMySchedule,
    deleteMySchedule,
    batchDeleteMySchedules,
  }
}
