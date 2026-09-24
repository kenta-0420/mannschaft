/** Multipart Upload パート URL 発行リクエスト */
export interface PartUrlRequest {
  file_key: string
  part_numbers: number[]
}

/** Multipart Upload パート URL */
export interface PresignedPartUrl {
  partNumber: number
  uploadUrl: string
}

/** Multipart Upload パート URL 発行レスポンス */
export interface PartUrlResponse {
  partUrls: PresignedPartUrl[]
  expiresIn: number
}

/** Multipart Upload 完了リクエストのパート情報 */
export interface CompletedPartInfo {
  part_number: number
  etag: string
}

/** Multipart Upload 完了リクエスト */
export interface CompleteMultipartRequest {
  file_key: string
  parts: CompletedPartInfo[]
}

/** Multipart Upload 完了レスポンス */
export interface CompleteMultipartResponse {
  fileKey: string
  fileSize: number
}
