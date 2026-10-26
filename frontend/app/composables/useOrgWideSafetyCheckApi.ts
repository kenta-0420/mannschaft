import type { OrgWideSafetyCheckResponse } from '~/types/residenceStatus'

export function useOrgWideSafetyCheckApi() {
  const api = useApi()

  async function triggerSafetyCheck(orgSlug: string, triggerReason: string) {
    return api<{ data: OrgWideSafetyCheckResponse }>(
      `/api/v1/organizations/${orgSlug}/residence-status/org-wide-safety-checks`,
      { method: 'POST', body: { organizationId: orgSlug, triggerReason } },
    )
  }

  async function listActiveChecks(orgSlug: string) {
    return api<{ data: OrgWideSafetyCheckResponse[] }>(
      `/api/v1/organizations/${orgSlug}/residence-status/org-wide-safety-checks/active`,
    )
  }

  return { triggerSafetyCheck, listActiveChecks }
}
