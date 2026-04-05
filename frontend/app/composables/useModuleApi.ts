export function useModuleApi() {
  const api = useApi()

  // === モジュールカタログ ===
  async function getModuleCatalog() {
    return api<{ data: Array<Record<string, unknown>> }>('/api/v1/modules')
  }

  async function getModule(id: number) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/modules/${id}`)
  }

  // === チームモジュール管理 ===
  async function getTeamModules(teamId: number) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/teams/${teamId}/modules`)
  }

  async function applyTemplate(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/modules/template`, { method: 'PUT', body })
  }

  async function toggleTeamModule(teamId: number, moduleId: number) {
    return api(`/api/v1/teams/${teamId}/modules/${moduleId}/toggle`, { method: 'PATCH' })
  }

  return {
    getModuleCatalog,
    getModule,
    getTeamModules,
    applyTemplate,
    toggleTeamModule,
  }
}
