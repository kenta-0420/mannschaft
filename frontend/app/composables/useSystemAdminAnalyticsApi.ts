const BASE = '/api/v1/system-admin/analytics'

export function useSystemAdminAnalyticsApi() {
  const api = useApi()

  async function getRevenueSummary() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/revenue/summary`)
  }

  async function getRevenueTrend(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    const qs = query.toString()
    return api<{ data: Record<string, unknown> }>(`${BASE}/revenue/trend${qs ? `?${qs}` : ''}`)
  }

  async function getRevenueBySource() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/revenue/by-source`)
  }

  async function getUsersTrend(params?: { from?: string; to?: string }) {
    const query = new URLSearchParams()
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    const qs = query.toString()
    return api<{ data: Record<string, unknown> }>(`${BASE}/users/trend${qs ? `?${qs}` : ''}`)
  }

  async function getChurnAnalysis() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/churn`)
  }

  async function getCohorts() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/cohorts`)
  }

  async function getModuleRanking() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/modules/ranking`)
  }

  async function getSegments() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/segments`)
  }

  async function getFunnel() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/funnel`)
  }

  async function getForecast() {
    return api<{ data: Record<string, unknown> }>(`${BASE}/forecast`)
  }

  async function getAlerts() {
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/alerts`)
  }

  async function getSnapshots() {
    return api<{ data: Record<string, unknown>[] }>(`${BASE}/snapshots`)
  }

  async function exportAnalytics(body: { type: string; from?: string; to?: string }) {
    return api<{ data: Record<string, unknown> }>(`${BASE}/export`, {
      method: 'POST',
      body,
    })
  }

  return {
    getRevenueSummary,
    getRevenueTrend,
    getRevenueBySource,
    getUsersTrend,
    getChurnAnalysis,
    getCohorts,
    getModuleRanking,
    getSegments,
    getFunnel,
    getForecast,
    getAlerts,
    getSnapshots,
    exportAnalytics,
  }
}
