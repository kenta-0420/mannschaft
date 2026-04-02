import type {
  SharedFolder,
  SharedFile,
  FileVersion,
  FolderDetailResponse,
} from '~/types/filesharing'

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
    return api<{
      data: SharedFile[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/files?${qs}`)
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
    return api<{ data: SharedFile }>(`/api/v1/files/${fileId}`, { method: 'PATCH', body })
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

  // === File Meta Update (PATCH) ===
  async function patchFile(fileId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFile }>(`/api/v1/files/${fileId}`, { method: 'PATCH', body })
  }

  // === Comments ===
  async function getFileComments(fileId: number) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/files/${fileId}/comments`)
  }

  async function createFileComment(fileId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/files/${fileId}/comments`, {
      method: 'POST',
      body,
    })
  }

  async function deleteFileComment(fileId: number, commentId: number) {
    return api(`/api/v1/files/${fileId}/comments/${commentId}`, { method: 'DELETE' })
  }

  async function updateFileComment(
    fileId: number,
    commentId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/files/${fileId}/comments/${commentId}`, {
      method: 'PATCH',
      body,
    })
  }

  // === Sharing Links ===
  async function getFileLinks(fileId: number) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/files/${fileId}/links`)
  }

  async function createFileLink(fileId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/files/${fileId}/links`, {
      method: 'POST',
      body,
    })
  }

  async function deleteFileLink(fileId: number, linkId: number) {
    return api(`/api/v1/files/${fileId}/links/${linkId}`, { method: 'DELETE' })
  }

  // === Stars (Favorites) ===
  async function getFileStar(fileId: number) {
    return api<{ data: { starred: boolean } }>(`/api/v1/files/${fileId}/stars/me`)
  }

  async function addFileStar(fileId: number) {
    return api(`/api/v1/files/${fileId}/stars`, { method: 'POST' })
  }

  async function removeFileStar(fileId: number) {
    return api(`/api/v1/files/${fileId}/stars`, { method: 'DELETE' })
  }

  // === Tags ===
  async function getFileTags(fileId: number) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/files/${fileId}/tags`)
  }

  async function addFileTag(fileId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/files/${fileId}/tags`, {
      method: 'POST',
      body,
    })
  }

  async function removeFileTag(fileId: number, tagId: number) {
    return api(`/api/v1/files/${fileId}/tags/${tagId}`, { method: 'DELETE' })
  }

  // === Version (single) ===
  async function getFileVersion(fileId: number, versionNumber: number) {
    return api<{ data: FileVersion }>(`/api/v1/files/${fileId}/versions/${versionNumber}`)
  }

  // === Scoped Folders (Team) ===
  async function getTeamFolders(teamId: number) {
    return api<{ data: SharedFolder[] }>(`/api/v1/teams/${teamId}/folders`)
  }

  async function createTeamFolder(teamId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>(`/api/v1/teams/${teamId}/folders`, { method: 'POST', body })
  }

  async function getTeamFolder(teamId: number, folderId: number) {
    return api<FolderDetailResponse>(`/api/v1/teams/${teamId}/folders/${folderId}`)
  }

  async function updateTeamFolder(teamId: number, folderId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>(`/api/v1/teams/${teamId}/folders/${folderId}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTeamFolder(teamId: number, folderId: number) {
    return api(`/api/v1/teams/${teamId}/folders/${folderId}`, { method: 'DELETE' })
  }

  async function getTeamFolderChildren(teamId: number, folderId: number) {
    return api<{ data: SharedFolder[] }>(`/api/v1/teams/${teamId}/folders/${folderId}/children`)
  }

  // === Scoped Folders (Organization) ===
  async function getOrgFolders(organizationId: number) {
    return api<{ data: SharedFolder[] }>(`/api/v1/organizations/${organizationId}/folders`)
  }

  async function createOrgFolder(organizationId: number, body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>(`/api/v1/organizations/${organizationId}/folders`, {
      method: 'POST',
      body,
    })
  }

  // === Scoped Folders (Me) ===
  async function getMyFolders() {
    return api<{ data: SharedFolder[] }>('/api/v1/me/folders')
  }

  async function createMyFolder(body: Record<string, unknown>) {
    return api<{ data: SharedFolder }>('/api/v1/me/folders', { method: 'POST', body })
  }

  return {
    getFolders,
    getFolder,
    createFolder,
    updateFolder,
    deleteFolder,
    getFiles,
    getFile,
    getUploadUrl,
    registerFile,
    updateFile,
    deleteFile,
    getDownloadUrl,
    getVersions,
    uploadNewVersion,
    patchFile,
    getFileComments,
    createFileComment,
    deleteFileComment,
    updateFileComment,
    getFileLinks,
    createFileLink,
    deleteFileLink,
    getFileStar,
    addFileStar,
    removeFileStar,
    getFileTags,
    addFileTag,
    removeFileTag,
    getFileVersion,
    getTeamFolders,
    createTeamFolder,
    getTeamFolder,
    updateTeamFolder,
    deleteTeamFolder,
    getTeamFolderChildren,
    getOrgFolders,
    createOrgFolder,
    getMyFolders,
    createMyFolder,
  }
}
