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

  // === Templates ===
  async function getTemplates(params?: Record<string, unknown>) {
    const qs = buildQuery(params || {})
    return api<{ data: ActivityTemplate[] }>(`/api/v1/activity-templates?${qs}`)
  }

  async function getTemplate(templateId: number) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${templateId}`)
  }

  async function createTemplate(body: Record<string, unknown>) {
    return api<{ data: ActivityTemplate }>('/api/v1/activity-templates', { method: 'POST', body })
  }

  async function updateTemplate(templateId: number, body: Record<string, unknown>) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${templateId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTemplate(templateId: number) {
    return api(`/api/v1/activity-templates/${templateId}`, { method: 'DELETE' })
  }

  async function duplicateTemplate(templateId: number) {
    return api<{ data: ActivityTemplate }>(`/api/v1/activity-templates/${templateId}/duplicate`, {
      method: 'POST',
    })
  }

  async function importPresetTemplate(body: Record<string, unknown>) {
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

  async function updateComment(activityId: number, commentId: number, body: string) {
    return api(`/api/v1/activities/${activityId}/comments/${commentId}`, {
      method: 'PUT',
      body: { body },
    })
  }

  async function deleteComment(activityId: number, commentId: number) {
    return api(`/api/v1/activities/${activityId}/comments/${commentId}`, { method: 'DELETE' })
  }

  // === Stats ===
  async function getStats(scopeType: string, scopeId: number) {
    return api<{ data: ActivityStats }>(
      `/api/v1/activities/stats?scope_type=${scopeType}&scope_id=${scopeId}`,
    )
  }

  async function getDashboardActivity() {
    return api('/api/v1/dashboard/activity')
  }

  async function getActivitySuggestions() {
    return api('/api/v1/matching/activity-suggestions')
  }

  return {
    getActivities,
    getActivity,
    createActivity,
    updateActivity,
    deleteActivity,
    getTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    duplicateTemplate,
    importPresetTemplate,
    getComments,
    addComment,
    updateComment,
    deleteComment,
    getStats,
    getDashboardActivity,
    getActivitySuggestions,
  }
}
