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
  async function getProfile(orgSlug: string) {
    return api<{ data: OrganizationProfileResponse }>(`/api/v1/organizations/${orgSlug}/profile`)
  }

  async function updateProfile(orgSlug: string, body: UpdateOrgProfileRequest) {
    return api<{ data: OrganizationProfileResponse }>(`/api/v1/organizations/${orgSlug}/profile`, {
      method: 'PATCH',
      body,
    })
  }

  // 役員
  async function getOfficers(orgSlug: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: OfficerResponse[] }>(`/api/v1/organizations/${orgSlug}/officers${query}`)
  }

  async function createOfficer(orgSlug: string, body: CreateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgSlug}/officers`, {
      method: 'POST',
      body,
    })
  }

  async function updateOfficer(orgSlug: string, officerId: number, body: UpdateOfficerRequest) {
    return api<{ data: OfficerResponse }>(`/api/v1/organizations/${orgSlug}/officers/${officerId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOfficer(orgSlug: string, officerId: number) {
    return api(`/api/v1/organizations/${orgSlug}/officers/${officerId}`, { method: 'DELETE' })
  }

  async function reorderOfficers(orgSlug: string, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgSlug}/officers/reorder`, { method: 'PUT', body })
  }

  // カスタムフィールド
  async function getCustomFields(orgSlug: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: CustomFieldResponse[] }>(`/api/v1/organizations/${orgSlug}/custom-fields${query}`)
  }

  async function createCustomField(orgSlug: string, body: CreateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgSlug}/custom-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateCustomField(orgSlug: string, fieldId: number, body: UpdateCustomFieldRequest) {
    return api<{ data: CustomFieldResponse }>(`/api/v1/organizations/${orgSlug}/custom-fields/${fieldId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteCustomField(orgSlug: string, fieldId: number) {
    return api(`/api/v1/organizations/${orgSlug}/custom-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderCustomFields(orgSlug: string, body: ReorderRequest) {
    return api(`/api/v1/organizations/${orgSlug}/custom-fields/reorder`, { method: 'PUT', body })
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
