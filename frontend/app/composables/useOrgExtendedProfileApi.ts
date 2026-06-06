import type {
  OrganizationProfileResponse,
  UpdateOrgProfileRequest,
  OfficerResponse,
  CreateOfficerRequest,
  UpdateOfficerRequest,
  CustomFieldResponse,
  CreateCustomFieldRequest,
  UpdateCustomFieldRequest,
  ReorderRequest,
} from '~/types/organization'

export function useOrgExtendedProfileApi() {
  const api = useApi()

  // 拡張プロフィール
  async function getProfile(orgId: string) {
    return api<{ data: OrganizationProfileResponse }>(`/api/v1/organizations/${orgId}/profile`)
  }

  async function updateProfile(orgId: string, body: UpdateOrgProfileRequest) {
    return api<{ data: OrganizationProfileResponse }>(`/api/v1/organizations/${orgId}/profile`, {
      method: 'PATCH',
      body,
    })
  }

  // 役員
  async function getOfficers(orgId: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: OfficerResponse[] }>(`/api/v1/organizations/${orgId}/officers${query}`)
  }

  async function createOfficer(orgId: string, body: CreateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgId}/officers`, {
      method: 'POST',
      body,
    })
  }

  async function updateOfficer(orgId: string, officerId: number, body: UpdateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgId}/officers/${officerId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOfficer(orgId: string, officerId: number) {
    return api(`/api/v1/organizations/${orgId}/officers/${officerId}`, { method: 'DELETE' })
  }

  async function reorderOfficers(orgId: string, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgId}/officers/reorder`, { method: 'PUT', body })
  }

  // カスタムフィールド
  async function getCustomFields(orgId: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: CustomFieldResponse[] }>(`/api/v1/organizations/${orgId}/custom-fields${query}`)
  }

  async function createCustomField(orgId: string, body: CreateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgId}/custom-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateCustomField(orgId: string, fieldId: number, body: UpdateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgId}/custom-fields/${fieldId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteCustomField(orgId: string, fieldId: number) {
    return api(`/api/v1/organizations/${orgId}/custom-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderCustomFields(orgId: string, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgId}/custom-fields/reorder`, { method: 'PUT', body })
  }

  return {
    getProfile,
    updateProfile,
    getOfficers,
    createOfficer,
    updateOfficer,
    deleteOfficer,
    reorderOfficers,
    getCustomFields,
    createCustomField,
    updateCustomField,
    deleteCustomField,
    reorderCustomFields,
  }
}
