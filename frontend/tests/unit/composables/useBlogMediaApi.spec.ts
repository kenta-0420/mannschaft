import { describe, it, expect, beforeEach, vi, type MockedFunction } from 'vitest'

/**
 * F06.1 useBlogMediaApi のユニットテスト。
 *
 * テストケース:
 * 1. generateUploadUrl: 正しいエンドポイントにPOSTされること
 * 2. uploadImage: Presigned URLにPUTアップロードされること
 * 3. uploadVideo: getPartUrls → PUT → completeUpload が順番に呼ばれること
 * 4. uploadVideo エラー系: パートアップロード失敗時にabortUploadが呼ばれること
 */

// useApi のモック
const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

// useMultipartUploadApi のモック
const mockGetPartUrls = vi.fn()
const mockCompleteUpload = vi.fn()
const mockAbortUpload = vi.fn()
vi.mock('~/composables/useMultipartUploadApi', () => ({
  useMultipartUploadApi: () => ({
    getPartUrls: mockGetPartUrls,
    completeUpload: mockCompleteUpload,
    abortUpload: mockAbortUpload,
    startUpload: vi.fn(),
    uploadLargeFile: vi.fn(),
  }),
}))

// グローバル fetch のモック（R2への直接PUT用）
const mockGlobalFetch = vi.fn() as MockedFunction<typeof fetch>
vi.stubGlobal('fetch', mockGlobalFetch)

// テスト対象を動的 import（モック設定後に import する必要がある）
const { useBlogMediaApi } = await import('~/composables/useBlogMediaApi')

// --- ヘルパー ---

function makeGenerateUrlResponseImage() {
  return {
    data: {
      media_id: 100,
      media_type: 'IMAGE' as const,
      file_key: 'blog/images/test-image.jpg',
      upload_url: 'https://r2.example.com/presigned-put-url?sig=abc',
      expires_in: 600,
      upload_id: null,
      part_size: null,
    },
  }
}

function makeGenerateUrlResponseVideo() {
  return {
    data: {
      media_id: 200,
      media_type: 'VIDEO' as const,
      file_key: 'blog/videos/test-video.mp4',
      upload_url: null,
      expires_in: null,
      upload_id: 'multipart-upload-xyz',
      part_size: 10 * 1024 * 1024,
    },
  }
}

function makePartUrlResponse(partCount: number) {
  return {
    partUrls: Array.from({ length: partCount }, (_, i) => ({
      partNumber: i + 1,
      uploadUrl: `https://r2.example.com/upload?part=${i + 1}`,
    })),
    expiresIn: 600,
  }
}

function makeCompleteResponse() {
  return {
    fileKey: 'blog/videos/test-video.mp4',
    fileSize: 20 * 1024 * 1024,
  }
}

function makeSuccessFetchResponse(partNumber: number) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: new Headers({ ETag: `"etag-part-${partNumber}"` }),
  } as Response
}

function makeTestImageFile(): File {
  const buffer = new ArrayBuffer(1024 * 1024) // 1MB
  return new File([buffer], 'test-image.jpg', { type: 'image/jpeg' })
}

function makeTestVideoFile(): File {
  const buffer = new ArrayBuffer(20 * 1024 * 1024) // 20MB
  return new File([buffer], 'test-video.mp4', { type: 'video/mp4' })
}

