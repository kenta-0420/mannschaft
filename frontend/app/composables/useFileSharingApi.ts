import type { SharedFolder, SharedFile, FileVersion, FolderDetailResponse } from '~/types/filesharing'

export function useFileSharingApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  // === Folders ===
  async function getFolders(scopeType: string, scopeId: number, parentId?: number) {
    const qs = buildQuery({ scope_type: scopeType, scope_id: scopeId, parent_id: parentId })
    return api<{ data: SharedFolder[] }>(`/api/v1/files/folders?${qs}`)
  }

  async function getFolder(folderId: number) {
    return api<FolderDetailResponse>(`/api/v1/files/folders/${folderId}`)
  }

  async function createFolder(body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>('/api/v1/files/folders', { method: 'POST', body })
  }

  async function updateFolder(folderId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>(`/api/v1/files/folders/${folderId}`, { method: 'PUT', body })
  }

  async function deleteFolder(folderId: number) {
    return api(`/api/v1/files/folders/${folderId}`, { method: 'DELETE' })
  }

  // === Files ===
  async function getFiles(params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: SharedFile[]; meta: { page: number; size: number; totalElements: number; totalPages: number } }>(`/api/v1/files?${qs}`)
  }

  async function getFile(fileId: number) {
    return api<{ data: SharedFile }>(`/api/v1/files/${fileId}`)
  }

  async function getUploadUrl(fileName: string, contentType: string, fileSize: number) {
    return api<{ data: { uploadUrl: string; fileKey: string } }>('/api/v1/files/upload-url', {
      method: 'POST',
      body: { fileName, contentType, fileSize },
    })
  }

  async function registerFile(body: Record<string, unknown>) {
    return api<{ data: SharedFile }>('/api/v1/files', { method: 'POST', body })
  }

  async function updateFile(fileId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFile }>(`/api/v1/files/${fileId}`, { method: 'PUT', body })
  }

  async function deleteFile(fileId: number) {
    return api(`/api/v1/files/${fileId}`, { method: 'DELETE' })
  }

  async function getDownloadUrl(fileId: number) {
    return api<{ data: { downloadUrl: string } }>(`/api/v1/files/${fileId}/download-url`)
  }

  // === Versions ===
  async function getVersions(fileId: number) {
    return api<{ data: FileVersion[] }>(`/api/v1/files/${fileId}/versions`)
  }

  async function uploadNewVersion(fileId: number, body: Record<string, unknown>) {
    return api<{ data: FileVersion }>(`/api/v1/files/${fileId}/versions`, { method: 'POST', body })
  }

  return {
    getFolders, getFolder, createFolder, updateFolder, deleteFolder,
    getFiles, getFile, getUploadUrl, registerFile, updateFile, deleteFile, getDownloadUrl,
    getVersions, uploadNewVersion,
  }
}
