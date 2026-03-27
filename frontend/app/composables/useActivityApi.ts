import type { ActivityRecordResponse, ActivityTemplate, ActivityComment, ActivityStats } from '~/types/activity'

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
    return api<{ data: ActivityRecordResponse[]; meta: { nextCursor: number | null; hasNext: boolean } }>(`/api/v1/activities?${qs}`)
  }

  async function getActivity(id: number) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/activities/${id}`)
  }

  async function createActivity(body: Record<string, unknown>) {
    return api<{ data: ActivityRecordResponse }>('/api/v1/activities', { method: 'POST', body })
  }

  async function updateActivity(id: number, body: Record<string, unknown>) {
    return api<{ data: ActivityRecordResponse }>(`/api/v1/activities/${id}`, { method: 'PUT', body })
  }

  async function deleteActivity(id: number) {
    return api(`/api/v1/activities/${id}`, { method: 'DELETE' })
  }

  async function getTemplates(scopeType: string, scopeId: number) {
    return api<{ data: ActivityTemplate[] }>(`/api/v1/activity-templates?scope_type=${scopeType}&scope_id=${scopeId}`)
  }

  async function getComments(activityId: number) {
    return api<{ data: ActivityComment[] }>(`/api/v1/activities/${activityId}/comments`)
  }

  async function addComment(activityId: number, body: string) {
    return api(`/api/v1/activities/${activityId}/comments`, { method: 'POST', body: { body } })
  }

  async function getStats(scopeType: string, scopeId: number) {
    return api<{ data: ActivityStats }>(`/api/v1/activities/stats?scope_type=${scopeType}&scope_id=${scopeId}`)
  }

  return { getActivities, getActivity, createActivity, updateActivity, deleteActivity, getTemplates, getComments, addComment, getStats }
}
