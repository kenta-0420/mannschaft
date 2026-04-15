import type { BlogMediaUploadUrlRequest, BlogMediaUploadUrlResponse } from '~/types/blogMedia'
import type { PartUrlRequest } from '~/types/multipart'

/** VIDEO アップロード時のパートサイズ（10MB 固定） */
const VIDEO_PART_SIZE = 10 * 1024 * 1024

/**
 * F06.1 ブログメディアアップロード API composable。
 * ブログ本文に埋め込む画像（Presigned PUT）・動画（Multipart Upload）のアップロードフローを提供する。
 */
export function useBlogMediaApi() {
  const api = useApi()
  const { getPartUrls, completeUpload, abortUpload } = useMultipartUploadApi()

  /**
   * ブログメディアのアップロード URL を発行する。
   * IMAGE → Presigned PUT URL。VIDEO → Multipart Upload 開始（uploadId / fileKey 返却）。
   *
   * @param request リクエスト情報
   * @returns URL 発行レスポンス
   */
  async function generateUploadUrl(
    request: BlogMediaUploadUrlRequest,
  ): Promise<BlogMediaUploadUrlResponse> {
    const result = await api<{ data: BlogMediaUploadUrlResponse }>(
      '/api/v1/blog/media/upload-url',
      { method: 'POST', body: request },
    )
    return result.data
  }

  /**
   * 画像ファイルをアップロードする。
   * generateUploadUrl で取得した Presigned PUT URL に fetch で直接 PUT する。
   *
   * @param params アップロードパラメータ
   * @returns mediaId と fileKey
   */
  async function uploadImage(params: {
    file: File
    scopeType: string
    scopeId: number
    blogPostId: number
  }): Promise<{ mediaId: number; fileKey: string }> {
    const { file, scopeType, scopeId, blogPostId } = params

    // 1. Presigned PUT URL を発行
    const urlResponse = await generateUploadUrl({
      media_type: 'IMAGE',
      content_type: file.type || 'image/jpeg',
      file_size: file.size,
      scope_type: scopeType,
      scope_id: scopeId,
      blog_post_id: blogPostId,
    })

    if (!urlResponse.upload_url) {
      throw new Error('画像用 Presigned URL の取得に失敗しました')
    }

    // 2. R2 に直接 PUT
    const response = await fetch(urlResponse.upload_url, {
      method: 'PUT',
      body: file,
      headers: {
        'Content-Type': file.type || 'image/jpeg',
      },
    })

    if (!response.ok) {
      throw new Error(
        `画像のアップロードに失敗しました: ${response.status} ${response.statusText}`,
      )
    }

    return {
      mediaId: urlResponse.media_id,
      fileKey: urlResponse.file_key,
    }
  }

  /**
   * 動画ファイルをアップロードする。
   * generateUploadUrl でバックエンドが Multipart Upload を開始し、
   * 返却された uploadId / fileKey を使ってパートアップロードを完了する。
   *
   * 内部処理:
   * 1. generateUploadUrl() → uploadId / fileKey 取得（バックエンドで Multipart Upload 開始済み）
   * 2. getPartUrls() でパート Presigned URL を取得
   * 3. 各パートを fetch で PUT（ETag 取得）
   * 4. completeUpload() でオブジェクトを組み立て
   * エラー/中断時: abortUpload() を呼んでからエラーを再 throw
   *
   * @param params アップロードパラメータ
   * @returns mediaId と fileKey
   */
  async function uploadVideo(params: {
    file: File
    scopeType: string
    scopeId: number
    blogPostId: number
    onProgress?: (progress: number) => void
    signal?: AbortSignal
  }): Promise<{ mediaId: number; fileKey: string }> {
    const { file, scopeType, scopeId, blogPostId, onProgress, signal } = params

    // 1. Multipart Upload 開始（バックエンド側で開始済み）
    const urlResponse = await generateUploadUrl({
      media_type: 'VIDEO',
      content_type: file.type || 'video/mp4',
      file_size: file.size,
      scope_type: scopeType,
      scope_id: scopeId,
      blog_post_id: blogPostId,
    })

    if (!urlResponse.upload_id || !urlResponse.file_key) {
      throw new Error('動画用 Multipart Upload の開始に失敗しました')
    }

    const uploadId = urlResponse.upload_id
    const fileKey = urlResponse.file_key
    // バックエンド推奨パートサイズを使用（なければ 10MB）
    const partSize = urlResponse.part_size ?? VIDEO_PART_SIZE
    const partCount = Math.ceil(file.size / partSize)

    try {
      // 2. パート Presigned URL を一括取得
      const partNumbers = Array.from({ length: partCount }, (_, i) => i + 1)
      const partUrlRequest: PartUrlRequest = {
        file_key: fileKey,
        part_numbers: partNumbers,
      }
      const partUrlResponse = await getPartUrls(uploadId, partUrlRequest)

      // 3. 各パートを並列アップロード
      let completedCount = 0
      const etags: Array<{ partNumber: number; etag: string }> = []

      await Promise.all(
        partUrlResponse.partUrls.map(async ({ partNumber, uploadUrl }) => {
          const start = (partNumber - 1) * partSize
          const end = Math.min(start + partSize, file.size)
          const chunk = file.slice(start, end)

          const response = await fetch(uploadUrl, {
            method: 'PUT',
            body: chunk,
            signal,
          })

          if (!response.ok) {
            throw new Error(
              `パート ${partNumber} のアップロードに失敗しました: ${response.status} ${response.statusText}`,
            )
          }

          const etag = response.headers.get('ETag') ?? response.headers.get('etag') ?? ''
          etags.push({ partNumber, etag })

          // 進捗通知（パート完了単位、0〜100）
          completedCount++
          onProgress?.(Math.round((completedCount / partCount) * 100))
        }),
      )

      // パート番号順にソート
      etags.sort((a, b) => a.partNumber - b.partNumber)

      // 4. Multipart Upload 完了
      await completeUpload(uploadId, {
        file_key: fileKey,
        parts: etags.map(({ partNumber, etag }) => ({
          part_number: partNumber,
          etag,
        })),
      })

      return {
        mediaId: urlResponse.media_id,
        fileKey,
      }
    }
    catch (error) {
      // エラー・AbortError いずれも abortUpload を呼んで後始末
      try {
        await abortUpload(uploadId)
      }
      catch {
        // abort 失敗は無視して元のエラーを再 throw
      }
      throw error
    }
  }

  return {
    generateUploadUrl,
    uploadImage,
    uploadVideo,
  }
}
