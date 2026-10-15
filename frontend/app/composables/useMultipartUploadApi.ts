import type {
  PartUrlRequest,
  PartUrlResponse,
  CompleteMultipartRequest,
  CompleteMultipartResponse,
} from '~/types/multipart'

/**
 * ドメイン別開始 API が発行した uploadId の後続操作を提供する。
 */
export function useMultipartUploadApi() {
  const api = useApi()

  async function getPartUrls(uploadId: string, request: PartUrlRequest): Promise<PartUrlResponse> {
    const result = await api<{ data: PartUrlResponse }>(
      `/api/v1/files/multipart/${uploadId}/part-url`,
      { method: 'POST', body: request },
    )
    return result.data
  }

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

  async function abortUpload(uploadId: string): Promise<void> {
    await api(`/api/v1/files/multipart/${uploadId}`, { method: 'DELETE' })
  }

  return {
    getPartUrls,
    completeUpload,
    abortUpload,
  }
}
