export function useAdminReportApi() {
  const api = useApi()

  // === 権限グループ管理 ===
  async function getPermissionGroups() {
    return api<{
      data: Array<{
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }>
    }>('/api/v1/admin/permission-groups')
  }

  async function createPermissionGroup(body: {
    name: string
    description?: string
    permissions: string[]
  }) {
    return api<{
      data: {
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }
    }>('/api/v1/admin/permission-groups', { method: 'POST', body })
  }

  async function updatePermissionGroup(
    id: number,
    body: { name?: string; description?: string; permissions?: string[] },
  ) {
    return api<{
      data: {
        id: number
        name: string
        description: string | null
        permissions: string[]
        createdAt: string
      }
    }>(`/api/v1/admin/permission-groups/${id}`, { method: 'PUT', body })
  }

  async function deletePermissionGroup(id: number) {
    return api(`/api/v1/admin/permission-groups/${id}`, { method: 'DELETE' })
  }

  async function assignPermissionGroup(groupId: number, userId: number) {
    return api(`/api/v1/admin/permission-groups/${groupId}/assign/${userId}`, { method: 'PATCH' })
  }

  async function unassignPermissionGroup(groupId: number, userId: number) {
    return api(`/api/v1/admin/permission-groups/${groupId}/unassign/${userId}`, { method: 'PATCH' })
  }

  async function duplicatePermissionGroup(groupId: number) {
    return api<{ data: string }>(`/api/v1/admin/permission-groups/${groupId}/duplicate`, {
      method: 'POST',
    })
  }

  return {
    getPermissionGroups,
    createPermissionGroup,
    updatePermissionGroup,
    deletePermissionGroup,
    assignPermissionGroup,
    unassignPermissionGroup,
    duplicatePermissionGroup,
  }
}
