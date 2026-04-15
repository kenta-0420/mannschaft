import type {
  TeamProfileResponse,
  UpdateTeamProfileRequest,
  TeamOfficerResponse,
  CreateTeamOfficerRequest,
  UpdateTeamOfficerRequest,
  TeamCustomFieldResponse,
  CreateTeamCustomFieldRequest,
  UpdateTeamCustomFieldRequest,
} from '~/types/team'
import type { ReorderRequest } from '~/types/organization'

export function useTeamExtendedProfileApi() {
  const api = useApi()

  // 拡張プロフィール
  async function updateProfile(teamId: number, body: UpdateTeamProfileRequest) {
    return api<{ data: TeamProfileResponse }>(`/api/v1/teams/${teamId}/profile`, {
      method: 'PATCH',
      body,
    })
  }

  // 役員
  async function getOfficers(teamId: number, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: TeamOfficerResponse[] }>(`/api/v1/teams/${teamId}/officers${query}`)
  }

  async function createOfficer(teamId: number, body: CreateTeamOfficerRequest) {
    return api<{ data: TeamOfficerResponse }>(`/api/v1/teams/${teamId}/officers`, {
      method: 'POST',
      body,
    })
  }

  async function updateOfficer(teamId: number, officerId: number, body: UpdateTeamOfficerRequest) {
    return api<{ data: TeamOfficerResponse }>(`/api/v1/teams/${teamId}/officers/${officerId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOfficer(teamId: number, officerId: number) {
    return api(`/api/v1/teams/${teamId}/officers/${officerId}`, { method: 'DELETE' })
  }

  async function reorderOfficers(teamId: number, body: ReorderRequest) {
    return api(`/api/v1/teams/${teamId}/officers/reorder`, { method: 'PUT', body })
  }

  // カスタムフィールド
  async function getCustomFields(teamId: number, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: TeamCustomFieldResponse[] }>(`/api/v1/teams/${teamId}/custom-fields${query}`)
  }

  async function createCustomField(teamId: number, body: CreateTeamCustomFieldRequest) {
    return api<{ data: TeamCustomFieldResponse }>(`/api/v1/teams/${teamId}/custom-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateCustomField(teamId: number, fieldId: number, body: UpdateTeamCustomFieldRequest) {
    return api<{ data: TeamCustomFieldResponse }>(`/api/v1/teams/${teamId}/custom-fields/${fieldId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteCustomField(teamId: number, fieldId: number) {
    return api(`/api/v1/teams/${teamId}/custom-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderCustomFields(teamId: number, body: ReorderRequest) {
    return api(`/api/v1/teams/${teamId}/custom-fields/reorder`, { method: 'PUT', body })
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
