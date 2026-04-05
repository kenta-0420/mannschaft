import type {
  OrgBillingSettingsResponse,
  UpdateOrgBillingSettingsRequest,
  OrgBillingOrganizationResponse,
  OrgBillingType,
} from '~/types/org-billing'

const BASE = '/api/v1/system-admin/org-billing'

export function useOrgBillingApi() {
  const api = useApi()

  async function getSettings() {
    return api<{ data: OrgBillingSettingsResponse[] }>(`${BASE}/settings`)
  }

  async function updateSettings(orgType: OrgBillingType, body: UpdateOrgBillingSettingsRequest) {
    return api<{ data: OrgBillingSettingsResponse }>(`${BASE}/settings/${orgType}`, {
      method: 'PUT',
      body,
    })
  }

  async function getOrganizations(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: OrgBillingOrganizationResponse[]; meta: { total: number } }>(
      `${BASE}/organizations?${query}`,
    )
  }

  return {
    getSettings,
    updateSettings,
    getOrganizations,
  }
}
