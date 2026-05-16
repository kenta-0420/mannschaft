export function useShiftConstraintApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  async function getWorkConstraints(teamId: number) {
    return api<{ data: unknown[] }>(`${BASE}/teams/${teamId}/work-constraints`)
  }

  async function upsertDefaultConstraint(teamId: number, req: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints`, {
      method: 'PUT',
      body: req,
    })
  }

  async function upsertMemberConstraint(
    teamId: number,
    userId: number,
    req: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints/${userId}`, {
      method: 'PUT',
      body: req,
    })
  }

  async function deleteMemberConstraint(teamId: number, userId: number) {
    return api(`${BASE}/teams/${teamId}/work-constraints/${userId}`, { method: 'DELETE' })
  }

  return {
    getWorkConstraints,
    upsertDefaultConstraint,
    upsertMemberConstraint,
    deleteMemberConstraint,
  }
}