describe('useBlogMediaApi', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
    mockGlobalFetch.mockReset()
    mockGetPartUrls.mockReset()
    mockCompleteUpload.mockReset()
    mockAbortUpload.mockReset()
  })

  // ===== generateUploadUrl テスト =====

  describe('generateUploadUrl', () => {
    it('POST /api/v1/blog/media/upload-url に正しいリクエストを送信すること', async () => {
      mockApiFetch.mockResolvedValueOnce(makeGenerateUrlResponseImage())

      const api = useBlogMediaApi()
      const result = await api.generateUploadUrl({
        media_type: 'IMAGE',
        content_type: 'image/jpeg',
        file_size: 1024 * 1024,
        scope_type: 'TEAM',
        scope_id: 1,
        blog_post_id: 42,
      })

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/blog/media/upload-url')
      expect(options.method).toBe('POST')
      const body = options.body as Record<string, unknown>
      expect(body.media_type).toBe('IMAGE')
      expect(body.scope_type).toBe('TEAM')
      expect(body.scope_id).toBe(1)
      expect(body.blog_post_id).toBe(42)

      // レスポンスが正しく返ること
      expect(result.media_id).toBe(100)
      expect(result.file_key).toBe('blog/images/test-image.jpg')
      expect(result.upload_url).toBe('https://r2.example.com/presigned-put-url?sig=abc')
    })
  })

  // ===== uploadImage テスト =====

  describe('uploadImage', () => {
    it('Presigned URLにPUTアップロードされること', async () => {
      // generateUploadUrl のモック
      mockApiFetch.mockResolvedValueOnce(makeGenerateUrlResponseImage())

      // R2 への直接 PUT のモック
      mockGlobalFetch.mockResolvedValueOnce({
        ok: true,
        status: 200,
        statusText: 'OK',
        headers: new Headers(),
      } as Response)

      const api = useBlogMediaApi()
      const file = makeTestImageFile()
      const result = await api.uploadImage({
        file,
        scopeType: 'TEAM',
        scopeId: 1,
        blogPostId: 42,
      })

      // generateUploadUrl が呼ばれること
      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/blog/media/upload-url')
      const body = options.body as Record<string, unknown>
      expect(body.media_type).toBe('IMAGE')

      // 取得した Presigned URL に PUT が送られること
      expect(mockGlobalFetch).toHaveBeenCalledTimes(1)
      const [fetchUrl, fetchOptions] = mockGlobalFetch.mock.calls[0] as [string, RequestInit]
      expect(fetchUrl).toBe('https://r2.example.com/presigned-put-url?sig=abc')
      expect(fetchOptions.method).toBe('PUT')

      // 戻り値の確認
      expect(result.mediaId).toBe(100)
      expect(result.fileKey).toBe('blog/images/test-image.jpg')
    })
  })

  // ===== uploadVideo テスト =====

  describe('uploadVideo', () => {
    it('getPartUrls → PUT → completeUpload が順番に呼ばれること', async () => {
      const file = makeTestVideoFile() // 20MB → 10MB パートサイズ → 2パート
      const onProgress = vi.fn()

      // generateUploadUrl のモック（Multipart 開始済み）
      mockApiFetch.mockResolvedValueOnce(makeGenerateUrlResponseVideo())

      // getPartUrls のモック
      mockGetPartUrls.mockResolvedValueOnce(makePartUrlResponse(2))

      // 各パートの PUT のモック（グローバル fetch）
      mockGlobalFetch
        .mockResolvedValueOnce(makeSuccessFetchResponse(1))
        .mockResolvedValueOnce(makeSuccessFetchResponse(2))

      // completeUpload のモック
      mockCompleteUpload.mockResolvedValueOnce(makeCompleteResponse())

      const api = useBlogMediaApi()
      const result = await api.uploadVideo({
        file,
        scopeType: 'TEAM',
        scopeId: 1,
        blogPostId: 42,
        onProgress,
      })

      // generateUploadUrl が VIDEO で呼ばれること
      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const body = (mockApiFetch.mock.calls[0] as [string, Record<string, unknown>])[1]
        .body as Record<string, unknown>
      expect(body.media_type).toBe('VIDEO')

      // getPartUrls が uploadId と fileKey で呼ばれること
      expect(mockGetPartUrls).toHaveBeenCalledTimes(1)
      const [calledUploadId, partUrlReq] = mockGetPartUrls.mock.calls[0] as [
        string,
        Record<string, unknown>,
      ]
      expect(calledUploadId).toBe('multipart-upload-xyz')
      expect(partUrlReq.file_key).toBe('blog/videos/test-video.mp4')

      // R2 へのパートPUT が2回呼ばれること
      expect(mockGlobalFetch).toHaveBeenCalledTimes(2)

      // completeUpload が呼ばれること
      expect(mockCompleteUpload).toHaveBeenCalledTimes(1)
      const [completeUploadId, completeReq] = mockCompleteUpload.mock.calls[0] as [
        string,
        Record<string, unknown>,
      ]
      expect(completeUploadId).toBe('multipart-upload-xyz')
      expect(completeReq.file_key).toBe('blog/videos/test-video.mp4')

      // 進捗コールバックが2回呼ばれること（2パート）
      expect(onProgress).toHaveBeenCalledTimes(2)
      const progressValues = onProgress.mock.calls.map((call) => call[0] as number)
      expect(progressValues).toContain(100)

      // 戻り値の確認
      expect(result.mediaId).toBe(200)
      expect(result.fileKey).toBe('blog/videos/test-video.mp4')
    })

    it('パートアップロード失敗時に abortUpload が呼ばれること', async () => {
      const file = makeTestVideoFile() // 20MB

      // generateUploadUrl のモック
      mockApiFetch.mockResolvedValueOnce(makeGenerateUrlResponseVideo())

      // getPartUrls のモック
      mockGetPartUrls.mockResolvedValueOnce(makePartUrlResponse(2))

      // パート2が失敗
      mockGlobalFetch
        .mockResolvedValueOnce(makeSuccessFetchResponse(1))
        .mockResolvedValueOnce({
          ok: false,
          status: 500,
          statusText: 'Internal Server Error',
          headers: new Headers(),
        } as Response)

      // abortUpload のモック
      mockAbortUpload.mockResolvedValueOnce(undefined)

      const api = useBlogMediaApi()

      await expect(
        api.uploadVideo({
          file,
          scopeType: 'TEAM',
          scopeId: 1,
          blogPostId: 42,
        }),
      ).rejects.toThrow()

      // abortUpload が uploadId で呼ばれること
      expect(mockAbortUpload).toHaveBeenCalledTimes(1)
      const [abortedUploadId] = mockAbortUpload.mock.calls[0] as [string]
      expect(abortedUploadId).toBe('multipart-upload-xyz')

      // completeUpload は呼ばれないこと
      expect(mockCompleteUpload).not.toHaveBeenCalled()
    })
  })
})
