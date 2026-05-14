import type { MonitoringVisitCreateRequest, MonitoringVisitResponse } from '~/types/residenceStatus'

export function useMonitoringVisitApi() {
  const api = useApi()

  async function listVisitsByCommittee(orgId: number, committeeId: number) {
    return api<{ data: MonitoringVisitResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits?committeeId=${committeeId}`,
    )
  }

  async function listVisitsByResident(orgId: number, residentRegistryId: number) {
    return api<{ data: MonitoringVisitResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits?residentRegistryId=${residentRegistryId}`,
    )
  }

  async function createVisit(orgId: number, body: MonitoringVisitCreateRequest) {
    return api<{ data: MonitoringVisitResponse }>(
      `/api/v1/organizations/${orgId}/residence-status/monitoring-visits`,
      { method: 'POST', body },
    )
  }

  return { listVisitsByCommittee, listVisitsByResident, createVisit }
}
