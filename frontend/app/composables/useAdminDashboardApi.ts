export function useAdminDashboardApi() {
  const api = useApi()

  async function getDashboard(scopeType: 'team' | 'organization', scopeId: number) {
    const base = scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const res = await api<{ data: Record<string, unknown> }>(`${base}/admin/dashboard`)
    return res.data
  }

  async function getSystemDashboard() {
    const res = await api<{ data: Record<string, unknown> }>('/api/v1/system-admin/dashboard')
    return res.data
  }

  async function listModules(scopeType: 'team' | 'organization', scopeId: number) {
    const base = scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const res = await api<{
      data: { moduleId: number; moduleName: string; moduleSlug: string; isEnabled: boolean }[]
    }>(`${base}/modules`)
    return res.data.map((m) => ({
      moduleId: String(m.moduleId),
      name: m.moduleName,
      enabled: m.isEnabled,
    }))
  }

  async function toggleModule(
    scopeType: 'team' | 'organization',
    scopeId: number,
    moduleId: string,
    enabled: boolean,
  ) {
    const base = scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
    const id = Number(moduleId)
    await api(`${base}/modules/${id}/toggle`, { method: 'PATCH', body: { moduleId: id, enabled } })
  }

  return { getDashboard, getSystemDashboard, listModules, toggleModule }
}
