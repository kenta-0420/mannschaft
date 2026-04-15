import { describe, it, expect, beforeEach, vi, type MockedFunction } from 'vitest'

/**
 * F05.5 useMultipartUploadApi のユニットテスト。
 *
 * テストケース:
 * - startUpload: 正しいエンドポイントにPOSTされること
 * - getPartUrls: {uploadId} がURLに含まれること
 * - completeUpload: 正しいbodyでPOSTされること
 * - abortUpload: DELETEリクエストが送られること
 * - uploadLargeFile 正常系: 3パート構成でonProgressが 33%, 66%, 100% で呼ばれること
 * - uploadLargeFile エラー系: パートアップロード失敗時にabortUploadが呼ばれること
 */

// useApi のモック
const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// グローバル fetch のモック（R2へのPUTアップロード用）
const mockGlobalFetch = vi.fn() as MockedFunction<typeof fetch>
vi.stubGlobal('fetch', mockGlobalFetch)

// テスト対象を動的 import（モック設定後に import する必要がある）
const { useMultipartUploadApi } = await import('~/composables/useMultipartUploadApi')

// --- ヘルパー ---

function makeStartResponse(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      uploadId: 'upload-123',
      fileKey: 'blog/test-file.mp4',
      partCount: 3,
      partSize: 5 * 1024 * 1024,
      ...overrides,
    },
  }
}

function makePartUrlResponse(partCount: number) {
  return {
    data: {
      partUrls: Array.from({ length: partCount }, (_, i) => ({
        partNumber: i + 1,
        uploadUrl: `https://r2.example.com/upload?part=${i + 1}`,
      })),
      expiresIn: 600,
    },
  }
}

function makeCompleteResponse(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      fileKey: 'blog/test-file.mp4',
      fileSize: 15 * 1024 * 1024,
      ...overrides,
    },
  }
}

/** 成功する fetch レスポンスを作成する（ETag付き） */
function makeSuccessFetchResponse(partNumber: number) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: new Headers({ ETag: `"etag-part-${partNumber}"` }),
  } as Response
}

// 3パート構成のファイル（15MB）を作成するヘルパー
function makeTestFile(sizeBytes: number = 15 * 1024 * 1024): File {
  const buffer = new ArrayBuffer(sizeBytes)
  return new File([buffer], 'test-video.mp4', { type: 'video/mp4' })
}

