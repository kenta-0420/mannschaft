import type { AuditLog, AuditLogParams } from '~/types/audit-log'

export function useAuditLogApi() {
  const api = useApi()

  function buildQuery(params?: AuditLogParams) {
    const query = new URLSearchParams()
    if (params?.userId) query.set('userId', String(params.userId))
    if (params?.targetUserId) query.set('targetUserId', String(params.targetUserId))
    if (params?.eventType) query.set('eventType', params.eventType)
    if (params?.eventCategory) query.set('eventCategory', params.eventCategory)
    if (params?.from) query.set('from', params.from)
    if (params?.to) query.set('to', params.to)
    if (params?.page != null) query.set('page', String(params.page))
    if (params?.size != null) query.set('size', String(params.size))
    return query.toString()
  }

  async function listAll(params?: AuditLogParams) {
    const qs = buildQuery(params)
    const res = await api<{ data: AuditLog[]; meta: { totalElements: number } }>(
      `/api/v1/admin/audit-logs${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function listByTeam(teamId: number, params?: AuditLogParams) {
    const qs = buildQuery(params)
    const res = await api<{ data: AuditLog[]; meta: { totalElements: number } }>(
      `/api/v1/teams/${teamId}/audit-logs${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function listByOrg(orgId: number, params?: AuditLogParams) {
    const qs = buildQuery(params)
    const res = await api<{ data: AuditLog[]; meta: { totalElements: number } }>(
      `/api/v1/organizations/${orgId}/audit-logs${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  return { listAll, listByTeam, listByOrg }
}
