/**
 * F01.6 プロフィールメディア（アイコン・バナー管理）型定義。
 * バックエンドの ProfileMediaUploadUrlRequest / ProfileMediaResponse DTO に対応する。
 */

/** メディアロール */
export type ProfileMediaRole = 'icon' | 'banner'

/** スコープ種別 */
export type ProfileMediaScope = 'user' | 'team' | 'organization'

/**
 * アップロード URL 発行リクエスト。
 * POST /api/v1/{scope}/{scopeId}/profile-media/{role}/upload-url のリクエストボディ。
 * バックエンドに @JsonProperty がないため camelCase で定義する。
 */
export interface ProfileMediaUploadUrlRequest {
  /** ファイルの MIME タイプ（例: "image/jpeg", "image/png"） */
  contentType: string
  /** ファイルサイズ（バイト） */
  fileSize: number
}

/**
 * アップロード URL 発行レスポンス。
 * バックエンドに @JsonProperty がないため camelCase で受け取る。
 */
export interface ProfileMediaUploadUrlResponse {
  /** R2 オブジェクトキー */
  r2Key: string
  /** Presigned PUT URL */
  uploadUrl: string
  /** Presigned URL の有効秒数 */
  expiresIn: number
}

/**
 * コミット（DB 反映）リクエスト。
 * PUT /api/v1/{scope}/{scopeId}/profile-media/{role} のリクエストボディ。
 */
export interface ProfileMediaCommitRequest {
  /** R2 オブジェクトキー */
  r2Key: string
}

/**
 * コミット完了レスポンス。
 */
export interface ProfileMediaCommitResponse {
  /** メディアロール（"icon" または "banner"） */
  mediaRole: string
  /** 公開 CDN URL */
  url: string
}
