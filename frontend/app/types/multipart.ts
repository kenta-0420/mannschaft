/**
 * F05.5 Multipart Upload 型定義。
 * バックエンドの DTO（snake_case JSON プロパティ）に対応する TypeScript 型。
 */

/** Multipart Upload 開始リクエスト */
export interface StartMultipartUploadRequest {
  /** ファイルフォルダ ID（files 直接利用時のみ。他機能からの呼び出し時は不要） */
  folder_id?: number | null
  /** アップロードするファイル名 */
  file_name: string
  /** ファイルの MIME タイプ */
  content_type: string
  /** ファイルサイズ（バイト） */
  file_size: number
  /** パート数（1〜10000） */
  part_count: number
  /** パートサイズ（バイト、最小 5MB = 5,242,880） */
  part_size: number
  /**
   * アップロード先プレフィックス（他機能からの呼び出し時に指定）。
   * "blog/", "timeline/", "gallery/", "files/" など。
   * null の場合はバックエンドが "files/" を使用する。
   */
  target_prefix?: string | null
}

/** Multipart Upload 開始レスポンス */
export interface StartMultipartUploadResponse {
  /** R2 Multipart Upload ID */
  uploadId: string
  /** R2 オブジェクトキー */
  fileKey: string
  /** パート数 */
  partCount: number
  /** パートサイズ（バイト） */
  partSize: number
}

/** パート用 Presigned URL 発行リクエスト */
export interface PartUrlRequest {
  /** R2 オブジェクトキー */
  file_key: string
  /** Presigned URL を発行するパート番号のリスト（1〜10000） */
  part_numbers: number[]
}

/** パート番号と Presigned PUT URL のペア */
export interface PresignedPartUrl {
  /** パート番号（1〜10000） */
  partNumber: number
  /** Presigned PUT URL */
  uploadUrl: string
}

/** パート用 Presigned URL 発行レスポンス */
export interface PartUrlResponse {
  /** パート番号と Presigned URL のペアのリスト */
  partUrls: PresignedPartUrl[]
  /** URL の有効期限（秒）、固定 600 秒 */
  expiresIn: number
}

/** 完了済みパート情報（パート番号と ETag のペア） */
export interface CompletedPartInfo {
  /** パート番号（1〜10000） */
  part_number: number
  /** R2 が返した ETag ヘッダーの値 */
  etag: string
}

/** Multipart Upload 完了リクエスト */
export interface CompleteMultipartRequest {
  /** R2 オブジェクトキー */
  file_key: string
  /** 完了済みパートのリスト（パート番号と ETag） */
  parts: CompletedPartInfo[]
}

/** Multipart Upload 完了レスポンス */
export interface CompleteMultipartResponse {
  /** R2 オブジェクトキー */
  fileKey: string
  /** 最終ファイルサイズ（バイト）。R2 HeadObject で取得した実サイズ */
  fileSize: number
}
