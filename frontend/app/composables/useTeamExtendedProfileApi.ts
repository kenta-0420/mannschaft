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
  async function getProfile(teamId: string) {
    return api<{ data: TeamProfileResponse }>(`/api/v1/teams/${teamId}/profile`)
  }

  async function updateProfile(teamId: string, body: UpdateTeamProfileRequest) {
    return api<{ data: TeamProfileResponse }>(`/api/v1/teams/${teamId}/profile`, {
      method: 'PATCH',
      body,
    })
  }

  // 役員
  async function getOfficers(teamId: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: TeamOfficerResponse[] }>(`/api/v1/teams/${teamId}/officers${query}`)
  }

  async function createOfficer(teamId: string, body: CreateTeamOfficerRequest) {
    return api<{ data: TeamOfficerResponse }>(`/api/v1/teams/${teamId}/officers`, {
      method: 'POST',
      body,
    })
  }

  async function updateOfficer(teamId: string, officerId: number, body: UpdateTeamOfficerRequest) {
    return api<{ data: TeamOfficerResponse }>(`/api/v1/teams/${teamId}/officers/${officerId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteOfficer(teamId: string, officerId: number) {
    return api(`/api/v1/teams/${teamId}/officers/${officerId}`, { method: 'DELETE' })
  }

  async function reorderOfficers(teamId: string, body: ReorderRequest) {
    return api(`/api/v1/teams/${teamId}/officers/reorder`, { method: 'PUT', body })
  }

  // カスタムフィールド
  async function getCustomFields(teamId: string, visibilityPreview = false) {
    const query = visibilityPreview ? '?visibilityPreview=true' : ''
    return api<{ data: TeamCustomFieldResponse[] }>(`/api/v1/teams/${teamId}/custom-fields${query}`)
  }

  async function createCustomField(teamId: string, body: CreateTeamCustomFieldRequest) {
    return api<{ data: TeamCustomFieldResponse }>(`/api/v1/teams/${teamId}/custom-fields`, {
      method: 'POST',
      body,
    })
  }

  async function updateCustomField(teamId: string, fieldId: number, body: UpdateTeamCustomFieldRequest) {
    return api<{ data: TeamCustomFieldResponse }>(`/api/v1/teams/${teamId}/custom-fields/${fieldId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteCustomField(teamId: string, fieldId: number) {
    return api(`/api/v1/teams/${teamId}/custom-fields/${fieldId}`, { method: 'DELETE' })
  }

  async function reorderCustomFields(teamId: string, body: ReorderRequest) {
    return api(`/api/v1/teams/${teamId}/custom-fields/reorder`, { method: 'PUT', body })
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
