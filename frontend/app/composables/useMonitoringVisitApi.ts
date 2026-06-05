import type { MonitoringVisitCreateRequest, MonitoringVisitResponse } from '~/types/residenceStatus'

export function useMonitoringVisitApi() {
  const api = useApi()

  async function listVisitsByCommittee(orgId: string, committeeId: number) {
    return api<{ data: MonitoringVisitResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits?committeeId=${committeeId}`,
    )
  }

  async function listVisitsByResident(orgId: string, residentRegistryId: number) {
    return api<{ data: MonitoringVisitResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits?residentRegistryId=${residentRegistryId}`,
    )
  }

  async function createVisit(orgId: string, body: MonitoringVisitCreateRequest) {
    return api<{ data: MonitoringVisitResponse }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits`,
      { method: 'POST', body },
    )
  }

  return { listVisitsByCommittee, listVisitsByResident, createVisit }
}
