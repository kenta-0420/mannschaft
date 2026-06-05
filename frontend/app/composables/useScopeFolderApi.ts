import type {
  ScopeFolder,
  CreateFolderRequest,
  UpdateFolderRequest,
} from '~/types/scopeFolder'

export function useScopeFolderApi() {
  const api = useApi()
  const base = '/api/v1/me/scope-folders'

  async function getFolders(scopeType: 'TEAM' | 'ORGANIZATION'): Promise<ScopeFolder[]> {
    const res = await api<{ data: ScopeFolder[] }>(`${base}?scopeType=${scopeType}`)
    return res.data
  }

  async function createFolder(
    scopeType: 'TEAM' | 'ORGANIZATION',
    req: CreateFolderRequest,
  ): Promise<ScopeFolder> {
    const res = await api<{ data: ScopeFolder }>(`${base}?scopeType=${scopeType}`, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  async function updateFolder(folderId: number, req: UpdateFolderRequest): Promise<ScopeFolder> {
    const res = await api<{ data: ScopeFolder }>(`${base}/${folderId}`, {
      method: 'PUT',
      body: req,
    })
    return res.data
  }

  async function deleteFolder(folderId: number): Promise<void> {
    await api(`${base}/${folderId}`, { method: 'DELETE' })
  }

  async function addItem(folderId: number, scopeId: string): Promise<void> {
    await api(`${base}/${folderId}/items`, {
      method: 'POST',
      body: { scopeId },
    })
  }

  async function removeItem(folderId: number, scopeId: string): Promise<void> {
    await api(`${base}/${folderId}/items/${scopeId}`, { method: 'DELETE' })
  }

  async function reorderFolders(
    scopeType: 'TEAM' | 'ORGANIZATION',
    orderedIds: number[],
  ): Promise<void> {
    await api(`${base}/reorder?scopeType=${scopeType}`, {
      method: 'PUT',
      body: { orderedIds },
    })
  }

  return {
    getFolders,
    createFolder,
    updateFolder,
    deleteFolder,
    addItem,
    removeItem,
    reorderFolders,
  }
}
