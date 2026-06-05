export interface OrgModuleItem {
  moduleId: number
  moduleName: string
  moduleSlug: string
  isEnabled: boolean
  enabledAt: string | null
}

export function useOrganizationModuleApi() {
  const api = useApi()

  async function getOrganizationModules(orgId: string): Promise<OrgModuleItem[]> {
    const res = await api<{ data: OrgModuleItem[] }>(`/api/v1/organizations/${orgId}/modules`)
    return res.data
  }

  async function toggleOrganizationModule(orgId: string, moduleId: number, enabled: boolean): Promise<void> {
    await api(`/api/v1/organizations/${orgId}/modules/${moduleId}/toggle`, {
      method: 'PATCH',
      body: { moduleId, enabled },
    })
  }

  return { getOrganizationModules, toggleOrganizationModule }
}
