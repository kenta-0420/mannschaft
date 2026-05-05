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

/**
 * F13 Phase 5-a: ファイル共有 presign-upload リクエスト型。
 * サーバー側で新統一パス命名規則に従った fileKey を生成してもらう。
 */
export interface SharedFilePresignRequest {
  folderId: number
  fileName: string
  contentType: string
  fileSize: number
}

/**
 * F13 Phase 5-a: ファイル共有 presign-upload レスポンス型。
 * uploadUrl を使って R2 に直接 PUT し、完了後に fileKey を createFile API に渡す。
 */
export interface SharedFilePresignResponse {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}
