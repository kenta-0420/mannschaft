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
  async function updateProfile(orgId: number, body: UpdateOrgProfileRequest) {
    return api<{ data: OrganizationProfileResponse }>(`/api/v1/organizations/${orgId}/profile`, {
      method: 'PATCH',
      body,
    })
  }

  // 役員
  async function getOfficers(orgId: number, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: OfficerResponse[] }>(`/api/v1/organizations/${orgId}/officers${query}`)
  }

  async function createOfficer(orgId: number, body: CreateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgId}/officers`, {
      method: 'POST',
      body,
    })
  }

  async function updateOfficer(orgId: number, officerId: number, body: UpdateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgId}/officers/${officerId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOfficer(orgId: number, officerId: number) {
    return api(`/api/v1/organizations/${orgId}/officers/${officerId}`, { method: 'DELETE' })
  }

  async function reorderOfficers(orgId: number, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgId}/officers/reorder`, { method: 'PUT', body })
  }

  // カスタムフィールド
  async function getCustomFields(orgId: number, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: CustomFieldResponse[] }>(`/api/v1/organizations/${orgId}/custom-fields${query}`)
  }

  async function createCustomField(orgId: number, body: CreateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgId}/custom-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateCustomField(orgId: number, fieldId: number, body: UpdateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgId}/custom-fields/${fieldId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteCustomField(orgId: number, fieldId: number) {
    return api(`/api/v1/organizations/${orgId}/custom-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderCustomFields(orgId: number, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgId}/custom-fields/reorder`, { method: 'PUT', body })
  }

  return {
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
