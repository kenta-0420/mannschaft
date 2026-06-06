import type { OrgWideSafetyCheckResponse } from '~/types/residenceStatus'

export function useOrgWideSafetyCheckApi() {
  const api = useApi()

  async function triggerSafetyCheck(orgId: string, triggerReason: string) {
    return api<{ data: OrgWideSafetyCheckResponse }>(
      `/api/v1/organizations/${orgId}/residence-status/org-wide-safety-checks`,
      { method: 'POST', body: { organizationId: orgId, triggerReason } },
    )
  }

  async function listActiveChecks(orgId: string) {
    return api<{ data: OrgWideSafetyCheckResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/org-wide-safety-checks/active`,
    )
  }

  return { triggerSafetyCheck, listActiveChecks }
}
