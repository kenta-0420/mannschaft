import type { RepairPlanTimelineResponse } from '~/types/repairPlanTimeline'

export function useRepairPlanTimelineApi() {
  const api = useApi()

  async function getTimeline(
    scope: string,
    scopeId: string,
    params?: { yearFrom?: number; yearTo?: number },
  ): Promise<RepairPlanTimelineResponse> {
    const query = new URLSearchParams()
    if (params?.yearFrom) query.set('yearFrom', String(params.yearFrom))
    if (params?.yearTo) query.set('yearTo', String(params.yearTo))
    const qs = query.toString() ? `?${query.toString()}` : ''
    const res = await api<{ data: RepairPlanTimelineResponse }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/timeline${qs}`,
    )
    return res.data
  }

  return { getTimeline }
}
