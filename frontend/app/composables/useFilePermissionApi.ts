import type { CreatePermissionRequest, FilePermissionResponse } from '~/types/file-permission'

export function useFilePermissionApi() {
  const api = useApi()
  const base = '/api/v1/file-permissions'

  async function listPermissions() {
    return api<{ data: FilePermissionResponse[] }>(base)
  }

  async function createPermission(body: CreatePermissionRequest) {
    return api<{ data: FilePermissionResponse }>(base, { method: 'POST', body })
  }

  async function deletePermission(permissionId: number) {
    return api(`${base}/${permissionId}`, { method: 'DELETE' })
  }

  return {
    listPermissions,
    createPermission,
    deletePermission,
  }
}
