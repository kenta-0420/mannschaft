/**
 * 権限カタログ API クライアント（F01.2 02_api_design.md の `GET /api/v1/permissions`・スコープの ADMIN+）。
 *
 * 権限グループの作成・更新で選べる権限（id・コード・表示名・ADMIN 専用か）を返す。
 * 生成型（`types/generated`）は CI 成果物の差し替え待ちのため、一時的にここで局所型を定義する。
 * 生成後は `components['schemas']['PermissionCatalogItemResponse']` に寄せる。
 */
export interface PermissionCatalogItem {
  id: number
  /** 権限名（コード。例: MANAGE_ORG_AFFILIATION）。 */
  name: string
  displayName: string
  scope: string
  /** スコープの ADMIN だけが権限グループへ含められる権限か。 */
  adminOnlyGrantable: boolean
  /**
   * この権限を含められる権限グループの対象ロール（DEPUTY_ADMIN / MEMBER）。課金権限は DEPUTY_ADMIN のみ。
   * BE の作成・更新 API はこれに反する組合せを 400 で拒否する。旧 BE は返さない（undefined。制約なしとして扱う）。
   */
  assignableTargetRoles?: string[]
}

export function usePermissionCatalogApi() {
  const api = useApi()

  async function listPermissionCatalog(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: number): Promise<PermissionCatalogItem[]> {
    const res = await api<{ data?: PermissionCatalogItem[] }>(
      `/api/v1/permissions?scopeType=${scopeType}&scopeId=${scopeId}`,
    )
    return res.data ?? []
  }

  return { listPermissionCatalog }
}
