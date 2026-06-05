import type { ActivitySnapshotItem, ResidenceStatusDashboard } from '~/types/residenceStatus'

export function useActivitySnapshotApi() {
  const api = useApi()

  async function getDashboard(orgId: string) {
    return api<{ data: ResidenceStatusDashboard }>(
      `/api/v1/organizations/${orgId}/residence-status/dashboard`,
    )
  }

  async function getSnapshots(orgId: string, residentRegistryId: number) {
    return api<{ data: ActivitySnapshotItem[] }>(
      `/api/v1/organizations/${orgId}/residence-status/activity-snapshots/${residentRegistryId}`,
    )
  }

  return { getDashboard, getSnapshots }
}
