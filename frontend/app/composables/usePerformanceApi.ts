import type {
  PerformanceMetric,
  PerformanceRecord,
  PerformanceStats,
  MemberPerformance,
} from '~/types/performance'

interface MetricTemplate {
  id: string
  name: string
  description: string
  fieldType: string
}

export function usePerformanceApi() {
  const api = useApi()

  function buildQuery(params?: Record<string, unknown>): string {
    const query = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) query.set(k, String(v))
      }
    return query.toString()
  }

  // === Metrics ===
  async function getMetrics(teamId: number) {
    return api<{ data: PerformanceMetric[] }>(`/api/v1/teams/${teamId}/performance/metrics`)
  }

  async function createMetric(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceMetric }>(`/api/v1/teams/${teamId}/performance/metrics`, {
      method: 'POST',
      body,
    })
  }

  async function createMetricFromTemplate(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceMetric }>(
      `/api/v1/teams/${teamId}/performance/metrics/from-template`,
      { method: 'POST', body },
    )
  }

  async function updateMetric(teamId: number, metricId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceMetric }>(
      `/api/v1/teams/${teamId}/performance/metrics/${metricId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteMetric(teamId: number, metricId: number) {
    return api(`/api/v1/teams/${teamId}/performance/metrics/${metricId}`, { method: 'DELETE' })
  }

  async function getLinkableFields(teamId: number) {
    return api(`/api/v1/teams/${teamId}/performance/metrics/linkable-fields`)
  }

  async function updateMetricSortOrder(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/performance/metrics/sort-order`, { method: 'PATCH', body })
  }

  async function getMetricTemplates() {
    return api<{ data: MetricTemplate[] }>('/api/v1/performance/metric-templates')
  }

  // === Records ===
  async function createRecord(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceRecord }>(`/api/v1/teams/${teamId}/performance/records`, {
      method: 'POST',
      body,
    })
  }

  async function createSelfRecord(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceRecord }>(`/api/v1/teams/${teamId}/performance/records/self`, {
      method: 'POST',
      body,
    })
  }

  async function updateRecord(teamId: number, recordId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceRecord }>(
      `/api/v1/teams/${teamId}/performance/records/${recordId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteRecord(teamId: number, recordId: number) {
    return api(`/api/v1/teams/${teamId}/performance/records/${recordId}`, { method: 'DELETE' })
  }

  async function bulkCreateRecords(teamId: number, records: Array<Record<string, unknown>>) {
    return api(`/api/v1/teams/${teamId}/performance/records/bulk`, {
      method: 'POST',
      body: { records },
    })
  }

  async function exportRecords(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/teams/${teamId}/performance/records/export?${qs}`)
  }

  // === Stats ===
  async function getTeamStats(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: PerformanceStats[] }>(`/api/v1/teams/${teamId}/performance/stats?${qs}`)
  }

  async function getMemberPerformance(
    teamId: number,
    userId: number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery(params)
    return api<{ data: MemberPerformance }>(
      `/api/v1/teams/${teamId}/members/${userId}/performance?${qs}`,
    )
  }

  async function getMyPerformance() {
    return api<{ data: MemberPerformance }>('/api/v1/performance/me')
  }

  async function getDashboardPerformance() {
    return api('/api/v1/dashboard/performance')
  }

  // === Activity / Schedule Performance ===
  async function getActivityPerformance(teamId: number, activityId: number) {
    return api(`/api/v1/teams/${teamId}/activities/${activityId}/performance`)
  }

  async function getSchedulePerformance(teamId: number, scheduleId: number) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance`)
  }

  async function bulkCreateScheduleRecords(
    teamId: number,
    scheduleId: number,
    records: Array<Record<string, unknown>>,
  ) {
    return api(`/api/v1/teams/${teamId}/schedules/${scheduleId}/performance/records/bulk`, {
      method: 'POST',
      body: { records },
    })
  }

  return {
    getMetrics,
    createMetric,
    createMetricFromTemplate,
    updateMetric,
    deleteMetric,
    getLinkableFields,
    updateMetricSortOrder,
    getMetricTemplates,
    createRecord,
    createSelfRecord,
    updateRecord,
    deleteRecord,
    bulkCreateRecords,
    exportRecords,
    getTeamStats,
    getMemberPerformance,
    getMyPerformance,
    getDashboardPerformance,
    getActivityPerformance,
    getSchedulePerformance,
    bulkCreateScheduleRecords,
  }
}
