/**
 * F05.5 (B) 最低可視ロール。
 * 生成型 components["schemas"]["FolderResponse"]["minVisibleRole"] と一致させる。
 * 未指定（null/undefined）＝制限なし（所属者全員が閲覧可）。
 */
export type FileVisibilityRole = 'SUPPORTERS_AND_ABOVE' | 'MEMBERS_AND_ABOVE' | 'ADMINS_AND_ABOVE'

export interface SharedFolder {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION' | 'PERSONAL'
  scopeId: string
  parentId: number | null
  name: string
  description: string | null
  createdBy: { id: number; displayName: string } | null
  fileCount: number
  subfolderCount: number
  createdAt: string
  updatedAt: string
  /** F05.5 (B) 最低可視ロール。null＝制限なし */
  minVisibleRole?: FileVisibilityRole | null
  /** F05.5 (C) ダウンロード禁止フラグ */
  downloadDisabled?: boolean
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
  /** F05.5 (B) 最低可視ロール。null＝制限なし */
  minVisibleRole?: FileVisibilityRole | null
  /** F05.5 (C) ダウンロード禁止フラグ */
  downloadDisabled?: boolean
}

/**
 * F05.5 (D) 公開共有リンク。
 * 生成型 components["schemas"]["LinkResponse"] と一致させる。
 */
export interface PublicFileLink {
  id: number
  fileId: number
  token: string
  downloadAllowed: boolean
  hasPassword: boolean
  active: boolean
  accessCount: number
  expiresAt: string | null
  lastAccessedAt: string | null
  createdAt: string
}

/**
 * F05.5 (D) 未認証の公開閲覧ページ（/shared/{token}）で表示するファイルメタ。
 * 生成型 components["schemas"]["FileResponse"] と一致させる。
 */
export interface PublicSharedFileMeta {
  id?: number
  name?: string
  fileSize?: number
  contentType?: string
  description?: string | null
  downloadDisabled?: boolean
  createdAt?: string
  updatedAt?: string
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
