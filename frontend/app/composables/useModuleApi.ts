export interface TeamModuleItem {
  moduleId: number
  moduleName: string
  moduleSlug: string
  isEnabled: boolean
  enabledAt: string | null
  trialExpiresAt: string | null
}

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
  async function getTeamModules(teamId: string) {
    return api<{ data: TeamModuleItem[] }>(`/api/v1/teams/${teamId}/modules`)
  }

  async function applyTemplate(teamId: string, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/modules/template`, { method: 'PUT', body })
  }

  async function toggleTeamModule(teamId: string, moduleId: number) {
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
