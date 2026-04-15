/**
 * F06.1 ブログメディアアップロード型定義。
 * バックエンドの BlogMediaUploadUrlRequest / BlogMediaUploadUrlResponse DTO に対応する。
 */

/** メディア種別 */
export type BlogMediaType = 'IMAGE' | 'VIDEO'

/** アップロード方式 */
export type BlogMediaUploadType = 'PRESIGNED' | 'MULTIPART'

/**
 * ブログメディアアップロード URL 発行リクエスト。
 * POST /api/v1/blog/media/upload-url のリクエストボディ。
 */
export interface BlogMediaUploadUrlRequest {
  /** メディア種別（"IMAGE" または "VIDEO"） */
  media_type: BlogMediaType
  /** ファイルの MIME タイプ（例: "image/jpeg", "video/mp4"） */
  content_type: string
  /** ファイルサイズ（バイト） */
  file_size: number
  /** スコープ種別（"TEAM", "ORGANIZATION", "PERSONAL"） */
  scope_type: string
  /** スコープ ID */
  scope_id: number
  /** 記事 ID（記事編集中に渡す。新規作成中は null） */
  blog_post_id: number | null
}

/**
 * ブログメディアアップロード URL 発行レスポンス。
 * IMAGE の場合は upload_url / expires_in が設定され、upload_id / part_size は null。
 * VIDEO の場合は upload_id / part_size が設定され、upload_url / expires_in は null。
 */
export interface BlogMediaUploadUrlResponse {
  /** blog_media_uploads.id */
  media_id: number
  /** メディア種別（"IMAGE" または "VIDEO"） */
  media_type: BlogMediaType
  /** R2 オブジェクトキー */
  file_key: string

  // === IMAGE のみ設定（VIDEO は null） ===
  /** Presigned PUT URL（IMAGE のみ） */
  upload_url: string | null
  /** Presigned URL の有効秒数（IMAGE のみ、600秒） */
  expires_in: number | null

  // === VIDEO のみ設定（IMAGE は null） ===
  /** Multipart Upload ID（VIDEO のみ） */
  upload_id: string | null
  /** 推奨パートサイズ（VIDEO のみ、10MB = 10_485_760） */
  part_size: number | null
}
