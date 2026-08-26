import type { components } from '~/types/generated'

/** チーム／組織の機能カタログ（生成型）。team/org で item の trialExpiresAt 有無が異なる。 */
export type ModuleCatalog =
  | components['schemas']['TeamModuleCatalog']
  | components['schemas']['OrgModuleCatalog']

/** カタログ要素（定義＋有効状態）。team/org のユニオン。 */
export type ModuleCatalogItem =
  | NonNullable<components['schemas']['TeamModuleCatalog']['modules']>[number]
  | NonNullable<components['schemas']['OrgModuleCatalog']['modules']>[number]

export function useAdminDashboardApi() {
  const api = useApi()

  function scopeBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  async function getDashboard(scopeType: 'team' | 'organization', scopeId: string) {
    const res = await api<{ data: Record<string, unknown> }>(
      `${scopeBase(scopeType, scopeId)}/admin/dashboard`,
    )
    return res.data
  }

  async function getSystemDashboard() {
    const res = await api<{ data: Record<string, unknown> }>('/api/v1/system-admin/dashboard')
    return res.data
  }

  async function listModules(scopeType: 'team' | 'organization', scopeId: string) {
    const res = await api<{
      data: { moduleId: number; moduleName: string; moduleSlug: string; isEnabled: boolean }[]
    }>(`${scopeBase(scopeType, scopeId)}/modules`)
    return res.data.map((m) => ({
      moduleId: String(m.moduleId),
      name: m.moduleName,
      enabled: m.isEnabled,
    }))
  }

  /**
   * 機能カタログ取得（全モジュール定義＋このスコープの有効状態）。
   * GET /api/v1/teams|organizations/{slug}/modules/catalog
   * team は ApiResponseTeamModuleCatalog、org は ApiResponseOrgModuleCatalog を返す。
   */
  async function getModuleCatalog(
    scopeType: 'team' | 'organization',
    scopeId: string,
  ): Promise<ModuleCatalog> {
    const res = await api<{ data: ModuleCatalog }>(
      `${scopeBase(scopeType, scopeId)}/modules/catalog`,
    )
    return res.data
  }

  async function toggleModule(
    scopeType: 'team' | 'organization',
    scopeId: string,
    moduleId: string,
    enabled: boolean,
  ) {
    const id = Number(moduleId)
    await api(`${scopeBase(scopeType, scopeId)}/modules/${id}/toggle`, {
      method: 'PATCH',
      body: { moduleId: id, enabled },
    })
  }

  return { getDashboard, getSystemDashboard, listModules, getModuleCatalog, toggleModule }
}
