export interface OrgModuleItem {
  moduleId: number
  moduleName: string
  moduleSlug: string
  isEnabled: boolean
  enabledAt: string | null
}

export function useOrganizationModuleApi() {
  const api = useApi()

  async function getOrganizationModules(orgSlug: string): Promise<OrgModuleItem[]> {
    const res = await api<{ data: OrgModuleItem[] }>(`/api/v1/organizations/${orgSlug}/modules`)
    return res.data
  }

  async function toggleOrganizationModule(orgSlug: string, moduleId: number, enabled: boolean): Promise<void> {
    await api(`/api/v1/organizations/${orgSlug}/modules/${moduleId}/toggle`, {
      method: 'PATCH',
      body: { moduleId, enabled },
    })
  }

  return { getOrganizationModules, toggleOrganizationModule }
}
