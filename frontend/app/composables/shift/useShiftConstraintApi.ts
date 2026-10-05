export function useShiftConstraintApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts'

  async function getWorkConstraints(teamId: string) {
    return api<{ data: unknown[] }>(`${BASE}/teams/${teamId}/work-constraints`)
  }

  async function upsertDefaultConstraint(teamId: string, req: Record<string, unknown>) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints/default`, {
      method: 'PUT',
      body: req,
    })
  }

  async function upsertMemberConstraint(
    teamId: string,
    userId: number,
    req: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${BASE}/teams/${teamId}/work-constraints/members/${userId}`, {
      method: 'PUT',
      body: req,
    })
  }

  async function deleteMemberConstraint(teamId: string, userId: number) {
    return api(`${BASE}/teams/${teamId}/work-constraints/members/${userId}`, { method: 'DELETE' })
  }

  return {
    getWorkConstraints,
    upsertDefaultConstraint,
    upsertMemberConstraint,
    deleteMemberConstraint,
  }
}
