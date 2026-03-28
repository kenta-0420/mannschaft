import type { AnalyticsResponse } from '~/types/analytics'

export function useAnalyticsApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}`
      : `/api/v1/organizations/${scopeId}`
  }

  async function getAnalytics(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: { dateFrom?: string; dateTo?: string },
  ) {
    const query = new URLSearchParams()
    if (params?.dateFrom) query.set('dateFrom', params.dateFrom)
    if (params?.dateTo) query.set('dateTo', params.dateTo)
    const qs = query.toString()
    const base = buildBase(scopeType, scopeId)
    const res = await api<{ data: AnalyticsResponse }>(`${base}/analytics${qs ? `?${qs}` : ''}`)
    return res.data
  }

  return { getAnalytics }
}
