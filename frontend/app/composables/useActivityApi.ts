import type {
  ActivityRecordResponse,
  ActivityTemplate,
  ActivityComment,
  ActivityStats,
} from '~/types/activity'

export function useActivityApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  async function getActivities(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{
      data: ActivityRecordResponse[]
      meta: { nextCursor: number | null; hasNext: boolean }
    }>(`/api/v1/activities?${qs}`)
  }

  async function getActivity(id: number) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/activities/${id}`)
  }

  async function createActivity(body: Record<string, unknown>) {
    return api<{ data: ActivityRecordResponse }>('/api/v1/activities', { method: 'POST', body })
  }

  async function updateActivity(id: number, body: Record<string, unknown>) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/activities/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteActivity(id: number) {
    return api(`/api/v1/activities/${id}`, { method: 'DELETE' })
  }

  // === Activity Templates ===
  async function getTemplates(scopeType: string, scopeId: number) {
    return api<{ data: ActivityTemplate[] }>(
      `/api/v1/activity-templates?scope_type=${scopeType}&scope_id=${scopeId}`,
    )
  }

  async function createTemplate(body: Record<string, unknown>) {
    return api<{ data: ActivityTemplate }>('/api/v1/activity-templates', { method: 'POST', body })
  }

  async function getTemplate(id: number) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${id}`)
  }

  async function updateTemplate(id: number, body: Record<string, unknown>) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTemplate(id: number) {
    return api(`/api/v1/activity-templates/${id}`, { method: 'DELETE' })
  }

  async function duplicateTemplate(id: number) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${id}/duplicate`, {
      method: 'POST',
    })
  }

  async function importTemplatePreset(body: Record<string, unknown>) {
    return api<{ data: ActivityTemplate }>('/api/v1/activity-templates/import-preset', {
      method: 'POST',
      body,
    })
  }

  // === Comments ===
  async function getComments(activityId: number) {
    return api<{ data: ActivityComment[] }>(`/api/v1/activities/${activityId}/comments`)
  }

  async function addComment(activityId: number, body: string) {
    return api(`/api/v1/activities/${activityId}/comments`, { method: 'POST', body: { body } })
  }

  async function updateComment(
    activityId: number,
    commentId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ActivityComment }>(
      `/api/v1/activities/${activityId}/comments/${commentId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteComment(activityId: number, commentId: number) {
    return api(`/api/v1/activities/${activityId}/comments/${commentId}`, { method: 'DELETE' })
  }

  // === Public Activities ===
  async function listOrgPublicActivities(orgId: number) {
    return api<{ data: ActivityRecordResponse[] }>(
      `/api/v1/public/organizations/${orgId}/activities`,
    )
  }

  async function getOrgPublicActivity(orgId: number, id: number) {
    return api<{ data: ActivityRecordResponse }>(
      `/api/v1/public/organizations/${orgId}/activities/${id}`,
    )
  }

  async function listTeamPublicActivities(teamId: number) {
    return api<{ data: ActivityRecordResponse[] }>(`/api/v1/public/teams/${teamId}/activities`)
  }

  async function getTeamPublicActivity(teamId: number, id: number) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/public/teams/${teamId}/activities/${id}`)
  }

  async function getStats(scopeType: string, scopeId: number) {
    return api<{ data: ActivityStats }>(
      `/api/v1/activities/stats?scope_type=${scopeType}&scope_id=${scopeId}`,
    )
  }

  // === Export ===
  async function exportActivities(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<Blob>(`/api/v1/activities/export?${qs}`)
  }

  // === Stats Fields ===
  async function getStatsFields(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: Record<string, unknown> }>(`/api/v1/activities/stats/fields?${qs}`)
  }

  // === Duplicate ===
  async function duplicateActivity(id: number, body?: Record<string, unknown>) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/activities/${id}/duplicate`, {
      method: 'POST',
      body,
    })
  }

  // === Participants ===
  async function addParticipants(id: number, body: { userIds: number[] }) {
    return api(`/api/v1/activities/${id}/participants`, { method: 'POST', body })
  }

  async function removeParticipants(id: number, body: { userIds: number[] }) {
    return api(`/api/v1/activities/${id}/participants`, { method: 'DELETE', body })
  }

  return {
    getActivities,
    getActivity,
    createActivity,
    updateActivity,
    deleteActivity,
    getTemplates,
    getComments,
    addComment,
    getStats,
    exportActivities,
    getStatsFields,
    duplicateActivity,
    addParticipants,
    removeParticipants,
    createTemplate,
    getTemplate,
    updateTemplate,
    deleteTemplate,
    duplicateTemplate,
    importTemplatePreset,
    updateComment,
    deleteComment,
    listOrgPublicActivities,
    getOrgPublicActivity,
    listTeamPublicActivities,
    getTeamPublicActivity,
  }
}
