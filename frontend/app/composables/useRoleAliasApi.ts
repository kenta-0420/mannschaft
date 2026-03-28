export interface RoleAliasResponse {
  roleName: string
  displayAlias: string
}

export interface AliasEntry {
  roleName: string
  displayAlias: string
}

export function useRoleAliasApi() {
  const api = useApi()

  async function getRoleAliases(teamId: number) {
    return api<{ data: RoleAliasResponse[] }>(`/api/v1/teams/${teamId}/role-aliases`)
  }

  async function updateRoleAliases(teamId: number, aliases: AliasEntry[]) {
    return api(`/api/v1/teams/${teamId}/role-aliases`, { method: 'PUT', body: { aliases } })
  }

  return {
    getRoleAliases,
    updateRoleAliases,
  }
}
