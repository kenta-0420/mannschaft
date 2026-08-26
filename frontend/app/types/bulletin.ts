export type BulletinScopeType = 'TEAM' | 'ORGANIZATION' | 'VILLAGE' | 'TOURNAMENT'
export type BulletinPriority = 'CRITICAL' | 'IMPORTANT' | 'WARNING' | 'INFO' | 'LOW'
export type ReadTrackingMode = 'NONE' | 'COUNT_ONLY' | 'SHOW_READERS'

export interface BulletinCategory {
  id: number
  scopeType: BulletinScopeType
  scopeId: string
  name: string
  description: string | null
  displayOrder: number
  color: string | null
  postMinRole: string
}

export interface BulletinThreadResponse {
  id: number
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  scopeType: BulletinScopeType
  scopeId: string
  author: { id: number; displayName: string; avatarUrl: string | null }
  title: string
  body: string
  priority: BulletinPriority
  readTrackingMode: ReadTrackingMode
  isPinned: boolean
  isLocked: boolean
  isArchived: boolean
  /**
   * 保管庫フォルダ ID（UUID 文字列）。
   * NULL かつ isArchived=true = 保管庫直下（未分類）。F05.1 保管庫フォルダ機能で追加。
   */
  archiveFolderId?: string | null
  replyCount: number
  readCount: number
  isRead: boolean
  reactionSummary: Record<string, number>
  myReactions: string[]
  lastRepliedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface BulletinReplyResponse {
  id: number
  threadId: number
  parentReplyId: number | null
  author: { id: number; displayName: string; avatarUrl: string | null }
  body: string
  depth: number
  reactionSummary: Record<string, number>
  myReactions: string[]
  children: BulletinReplyResponse[]
  createdAt: string
  updatedAt: string
}

export interface CreateBulletinThreadRequest {
  categoryId?: number
  title: string
  body: string
  priority?: BulletinPriority
  readTrackingMode?: ReadTrackingMode
}

export interface CreateBulletinReplyRequest {
  body: string
}

export interface BulletinReader {
  userId: number
  displayName: string
  avatarUrl: string | null
  readAt: string
}

export interface BulletinReadStatus {
  threadId: number
  isRead: boolean
  readAt: string | null
  totalReaders: number
  readCount: number
}

export interface BulletinReactionSummary {
  targetType: string
  targetId: number
  reactions: Record<string, number>
  myReactions: string[]
}

export interface CreateBulletinReactionRequest {
  targetType: string
  targetId: number
  emoji: string
}

export interface BulletinThreadSearchParams {
  keyword: string
  page?: number
  size?: number
}

// =====================================================================
// 保管庫（アーカイブ）フォルダ（F05.1 §4 / §5）
// =====================================================================

/**
 * 保管庫フォルダ（単一ノード）。
 *
 * Backend `ArchiveFolderResponse` と意味的に同一。ツリー取得時は `children` に
 * 子フォルダを再帰ネストする。単一作成・更新レスポンスでは `children` は空配列。
 */
export interface BulletinArchiveFolder {
  /** フォルダ UUID。 */
  id: string
  /** 親フォルダ UUID（null = ルート＝保管庫直下のトップレベル）。 */
  parentId: string | null
  name: string
  /** カラー（HEX #RRGGBB）。 */
  color: string | null
  /** アイコン（PrimeIcons 名）。 */
  icon: string | null
  /** ネスト深さ（0 = ルート、最大 4）。 */
  depth: number
  displayOrder: number
  /** 直下の子フォルダ数。 */
  childCount: number
  /** このフォルダ直下に所属するアーカイブ済みスレッド数。 */
  threadCount: number
  /** 子フォルダ（ツリー構造）。 */
  children: BulletinArchiveFolder[]
}

/**
 * 保管庫フォルダツリーの再帰ノード型エイリアス（設計書 §5 で用いる呼称）。
 * 構造は {@link BulletinArchiveFolder} と同一。
 */
export type ArchiveFolderTreeNode = BulletinArchiveFolder

/** 保管庫フォルダツリーのメタ情報（GET .../archive/folders の meta）。 */
export interface ArchiveFolderTreeMeta {
  /** 保管庫直下（archive_folder_id=NULL かつ is_archived=true）の未分類スレッド数。 */
  unfiledThreadCount: number
  /** アクティブなフォルダ総数。 */
  totalFolderCount: number
  /** ネスト最大階層（= 5）。 */
  maxDepth: number
  /** フォルダ数上限（= 200）。 */
  maxFolderCount: number
}

/** 保管庫フォルダツリーレスポンス（GET .../archive/folders）。 */
export interface ArchiveFolderTreeResponse {
  data: ArchiveFolderTreeNode[]
  meta: ArchiveFolderTreeMeta
}

/** 保管庫フォルダ作成リクエスト（POST .../archive/folders）。 */
export interface CreateArchiveFolderRequest {
  name: string
  /** 親フォルダ UUID（省略・null = 保管庫直下のルート）。 */
  parentFolderId?: string | null
  /** カラー（HEX #RRGGBB）。 */
  color?: string | null
  /** アイコン（PrimeIcons 名）。 */
  icon?: string | null
}

/**
 * 保管庫フォルダ更新・移動リクエスト（PUT .../archive/folders/{folderId}）。
 *
 * すべて任意。`parentFolderId` を指定すると移動（サブツリーごと）。
 * ルートへ移動する場合は `parentFolderId: null` を明示送信する。
 */
export interface UpdateArchiveFolderRequest {
  name?: string
  color?: string | null
  icon?: string | null
  displayOrder?: number
  parentFolderId?: string | null
}

/** スレッドのフォルダ振り分けリクエスト（PATCH .../archive/threads/{threadId}/folder）。 */
export interface MoveThreadFolderRequest {
  /** 移動先フォルダ UUID。null = 保管庫直下（未分類）。 */
  archiveFolderId: string | null
}

/** 保管庫フォルダ削除レスポンス（DELETE .../archive/folders/{folderId}）。 */
export interface DeleteArchiveFolderResponse {
  id: string
  deletedAt: string
  /** 保管庫直下（未分類）へ退避したスレッド件数。 */
  movedThreadCount: number
  /** 親へ繰り上げた子フォルダ件数。 */
  promotedFolderCount: number
  message: string
}

// =====================================================================
// 添付ファイル（F05.1 §6 / presigned URL 方式 A）
// =====================================================================

/** 添付対象の種別（BE の TargetType enum に対応）。 */
export type BulletinAttachmentTargetType = 'THREAD' | 'REPLY'

/**
 * 掲示板添付ファイル情報（BE AttachmentResponse に対応）。
 * download-url API 経由でファイルを取得する（生 fileKey は返却されない）。
 */
export interface BulletinAttachment {
  id: number
  targetType: BulletinAttachmentTargetType
  targetId: number
  fileKey: string
  originalFilename: string
  fileSize: number
  contentType: string
  createdBy: number
  createdAt: string
}

/**
 * presign-upload リクエスト（POST /api/v1/bulletin/attachments/upload-url）。
 * サーバー側でスコープ認可・サイズ・MIMEホワイトリスト検証を行う。
 */
export interface BulletinAttachmentPresignRequest {
  targetType: BulletinAttachmentTargetType
  targetId: number
  fileName: string
  contentType: string
  fileSize: number
}

/**
 * presign-upload レスポンス（BE AttachmentPresignResponse に対応）。
 * uploadUrl へブラウザから直接 PUT し、完了後に fileKey を確定 API に渡す。
 */
export interface BulletinAttachmentPresignResponse {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}

/**
 * 添付ファイル確定リクエスト（POST /api/v1/bulletin/attachments）。
 */
export interface BulletinAttachmentConfirmRequest {
  targetType: BulletinAttachmentTargetType
  targetId: number
  fileKey: string
  originalFilename: string
  fileSize: number
  contentType: string
}

/**
 * ダウンロード URL レスポンス（GET /api/v1/bulletin/attachments/{id}/download-url）。
 * 生 fileKey は返却されず、短命 TTL の presigned GET URL のみを返す。
 */
export interface BulletinAttachmentDownloadUrlResponse {
  downloadUrl: string
  expiresInSeconds: number
}
