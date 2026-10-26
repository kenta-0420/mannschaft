/**
 * 年次計画 / パフォーマンス記録 / 出欠統計（チーム・組織・個人）。
 *
 * 提供する関数:
 * - 年次計画:        getAnnualSchedules / previewAnnualCopy / executeAnnualCopy / getAnnualCopyLogs
 * - パフォーマンス:  getSchedulePerformance / bulkCreatePerformanceRecords
 * - 出欠統計:        getTeamAttendanceStats / exportTeamAttendanceStats / getOrgAttendanceStats / exportOrgAttendanceStats / getMyAttendanceStats
 */
import type {
  AnnualScheduleParams,
  AnnualCopyPreviewParams,
  ExecuteCopyRequest,
  ScheduleBulkRecordRequest,
} from '~/types/schedule'

export function useScheduleAnalytics() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Annual Schedule ===
  async function getAnnualSchedules(
    scopeType: 'team' | 'organization',
    scopeId: string,
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
    scopeId: string,
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
    scopeId: string,
    body: ExecuteCopyRequest,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/schedules/annual/copy`, {
      method: 'POST',
      body,
    })
  }

  async function getAnnualCopyLogs(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: unknown[] }>(`${buildBase(scopeType, scopeId)}/schedules/annual/copy-logs`)
  }

  // === Performance (teams only) ===
  async function getSchedulePerformance(teamId: string, scheduleId: number) {
    return api<{ data: unknown }>(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance`)
  }

  async function bulkCreatePerformanceRecords(
    teamId: string,
    scheduleId: number,
    body: ScheduleBulkRecordRequest,
  ) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance/records/bulk`, {
      method: 'POST',
      body,
    })
  }

  // === Attendance Stats ===
  async function getTeamAttendanceStats(teamId: string, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown }>(`/api/v1/teams/${teamId}/attendance-stats?${query}`)
  }

  async function exportTeamAttendanceStats(
    teamId: string,
    params?: { from?: string; to?: string },
  ) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api(`/api/v1/teams/${teamId}/attendance-stats/export?${query}`, { responseType: 'blob' })
  }

  async function getOrgAttendanceStats(orgId: string, params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    return api<{ data: unknown }>(`/api/v1/organizations/${orgId}/attendance-stats?${query}`)
  }

  async function exportOrgAttendanceStats(orgId: string, params?: { from?: string; to?: string }) {
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

  return {
    getAnnualSchedules,
    previewAnnualCopy,
    executeAnnualCopy,
    getAnnualCopyLogs,
    getSchedulePerformance,
    bulkCreatePerformanceRecords,
    getTeamAttendanceStats,
    exportTeamAttendanceStats,
    getOrgAttendanceStats,
    exportOrgAttendanceStats,
    getMyAttendanceStats,
  }
}
