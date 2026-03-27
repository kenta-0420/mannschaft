import type { PerformanceMetric, PerformanceRecord, PerformanceStats, MemberPerformance } from '~/types/performance'

export function usePerformanceApi() {
  const api = useApi()

  async function getMetrics(teamId: number) {
    return api<{ data: PerformanceMetric[] }>(`/api/v1/teams/${teamId}/performance/metrics`)
  }

  async function createMetric(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceMetric }>(`/api/v1/teams/${teamId}/performance/metrics`, { method: 'POST', body })
  }

  async function updateMetric(teamId: number, metricId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceMetric }>(`/api/v1/teams/${teamId}/performance/metrics/${metricId}`, { method: 'PUT', body })
  }

  async function createRecord(teamId: number, body: Record<string, unknown>) {
    return api<{ data: PerformanceRecord }>(`/api/v1/teams/${teamId}/performance/records`, { method: 'POST', body })
  }

  async function bulkCreateRecords(teamId: number, records: Array<Record<string, unknown>>) {
    return api(`/api/v1/teams/${teamId}/performance/records/bulk`, { method: 'POST', body: { records } })
  }

  async function getTeamStats(teamId: number, params?: Record<string, unknown>) {
    const query = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) query.set(k, String(v)) }
    return api<{ data: PerformanceStats[] }>(`/api/v1/teams/${teamId}/performance/stats?${query}`)
  }

  async function getMemberPerformance(teamId: number, userId: number, params?: Record<string, unknown>) {
    const query = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) query.set(k, String(v)) }
    return api<{ data: MemberPerformance }>(`/api/v1/teams/${teamId}/members/${userId}/performance?${query}`)
  }

  async function getMyPerformance() {
    return api<{ data: MemberPerformance }>('/api/v1/performance/me')
  }

  return { getMetrics, createMetric, updateMetric, createRecord, bulkCreateRecords, getTeamStats, getMemberPerformance, getMyPerformance }
}
