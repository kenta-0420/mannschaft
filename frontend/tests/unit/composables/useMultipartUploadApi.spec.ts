import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useMultipartUploadApi } = await import('~/composables/useMultipartUploadApi')

describe('useMultipartUploadApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ドメイン別開始 API の uploadId に対してパート URL を発行する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: { partUrls: [{ partNumber: 1, uploadUrl: 'https://r2.example.com/part' }], expiresIn: 600 },
    })

    const result = await useMultipartUploadApi().getPartUrls('upload-123', {
      file_key: 'blog/video.mp4',
      part_numbers: [1],
    })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/files/multipart/upload-123/part-url', {
      method: 'POST',
      body: { file_key: 'blog/video.mp4', part_numbers: [1] },
    })
    expect(result.partUrls).toHaveLength(1)
  })

  it('ドメイン別開始 API の uploadId を完了する', async () => {
    mockFetch.mockResolvedValueOnce({ data: { fileKey: 'blog/video.mp4', fileSize: 10 } })

    const result = await useMultipartUploadApi().completeUpload('upload-123', {
      file_key: 'blog/video.mp4',
      parts: [{ part_number: 1, etag: 'etag-1' }],
    })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/files/multipart/upload-123/complete', {
      method: 'POST',
      body: { file_key: 'blog/video.mp4', parts: [{ part_number: 1, etag: 'etag-1' }] },
    })
    expect(result.fileKey).toBe('blog/video.mp4')
  })

  it('ドメイン別開始 API の uploadId を中断する', async () => {
    mockFetch.mockResolvedValueOnce(undefined)

    await useMultipartUploadApi().abortUpload('upload-123')

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/files/multipart/upload-123', { method: 'DELETE' })
  })
})