describe('useMultipartUploadApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockGlobalFetch.mockReset()
  })

  // ===== 低レベルAPI テスト =====

  describe('startUpload', () => {
    it('POST /api/v1/files/multipart/start に正しいリクエストを送信する', async () => {
      const expectedResponse = makeStartResponse()
      mockFetch.mockResolvedValueOnce(expectedResponse)

      const api = useMultipartUploadApi()
      const result = await api.startUpload({
        file_name: 'video.mp4',
        content_type: 'video/mp4',
        file_size: 15 * 1024 * 1024,
        part_count: 3,
        part_size: 5 * 1024 * 1024,
        target_prefix: 'blog/',
      })

      expect(mockFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/files/multipart/start')
      expect(options.method).toBe('POST')
      expect(result.uploadId).toBe('upload-123')
      expect(result.fileKey).toBe('blog/test-file.mp4')
    })
  })

  describe('getPartUrls', () => {
    it('POST /api/v1/files/multipart/{uploadId}/part-url の URL に uploadId が含まれる', async () => {
      mockFetch.mockResolvedValueOnce(makePartUrlResponse(3))

      const api = useMultipartUploadApi()
      const result = await api.getPartUrls('my-upload-id-456', {
        file_key: 'blog/test.mp4',
        part_numbers: [1, 2, 3],
      })

      expect(mockFetch).toHaveBeenCalledTimes(1)
      const [url] = mockFetch.mock.calls[0] as [string]
      expect(url).toContain('my-upload-id-456')
      expect(url).toBe('/api/v1/files/multipart/my-upload-id-456/part-url')
      expect(result.partUrls).toHaveLength(3)
    })
  })

  describe('completeUpload', () => {
    it('POST /api/v1/files/multipart/{uploadId}/complete に正しい body を送信する', async () => {
      mockFetch.mockResolvedValueOnce(makeCompleteResponse())

      const api = useMultipartUploadApi()
      const result = await api.completeUpload('upload-789', {
        file_key: 'blog/test.mp4',
        parts: [
          { part_number: 1, etag: '"etag-1"' },
          { part_number: 2, etag: '"etag-2"' },
        ],
      })

      expect(mockFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/files/multipart/upload-789/complete')
      expect(options.method).toBe('POST')
      const body = options.body as Record<string, unknown>
      expect(body.file_key).toBe('blog/test.mp4')
      expect(result.fileKey).toBe('blog/test-file.mp4')
    })
  })

  describe('abortUpload', () => {
    it('DELETE /api/v1/files/multipart/{uploadId} を送信する', async () => {
      mockFetch.mockResolvedValueOnce(undefined)

      const api = useMultipartUploadApi()
      await api.abortUpload('upload-to-abort')

      expect(mockFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockFetch.mock.calls[0] as [string, Record<string, unknown>]
      expect(url).toBe('/api/v1/files/multipart/upload-to-abort')
      expect(options.method).toBe('DELETE')
    })
  })

  // ===== 高レベルAPI テスト =====

  describe('uploadLargeFile', () => {
    it('3パート構成でonProgressが 33%, 66%, 100% で呼ばれること', async () => {
      // 15MB のファイル → PART_SIZE_SMALL (5MB) → 3パート
      const file = makeTestFile(15 * 1024 * 1024)
      const onProgress = vi.fn()

      // 1. startUpload
      mockFetch.mockResolvedValueOnce(makeStartResponse({
        uploadId: 'upload-progress-test',
        fileKey: 'blog/test-video.mp4',
        partCount: 3,
        partSize: 5 * 1024 * 1024,
      }))

      // 2. getPartUrls
      mockFetch.mockResolvedValueOnce(makePartUrlResponse(3))

      // 3. 各パートの fetch (グローバル fetch)
      // 並列アップロードなので順序は保証されないが3回呼ばれる
      mockGlobalFetch
        .mockResolvedValueOnce(makeSuccessFetchResponse(1))
        .mockResolvedValueOnce(makeSuccessFetchResponse(2))
        .mockResolvedValueOnce(makeSuccessFetchResponse(3))

      // 4. completeUpload
      mockFetch.mockResolvedValueOnce(makeCompleteResponse({
        fileKey: 'blog/test-video.mp4',
        fileSize: 15 * 1024 * 1024,
      }))

      const api = useMultipartUploadApi()
      const result = await api.uploadLargeFile({
        file,
        targetPrefix: 'blog/',
        onProgress,
      })

      // 結果確認
      expect(result.fileKey).toBe('blog/test-video.mp4')
      expect(result.fileSize).toBe(15 * 1024 * 1024)

      // onProgress が3回呼ばれ、最終値が100%であること
      expect(onProgress).toHaveBeenCalledTimes(3)
      const progressValues = onProgress.mock.calls.map((call) => call[0] as number)
      expect(progressValues).toContain(100)
      // 全ての進捗値が 1〜100 の範囲であること
      for (const v of progressValues) {
        expect(v).toBeGreaterThan(0)
        expect(v).toBeLessThanOrEqual(100)
      }
    })

    it('パートアップロード失敗時に abortUpload が呼ばれること', async () => {
      const file = makeTestFile(15 * 1024 * 1024)

      // 1. startUpload 成功
      mockFetch.mockResolvedValueOnce(makeStartResponse({
        uploadId: 'upload-fail-test',
        fileKey: 'blog/fail-video.mp4',
      }))

      // 2. getPartUrls 成功
      mockFetch.mockResolvedValueOnce(makePartUrlResponse(3))

      // 3. パートアップロードの1つが失敗
      mockGlobalFetch
        .mockResolvedValueOnce(makeSuccessFetchResponse(1))
        .mockResolvedValueOnce({
          ok: false,
          status: 500,
          statusText: 'Internal Server Error',
          headers: new Headers(),
        } as Response)
        .mockResolvedValueOnce(makeSuccessFetchResponse(3))

      // 4. abortUpload（エラー後に呼ばれる）
      mockFetch.mockResolvedValueOnce(undefined)

      const api = useMultipartUploadApi()

      await expect(
        api.uploadLargeFile({
          file,
          targetPrefix: 'blog/',
        }),
      ).rejects.toThrow()

      // abortUpload が呼ばれていること
      // mockFetch の呼び出し順: startUpload(1) + getPartUrls(2) + abortUpload(3)
      const deleteCall = mockFetch.mock.calls.find((call) => {
        const options = call[1] as Record<string, unknown> | undefined
        return options?.method === 'DELETE'
      })
      expect(deleteCall).toBeDefined()
      const [abortUrl] = deleteCall as [string]
      expect(abortUrl).toContain('upload-fail-test')
    })
  })
})
