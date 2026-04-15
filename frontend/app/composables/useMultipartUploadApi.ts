import type {
  StartMultipartUploadRequest,
  StartMultipartUploadResponse,
  PartUrlRequest,
  PartUrlResponse,
  CompleteMultipartRequest,
  CompleteMultipartResponse,
} from '~/types/multipart'

/** パートサイズ定数（バイト） */
const PART_SIZE_SMALL = 5 * 1024 * 1024 // 5MB：100MB 未満のファイル用
const PART_SIZE_MEDIUM = 10 * 1024 * 1024 // 10MB：1GB 未満のファイル用
const PART_SIZE_LARGE = 50 * 1024 * 1024 // 50MB：1GB 以上のファイル用

/**
 * ファイルサイズに応じて最適なパートサイズを決定する。
 *
 * @param fileSize ファイルサイズ（バイト）
 * @returns パートサイズ（バイト）
 */
function determinePartSize(fileSize: number): number {
  if (fileSize < 100 * 1024 * 1024) return PART_SIZE_SMALL
  if (fileSize < 1024 * 1024 * 1024) return PART_SIZE_MEDIUM
  return PART_SIZE_LARGE
}

/**
 * F05.5 Multipart Upload API composable。
 * 大容量ファイルの R2 Multipart Upload フロー（開始・パートURL発行・完了・中断）を提供する。
 */
export function useMultipartUploadApi() {
  const api = useApi()

  /**
   * Multipart Upload を開始する。
   * R2 で Multipart Upload セッションを作成し、uploadId と fileKey を返す。
   *
   * @param request 開始リクエスト
   * @returns 開始レスポンス（uploadId / fileKey / partCount / partSize）
   */
  async function startUpload(
    request: StartMultipartUploadRequest,
  ): Promise<StartMultipartUploadResponse> {
    const result = await api<{ data: StartMultipartUploadResponse }>(
      '/api/v1/files/multipart/start',
      { method: 'POST', body: request },
    )
    return result.data
  }

  /**
   * パート用 Presigned URL を発行する。
   * 指定したパート番号に対する Presigned PUT URL を一括発行する。
   *
   * @param uploadId Multipart Upload ID
   * @param request パートURL発行リクエスト
   * @returns パートURL発行レスポンス
   */
  async function getPartUrls(uploadId: string, request: PartUrlRequest): Promise<PartUrlResponse> {
    const result = await api<{ data: PartUrlResponse }>(
      `/api/v1/files/multipart/${uploadId}/part-url`,
      { method: 'POST', body: request },
    )
    return result.data
  }

  /**
   * Multipart Upload を完了する。
   * 全パートのアップロード後に呼び出し、R2 にオブジェクトを組み立てる。
   *
   * @param uploadId Multipart Upload ID
   * @param request 完了リクエスト（fileKey と全パートのETag）
   * @returns 完了レスポンス（fileKey / fileSize）
   */
  async function completeUpload(
    uploadId: string,
    request: CompleteMultipartRequest,
  ): Promise<CompleteMultipartResponse> {
    const result = await api<{ data: CompleteMultipartResponse }>(
      `/api/v1/files/multipart/${uploadId}/complete`,
      { method: 'POST', body: request },
    )
    return result.data
  }

  /**
   * Multipart Upload を中断する。
   * タイムアウトやユーザーキャンセル時に呼び出す。アップロード済みパートを破棄する。
   *
   * @param uploadId Multipart Upload ID
   */
  async function abortUpload(uploadId: string): Promise<void> {
    await api(`/api/v1/files/multipart/${uploadId}`, { method: 'DELETE' })
  }

  /**
   * 大容量ファイルのアップロードフロー全体を統合した高レベル API。
   *
   * 内部処理:
   * 1. ファイルサイズに応じてパートサイズを自動決定
   * 2. Multipart Upload セッションを開始
   * 3. 全パートの Presigned URL を一括取得
   * 4. 全パートを並列でアップロード（fetch で PUT）
   * 5. 各パート完了後に onProgress を呼び出し進捗を通知
   * 6. 完了 API を呼び出してオブジェクトを組み立て
   * エラー/中断時: abortUpload を呼んでからエラーを再 throw
   *
   * @param params アップロードパラメータ
   * @returns fileKey と fileSize
   */
  async function uploadLargeFile(params: {
    file: File
    targetPrefix: string
    onProgress?: (progress: number) => void
    signal?: AbortSignal
  }): Promise<{ fileKey: string; fileSize: number }> {
    const { file, targetPrefix, onProgress, signal } = params

    const partSize = determinePartSize(file.size)
    const partCount = Math.ceil(file.size / partSize)

    // 1. Multipart Upload セッション開始
    const startResponse = await startUpload({
      file_name: file.name,
      content_type: file.type || 'application/octet-stream',
      file_size: file.size,
      part_count: partCount,
      part_size: partSize,
      target_prefix: targetPrefix,
    })

    const { uploadId, fileKey } = startResponse

    try {
      // 2. 全パートの Presigned URL を一括取得
      const partNumbers = Array.from({ length: partCount }, (_, i) => i + 1)
      const partUrlResponse = await getPartUrls(uploadId, {
        file_key: fileKey,
        part_numbers: partNumbers,
      })

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

          // ETag を取得（R2 は ETag ヘッダーを返す）
          const etag = response.headers.get('ETag') ?? response.headers.get('etag') ?? ''

          etags.push({ partNumber, etag })

          // 進捗通知（完了パート数 / 総パート数 * 100）
          completedCount++
          onProgress?.(Math.round((completedCount / partCount) * 100))
        }),
      )

      // パート番号順にソート
      etags.sort((a, b) => a.partNumber - b.partNumber)

      // 4. Multipart Upload 完了
      const completeResponse = await completeUpload(uploadId, {
        file_key: fileKey,
        parts: etags.map(({ partNumber, etag }) => ({
          part_number: partNumber,
          etag,
        })),
      })

      return {
        fileKey: completeResponse.fileKey,
        fileSize: completeResponse.fileSize,
      }
    }
    catch (error) {
      // AbortError（ユーザーキャンセル）でも abortUpload を呼ぶ
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
    startUpload,
    getPartUrls,
    completeUpload,
    abortUpload,
    uploadLargeFile,
  }
}
