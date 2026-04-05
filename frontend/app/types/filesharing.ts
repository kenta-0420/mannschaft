export interface SharedFolder {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  parentId: number | null
  name: string
  description: string | null
  createdBy: { id: number; displayName: string } | null
  fileCount: number
  subfolderCount: number
  createdAt: string
  updatedAt: string
}

export interface SharedFile {
  id: number
  folderId: number
  fileName: string
  originalFileName: string
  fileSize: number
  mimeType: string
  description: string | null
  uploadedBy: { id: number; displayName: string } | null
  versionCount: number
  currentVersionId: number
  tags: string[]
  downloadCount: number
  createdAt: string
  updatedAt: string
}

export interface FileVersion {
  id: number
  fileId: number
  versionNumber: number
  fileSize: number
  uploadedBy: { id: number; displayName: string } | null
  comment: string | null
  createdAt: string
}

export interface FolderDetailResponse {
  data: SharedFolder & {
    subfolders: SharedFolder[]
    files: SharedFile[]
    breadcrumbs: Array<{ id: number; name: string }>
  }
}
